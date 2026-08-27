#!/usr/bin/env python3
"""Compatibility entry point for the former single-photo example."""

from __future__ import annotations

import sys

from camera_control.cli import main


if __name__ == "__main__":
    raise SystemExit(main(["capture", *sys.argv[1:]]))
