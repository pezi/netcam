/** WebSocket transport with a pull-based receive queue. */

import { readFile } from 'node:fs/promises';
import WebSocket from 'ws';

import { ConnectionInterrupted, ProtocolError, TimeoutError } from './errors.js';

/**
 * A pull-based text WebSocket channel. Incoming frames are buffered so that a
 * caller can await one message at a time regardless of arrival order.
 */
export class WebSocketChannel {
  #socket;
  #messages = [];
  #waiting = null;
  #failure = null;

  constructor(socket) {
    this.#socket = socket;
    socket.on('message', (data, isBinary) => {
      if (isBinary) {
        this.#fail(new ProtocolError('The server returned a non-JSON WebSocket message'));
        return;
      }
      this.#deliver(data.toString('utf8'));
    });
    socket.on('error', (error) => this.#fail(error));
    socket.on('close', () =>
      this.#fail(new ConnectionInterrupted('The Camera Control connection closed')));
  }

  #deliver(message) {
    if (this.#waiting) {
      const { resolve, timer } = this.#waiting;
      this.#waiting = null;
      clearTimeout(timer);
      resolve(message);
      return;
    }
    this.#messages.push(message);
  }

  #fail(error) {
    if (this.#failure) return;
    this.#failure = error;
    if (this.#waiting) {
      const { reject, timer } = this.#waiting;
      this.#waiting = null;
      clearTimeout(timer);
      reject(error);
    }
  }

  send(message) {
    if (this.#failure) throw this.#failure;
    this.#socket.send(message);
  }

  /** Resolve with the next text frame, or reject once the deadline passes. */
  receive(timeoutMs) {
    if (this.#messages.length > 0) return Promise.resolve(this.#messages.shift());
    if (this.#failure) return Promise.reject(this.#failure);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#waiting = null;
        reject(new TimeoutError('Timed out waiting for a Camera Control message'));
      }, timeoutMs);
      timer.unref?.();
      this.#waiting = { resolve, reject, timer };
    });
  }

  close() {
    this.#fail(new ConnectionInterrupted('The Camera Control connection was closed locally'));
    try {
      this.#socket.close();
    } catch {
      // A socket that never opened is already unusable.
    }
  }
}

/**
 * Open one verified-TLS WebSocket connection.
 *
 * @param {string} url wss:// endpoint shown by the app.
 * @param {{caFile?: string, tlsOptions?: object, openTimeoutMs: number}} options
 */
export async function openWebSocket(url, { caFile, tlsOptions, openTimeoutMs }) {
  const tls = tlsOptions ?? { ca: await readCertificate(caFile) };
  const socket = new WebSocket(url, {
    ...tls,
    handshakeTimeout: openTimeoutMs,
    followRedirects: false,
  });
  await new Promise((resolve, reject) => {
    const onOpen = () => {
      socket.off('error', onError);
      resolve();
    };
    const onError = (error) => {
      socket.off('open', onOpen);
      reject(error);
    };
    socket.once('open', onOpen);
    socket.once('error', onError);
  });
  return new WebSocketChannel(socket);
}

async function readCertificate(caFile) {
  if (!caFile) {
    throw new Error('A CA certificate is required without an explicit TLS configuration');
  }
  try {
    return await readFile(caFile);
  } catch {
    throw new Error(`CA certificate not found: ${caFile}`);
  }
}
