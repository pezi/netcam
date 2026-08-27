package app.flutterdev.cameracontrol;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;

/**
 * One authenticated Camera Control connection.
 *
 * <p>Requests are serialized. Status and capture-session polling reconnect
 * automatically with bounded exponential backoff. A capture job reuses a
 * stable {@code requestId} when it is replayed after a dropped socket so the
 * running server can deduplicate it.
 *
 * <p>Instances are not safe for concurrent use by multiple threads.
 */
public final class CameraControlClient implements AutoCloseable {

  /** The only Camera Control protocol this client speaks. */
  public static final long API_PROTOCOL_VERSION = 4;

  /** Capture-job states that end a job. */
  public static final Set<String> TERMINAL_JOB_STATES =
      Set.of("completed", "failed", "cancelled");
  /** Capture-session states that end a session. */
  public static final Set<String> TERMINAL_SESSION_STATES =
      Set.of("completed", "failed", "cancelled");

  private final String url;
  private final String password;
  private final Options options;
  private final ChannelFactory channelFactory;
  private final Deque<Map<String, Object>> events = new ArrayDeque<>();
  private Channel channel;
  private long requestNumber;

  /** Opens one channel per connection attempt. */
  @FunctionalInterface
  public interface ChannelFactory {
    Channel open(String url, Duration openTimeout) throws CameraControlException;
  }

  /** Connection tuning shared by every request. */
  public record Options(Duration timeout, int reconnectAttempts, Duration reconnectDelay) {

    public Options {
      if (timeout == null || timeout.isNegative() || timeout.isZero()) {
        throw new IllegalArgumentException("timeout must be positive");
      }
      if (reconnectAttempts < 0) {
        throw new IllegalArgumentException("reconnectAttempts cannot be negative");
      }
    }

    /** Thirty-second requests with five idempotent retries. */
    public static Options defaults() {
      return new Options(Duration.ofSeconds(30), 5, Duration.ofSeconds(1));
    }

    public Options withTimeout(Duration timeout) {
      return new Options(timeout, reconnectAttempts, reconnectDelay);
    }

    public Options withReconnectAttempts(int reconnectAttempts) {
      return new Options(timeout, reconnectAttempts, reconnectDelay);
    }
  }

  /** The capture settings shared by jobs and sessions. */
  public record CaptureOptions(
      int camera, String resolution, String flash, Map<String, Object> controls, String requestId) {

    /** Camera 1, high resolution, no flash, no extra controls. */
    public static CaptureOptions defaults() {
      return new CaptureOptions(1, "high", "off", Map.of(), null);
    }

    public CaptureOptions withRequestId(String requestId) {
      return new CaptureOptions(camera, resolution, flash, controls, requestId);
    }
  }

  /** Build a client over the default verified-TLS transport. */
  public static CameraControlClient withCaFile(
      String url, String password, Path caFile, Options options) throws CameraControlException {
    SSLContext sslContext = WebSocketChannel.sslContextFor(caFile);
    return new CameraControlClient(url, password, options,
        (endpoint, openTimeout) -> WebSocketChannel.open(endpoint, sslContext, openTimeout));
  }

  /** Build a client over any transport, which is how tests inject a fake. */
  public CameraControlClient(
      String url, String password, Options options, ChannelFactory channelFactory) {
    if (!url.startsWith("wss://")) {
      throw new IllegalArgumentException("Camera Control requires a wss:// URL");
    }
    this.url = url;
    this.password = password;
    this.options = options;
    this.channelFactory = channelFactory;
  }

  /** Whether a socket is currently retained. */
  public boolean isConnected() {
    return channel != null;
  }

  /** Open, validate protocol v4, and authenticate one WSS connection. */
  public void connect() throws CameraControlException {
    close();
    Duration openTimeout =
        options.timeout().compareTo(Duration.ofSeconds(10)) < 0
            ? options.timeout()
            : Duration.ofSeconds(10);
    channel = channelFactory.open(url, openTimeout);
    try {
      Map<String, Object> hello = receive(options.timeout());
      if (!"hello".equals(hello.get("event")) || !isProtocolVersion(hello.get("protocolVersion"))) {
        throw new ProtocolException("unsupported server greeting: " + Json.write(hello));
      }
      Map<String, Object> authenticated =
          requestOnce("authenticate", Map.of("password", password), options.timeout());
      if (!isProtocolVersion(authenticated.get("protocolVersion"))) {
        throw new ProtocolException(
            "authentication returned an unsupported protocol: " + Json.write(authenticated));
      }
    } catch (CameraControlException | RuntimeException error) {
      close();
      throw error;
    }
  }

