package app.flutterdev.cameracontrol;

/** The base of every failure the Camera Control client reports. */
public class CameraControlException extends Exception {

  private static final long serialVersionUID = 1L;

  public CameraControlException(String message) {
    super(message);
  }

  public CameraControlException(String message, Throwable cause) {
    super(message, cause);
  }

  /** Whether retrying the operation cannot help. */
  public boolean isPermanent() {
    return false;
  }
}
