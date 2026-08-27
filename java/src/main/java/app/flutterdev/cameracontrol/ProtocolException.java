package app.flutterdev.cameracontrol;

/** The peer returned a malformed or unsupported protocol message. */
public final class ProtocolException extends CameraControlException {

  private static final long serialVersionUID = 1L;

  public ProtocolException(String message) {
    super(message);
  }

  public ProtocolException(String message, Throwable cause) {
    super(message, cause);
  }

  @Override
  public boolean isPermanent() {
    return true;
  }
}
