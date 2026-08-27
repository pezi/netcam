//! Command-line interface for Camera Control automation.

use std::io::{BufRead, Write};
use std::path::PathBuf;
use std::process::{Command as ProcessCommand, Stdio};
use std::time::{Duration, Instant};

use camera_control::{
    decode_photo, CaptureOptions, Client, Error, Options, Result, TERMINAL_SESSION_STATES,
};
use clap::{Args, Parser, Subcommand, ValueEnum};
use serde_json::{Map, Value};

#[derive(Parser)]
#[command(
    name = "camera-control",
    about = "Control Camera Control through its verified-TLS WSS API.",
    version
)]
struct Cli {
    #[command(subcommand)]
    command: CommandKind,
}

#[derive(Subcommand)]
enum CommandKind {
    /// Print camera, device, capture-session, and reliability status.
    Status {
        #[command(flatten)]
        connection: Connection,
        /// Print compact JSON.
        #[arg(long)]
        compact: bool,
    },
    /// Capture and save one JPEG through the idempotent job API.
    Capture {
        #[command(flatten)]
        connection: Connection,
        #[command(flatten)]
        capture: CaptureArgs,
        /// JPEG output path.
        #[arg(long, default_value = "photo.jpg")]
        output: PathBuf,
        /// Stable idempotency key; generated when omitted.
        #[arg(long = "request-id")]
        request_id: Option<String>,
    },
    /// Start, poll, and cancel a time-lapse capture session.
    TimeLapse {
        #[command(flatten)]
        connection: Connection,
        #[command(flatten)]
        capture: CaptureArgs,
        /// Seconds between photos.
        #[arg(long, value_parser = interval_seconds)]
        interval: f64,
        /// Stop after this many photos.
        #[arg(long, value_parser = clap::value_parser!(i64).range(2..=1000))]
        count: Option<i64>,
        /// Cancel after this many seconds.
        #[arg(long, value_parser = positive_seconds)]
        duration: Option<f64>,
        /// Status poll interval in seconds.
        #[arg(long, default_value_t = 1.0, value_parser = positive_seconds)]
        poll: f64,
    },
}

#[derive(Args)]
struct Connection {
    /// The wss:// URL shown by the app.
    url: String,
    /// Verified Camera Control local CA certificate.
    #[arg(long)]
    ca: PathBuf,
    /// Environment variable containing the password.
    #[arg(long = "password-env", default_value = "CAMERA_CONTROL_PASSWORD")]
    password_env: String,
    /// Request timeout in seconds.
    #[arg(long, default_value_t = 30.0, value_parser = positive_seconds)]
    timeout: f64,
    /// Idempotent retry attempts.
    #[arg(long = "reconnect-attempts", default_value_t = 5)]
    reconnect_attempts: u32,
}

#[derive(Args)]
struct CaptureArgs {
    /// Camera index.
    #[arg(long, default_value_t = 1)]
    camera: i64,
    #[arg(long, value_enum, default_value_t = Resolution::High)]
    resolution: Resolution,
    #[arg(long, value_enum, default_value_t = Flash::Off)]
    flash: Flash,
    /// JPEG quality between 1 and 100.
    #[arg(long = "jpeg-quality", value_parser = clap::value_parser!(i64).range(1..=100))]
    jpeg_quality: Option<i64>,
}

#[derive(Clone, Copy, ValueEnum)]
enum Resolution {
    Low,
    Medium,
    High,
    #[value(name = "veryHigh")]
    VeryHigh,
    #[value(name = "ultraHigh")]
    UltraHigh,
    Max,
}

#[derive(Clone, Copy, ValueEnum)]
enum Flash {
    Off,
    Auto,
    Always,
    Torch,
}

impl CaptureArgs {
    fn to_options(&self, request_id: Option<String>) -> CaptureOptions {
        let mut controls = Map::new();
        if let Some(quality) = self.jpeg_quality {
            controls.insert("jpegQuality".into(), Value::from(quality));
        }
        CaptureOptions {
            camera: self.camera,
            resolution: self
                .resolution
                .to_possible_value()
                .expect("every resolution has a value")
                .get_name()
                .to_string(),
            flash: self
                .flash
                .to_possible_value()
                .expect("every flash mode has a value")
                .get_name()
                .to_string(),
            controls,
            request_id,
        }
    }
}

#[tokio::main]
async fn main() -> std::process::ExitCode {
    match run(Cli::parse()).await {
        Ok(code) => code,
        Err(error) => {
            eprintln!("camera-control: {error}");
            std::process::ExitCode::FAILURE
        }
    }
}

async fn run(cli: Cli) -> Result<std::process::ExitCode> {
    let connection = match &cli.command {
        CommandKind::Status { connection, .. }
        | CommandKind::Capture { connection, .. }
        | CommandKind::TimeLapse { connection, .. } => connection,
    };
    let password = read_password(&connection.password_env)?;
    let mut client = Client::with_ca_file(
        connection.url.clone(),
        password,
        &connection.ca,
        Options {
            timeout: Duration::from_secs_f64(connection.timeout),
            reconnect_attempts: connection.reconnect_attempts,
            ..Options::default()
        },
    )
    .await?;
    client.connect().await?;

    let code = match &cli.command {
        CommandKind::Status { compact, .. } => {
            let status = client.status().await?;
            println!("{}", render(&status, *compact));
            std::process::ExitCode::SUCCESS
        }
        CommandKind::Capture {
            capture,
            output,
            request_id,
            ..
        } => run_capture(&mut client, capture, output, request_id.clone()).await?,
        CommandKind::TimeLapse {
            capture,
            interval,
            count,
            duration,
            poll,
            ..
        } => run_time_lapse(&mut client, capture, *interval, *count, *duration, *poll).await?,
    };
    client.close().await;
    Ok(code)
}

