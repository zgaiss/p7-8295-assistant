package com.xiaopeng.clusterassist;

/** Bounded latest-frame mailbox. No Android dependencies; old capture generations cannot revive it. */
final class MirrorPreviewFrames {
    static final class Frame {
        final int side;
        final long at;
        Frame(int side, long at) { this.side = side; this.at = at; }
    }

    private final byte[] pixels;
    private long generation;
    private int side;
    private boolean enabled, dirty;
    private long at;

    MirrorPreviewFrames(int bytes) { pixels = new byte[bytes]; }

    synchronized long begin(int direction) {
        generation++;
        side = direction == 1 || direction == 2 ? direction : 0;
        dirty = false;
        return generation;
    }

    synchronized void setEnabled(boolean value) {
        enabled = value;
        if (!value) dirty = false;
    }

    synchronized void stop() {
        generation++;
        side = 0;
        dirty = false;
    }

    synchronized boolean offer(long token, byte[] source, int direction, long now) {
        if (!enabled || token != generation || side == 0 || side != direction
                || source == null || source.length != pixels.length) return false;
        System.arraycopy(source, 0, pixels, 0, pixels.length);
        at = now;
        dirty = true;
        return true;
    }

    synchronized Frame takeInto(byte[] destination, long now, long maxAge) {
        if (!enabled || !dirty || destination == null || destination.length != pixels.length) return null;
        dirty = false;
        if (now < at || now - at >= maxAge) return null;
        System.arraycopy(pixels, 0, destination, 0, pixels.length);
        return new Frame(side, at);
    }
}
