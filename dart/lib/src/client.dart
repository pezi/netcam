/// Asynchronous, reconnecting Camera Control protocol client.
library;

import 'dart:async';
import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'channel.dart';
import 'exceptions.dart';

/// The only Camera Control protocol this client speaks.
const int apiProtocolVersion = 4;

/// Capture-job states that end a job.
const Set<String> terminalJobStates = <String>{'completed', 'failed', 'cancelled'};

/// Capture-session states that end a session.
const Set<String> terminalSessionStates = <String>{'completed', 'failed', 'cancelled'};

/// One decoded JSON object.
typedef JsonObject = Map<String, Object?>;

/// Connection tuning shared by every request.
final class Options {
  const Options({
    this.timeout = const Duration(seconds: 30),
    this.reconnectAttempts = 5,
    this.reconnectDelay = const Duration(seconds: 1),
  });

  /// Bounds a single request or event wait.
  final Duration timeout;

  /// Bounds idempotent retries after a dropped socket.
  final int reconnectAttempts;

  /// The first backoff delay; it doubles up to ten seconds.
  final Duration reconnectDelay;
}

/// The capture settings shared by jobs and sessions.
final class CaptureOptions {
  const CaptureOptions({
    this.camera = 1,
    this.resolution = 'high',
    this.flash = 'off',
    this.controls = const <String, Object?>{},
    this.requestId,
  });

  final int camera;
  final String resolution;
  final String flash;
  final JsonObject controls;

  /// The stable idempotency key. One is generated when it is null.
  final String? requestId;

  CaptureOptions withRequestId(String requestId) => CaptureOptions(
        camera: camera,
        resolution: resolution,
        flash: flash,
        controls: controls,
        requestId: requestId,
      );
}

/// One authenticated Camera Control connection.
///
/// Requests are serialized. Status and capture-session polling reconnect
/// automatically with bounded exponential backoff. A capture job reuses a
/// stable `requestId` when it is replayed after a dropped socket so the
/// running server can deduplicate it.
final class CameraControlClient {
  /// Build a client over the default verified-TLS transport.
  factory CameraControlClient({
    required String url,
    required String password,
    required String caFile,
    Options options = const Options(),
  }) {
    return CameraControlClient.withChannelFactory(
      url: url,
      password: password,
      options: options,
      channelFactory: (String endpoint, Duration openTimeout) =>
          WebSocketChannel.connect(endpoint, caFile, openTimeout),
    );
  }

  /// Build a client over any transport, which is how tests inject a fake.
  CameraControlClient.withChannelFactory({
    required String url,
    required String password,
    required ChannelFactory channelFactory,
    Options options = const Options(),
  })  : _url = url,
        _password = password,
        _channelFactory = channelFactory,
        _options = options {
    if (Uri.parse(url).scheme != 'wss') {
      throw const ConfigurationException('Camera Control requires a wss:// URL');
    }
    if (options.timeout <= Duration.zero) {
      throw const ConfigurationException('timeout must be positive');
    }
    if (options.reconnectAttempts < 0) {
      throw const ConfigurationException('reconnectAttempts cannot be negative');
    }
  }

  final String _url;
  final String _password;
  final ChannelFactory _channelFactory;
  final Options _options;
  final List<JsonObject> _events = <JsonObject>[];
  final Random _random = Random.secure();

  Channel? _channel;
  int _requestNumber = 0;
  Future<void> _pending = Future<void>.value();

  /// Whether a socket is currently retained.
  bool get isConnected => _channel != null;

  /// Open, validate protocol v4, and authenticate one WSS connection.
  Future<void> connect() async {
    await close();
    final Duration openTimeout = _options.timeout < const Duration(seconds: 10)
        ? _options.timeout
        : const Duration(seconds: 10);
    _channel = await _channelFactory(_url, openTimeout);
    try {
      final JsonObject hello = await _receive(_options.timeout);
      if (hello['event'] != 'hello' || hello['protocolVersion'] != apiProtocolVersion) {
        throw ProtocolException('Unsupported server greeting: ${jsonEncode(hello)}');
      }
      final JsonObject authenticated = await _requestOnce(
        'authenticate',
        <String, Object?>{'password': _password},
        _options.timeout,
      );
      if (authenticated['protocolVersion'] != apiProtocolVersion) {
        throw ProtocolException(
            'Authentication returned an unsupported protocol: ${jsonEncode(authenticated)}');
      }
    } on Object {
      await close();
      rethrow;
    }
  }

  /// Close the current socket without discarding queued events.
  Future<void> close() async {
    final Channel? channel = _channel;
    _channel = null;
    await channel?.close();
  }

