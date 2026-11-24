package com.xiaopeng.clusterassist;

/** Host-only policy checks. Camera/HAL behavior is tested separately, not by these fakes. */
public final class MirrorTriggerGateTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static void decision(int expected, boolean enabled, Boolean left, Boolean right,
                                 Integer gear, int reverseGear, String why) {
        check(MirrorTriggerGate.decide(enabled, left, right, gear, reverseGear) == expected, why);
    }
    private static MirrorStepHarness harness(Integer gear, Boolean left, Boolean right) {
        Prefs.saved = new Prefs();
        XuiLight.value = gear == null ? -1 : gear;
        XuiLight.left = left; XuiLight.right = right;
        XuiLight.reads = 0; XuiLight.lampReads = 0; CarBridge.lampReads = 0;
        XuiLight.ready = true;
        CarBridge.value = gear; CarBridge.left = left; CarBridge.right = right;
        MirrorSource.yields = 0;
        return new MirrorStepHarness();
    }
    public static void main(String[] args) {
        for (int reverseGear : new int[] {2, 3}) {
            for (Boolean left : new Boolean[] {null, false, true}) {
                for (Boolean right : new Boolean[] {null, false, true}) {
                    for (boolean enabled : new boolean[] {false, true}) {
                        decision(MirrorTriggerGate.YIELD, enabled, left, right, reverseGear,
                                reverseGear, "reverse overrides lamps and enable state");
                        decision(MirrorTriggerGate.YIELD, enabled, left, right, null,
                                reverseGear, "unknown gear cannot rule out reverse");
                    }
                }
            }
            for (int unknown : new int[] {-1, 0, 5, 999}) {
                decision(MirrorTriggerGate.YIELD, true, true, false, unknown, reverseGear,
                        "unsupported gear must not trigger turn view");
            }
        }
        decision(MirrorTriggerGate.LEFT, true, true, false, 4, 2, "D plus left");
        decision(MirrorTriggerGate.RIGHT, true, false, true, 4, 2, "D plus right");
        decision(MirrorTriggerGate.STOP, true, false, false, 4, 2, "lamps off: restore");
        decision(MirrorTriggerGate.STOP, true, true, true, 4, 2, "hazards: restore");
        decision(MirrorTriggerGate.STOP, false, null, null, 4, 2, "disabled non-R: restore");
        decision(MirrorTriggerGate.WAIT, true, null, true, 4, 2, "unknown lamp: wait");

        MirrorStepHarness h = harness(2, true, false);
        h.step();
        check(h.starts == 0 && h.stops == 1 && !h.restore && MirrorSource.yields == 1,
                "production mirrorStep: reverse stops without restore, never starts");
        check(XuiLight.lampReads == 0, "reverse yields before reading lamps");
        h = harness(2, null, null); h.step();
        check(h.starts == 0 && h.stops == 1 && !h.restore,
                "production mirrorStep: reverse overrides unknown lamps");
        h = harness(null, true, false); h.step();
        check(h.starts == 0 && h.stops == 1 && !h.restore,
                "production mirrorStep: unknown gear yields without restore");
        check(XuiLight.lampReads == 0, "unknown gear yields before reading lamps");
        h = harness(4, true, false); h.step();
        check(h.starts == 1 && h.startedSide == 1 && XuiLight.reads == 1,
                "production mirrorStep: one gear snapshot, left starts");
        h = harness(4, false, true); h.step();
        check(h.starts == 1 && h.startedSide == 2, "production mirrorStep: right starts");
        h = harness(4, false, false); h.step();
        check(h.starts == 0 && h.stops == 1 && h.restore && !h.closeSocket,
                "production mirrorStep: ordinary lamp-off restores, socket stays");
        h = harness(4, true, true); h.step();
        check(h.stops == 1 && h.restore, "production mirrorStep: hazards end normally");
        h = harness(4, true, false); Prefs.saved.mirror = false; h.step();
        check(h.starts == 0 && h.stops == 1 && h.restore && h.closeSocket,
                "production mirrorStep: disabled non-R restores and closes socket");
        check(XuiLight.lampReads == 0, "disabled does not perform unnecessary lamp reads");
        // Service has no current source now, but an old owner's restore can still be queued.
        XuiLight.value = 2; h.step();
        check(h.starts == 0 && h.stops == 2 && !h.restore && MirrorSource.yields == 1,
                "production mirrorStep: disabled still checks reverse to cancel old restore");
        h = harness(2, true, false); Prefs.saved.master = false; h.step();
        check(h.starts == 0 && !h.restore && h.closeSocket,
                "production mirrorStep: master-off cannot bypass reverse priority");
        h = harness(3, true, false); XuiLight.ready = false; h.step();
        check(h.starts == 0 && !h.restore, "production mirrorStep: CarProperty R is 3, not XUI 2");
        check(CarBridge.lampReads == 0, "CarProperty reverse skips potentially blocking lamp IPC");
        h = harness(2, true, false); XuiLight.ready = false; h.step();
        check(h.starts == 1, "production mirrorStep: CarProperty 2 is not reverse");
        h = harness(4, null, false); h.step();
        check(h.starts == 0 && h.stops == 0, "production mirrorStep: unknown lamps wait");
        System.out.println("PASS: " + checks + " trigger-policy and extracted production mirrorStep checks");
    }
}
