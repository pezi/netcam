/// The WebSocket transport and its verified-TLS connector.
library;

import 'dart:async';
import 'dart:collection';
import 'dart:io';

import 'exceptions.dart';

/// One open text-message channel. Implement it to drive the client in tests.
abstract interface class Channel {
  /// Send one text frame.
  void send(String message);

  /// Return the next text frame, or fail once [timeout] elapses.
  Future<String> receive(Duration timeout);

  /// Close the socket. Pending and later receives fail afterwards.
  Future<void> close();
}

/// Opens one channel per connection attempt.
typedef ChannelFactory = Future<Channel> Function(String url, Duration openTimeout);

/// A pull-based channel over a `dart:io` [WebSocket].
///
/// Incoming frames are buffered so that a caller can await one message at a
/// time regardless of arrival order.
final class WebSocketChannel implements Channel {
  WebSocketChannel(this._socket) {
    _subscription = _socket.listen(
      (Object? frame) {
        if (frame is String) {
          _deliver(frame);
        } else {
          _fail(const ProtocolException(
              'The server returned a non-JSON WebSocket message'));
        }
      },
      onError: (Object error) =>
          _fail(ConnectionInterrupted('The Camera Control connection failed: $error', error)),
      onDone: () =>
          _fail(const ConnectionInterrupted('The Camera Control connection closed')),
      cancelOnError: false,
    );
  }

  /// Open one verified-TLS connection that trusts only the app's local CA.
  static Future<Channel> connect(
    String url,
    String caFile,
    Duration openTimeout,
  ) async {
    if (!File(caFile).existsSync()) {
      throw ConfigurationException('CA certificate not found: $caFile');
    }
    final SecurityContext context = SecurityContext(withTrustedRoots: false);
    try {
      context.setTrustedCertificates(caFile);
    } on TlsException catch (error) {
      throw ConfigurationException('The CA certificate is unusable: ${error.message}');
    }
    final HttpClient httpClient = HttpClient(context: context)
      ..connectionTimeout = openTimeout;
    try {
      final WebSocket socket =
          await WebSocket.connect(url, customClient: httpClient).timeout(openTimeout);
      return WebSocketChannel(socket);
    } on TimeoutException {
      httpClient.close(force: true);
      throw const ConnectionInterrupted(
          'Timed out opening the Camera Control connection');
    } on Object catch (error) {
      httpClient.close(force: true);
      throw ConnectionInterrupted(
          'The Camera Control connection could not be opened: $error', error);
    }
  }

  final WebSocket _socket;
  late final StreamSubscription<dynamic> _subscription;
  final Queue<String> _messages = Queue<String>();
  Completer<String>? _waiting;
  CameraControlException? _failure;

  void _deliver(String message) {
    final Completer<String>? waiting = _waiting;
    if (waiting != null && !waiting.isCompleted) {
      _waiting = null;
      waiting.complete(message);
      return;
    }
    _messages.add(message);
  }

  void _fail(CameraControlException error) {
    _failure ??= error;
    final Completer<String>? waiting = _waiting;
    if (waiting != null && !waiting.isCompleted) {
      _waiting = null;
      waiting.completeError(_failure!);
    }
  }

  @override
  void send(String message) {
    final CameraControlException? failure = _failure;
    if (failure != null) {
      throw failure;
    }
    _socket.add(message);
  }

  @override
  Future<String> receive(Duration timeout) {
    if (_messages.isNotEmpty) {
      return Future<String>.value(_messages.removeFirst());
    }
    final CameraControlException? failure = _failure;
    if (failure != null) {
      return Future<String>.error(failure);
    }
    final Completer<String> completer = Completer<String>();
    _waiting = completer;
    return completer.future.timeout(timeout, onTimeout: () {
      _waiting = null;
      throw const ConnectionInterrupted(
          'Timed out waiting for a Camera Control message');
    });
  }

  @override
  Future<void> close() async {
    _fail(const ConnectionInterrupted(
        'The Camera Control connection was closed locally'));
    await _subscription.cancel();
    await _socket.close(WebSocketStatus.normalClosure);
  }
}
