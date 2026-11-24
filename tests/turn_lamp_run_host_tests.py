#!/usr/bin/env python3
"""Compile the pure turn-lamp phase window and run its host tests.

Extracted policy only: no Android, camera, or HAL behavior is exercised here.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    resolved = shutil.which(name)
    if not resolved:
        raise SystemExit("Missing JDK tool: " + name)
    return resolved


def main():
    with tempfile.TemporaryDirectory(prefix="p7-turn-lamp-host-") as tmp:
        subprocess.run([tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-Xlint:-options", "-d", tmp,
                        str(ROOT / "carpad/src/com/xiaopeng/clusterassist/TurnLamp.java"),
                        str(ROOT / "tests/TurnLampTest.java")], check=True)
        subprocess.run([tool("java"), "-ea", "-cp", tmp,
                        "com.xiaopeng.clusterassist.TurnLampTest"], check=True)


if __name__ == "__main__":
    main()
