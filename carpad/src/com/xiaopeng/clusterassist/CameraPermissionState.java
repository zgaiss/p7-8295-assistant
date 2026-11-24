package com.xiaopeng.clusterassist;

/** Android-free, fail-closed state for an explicit mirror enable gesture. */
final class CameraPermissionState {
    enum Action { NONE, ENABLE, REQUEST, DENIED, SETTINGS }

    private boolean alive = true;
    private boolean wanted;
    private boolean requesting;
    private boolean settingsPrompt;
    private boolean settingsReturn;
    private boolean settingsPaused;
    private int generation;

    Action enable(boolean granted, boolean permanentlyDenied) {
        if (!alive) return Action.NONE;
        wanted = true;
        if (granted) {
            disable();
            return Action.ENABLE;
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
        if (granted) return Action.ENABLE;
        settingsPrompt = permanentlyDenied;
        return permanentlyDenied ? Action.SETTINGS : Action.DENIED;
    }

    int settingsToken() { return generation; }

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
        boolean enable = wanted && granted;
        disable();
        return enable ? Action.ENABLE : Action.DENIED;
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
