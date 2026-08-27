//! Asynchronous, reconnecting client for the Camera Control JSON WebSocket
//! API (protocol version 4).
//!
//! Requests are serialized on one authenticated connection. Status and
//! capture-session polling reconnect automatically with bounded exponential
//! backoff. A capture job reuses a stable request ID when it is replayed after
//! a dropped socket so the running server can deduplicate it.

pub mod error;
pub mod transport;

use std::collections::VecDeque;
use std::sync::Arc;
use std::time::{Duration, Instant};

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine;
use serde_json::{json, Map, Value};

pub use crate::error::{Error, Result};
pub use crate::transport::{Channel, Connect, TlsConnector};
use crate::transport::validate_endpoint;

/// The only Camera Control protocol this client speaks.
pub const API_PROTOCOL_VERSION: u64 = 4;

/// Capture-job states that end a job.
pub const TERMINAL_JOB_STATES: [&str; 3] = ["completed", "failed", "cancelled"];
/// Capture-session states that end a session.
pub const TERMINAL_SESSION_STATES: [&str; 3] = ["completed", "failed", "cancelled"];

/// Connection tuning shared by every request.
#[derive(Clone, Debug)]
pub struct Options {
    /// Bounds a single request or event wait.
    pub timeout: Duration,
    /// Bounds idempotent retries after a dropped socket.
    pub reconnect_attempts: u32,
    /// The first backoff delay; it doubles up to ten seconds.
    pub reconnect_delay: Duration,
}

impl Default for Options {
    fn default() -> Self {
        Self {
            timeout: Duration::from_secs(30),
            reconnect_attempts: 5,
            reconnect_delay: Duration::from_secs(1),
        }
    }
}

/// The capture settings shared by jobs and sessions.
#[derive(Clone, Debug)]
pub struct CaptureOptions {
    pub camera: i64,
    pub resolution: String,
    pub flash: String,
    pub controls: Map<String, Value>,
    /// The stable idempotency key. One is generated when it is `None`.
    pub request_id: Option<String>,
}

impl Default for CaptureOptions {
    fn default() -> Self {
        Self {
            camera: 1,
            resolution: "high".into(),
            flash: "off".into(),
            controls: Map::new(),
            request_id: None,
        }
    }
}

/// One authenticated Camera Control connection.
pub struct Client {
    url: String,
    password: String,
    options: Options,
    connector: Arc<dyn Connect>,
    channel: Option<Box<dyn Channel>>,
    events: VecDeque<Value>,
    request_number: u64,
}

impl Client {
    /// Build a client over the default verified-TLS connector.
    pub async fn with_ca_file(
        url: impl Into<String>,
        password: impl Into<String>,
        ca_file: impl AsRef<std::path::Path>,
        options: Options,
    ) -> Result<Self> {
        let connector = TlsConnector::from_ca_file(ca_file.as_ref()).await?;
        Self::new(url, password, Arc::new(connector), options)
    }

    /// Build a client over any connector, which is how tests inject a fake.
    pub fn new(
        url: impl Into<String>,
        password: impl Into<String>,
        connector: Arc<dyn Connect>,
        options: Options,
    ) -> Result<Self> {
        let url = url.into();
        validate_endpoint(&url)?;
        if options.timeout.is_zero() {
            return Err(Error::Config("timeout must be positive".into()));
        }
        Ok(Self {
            url,
            password: password.into(),
            options,
            connector,
            channel: None,
            events: VecDeque::new(),
            request_number: 0,
        })
    }

    /// Whether a socket is currently retained.
    pub fn connected(&self) -> bool {
        self.channel.is_some()
    }

    /// Open, validate protocol v4, and authenticate one WSS connection.
    pub async fn connect(&mut self) -> Result<()> {
        self.close().await;
        let open_timeout = self.options.timeout.min(Duration::from_secs(10));
        let channel = tokio::time::timeout(open_timeout, self.connector.connect(self.url.clone()))
            .await
            .map_err(|_| Error::Timeout("timed out opening the Camera Control connection".into()))??;
        self.channel = Some(channel);
        match self.handshake().await {
            Ok(()) => Ok(()),
            Err(error) => {
                self.close().await;
                Err(error)
            }
        }
    }

    async fn handshake(&mut self) -> Result<()> {
        let hello = self.receive(self.options.timeout).await?;
        let is_hello = hello.get("event").and_then(Value::as_str) == Some("hello")
            && hello.get("protocolVersion").and_then(Value::as_u64) == Some(API_PROTOCOL_VERSION);
        if !is_hello {
            return Err(Error::protocol(format!("unsupported server greeting: {hello}")));
        }
        let authenticated = self
            .request_once("authenticate", json!({ "password": self.password }), self.options.timeout)
            .await?;
        if authenticated.get("protocolVersion").and_then(Value::as_u64) != Some(API_PROTOCOL_VERSION)
        {
            return Err(Error::protocol(format!(
                "authentication returned an unsupported protocol: {authenticated}"
            )));
        }
        Ok(())
    }

    /// Close the current socket without discarding queued events.
    pub async fn close(&mut self) {
        if let Some(mut channel) = self.channel.take() {
            channel.close().await;
        }
    }

