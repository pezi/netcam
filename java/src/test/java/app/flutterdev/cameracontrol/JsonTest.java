package app.flutterdev.cameracontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The bundled JSON reader and writer must round-trip protocol messages. */
class JsonTest {

  @Test
  void roundTripsProtocolMessages() {
    String encoded = "{\"id\":\"java-1\",\"ok\":true,\"result\":{\"cameras\":[0,1],"
        + "\"controls\":{\"jpegQuality\":90},\"note\":\"line\\nbreak\",\"ratio\":1.5,"
        + "\"missing\":null}}";
    Map<String, Object> decoded = Json.parseObject(encoded);
    assertEquals("java-1", decoded.get("id"));
    assertEquals(Boolean.TRUE, decoded.get("ok"));
    assertEquals(encoded, Json.write(decoded));
  }

  @Test
  void sortsKeysWhenPrettyPrinting() {
    String pretty = Json.writePretty(Map.of("b", 1, "a", List.of(1, 2)));
    assertEquals("{\n  \"a\": [\n    1,\n    2\n  ],\n  \"b\": 1\n}", pretty);
  }

  @Test
  void rejectsTrailingContent() {
    assertThrows(IllegalArgumentException.class, () -> Json.parse("{} {}"));
  }
}