  /// Send one action and return its result object.
  ///
  /// Pass `retryOnDisconnect: false` for non-idempotent operations such as the
  /// legacy direct `capture` action.
  Future<JsonObject> request(
    String action, {
    JsonObject parameters = const <String, Object?>{},
    bool retryOnDisconnect = true,
    Duration? timeout,
  }) async {
    final int attempts = retryOnDisconnect ? _options.reconnectAttempts : 0;
    Object? lastError;
    for (int attempt = 0; attempt <= attempts; attempt++) {
      try {
        if (_channel == null) {
          await connect();
        }
        return await _requestOnce(action, parameters, timeout ?? _options.timeout);
      } on CameraControlException catch (error) {
        if (error.isPermanent) {
          rethrow;
        }
        lastError = error;
        await close();
        if (attempt >= attempts) {
          break;
        }
        await Future<void>.delayed(_backoff(attempt));
      }
    }
    throw ConnectionInterrupted(
        "Connection interrupted while performing '$action'", lastError);
  }

  /// Camera, device, capture-session, and reliability status.
  Future<JsonObject> status() => request('status');

  /// Capture once through the idempotent job API and await completion.
  Future<JsonObject> captureJob([CaptureOptions capture = const CaptureOptions()]) async {
    final String requestId = capture.requestId ?? 'dart-${newRequestToken()}';
    final JsonObject parameters = <String, Object?>{
      'requestId': requestId,
      'camera': capture.camera,
      'resolution': capture.resolution,
      'flash': capture.flash,
      'controls': capture.controls,
    };
    for (int resume = 0; resume <= _options.reconnectAttempts; resume++) {
      final JsonObject result = await request('captureJob', parameters: parameters);
      final JsonObject job = _objectField(result, 'job', 'captureJob result.job');
      if (terminalJobStates.contains(job['state'])) {
        return job;
      }
      final Object? jobId = job['id'];
      try {
        final JsonObject event = await waitForEvent(
          'captureJob',
          predicate: (JsonObject candidate) {
            final Object? pending = candidate['job'];
            return pending is Map<String, Object?> &&
                pending['id'] == jobId &&
                terminalJobStates.contains(pending['state']);
          },
        );
        return _objectField(event, 'job', 'captureJob event.job');
      } on ConnectionInterrupted {
        // Re-submit the same semantic request ID. The running app deduplicates
        // it and returns the retained job snapshot.
        if (resume >= _options.reconnectAttempts) {
          rethrow;
        }
      }
    }
    throw const ConnectionInterrupted('Capture job recovery attempts exhausted');
  }

  /// Start a time-lapse capture session and return its snapshot.
  ///
  /// [count] stops the session after that many photos; omit it to run until
  /// the session is cancelled.
  Future<JsonObject> startTimeLapse({
    required double intervalSeconds,
    int? count,
    CaptureOptions capture = const CaptureOptions(),
  }) async {
    final JsonObject plan = <String, Object?>{
      'mode': 'timeLapse',
      'intervalSeconds': intervalSeconds,
      if (count != null) 'count': count,
    };
    final JsonObject result = await request(
      'startCaptureSession',
      retryOnDisconnect: false,
      parameters: <String, Object?>{
        'camera': capture.camera,
        'resolution': capture.resolution,
        'flash': capture.flash,
        'controls': capture.controls,
        'plan': plan,
      },
    );
    return _objectField(result, 'session', 'startCaptureSession result.session');
  }

  /// Poll the latest retained capture session across reconnects.
  Future<JsonObject> captureSessionStatus(String sessionId) async {
    final JsonObject snapshot = await status();
    final Object? session = snapshot['captureSession'];
    if (session is! Map<String, Object?> || session['id'] != sessionId) {
      throw const ProtocolException(
          'The capture session is no longer retained by the server');
    }
    return session;
  }

  /// Cancel one retained time-lapse/capture session.
  Future<JsonObject> cancelCaptureSession(String sessionId) async {
    final JsonObject result = await request(
      'cancelCaptureSession',
      parameters: <String, Object?>{'sessionId': sessionId},
    );
    return _objectField(result, 'session', 'cancelCaptureSession result.session');
  }

