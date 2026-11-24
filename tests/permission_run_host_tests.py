#!/usr/bin/env python3
"""Run camera-permission host regression tests with JDK 8+ and no Android SDK."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    home = os.environ.get("JAVA_HOME")
    candidate = Path(home) / "bin" / name if home else None
    if candidate is not None and candidate.is_file():
        return str(candidate)
    resolved = shutil.which(name)
    if not resolved:
        raise SystemExit("Missing JDK tool: " + name)
    return resolved


def main():
    # TemporaryDirectory owns this fresh absolute directory and cleans only its own output.
    with tempfile.TemporaryDirectory(prefix="p7-permission-host-") as output:
        subprocess.run([
            java_tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-Xlint:-options", "-d", output,
            str(ROOT / "carpad/src/com/xiaopeng/clusterassist/CameraPermissionState.java"),
            str(ROOT / "tests/permission/PermissionCameraStateTest.java"),
        ], check=True)
        subprocess.run([
            java_tool("java"), "-ea", "-cp", output,
            "com.xiaopeng.clusterassist.PermissionCameraStateTest",
        ], check=True)


if __name__ == "__main__":
    main()
