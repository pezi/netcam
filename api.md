# Camera Control WebSocket API

Protocol version: **4**

The endpoint is `wss://PHONE_IP:PORT/ws`. It accepts UTF-8 JSON objects up to
64 KiB. The server sends a `hello` event first; clients must then authenticate
with the shared Web/API password. TLS verification must use the local CA shown
by the app.

```json
{"event":"hello","protocolVersion":4,"authenticationRequired":true}
```

```json
{"id":"auth-1","action":"authenticate","password":"…"}
```

Every response echoes `id` and contains either `{"ok":true,"result":{…}}` or
`{"ok":false,"error":{"code":"…","message":"…"}}`.

## Client implementations

Six clients in this repository speak the protocol described below. All of them
expose the same three commands — `status`, `capture`, and `time-lapse` — the
same connection flags, and the same reconnect and idempotency behavior, so a
script written against one translates directly to the others.

| Language | Directory | Library entry point | Transport |
| --- | --- | --- | --- |
| Python | [`python/`](python/README.md) | `camera_control.CameraControlClient` | `websockets` (sync) |
| Go | [`go/`](go/README.md) | `cameracontrol.Client` | `github.com/coder/websocket` |
| Rust | [`rust/`](rust/README.md) | `camera_control::Client` | `tokio-tungstenite` + rustls |
| Java | [`java/`](java/README.md) | `app.flutterdev.cameracontrol.CameraControlClient` | `java.net.http.WebSocket` |
| Node.js | [`node/`](node/README.md) | `CameraControlClient` | `ws` |
| Dart | [`dart/`](dart/README.md) | `CameraControlClient` | `dart:io` `WebSocket` |

Every client:

- refuses anything but a `wss://` URL, and trusts only the CA file it is given,
  never the platform root store;
- rejects a `hello` whose `protocolVersion` isn't 4, and rejects an
  authentication result that doesn't confirm the same version;
- reads the password from an environment variable (`CAMERA_CONTROL_PASSWORD`
  by default) so it never appears in process arguments;
- serializes requests over one connection, queues unsolicited events that
  arrive between a request and its response, and replays them to the next
  matching `waitForEvent`;
- retries idempotent actions across a dropped socket with bounded exponential
  backoff, and never retries `startCaptureSession` or the legacy `capture`;
- exposes the same three error kinds: a structured API error, a protocol error
  for malformed or unsupported messages, and a connection-interrupted error.

## Actions

| Action | Purpose |
| --- | --- |
| `status` | Camera capabilities, device health, reliability state, and latest capture session |
| `configure` | Select camera/resolution and apply supported controls |
| `capture` | Immediate, non-idempotent capture |
| `captureJob` | Idempotent event-driven capture using a stable `requestId` |
| `cancelCaptureJob` | Cancel a retained capture job |
| `startCaptureSession` | Start burst, delayed, interval, or time-lapse capture |
| `cancelCaptureSession` | Cancel a retained capture session |
| `startPreview` / `stopPreview` | Start or stop bounded JPEG preview events |

### Status

```json
{"id":"status-1","action":"status"}
```

The result includes `ready`, `busy`, `cameras`, `resolutions`, `capabilities`,
`controls`, `device`, `reliability`, and `captureSession`.

### Idempotent capture

```json
{
  "id":"job-1",
  "action":"captureJob",
  "requestId":"document-page-42",
  "camera":1,
  "resolution":"high",
  "flash":"off",
  "controls":{"jpegQuality":90}
}
```

The response contains `job` and `duplicate`. Subsequent `captureJob` events
move through `accepted`, `focusing`, `capturing`, and a terminal `completed`,
`failed`, or `cancelled` state. A completed job contains Base64 JPEG data under
`job.result.photo.dataBase64`.

Replaying a `captureJob` with the same `requestId` after an ambiguous
disconnect returns the retained job snapshot instead of taking a second photo.
Do not reuse a request ID with different capture settings. If the phone app
itself restarts, its in-memory idempotency history is lost; check `status`
before replaying.

### Time lapse

```json
{
  "id":"session-1",
  "action":"startCaptureSession",
  "camera":1,
  "resolution":"high",
  "flash":"off",
  "controls":{},
  "plan":{"mode":"timeLapse","intervalSeconds":5,"count":20}
}
```

`count` is optional for time lapse. Progress is published as
`captureSession` events and is also available as the latest
`status.captureSession`. Cancel with:

```json
{"id":"cancel-1","action":"cancelCaptureSession","sessionId":"session_…"}
```

Because `startCaptureSession` is not idempotent, no client retries it after a
dropped socket. Polling `status.captureSession` is how each client follows a
running session across reconnects.

### Photo payloads

A completed job or session photo is a JSON object:

```json
{"mimeType":"image/jpeg","dataBase64":"…","width":4032,"height":3024}
```

Every client validates `mimeType` before decoding and rejects anything that
isn't `image/jpeg`.

## Certificate bootstrap

Download the CA from the URL shown by the app, then compare its SHA-256
fingerprint with the phone before trusting it:

```bash
curl --insecure --output camera-control-local-ca.crt \
  https://192.168.1.50:8080/ca.crt
openssl x509 -in camera-control-local-ca.crt -noout -fingerprint -sha256
```

`--insecure` is only for the initial CA download. Every client in this
repository verifies the certificate chain for the WebSocket connection itself.

Each port's README covers installation, the CLI, and library usage in that
language.
