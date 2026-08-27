//! The WebSocket transport and its verified-TLS connector.

use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use futures_util::{SinkExt, StreamExt};
use tokio_rustls::rustls::pki_types::ServerName;
use tokio_rustls::rustls::{ClientConfig, RootCertStore};
use tokio_tungstenite::tungstenite::protocol::{Message, WebSocketConfig};
use tokio_tungstenite::{Connector, MaybeTlsStream, WebSocketStream};

use crate::error::{Error, Result};

/// A boxed future, so that the transport traits stay object safe.
pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// One open text-message channel. Implement it to drive the client in tests.
pub trait Channel: Send {
    fn send(&mut self, message: String) -> BoxFuture<'_, Result<()>>;
    fn receive(&mut self) -> BoxFuture<'_, Result<String>>;
    fn close(&mut self) -> BoxFuture<'_, ()>;
}

/// Opens one channel per connection attempt.
pub trait Connect: Send + Sync {
    fn connect(&self, url: String) -> BoxFuture<'static, Result<Box<dyn Channel>>>;
}

/// The default connector: a WSS handshake against the app's local CA.
pub struct TlsConnector {
    config: Arc<ClientConfig>,
}

impl TlsConnector {
    /// Build a connector that trusts exactly the certificates in `ca_pem`.
    pub fn from_ca_pem(ca_pem: &[u8]) -> Result<Self> {
        let mut roots = RootCertStore::empty();
        let mut reader = std::io::BufReader::new(ca_pem);
        let mut added = 0usize;
        for certificate in rustls_pemfile::certs(&mut reader) {
            let certificate = certificate.map_err(|error| {
                Error::Config(format!("the CA certificate could not be parsed: {error}"))
            })?;
            roots
                .add(certificate)
                .map_err(|error| Error::Config(format!("the CA certificate is unusable: {error}")))?;
            added += 1;
        }
        if added == 0 {
            return Err(Error::Config("no certificate was found in the CA file".into()));
        }
        let provider = Arc::new(tokio_rustls::rustls::crypto::ring::default_provider());
        let config = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .map_err(|error| Error::Config(error.to_string()))?
            .with_root_certificates(roots)
            .with_no_client_auth();
        Ok(Self {
            config: Arc::new(config),
        })
    }

    /// Load the verified local CA certificate from disk.
    pub async fn from_ca_file(path: &std::path::Path) -> Result<Self> {
        let pem = tokio::fs::read(path)
            .await
            .map_err(|_| Error::Config(format!("CA certificate not found: {}", path.display())))?;
        Self::from_ca_pem(&pem)
    }
}

impl Connect for TlsConnector {
    fn connect(&self, url: String) -> BoxFuture<'static, Result<Box<dyn Channel>>> {
        let config = self.config.clone();
        Box::pin(async move {
            // Base64 JPEG payloads are large, so the frame limits stay open.
            let websocket_config = WebSocketConfig::default()
                .max_message_size(None)
                .max_frame_size(None);
            let (stream, _response) = tokio_tungstenite::connect_async_tls_with_config(
                url,
                Some(websocket_config),
                false,
                Some(Connector::Rustls(config)),
            )
            .await
            .map_err(|error| Error::transport(error.to_string()))?;
            Ok(Box::new(WebSocketChannel { stream }) as Box<dyn Channel>)
        })
    }
}

struct WebSocketChannel {
    stream: WebSocketStream<MaybeTlsStream<tokio::net::TcpStream>>,
}

impl Channel for WebSocketChannel {
    fn send(&mut self, message: String) -> BoxFuture<'_, Result<()>> {
        Box::pin(async move {
            self.stream
                .send(Message::Text(message.into()))
                .await
                .map_err(|error| Error::transport(error.to_string()))
        })
    }

    fn receive(&mut self) -> BoxFuture<'_, Result<String>> {
        Box::pin(async move {
            loop {
                match self.stream.next().await {
                    Some(Ok(Message::Text(text))) => return Ok(text.to_string()),
                    Some(Ok(Message::Ping(_) | Message::Pong(_) | Message::Frame(_))) => continue,
                    Some(Ok(Message::Close(_))) | None => {
                        return Err(Error::transport("the Camera Control connection closed"))
                    }
                    Some(Ok(Message::Binary(_))) => {
                        return Err(Error::protocol(
                            "the server returned a non-JSON WebSocket message",
                        ))
                    }
                    Some(Err(error)) => return Err(Error::transport(error.to_string())),
                }
            }
        })
    }

    fn close(&mut self) -> BoxFuture<'_, ()> {
        Box::pin(async move {
            let _ = self.stream.close(None).await;
        })
    }
}

/// Reject anything but a `wss://` endpoint before a connection is attempted.
pub fn validate_endpoint(url: &str) -> Result<()> {
    if !url.starts_with("wss://") {
        return Err(Error::Config("Camera Control requires a wss:// URL".into()));
    }
    let authority = &url["wss://".len()..];
    let host = authority.split(['/', ':']).next().unwrap_or_default();
    if host.is_empty() || ServerName::try_from(host.to_owned()).is_err() {
        return Err(Error::Config(format!("the URL has no usable host: {url}")));
    }
    Ok(())
}
