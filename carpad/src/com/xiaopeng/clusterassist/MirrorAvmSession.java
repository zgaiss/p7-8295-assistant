package com.xiaopeng.clusterassist;

/** One camera owner's AVM command/readback gate; no Android dependencies. */
final class MirrorAvmSession {
    interface Control {
        int current();
        boolean set(int mode);
    }

    private static final int QUAD = 8;
    private final Control control;
    private final int knownPrevious;
    private final boolean trustSet;
    private int previous = -1;
    private boolean prepared;
    private boolean touched;
    private boolean closed;

    MirrorAvmSession(Control control) { this(control, -1, false); }

    /** knownPrevious comes from a shell dumpsys snapshot; trustSet allows a successful
     *  vendor write to stand in for the unreadable vendor parameters on this firmware. */
    MirrorAvmSession(Control control, int knownPrevious, boolean trustSet) {
        this.control = control;
        this.knownPrevious = knownPrevious;
        this.trustSet = trustSet;
    }

    // Snapshot exactly once before our first write. Never enter an un-restorable mode.
    // A known snapshot permits a post-open retry even if the pre-open command is rejected.
    boolean prepare() {
        if (closed) return false;
        if (prepared) return previous >= 0;
        prepared = true;
        previous = knownPrevious >= 0 ? knownPrevious : control.current();
        if (previous < 0) return false;
        touched |= control.set(QUAD);
        return true;
    }

    // Some HALs drop commands without an open client. Reassert after onOpened.
    boolean activate() {
        if (closed || !prepared || previous < 0) return false;
        boolean sent = control.set(QUAD);
        touched |= sent;
        return sent;
    }

    boolean ready() { return !closed && prepared && previous >= 0 && touched
            && (trustSet || control.current() == QUAD); }

    void close() {
        if (closed) return;
        closed = true;
        // Never guess a default, or overwrite a newer reverse/360 owner's selection.
        if (touched && previous >= 0 && previous != QUAD
                && (trustSet || control.current() == QUAD)) {
            control.set(previous);
        }
    }
}
