//! Offline protocol tests driven by a scripted in-memory channel.

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use camera_control::error::Error;
use camera_control::transport::{BoxFuture, Channel, Connect};
use camera_control::{decode_photo, Client, Options, Result, API_PROTOCOL_VERSION};
use serde_json::{json, Value};

/// Replays scripted server messages and answers requests the way the app does.
struct FakeChannel {
    messages: VecDeque<Value>,
    sent: Arc<Mutex<Vec<Value>>>,
    closed: Arc<Mutex<bool>>,
}

impl Channel for FakeChannel {
    fn send(&mut self, message: String) -> BoxFuture<'_, Result<()>> {
        let payload: Value = serde_json::from_str(&message).expect("the client sends JSON");
        let id = payload.get("id").cloned().unwrap_or(Value::Null);
        match payload.get("action").and_then(Value::as_str) {
            Some("authenticate") => self.messages.push_front(json!({
                "id": id,
                "ok": true,
                "result": { "protocolVersion": API_PROTOCOL_VERSION },
            })),
            Some("status") => {
                self.messages.push_back(json!({
                    "event": "captureSession",
                    "session": { "id": "session-1", "state": "waiting" },
                }));
                self.messages
                    .push_back(json!({ "id": id, "ok": true, "result": { "ready": true } }));
            }
            _ => {}
        }
        self.sent.lock().unwrap().push(payload);
        Box::pin(async { Ok(()) })
    }

    fn receive(&mut self) -> BoxFuture<'_, Result<String>> {
        let next = self.messages.pop_front();
        Box::pin(async move {
            match next {
                Some(message) => Ok(message.to_string()),
                None => Err(Error::Transport("the connection was closed by the peer".into())),
            }
        })
    }

    fn close(&mut self) -> BoxFuture<'_, ()> {
        *self.closed.lock().unwrap() = true;
        Box::pin(async {})
    }
}

struct FakeConnector {
    script: Vec<Value>,
    sent: Arc<Mutex<Vec<Value>>>,
    closed: Arc<Mutex<bool>>,
}

impl Connect for FakeConnector {
    fn connect(&self, _url: String) -> BoxFuture<'static, Result<Box<dyn Channel>>> {
        let channel = FakeChannel {
            messages: self.script.iter().cloned().collect(),
            sent: self.sent.clone(),
            closed: self.closed.clone(),
        };
        Box::pin(async move { Ok(Box::new(channel) as Box<dyn Channel>) })
    }
}

struct Harness {
    client: Client,
    sent: Arc<Mutex<Vec<Value>>>,
    closed: Arc<Mutex<bool>>,
}

fn harness(script: Vec<Value>) -> Harness {
    let sent = Arc::new(Mutex::new(Vec::new()));
    let closed = Arc::new(Mutex::new(false));
    let connector = Arc::new(FakeConnector {
        script,
        sent: sent.clone(),
        closed: closed.clone(),
    });
    let client = Client::new(
        "wss://192.168.1.50:8080/ws",
        "secret123",
        connector,
        Options {
            timeout: Duration::from_secs(1),
            ..Options::default()
        },
    )
    .expect("the endpoint is valid");
    Harness { client, sent, closed }
}

fn hello() -> Value {
    json!({ "event": "hello", "protocolVersion": API_PROTOCOL_VERSION })
}

#[tokio::test]
async fn authenticates_protocol_v4_and_queues_events() {
    let mut harness = harness(vec![hello()]);
    harness.client.connect().await.expect("the handshake succeeds");
    assert_eq!(harness.client.status().await.unwrap(), json!({ "ready": true }));
    let event = harness
        .client
        .wait_for_event("captureSession", Duration::from_millis(100), |_| true)
        .await
        .expect("the queued event is replayed");
    assert_eq!(event["session"]["id"], json!("session-1"));
    assert_eq!(harness.sent.lock().unwrap()[0]["action"], json!("authenticate"));
    harness.client.close().await;
    assert!(*harness.closed.lock().unwrap());
}

#[tokio::test]
async fn rejects_api_errors() {
    let mut harness = harness(vec![
        hello(),
        json!({
            "id": "rust-2",
            "ok": false,
            "error": { "code": "camera_busy", "message": "Busy" },
        }),
    ]);
    harness.client.connect().await.expect("the handshake succeeds");
    let error = harness
        .client
        .request("cancelCaptureSession", json!({ "sessionId": "session-1" }), false)
        .await
        .expect_err("a failing response becomes an error");
    match error {
        Error::Api { code, .. } => assert_eq!(code, "camera_busy"),
        other => panic!("expected an API error, got {other}"),
    }
}

#[tokio::test]
async fn rejects_old_protocol_servers() {
    let mut harness = harness(vec![json!({ "event": "hello", "protocolVersion": 1 })]);
    let error = harness
        .client
        .connect()
        .await
        .expect_err("an old server is refused");
    assert!(matches!(error, Error::Protocol(_)), "got {error}");
}

#[test]
fn requires_a_secure_url() {
    let connector = Arc::new(FakeConnector {
        script: Vec::new(),
        sent: Arc::new(Mutex::new(Vec::new())),
        closed: Arc::new(Mutex::new(false)),
    });
    match Client::new("ws://192.168.1.50:8080/ws", "x", connector, Options::default()) {
        Err(Error::Config(_)) => {}
        Err(other) => panic!("expected a configuration error, got {other}"),
        Ok(_) => panic!("a ws:// URL must be refused"),
    }
}

#[test]
fn decodes_only_jpeg_photos() {
    assert!(decode_photo(&json!({ "mimeType": "image/png" })).is_err());
    let jpeg = decode_photo(&json!({ "mimeType": "image/jpeg", "dataBase64": "/9j/4AAQ" }))
        .expect("valid Base64 decodes");
    assert_eq!(&jpeg[..2], &[0xff, 0xd8]);
}
