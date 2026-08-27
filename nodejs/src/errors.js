/** Errors raised by the Camera Control client. */

/** A structured error returned by the Camera Control API. */
export class ApiError extends Error {
  constructor(code, message, details = undefined) {
    super(`API error ${code}: ${message}`);
    this.name = 'ApiError';
    this.code = code;
    this.apiMessage = message;
    this.details = details;
  }
}

/** The peer returned a malformed or unsupported protocol message. */
export class ProtocolError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ProtocolError';
  }
}

/** The connection could not be restored within the configured attempts. */
export class ConnectionInterrupted extends Error {
  constructor(message, cause = undefined) {
    super(message, { cause });
    this.name = 'ConnectionInterrupted';
  }
}

/** A request or event wait exceeded its deadline. */
export class TimeoutError extends Error {
  constructor(message) {
    super(message);
    this.name = 'TimeoutError';
  }
}

/** Retrying cannot help for API and protocol errors. */
export function isPermanent(error) {
  return error instanceof ApiError || error instanceof ProtocolError;
}
