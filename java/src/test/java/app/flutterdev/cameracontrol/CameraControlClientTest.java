package app.flutterdev.cameracontrol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.flutterdev.cameracontrol.CameraControlClient.Options;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Offline protocol tests driven by a scripted in-memory channel. */
class CameraControlClientTest {

  /** Replays scripted server messages and answers requests the way the app does. */
  private static final class FakeChannel implements Channel {

    private final Deque<Map<String, Object>> messages = new ArrayDeque<>();
    private final List<Map<String, Object>> sent = new ArrayList<>();
    private boolean closed;

    FakeChannel(List<Map<String, Object>> script) {
      messages.addAll(script);
    }

    @Override
    public void send(String message) {
      Map<String, Object> payload = Json.parseObject(message);
      sent.add(payload);
      Object id = payload.get("id");
      switch (String.valueOf(payload.get("action"))) {
        case "authenticate" -> messages.addFirst(Map.of(
            "id", id, "ok", true,
            "result", Map.of("protocolVersion", CameraControlClient.API_PROTOCOL_VERSION)));
        case "status" -> {
          messages.addLast(Map.of(
              "event", "captureSession",
              "session", Map.of("id", "session-1", "state", "waiting")));
          messages.addLast(Map.of("id", id, "ok", true, "result", Map.of("ready", true)));
        }
        default -> { }
      }
    }

    @Override
    public String receive(Duration timeout) throws CameraControlException {
      Map<String, Object> message = messages.pollFirst();
      if (message == null) {
        throw new ConnectionInterruptedException("the connection was closed by the peer");
      }
      return Json.write(message);
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static Map<String, Object> hello(long protocolVersion) {
    return Map.of("event", "hello", "protocolVersion", protocolVersion);
  }

  private static CameraControlClient clientFor(FakeChannel channel) {
    return new CameraControlClient(
        "wss://192.168.1.50:8080/ws",
        "secret123",
        Options.defaults().withTimeout(Duration.ofSeconds(1)),
        (url, openTimeout) -> channel);
  }

  @Test
  void authenticatesProtocolV4AndQueuesEvents() throws Exception {
    FakeChannel channel =
        new FakeChannel(List.of(hello(CameraControlClient.API_PROTOCOL_VERSION)));
    CameraControlClient client = clientFor(channel);
    client.connect();

    assertEquals(Map.of("ready", true), client.status());
    Map<String, Object> event =
        client.waitForEvent("captureSession", Duration.ofMillis(100), null);
    assertEquals("session-1", CameraControlClient
        .objectField(event, "session", "event.session").get("id"));
    assertEquals("authenticate", channel.sent.get(0).get("action"));

    client.close();
    assertTrue(channel.closed);
  }

  @Test
  void rejectsApiErrors() throws Exception {
    FakeChannel channel = new FakeChannel(List.of(
        hello(CameraControlClient.API_PROTOCOL_VERSION),
        Map.of("id", "java-2", "ok", false,
            "error", Map.of("code", "camera_busy", "message", "Busy"))));
    CameraControlClient client = clientFor(channel);
    client.connect();

    ApiException error = assertThrows(ApiException.class,
        () -> client.request("cancelCaptureSession", Map.of("sessionId", "session-1"), false));
    assertEquals("camera_busy", error.code());
  }

  @Test
  void rejectsOldProtocolServers() {
    FakeChannel channel = new FakeChannel(List.of(hello(1)));
    CameraControlClient client = clientFor(channel);
    ProtocolException error = assertThrows(ProtocolException.class, client::connect);
    assertTrue(error.getMessage().contains("unsupported server greeting"));
  }

  @Test
  void requiresASecureUrl() {
    assertThrows(IllegalArgumentException.class, () -> new CameraControlClient(
        "ws://192.168.1.50:8080/ws", "x", Options.defaults(), (url, timeout) -> null));
  }

  @Test
  void decodesOnlyJpegPhotos() throws Exception {
    assertThrows(ProtocolException.class,
        () -> CameraControlClient.decodePhoto(Map.of("mimeType", "image/png")));
    byte[] jpeg = CameraControlClient.decodePhoto(
        Map.of("mimeType", "image/jpeg", "dataBase64", "/9j/4AAQ"));
    assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00, 0x10},
        jpeg);
  }
}
