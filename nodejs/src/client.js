/** Asynchronous, reconnecting Camera Control protocol client. */

import { randomUUID } from 'node:crypto';

import {
  ApiError,
  ConnectionInterrupted,
  ProtocolError,
  TimeoutError,
  isPermanent,
} from './errors.js';
import { openWebSocket } from './socket.js';

/** The only Camera Control protocol this client speaks. */
export const API_PROTOCOL_VERSION = 4;

export const TERMINAL_JOB_STATES = Object.freeze(['completed', 'failed', 'cancelled']);
export const TERMINAL_SESSION_STATES = Object.freeze(['completed', 'failed', 'cancelled']);

/**
 * One authenticated Camera Control connection.
 *
 * Requests are serialized. Status and capture-session polling reconnect
 * automatically. A capture job uses a stable `requestId` when it is replayed
 * after a dropped socket so the running server can deduplicate it.
 */
export class CameraControlClient {
  #url;
  #password;
  #caFile;
  #timeout;
  #reconnectAttempts;
  #reconnectDelay;
  #tlsOptions;
  #connectFactory;
  #channel = null;
  #events = [];
  #requestNumber = 0;
  #pending = null;

  /**
   * @param {string} url wss:// endpoint shown by the app.
   * @param {string} password shared Web/API password.
   * @param {string} [caFile] verified Camera Control local CA certificate.
   */
  constructor(url, password, caFile, {
    timeout = 30_000,
    reconnectAttempts = 5,
    reconnectDelay = 1_000,
    tlsOptions = undefined,
    connectFactory = openWebSocket,
  } = {}) {
    if (new URL(url).protocol !== 'wss:') {
      throw new Error('Camera Control requires a wss:// URL');
    }
    if (!(timeout > 0)) throw new Error('timeout must be positive');
    if (reconnectAttempts < 0) throw new Error('reconnectAttempts cannot be negative');
    this.#url = url;
    this.#password = password;
    this.#caFile = caFile;
    this.#timeout = timeout;
    this.#reconnectAttempts = reconnectAttempts;
    this.#reconnectDelay = reconnectDelay;
    this.#tlsOptions = tlsOptions;
    this.#connectFactory = connectFactory;
  }

  /** Whether a socket is currently retained. */
  get connected() {
    return this.#channel !== null;
  }

