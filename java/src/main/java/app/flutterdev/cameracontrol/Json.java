package app.flutterdev.cameracontrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A minimal RFC 8259 reader and writer.
 *
 * <p>The Camera Control protocol carries small, well-defined JSON documents,
 * so the client keeps its dependency surface empty instead of pulling in a
 * general-purpose binding library.
 */
public final class Json {

  private final String text;
  private int position;

  private Json(String text) {
    this.text = text;
  }

  /** Parse one JSON document into maps, lists, strings, doubles, booleans, and nulls. */
  public static Object parse(String text) {
    Json reader = new Json(text);
    reader.skipWhitespace();
    Object value = reader.readValue();
    reader.skipWhitespace();
    if (reader.position != text.length()) {
      throw new IllegalArgumentException("trailing content after the JSON document");
    }
    return value;
  }

  /** Parse a document that must be a JSON object. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    Object value = parse(text);
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException("the document isn't a JSON object");
    }
    return (Map<String, Object>) value;
  }

  /** Serialize a value in the compact form the protocol expects. */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out, null, 0);
    return out.toString();
  }

  /** Serialize a value with sorted keys and two-space indentation. */
  public static String writePretty(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out, "  ", 0);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static void write(Object value, StringBuilder out, String indent, int depth) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String string) {
      writeString(string, out);
    } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
      out.append(value);
    } else if (value instanceof Number number) {
      double raw = number.doubleValue();
      if (raw == Math.rint(raw) && !Double.isInfinite(raw) && Math.abs(raw) < 1e15) {
        out.append((long) raw);
      } else {
        out.append(raw);
      }
    } else if (value instanceof Map<?, ?> map) {
      writeMap(indent == null ? (Map<String, Object>) map : new TreeMap<>((Map<String, Object>) map),
          out, indent, depth);
    } else if (value instanceof List<?> list) {
      writeList(list, out, indent, depth);
    } else {
      writeString(String.valueOf(value), out);
    }
  }

  private static void writeMap(Map<String, Object> map, StringBuilder out, String indent, int depth) {
    if (map.isEmpty()) {
      out.append("{}");
      return;
    }
    out.append('{');
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      newline(out, indent, depth + 1);
      writeString(entry.getKey(), out);
      out.append(':');
      if (indent != null) {
        out.append(' ');
      }
      write(entry.getValue(), out, indent, depth + 1);
    }
    newline(out, indent, depth);
    out.append('}');
  }

  private static void writeList(List<?> list, StringBuilder out, String indent, int depth) {
    if (list.isEmpty()) {
      out.append("[]");
      return;
    }
    out.append('[');
    boolean first = true;
    for (Object element : list) {
      if (!first) {
        out.append(',');
      }
      first = false;
      newline(out, indent, depth + 1);
      write(element, out, indent, depth + 1);
    }
    newline(out, indent, depth);
    out.append(']');
  }

  private static void newline(StringBuilder out, String indent, int depth) {
    if (indent == null) {
      return;
    }
    out.append('\n');
    out.append(indent.repeat(depth));
  }

  private static void writeString(String value, StringBuilder out) {
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (character < 0x20) {
            out.append(String.format("\\u%04x", (int) character));
          } else {
            out.append(character);
          }
        }
      }
    }
    out.append('"');
  }

  private Object readValue() {
    char character = peek();
    return switch (character) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't' -> readLiteral("true", Boolean.TRUE);
      case 'f' -> readLiteral("false", Boolean.FALSE);
      case 'n' -> readLiteral("null", null);
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    expect('{');
    Map<String, Object> map = new LinkedHashMap<>();
    skipWhitespace();
    if (peek() == '}') {
      position++;
      return map;
    }
    while (true) {
      skipWhitespace();
      String key = readString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      map.put(key, readValue());
      skipWhitespace();
      char character = next();
      if (character == '}') {
        return map;
      }
      if (character != ',') {
        throw new IllegalArgumentException("expected ',' or '}' at offset " + position);
      }
    }
  }

  private List<Object> readArray() {
    expect('[');
    List<Object> list = new ArrayList<>();
    skipWhitespace();
    if (peek() == ']') {
      position++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(readValue());
      skipWhitespace();
      char character = next();
      if (character == ']') {
        return list;
      }
      if (character != ',') {
        throw new IllegalArgumentException("expected ',' or ']' at offset " + position);
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder value = new StringBuilder();
    while (true) {
      char character = next();
      if (character == '"') {
        return value.toString();
      }
      if (character != '\\') {
        value.append(character);
        continue;
      }
      char escape = next();
      switch (escape) {
        case '"' -> value.append('"');
        case '\\' -> value.append('\\');
        case '/' -> value.append('/');
        case 'b' -> value.append('\b');
        case 'f' -> value.append('\f');
        case 'n' -> value.append('\n');
        case 'r' -> value.append('\r');
        case 't' -> value.append('\t');
        case 'u' -> {
          value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
          position += 4;
        }
        default -> throw new IllegalArgumentException("invalid escape at offset " + position);
      }
    }
  }

  private Object readLiteral(String literal, Object value) {
    if (!text.startsWith(literal, position)) {
      throw new IllegalArgumentException("invalid literal at offset " + position);
    }
    position += literal.length();
    return value;
  }

  private Object readNumber() {
    int start = position;
    while (position < text.length() && "+-.eE0123456789".indexOf(text.charAt(position)) >= 0) {
      position++;
    }
    if (start == position) {
      throw new IllegalArgumentException("invalid value at offset " + position);
    }
    String raw = text.substring(start, position);
    try {
      if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
        return Long.parseLong(raw);
      }
      return Double.parseDouble(raw);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("invalid number at offset " + start, error);
    }
  }

  private void skipWhitespace() {
    while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
      position++;
    }
  }

  private char peek() {
    if (position >= text.length()) {
      throw new IllegalArgumentException("the JSON document ended unexpectedly");
    }
    return text.charAt(position);
  }

  private char next() {
    char character = peek();
    position++;
    return character;
  }

  private void expect(char expected) {
    if (next() != expected) {
      throw new IllegalArgumentException("expected '" + expected + "' at offset " + (position - 1));
    }
  }
}
