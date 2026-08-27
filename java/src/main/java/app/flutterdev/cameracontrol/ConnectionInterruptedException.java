package app.flutterdev.cameracontrol;

/** The connection could not be restored within the configured attempts. */
public final class ConnectionInterruptedException extends CameraControlException {

  private static final long serialVersionUID = 1L;

  public ConnectionInterruptedException(String message) {
    super(message);
  }

  public ConnectionInterruptedException(String message, Throwable cause) {
    super(message, cause);
  }
}
