/// Errors raised by the Camera Control client.
library;

/// The base of every failure the Camera Control client reports.
sealed class CameraControlException implements Exception {
  const CameraControlException(this.message);

  /// A human-readable description of the failure.
  final String message;

  /// Whether retrying the operation cannot help.
  bool get isPermanent => false;

  @override
  String toString() => message;
}

/// A structured error returned by the Camera Control API.
final class ApiException extends CameraControlException {
  const ApiException(this.code, this.apiMessage, [this.details])
      : super('API error $code: $apiMessage');

  /// The machine-readable error code, such as `camera_busy`.
  final String code;

  /// The server's own message.
  final String apiMessage;

  /// Optional structured detail supplied by the server.
  final Object? details;

  @override
  bool get isPermanent => true;
}

/// The peer returned a malformed or unsupported protocol message.
final class ProtocolException extends CameraControlException {
  const ProtocolException(super.message);

  @override
  bool get isPermanent => true;
}

/// The client was configured with unusable arguments.
final class ConfigurationException extends CameraControlException {
  const ConfigurationException(super.message);

  @override
  bool get isPermanent => true;
}

/// The connection could not be restored within the configured attempts.
final class ConnectionInterrupted extends CameraControlException {
  const ConnectionInterrupted(super.message, [this.cause]);

  /// The transport failure that ended the connection, when there was one.
  final Object? cause;
}
