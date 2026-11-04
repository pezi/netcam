/// Dart client for the Camera Control JSON WebSocket API (protocol version 4).
library;

export 'src/channel.dart' show Channel, ChannelFactory, WebSocketChannel;
export 'src/client.dart'
    show
        CameraControlClient,
        CaptureOptions,
        JsonObject,
        Options,
        apiProtocolVersion,
        decodePhoto,
        terminalJobStates,
        terminalSessionStates;
export 'src/exceptions.dart'
    show
        ApiException,
        CameraControlException,
        ConfigurationException,
        ConnectionInterrupted,
        ProtocolException;
