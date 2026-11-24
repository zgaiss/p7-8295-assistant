package com.xiaopeng.clusterassist;

/** One camera owner's AVM command/readback gate; no Android dependencies. */
final class MirrorAvmSession {
    interface Control {
        int current();
        boolean set(int mode);
    }

    private static final int QUAD = 8;
    private final Control control;
    private int previous = -1;
    private boolean touched;
    private boolean closed;

    MirrorAvmSession(Control control) { this.control = control; }

    // Match ViewAssist's pre-open selection, but do not mistake dispatch for activation.
    void prepare() {
        if (closed) return;
        previous = control.current();
        touched |= control.set(QUAD);
    }

    // Some HALs drop commands without an open client. Reassert after onOpened.
    boolean activate() {
        if (closed) return false;
        boolean sent = control.set(QUAD);
        touched |= sent;
        return sent;
    }

    boolean ready() { return !closed && control.current() == QUAD; }

    void close() {
        if (closed) return;
        closed = true;
        // Never guess a default, or overwrite a newer reverse/360 owner's selection.
        if (touched && previous >= 0 && previous != QUAD && control.current() == QUAD) {
            control.set(previous);
        }
    }
}
