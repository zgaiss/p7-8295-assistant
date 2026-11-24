#!/usr/bin/env python3
"""Stable entry point for the deterministic real-MirrorSource host tests."""
from pathlib import Path
import runpy

if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parent / "mirror-camera/run_host_tests.py"), run_name="__main__")