async fn run_capture(
    client: &mut Client,
    capture: &CaptureArgs,
    output: &PathBuf,
    request_id: Option<String>,
) -> Result<std::process::ExitCode> {
    let request_id = request_id.unwrap_or_else(|| format!("rust-cli-{}", uuid::Uuid::new_v4()));
    let options = capture.to_options(Some(request_id.clone()));
    let job = client.capture_job(&options).await?;
    if job.get("state").and_then(Value::as_str) != Some("completed") {
        let message = job
            .get("error")
            .and_then(|error| error.get("message"))
            .and_then(Value::as_str)
            .unwrap_or_default();
        return Err(Error::Protocol(format!(
            "capture ended as {}: {message}",
            job.get("state").unwrap_or(&Value::Null)
        )));
    }
    let photo = job
        .get("result")
        .and_then(|result| result.get("photo"))
        .ok_or_else(|| Error::Protocol("completed capture job has no photo".into()))?;
    let jpeg = decode_photo(photo)?;
    if let Some(parent) = output.parent().filter(|parent| !parent.as_os_str().is_empty()) {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(output, &jpeg).await?;
    println!(
        "Saved {} bytes to {} (requestId={request_id})",
        jpeg.len(),
        output.display()
    );
    Ok(std::process::ExitCode::SUCCESS)
}

async fn run_time_lapse(
    client: &mut Client,
    capture: &CaptureArgs,
    interval: f64,
    count: Option<i64>,
    duration: Option<f64>,
    poll: f64,
) -> Result<std::process::ExitCode> {
    let options = capture.to_options(None);
    let mut session = client.start_time_lapse(interval, count, &options).await?;
    let session_id = session
        .get("id")
        .and_then(Value::as_str)
        .ok_or_else(|| Error::Protocol("the session has no id".into()))?
        .to_string();
    println!("Started {session_id}; press Ctrl+C to stop.");

    let deadline = duration.map(|seconds| Instant::now() + Duration::from_secs_f64(seconds));
    let mut last_signature = String::new();
    let mut cancel = false;
    while !is_terminal(&session) {
        let signature = format!(
            "{}/{}",
            field(&session, "state"),
            field(&session, "capturedCount")
        );
        if signature != last_signature {
            let target = match session.get("targetCount") {
                Some(Value::Null) | None => "∞".to_string(),
                Some(value) => value.to_string(),
            };
            println!(
                "{}: {}/{target}",
                field(&session, "state"),
                field(&session, "capturedCount")
            );
            last_signature = signature;
        }
        if deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            cancel = true;
            break;
        }
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_secs_f64(poll)) => {}
            _ = tokio::signal::ctrl_c() => {
                eprintln!("\nCancellation requested…");
                cancel = true;
            }
        }
        if cancel {
            break;
        }
        session = client.capture_session_status(&session_id).await?;
    }
    if cancel {
        session = client.cancel_capture_session(&session_id).await?;
    }

    println!("{}", render(&session, false));
    Ok(match session.get("state").and_then(Value::as_str) {
        Some("completed" | "cancelled") => std::process::ExitCode::SUCCESS,
        _ => std::process::ExitCode::FAILURE,
    })
}

fn is_terminal(session: &Value) -> bool {
    session
        .get("state")
        .and_then(Value::as_str)
        .is_some_and(|state| TERMINAL_SESSION_STATES.contains(&state))
}

fn field(value: &Value, key: &str) -> String {
    match value.get(key) {
        Some(Value::String(text)) => text.clone(),
        Some(other) => other.to_string(),
        None => "null".into(),
    }
}

/// serde_json keeps object keys sorted only with the `preserve_order` feature
/// disabled, which is the default, so plain serialization is already stable.
fn render(value: &Value, compact: bool) -> String {
    if compact {
        value.to_string()
    } else {
        serde_json::to_string_pretty(value).unwrap_or_else(|_| value.to_string())
    }
}

fn interval_seconds(raw: &str) -> std::result::Result<f64, String> {
    let seconds: f64 = raw.parse().map_err(|_| "expected a number".to_string())?;
    if !(1.0..=86_400.0).contains(&seconds) {
        return Err("must be between 1 and 86400 seconds".into());
    }
    Ok(seconds)
}

fn positive_seconds(raw: &str) -> std::result::Result<f64, String> {
    let seconds: f64 = raw.parse().map_err(|_| "expected a number".to_string())?;
    if seconds <= 0.0 {
        return Err("must be positive".into());
    }
    Ok(seconds)
}

/// Prefer the environment variable so that the password never appears in
/// process arguments, and prompt without echo otherwise.
fn read_password(variable: &str) -> Result<String> {
    if let Ok(password) = std::env::var(variable) {
        return Ok(password);
    }
    eprint!("Camera Control password: ");
    std::io::stderr().flush().ok();
    let echo_disabled = set_terminal_echo(false);
    let mut password = String::new();
    let read = std::io::stdin().lock().read_line(&mut password);
    if echo_disabled {
        set_terminal_echo(true);
    }
    eprintln!();
    read.map_err(|error| Error::Config(error.to_string()))?;
    Ok(password.trim_end_matches(['\r', '\n']).to_string())
}

fn set_terminal_echo(enabled: bool) -> bool {
    ProcessCommand::new("stty")
        .arg(if enabled { "echo" } else { "-echo" })
        .stdin(Stdio::inherit())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|status| status.success())
        .unwrap_or(false)
}
