package com.xiaopeng.clusterassist;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraDevice;
import android.media.ImageReader;
import android.os.Handler;

/** Executes production lifecycle code against controllable asynchronous fake Android APIs. */
public final class MirrorCameraLifecycleTest {
    private static int cases;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static MirrorSource start(CameraManager manager, MirrorPush push, boolean quad) {
        MirrorSource source = new MirrorSource(new Context(manager), 0, 1, quad, push);
        source.start();
        Handler.ready();
        return source;
    }

    private static void finish(MirrorSource source, CameraManager manager, int request) {
        source.stop();
        Handler.ready();
        manager.requests.get(request).closed();
        Handler.ready();
        Handler.advance(4000);
        check(Handler.pending() == 0, "test leaked delayed owner tasks");
    }

    private static void stopBeforeOpenTask() {
        CameraManager m = new CameraManager();
        MirrorSource s = new MirrorSource(new Context(m), 0, 1, true, new MirrorPush());
        s.start();
        s.stop();
        Handler.ready();
        check(m.opens == 0, "stopped-before-open source opened a camera");
        check(m.writes.isEmpty(), "stopped-before-open source wrote AVM mode");
        cases++;
    }

    private static void lateOpened() {
        CameraManager m = new CameraManager();
        MirrorPush push = new MirrorPush();
        MirrorSource s = start(m, push, true);
        check(m.opens == 1, "start should dispatch exactly one open request");
        int writes = m.writes.size();
        s.stop();
        Handler.ready();
        m.requests.get(0).opened();
        Handler.ready();
        CameraDevice d = m.requests.get(0).device;
        check(d.closes > 0, "late onOpened must close its camera");
        check(d.sessionCreates == 0, "late onOpened created a capture session");
        check(m.writes.size() == writes, "late onOpened wrote AVM mode");
        check(push.submits == 0, "late onOpened submitted a frame");
        finish(s, m, 0);
        cases++;
    }

    private static void lateConfigured() {
        CameraManager m = new CameraManager();
        MirrorSource s = start(m, new MirrorPush(), false);
        m.requests.get(0).opened();
        Handler.ready();
        CameraDevice d = m.requests.get(0).device;
        check(d.sessionCreates == 1, "non-quad session should configure after open");
        s.stop();
        Handler.ready();
        d.configured();
        Handler.ready();
        check(d.session.repeats == 0, "late onConfigured started repeating capture");
        check(d.session.closes > 0, "late onConfigured did not close stale session");
        finish(s, m, 0);
        cases++;
    }

    private static void closeBarrier() {
        CameraManager m = new CameraManager();
        MirrorSource first = start(m, new MirrorPush(), false);
        first.stop();
        Handler.ready();
        MirrorSource second = start(m, new MirrorPush(), false);
        Handler.advance(250);
        check(m.opens == 1, "new owner opened before old pending onOpened completed");
        m.requests.get(0).opened();
        Handler.ready();
        check(m.requests.get(0).device.closes > 0, "stopped open owner not closing");
        Handler.advance(250);
        check(m.opens == 1, "new owner opened before old onClosed barrier");
        m.requests.get(0).closed();
        Handler.ready();
        Handler.advance(150);
        check(m.opens == 2, "new owner did not open after old onClosed barrier");
        second.stop();
        Handler.ready();
        m.requests.get(1).opened();
        Handler.ready();
        finish(second, m, 1);
        cases++;
    }

    private static CameraDevice activateQuad(CameraManager m, MirrorSource s) {
        check(m.mode == 3, "pre-open mode write should be ignored by fake HAL");
        check(m.writes.size() == 1 && m.writes.get(0) == 8, "missing pre-open quad selection");
        m.requests.get(0).opened();
        Handler.ready();
        check(m.writes.size() >= 2 && m.writes.get(1) == 8, "onOpened did not reassert quad");
        check(m.mode == 8, "post-open AVM selection not activated");
        CameraDevice d = m.requests.get(0).device;
        check(d.sessionCreates == 1, "verified mode did not allow session creation");
        Handler.advance(250); // Production drains old-composition frames before submit, not session creation.
        d.configured();
        Handler.ready();
        check(d.session.repeats == 1, "valid configured quad session did not start");
        return d;
    }

