# Camera Control Java Client

A dependency-free Java library and the `camera-control` command-line client for
Camera Control protocol version 4. It supports status, idempotent single
capture, and cancellable time-lapse operation over verified TLS. Status polling
reconnects with bounded exponential backoff when Wi-Fi or the server connection
is interrupted.

The client uses `java.net.http.WebSocket` and a small bundled JSON reader, so
the shipped JAR has no runtime dependencies at all.

## Requirements and installation

- JDK 17 or newer
- Camera Control's TLS-only WebSocket API enabled and running
- The verified local CA certificate shown by the phone app

```bash
cd java
mvn package
```

The build produces `target/camera-control-client-0.1.0.jar` with an executable
manifest. Set the password without exposing it in process arguments:

```bash
export CAMERA_CONTROL_PASSWORD='your password'
```

If this variable isn't present, the CLI prompts on the console without echoing
the password.

## Commands

Read camera, device, capture-session, and reliability status:

```bash
java -jar target/camera-control-client-0.1.0.jar \
  status wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt
```

Capture and save one JPEG through the idempotent job API:

```bash
java -jar target/camera-control-client-0.1.0.jar \
  capture wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --camera 1 --resolution high --flash off \
  --jpeg-quality 90 --output photo.jpg
```

Start a 5-second time lapse, stopping after 20 photos:

```bash
java -jar target/camera-control-client-0.1.0.jar \
  time-lapse wss://192.168.1.50:8080/ws \
  --ca camera-control-local-ca.crt \
  --interval 5 --count 20
```

Omit `--count` to run until `Ctrl+C`, or use `--duration SECONDS`.

## Library usage

```java
import app.flutterdev.cameracontrol.CameraControlClient;
import app.flutterdev.cameracontrol.CameraControlClient.CaptureOptions;
import app.flutterdev.cameracontrol.CameraControlClient.Options;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

try (CameraControlClient camera = CameraControlClient.withCaFile(
    "wss://192.168.1.50:8080/ws",
    System.getenv("CAMERA_CONTROL_PASSWORD"),
    Path.of("camera-control-local-ca.crt"),
    Options.defaults())) {

  camera.connect();
  System.out.println(camera.status());

  Map<String, Object> job = camera.captureJob(
      CaptureOptions.defaults().withRequestId("document-page-42"));
  Map<String, Object> result =
      CameraControlClient.objectField(job, "result", "job.result");
  Files.write(Path.of("photo.jpg"), CameraControlClient.decodePhoto(
      CameraControlClient.objectField(result, "photo", "job.result.photo")));
}
```

`captureJob` retries reuse the same semantic `requestId`, allowing the running
app process to deduplicate an ambiguous reconnect. Do not deliberately reuse a
request ID with different capture settings. If the phone app itself restarts,
its in-memory idempotency history is lost; verify status before replaying an
ambiguous capture.

A `CameraControlClient` is not safe for concurrent use by multiple threads.

## Certificate bootstrap

Download the CA from the URL shown by the app, then compare its SHA-256
fingerprint with the phone before trusting it:

```bash
curl --insecure --output camera-control-local-ca.crt \
  https://192.168.1.50:8080/ca.crt
openssl x509 -in camera-control-local-ca.crt -noout -fingerprint -sha256
```

`--insecure` is only for the initial CA download. `WebSocketChannel` builds a
`KeyStore` containing only this certificate and an `SSLContext` that trusts
nothing else.

Run tests with:

```bash
mvn test
```

See [../api.md](../api.md) for the command and event summary.
