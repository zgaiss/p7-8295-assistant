package com.xiaopeng.clusterassist;

import static com.xiaopeng.clusterassist.CameraPermissionState.Action.*;

/** Host tests: no Android SDK, emulator, account, camera or ADB required. */
public final class PermissionCameraStateTest {
    private static int cases;

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    private static CameraPermissionState requesting() {
        CameraPermissionState s = new CameraPermissionState();
        equal(REQUEST, s.enable(false, false));
        return s;
    }

    private static CameraPermissionState settings() {
        CameraPermissionState s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        equal(true, s.openSettings(s.settingsToken()));
        return s;
    }

    public static void main(String[] args) {
        CameraPermissionState s = new CameraPermissionState();
        equal(NONE, s.resumed(true)); // Existing grant alone must not opt a user in.
        cases++;

        s = new CameraPermissionState();
        equal(ENABLE, s.enable(true, false)); // Pre-granted upgrade, no extra OS request.
        equal(NONE, s.resumed(true));
        cases++;

        s = requesting();
        equal(NONE, s.resumed(false)); // OS resume may precede permission callback.
        equal(ENABLE, s.permissionResult(true, false));
        equal(NONE, s.permissionResult(true, false)); // Duplicate callback.
        cases++;

        s = requesting();
        equal(DENIED, s.permissionResult(false, false)); // Ordinary denial / empty callback.
        equal(NONE, s.resumed(true));
        equal(REQUEST, s.enable(false, false)); // Explicit retry remains possible.
        cases++;

        s = requesting();
        equal(SETTINGS, s.permissionResult(false, true)); // Never-ask-again.
        equal(NONE, s.resumed(true)); // Merely showing guidance is not settings consent.
        cases++;

        s = requesting();
        s.disable();
        equal(NONE, s.permissionResult(true, false)); // OFF beats late grant.
        equal(NONE, s.resumed(true));
        cases++;

        s = requesting();
        s.disable();
        equal(NONE, s.enable(false, false)); // Reuse in-flight request, never launch two.
        equal(ENABLE, s.permissionResult(true, false));
        cases++;

        s = requesting();
        s.disable();
        equal(NONE, s.permissionResult(false, true)); // No unsolicited settings prompt.
        cases++;

        s = new CameraPermissionState();
        equal(NONE, s.permissionResult(true, false)); // Unsolicited/old instance callback.
        cases++;

        s = settings();
        equal(NONE, s.resumed(true)); // Must actually have left for Settings.
        s.paused();
        equal(ENABLE, s.resumed(true));
        equal(NONE, s.resumed(true)); // Consume once.
        cases++;

        s = settings();
        s.paused();
        equal(DENIED, s.resumed(false)); // Back without granting leaves mirror off.
        equal(NONE, s.resumed(true)); // Later unrelated grant cannot revive stale intent.
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        int token = s.settingsToken();
        s.cancelSettings(token); // Negative button / Back / outside tap.
        equal(false, s.openSettings(token));
        s.paused();
        equal(NONE, s.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        token = s.settingsToken();
        s.disable();
        equal(false, s.openSettings(token)); // Old dialog cannot reopen after OFF.
        cases++;

        s = settings();
        s.paused();
        s.disable(); // OFF or failed startActivity cancels settings return.
        equal(NONE, s.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        int oldToken = s.settingsToken();
        equal(SETTINGS, s.enable(false, true));
        s.cancelSettings(oldToken); // Stale dismiss must not cancel newer dialog.
        equal(false, s.openSettings(oldToken));
        equal(true, s.openSettings(s.settingsToken()));
        s.paused();
        equal(ENABLE, s.resumed(true));
        cases++;

        s = requesting();
        s.destroy();
        equal(NONE, s.permissionResult(true, false));
        equal(NONE, s.enable(true, false));
        equal(NONE, s.resumed(true));
        cases++;

        s = settings();
        s.paused();
        s.destroy();
        equal(NONE, s.resumed(true));
        equal(false, s.openSettings(s.settingsToken()));
        cases++;

        s = requesting();
        s.destroy();
        CameraPermissionState recreated = new CameraPermissionState();
        equal(NONE, recreated.permissionResult(true, false)); // Recreation fails closed.
        equal(NONE, recreated.resumed(true));
        equal(ENABLE, recreated.enable(true, false)); // A fresh gesture still works.
        cases++;

        s = requesting();
        equal(DENIED, s.permissionResult(false, false)); // requestPermissions exception.
        equal(REQUEST, s.enable(false, false)); // Failure does not wedge request state.
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        token = s.settingsToken();
        equal(ENABLE, s.enable(true, false)); // Grant before dialog's positive button.
        equal(false, s.openSettings(token));
        cases++;

        System.out.println("PASS: " + cases + " camera permission state scenarios");
    }
}