    private static void reassertAfterOpen() {
        CameraManager m = new CameraManager();
        MirrorSource s = start(m, new MirrorPush(), true);
        activateQuad(m, s);
        s.stop();
        Handler.ready();
        check(m.mode == 3, "normal stop should restore captured previous mode");
        finish(s, m, 0);
        cases++;
    }

    private static void noReadbackNoSession() {
        CameraManager m = new CameraManager();
        m.ignoreWrites = true;
        MirrorSource s = start(m, new MirrorPush(), true);
        m.requests.get(0).opened();
        Handler.ready();
        Handler.advance(1500);
        CameraDevice d = m.requests.get(0).device;
        check(d.sessionCreates == 0, "unconfirmed mode created a capture session");
        check(d.closes > 0, "mode-readback timeout did not close camera");
        check(s.lastError().contains("确认"), "mode-readback timeout diagnostic missing");
        finish(s, m, 0);
        cases++;
    }

    private static void reverseYieldNoRestore() {
        CameraManager m = new CameraManager();
        MirrorSource s = start(m, new MirrorPush(), true);
        activateQuad(m, s);
        int writes = m.writes.size();
        s.stop(false);
        Handler.ready();
        check(m.writes.size() == writes, "reverse-yield stop restored AVM mode");
        check(m.mode == 8, "reverse-yield stop overwrote latest AVM owner mode");
        finish(s, m, 0);
        cases++;
    }

    private static void oldFrameNeverReadOrSubmitted() {
        CameraManager m = new CameraManager();
        MirrorPush push = new MirrorPush();
        MirrorSource s = start(m, push, false);
        m.requests.get(0).opened();
        Handler.ready();
        CameraDevice d = m.requests.get(0).device;
        d.configured();
        Handler.ready();
        ImageReader old = ImageReader.latest;
        old.frame();
        Handler.ready();
        check(push.submits == 1, "fake active image path must actually submit a frame");
        int reads = ImageReader.reads;
        int submits = push.submits;
        old.frame(); // Queue an old callback, then stop before dispatching it.
        s.stop();
        Handler.ready();
        old.frame(); // Also inject a callback after close has been dispatched.
        Handler.ready();
        check(ImageReader.reads == reads, "stopped source acquired a stale image");
        check(push.submits == submits, "stopped source submitted a stale frame");
        finish(s, m, 0);
        cases++;
    }

    private static void driftClosesWithoutRestore() {
        CameraManager m = new CameraManager();
        MirrorPush push = new MirrorPush();
        MirrorSource s = start(m, push, true);
        CameraDevice d = activateQuad(m, s);
        m.mode = 6; // Another owner (reverse/360) has taken control.
        int writes = m.writes.size();
        Handler.advance(250);
        ImageReader.latest.frame();
        Handler.ready();
        check(d.closes > 0, "mode drift did not close capture");
        check(push.submits == 0, "mode drift submitted a wrong-layout frame");
        check(m.writes.size() == writes && m.mode == 6, "mode drift overwrote newer AVM owner");
        check(s.lastError().contains("接管"), "mode drift diagnostic missing");
        finish(s, m, 0);
        cases++;
    }

    private static void queuedNormalStopThenNativeYield() {
        CameraManager m = new CameraManager();
        MirrorSource s = start(m, new MirrorPush(), true);
        activateQuad(m, s);
        int writes = m.writes.size();
        // PushService may have already dropped its source reference. A later reverse
        // signal must revoke that old source's restoration before its owner task runs.
        s.stop();
        MirrorSource.yieldToNative();
        Handler.ready();
        check(m.writes.size() == writes, "queued normal stop restored after native ownership yield");
        check(m.mode == 8, "queued old restore overwrote native owner's mode");
        finish(s, m, 0);
        cases++;
    }

