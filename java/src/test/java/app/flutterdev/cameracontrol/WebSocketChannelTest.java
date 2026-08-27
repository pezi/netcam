package app.flutterdev.cameracontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSocketChannelTest {

  @Test
  void listenerBuffersGreetingBeforeChannelConstruction() {
    BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    WebSocketChannel.Listener listener = new WebSocketChannel.Listener(messages, failure);
    AtomicLong requestedMessages = new AtomicLong();
    WebSocket webSocket = (WebSocket) Proxy.newProxyInstance(
        WebSocket.class.getClassLoader(),
        new Class<?>[] {WebSocket.class},
        (proxy, method, arguments) -> {
          if (method.getName().equals("request")) {
            requestedMessages.addAndGet((long) arguments[0]);
            return null;
          }
          throw new UnsupportedOperationException(method.getName());
        });

    listener.onOpen(webSocket);
    listener.onText(webSocket, "{\"event\":\"hel", false);
    listener.onText(webSocket, "lo\"}", true);

    assertEquals("{\"event\":\"hello\"}", messages.poll());
    assertEquals(3, requestedMessages.get());
    assertNull(failure.get());
  }
}
