package com.xiaopeng.clusterassist;

import java.util.ArrayList;
import java.util.List;

public final class MirrorAvmSessionTest {
    private static final class Fake implements MirrorAvmSession.Control {
        int mode = 21;
        boolean reject, ignore;
        final List<Integer> sent = new ArrayList<>();
        public int current() { return mode; }
        public boolean set(int value) {
            sent.add(value);
            if (reject) return false;
            if (!ignore) mode = value;
            return true;
        }
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        Fake f = new Fake();
        MirrorAvmSession s = new MirrorAvmSession(f);
        f.ignore = true;
        s.prepare();
        check(!s.ready(), "normal return with unchanged mode is not ready");
        f.ignore = false;
        check(s.activate() && s.ready(), "reassert after camera opens");
        check(f.sent.size() == 2, "both pre-open and post-open commands");
        s.close();
        check(f.mode == 21 && !s.ready(), "restore known mode and revoke gate");
        int count = f.sent.size();
        s.close(); s.prepare();
        check(!s.activate() && f.sent.size() == count, "closed owner cannot write again");

        f = new Fake(); s = new MirrorAvmSession(f);
        s.prepare(); s.activate(); f.mode = 16; s.close();
        check(f.mode == 16, "never overwrite native AVM takeover");

        f = new Fake(); f.mode = -1; s = new MirrorAvmSession(f);
        check(!s.prepare() && !s.activate() && !s.ready(), "unknown snapshot rejects fish-eye");
        s.close();
        check(f.mode == -1 && f.sent.isEmpty(), "unknown previous mode must not be changed");

        f = new Fake(); f.mode = 0; s = new MirrorAvmSession(f);
        s.prepare(); s.activate(); s.close();
        check(f.mode == 0, "zero is a valid previous mode");

        f = new Fake(); f.reject = true; s = new MirrorAvmSession(f);
        s.prepare(); check(!s.activate() && !s.ready(), "rejected set must not permit crop");
        s.close(); check(f.sent.size() == 2, "rejected commands do not create restore ownership");
        for (int original : new int[] {0, 8, 16, 21}) {
            f = new Fake(); f.mode = original; s = new MirrorAvmSession(f);
            check(!s.activate() && !s.ready() && f.sent.isEmpty(), "no write before snapshot");
            check(s.prepare(), "known state can enter fish-eye");
            int afterPrepare = f.sent.size();
            check(s.prepare() && f.sent.size() == afterPrepare, "prepare is idempotent");
            check(s.activate() && s.ready(), "fish-eye active after snapshot");
            s.prepare(); // Must not replace the original snapshot with our own mode 8.
            s.close();
            check(f.mode == original, "normal turn end restores exact original " + original);
            count = f.sent.size();
            s.close();
            check(count == f.sent.size(), "restore occurs at most once");
        }
        f = new Fake(); f.mode = -1; s = new MirrorAvmSession(f);
        check(!s.prepare(), "unknown entry snapshot rejected");
        f.mode = 21;
        check(!s.prepare() && !s.activate() && f.sent.isEmpty(), "failed snapshot is not recaptured mid-owner");
        s.close();
        check(f.mode == 21 && f.sent.isEmpty(), "unknown owner does not touch later native state");

        System.out.println("MirrorAvmSessionTest: PASS (one-shot snapshot, exact restore 0/8/16/21, unknown-state refusal, takeover, dispatch)");
    }
}
