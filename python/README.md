# Camera Control Python Client

This directory contains an installable Python library and the `camera-control`
command-line client for Camera Control protocol version 4. It supports status,
idempotent single capture, and cancellable time-lapse operation over verified
TLS. Status polling reconnects with bounded exponential backoff when Wi-Fi or
the server connection is interrupted.

## Requirements and installation

- Python 3.9 or newer
- Camera Control's TLS-only WebSocket API enabled and running
- The verified local CA certificate shown by the phone app

```bash
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install --upgrade pip
python3 -m pip install -e .
```

Set the password without exposing it in process arguments:

```bash
export CAMERA_CONTROL_PASSWORD='your password'
```

If this variable isn't present, the CLI prompts without echoing the password.

## Commands

Read camera, device, capture-session, and reliability status:

```bash
camera-control status wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt
```

Capture and save one JPEG through the idempotent job API:

```bash
camera-control capture wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --camera 1 --resolution high --flash off \
  --jpeg-quality 90 --output photo.jpg
```

Start a 5-second time lapse, stopping after 20 photos:

```bash
camera-control time-lapse wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --interval 5 --count 20
```

Omit `--count` to run until `Ctrl+C`, or use `--duration SECONDS`. The client
polls session status, reconnects between polls, and requests server-side
cancellation when stopped.

The legacy entry point still works after installing the package:

```bash
python3 capture_photo.py wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt --output photo.jpg
```

## Library usage

```python
import os
from pathlib import Path
from camera_control import CameraControlClient, decode_photo

with CameraControlClient(
    "wss://192.168.1.50:8080/ws",
    os.environ["CAMERA_CONTROL_PASSWORD"],
    Path("camera-control-local-ca.crt"),
) as camera:
    print(camera.status())
    job = camera.capture_job(request_id="document-page-42")
    jpeg = decode_photo(job["result"]["photo"])
    Path("photo.jpg").write_bytes(jpeg)
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

`--insecure` is only for the initial CA download. The Python library always
uses a verified `SSLContext` for the WebSocket connection.

Run tests with:

```bash
PYTHONPATH=src python3 -m unittest discover -s tests -v
```

See [../api.md](../api.md) for the command and event summary.
