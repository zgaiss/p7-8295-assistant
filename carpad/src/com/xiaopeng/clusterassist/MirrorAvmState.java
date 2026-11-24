package com.xiaopeng.clusterassist;

import android.content.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cached AVM display-mode snapshot read through the local adb shell.
 *
 * <p>This firmware does not populate the vendor CameraManager parameters, so the only
 * readable pre-capture mode is the CarPropertyService lastEvent. Reading it needs shell,
 * which the app already has for the local adb channel. The cache is intentionally short
 * lived: a stale value must never be used to restore a view we did not enter.</p>
 */
final class MirrorAvmState {
    private static final Pattern VALUE =
            Pattern.compile("int32Values: \\[([0-9]+)\\]");
    private static final long FRESH_MS = 60000;
    private static final long RETRY_MS = 5000;

    private static volatile int mode = -1;
    private static volatile long at;
    private static volatile boolean running;

    private MirrorAvmState() { }

    /** Fresh snapshot, or -1 when the shell channel has not produced one yet. */
    static int mode() {
        return at != 0 && System.currentTimeMillis() - at <= FRESH_MS ? mode : -1;
    }

    /** Refresh in the background; a blocking adb handshake must never run on the UI thread. */
    static void refresh(final Context ctx) {
        long now = System.currentTimeMillis();
        if (running || (at != 0 && now - at < RETRY_MS)) return;
        running = true;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String out = LocalAdb.shell(app,
                            "dumpsys car_service 2>/dev/null"
                                    + " | grep -oE 'AVM_DISPLAY_MODE.*int32Values: \\[[0-9]+\\]'",
                            4000);
                    if (out != null) {
                        Matcher m = VALUE.matcher(out);
                        if (m.find()) {
                            int value = Integer.parseInt(m.group(1));
                            if (value >= 0) {
                                mode = value;
                                at = System.currentTimeMillis();
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Keep the old value until it expires; quad then fails closed.
                } finally {
                    running = false;
                }
            }
        }, "p7-avm-state").start();
    }
}
