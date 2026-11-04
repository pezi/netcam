#!/usr/bin/env python3
"""Command-line remote shutter for NetCam's Tripod Mode.

Talks to the login-free links /photo, /next, /delete, /remote/state and
/remote/photo of a running NetCam server. The Tripod screen must be open on
the phone, otherwise every action answers 409 tripod_inactive.

Usage:
    tripod_shutter.py BASE_URL state
    tripod_shutter.py BASE_URL photo [--output FILE]
    tripod_shutter.py BASE_URL next
    tripod_shutter.py BASE_URL delete
    tripod_shutter.py BASE_URL download --output FILE
    tripod_shutter.py BASE_URL sequence [--interval S] [--count N] [--prefix P]
"""

from __future__ import annotations

import argparse
import json
import sys
import time

import requests

EXIT_OK = 0
EXIT_ERROR = 1
EXIT_INACTIVE = 2
EXIT_CAMERA = 3

HEADERS = {"Accept": "application/json"}


class TripodError(Exception):
    """An error answer from the server, carrying the exit code to use."""

    def __init__(self, message: str, exit_code: int) -> None:
        super().__init__(message)
        self.exit_code = exit_code


class TripodShutter:
    """Client for the Tripod Mode links of one NetCam server."""

    def __init__(self, base_url: str, ca: str | None, timeout: float) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.session = requests.Session()
        self.session.headers.update(HEADERS)
        if ca:
            self.session.verify = ca

    def _get_json(self, path: str) -> dict:
        try:
            response = self.session.get(self.base_url + path, timeout=self.timeout)
        except requests.RequestException as error:
            raise TripodError(f"request failed: {error}", EXIT_ERROR) from error
        try:
            body = response.json()
        except ValueError as error:
            raise TripodError(
                f"{path}: unexpected {response.status_code} answer without JSON",
                EXIT_ERROR,
            ) from error
        if response.ok:
            return body
        code = body.get("error", "unknown")
        if code == "tripod_inactive":
            raise TripodError("Tripod Mode is not open on the phone", EXIT_INACTIVE)
        if code in ("busy", "camera_unavailable"):
            raise TripodError(f"camera {code.replace('_', ' ')}", EXIT_CAMERA)
        message = body.get("message", "")
        raise TripodError(f"{path}: {response.status_code} {code} {message}".strip(), EXIT_ERROR)

    def state(self) -> dict:
        return self._get_json("/remote/state")

    def photo(self) -> dict:
        return self._get_json("/photo")

    def next(self) -> dict:
        return self._get_json("/next")

    def delete(self) -> dict:
        return self._get_json("/delete")

    def download(self, path: str) -> None:
        """Saves the photo currently under review to ``path``."""
        try:
            with self.session.get(
                self.base_url + "/remote/photo", timeout=self.timeout, stream=True
            ) as response:
                if response.status_code == 404:
                    raise TripodError("no photo is under review", EXIT_ERROR)
                response.raise_for_status()
                with open(path, "wb") as file:
                    for chunk in response.iter_content(65536):
                        file.write(chunk)
        except requests.RequestException as error:
            raise TripodError(f"download failed: {error}", EXIT_ERROR) from error


def print_json(value: dict) -> None:
    print(json.dumps(value, indent=2))


def run_sequence(shutter: TripodShutter, args: argparse.Namespace) -> None:
    """Takes photos every ``interval`` seconds and saves each one."""
    taken = 0
    while args.count is None or taken < args.count:
        started = time.monotonic()
        result = shutter.photo()
        taken += 1
        path = f"{args.prefix}_{taken:04d}.jpg"
        shutter.download(path)
        shutter.next()
        print(f"{path}  {result['photo']['takenAt']}")
        if args.count is not None and taken >= args.count:
            break
        time.sleep(max(0.0, args.interval - (time.monotonic() - started)))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Remote shutter for NetCam's Tripod Mode.")
    parser.add_argument("base_url", help="server address, e.g. http://192.168.1.50:8080")
    parser.add_argument("--ca", metavar="FILE", help="CA certificate for HTTPS (the phone's /ca.crt)")
    parser.add_argument("--timeout", type=float, default=15, metavar="SECONDS")
    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("state", help="print the Tripod state")
    photo = commands.add_parser("photo", help="take a photo")
    photo.add_argument("--output", metavar="FILE", help="also download the photo to FILE")
    commands.add_parser("next", help="keep the shown photo, back to the preview")
    commands.add_parser("delete", help="delete the shown photo, back to the preview")
    download = commands.add_parser("download", help="save the photo under review")
    download.add_argument("--output", metavar="FILE", required=True)
    sequence = commands.add_parser("sequence", help="photo, download, next in a loop")
    sequence.add_argument("--interval", type=float, default=10, metavar="SECONDS")
    sequence.add_argument("--count", type=int, metavar="N", help="stop after N photos")
    sequence.add_argument("--prefix", default="photo", help="file name prefix")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    shutter = TripodShutter(args.base_url, args.ca, args.timeout)
    try:
        if args.command == "state":
            print_json(shutter.state())
        elif args.command == "photo":
            print_json(shutter.photo())
            if args.output:
                shutter.download(args.output)
                print(f"saved {args.output}")
        elif args.command == "next":
            print_json(shutter.next())
        elif args.command == "delete":
            print_json(shutter.delete())
        elif args.command == "download":
            shutter.download(args.output)
            print(f"saved {args.output}")
        elif args.command == "sequence":
            run_sequence(shutter, args)
    except TripodError as error:
        print(f"error: {error}", file=sys.stderr)
        return error.exit_code
    except KeyboardInterrupt:
        return EXIT_ERROR
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
