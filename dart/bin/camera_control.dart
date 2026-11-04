/// Command-line interface for Camera Control automation.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:args/args.dart';
import 'package:camera_control_client/camera_control_client.dart';

const List<String> resolutions = <String>[
  'low',
  'medium',
  'high',
  'veryHigh',
  'ultraHigh',
  'max',
];
const List<String> flashModes = <String>['off', 'auto', 'always', 'torch'];

Future<void> main(List<String> arguments) async {
  try {
    exitCode = await run(arguments);
  } on CameraControlException catch (error) {
    stderr.writeln('camera-control: ${error.message}');
    exitCode = 1;
  } on FormatException catch (error) {
    stderr.writeln('camera-control: ${error.message}');
    exitCode = 1;
  } on FileSystemException catch (error) {
    stderr.writeln('camera-control: ${error.message}');
    exitCode = 1;
  }
}

Future<int> run(List<String> arguments) async {
  final ArgParser parser = buildParser();
  final ArgResults parsed = parser.parse(arguments);
  final ArgResults? command = parsed.command;
  if (command == null || parsed['help'] as bool) {
    stderr.writeln('Control Camera Control through its verified-TLS WSS API.\n');
    stderr.writeln(parser.usage);
    for (final String name in parser.commands.keys) {
      stderr.writeln('\n$name:\n${parser.commands[name]!.usage}');
    }
    return command == null ? 2 : 0;
  }
  if (command['help'] as bool) {
    stderr.writeln(parser.commands[command.name]!.usage);
    return 0;
  }

  final List<String> positional = command.rest;
  if (positional.isEmpty) {
    throw const ConfigurationException('the wss:// URL shown by the app is required');
  }
  final String? caFile = command['ca'] as String?;
  if (caFile == null) {
    throw const ConfigurationException('--ca is required');
  }

  final CameraControlClient client = CameraControlClient(
    url: positional.first,
    password: readPassword(command['password-env'] as String),
    caFile: caFile,
    options: Options(
      timeout: Duration(
        milliseconds: (parseNumber(command['timeout'] as String, '--timeout') * 1000).round(),
      ),
      reconnectAttempts:
          parseNumber(command['reconnect-attempts'] as String, '--reconnect-attempts').toInt(),
    ),
  );

  await client.connect();
  try {
    return switch (command.name) {
      'status' => await runStatus(client, command),
      'capture' => await runCapture(client, command),
      _ => await runTimeLapse(client, command),
    };
  } finally {
    await client.close();
  }
}

ArgParser buildParser() {
  ArgParser connection() => ArgParser()
    ..addFlag('help', abbr: 'h', negatable: false, help: 'Print this command\'s options.')
    ..addOption('ca', help: 'Verified Camera Control local CA certificate.')
    ..addOption('password-env',
        defaultsTo: 'CAMERA_CONTROL_PASSWORD',
        help: 'Environment variable containing the password.')
    ..addOption('timeout', defaultsTo: '30', help: 'Request timeout in seconds.')
    ..addOption('reconnect-attempts', defaultsTo: '5', help: 'Idempotent retry attempts.');

  ArgParser withCaptureOptions(ArgParser parser) => parser
    ..addOption('camera', defaultsTo: '1', help: 'Camera index.')
    ..addOption('resolution', defaultsTo: 'high', allowed: resolutions)
    ..addOption('flash', defaultsTo: 'off', allowed: flashModes)
    ..addOption('jpeg-quality', help: 'JPEG quality between 1 and 100.')
    ..addOption('rotation',
        allowed: ['0', '90', '180', '270'],
        help: 'Clockwise image rotation; omitted keeps the current setting.');

  final ArgParser parser = ArgParser()
    ..addFlag('help', abbr: 'h', negatable: false, help: 'Print the command summary.');

  parser.addCommand(
    'status',
    connection()..addFlag('compact', negatable: false, help: 'Print compact JSON.'),
  );
  parser.addCommand(
    'capture',
    withCaptureOptions(connection())
      ..addOption('output', defaultsTo: 'photo.jpg', help: 'JPEG output path.')
      ..addOption('request-id', help: 'Stable idempotency key; generated when omitted.'),
  );
  parser.addCommand(
    'time-lapse',
    withCaptureOptions(connection())
      ..addOption('interval', help: 'Seconds between photos (required).')
      ..addOption('count', help: 'Stop after this many photos.')
      ..addOption('duration', help: 'Cancel after this many seconds.')
      ..addOption('poll', defaultsTo: '1', help: 'Status poll interval in seconds.'),
  );
  return parser;
}

Future<int> runStatus(CameraControlClient client, ArgResults command) async {
  final JsonObject status = await client.status();
  stdout.writeln(render(status, compact: command['compact'] as bool));
  return 0;
}

Future<int> runCapture(CameraControlClient client, ArgResults command) async {
  final String requestId =
      command['request-id'] as String? ?? 'dart-cli-${client.newRequestToken()}';
  final JsonObject job = await client.captureJob(
    captureOptions(command).withRequestId(requestId),
  );
  if (job['state'] != 'completed') {
    final Object? error = job['error'];
    final Object? message = error is Map<String, Object?> ? error['message'] : null;
    throw ProtocolException('Capture ended as ${job['state']}: ${message ?? ''}');
  }
  final Object? result = job['result'];
  final Object? photo = result is Map<String, Object?> ? result['photo'] : null;
  if (photo is! Map<String, Object?>) {
    throw const ProtocolException('Completed capture job has no photo');
  }
  final List<int> jpeg = decodePhoto(photo);
  final File output = File(command['output'] as String);
  await output.parent.create(recursive: true);
  await output.writeAsBytes(jpeg, flush: true);
  stdout.writeln('Saved ${jpeg.length} bytes to ${output.path} (requestId=$requestId)');
  return 0;
}

