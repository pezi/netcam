# Camera Control Dart Client

A pure-Dart package and the `camera-control` command-line client for Camera
Control protocol version 4. It supports status, idempotent single capture, and
cancellable time-lapse operation over verified TLS. Status polling reconnects
with bounded exponential backoff when Wi-Fi or the server connection is
interrupted.

The library depends only on `dart:io`, so it also runs inside a Flutter app on
every platform where `dart:io` is available (Android, iOS, macOS, Windows,
Linux — not the web).

## Requirements and installation

- Dart SDK 3.5 or newer (or a Flutter SDK that bundles one)
- Camera Control's TLS-only WebSocket API enabled and running
- The verified local CA certificate shown by the phone app

```bash
cd dart
dart pub get
dart compile exe bin/camera_control.dart -o camera-control   # optional
```

Set the password without exposing it in process arguments:

```bash
export CAMERA_CONTROL_PASSWORD='your password'
```

If this variable isn't present, the CLI prompts without echoing the password.

## Commands

Read camera, device, capture-session, and reliability status:

```bash
dart run bin/camera_control.dart status wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt
```

Capture and save one JPEG through the idempotent job API:

```bash
dart run bin/camera_control.dart capture wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --camera 1 --resolution high --flash off \
  --jpeg-quality 90 --rotation 90 --output photo.jpg
```

Rotation accepts `0`, `90`, `180`, or `270` clockwise degrees. Omit the flag
to keep the current app setting; use `--rotation 0` to reset it. The app starts
at 0°. Rotation applies to new photos and live previews; existing gallery
photos keep their orientation. Library callers pass `"rotation": 90` in the
existing controls map. Check `status.capabilities.rotation` for support.

Start a 5-second time lapse, stopping after 20 photos:

```bash
dart run bin/camera_control.dart time-lapse wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --interval 5 --count 20
```

Omit `--count` to run until `Ctrl+C`, or use `--duration SECONDS`.

## Library usage

```dart
import 'dart:io';

import 'package:camera_control_client/camera_control_client.dart';

Future<void> main() async {
  final camera = CameraControlClient(
    url: 'wss://192.168.1.50:8080/ws',
    password: Platform.environment['CAMERA_CONTROL_PASSWORD']!,
    caFile: 'camera-control-local-ca.crt',
  );

  await camera.connect();
  try {
    print(await camera.status());
    final job = await camera.captureJob(
      const CaptureOptions(requestId: 'document-page-42'),
    );
    final photo = (job['result']! as JsonObject)['photo']! as JsonObject;
    await File('photo.jpg').writeAsBytes(decodePhoto(photo));
  } finally {
    await camera.close();
  }
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

`--insecure` is only for the initial CA download. The library builds a
`SecurityContext(withTrustedRoots: false)` holding only this certificate.

Run tests with:

```bash
dart test
```

See [../api.md](../api.md) for the command and event summary.