    /// Send one action and return its result object.
    ///
    /// Pass `retry_on_disconnect = false` for non-idempotent operations such
    /// as the legacy direct `capture` action.
    pub async fn request(
        &mut self,
        action: &str,
        parameters: Value,
        retry_on_disconnect: bool,
    ) -> Result<Value> {
        let attempts = if retry_on_disconnect {
            self.options.reconnect_attempts
        } else {
            0
        };
        let mut last_error: Option<Error> = None;
        for attempt in 0..=attempts {
            if self.channel.is_none() {
                if let Err(error) = self.connect().await {
                    if error.is_permanent() {
                        return Err(error);
                    }
                    last_error = Some(error);
                    if attempt >= attempts {
                        break;
                    }
                    self.backoff(attempt).await;
                    continue;
                }
            }
            match self
                .request_once(action, parameters.clone(), self.options.timeout)
                .await
            {
                Ok(result) => return Ok(result),
                Err(error) if error.is_permanent() => return Err(error),
                Err(error) => {
                    last_error = Some(error);
                    self.close().await;
                    if attempt >= attempts {
                        break;
                    }
                    self.backoff(attempt).await;
                }
            }
        }
        Err(Error::ConnectionInterrupted(format!(
            "connection interrupted while performing '{action}': {}",
            last_error.map_or_else(|| "no active connection".to_string(), |error| error.to_string())
        )))
    }

    /// Camera, device, capture-session, and reliability status.
    pub async fn status(&mut self) -> Result<Value> {
        self.request("status", json!({}), true).await
    }

    /// Capture once through the idempotent job API and await completion.
    pub async fn capture_job(&mut self, options: &CaptureOptions) -> Result<Value> {
        let request_id = options
            .request_id
            .clone()
            .unwrap_or_else(|| format!("rust-{}", uuid::Uuid::new_v4()));
        let parameters = json!({
            "requestId": request_id,
            "camera": options.camera,
            "resolution": options.resolution,
            "flash": options.flash,
            "controls": Value::Object(options.controls.clone()),
        });
        for resume in 0..=self.options.reconnect_attempts {
            let result = self.request("captureJob", parameters.clone(), true).await?;
            let job = object_field(&result, "job", "captureJob result.job")?;
            if is_terminal(&job, &TERMINAL_JOB_STATES) {
                return Ok(job);
            }
            let job_id = job.get("id").cloned().unwrap_or(Value::Null);
            let timeout = self.options.timeout;
            let matched = self
                .wait_for_event("captureJob", timeout, |event| {
                    event
                        .get("job")
                        .map(|pending| {
                            pending.get("id") == Some(&job_id)
                                && is_terminal(pending, &TERMINAL_JOB_STATES)
                        })
                        .unwrap_or(false)
                })
                .await;
            match matched {
                Ok(event) => return object_field(&event, "job", "captureJob event.job"),
                // Re-submit the same semantic request ID. The running app
                // deduplicates it and returns the retained job snapshot.
                Err(Error::ConnectionInterrupted(message)) => {
                    if resume >= self.options.reconnect_attempts {
                        return Err(Error::ConnectionInterrupted(message));
                    }
                }
                Err(error) => return Err(error),
            }
        }
        Err(Error::ConnectionInterrupted(
            "capture job recovery attempts exhausted".into(),
        ))
    }

    /// Start a time-lapse capture session and return its snapshot.
    pub async fn start_time_lapse(
        &mut self,
        interval_seconds: f64,
        count: Option<i64>,
        options: &CaptureOptions,
    ) -> Result<Value> {
        let mut plan = json!({ "mode": "timeLapse", "intervalSeconds": interval_seconds });
        if let Some(count) = count {
            plan["count"] = json!(count);
        }
        let result = self
            .request(
                "startCaptureSession",
                json!({
                    "camera": options.camera,
                    "resolution": options.resolution,
                    "flash": options.flash,
                    "controls": Value::Object(options.controls.clone()),
                    "plan": plan,
                }),
                false,
            )
            .await?;
        object_field(&result, "session", "startCaptureSession result.session")
    }

    /// Poll the latest retained capture session across reconnects.
    pub async fn capture_session_status(&mut self, session_id: &str) -> Result<Value> {
        let status = self.status().await?;
        let session = status.get("captureSession");
        match session {
            Some(session)
                if session.get("id").and_then(Value::as_str) == Some(session_id) =>
            {
                Ok(session.clone())
            }
            _ => Err(Error::protocol(
                "the capture session is no longer retained by the server",
            )),
        }
    }

    /// Cancel one retained time-lapse/capture session.
    pub async fn cancel_capture_session(&mut self, session_id: &str) -> Result<Value> {
        let result = self
            .request("cancelCaptureSession", json!({ "sessionId": session_id }), true)
            .await?;
        object_field(&result, "session", "cancelCaptureSession result.session")
    }

