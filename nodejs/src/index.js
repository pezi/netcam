/** Node.js client for the Camera Control JSON WebSocket API. */

export {
  API_PROTOCOL_VERSION,
  CameraControlClient,
  TERMINAL_JOB_STATES,
  TERMINAL_SESSION_STATES,
  decodePhoto,
} from './client.js';
export {
  ApiError,
  ConnectionInterrupted,
  ProtocolError,
  TimeoutError,
} from './errors.js';
export { WebSocketChannel, openWebSocket } from './socket.js';
