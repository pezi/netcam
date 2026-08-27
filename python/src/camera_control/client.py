"""Synchronous, reconnecting Camera Control protocol client."""

from __future__ import annotations

import base64
import json
import ssl
import time
import uuid
from collections import deque
from pathlib import Path
from typing import Any, Callable, Deque, Dict, Optional
from urllib.parse import urlsplit

from websockets.exceptions import ConnectionClosed
from websockets.sync.client import connect


API_PROTOCOL_VERSION = 4
JsonObject = Dict[str, Any]
ConnectFactory = Callable[..., Any]
TERMINAL_JOB_STATES = frozenset(("completed", "failed", "cancelled"))
TERMINAL_SESSION_STATES = frozenset(("completed", "failed", "cancelled"))


class ProtocolError(RuntimeError):
    """The peer returned a malformed or unsupported protocol message."""


class ConnectionInterrupted(RuntimeError):
    """The connection could not be restored within the configured attempts."""


class ApiError(RuntimeError):
    """A structured error returned by the Camera Control API."""

    def __init__(self, code: str, message: str, details: Any = None) -> None:
        super().__init__(f"API error {code}: {message}")
        self.code = code
        self.message = message
        self.details = details


class CameraControlClient:
    """One authenticated Camera Control connection.

    Requests are serialized. Status and capture-session polling reconnect
    automatically. A capture job uses a stable ``requestId`` when replayed
    after a dropped socket so the running server can deduplicate it.
    """

    def __init__(
        self,
        url: str,
        password: str,
        ca_file: Optional[Path] = None,
        *,
        timeout: float = 30.0,
        reconnect_attempts: int = 5,
        reconnect_delay: float = 1.0,
        ssl_context: Optional[ssl.SSLContext] = None,
        connect_factory: ConnectFactory = connect,
    ) -> None:
        if urlsplit(url).scheme.lower() != "wss":
            raise ValueError("Camera Control requires a wss:// URL")
        if timeout <= 0:
            raise ValueError("timeout must be positive")
        if reconnect_attempts < 0:
            raise ValueError("reconnect_attempts cannot be negative")
        self.url = url
        self.password = password
        self.ca_file = Path(ca_file) if ca_file is not None else None
        self.timeout = timeout
        self.reconnect_attempts = reconnect_attempts
        self.reconnect_delay = reconnect_delay
        self._ssl_context = ssl_context
        self._connect_factory = connect_factory
        self._websocket: Any = None
        self._events: Deque[JsonObject] = deque()
        self._request_number = 0

    def __enter__(self) -> "CameraControlClient":
        self.connect()
        return self

    def __exit__(self, *_: Any) -> None:
        self.close()

    @property
    def connected(self) -> bool:
        """Whether a socket object is currently retained."""

        return self._websocket is not None

    def connect(self) -> None:
        """Open, validate protocol v4, and authenticate one WSS connection."""

        self.close()
        tls = self._ssl_context
        if tls is None:
            if self.ca_file is None or not self.ca_file.is_file():
                raise FileNotFoundError(f"CA certificate not found: {self.ca_file}")
            tls = ssl.create_default_context(cafile=str(self.ca_file))

        websocket = self._connect_factory(
            self.url,
            ssl=tls,
            max_size=None,
            proxy=None,
            open_timeout=min(self.timeout, 10.0),
            ping_interval=20,
            ping_timeout=20,
        )
        self._websocket = websocket
        try:
            hello = self._receive(timeout=self.timeout)
            if (
                hello.get("event") != "hello"
                or hello.get("protocolVersion") != API_PROTOCOL_VERSION
            ):
                raise ProtocolError(f"Unsupported server greeting: {hello!r}")
            authenticated = self._request_once(
                "authenticate",
                {"password": self.password},
                timeout=self.timeout,
            )
            if authenticated.get("protocolVersion") != API_PROTOCOL_VERSION:
                raise ProtocolError(
                    f"Authentication returned an unsupported protocol: {authenticated!r}"
                )
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        """Close the current socket without discarding queued events."""

        websocket, self._websocket = self._websocket, None
        if websocket is not None:
            try:
                websocket.close()
            except (ConnectionClosed, OSError):
                pass

    def request(
        self,
        action: str,
        *,
        retry_on_disconnect: bool = True,
        timeout: Optional[float] = None,
        **parameters: Any,
    ) -> JsonObject:
        """Send one action and return its result object.

        Set ``retry_on_disconnect=False`` for non-idempotent operations such
        as the legacy direct ``capture`` action.
        """

        attempts = self.reconnect_attempts if retry_on_disconnect else 0
        last_error: Optional[BaseException] = None
        for attempt in range(attempts + 1):
            try:
                if self._websocket is None:
                    self.connect()
                return self._request_once(
                    action,
                    parameters,
                    timeout=timeout or self.timeout,
                )
            except (ConnectionClosed, OSError, TimeoutError) as error:
                last_error = error
                self.close()
                if attempt >= attempts:
                    break
                time.sleep(min(self.reconnect_delay * (2**attempt), 10.0))
        raise ConnectionInterrupted(
            f"Connection interrupted while performing {action!r}"
        ) from last_error

    def status(self) -> JsonObject:
        """Return camera, device, capture-session, and reliability status."""

        return self.request("status")

    def capture_job(
        self,
        *,
        camera: int = 1,
        resolution: str = "high",
        flash: str = "off",
        controls: Optional[JsonObject] = None,
        request_id: Optional[str] = None,
    ) -> JsonObject:
        """Capture once through the idempotent job API and await completion."""

        stable_request_id = request_id or f"python-{uuid.uuid4()}"
        parameters = {
            "requestId": stable_request_id,
            "camera": camera,
            "resolution": resolution,
            "flash": flash,
            "controls": controls or {},
        }
        for resume_attempt in range(self.reconnect_attempts + 1):
            result = self.request("captureJob", **parameters)
            job = _object(result.get("job"), "captureJob result.job")
            if job.get("state") in TERMINAL_JOB_STATES:
                return job
            job_id = job.get("id")
            try:
                return self.wait_for_event(
                    "captureJob",
                    predicate=lambda event: (
                        isinstance(event.get("job"), dict)
                        and event["job"].get("id") == job_id
                        and event["job"].get("state") in TERMINAL_JOB_STATES
                    ),
                    timeout=self.timeout,
                )["job"]
            except ConnectionInterrupted:
                # Re-submit the same semantic request ID. The running app
                # deduplicates it and returns the retained job snapshot.
                if resume_attempt >= self.reconnect_attempts:
                    raise
        raise ConnectionInterrupted("Capture job recovery attempts exhausted")

    def start_time_lapse(
        self,
        *,
        interval_seconds: float,
        count: Optional[int] = None,
        camera: int = 1,
        resolution: str = "high",
        flash: str = "off",
        controls: Optional[JsonObject] = None,
    ) -> JsonObject:
        """Start a time-lapse capture session and return its snapshot."""

        plan: JsonObject = {
            "mode": "timeLapse",
            "intervalSeconds": interval_seconds,
        }
        if count is not None:
            plan["count"] = count
        result = self.request(
            "startCaptureSession",
            retry_on_disconnect=False,
            camera=camera,
            resolution=resolution,
            flash=flash,
            controls=controls or {},
            plan=plan,
        )
        return _object(result.get("session"), "startCaptureSession result.session")

    def capture_session_status(self, session_id: str) -> JsonObject:
        """Poll the latest retained capture session across reconnects."""

        status = self.status()
        session = status.get("captureSession")
        if not isinstance(session, dict) or session.get("id") != session_id:
            raise ProtocolError(
                "The capture session is no longer retained by the server"
            )
        return session

    def cancel_capture_session(self, session_id: str) -> JsonObject:
        """Cancel one retained time-lapse/capture session."""

        result = self.request("cancelCaptureSession", sessionId=session_id)
        return _object(result.get("session"), "cancelCaptureSession result.session")

    def wait_for_event(
        self,
        event_name: str,
        *,
        predicate: Optional[Callable[[JsonObject], bool]] = None,
        timeout: Optional[float] = None,
    ) -> JsonObject:
        """Return the next matching unsolicited event."""

        deadline = time.monotonic() + (timeout or self.timeout)
        while True:
            for _ in range(len(self._events)):
                event = self._events.popleft()
                if event.get("event") == event_name and (
                    predicate is None or predicate(event)
                ):
                    return event
                self._events.append(event)
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Timed out waiting for {event_name!r}")
            try:
                message = self._receive(timeout=remaining)
            except (ConnectionClosed, OSError, TimeoutError) as error:
                self.close()
                raise ConnectionInterrupted(
                    f"Connection interrupted while waiting for {event_name!r}"
                ) from error
            if "event" not in message:
                raise ProtocolError(f"Unexpected response while waiting: {message!r}")
            if message.get("event") == event_name and (
                predicate is None or predicate(message)
            ):
                return message
            self._events.append(message)

    def _request_once(
        self,
        action: str,
        parameters: JsonObject,
        *,
        timeout: float,
    ) -> JsonObject:
        websocket = self._websocket
        if websocket is None:
            raise ConnectionInterrupted("No active Camera Control connection")
        self._request_number += 1
        request_id = f"python-{self._request_number}"
        payload = {"id": request_id, "action": action, **parameters}
        websocket.send(json.dumps(payload, separators=(",", ":")))
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Timed out waiting for {action!r}")
            response = self._receive(timeout=remaining)
            if "event" in response:
                self._events.append(response)
                continue
            if response.get("id") != request_id:
                raise ProtocolError(f"Unexpected response id: {response.get('id')!r}")
            if response.get("ok") is not True:
                error = response.get("error")
                if not isinstance(error, dict):
                    raise ProtocolError(f"Malformed API error: {response!r}")
                raise ApiError(
                    str(error.get("code", "unknown")),
                    str(error.get("message", "no message")),
                    error.get("details"),
                )
            return _object(response.get("result"), f"{action} result")

    def _receive(self, *, timeout: float) -> JsonObject:
        websocket = self._websocket
        if websocket is None:
            raise ConnectionInterrupted("No active Camera Control connection")
        message = websocket.recv(timeout=timeout)
        if not isinstance(message, str):
            raise ProtocolError("The server returned a non-JSON WebSocket message")
        try:
            decoded = json.loads(message)
        except json.JSONDecodeError as error:
            raise ProtocolError("The server returned invalid JSON") from error
        return _object(decoded, "WebSocket message")


def decode_photo(photo: JsonObject) -> bytes:
    """Validate and decode a JPEG photo object returned by the API."""

    if photo.get("mimeType") != "image/jpeg":
        raise ProtocolError(f"Unexpected photo metadata: {photo!r}")
    encoded = photo.get("dataBase64")
    if not isinstance(encoded, str):
        raise ProtocolError("The photo doesn't contain Base64 JPEG data")
    try:
        return base64.b64decode(encoded, validate=True)
    except (ValueError, TypeError) as error:
        raise ProtocolError("The photo contains invalid Base64 data") from error


def _object(value: Any, label: str) -> JsonObject:
    if not isinstance(value, dict):
        raise ProtocolError(f"{label} isn't a JSON object")
    return value
