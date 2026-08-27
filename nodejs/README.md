# Camera Control Node.js Client

An ES module library and the `camera-control` command-line client for Camera
Control protocol version 4. It supports status, idempotent single capture, and
cancellable time-lapse operation over verified TLS. Status polling reconnects
with bounded exponential backoff when Wi-Fi or the server connection is
interrupted.

## Requirements and installation

- Node.js 18.17 or newer
- Camera Control's TLS-only WebSocket API enabled and running
- The verified local CA certificate shown by the phone app

```bash
cd node
npm install
npm link      # optional: puts `camera-control` on PATH
```

Set the password without exposing it in process arguments:

```bash
export CAMERA_CONTROL_PASSWORD='your password'
```

If this variable isn't present, the CLI prompts without echoing the password.

## Commands

Read camera, device, capture-session, and reliability status:

```bash
node bin/camera-control.js status wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt
```

Capture and save one JPEG through the idempotent job API:

```bash
node bin/camera-control.js capture wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --camera 1 --resolution high --flash off \
  --jpeg-quality 90 --output photo.jpg
```

Start a 5-second time lapse, stopping after 20 photos:

```bash
node bin/camera-control.js time-lapse wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --interval 5 --count 20
```

Omit `--count` to run until `Ctrl+C`, or use `--duration SECONDS`. The client
polls session status, reconnects between polls, and requests server-side
cancellation when stopped.

## Library usage

```javascript
import { writeFile } from 'node:fs/promises';
import { CameraControlClient, decodePhoto } from 'camera-control-client';

const camera = new CameraControlClient(
  'wss://192.168.1.50:8080/ws',
  process.env.CAMERA_CONTROL_PASSWORD,
  'camera-control-local-ca.crt',
);

await camera.connect();
try {
  console.log(await camera.status());
  const job = await camera.captureJob({ requestId: 'document-page-42' });
  await writeFile('photo.jpg', decodePhoto(job.result.photo));
} finally {
  camera.close();
}
```

`captureJob` retries reuse the same semantic `requestId`, allowing the running
app process to deduplicate an ambiguous reconnect. Do not deliberately reuse a
request ID with different capture settings. If the phone app itself restarts,
its in-memory idempotency history is lost; verify status before replaying an
ambiguous capture.

## Certificate bootstrap

Download the CA from the URL shown by the app, then compare its SHA-256
fingerprint with the phone before trusting it:

```bash
curl --insecure --output camera-control-local-ca.crt \
  https://192.168.1.50:8080/ca.crt
openssl x509 -in camera-control-local-ca.crt -noout -fingerprint -sha256
```

`--insecure` is only for the initial CA download. The library passes the CA to
`ws` as the only trusted root, so certificate verification stays on.

Run tests with:

```bash
npm test
```

See [../api.md](../api.md) for the command and event summary.
