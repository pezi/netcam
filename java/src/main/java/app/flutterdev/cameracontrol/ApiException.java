package app.flutterdev.cameracontrol;

/** A structured error returned by the Camera Control API. */
public final class ApiException extends CameraControlException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final String apiMessage;
  private final transient Object details;

  public ApiException(String code, String apiMessage, Object details) {
    super("API error " + code + ": " + apiMessage);
    this.code = code;
    this.apiMessage = apiMessage;
    this.details = details;
  }

  public String code() {
    return code;
  }

  public String apiMessage() {
    return apiMessage;
  }

  public Object details() {
    return details;
  }

  @Override
  public boolean isPermanent() {
    return true;
  }
}
