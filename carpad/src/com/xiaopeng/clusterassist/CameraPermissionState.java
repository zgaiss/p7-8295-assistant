package com.xiaopeng.clusterassist;

/** Android-free, fail-closed permission transaction; each instance owns one permission. */
final class CameraPermissionState {
    enum Action { NONE, ENABLE, GRANTED, REQUEST, DENIED, SETTINGS }

    private boolean alive = true;
    private boolean wanted;
    private boolean enableAfterGrant;
    private boolean requesting;
    private boolean settingsPrompt;
    private boolean settingsReturn;
    private boolean settingsPaused;
    private int generation;

    Action enable(boolean granted, boolean permanentlyDenied) {
        return begin(granted, permanentlyDenied, true);
    }

    Action authorize(boolean granted, boolean permanentlyDenied) {
        // An explicit grant-only gesture supersedes any older enable intention.
        // disable() retains an in-flight OS request, so its callback is safely reused.
        disable();
        return begin(granted, permanentlyDenied, false);
    }

    private Action begin(boolean granted, boolean permanentlyDenied, boolean enable) {
        if (!alive) return Action.NONE;
        // A second gesture must not silently change the purpose of an active OS request.
        if (requesting && wanted) return Action.NONE;
        wanted = true;
        enableAfterGrant = enable;
        if (granted) {
            disable();
            return enable ? Action.ENABLE : Action.GRANTED;
        }
        // An OFF/ON gesture while Android still owns the dialog reuses that request.
        if (requesting) return Action.NONE;
        generation++;
        settingsReturn = false;
        settingsPaused = false;
        settingsPrompt = permanentlyDenied;
        if (permanentlyDenied) {
            wanted = false; // Only the settings dialog's positive button may re-arm it.
            return Action.SETTINGS;
        }
        requesting = true;
        return Action.REQUEST;
    }

    Action permissionResult(boolean granted, boolean permanentlyDenied) {
        if (!alive || !requesting) return Action.NONE;
        requesting = false;
        if (!wanted) return Action.NONE;
        wanted = false;
        generation++;
        if (granted) return enableAfterGrant ? Action.ENABLE : Action.GRANTED;
        settingsPrompt = permanentlyDenied;
        return permanentlyDenied ? Action.SETTINGS : Action.DENIED;
    }

    int settingsToken() { return generation; }

    boolean enablesAfterGrant() { return enableAfterGrant; }

    boolean openSettings(int token) {
        if (!alive || !settingsPrompt || token != generation) return false;
        settingsPrompt = false;
        wanted = true;
        settingsReturn = true;
        settingsPaused = false;
        return true;
    }

    void cancelSettings(int token) {
        if (token == generation) disable();
    }

    void paused() {
        if (alive && settingsReturn) settingsPaused = true;
    }

    Action resumed(boolean granted) {
        if (!alive || !settingsReturn || !settingsPaused) return Action.NONE;
        Action result = wanted && granted
                ? (enableAfterGrant ? Action.ENABLE : Action.GRANTED) : Action.DENIED;
        disable();
        return result;
    }

    void disable() {
        wanted = false;
        settingsPrompt = false;
        settingsReturn = false;
        settingsPaused = false;
        generation++;
        // Keep requesting until its callback, so a second OS dialog is never launched.
    }

    void destroy() {
        disable();
        alive = false;
    }
}
