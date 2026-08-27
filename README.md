# Camera Control Clients

Client libraries and command-line tools for the **Camera Control** phone app's
JSON WebSocket API, in six languages. Every port speaks protocol version 4 over
verified TLS, and every one exposes the same three commands — read status,
capture one photo, run a time lapse — with the same flags and the same
reconnect behavior.

The protocol itself is documented in **[api.md](api.md)**.

## Ports

| Language | Directory | Library entry point | Transport | Runtime dependencies |
| --- | --- | --- | --- | --- |
| Python | [`python/`](python/README.md) | `camera_control.CameraControlClient` | `websockets` (sync) | 1 |
| Go | [`go/`](go/README.md) | `cameracontrol.Client` | `github.com/coder/websocket` | 1 |
| Rust | [`rust/`](rust/README.md) | `camera_control::Client` | `tokio-tungstenite` + rustls | tokio stack |
| Java | [`java/`](java/README.md) | `app.flutterdev.cameracontrol.CameraControlClient` | `java.net.http.WebSocket` | none |
| Node.js | [`nodejs/`](node/README.md) | `CameraControlClient` | `ws` | 1 |
| Dart | [`dart/`](dart/README.md) | `CameraControlClient` | `dart:io` `WebSocket` | 1 (CLI only) |

The Java port bundles its own JSON reader, so the built JAR has no runtime
dependencies at all. The Dart port's library half depends only on `dart:io`,
which means it also runs inside a Flutter app on every non-web platform.

Each directory has its own README covering installation, the CLI, and library
usage in that language.

## Quick start

Pick a port, then point it at the `wss://` URL the app shows. Every client
reads the password from an environment variable so it never lands in process
arguments or shell history:

```bash
export CAMERA_CONTROL_PASSWORD='your password'
```

Download the app's local CA and verify its fingerprint against the phone before
trusting it — this is the one step you should not skip:

```bash
curl --insecure --output camera-control-local-ca.crt \
  https://192.168.1.50:8080/ca.crt
openssl x509 -in camera-control-local-ca.crt -noout -fingerprint -sha256
```

`--insecure` applies only to that first download. From then on every client
verifies the chain against exactly this CA and nothing else.

Capture one photo:

```bash
# Python
camera-control capture wss://192.168.1.50:8080/ws --ca camera-control-local-ca.crt

# Go
go run ./cmd/camera-control capture wss://192.168.1.50:8080/ws -ca camera-control-local-ca.crt

# Rust
cargo run -- capture wss://192.168.1.50:8080/ws --ca camera-control-local-ca.crt

# Java
java -jar target/camera-control-client-0.1.0.jar \
  capture wss://192.168.1.50:8080/ws --ca camera-control-local-ca.crt

# Node.js
node bin/camera-control.js capture wss://192.168.1.50:8080/ws --ca camera-control-local-ca.crt

# Dart
dart run bin/camera_control.dart capture wss://192.168.1.50:8080/ws --ca camera-control-local-ca.crt
```

## The shared command line

```
camera-control <command> <wss-url> [options]
```

| Command | What it does |
| --- | --- |
| `status` | Print camera, device, capture-session, and reliability status |
| `capture` | Capture and save one JPEG through the idempotent job API |
| `time-lapse` | Start a capture session, follow it, and cancel it cleanly |

| Option | Applies to | Default |
| --- | --- | --- |
| `--ca FILE` | all | required |
| `--password-env NAME` | all | `CAMERA_CONTROL_PASSWORD` |
| `--timeout SECONDS` | all | `30` |
| `--reconnect-attempts N` | all | `5` |
| `--compact` | `status` | off |
| `--camera N` | `capture`, `time-lapse` | `1` |
| `--resolution NAME` | `capture`, `time-lapse` | `high` |
| `--flash MODE` | `capture`, `time-lapse` | `off` |
| `--jpeg-quality N` | `capture`, `time-lapse` | unset |
| `--output FILE` | `capture` | `photo.jpg` |
| `--request-id ID` | `capture` | generated |
| `--interval SECONDS` | `time-lapse` | required |
| `--count N` | `time-lapse` | unbounded |
| `--duration SECONDS` | `time-lapse` | unbounded |
| `--poll SECONDS` | `time-lapse` | `1` |

Resolutions are `low`, `medium`, `high`, `veryHigh`, `ultraHigh`, and `max`.
Flash modes are `off`, `auto`, `always`, and `torch`. The Go CLI accepts both
`-flag` and `--flag`; the others take `--flag`.

## What every port guarantees

- **Verified TLS only.** A `ws://` URL is refused outright, and the CA file you
  supply is the only trusted root — never the platform store.
- **Protocol version pinning.** A `hello` that isn't version 4 is rejected, and
  so is an authentication result that doesn't confirm the same version.
- **Serialized requests with an event queue.** Unsolicited events that arrive
  between a request and its response are queued and replayed to the next
  matching wait, rather than being dropped or mistaken for a response.
- **Bounded reconnect.** Idempotent actions retry across a dropped socket with
  exponential backoff capped at ten seconds. `startCaptureSession` and the
  legacy direct `capture` are never retried.
- **Idempotent capture.** `captureJob` replays reuse the same semantic
  `requestId`, so the running app deduplicates an ambiguous reconnect instead
  of taking a second photo.
- **The same three error kinds:** a structured API error carrying the server's
  code, a protocol error for malformed or unsupported messages, and a
  connection-interrupted error.

Do not reuse a request ID with different capture settings. If the phone app
itself restarts, its in-memory idempotency history is lost — check `status`
before replaying an ambiguous capture.

## Development

Each port has an injectable transport, so the protocol logic is tested offline
against a scripted in-memory channel; no phone or network is needed.

```bash
cd python && PYTHONPATH=src python3 -m unittest discover -s tests
cd go     && go vet ./... && go test ./...
cd rust   && cargo test && cargo clippy --all-targets
cd java   && mvn test
cd nodejs   && npm install && npm test
cd dart   && dart pub get && dart analyze && dart test
```

Ignore rules are split: the root `.gitignore` holds shared entries (editors, OS
files, secrets, certificates, and captured photos), and each port directory
carries its own language-specific rules.
