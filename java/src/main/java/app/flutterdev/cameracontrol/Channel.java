package app.flutterdev.cameracontrol;

import java.time.Duration;

/** One open text-message channel. Implement it to drive the client in tests. */
public interface Channel extends AutoCloseable {

  /** Send one text frame. */
  void send(String message) throws CameraControlException;

  /**
   * Return the next text frame.
   *
   * @throws ConnectionInterruptedException when the socket failed or the
   *     timeout elapsed before a frame arrived.
   */
  String receive(Duration timeout) throws CameraControlException;

  @Override
  void close();
}