  /** Close the current socket without discarding queued events. */
  @Override
  public void close() {
    Channel current = channel;
    channel = null;
    if (current != null) {
      current.close();
    }
  }

  /**
   * Send one action and return its result object.
   *
   * <p>Pass {@code retryOnDisconnect = false} for non-idempotent operations
   * such as the legacy direct {@code capture} action.
   */
  public Map<String, Object> request(
      String action, Map<String, Object> parameters, boolean retryOnDisconnect)
      throws CameraControlException {
    int attempts = retryOnDisconnect ? options.reconnectAttempts() : 0;
    CameraControlException lastError = null;
    for (int attempt = 0; attempt <= attempts; attempt++) {
      try {
        if (channel == null) {
          connect();
        }
        return requestOnce(action, parameters, options.timeout());
      } catch (CameraControlException error) {
        if (error.isPermanent()) {
          throw error;
        }
        lastError = error;
        close();
        if (attempt >= attempts) {
          break;
        }
        backoff(attempt);
      }
    }
    throw new ConnectionInterruptedException(
        "connection interrupted while performing '" + action + "'", lastError);
  }

  /** Camera, device, capture-session, and reliability status. */
  public Map<String, Object> status() throws CameraControlException {
    return request("status", Map.of(), true);
  }

  /** Capture once through the idempotent job API and await completion. */
  public Map<String, Object> captureJob(CaptureOptions capture) throws CameraControlException {
    String requestId =
        capture.requestId() != null ? capture.requestId() : "java-" + UUID.randomUUID();
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("requestId", requestId);
    parameters.put("camera", capture.camera());
    parameters.put("resolution", capture.resolution());
    parameters.put("flash", capture.flash());
    parameters.put("controls", capture.controls());

    for (int resume = 0; resume <= options.reconnectAttempts(); resume++) {
      Map<String, Object> result = request("captureJob", parameters, true);
      Map<String, Object> job = objectField(result, "job", "captureJob result.job");
      if (TERMINAL_JOB_STATES.contains(job.get("state"))) {
        return job;
      }
      Object jobId = job.get("id");
      try {
        Map<String, Object> event = waitForEvent("captureJob", options.timeout(), candidate -> {
          Object pending = candidate.get("job");
          if (!(pending instanceof Map<?, ?> snapshot)) {
            return false;
          }
          return java.util.Objects.equals(snapshot.get("id"), jobId)
              && TERMINAL_JOB_STATES.contains(snapshot.get("state"));
        });
        return objectField(event, "job", "captureJob event.job");
      } catch (ConnectionInterruptedException error) {
        // Re-submit the same semantic request ID. The running app deduplicates
        // it and returns the retained job snapshot.
        if (resume >= options.reconnectAttempts()) {
          throw error;
        }
      }
    }
    throw new ConnectionInterruptedException("capture job recovery attempts exhausted");
  }

  /**
   * Start a time-lapse capture session and return its snapshot.
   *
   * @param count stops the session after this many photos, or {@code null} to
   *     run until it is cancelled.
   */
  public Map<String, Object> startTimeLapse(
      double intervalSeconds, Integer count, CaptureOptions capture)
      throws CameraControlException {
    Map<String, Object> plan = new LinkedHashMap<>();
    plan.put("mode", "timeLapse");
    plan.put("intervalSeconds", intervalSeconds);
    if (count != null) {
      plan.put("count", count);
    }
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("camera", capture.camera());
    parameters.put("resolution", capture.resolution());
    parameters.put("flash", capture.flash());
    parameters.put("controls", capture.controls());
    parameters.put("plan", plan);
    Map<String, Object> result = request("startCaptureSession", parameters, false);
    return objectField(result, "session", "startCaptureSession result.session");
  }

  /** Poll the latest retained capture session across reconnects. */
  public Map<String, Object> captureSessionStatus(String sessionId) throws CameraControlException {
    Map<String, Object> status = status();
    Object session = status.get("captureSession");
    if (!(session instanceof Map<?, ?> snapshot) || !sessionId.equals(snapshot.get("id"))) {
      throw new ProtocolException("the capture session is no longer retained by the server");
    }
    return objectField(status, "captureSession", "status.captureSession");
  }

  /** Cancel one retained time-lapse/capture session. */
  public Map<String, Object> cancelCaptureSession(String sessionId) throws CameraControlException {
    Map<String, Object> result =
        request("cancelCaptureSession", Map.of("sessionId", sessionId), true);
    return objectField(result, "session", "cancelCaptureSession result.session");
  }

