package com.xiaopeng.clusterassist;

/** A non-blocking consumer of one reusable RGB565 frame. Copy pixels before returning. */
public interface MirrorFrameSink {
    void submit(byte[] pixels, int side);
}
