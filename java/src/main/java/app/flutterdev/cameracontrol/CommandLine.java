package app.flutterdev.cameracontrol;

import app.flutterdev.cameracontrol.CameraControlClient.CaptureOptions;
import app.flutterdev.cameracontrol.CameraControlClient.Options;
import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Command-line interface for Camera Control automation. */
public final class CommandLine {

  private static final List<String> RESOLUTIONS =
      List.of("low", "medium", "high", "veryHigh", "ultraHigh", "max");
  private static final List<String> FLASH_MODES = List.of("off", "auto", "always", "torch");

  private static final String USAGE = """
      Control Camera Control through its verified-TLS WSS API.

      usage: camera-control <command> <wss-url> [options]

      commands:
        status       print camera, device, capture-session, and reliability status
        capture      capture and save one JPEG through the idempotent job API
        time-lapse   start, poll, and cancel a time-lapse capture session

      connection options:
        --ca FILE                 verified Camera Control local CA certificate (required)
        --password-env NAME       environment variable holding the password
                                  (default: CAMERA_CONTROL_PASSWORD)
        --timeout SECONDS         request timeout (default: 30)
        --reconnect-attempts N    idempotent retry attempts (default: 5)

      capture options (capture, time-lapse):
        --camera N                camera index (default: 1)
        --resolution NAME         low, medium, high, veryHigh, ultraHigh, max (default: high)
        --flash MODE              off, auto, always, torch (default: off)
        --jpeg-quality N          1-100

      capture options:
        --output FILE             JPEG output path (default: photo.jpg)
        --request-id ID           stable idempotency key; generated when omitted

      time-lapse options:
        --interval SECONDS        seconds between photos (required)
        --count N                 stop after this many photos
        --duration SECONDS        cancel after this many seconds
        --poll SECONDS            status poll interval (default: 1)
      """;

  private CommandLine() {}

  public static void main(String[] arguments) {
    try {
      System.exit(run(arguments));
    } catch (CameraControlException | IllegalArgumentException | IOException error) {
      System.err.println("camera-control: " + error.getMessage());
      System.exit(1);
    }
  }

  static int run(String[] arguments) throws CameraControlException, IOException {
    if (arguments.length == 0
        || "-h".equals(arguments[0])
        || "--help".equals(arguments[0])
        || "help".equals(arguments[0])) {
      System.err.print(USAGE);
      return arguments.length == 0 ? 2 : 0;
    }
    String command = arguments[0];
    if (!List.of("status", "capture", "time-lapse").contains(command)) {
      throw new IllegalArgumentException("unknown command '" + command + "'");
    }

    Arguments parsed = Arguments.parse(arguments);
    String url = parsed.url();
    if (url == null) {
      throw new IllegalArgumentException("the wss:// URL shown by the app is required");
    }
    Path caFile = Path.of(parsed.require("ca"));
    Options options = new Options(
        Duration.ofMillis(Math.round(parsed.number("timeout", 30) * 1000)),
        (int) parsed.number("reconnect-attempts", 5),
        Duration.ofSeconds(1));

    String password = readPassword(parsed.text("password-env", "CAMERA_CONTROL_PASSWORD"));
    try (CameraControlClient client =
        CameraControlClient.withCaFile(url, password, caFile, options)) {
      client.connect();
      return switch (command) {
        case "status" -> runStatus(client, parsed);
        case "capture" -> runCapture(client, parsed);
        default -> runTimeLapse(client, parsed);
      };
    }
  }

  private static int runStatus(CameraControlClient client, Arguments parsed)
      throws CameraControlException {
    Map<String, Object> status = client.status();
    System.out.println(parsed.flag("compact") ? Json.write(status) : Json.writePretty(status));
    return 0;
  }

  private static int runCapture(CameraControlClient client, Arguments parsed)
      throws CameraControlException, IOException {
    String requestId = parsed.text("request-id", "java-cli-" + UUID.randomUUID());
    Map<String, Object> job = client.captureJob(captureOptions(parsed).withRequestId(requestId));
    if (!"completed".equals(job.get("state"))) {
      Object error = job.get("error");
      Object detail = error instanceof Map<?, ?> failure ? failure.get("message") : null;
      String message = detail == null ? "" : String.valueOf(detail);
      throw new ProtocolException("capture ended as " + job.get("state") + ": " + message);
    }
    Map<String, Object> result =
        CameraControlClient.objectField(job, "result", "captureJob job.result");
    byte[] jpeg = CameraControlClient.decodePhoto(
        CameraControlClient.objectField(result, "photo", "captureJob job.result.photo"));
    Path output = Path.of(parsed.text("output", "photo.jpg"));
    if (output.getParent() != null) {
      Files.createDirectories(output.getParent());
    }
    Files.write(output, jpeg);
    System.out.printf(
        "Saved %d bytes to %s (requestId=%s)%n", jpeg.length, output, requestId);
    return 0;
  }

