# Camera Control Go Client

An importable Go package and the `camera-control` command-line client for
Camera Control protocol version 4. It supports status, idempotent single
capture, and cancellable time-lapse operation over verified TLS. Status polling
reconnects with bounded exponential backoff when Wi-Fi or the server connection
is interrupted.

## Requirements and installation

- Go 1.23 or newer
- Camera Control's TLS-only WebSocket API enabled and running
- The verified local CA certificate shown by the phone app

```bash
cd go
go build ./...
go install ./cmd/camera-control
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

Omit `--count` to run until `Ctrl+C`, or use `--duration SECONDS`. Go's `flag`
package accepts both `-flag` and `--flag`.

## Library usage

```go
package main

import (
	"context"
	"fmt"
	"os"

	"flutterdev.app/camera_control_github/go/cameracontrol"
)

func main() {
	client, err := cameracontrol.New("wss://192.168.1.50:8080/ws", cameracontrol.Options{
		Password: os.Getenv("CAMERA_CONTROL_PASSWORD"),
		CAFile:   "camera-control-local-ca.crt",
	})
	if err != nil {
		panic(err)
	}
	ctx := context.Background()
	if err := client.Connect(ctx); err != nil {
		panic(err)
	}
	defer client.Close()

	status, err := client.Status(ctx)
	if err != nil {
		panic(err)
	}
	fmt.Println(status["ready"])

	job, err := client.CaptureJob(ctx, cameracontrol.CaptureOptions{
		RequestID: "document-page-42",
	})
	if err != nil {
		panic(err)
	}
	photo := job["result"].(map[string]any)["photo"].(map[string]any)
	jpeg, err := cameracontrol.DecodePhoto(photo)
	if err != nil {
		panic(err)
	}
	if err := os.WriteFile("photo.jpg", jpeg, 0o644); err != nil {
		panic(err)
	}
}
```

`CaptureJob` retries reuse the same semantic `RequestID`, allowing the running
app process to deduplicate an ambiguous reconnect. Do not deliberately reuse a
request ID with different capture settings. If the phone app itself restarts,
its in-memory idempotency history is lost; verify status before replaying an
ambiguous capture.

A `Client` is not safe for concurrent use by multiple goroutines. Open one
client per goroutine, or serialize calls yourself.

## Certificate bootstrap

Download the CA from the URL shown by the app, then compare its SHA-256
fingerprint with the phone before trusting it:

```bash
curl --insecure --output camera-control-local-ca.crt \
  https://192.168.1.50:8080/ca.crt
openssl x509 -in camera-control-local-ca.crt -noout -fingerprint -sha256
```

`--insecure` is only for the initial CA download. The package always dials with
a `tls.Config` that trusts exactly this CA and nothing else.

Run tests with:

```bash
go test ./...
```

See [../api.md](../api.md) for the command and event summary.
