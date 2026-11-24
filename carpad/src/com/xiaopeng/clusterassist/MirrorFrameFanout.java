package com.xiaopeng.clusterassist;

/** One capture feeds independent local/network sinks; closing invalidates late source callbacks. */
final class MirrorFrameFanout implements MirrorFrameSink {
    private final MirrorFrameSink local;
    private final MirrorFrameSink remote;
    private volatile boolean closed;

    MirrorFrameFanout(MirrorFrameSink local, MirrorFrameSink remote) {
        this.local = local;
        this.remote = remote;
    }

    @Override public void submit(byte[] pixels, int side) {
        if (closed) return;
        deliver(local, pixels, side);
        if (!closed) deliver(remote, pixels, side);
    }

    private void deliver(MirrorFrameSink sink, byte[] pixels, int side) {
        if (sink == null || closed) return;
        try {
            sink.submit(pixels, side);
        } catch (RuntimeException ignored) {
            // A failed optional display must not prevent the other consumer receiving this frame.
        }
    }

    void close() { closed = true; }
}
