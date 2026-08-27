//! Errors raised by the Camera Control client.

use std::fmt;

use serde_json::Value;

/// Every failure mode of the Camera Control protocol client.
#[derive(Debug)]
pub enum Error {
    /// A structured error returned by the Camera Control API.
    Api {
        code: String,
        message: String,
        details: Option<Value>,
    },
    /// The peer returned a malformed or unsupported protocol message.
    Protocol(String),
    /// The connection could not be restored within the configured attempts.
    ConnectionInterrupted(String),
    /// A request or event wait exceeded its deadline.
    Timeout(String),
    /// A transport, TLS, or filesystem failure.
    Transport(String),
    /// The client was configured with unusable arguments.
    Config(String),
}

impl Error {
    /// Whether retrying the operation cannot help.
    pub fn is_permanent(&self) -> bool {
        matches!(self, Error::Api { .. } | Error::Protocol(_) | Error::Config(_))
    }

    pub(crate) fn protocol(message: impl Into<String>) -> Self {
        Error::Protocol(message.into())
    }

    pub(crate) fn transport(message: impl Into<String>) -> Self {
        Error::Transport(message.into())
    }
}

impl fmt::Display for Error {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Api { code, message, .. } => write!(formatter, "API error {code}: {message}"),
            Error::Protocol(message)
            | Error::ConnectionInterrupted(message)
            | Error::Timeout(message)
            | Error::Transport(message)
            | Error::Config(message) => formatter.write_str(message),
        }
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        Error::Transport(error.to_string())
    }
}

/// A result carrying a Camera Control [`Error`].
pub type Result<T> = std::result::Result<T, Error>;
