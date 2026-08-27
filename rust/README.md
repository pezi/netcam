# Camera Control Rust Client

A `camera_control` library crate and the `camera-control` command-line client
for Camera Control protocol version 4. It supports status, idempotent single
capture, and cancellable time-lapse operation over verified TLS. Status polling
reconnects with bounded exponential backoff when Wi-Fi or the server connection
is interrupted.

TLS is provided by rustls with the `ring` provider, so the client trusts
exactly the app's local CA and never the platform root store.

## Requirements and installation

- Rust 1.75 or newer
- Camera Control's TLS-only WebSocket API enabled and running
- The verified local CA certificate shown by the phone app

```bash
cd rust
cargo build --release
cargo install --path .   # optional: puts `camera-control` on PATH
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

Omit `--count` to run until `Ctrl+C`, or use `--duration SECONDS`.

## Library usage

```rust
use camera_control::{decode_photo, CaptureOptions, Client, Options};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut camera = Client::with_ca_file(
        "wss://192.168.1.50:8080/ws",
        std::env::var("CAMERA_CONTROL_PASSWORD")?,
        "camera-control-local-ca.crt",
        Options::default(),
    )
    .await?;
    camera.connect().await?;

    println!("{}", camera.status().await?);

    let job = camera
        .capture_job(&CaptureOptions {
            request_id: Some("document-page-42".into()),
            ..CaptureOptions::default()
        })
        .await?;
    let photo = &job["result"]["photo"];
    std::fs::write("photo.jpg", decode_photo(photo)?)?;

    camera.close().await;
    Ok(())
}
```

`capture_job` retries reuse the same semantic `request_id`, allowing the
running app process to deduplicate an ambiguous reconnect. Do not deliberately
reuse a request ID with different capture settings. If the phone app itself
restarts, its in-memory idempotency history is lost; verify status before
replaying an ambiguous capture.

## Certificate bootstrap

Download the CA from the URL shown by the app, then compare its SHA-256
fingerprint with the phone before trusting it:

```bash
curl --insecure --output camera-control-local-ca.crt \
  https://192.168.1.50:8080/ca.crt
openssl x509 -in camera-control-local-ca.crt -noout -fingerprint -sha256
```

`--insecure` is only for the initial CA download. `TlsConnector::from_ca_file`
builds a root store containing only this certificate.

Run tests with:

```bash
cargo test
```

See [../api.md](../api.md) for the command and event summary.