  /** Return the next matching unsolicited event. */
  public Map<String, Object> waitForEvent(
      String eventName, Duration timeout, Predicate<Map<String, Object>> predicate)
      throws CameraControlException {
    Predicate<Map<String, Object>> matches =
        event -> eventName.equals(event.get("event")) && (predicate == null || predicate.test(event));
    List<Map<String, Object>> queued = new ArrayList<>(events);
    for (int index = 0; index < queued.size(); index++) {
      if (matches.test(queued.get(index))) {
        Map<String, Object> event = queued.remove(index);
        events.clear();
        events.addAll(queued);
        return event;
      }
    }

    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
      if (remaining.isNegative() || remaining.isZero()) {
        throw new ConnectionInterruptedException("timed out waiting for '" + eventName + "'");
      }
      Map<String, Object> message;
      try {
        message = receive(remaining);
      } catch (CameraControlException error) {
        if (error.isPermanent()) {
          throw error;
        }
        close();
        throw new ConnectionInterruptedException(
            "connection interrupted while waiting for '" + eventName + "'", error);
      }
      if (!message.containsKey("event")) {
        throw new ProtocolException("unexpected response while waiting: " + Json.write(message));
      }
      if (matches.test(message)) {
        return message;
      }
      events.addLast(message);
    }
  }

  private Map<String, Object> requestOnce(
      String action, Map<String, Object> parameters, Duration timeout)
      throws CameraControlException {
    Channel current = channel;
    if (current == null) {
      throw new ConnectionInterruptedException("no active Camera Control connection");
    }
    requestNumber++;
    String requestId = "java-" + requestNumber;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", requestId);
    payload.put("action", action);
    payload.putAll(parameters);
    current.send(Json.write(payload));

    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
      if (remaining.isNegative() || remaining.isZero()) {
        throw new ConnectionInterruptedException("timed out waiting for '" + action + "'");
      }
      Map<String, Object> response = receive(remaining);
      if (response.containsKey("event")) {
        events.addLast(response);
        continue;
      }
      if (!requestId.equals(response.get("id"))) {
        throw new ProtocolException("unexpected response id: " + response.get("id"));
      }
      if (!Boolean.TRUE.equals(response.get("ok"))) {
        Object error = response.get("error");
        if (!(error instanceof Map<?, ?> failure)) {
          throw new ProtocolException("malformed API error: " + Json.write(response));
        }
        throw new ApiException(
            text(failure.get("code"), "unknown"),
            text(failure.get("message"), "no message"),
            failure.get("details"));
      }
      return objectField(response, "result", action + " result");
    }
  }

  private Map<String, Object> receive(Duration timeout) throws CameraControlException {
    Channel current = channel;
    if (current == null) {
      throw new ConnectionInterruptedException("no active Camera Control connection");
    }
    String message = current.receive(timeout);
    Object decoded;
    try {
      decoded = Json.parse(message);
    } catch (IllegalArgumentException error) {
      throw new ProtocolException("the server returned invalid JSON", error);
    }
    if (!(decoded instanceof Map)) {
      throw new ProtocolException("the WebSocket message isn't a JSON object");
    }
    return asObject(decoded);
  }

  private void backoff(int attempt) throws CameraControlException {
    long millis = Math.min(options.reconnectDelay().toMillis() << Math.min(attempt, 4), 10_000L);
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new ConnectionInterruptedException("the reconnect delay was interrupted", error);
    }
  }

  /** Validate and decode a JPEG photo object returned by the API. */
  public static byte[] decodePhoto(Map<String, Object> photo) throws ProtocolException {
    if (!"image/jpeg".equals(photo.get("mimeType"))) {
      throw new ProtocolException("unexpected photo metadata: " + Json.write(photo));
    }
    if (!(photo.get("dataBase64") instanceof String encoded)) {
      throw new ProtocolException("the photo doesn't contain Base64 JPEG data");
    }
    try {
      return Base64.getDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new ProtocolException("the photo contains invalid Base64 data", error);
    }
  }

  /** Read one nested object out of a decoded message. */
  public static Map<String, Object> objectField(Map<String, Object> parent, String key, String label)
      throws ProtocolException {
    Object value = parent.get(key);
    if (!(value instanceof Map)) {
      throw new ProtocolException(label + " isn't a JSON object");
    }
    return asObject(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObject(Object value) {
    return (Map<String, Object>) value;
  }

  private static String text(Object value, String fallback) {
    return value == null ? fallback : String.valueOf(value);
  }

  private static boolean isProtocolVersion(Object value) {
    return value instanceof Number number && number.longValue() == API_PROTOCOL_VERSION;
  }
}
