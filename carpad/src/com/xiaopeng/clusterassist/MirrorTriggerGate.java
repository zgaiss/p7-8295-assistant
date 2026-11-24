package com.xiaopeng.clusterassist;

/** Pure turn-view policy. Unknown gear is not evidence that reverse is inactive. */
final class MirrorTriggerGate {
    static final int YIELD = -1;
    static final int STOP = 0;
    static final int LEFT = 1;
    static final int RIGHT = 2;
    static final int WAIT = 3;

    private MirrorTriggerGate() { }

    static int decide(boolean enabled, Boolean left, Boolean right,
                      Integer gear, int reverseGear) {
        // Both currently supported signal sources encode P/R/N/D in the range 1..4.
        // Never start or restore a turn view when reverse cannot be ruled out.
        if (gear == null || gear < 1 || gear > 4 || gear == reverseGear) return YIELD;
        if (!enabled) return STOP;
        if (left == null || right == null) return WAIT;
        if (left.booleanValue() == right.booleanValue()) return STOP; // Off or hazards: restore.
        return left ? LEFT : RIGHT;
    }
}