Future<int> runTimeLapse(CameraControlClient client, ArgResults command) async {
  final String? rawInterval = command['interval'] as String?;
  if (rawInterval == null) {
    throw const ConfigurationException('--interval is required');
  }
  final double interval = parseNumber(rawInterval, '--interval');
  if (interval < 1 || interval > 86400) {
    throw const ConfigurationException('--interval must be between 1 and 86400 seconds');
  }
  final String? rawCount = command['count'] as String?;
  final int? count = rawCount == null ? null : parseNumber(rawCount, '--count').toInt();
  if (count != null && (count < 2 || count > 1000)) {
    throw const ConfigurationException('--count must be between 2 and 1000');
  }
  final String? rawDuration = command['duration'] as String?;
  final double? duration =
      rawDuration == null ? null : parseNumber(rawDuration, '--duration');
  if (duration != null && duration <= 0) {
    throw const ConfigurationException('--duration must be positive');
  }
  final double poll = parseNumber(command['poll'] as String, '--poll');
  if (poll <= 0) {
    throw const ConfigurationException('--poll must be positive');
  }

  JsonObject session = await client.startTimeLapse(
    intervalSeconds: interval,
    count: count,
    capture: captureOptions(command),
  );
  final String sessionId = '${session['id']}';
  stdout.writeln('Started $sessionId; press Ctrl+C to stop.');

  // Cancellation still needs a live connection, so the loop owns the signal
  // instead of letting the default handler tear the process down.
  bool stopRequested = false;
  final StreamSubscription<ProcessSignal> interrupt =
      ProcessSignal.sigint.watch().listen((_) {
    stderr.writeln('\nCancellation requested…');
    stopRequested = true;
  });

  final DateTime? deadline = duration == null
      ? null
      : DateTime.now().add(Duration(milliseconds: (duration * 1000).round()));
  String lastSignature = '';
  bool cancel = false;
  try {
    while (!terminalSessionStates.contains(session['state'])) {
      final String signature = '${session['state']}/${session['capturedCount']}';
      if (signature != lastSignature) {
        stdout.writeln(
            '${session['state']}: ${session['capturedCount']}/${session['targetCount'] ?? '∞'}');
        lastSignature = signature;
      }
      if (stopRequested || (deadline != null && !DateTime.now().isBefore(deadline))) {
        cancel = true;
        break;
      }
      await Future<void>.delayed(Duration(milliseconds: (poll * 1000).round()));
      if (stopRequested) {
        cancel = true;
        break;
      }
      session = await client.captureSessionStatus(sessionId);
    }
    if (cancel) {
      session = await client.cancelCaptureSession(sessionId);
    }
  } finally {
    await interrupt.cancel();
  }

  stdout.writeln(render(session, compact: false));
  return <Object?>['completed', 'cancelled'].contains(session['state']) ? 0 : 1;
}

CaptureOptions captureOptions(ArgResults command) {
  final String? rawQuality = command['jpeg-quality'] as String?;
  final JsonObject controls = <String, Object?>{};
  if (rawQuality != null) {
    final int quality = parseNumber(rawQuality, '--jpeg-quality').toInt();
    if (quality < 1 || quality > 100) {
      throw const ConfigurationException('--jpeg-quality must be between 1 and 100');
    }
    controls['jpegQuality'] = quality;
  }
  final String? rotation = command['rotation'] as String?;
  if (rotation != null) controls['rotation'] = int.parse(rotation);
  return CaptureOptions(
    camera: parseNumber(command['camera'] as String, '--camera').toInt(),
    resolution: command['resolution'] as String,
    flash: command['flash'] as String,
    controls: controls,
  );
}

double parseNumber(String raw, String name) {
  final double? value = double.tryParse(raw);
  if (value == null) {
    throw ConfigurationException('$name must be a number');
  }
  return value;
}

/// Serialize with sorted keys so that output diffs stay readable.
String render(Object? value, {required bool compact}) {
  final Object? sorted = sortKeys(value);
  return compact
      ? jsonEncode(sorted)
      : const JsonEncoder.withIndent('  ').convert(sorted);
}

Object? sortKeys(Object? value) {
  if (value is Map<String, Object?>) {
    return <String, Object?>{
      for (final String key in value.keys.toList()..sort()) key: sortKeys(value[key]),
    };
  }
  if (value is List<Object?>) {
    return value.map(sortKeys).toList();
  }
  return value;
}

/// Prefer the environment variable so that the password never appears in
/// process arguments, and prompt without echo otherwise.
String readPassword(String variable) {
  final String? fromEnvironment = Platform.environment[variable];
  if (fromEnvironment != null) {
    return fromEnvironment;
  }
  stderr.write('Camera Control password: ');
  final bool echo = stdin.echoMode;
  try {
    stdin.echoMode = false;
  } on StdinException {
    // A pipe has no terminal modes to change.
  }
  final String password = stdin.readLineSync(encoding: utf8) ?? '';
  try {
    stdin.echoMode = echo;
  } on StdinException {
    // Restoring is best effort for the same reason.
  }
  stderr.writeln();
  return password;
}