  private static int runTimeLapse(CameraControlClient client, Arguments parsed)
      throws CameraControlException {
    double interval = parsed.number("interval", Double.NaN);
    if (!(interval >= 1 && interval <= 86400)) {
      throw new IllegalArgumentException("--interval must be between 1 and 86400 seconds");
    }
    Integer count = parsed.has("count") ? (int) parsed.number("count", 0) : null;
    if (count != null && (count < 2 || count > 1000)) {
      throw new IllegalArgumentException("--count must be between 2 and 1000");
    }
    Double duration = parsed.has("duration") ? parsed.number("duration", 0) : null;
    if (duration != null && duration <= 0) {
      throw new IllegalArgumentException("--duration must be positive");
    }
    double poll = parsed.number("poll", 1);
    if (poll <= 0) {
      throw new IllegalArgumentException("--poll must be positive");
    }

    Map<String, Object> session = client.startTimeLapse(interval, count, captureOptions(parsed));
    String sessionId = String.valueOf(session.get("id"));
    System.out.println("Started " + sessionId + "; press Ctrl+C to stop.");

    // A shutdown hook cannot use the socket, so the loop watches a flag that
    // the hook sets and cancels the session itself.
    java.util.concurrent.atomic.AtomicBoolean stopRequested =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    Thread interrupt = new Thread(() -> stopRequested.set(true));
    Runtime.getRuntime().addShutdownHook(interrupt);

    long deadline = duration == null ? Long.MAX_VALUE
        : System.nanoTime() + Math.round(duration * 1_000_000_000d);
    String lastSignature = "";
    boolean cancel = false;
    try {
      while (!CameraControlClient.TERMINAL_SESSION_STATES.contains(session.get("state"))) {
        String signature = session.get("state") + "/" + session.get("capturedCount");
        if (!signature.equals(lastSignature)) {
          Object target = session.get("targetCount");
          System.out.printf("%s: %s/%s%n", session.get("state"), session.get("capturedCount"),
              target == null ? "∞" : target);
          lastSignature = signature;
        }
        if (stopRequested.get() || System.nanoTime() >= deadline) {
          cancel = true;
          break;
        }
        try {
          Thread.sleep(Math.round(poll * 1000));
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          cancel = true;
          break;
        }
        if (stopRequested.get()) {
          cancel = true;
          break;
        }
        session = client.captureSessionStatus(sessionId);
      }
      if (cancel) {
        System.err.println("Cancellation requested…");
        session = client.cancelCaptureSession(sessionId);
      }
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(interrupt);
      } catch (IllegalStateException ignored) {
        // The JVM is already shutting down.
      }
    }

    System.out.println(Json.writePretty(session));
    return List.of("completed", "cancelled").contains(session.get("state")) ? 0 : 1;
  }

  private static CaptureOptions captureOptions(Arguments parsed) {
    Map<String, Object> controls = new LinkedHashMap<>();
    if (parsed.has("jpeg-quality")) {
      int quality = (int) parsed.number("jpeg-quality", 0);
      if (quality < 1 || quality > 100) {
        throw new IllegalArgumentException("--jpeg-quality must be between 1 and 100");
      }
      controls.put("jpegQuality", quality);
    }
    String resolution = parsed.text("resolution", "high");
    if (!RESOLUTIONS.contains(resolution)) {
      throw new IllegalArgumentException("--resolution must be one of " + String.join(", ", RESOLUTIONS));
    }
    String flash = parsed.text("flash", "off");
    if (!FLASH_MODES.contains(flash)) {
      throw new IllegalArgumentException("--flash must be one of " + String.join(", ", FLASH_MODES));
    }
    return new CaptureOptions(
        (int) parsed.number("camera", 1), resolution, flash, controls, null);
  }

  /**
   * Prefer the environment variable so that the password never appears in
   * process arguments, and prompt without echo otherwise.
   */
  private static String readPassword(String variable) {
    String fromEnvironment = System.getenv(variable);
    if (fromEnvironment != null) {
      return fromEnvironment;
    }
    Console console = System.console();
    if (console == null) {
      throw new IllegalArgumentException(
          "set " + variable + "; there is no terminal to prompt on");
    }
    return new String(console.readPassword("Camera Control password: "));
  }

  /** A small long-option parser: {@code <command> <url> [--name value | --flag]}. */
  record Arguments(String url, Map<String, String> values) {

    static Arguments parse(String[] arguments) {
      String url = null;
      Map<String, String> values = new LinkedHashMap<>();
      int index = 1;
      if (index < arguments.length && !arguments[index].startsWith("-")) {
        url = arguments[index++];
      }
      while (index < arguments.length) {
        String argument = arguments[index++];
        if (!argument.startsWith("--")) {
          throw new IllegalArgumentException("unexpected argument '" + argument + "'");
        }
        String name = argument.substring(2);
        int equals = name.indexOf('=');
        if (equals >= 0) {
          values.put(name.substring(0, equals), name.substring(equals + 1));
        } else if (index < arguments.length && !arguments[index].startsWith("--")) {
          values.put(name, arguments[index++]);
        } else {
          values.put(name, "true");
        }
      }
      return new Arguments(url, values);
    }

    boolean has(String name) {
      return values.containsKey(name);
    }

    boolean flag(String name) {
      return "true".equals(values.get(name));
    }

    String text(String name, String fallback) {
      return values.getOrDefault(name, fallback);
    }

    String require(String name) {
      String value = values.get(name);
      if (value == null) {
        throw new IllegalArgumentException("--" + name + " is required");
      }
      return value;
    }

    double number(String name, double fallback) {
      String value = values.get(name);
      if (value == null) {
        if (Double.isNaN(fallback)) {
          throw new IllegalArgumentException("--" + name + " is required");
        }
        return fallback;
      }
      try {
        return Double.parseDouble(value);
      } catch (NumberFormatException error) {
        throw new IllegalArgumentException("--" + name + " must be a number", error);
      }
    }
  }
}