  /// Return the next matching unsolicited event.
  Future<JsonObject> waitForEvent(
    String eventName, {
    bool Function(JsonObject event)? predicate,
    Duration? timeout,
  }) async {
    bool matches(JsonObject event) =>
        event['event'] == eventName && (predicate == null || predicate(event));

    final int queued = _events.indexWhere(matches);
    if (queued >= 0) {
      return _events.removeAt(queued);
    }

    final DateTime deadline = DateTime.now().add(timeout ?? _options.timeout);
    while (true) {
      final Duration remaining = deadline.difference(DateTime.now());
      if (remaining <= Duration.zero) {
        throw ConnectionInterrupted("Timed out waiting for '$eventName'");
      }
      final JsonObject message;
      try {
        message = await _receive(remaining);
      } on CameraControlException catch (error) {
        if (error.isPermanent) {
          rethrow;
        }
        await close();
        throw ConnectionInterrupted(
            "Connection interrupted while waiting for '$eventName'", error);
      }
      if (!message.containsKey('event')) {
        throw ProtocolException('Unexpected response while waiting: ${jsonEncode(message)}');
      }
      if (matches(message)) {
        return message;
      }
      _events.add(message);
    }
  }

  /// Serialize requests so that one response can never be matched twice.
  Future<JsonObject> _requestOnce(
    String action,
    JsonObject parameters,
    Duration timeout,
  ) {
    final Completer<JsonObject> completer = Completer<JsonObject>();
    _pending = _pending.then<void>((_) async {
      try {
        completer.complete(await _exchange(action, parameters, timeout));
      } on Object catch (error, stackTrace) {
        completer.completeError(error, stackTrace);
      }
    });
    return completer.future;
  }

  Future<JsonObject> _exchange(
    String action,
    JsonObject parameters,
    Duration timeout,
  ) async {
    final Channel? channel = _channel;
    if (channel == null) {
      throw const ConnectionInterrupted('No active Camera Control connection');
    }
    _requestNumber += 1;
    final String requestId = 'dart-$_requestNumber';
    channel.send(jsonEncode(<String, Object?>{
      'id': requestId,
      'action': action,
      ...parameters,
    }));

    final DateTime deadline = DateTime.now().add(timeout);
    while (true) {
      final Duration remaining = deadline.difference(DateTime.now());
      if (remaining <= Duration.zero) {
        throw ConnectionInterrupted("Timed out waiting for '$action'");
      }
      final JsonObject response = await _receive(remaining);
      if (response.containsKey('event')) {
        _events.add(response);
        continue;
      }
      if (response['id'] != requestId) {
        throw ProtocolException('Unexpected response id: ${response['id']}');
      }
      if (response['ok'] != true) {
        final Object? error = response['error'];
        if (error is! Map<String, Object?>) {
          throw ProtocolException('Malformed API error: ${jsonEncode(response)}');
        }
        throw ApiException(
          '${error['code'] ?? 'unknown'}',
          '${error['message'] ?? 'no message'}',
          error['details'],
        );
      }
      return _objectField(response, 'result', '$action result');
    }
  }

  Future<JsonObject> _receive(Duration timeout) async {
    final Channel? channel = _channel;
    if (channel == null) {
      throw const ConnectionInterrupted('No active Camera Control connection');
    }
    final String message = await channel.receive(timeout);
    final Object? decoded;
    try {
      decoded = jsonDecode(message);
    } on FormatException {
      throw const ProtocolException('The server returned invalid JSON');
    }
    if (decoded is! Map<String, Object?>) {
      throw const ProtocolException("The WebSocket message isn't a JSON object");
    }
    return decoded;
  }

  Duration _backoff(int attempt) {
    final int milliseconds = _options.reconnectDelay.inMilliseconds * (1 << min(attempt, 4));
    return Duration(milliseconds: min(milliseconds, 10000));
  }

  /// A fresh idempotency token drawn from a cryptographic random source.
  String newRequestToken() {
    final List<String> parts = <String>[
      for (int index = 0; index < 4; index++)
        _random.nextInt(0xFFFFFFFF).toRadixString(16).padLeft(8, '0'),
    ];
    return parts.join();
  }
}

/// Validate and decode a JPEG photo object returned by the API.
Uint8List decodePhoto(JsonObject photo) {
  if (photo['mimeType'] != 'image/jpeg') {
    throw ProtocolException('Unexpected photo metadata: ${jsonEncode(photo)}');
  }
  final Object? encoded = photo['dataBase64'];
  if (encoded is! String) {
    throw const ProtocolException("The photo doesn't contain Base64 JPEG data");
  }
  try {
    return base64Decode(encoded);
  } on FormatException {
    throw const ProtocolException('The photo contains invalid Base64 data');
  }
}

JsonObject _objectField(JsonObject parent, String key, String label) {
  final Object? value = parent[key];
  if (value is! Map<String, Object?>) {
    throw ProtocolException("$label isn't a JSON object");
  }
  return value;
}
