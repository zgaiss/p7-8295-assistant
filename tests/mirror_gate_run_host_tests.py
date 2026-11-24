#!/usr/bin/env python3
"""Compile policy + the actual mirrorStep method with fake signal/action endpoints.

This is an extracted-production-method host test, not an Android service or HAL test.
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


def mirror_step():
    source = (ROOT / "carpad/src/com/xiaopeng/clusterassist/PushService.java").read_text()
    start = source.index("    private void mirrorStep() {")
    # Current method has no braces inside literals/comments; fail rather than
    # silently extract a different region if its next method boundary changes.
    end = source.index("    private void startMirror(int side)", start)
    method = source[start:end].strip()
    assert method.endswith("}") and method.count("{") == method.count("}")
    return method


HARNESS = r'''
package com.xiaopeng.clusterassist;
final class MirrorStepHarness {
    Prefs prefs = Prefs.saved;
    long prefsReloadAt, xuiRetryAt, mirrorLogAt, mirrorRetryAt;
    int mirrorSide, mirrorRetrySide, mirrorFailures, mirrorCamera;
    boolean mirrorUsingAvm;
    MirrorSource mirrorSrc;
    MirrorOverlay mirrorOverlay = new MirrorOverlay();
    boolean cameraGranted = true;
    boolean permissionThrows;
    int checkSelfPermission(String permission) {
        if (permissionThrows) throw new SecurityException("permission service unavailable");
        return cameraGranted ? 0 : -1;
    }
    int starts, stops, startedSide;
    boolean restore, closeSocket;
    void step() { mirrorStep(); }
    private void startMirror(int side) { starts++; startedSide = side; mirrorSide = side; }
    private void stopMirror(boolean close, boolean restoreMode) {
        stops++; closeSocket = close; restore = restoreMode; mirrorSide = 0; mirrorSrc = null;
    }
    private void stopMirror(boolean close) { stopMirror(close, true); }
    __PRODUCTION_METHOD__
}
final class Prefs {
    static Prefs saved;
    boolean master = true, mirror = true, mirrorAvm = true, mirrorOverlay;
    int mirrorCamL, mirrorCamR;
    static Prefs load(Object ignored) { return saved; }
}
final class MirrorOverlay {
    int changes;
    boolean enabled;
    void setEnabled(boolean value) { changes++; enabled = value; }
}
final class MirrorSource {
    static int yields;
    static void yieldToNative() { yields++; }
    boolean isStopped() { return false; }
    String lastError() { return ""; }
}
final class XuiLight {
    static final int GEAR_REVERSE = 2;
    static boolean ready = true;
    static int value, reads, lampReads;
    static Boolean left, right;
    static boolean isReady() { return ready; }
    static void start() { }
    static int gear() { reads++; return value; }
    static boolean isReverse() { return value == GEAR_REVERSE; }
    static Boolean isLeftOn() { lampReads++; return left; }
    static Boolean isRightOn() { lampReads++; return right; }
    static int eventCount() { return 0; }
    static String lastError() { return ""; }
}
final class CarBridge {
    static final int GEAR_REVERSE = 3;
    static Integer value;
    static int lampReads;
    static Boolean left, right;
    static Integer readGear() { return value; }
    static Boolean readTurnLeft() { lampReads++; return left; }
    static Boolean readTurnRight() { lampReads++; return right; }
    static String lastError() { return ""; }
}
final class Log {
    static int i(String tag, String message) { return 0; }
    static int w(String tag, String message) { return 0; }
}
'''


def main():
    with tempfile.TemporaryDirectory(prefix="p7-mirror-gate-host-") as tmp:
        out = Path(tmp)
        harness = out / "MirrorStepHarness.java"
        harness.write_text(HARNESS.replace("__PRODUCTION_METHOD__", mirror_step()))
        clock = out / "SystemClock.java"
        clock.write_text("package android.os; public final class SystemClock { "
                         "public static long elapsedRealtime() { return 10000; } }")
        manifest = out / "Manifest.java"
        manifest.write_text('package android; public final class Manifest { public static final class permission { '
                            'public static final String CAMERA = "android.permission.CAMERA"; } }')
        manager = out / "PackageManager.java"
        manager.write_text('package android.content.pm; public final class PackageManager { '
                           'public static final int PERMISSION_GRANTED = 0; }')
        subprocess.run([tool("javac"), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                        "-Xlint:-options", "-d", tmp,
                        str(ROOT / "carpad/src/com/xiaopeng/clusterassist/MirrorTriggerGate.java"),
                        str(ROOT / "tests/MirrorTriggerGateTest.java"),
                        str(harness), str(clock), str(manifest), str(manager)], check=True)
        subprocess.run([tool("java"), "-ea", "-cp", tmp,
                        "com.xiaopeng.clusterassist.MirrorTriggerGateTest"], check=True)


if __name__ == "__main__":
    main()
