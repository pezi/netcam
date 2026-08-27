"""Python client for the Camera Control JSON WebSocket API."""

from .client import (
    API_PROTOCOL_VERSION,
    ApiError,
    CameraControlClient,
    ConnectionInterrupted,
    ProtocolError,
    decode_photo,
)

__all__ = [
    "API_PROTOCOL_VERSION",
    "ApiError",
    "CameraControlClient",
    "ConnectionInterrupted",
    "ProtocolError",
    "decode_photo",
]
