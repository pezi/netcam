import assert from 'node:assert/strict';
import test from 'node:test';

import {
  API_PROTOCOL_VERSION,
  ApiError,
  CameraControlClient,
  ProtocolError,
  decodePhoto,
} from '../src/index.js';

/**
 * Replays scripted server messages and answers requests the way the app does,
 * so the handshake and event queue can be exercised offline.
 */
class FakeChannel {
  constructor(messages) {
    this.messages = [...messages];
    this.sent = [];
    this.closed = false;
  }

  send(message) {
    const payload = JSON.parse(message);
    this.sent.push(payload);
    if (payload.action === 'authenticate') {
      this.messages.unshift({
        id: payload.id,
        ok: true,
        result: { protocolVersion: API_PROTOCOL_VERSION },
      });
    } else if (payload.action === 'status') {
      this.messages.push(
        { event: 'captureSession', session: { id: 'session-1', state: 'waiting' } },
        { id: payload.id, ok: true, result: { ready: true } },
      );
    }
  }

  async receive() {
    if (this.messages.length === 0) throw new Error('the connection was closed by the peer');
    return JSON.stringify(this.messages.shift());
  }

  close() {
    this.closed = true;
  }
}

function makeClient(channel) {
  return new CameraControlClient('wss://192.168.1.50:8080/ws', 'secret123', undefined, {
    timeout: 1_000,
    connectFactory: async () => channel,
  });
}

test('authenticates protocol v4 and queues events', async () => {
  const channel = new FakeChannel([{ event: 'hello', protocolVersion: API_PROTOCOL_VERSION }]);
  const client = makeClient(channel);
  await client.connect();
  assert.deepEqual(await client.status(), { ready: true });
  const event = await client.waitForEvent('captureSession', { timeout: 100 });
  assert.equal(event.session.id, 'session-1');
  assert.equal(channel.sent[0].action, 'authenticate');
  client.close();
  assert.equal(channel.closed, true);
});

test('rejects API errors', async () => {
  const channel = new FakeChannel([
    { event: 'hello', protocolVersion: API_PROTOCOL_VERSION },
    { id: 'node-2', ok: false, error: { code: 'camera_busy', message: 'Busy' } },
  ]);
  const client = makeClient(channel);
  await client.connect();
  await assert.rejects(
    client.request('cancelCaptureSession', { sessionId: 'session-1' }, { retryOnDisconnect: false }),
    (error) => error instanceof ApiError && error.code === 'camera_busy',
  );
});

test('rejects old protocol servers', async () => {
  const channel = new FakeChannel([{ event: 'hello', protocolVersion: 1 }]);
  const client = makeClient(channel);
  await assert.rejects(client.connect(), ProtocolError);
});

test('requires a wss URL', () => {
  assert.throws(
    () => new CameraControlClient('ws://192.168.1.50:8080/ws', 'x', undefined, {}),
    /wss:\/\/ URL/,
  );
});

test('decodes only JPEG photos', () => {
  assert.throws(() => decodePhoto({ mimeType: 'image/png' }), ProtocolError);
  const jpeg = decodePhoto({ mimeType: 'image/jpeg', dataBase64: '/9j/4AAQ' });
  assert.equal(jpeg[0], 0xff);
  assert.equal(jpeg[1], 0xd8);
});
