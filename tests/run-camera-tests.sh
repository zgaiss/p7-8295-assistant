#!/bin/sh
# Host-only tests. Android fakes model callbacks, not HAL/optical correctness.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
cd "$ROOT"
python3 tests/permission_run_host_tests.py
python3 tests/avm_run_host_tests.py
python3 tests/mirror_camera_run_host_tests.py
python3 tests/mirror_gate_run_host_tests.py
python3 tests/turn_lamp_run_host_tests.py
python3 tests/mirror_overlay_run_host_tests.py
python3 - <<'PY'
import pathlib, subprocess, tempfile
root = pathlib.Path.cwd()
with tempfile.TemporaryDirectory(prefix='p7-mirror-avm-state-') as out:
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', out,
                    str(root/'carpad/src/com/xiaopeng/clusterassist/MirrorAvmSession.java'),
                    str(root/'tests/MirrorAvmSessionTest.java')], check=True)
    subprocess.run(['java', '-cp', out, 'com.xiaopeng.clusterassist.MirrorAvmSessionTest'], check=True)
PY