    /// Return the next matching unsolicited event.
    pub async fn wait_for_event(
        &mut self,
        event_name: &str,
        timeout: Duration,
        predicate: impl Fn(&Value) -> bool,
    ) -> Result<Value> {
        let matches = |event: &Value| {
            event.get("event").and_then(Value::as_str) == Some(event_name) && predicate(event)
        };
        if let Some(index) = self.events.iter().position(matches) {
            return Ok(self.events.remove(index).expect("the index was just found"));
        }
        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(Error::ConnectionInterrupted(format!(
                    "timed out waiting for '{event_name}'"
                )));
            }
            let message = match self.receive(remaining).await {
                Ok(message) => message,
                Err(error) if error.is_permanent() => return Err(error),
                Err(error) => {
                    self.close().await;
                    return Err(Error::ConnectionInterrupted(format!(
                        "connection interrupted while waiting for '{event_name}': {error}"
                    )));
                }
            };
            if message.get("event").is_none() {
                return Err(Error::protocol(format!(
                    "unexpected response while waiting: {message}"
                )));
            }
            if matches(&message) {
                return Ok(message);
            }
            self.events.push_back(message);
        }
    }

    async fn request_once(
        &mut self,
        action: &str,
        parameters: Value,
        timeout: Duration,
    ) -> Result<Value> {
        self.request_number += 1;
        let request_id = format!("rust-{}", self.request_number);
        let mut payload = Map::new();
        payload.insert("id".into(), json!(request_id));
        payload.insert("action".into(), json!(action));
        if let Value::Object(parameters) = parameters {
            payload.extend(parameters);
        }
        let encoded = Value::Object(payload).to_string();
        let channel = self
            .channel
            .as_mut()
            .ok_or_else(|| Error::transport("no active Camera Control connection"))?;
        tokio::time::timeout(timeout, channel.send(encoded))
            .await
            .map_err(|_| Error::Timeout(format!("timed out sending '{action}'")))??;

        let deadline = Instant::now() + timeout;
        loop {
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return Err(Error::Timeout(format!("timed out waiting for '{action}'")));
            }
            let response = self.receive(remaining).await?;
            if response.get("event").is_some() {
                self.events.push_back(response);
                continue;
            }
            if response.get("id").and_then(Value::as_str) != Some(request_id.as_str()) {
                return Err(Error::protocol(format!(
                    "unexpected response id: {}",
                    response.get("id").unwrap_or(&Value::Null)
                )));
            }
            if response.get("ok") != Some(&Value::Bool(true)) {
                let error = response.get("error").ok_or_else(|| {
                    Error::protocol(format!("malformed API error: {response}"))
                })?;
                if !error.is_object() {
                    return Err(Error::protocol(format!("malformed API error: {response}")));
                }
                return Err(Error::Api {
                    code: error
                        .get("code")
                        .and_then(Value::as_str)
                        .unwrap_or("unknown")
                        .to_string(),
                    message: error
                        .get("message")
                        .and_then(Value::as_str)
                        .unwrap_or("no message")
                        .to_string(),
                    details: error.get("details").cloned(),
                });
            }
            let result = response
                .get("result")
                .cloned()
                .unwrap_or(Value::Null);
            if !result.is_object() {
                return Err(Error::protocol(format!("{action} result isn't a JSON object")));
            }
            return Ok(result);
        }
    }

    async fn receive(&mut self, timeout: Duration) -> Result<Value> {
        let channel = self
            .channel
            .as_mut()
            .ok_or_else(|| Error::transport("no active Camera Control connection"))?;
        let message = tokio::time::timeout(timeout, channel.receive())
            .await
            .map_err(|_| Error::Timeout("timed out waiting for a Camera Control message".into()))??;
        let decoded: Value = serde_json::from_str(&message)
            .map_err(|_| Error::protocol("the server returned invalid JSON"))?;
        if !decoded.is_object() {
            return Err(Error::protocol("the WebSocket message isn't a JSON object"));
        }
        Ok(decoded)
    }

    async fn backoff(&self, attempt: u32) {
        let delay = self
            .options
            .reconnect_delay
            .saturating_mul(1u32 << attempt.min(4))
            .min(Duration::from_secs(10));
        tokio::time::sleep(delay).await;
    }
}

/// Validate and decode a JPEG photo object returned by the API.
pub fn decode_photo(photo: &Value) -> Result<Vec<u8>> {
    if photo.get("mimeType").and_then(Value::as_str) != Some("image/jpeg") {
        return Err(Error::protocol(format!("unexpected photo metadata: {photo}")));
    }
    let encoded = photo
        .get("dataBase64")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::protocol("the photo doesn't contain Base64 JPEG data"))?;
    BASE64
        .decode(encoded)
        .map_err(|_| Error::protocol("the photo contains invalid Base64 data"))
}

fn object_field(value: &Value, key: &str, label: &str) -> Result<Value> {
    match value.get(key) {
        Some(field) if field.is_object() => Ok(field.clone()),
        _ => Err(Error::protocol(format!("{label} isn't a JSON object"))),
    }
}

fn is_terminal(value: &Value, states: &[&str]) -> bool {
    value
        .get("state")
        .and_then(Value::as_str)
        .map(|state| states.contains(&state))
        .unwrap_or(false)
}