  /** Open, validate protocol v4, and authenticate one WSS connection. */
  async connect() {
    this.close();
    this.#channel = await this.#connectFactory(this.#url, {
      caFile: this.#caFile,
      tlsOptions: this.#tlsOptions,
      openTimeoutMs: Math.min(this.#timeout, 10_000),
    });
    try {
      const hello = await this.#receive(this.#timeout);
      if (hello.event !== 'hello' || hello.protocolVersion !== API_PROTOCOL_VERSION) {
        throw new ProtocolError(`Unsupported server greeting: ${JSON.stringify(hello)}`);
      }
      const authenticated = await this.#requestOnce(
        'authenticate',
        { password: this.#password },
        this.#timeout,
      );
      if (authenticated.protocolVersion !== API_PROTOCOL_VERSION) {
        throw new ProtocolError(
          `Authentication returned an unsupported protocol: ${JSON.stringify(authenticated)}`,
        );
      }
    } catch (error) {
      this.close();
      throw error;
    }
  }

  /** Close the current socket without discarding queued events. */
  close() {
    const channel = this.#channel;
    this.#channel = null;
    channel?.close();
  }

  /**
   * Send one action and return its result object.
   *
   * Pass `retryOnDisconnect: false` for non-idempotent operations such as the
   * legacy direct `capture` action.
   */
  async request(action, parameters = {}, { retryOnDisconnect = true, timeout } = {}) {
    const attempts = retryOnDisconnect ? this.#reconnectAttempts : 0;
    let lastError;
    for (let attempt = 0; attempt <= attempts; attempt += 1) {
      try {
        if (this.#channel === null) await this.connect();
        return await this.#requestOnce(action, parameters, timeout ?? this.#timeout);
      } catch (error) {
        if (isPermanent(error)) throw error;
        lastError = error;
        this.close();
        if (attempt >= attempts) break;
        await delay(Math.min(this.#reconnectDelay * 2 ** attempt, 10_000));
      }
    }
    throw new ConnectionInterrupted(
      `Connection interrupted while performing '${action}'`,
      lastError,
    );
  }

  /** Camera, device, capture-session, and reliability status. */
  async status() {
    return this.request('status');
  }

  /** Capture once through the idempotent job API and await completion. */
  async captureJob({
    camera = 1,
    resolution = 'high',
    flash = 'off',
    controls = {},
    requestId = undefined,
  } = {}) {
    const stableRequestId = requestId ?? `node-${randomUUID()}`;
    const parameters = {
      requestId: stableRequestId,
      camera,
      resolution,
      flash,
      controls,
    };
    for (let resume = 0; resume <= this.#reconnectAttempts; resume += 1) {
      const result = await this.request('captureJob', parameters);
      const job = asObject(result.job, 'captureJob result.job');
      if (TERMINAL_JOB_STATES.includes(job.state)) return job;
      const jobId = job.id;
      try {
        const event = await this.waitForEvent('captureJob', {
          predicate: (candidate) =>
            isObject(candidate.job) &&
            candidate.job.id === jobId &&
            TERMINAL_JOB_STATES.includes(candidate.job.state),
        });
        return asObject(event.job, 'captureJob event.job');
      } catch (error) {
        if (!(error instanceof ConnectionInterrupted)) throw error;
        // Re-submit the same semantic request ID. The running app
        // deduplicates it and returns the retained job snapshot.
        if (resume >= this.#reconnectAttempts) throw error;
      }
    }
    throw new ConnectionInterrupted('Capture job recovery attempts exhausted');
  }

  /** Start a time-lapse capture session and return its snapshot. */
  async startTimeLapse({
    intervalSeconds,
    count = undefined,
    camera = 1,
    resolution = 'high',
    flash = 'off',
    controls = {},
  }) {
    const plan = { mode: 'timeLapse', intervalSeconds };
    if (count !== undefined && count !== null) plan.count = count;
    const result = await this.request(
      'startCaptureSession',
      { camera, resolution, flash, controls, plan },
      { retryOnDisconnect: false },
    );
    return asObject(result.session, 'startCaptureSession result.session');
  }

  /** Poll the latest retained capture session across reconnects. */
  async captureSessionStatus(sessionId) {
    const status = await this.status();
    const session = status.captureSession;
    if (!isObject(session) || session.id !== sessionId) {
      throw new ProtocolError('The capture session is no longer retained by the server');
    }
    return session;
  }

  /** Cancel one retained time-lapse/capture session. */
  async cancelCaptureSession(sessionId) {
    const result = await this.request('cancelCaptureSession', { sessionId });
    return asObject(result.session, 'cancelCaptureSession result.session');
  }

  /** Return the next matching unsolicited event. */
  async waitForEvent(eventName, { predicate = undefined, timeout = undefined } = {}) {
    const matches = (event) =>
      event.event === eventName && (predicate === undefined || predicate(event));
    const index = this.#events.findIndex(matches);
    if (index >= 0) return this.#events.splice(index, 1)[0];

    const deadline = Date.now() + (timeout ?? this.#timeout);
    for (;;) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        throw new ConnectionInterrupted(`Timed out waiting for '${eventName}'`);
      }
      let message;
      try {
        message = await this.#receive(remaining);
      } catch (error) {
        if (isPermanent(error)) throw error;
        this.close();
        throw new ConnectionInterrupted(
          `Connection interrupted while waiting for '${eventName}'`,
          error,
        );
      }
      if (!('event' in message)) {
        throw new ProtocolError(`Unexpected response while waiting: ${JSON.stringify(message)}`);
      }
      if (matches(message)) return message;
      this.#events.push(message);
    }
  }

  async #requestOnce(action, parameters, timeout) {
    const channel = this.#channel;
    if (channel === null) {
      throw new ConnectionInterrupted('No active Camera Control connection');
    }
    // Serialize requests so that one response can never be matched twice.
    const previous = this.#pending ?? Promise.resolve();
    const current = previous
      .catch(() => {})
      .then(() => this.#exchange(channel, action, parameters, timeout));
    this.#pending = current.catch(() => {});
    return current;
  }

  async #exchange(channel, action, parameters, timeout) {
    this.#requestNumber += 1;
    const requestId = `node-${this.#requestNumber}`;
    channel.send(JSON.stringify({ id: requestId, action, ...parameters }));

    const deadline = Date.now() + timeout;
    for (;;) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw new TimeoutError(`Timed out waiting for '${action}'`);
      const response = await this.#receive(remaining);
      if ('event' in response) {
        this.#events.push(response);
        continue;
      }
      if (response.id !== requestId) {
        throw new ProtocolError(`Unexpected response id: ${JSON.stringify(response.id)}`);
      }
      if (response.ok !== true) {
        const error = response.error;
        if (!isObject(error)) {
          throw new ProtocolError(`Malformed API error: ${JSON.stringify(response)}`);
        }
        throw new ApiError(
          String(error.code ?? 'unknown'),
          String(error.message ?? 'no message'),
          error.details,
        );
      }
      return asObject(response.result, `${action} result`);
    }
  }

  async #receive(timeout) {
    const channel = this.#channel;
    if (channel === null) {
      throw new ConnectionInterrupted('No active Camera Control connection');
    }
    const message = await channel.receive(timeout);
    let decoded;
    try {
      decoded = JSON.parse(message);
    } catch {
      throw new ProtocolError('The server returned invalid JSON');
    }
    return asObject(decoded, 'WebSocket message');
  }
}

/** Validate and decode a JPEG photo object returned by the API. */
export function decodePhoto(photo) {
  if (!isObject(photo) || photo.mimeType !== 'image/jpeg') {
    throw new ProtocolError(`Unexpected photo metadata: ${JSON.stringify(photo)}`);
  }
  if (typeof photo.dataBase64 !== 'string') {
    throw new ProtocolError("The photo doesn't contain Base64 JPEG data");
  }
  const jpeg = Buffer.from(photo.dataBase64, 'base64');
  if (jpeg.length === 0 && photo.dataBase64.length > 0) {
    throw new ProtocolError('The photo contains invalid Base64 data');
  }
  return jpeg;
}

function isObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asObject(value, label) {
  if (!isObject(value)) throw new ProtocolError(`${label} isn't a JSON object`);
  return value;
}

function delay(milliseconds) {
  return new Promise((resolve) => {
    setTimeout(resolve, milliseconds).unref?.();
  });
}
