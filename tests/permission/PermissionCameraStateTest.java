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

        s = new CameraPermissionState();
        equal(GRANTED, s.authorize(true, false)); // A grant-only button never opts in.
        equal(NONE, s.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(REQUEST, s.authorize(false, false));
        equal(NONE, s.enable(false, false)); // Do not upgrade an in-flight authorization.
        equal(GRANTED, s.permissionResult(true, false));
        equal(NONE, s.permissionResult(true, false));
        cases++;

        s = new CameraPermissionState();
        equal(REQUEST, s.authorize(false, false));
        equal(SETTINGS, s.permissionResult(false, true));
        equal(false, s.enablesAfterGrant());
        equal(true, s.openSettings(s.settingsToken()));
        s.paused();
        equal(GRANTED, s.resumed(true)); // Permanent denial round trip is also grant-only.
        equal(NONE, s.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.authorize(false, true)); // Overlay uses Settings, not runtime requests.
        token = s.settingsToken();
        s.cancelSettings(token);
        equal(false, s.openSettings(token));
        equal(NONE, s.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.authorize(false, true));
        equal(true, s.openSettings(s.settingsToken()));
        equal(NONE, s.resumed(true)); // Must leave before a settings result is consumed.
        s.paused();
        equal(DENIED, s.resumed(false));
        equal(NONE, s.resumed(true)); // Granting later does not resurrect this transaction.
        cases++;

        s = new CameraPermissionState();
        equal(REQUEST, s.authorize(false, false));
        s.disable();
        equal(NONE, s.permissionResult(true, false));
        equal(ENABLE, s.enable(true, false)); // Fresh enable gesture remains explicit.
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        equal(true, s.openSettings(s.settingsToken()));
        s.paused();
        s.disable(); // Closing the global switch cancels pending overlay enable too.
        equal(NONE, s.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.authorize(false, true));
        token = s.settingsToken();
        equal(SETTINGS, s.enable(false, true));
        s.cancelSettings(token);
        equal(false, s.openSettings(token));
        equal(true, s.openSettings(s.settingsToken()));
        s.paused();
        equal(ENABLE, s.resumed(true)); // Only the latest explicit gesture owns Settings.
        cases++;

        CameraPermissionState camera = new CameraPermissionState();
        CameraPermissionState overlay = new CameraPermissionState();
        equal(REQUEST, camera.authorize(false, false));
        equal(SETTINGS, overlay.authorize(false, true));
        equal(true, overlay.openSettings(overlay.settingsToken()));
        overlay.paused();
        equal(GRANTED, camera.permissionResult(true, false));
        equal(GRANTED, overlay.resumed(true)); // Neither permission grants an enable intent.
        equal(NONE, camera.resumed(true));
        equal(NONE, overlay.resumed(true));
        cases++;

        camera = requesting();
        overlay = new CameraPermissionState();
        equal(SETTINGS, overlay.enable(false, true));
        equal(true, overlay.openSettings(overlay.settingsToken()));
        overlay.paused();
        camera.disable();
        overlay.disable(); // MainActivity cancels both when global enable is switched off.
        equal(NONE, camera.permissionResult(true, false));
        equal(NONE, overlay.resumed(true));
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.authorize(false, true));
        equal(true, s.openSettings(s.settingsToken()));
        s.paused();
        s.destroy();
        recreated = new CameraPermissionState();
        equal(NONE, s.resumed(true));
        equal(NONE, recreated.resumed(true)); // Recreation never restores pending enable.
        equal(NONE, s.authorize(true, false));
        cases++;

        s = requesting();
        equal(NONE, s.authorize(false, false)); // Grant-only supersedes old pending enable.
        equal(false, s.enablesAfterGrant());
        equal(GRANTED, s.permissionResult(true, false)); // Reuse OS dialog, do not enable.
        cases++;

        s = new CameraPermissionState();
        equal(SETTINGS, s.enable(false, true));
        token = s.settingsToken();
        equal(SETTINGS, s.authorize(false, true));
        equal(false, s.openSettings(token)); // Previous enable dialog is stale.
        equal(true, s.openSettings(s.settingsToken()));
        s.paused();
        equal(GRANTED, s.resumed(true));
        cases++;

        s = settings();
        s.paused();
        equal(GRANTED, s.authorize(true, false)); // Supersede an already-open Settings trip.
        equal(NONE, s.resumed(true));
        cases++;

        camera = settings();
        camera.paused();
        overlay = new CameraPermissionState();
        camera.disable(); // MainActivity enforces one permission transaction at a time.
        equal(SETTINGS, overlay.authorize(false, true));
        equal(true, overlay.openSettings(overlay.settingsToken()));
        overlay.paused();
        equal(NONE, camera.resumed(true)); // Overlay settings return cannot enable camera.
        equal(GRANTED, overlay.resumed(true));
        cases++;

        overlay = settings();
        overlay.paused();
        camera = new CameraPermissionState();
        overlay.disable(); // Switching to camera authorization invalidates overlay enable.
        equal(REQUEST, camera.authorize(false, false));
        equal(NONE, overlay.resumed(true));
        equal(GRANTED, camera.permissionResult(true, false));
        cases++;

        System.out.println("PASS: " + cases + " camera/overlay permission state scenarios");
    }
}
