from __future__ import annotations

import json
import ssl
import unittest
from collections import deque

from camera_control import API_PROTOCOL_VERSION, ApiError, CameraControlClient


class FakeConnection:
    def __init__(self, messages):
        self.messages = deque(messages)
        self.sent = []
        self.closed = False

    def recv(self, timeout=None):
        value = self.messages.popleft()
        if isinstance(value, BaseException):
            raise value
        return json.dumps(value)

    def send(self, message):
        payload = json.loads(message)
        self.sent.append(payload)
        if payload["action"] == "authenticate":
            self.messages.appendleft(
                {
                    "id": payload["id"],
                    "ok": True,
                    "result": {"protocolVersion": API_PROTOCOL_VERSION},
                }
            )
        elif payload["action"] == "status":
            self.messages.append(
                {
                    "event": "captureSession",
                    "session": {"id": "session-1", "state": "waiting"},
                }
            )
            self.messages.append(
                {
                    "id": payload["id"],
                    "ok": True,
                    "result": {"ready": True},
                }
            )

    def close(self):
        self.closed = True


class ClientTest(unittest.TestCase):
    def make_client(self, connection):
        return CameraControlClient(
            "wss://192.168.1.50:8080/ws",
            "secret123",
            ssl_context=ssl.create_default_context(),
            connect_factory=lambda *args, **kwargs: connection,
        )

    def test_authenticates_protocol_v4_and_queues_events(self):
        connection = FakeConnection(
            [{"event": "hello", "protocolVersion": API_PROTOCOL_VERSION}]
        )
        client = self.make_client(connection)
        client.connect()
        self.assertEqual(client.status(), {"ready": True})
        event = client.wait_for_event("captureSession", timeout=0.1)
        self.assertEqual(event["session"]["id"], "session-1")
        self.assertEqual(connection.sent[0]["action"], "authenticate")
        client.close()
        self.assertTrue(connection.closed)

    def test_rejects_api_errors(self):
        connection = FakeConnection(
            [{"event": "hello", "protocolVersion": API_PROTOCOL_VERSION}]
        )
        client = self.make_client(connection)
        client.connect()

        def send_error(message):
            payload = json.loads(message)
            connection.sent.append(payload)
            connection.messages.append(
                {
                    "id": payload["id"],
                    "ok": False,
                    "error": {"code": "camera_busy", "message": "Busy"},
                }
            )

        connection.send = send_error
        with self.assertRaises(ApiError) as raised:
            client.request("status", retry_on_disconnect=False)
        self.assertEqual(raised.exception.code, "camera_busy")

    def test_rejects_old_protocol_servers(self):
        connection = FakeConnection([{"event": "hello", "protocolVersion": 1}])
        client = self.make_client(connection)
        with self.assertRaisesRegex(RuntimeError, "Unsupported server greeting"):
            client.connect()


if __name__ == "__main__":
    unittest.main()
