"""Command-line interface for Camera Control automation."""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
import time
import uuid
from pathlib import Path
from typing import Any, Optional, Sequence

from .client import (
    TERMINAL_SESSION_STATES,
    ApiError,
    CameraControlClient,
    ConnectionInterrupted,
    ProtocolError,
    decode_photo,
)


RESOLUTIONS = ("low", "medium", "high", "veryHigh", "ultraHigh", "max")
FLASH_MODES = ("off", "auto", "always", "torch")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="camera-control",
        description="Control Camera Control through its verified-TLS WSS API.",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    connection = argparse.ArgumentParser(add_help=False)
    connection.add_argument("url", help="wss:// URL shown by the app")
    connection.add_argument(
        "--ca",
        type=Path,
        required=True,
        help="verified Camera Control local CA certificate",
    )
    connection.add_argument(
        "--password-env",
        default="CAMERA_CONTROL_PASSWORD",
        help="environment variable containing the password",
    )
    connection.add_argument("--timeout", type=float, default=30.0)
    connection.add_argument("--reconnect-attempts", type=int, default=5)

    status = commands.add_parser("status", parents=[connection])
    status.add_argument(
        "--compact", action="store_true", help="print compact JSON"
    )

    capture = commands.add_parser("capture", parents=[connection])
    _add_capture_options(capture)
    capture.add_argument("--output", type=Path, default=Path("photo.jpg"))
    capture.add_argument(
        "--request-id",
        help="stable idempotency key; generated when omitted",
    )

    time_lapse = commands.add_parser("time-lapse", parents=[connection])
    _add_capture_options(time_lapse)
    time_lapse.add_argument(
        "--interval", type=float, required=True, help="seconds between photos"
    )
    time_lapse.add_argument(
        "--count", type=int, help="stop after this many photos"
    )
    time_lapse.add_argument(
        "--duration",
        type=float,
        help="cancel after this many seconds; otherwise run until count/Ctrl+C",
    )
    time_lapse.add_argument("--poll", type=float, default=1.0)
    return parser


def _add_capture_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--camera", type=int, default=1)
    parser.add_argument("--resolution", choices=RESOLUTIONS, default="high")
    parser.add_argument("--flash", choices=FLASH_MODES, default="off")
    parser.add_argument("--jpeg-quality", type=int)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    password = os.environ.get(args.password_env)
    if password is None:
        password = getpass.getpass("Camera Control password: ")
    try:
        controls = _controls(args)
        with CameraControlClient(
            args.url,
            password,
            args.ca,
            timeout=args.timeout,
            reconnect_attempts=args.reconnect_attempts,
        ) as client:
            if args.command == "status":
                print(
                    json.dumps(
                        client.status(),
                        indent=None if args.compact else 2,
                        sort_keys=True,
                    )
                )
                return 0
            if args.command == "capture":
                return _capture(client, args, controls)
            if args.command == "time-lapse":
                return _time_lapse(client, args, controls)
    except (ApiError, ConnectionInterrupted, ProtocolError, OSError, ValueError) as error:
        print(f"camera-control: {error}", file=sys.stderr)
        return 1
    return 2


def _capture(
    client: CameraControlClient,
    args: argparse.Namespace,
    controls: dict[str, Any],
) -> int:
    request_id = args.request_id or f"cli-{uuid.uuid4()}"
    job = client.capture_job(
        camera=args.camera,
        resolution=args.resolution,
        flash=args.flash,
        controls=controls,
        request_id=request_id,
    )
    if job.get("state") != "completed":
        error = job.get("error") or {}
        raise ProtocolError(
            f"Capture ended as {job.get('state')}: {error.get('message', '')}"
        )
    result = job.get("result")
    if not isinstance(result, dict) or not isinstance(result.get("photo"), dict):
        raise ProtocolError("Completed capture job has no photo")
    jpeg = decode_photo(result["photo"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(jpeg)
    print(f"Saved {len(jpeg)} bytes to {args.output} (requestId={request_id})")
    return 0


def _time_lapse(
    client: CameraControlClient,
    args: argparse.Namespace,
    controls: dict[str, Any],
) -> int:
    if args.interval < 1 or args.interval > 86400:
        raise ValueError("--interval must be between 1 and 86400 seconds")
    if args.count is not None and not 2 <= args.count <= 1000:
        raise ValueError("--count must be between 2 and 1000")
    if args.duration is not None and args.duration <= 0:
        raise ValueError("--duration must be positive")
    if args.poll <= 0:
        raise ValueError("--poll must be positive")

    session = client.start_time_lapse(
        interval_seconds=args.interval,
        count=args.count,
        camera=args.camera,
        resolution=args.resolution,
        flash=args.flash,
        controls=controls,
    )
    session_id = str(session["id"])
    deadline = time.monotonic() + args.duration if args.duration else None
    last_signature: Any = None
    print(f"Started {session_id}; press Ctrl+C to stop.")
    try:
        while session.get("state") not in TERMINAL_SESSION_STATES:
            signature = (session.get("state"), session.get("capturedCount"))
            if signature != last_signature:
                print(
                    f"{session.get('state')}: "
                    f"{session.get('capturedCount')}/{session.get('targetCount') or '∞'}"
                )
                last_signature = signature
            if deadline is not None and time.monotonic() >= deadline:
                session = client.cancel_capture_session(session_id)
                break
            time.sleep(args.poll)
            session = client.capture_session_status(session_id)
    except KeyboardInterrupt:
        print("\nCancellation requested…", file=sys.stderr)
        session = client.cancel_capture_session(session_id)

    print(json.dumps(session, indent=2, sort_keys=True))
    return 0 if session.get("state") in ("completed", "cancelled") else 1


def _controls(args: argparse.Namespace) -> dict[str, Any]:
    quality = getattr(args, "jpeg_quality", None)
    if quality is None:
        return {}
    if not 1 <= quality <= 100:
        raise ValueError("--jpeg-quality must be between 1 and 100")
    return {"jpegQuality": quality}
