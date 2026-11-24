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
        s.prepare(); s.activate(); s.close();
        check(f.mode == 8, "unknown previous mode must not be guessed as 21");

        f = new Fake(); f.mode = 0; s = new MirrorAvmSession(f);
        s.prepare(); s.activate(); s.close();
        check(f.mode == 0, "zero is a valid previous mode");

        f = new Fake(); f.reject = true; s = new MirrorAvmSession(f);
        s.prepare(); check(!s.activate() && !s.ready(), "rejected set must not permit crop");
        s.close(); check(f.sent.size() == 2, "rejected commands do not create restore ownership");
        System.out.println("MirrorAvmSessionTest: PASS (dispatch, post-open retry, takeover, close, unknown/zero mode)");
    }
}
