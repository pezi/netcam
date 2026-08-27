package app.flutterdev.cameracontrol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * A pull-based WSS channel over {@link java.net.http.WebSocket}.
 *
 * <p>Incoming frames are buffered so that a caller can await one message at a
 * time regardless of arrival order, and continuation frames are reassembled
 * before delivery.
 */
public final class WebSocketChannel implements Channel {

  private final WebSocket webSocket;
  private final BlockingQueue<String> messages;
  private final AtomicReference<Throwable> failure;

  private WebSocketChannel(
      WebSocket webSocket,
      BlockingQueue<String> messages,
      AtomicReference<Throwable> failure) {
    this.webSocket = webSocket;
    this.messages = messages;
    this.failure = failure;
  }

  /**
   * Open one verified-TLS connection.
   *
   * @param url the {@code wss://} endpoint shown by the app.
   * @param sslContext a context that trusts the app's local CA.
   * @param openTimeout bounds the TLS and WebSocket handshake.
   */
  public static WebSocketChannel open(String url, SSLContext sslContext, Duration openTimeout)
      throws CameraControlException {
    HttpClient httpClient = HttpClient.newBuilder()
        .sslContext(sslContext)
        .connectTimeout(openTimeout)
        .build();
    BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Listener listener = new Listener(messages, failure);
    try {
      WebSocket webSocket = httpClient.newWebSocketBuilder()
          .connectTimeout(openTimeout)
          .buildAsync(URI.create(url), listener)
          .get(openTimeout.toMillis(), TimeUnit.MILLISECONDS);
      return new WebSocketChannel(webSocket, messages, failure);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ConnectionInterruptedException("the handshake was interrupted", error);
    } catch (Exception error) {
      throw new ConnectionInterruptedException(
          "the Camera Control connection could not be opened: " + rootMessage(error), error);
    }
  }

  /** Build an {@link SSLContext} that trusts exactly the app's local CA. */
  public static SSLContext sslContextFor(Path caFile) throws CameraControlException {
    try {
      Collection<? extends Certificate> certificates;
      try (var stream = Files.newInputStream(caFile)) {
        certificates = CertificateFactory.getInstance("X.509").generateCertificates(stream);
      }
      if (certificates.isEmpty()) {
        throw new CameraControlException("no certificate was found in " + caFile);
      }
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      int index = 0;
      for (Certificate certificate : certificates) {
        trustStore.setCertificateEntry("camera-control-" + index++, certificate);
      }
      TrustManagerFactory trustManagers =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustManagers.init(trustStore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustManagers.getTrustManagers(), null);
      return context;
    } catch (IOException error) {
      throw new CameraControlException("CA certificate not found: " + caFile, error);
    } catch (Exception error) {
      throw new CameraControlException("the CA certificate is unusable: " + rootMessage(error), error);
    }
  }

  @Override
  public void send(String message) throws CameraControlException {
    checkFailure();
    try {
      webSocket.sendText(message, true).join();
    } catch (Exception error) {
      throw new ConnectionInterruptedException("the request could not be sent", error);
    }
  }

  @Override
  public String receive(Duration timeout) throws CameraControlException {
    String message;
    try {
      message = messages.poll(Math.max(timeout.toMillis(), 0), TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ConnectionInterruptedException("the wait was interrupted", error);
    }
    if (message != null) {
      return message;
    }
    checkFailure();
    throw new ConnectionInterruptedException("timed out waiting for a Camera Control message");
  }

  @Override
  public void close() {
    fail(new ConnectionInterruptedException("the Camera Control connection was closed locally"));
    try {
      webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "")
          .orTimeout(2, TimeUnit.SECONDS)
          .join();
    } catch (Exception ignored) {
      webSocket.abort();
    }
  }

  private void checkFailure() throws CameraControlException {
    Throwable error = failure.get();
    if (error == null) {
      return;
    }
    if (error instanceof CameraControlException known) {
      throw known;
    }
    throw new ConnectionInterruptedException(
        "the Camera Control connection failed: " + rootMessage(error), error);
  }

  private void fail(Throwable error) {
    failure.compareAndSet(null, error);
  }

  private static String rootMessage(Throwable error) {
    Throwable cause = error;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    String message = cause.getMessage();
    return message == null ? cause.getClass().getSimpleName() : message;
  }

  /** Reassembles continuation frames and hands complete messages to the queue. */
  static final class Listener implements WebSocket.Listener {

    private final StringBuilder partial = new StringBuilder();
    private final BlockingQueue<String> messages;
    private final AtomicReference<Throwable> failure;

    Listener(BlockingQueue<String> messages, AtomicReference<Throwable> failure) {
      this.messages = messages;
      this.failure = failure;
    }

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        String message = partial.toString();
        partial.setLength(0);
        messages.add(message);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(
        WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
      onError(webSocket, new ProtocolException("the server returned a non-JSON WebSocket message"));
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      failure.compareAndSet(null, new ConnectionInterruptedException(
          "the Camera Control connection closed (" + statusCode + ")"));
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      failure.compareAndSet(null, error);
    }
  }
}
