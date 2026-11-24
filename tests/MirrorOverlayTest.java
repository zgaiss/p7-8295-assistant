package com.xiaopeng.clusterassist;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Arrays;

/** Deterministic host checks of the real production classes, not extracted copies. */
public final class MirrorOverlayTest {
    private static final int BYTES = 640 * 360 * 2;
    private static int checks, scenarios, failures;

    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }

    private static void run(String name, Runnable body) {
        scenarios++;
        Handler.reset();
        Bitmap.createThrows = false;
        Bitmap.copyThrows = false;
        Bitmap.copyClockAdvance = 0;
        try {
            body.run();
            System.out.println("  PASS " + name);
        } catch (Throwable error) {
            failures++;
            System.err.println("  FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static byte[] pixels(int value) {
        byte[] result = new byte[BYTES];
        Arrays.fill(result, (byte) value);
        return result;
    }

    private static final class Counter implements MirrorFrameSink {
        int calls, side;
        byte[] bytes;
        boolean fail;
        @Override public void submit(byte[] p, int direction) {
            calls++;
            bytes = p;
            side = direction;
            if (fail) throw new IllegalStateException("injected sink failure");
        }
    }

    private static final class Fixture {
        final Context context = new Context();
        final WindowManager windows = context.windows;
        final MirrorOverlay overlay = new MirrorOverlay(context);
        MirrorFrameSink sink;
        Fixture() { overlay.setEnabled(true); sink = overlay.begin(1); }
        void frame(int value) { sink.submit(pixels(value), 1); Handler.runReady(); }
        Canvas draw() {
            Canvas canvas = new Canvas();
            windows.lastView.draw(canvas);
            return canvas;
        }
    }

    private static void fanoutBoth() {
        Counter local = new Counter(), remote = new Counter();
        MirrorFrameFanout fanout = new MirrorFrameFanout(local, remote);
        byte[] input = pixels(7);
        fanout.submit(input, 2);
        check(local.calls == 1 && remote.calls == 1, "one capture submit reaches each sink exactly once");
        check(local.bytes == input && remote.bytes == input, "fanout does not recapture or recopy");
        check(local.side == 2 && remote.side == 2, "side reaches both consumers unchanged");
    }

    private static void fanoutFailures() {
        Counter local = new Counter(), remote = new Counter();
        remote.fail = true;
        MirrorFrameFanout fanout = new MirrorFrameFanout(local, remote);
        fanout.submit(pixels(1), 1);
        check(local.calls == 1 && remote.calls == 1, "remote exception cannot stop local delivery");
        local.fail = true; remote.fail = false;
        fanout.submit(pixels(2), 2);
        check(local.calls == 2 && remote.calls == 2, "local exception cannot stop network delivery");
        new MirrorFrameFanout(null, remote).submit(pixels(3), 1);
        new MirrorFrameFanout(local, null).submit(pixels(3), 1);
        check(remote.calls == 3 && local.calls == 3, "independent optional sinks may be absent");
    }

    private static void fanoutClose() {
        Counter local = new Counter(), remote = new Counter();
        final MirrorFrameFanout[] owner = new MirrorFrameFanout[1];
        owner[0] = new MirrorFrameFanout((p, s) -> { local.submit(p, s); owner[0].close(); }, remote);
        owner[0].submit(pixels(1), 1);
        check(local.calls == 1 && remote.calls == 0, "close during local submit suppresses subsequent remote delivery");
        owner[0].submit(pixels(2), 1);
        check(local.calls == 1 && remote.calls == 0, "close rejects late source callbacks");
        owner[0].close();
    }

    private static MirrorPreviewFrames mailbox() {
        MirrorPreviewFrames frames = new MirrorPreviewFrames(4);
        frames.setEnabled(true);
        return frames;
    }

    private static void mailboxCopy() {
        MirrorPreviewFrames frames = mailbox();
        long token = frames.begin(1);
        byte[] source = {1, 2, 3, 4}, dest = new byte[4];
        check(frames.offer(token, source, 1, 100), "valid offer accepted");
        source[0] = 99;
        MirrorPreviewFrames.Frame frame = frames.takeInto(dest, 101, 800);
        check(frame != null && dest[0] == 1 && dest[3] == 4, "mailbox owns a copied frame");
        check(frame.side == 1 && frame.at == 100, "side and monotonic timestamp retained");
        check(frames.takeInto(dest, 101, 800) == null, "frame can be consumed once only");
    }

    private static void mailboxBounded() {
        MirrorPreviewFrames frames = mailbox();
        long token = frames.begin(2);
        byte[] input = new byte[4], dest = new byte[4];
        for (int i = 0; i < 10000; i++) {
            Arrays.fill(input, (byte)i);
            check(frames.offer(token, input, 2, i), "new frame accepted while previous is pending");
        }
        MirrorPreviewFrames.Frame frame = frames.takeInto(dest, 10000, 800);
        check(frame != null && frame.at == 9999 && dest[3] == (byte)9999, "only newest frame survives flood");
        check(frames.takeInto(dest, 10000, 800) == null, "no backlog after 10000 offers");
    }

    private static void mailboxDisable() {
        MirrorPreviewFrames frames = mailbox();
        long token = frames.begin(1);
        frames.offer(token, new byte[4], 1, 100);
        frames.setEnabled(false);
        check(!frames.offer(token, new byte[4], 1, 101), "disabled mailbox rejects offers");
        frames.setEnabled(true);
        check(frames.takeInto(new byte[4], 101, 800) == null, "disable clears pending pixels permanently");
        check(frames.offer(token, new byte[4], 1, 102), "same live source can resume when enabled");
    }

    private static void mailboxGenerations() {
        MirrorPreviewFrames frames = mailbox();
        long left = frames.begin(1);
        frames.offer(left, new byte[4], 1, 100);
        frames.stop();
        check(frames.takeInto(new byte[4], 101, 800) == null, "stop clears pending frame");
        check(!frames.offer(left, new byte[4], 1, 101), "stop invalidates source generation");
        long right = frames.begin(2);
        check(!frames.offer(left, new byte[4], 1, 101), "old-side token cannot revive new session");
        check(!frames.offer(right, new byte[4], 1, 101), "new token with wrong side rejected");
        check(frames.offer(right, new byte[4], 2, 101), "new-side source accepted");
        long invalid = frames.begin(9);
        check(!frames.offer(invalid, new byte[4], 9, 101), "invalid direction fails closed");
    }

    private static void mailboxValidation() {
        MirrorPreviewFrames frames = mailbox();
        long token = frames.begin(1);
        check(!frames.offer(token, null, 1, 100), "null input rejected");
        check(!frames.offer(token, new byte[3], 1, 100), "short input rejected");
        check(!frames.offer(token, new byte[5], 1, 100), "long input rejected");
        frames.offer(token, new byte[] {1,2,3,4}, 1, 100);
        check(frames.takeInto(null, 100, 800) == null, "null destination rejected");
        check(frames.takeInto(new byte[3], 100, 800) == null, "short destination rejected");
        check(frames.takeInto(new byte[4], 899, 800) != null, "799ms frame still fresh");
        frames.offer(token, new byte[4], 1, 100);
        check(frames.takeInto(new byte[4], 900, 800) == null, "800ms frame rejected at boundary");
        check(frames.takeInto(new byte[4], 901, 800) == null, "expired frame is discarded not retained");
        frames.offer(token, new byte[4], 1, 100);
        check(frames.takeInto(new byte[4], 99, 800) == null, "future timestamp rejected");
    }

    private static void noFrames() {
        Fixture f = new Fixture();
        Handler.advanceTo(100000);
        check(f.windows.adds == 0 && Handler.pending() == 0, "enable/begin with no frames never adds or polls a window");
        f.sink.submit(null, 1); f.sink.submit(new byte[4], 1); f.sink.submit(pixels(1), 2);
        Handler.runReady();
        check(f.windows.adds == 0, "malformed or wrong-side inputs cannot attach");
    }

    private static void freshFrame() {
        Fixture f = new Fixture();
        byte[] source = pixels(21);
        f.sink.submit(source, 1);
        Arrays.fill(source, (byte)99);
        check(f.windows.adds == 0, "camera callback does not touch WindowManager");
        Handler.runReady();
        check(f.windows.adds == 1 && f.windows.installed.size() == 1, "first fresh frame adds exactly one real overlay");
        Canvas canvas = f.draw();
        check(canvas.bitmapDraws == 1 && canvas.bitmap.config == Bitmap.Config.RGB_565, "production onDraw renders RGB565 bitmap");
        check(canvas.bitmap.width == 640 && canvas.bitmap.height == 360, "same source dimensions retained");
        check(canvas.bitmap.pixels[0] == 21 && canvas.bitmap.pixels[BYTES - 1] == 21, "queued UI never reads mutated capture buffer");
        check("左转影像".equals(canvas.text), "left label is rendered");
        check(canvas.destination.right == f.windows.lastLayout.width && canvas.destination.bottom == f.windows.lastLayout.height,
                "bitmap destination fills the 16:9 window");
    }

    private static void newestFrame() {
        Fixture f = new Fixture();
        byte[] source = pixels(0);
        for (int i = 0; i < 200; i++) { source[0] = (byte)i; f.sink.submit(source, 1); }
        check(Handler.pending() == 1, "200 camera frames schedule only one UI render");
        Handler.runReady();
        check(f.draw().bitmap.pixels[0] == (byte)199, "latest pending frame wins");
        check(f.windows.adds == 1 && Handler.pending() == 1, "after render only one expiry callback remains");
        f.frame(33);
        check(f.windows.adds == 1 && f.windows.lastView.invalidations == 2, "later frame redraws without duplicate add");
        check(f.draw().bitmap.pixels[0] == 33, "reused ByteBuffer rewinds for each copy");
    }

    private static void windowFlags() {
        Fixture f = new Fixture(); f.frame(1);
        WindowManager.LayoutParams p = f.windows.lastLayout;
        check(p.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, "uses ordinary overlay window type");
        int required = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        check((p.flags & required) == required, "window cannot intercept touch or focus");
        check(Math.abs(p.alpha - .8f) < .00001f, "opacity is exactly 80 percent for touch-through");
        check(p.gravity == (Gravity.LEFT | Gravity.CENTER_VERTICAL), "left source is left-center");
        check(p.x == 16 && p.width == 480 && p.height == 270, "default margins and size use dp with width cap");
    }

    private static void layoutSizes() {
        int[][] screens = {{800,480,2}, {1920,1080,1}, {2560,1440,2}, {320,240,1}};
        for (int[] screen : screens) {
            Fixture f = new Fixture();
            f.context.resources.metrics.widthPixels=screen[0]; f.context.resources.metrics.heightPixels=screen[1];
            f.context.resources.metrics.density=screen[2]; f.frame(1);
            WindowManager.LayoutParams p = f.windows.lastLayout;
            check(p.width * 9 == p.height * 16, "16:9 display aspect for integral reference screen");
            check(p.width <= Math.round(480 * screen[2]) && p.width <= screen[0] * 2 / 5, "width capped at 480dp and 40 percent");
            check(p.height + 32 * screen[2] <= screen[1], "window stays within vertical margins");
            f.overlay.destroy();
        }
    }

    private static void hideAndLateFrames() {
        Fixture f = new Fixture(); f.frame(1);
        View old = f.windows.lastView;
        f.sink.submit(pixels(2), 1);
        f.overlay.hide(); Handler.runReady();
        check(f.windows.installed.isEmpty() && old.visibility == View.GONE, "hide immediately removes and blanks window");
        f.sink.submit(pixels(3), 1); Handler.runReady();
        check(f.windows.adds == 1, "old sink cannot resurrect hidden capture");
        f.sink=f.overlay.begin(1); f.frame(4);
        check(f.windows.adds == 2, "new generation can display after hide");
    }

    private static void disabledClearsPending() {
        Fixture f = new Fixture();
        f.sink.submit(pixels(1), 1); f.overlay.setEnabled(false); Handler.runReady();
        check(f.windows.adds == 0, "disable before UI render discards queued pixels");
        f.sink.submit(pixels(2), 1); Handler.runReady();
        check(f.windows.adds == 0, "disabled sink never displays");
        f.overlay.setEnabled(true); Handler.runReady();
        check(f.windows.adds == 0, "re-enable alone cannot show old pending frame");
        f.frame(3); f.overlay.setEnabled(false);
        check(f.windows.installed.isEmpty(), "disable removes existing display");
    }

    private static void destroyedCannotRevive() {
        Fixture f = new Fixture();
        f.sink.submit(pixels(1), 1); f.overlay.destroy(); Handler.runReady();
        f.overlay.setEnabled(true);
        f.sink.submit(pixels(2), 1);
        MirrorFrameSink late = f.overlay.begin(2); late.submit(pixels(3), 2);
        Handler.advanceTo(10000);
        check(f.windows.adds == 0 && Handler.pending() == 0, "destroy invalidates queued/late/new callbacks forever");
        Fixture g = new Fixture(); g.frame(1); g.overlay.destroy(); g.overlay.destroy();
        check(g.windows.installed.isEmpty() && Handler.pending() == 0, "destroy removes live window and expiry idempotently");
    }

    private static void sideSwitch() {
        Fixture f = new Fixture(); f.frame(1);
        MirrorFrameSink left = f.sink;
        left.submit(pixels(2), 1);
        f.sink=f.overlay.begin(2);
        check(f.windows.installed.isEmpty(), "begin new side removes old pixels before any new frame");
        left.submit(pixels(3), 1); Handler.runReady();
        check(f.windows.adds == 1, "queued old-side frame and old sink cannot display after side switch");
        f.sink.submit(pixels(4), 1); Handler.runReady();
        check(f.windows.adds == 1, "new sink cannot submit mismatching side");
        f.sink.submit(pixels(5), 2); Handler.runReady();
        check(f.windows.adds == 2 && f.draw().bitmap.pixels[0] == 5, "new right-side frame is the only accepted content");
        check(f.windows.lastLayout.gravity == (Gravity.RIGHT | Gravity.CENTER_VERTICAL), "right source uses right-center");
        check("右转影像".equals(f.draw().text), "direction label changes with source");
    }

    private static void denyPermissions() {
        Fixture f = new Fixture(); f.context.cameraGranted=false; f.frame(1);
        check(f.windows.adds == 0, "camera denial blocks display even with submitted pixels");
        Fixture g = new Fixture(); g.context.overlayGranted=false; g.frame(1);
        check(g.windows.adds == 0, "overlay denial blocks addView");
        Context absent = new Context(); absent.windows=null;
        MirrorOverlay overlay = new MirrorOverlay(absent); overlay.setEnabled(true);
        overlay.begin(1).submit(pixels(1), 1); Handler.runReady();
        check(Handler.pending() == 0, "missing window service fails closed without retry loop");
    }

    private static void permissionExceptions() {
        Fixture f = new Fixture(); f.context.permissionThrows=true; f.frame(1);
        check(f.windows.adds == 0, "permission service exception fails closed");
        Fixture g = new Fixture(); g.context.settingsThrows=true; g.frame(1);
        check(g.windows.adds == 0, "Settings service exception fails closed");
    }

    private static void revokeOnFrame() {
        Fixture f = new Fixture(); f.frame(1);
        f.context.overlayGranted=false; f.frame(2);
        check(f.windows.installed.isEmpty() && f.windows.lastView.visibility == View.GONE, "runtime overlay revocation removes pixels");
        Fixture g = new Fixture(); g.frame(1); g.context.cameraGranted=false; g.frame(2);
        check(g.windows.installed.isEmpty(), "runtime camera revocation removes pixels");
    }

    private static void revokeOnExpiry() {
        Fixture f = new Fixture(); f.frame(1);
        Handler.advanceTo(1700); f.frame(2);
        f.context.overlayGranted=false;
        Handler.advanceTo(1800);
        check(f.windows.installed.isEmpty(), "scheduled expiry rechecks permission even while last frame is fresh");
    }

    private static void expiresAt800() {
        Fixture f = new Fixture(); f.frame(1);
        Handler.advanceTo(1799);
        check(f.windows.installed.size() == 1, "frame visible through 799ms");
        Handler.advanceTo(1800);
        check(f.windows.installed.isEmpty() && f.windows.lastView.visibility == View.GONE, "800ms stream gap automatically removes window");
        check(Handler.pending() == 0, "expiry does not poll after removal");
    }

    private static void expiryTracksNewest() {
        Fixture f = new Fixture(); f.frame(1);
        Handler.advanceTo(1500); f.frame(2);
        Handler.advanceTo(1800);
        check(f.windows.installed.size() == 1, "old expiry cannot remove recently refreshed frame");
        Handler.advanceTo(2299);
        check(f.windows.installed.size() == 1, "newest frame remains for its own 799ms");
        Handler.advanceTo(2300);
        check(f.windows.installed.isEmpty(), "updated frame expires exactly 800ms after capture");
    }

    private static void delayedUiCannotDisplay() {
        Fixture f = new Fixture(); f.sink.submit(pixels(1), 1);
        Handler.advanceTo(1800);
        check(f.windows.adds == 0, "800ms delayed UI cannot add stale first frame");
        check(Handler.pending() == 0, "discarded first frame has no lingering expiry");
        f.frame(2);
        check(f.windows.adds == 1, "new fresh frame after UI backlog can show normally");
        f.sink.submit(pixels(3), 1); Handler.advanceTo(2700);
        check(f.windows.installed.isEmpty(), "late render cannot prolong visible window with stale pixels");
    }

    private static void delayedFreshFrameUsesCaptureDeadline() {
        Fixture f = new Fixture();
        f.sink.submit(pixels(1), 1);
        Handler.advanceTo(1799);
        check(f.windows.adds == 1, "799ms delayed frame is still fresh at render time");
        Handler.advanceTo(1800);
        check(f.windows.installed.isEmpty(), "expiry deadline is capture+800ms, not UI attachment+800ms");
    }

    private static void frameExpiresDuringBitmapCopy() {
        Fixture f = new Fixture();
        f.sink.submit(pixels(1), 1);
        Bitmap.copyClockAdvance=2;
        Handler.advanceTo(1799);
        check(f.windows.adds == 0 && f.windows.installed.isEmpty(), "frame expiring during bitmap copy cannot be attached");
        Bitmap.copyClockAdvance=0;
        f.frame(2);
        check(f.windows.adds == 1 && f.draw().bitmap.pixels[0] == 2, "discarding expired render does not prevent next fresh frame");
    }

    private static void bitmapAllocationFailure() {
        Fixture f = new Fixture(); Counter remote = new Counter();
        Bitmap.createThrows=true;
        MirrorFrameFanout fanout=new MirrorFrameFanout(f.sink, remote);
        fanout.submit(pixels(1), 1); Handler.runReady();
        check(f.windows.adds == 0 && remote.calls == 1, "FrameView construction failure cannot block remote or attach blank window");
        Bitmap.createThrows=false;
        Handler.advanceTo(3000); fanout.submit(pixels(2), 1); Handler.runReady();
        check(f.windows.adds == 1 && remote.calls == 2, "view can be reconstructed after failed bitmap allocation");
    }

    private static void layoutUpdate() {
        Fixture f = new Fixture(); f.frame(1);
        f.context.resources.metrics.widthPixels=800;
        f.frame(2);
        check(f.windows.adds == 1 && f.windows.updates == 1, "display metrics changes use update not second add");
        check(f.windows.lastLayout.width == 320 && f.windows.lastLayout.height == 180, "resized screen keeps image aspect ratio");
    }

    private static void addFailureIsolated() {
        Fixture f = new Fixture(); Counter remote = new Counter();
        f.windows.addThrows=true;
        MirrorFrameFanout fanout=new MirrorFrameFanout(f.sink, remote);
        fanout.submit(pixels(1), 1); Handler.runReady();
        check(remote.calls == 1 && f.windows.installed.isEmpty(), "addView failure leaves remote delivery intact");
        fanout.submit(pixels(2), 1); Handler.runReady();
        check(remote.calls == 2 && f.windows.adds == 1, "render failure uses backoff instead of hammering WindowManager");
    }

    private static void partialAddCleanup() {
        Fixture f = new Fixture(); f.windows.partialAddThrows=true; f.frame(1);
        check(f.windows.adds == 1 && f.windows.removes >= 1, "partial add attempts cleanup despite addView throwing");
        check(f.windows.installed.isEmpty() && f.windows.lastView.visibility == View.GONE, "partially attached view cannot leak after failure");
    }

    private static void updateFailureIsolated() {
        Fixture f = new Fixture(); f.frame(1);
        f.windows.updateThrows=true; f.context.resources.metrics.widthPixels=800;
        Counter remote = new Counter();
        new MirrorFrameFanout(f.sink, remote).submit(pixels(2), 1); Handler.runReady();
        check(remote.calls == 1 && f.windows.updates == 1, "update failure never interrupts remote sink");
        check(f.windows.installed.isEmpty(), "failed layout removes unsafe display");
    }

    private static void removeFailureIsolated() {
        Fixture f = new Fixture(); f.frame(1); f.windows.removeThrows=true;
        f.overlay.hide();
        check(f.windows.lastView.visibility == View.GONE, "remove failure still blanks all visible pixels");
        Counter remote = new Counter();
        new MirrorFrameFanout(f.sink, remote).submit(pixels(2), 1); Handler.runReady();
        check(remote.calls == 1 && f.windows.adds == 1, "failed removal cannot revive old sink or block remote");
        f.windows.removeThrows=false; f.overlay.hide();
        check(f.windows.installed.isEmpty(), "subsequent stop retries failed removal");
        Fixture g = new Fixture(); g.frame(1); g.windows.alreadyRemoved=true; g.overlay.hide();
        check(g.windows.installed.isEmpty(), "already-removed exception is benign");
    }

    private static void renderBackoffRecovery() {
        Fixture f = new Fixture(); f.windows.addThrows=true; f.frame(1);
        f.windows.addThrows=false;
        Handler.advanceTo(2999); f.frame(2);
        check(f.windows.adds == 1, "failed render does not retry inside 2s window");
        Handler.advanceTo(3000); f.frame(3);
        check(f.windows.adds == 2 && f.windows.installed.size() == 1, "fresh frame recovers after 2s without toggling setting");
        check(f.draw().bitmap.pixels[0] == 3, "recovery displays only new pixels not failed/backoff frame");
    }

    private static void bitmapFailureIsolated() {
        Fixture f = new Fixture(); Counter remote = new Counter();
        Bitmap.copyThrows=true;
        MirrorFrameFanout fanout = new MirrorFrameFanout(f.sink, remote);
        fanout.submit(pixels(1), 1); Handler.runReady();
        check(remote.calls == 1 && f.windows.adds == 0, "bitmap failure never attaches incomplete pixels or blocks remote");
        Bitmap.copyThrows=false;
        Handler.advanceTo(3000); fanout.submit(pixels(2), 1); Handler.runReady();
        check(remote.calls == 2 && f.windows.adds == 1, "bitmap failure recovers on fresh frame after backoff");
    }

    public static void main(String[] args) {
        run("fanout one-submit two consumers", MirrorOverlayTest::fanoutBoth);
        run("fanout isolated sink failures", MirrorOverlayTest::fanoutFailures);
        run("fanout close rejects late callbacks", MirrorOverlayTest::fanoutClose);
        run("mailbox copies reusable source array", MirrorOverlayTest::mailboxCopy);
        run("mailbox bounded newest-frame wins", MirrorOverlayTest::mailboxBounded);
        run("mailbox disabling clears pending pixels", MirrorOverlayTest::mailboxDisable);
        run("mailbox stop and side generations", MirrorOverlayTest::mailboxGenerations);
        run("mailbox input and 800ms boundary validation", MirrorOverlayTest::mailboxValidation);
        run("overlay no frames no window", MirrorOverlayTest::noFrames);
        run("overlay first real RGB565 frame and Canvas draw", MirrorOverlayTest::freshFrame);
        run("overlay bounded UI render and redraw without re-add", MirrorOverlayTest::newestFrame);
        run("overlay noninteractive flags and 80 percent alpha", MirrorOverlayTest::windowFlags);
        run("overlay 16:9 aspect and size limits", MirrorOverlayTest::layoutSizes);
        run("overlay hide rejects queued/late frames", MirrorOverlayTest::hideAndLateFrames);
        run("overlay disabled drops queued frames", MirrorOverlayTest::disabledClearsPending);
        run("overlay destroy cannot revive", MirrorOverlayTest::destroyedCannotRevive);
        run("overlay side switch rejects old sink and shows right direction", MirrorOverlayTest::sideSwitch);
        run("overlay camera/window permission denials", MirrorOverlayTest::denyPermissions);
        run("overlay permission query exceptions fail closed", MirrorOverlayTest::permissionExceptions);
        run("overlay runtime permission revocation on fresh frame", MirrorOverlayTest::revokeOnFrame);
        run("overlay expiry checks permission revocation", MirrorOverlayTest::revokeOnExpiry);
        run("overlay stream gap expires at 800ms", MirrorOverlayTest::expiresAt800);
        run("overlay expiry tracks newest timestamp", MirrorOverlayTest::expiryTracksNewest);
        run("overlay delayed UI cannot display stale frames", MirrorOverlayTest::delayedUiCannotDisplay);
        run("overlay expiry uses capture time despite delayed first render", MirrorOverlayTest::delayedFreshFrameUsesCaptureDeadline);
        run("overlay frame expiring during bitmap copy is never attached", MirrorOverlayTest::frameExpiresDuringBitmapCopy);
        run("overlay bitmap allocation failure recovers without blocking remote", MirrorOverlayTest::bitmapAllocationFailure);
        run("overlay screen resize updates attached window", MirrorOverlayTest::layoutUpdate);
        run("overlay add failure isolated from remote sink", MirrorOverlayTest::addFailureIsolated);
        run("overlay partially successful add is cleaned up", MirrorOverlayTest::partialAddCleanup);
        run("overlay update failure isolated from remote sink", MirrorOverlayTest::updateFailureIsolated);
        run("overlay remove failure blanks then retries", MirrorOverlayTest::removeFailureIsolated);
        run("overlay render failure 2s backoff and recovery", MirrorOverlayTest::renderBackoffRecovery);
        run("overlay bitmap failure isolated and recoverable", MirrorOverlayTest::bitmapFailureIsolated);
        System.out.println((failures == 0 ? "PASS" : "FAIL") + ": " + scenarios + " overlay/fanout/mailbox scenarios, "
                + checks + " checks, " + failures + " failures (real production Java + deterministic Android fakes)");
        if (failures != 0) throw new AssertionError(failures + " overlay host scenarios failed");
    }
}