    private static void openTimeoutRetainsLeaseUntilLateClose() {
        CameraManager m = new CameraManager();
        MirrorPush push = new MirrorPush();
        MirrorSource first = start(m, push, false);
        int readersClosed = ImageReader.closed;
        Handler.advance(3100); // Neither onOpened nor onError arrives.
        check(first.isStopped(), "missing onOpened did not fail source at bounded timeout");
        check(!first.lastError().isEmpty(), "missing onOpened has no failure diagnostic");
        check(ImageReader.closed == readersClosed, "open timeout released surface before HAL close");
        MirrorSource second = start(m, new MirrorPush(), false);
        Handler.advance(250);
        check(m.opens == 1, "open timeout freed lease for overlapping new open");
        m.requests.get(0).opened();
        Handler.ready();
        CameraDevice old = m.requests.get(0).device;
        check(old.closes > 0, "late onOpened after timeout not closed");
        check(old.sessionCreates == 0, "late onOpened after timeout created session");
        check(push.submits == 0, "timed-out open submitted frame");
        check(ImageReader.closed == readersClosed, "late open released surface before matching onClosed");
        Handler.advance(250);
        check(m.opens == 1, "late open alone released lease without onClosed");
        m.requests.get(0).closed();
        Handler.ready();
        check(ImageReader.closed == readersClosed + 1, "matching old close did not release exactly one reader");
        Handler.advance(150);
        check(m.opens == 2, "waiting source did not open after timed-out owner actually closed");
        second.stop();
        Handler.ready();
        m.requests.get(1).opened();
        Handler.ready();
        finish(second, m, 1);
        cases++;
    }

    private static void configureTimeoutRetainsLeaseAndRejectsLateSession() {
        CameraManager m = new CameraManager();
        MirrorPush push = new MirrorPush();
        MirrorSource first = start(m, push, false);
        m.requests.get(0).opened();
        Handler.ready();
        CameraDevice old = m.requests.get(0).device;
        check(old.sessionCreates == 1, "test did not enter configuring state");
        int readersClosed = ImageReader.closed;
        Handler.advance(3100); // Neither onConfigured nor onConfigureFailed arrives.
        check(first.isStopped(), "missing onConfigured did not fail source at bounded timeout");
        check(!first.lastError().isEmpty(), "missing onConfigured has no failure diagnostic");
        check(old.closes > 0, "configure timeout did not request device close");
        check(ImageReader.closed == readersClosed, "configure timeout released live reader before onClosed");
        MirrorSource second = start(m, new MirrorPush(), false);
        Handler.advance(250);
        check(m.opens == 1, "configure timeout freed lease without device close");
        old.configured();
        Handler.ready();
        check(old.session.repeats == 0, "late configured session started capture after watchdog failure");
        check(old.session.closes > 0, "late configured session did not close itself");
        check(push.submits == 0, "timed-out configure submitted frame");
        check(ImageReader.closed == readersClosed, "late configured callback released reader before device close");
        m.requests.get(0).closed();
        Handler.ready();
        check(ImageReader.closed == readersClosed + 1, "configure owner's onClosed did not release exactly one reader");
        Handler.advance(150);
        check(m.opens == 2, "waiting source did not open after configuring owner actually closed");
        second.stop();
        Handler.ready();
        m.requests.get(1).opened();
        Handler.ready();
        finish(second, m, 1);
        cases++;
    }

    public static void main(String[] args) {
        stopBeforeOpenTask();
        lateOpened();
        lateConfigured();
        closeBarrier();
        reassertAfterOpen();
        noReadbackNoSession();
        reverseYieldNoRestore();
        oldFrameNeverReadOrSubmitted();
        driftClosesWithoutRestore();
        queuedNormalStopThenNativeYield();
        openTimeoutRetainsLeaseUntilLateClose();
        configureTimeoutRetainsLeaseAndRejectsLateSession();
        System.out.println("PASS: " + cases + " real MirrorSource callback/owner scenarios (fake Android; not HAL validation)");
    }
}
