package com.xiaopeng.clusterassist;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reflection/payload unit tests only: no emulation of Android HAL or actual camera pixels. */
public final class AvmModeHostTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual),
                message + ": expected=" + expected + " actual=" + actual);
    }

    public static class Params {
        public final Map<String, String> mMap = new LinkedHashMap<>();
        public Params() { mMap.put("historic-field", "must-not-resend"); }
        public void setAvmDisplayMode(int mode) { mMap.put("display-mode", String.valueOf(mode)); }
        public void set(String key, int value) { mMap.put(key, String.valueOf(value)); }
        public int getAvmDisplayMode() { return -1; }
        public String flatten() { throw new IllegalStateException("broken flatten"); }
    }
    public static class Manager {
        final Object live;
        Manager(Object live) { this.live = live; }
        public Object getCmdControlParameters() { return live; }
    }
    public static class IntRaw extends Manager {
        int id = -1;
        String payload;
        IntRaw() { super(new Params()); }
        public void setCmdControlParameters(int id, String payload) { this.id = id; this.payload = payload; }
    }
    public static class StringRaw {
        String id, payload;
        public void setCmdControlParameters(String id, String payload) { this.id = id; this.payload = payload; }
        public void earlysetCmdControlParameters(String id, String payload) { this.id = id; this.payload = payload; }
    }
    public static class SingleRaw {
        String payload;
        public int setCmdControlParameters(String payload) { this.payload = payload; return 0; }
        public int earlySetCmdControlParameters(String payload) { this.payload = payload; return 1; }
    }
    public static class Priority extends IntRaw {
        boolean unexpected;
        public void setCmdControlParameters(String id, String payload) { unexpected = true; }
        public void setCmdControlParameters(String payload) { unexpected = true; }
        public void setCmdControlParameters(Params params) { unexpected = true; }
        @Override public Object getCmdControlParameters() { throw new AssertionError("must not read params on raw path"); }
    }
    public static class RejectedThenAccepted {
        int count;
        public boolean setCmdControlParameters(int id, String raw) { count++; return false; }
        public int setCmdControlParameters(String id, String raw) { count++; return -2; }
        public void setCmdControlParameters(String raw) { count++; }
    }
    public static class Reject {
        public boolean setCmdControlParameters(int id, String raw) { return false; }
        public int setCmdControlParameters(String id, String raw) { return -7; }
        public void setCmdControlParameters(String raw) { throw new IllegalStateException("vendor refused"); }
    }
    public static class ObjectOnly extends Manager {
        Params received;
        ObjectOnly(Params live) { super(live); }
        public void setCmdControlParameters(Params params) { received = params; }
    }
    public static class IntObject extends Manager {
        int id = -1;
        Params received;
        IntObject() { super(new Params()); }
        public boolean setCmdControlParameters(int id, Params params) { this.id = id; received = params; return true; }
        public void earlysetCmdControlParameters(int id, Params params) { this.id = id; received = params; }
    }
    public static class StringObject extends Manager {
        String id;
        Params received;
        StringObject() { super(new Params()); }
        public void setCmdControlParameters(String id, Params params) { this.id = id; received = params; }
        public void earlysetCmdControlParameters(String id, Params params) { this.id = id; received = params; }
    }
    public static class EarlyInt extends IntRaw {
        public void earlysetCmdControlParameters(int id, String raw) { this.id = id; payload = raw; }
    }
    public static class EarlyObject extends ObjectOnly {
        EarlyObject() { super(new Params()); }
        public void earlysetCmdControlParameters(Params params) { received = params; }
    }
    public static class GetterParams extends Params {
        @Override public int getAvmDisplayMode() { return 0; }
        @Override public String flatten() { throw new AssertionError("getter should win"); }
    }
    public static class FlattenParams extends Params {
        final String value;
        FlattenParams(String value) { this.value = value; }
        @Override public int getAvmDisplayMode() { throw new IllegalStateException("getter unavailable"); }
        @Override public String flatten() { return value; }
    }
    public static class NoConstructor {
        private NoConstructor(int unused) { }
        public void setAvmDisplayMode(int mode) { }
    }
    public static class NoConstructorManager extends Manager {
        boolean called;
        NoConstructorManager() { super(new NoConstructor(0)); }
        public void setCmdControlParameters(NoConstructor params) { called = true; }
    }
    public static class Unverifiable {
        public Unverifiable() { }
        public void setAvmDisplayMode(int mode) { }
    }
    public static class UnverifiableManager extends Manager {
        boolean called;
        UnverifiableManager() { super(new Unverifiable()); }
        public void setCmdControlParameters(Unverifiable params) { called = true; }
    }
    public static class Dirty extends Params {
        @Override public void setAvmDisplayMode(int mode) {
            super.setAvmDisplayMode(mode);
            mMap.put("overlay-workst", "1");
        }
    }
    public static class DirtyManager extends Manager {
        boolean called;
        DirtyManager() { super(new Dirty()); }
        public void setCmdControlParameters(Dirty params) { called = true; }
    }
    public static class Shared extends Params {
        static final Map<String, String> shared = new LinkedHashMap<>();
        public final Map<String, String> mMap = shared;
        public Shared() { shared.put("preserve", "original"); }
    }
    public static class SharedManager extends Manager {
        boolean called;
        SharedManager() { super(new Shared()); }
        public void setCmdControlParameters(Shared params) { called = true; }
    }
    public static class FlatOnly {
        int mode;
        public FlatOnly() { }
        public void setAvmDisplayMode(int mode) { this.mode = mode; }
        public String flatten() { return "display-mode=" + mode + ";"; }
    }
    public static class FlatOnlyManager extends Manager {
        FlatOnly received;
        FlatOnlyManager() { super(new FlatOnly()); }
        public void setCmdControlParameters(FlatOnly params) { received = params; }
    }
    public static class PrivatePut {
        private final Map<String, String> mMap = new LinkedHashMap<>();
        public PrivatePut() { mMap.put("old", "must-not-send"); }
        private void put(String key, String value) { mMap.put(key, value); }
    }
    public static class PrivatePutManager extends Manager {
        PrivatePut received;
        PrivatePutManager() { super(new PrivatePut()); }
        public void setCmdControlParameters(PrivatePut params) { received = params; }
    }

    public static class RejectObject extends Manager {
        int calls;
        RejectObject() { super(new Params()); }
        public boolean setCmdControlParameters(Params params) { calls++; return false; }
        public int setCmdControlParameters(int id, Params params) { calls++; return -3; }
        public void setCmdControlParameters(String id, Params params) {
            calls++;
            throw new IllegalArgumentException("object refused");
        }
    }
    public static class InnerManager {
        final InnerParams live = new InnerParams();
        InnerParams received;
        public class InnerParams {
            private final Map<String, String> mMap = new LinkedHashMap<>();
            public InnerParams() { mMap.put("default", "do-not-send"); }
            public void setAvmDisplayMode(int mode) { mMap.put("display-mode", String.valueOf(mode)); }
        }
        public InnerParams getCmdControlParameters() { return live; }
        public void setCmdControlParameters(InnerParams params) { received = params; }
    }

    public static void main(String[] args) {
        IntRaw raw = new IntRaw();
        check(AvmMode.set(raw, 8), "raw int send with flatten throwing");
        equal(0, raw.id, "int camera id");
        equal("display-mode=8", raw.payload, "raw has no historic fields");
        check(((Params) raw.live).mMap.containsKey("historic-field"), "readback not modified");
        StringRaw strings = new StringRaw();
        check(AvmMode.set(strings, 16), "String cameraId overload without get/flatten");
        equal("0", strings.id, "String camera id");
        equal("display-mode=16", strings.payload, "String raw");
        check(AvmMode.earlySet(strings, 8), "early String cameraId");
        equal("display-mode=8", strings.payload, "early raw");
        SingleRaw single = new SingleRaw();
        check(AvmMode.set(single, 21), "single raw numeric zero accepted");
        equal("display-mode=21", single.payload, "single payload");
        check(AvmMode.earlySet(single, 8), "early single raw positive accepted");
        Priority priority = new Priority();
        check(AvmMode.set(priority, 8), "raw int preferred");
        check(!priority.unexpected, "does not call lower priority overload");
        RejectedThenAccepted fallback = new RejectedThenAccepted();
        check(AvmMode.set(fallback, 8), "fallback after false/negative");
        equal(3, fallback.count, "all raw overloads tried in order");
        check(!AvmMode.set(new Reject(), 8), "rejected and throwing commands fail");
        check(AvmMode.lastError().contains("returned false"), "false return diagnostic");
        check(AvmMode.lastError().contains("returned -7"), "negative return diagnostic");
        check(AvmMode.lastError().contains("vendor refused"), "exception unwrapped");
        check(AvmMode.lastError().contains("java.lang.String"), "full method signature diagnostic");
        check(!AvmMode.earlySet(raw, 8), "missing early must not fall back to normal implicitly");
        check(AvmMode.lastError().contains("no compatible overload"), "unsupported diagnostic");

        Params live = new Params();
        live.mMap.put("display-mode", "21");
        ObjectOnly object = new ObjectOnly(live);
        check(AvmMode.set(object, 8), "object-only fallback tolerates flatten exception");
        check(object.received != live, "fresh builder");
        equal(1, object.received.mMap.size(), "fresh map only requested field");
        equal("8", object.received.mMap.get("display-mode"), "object value");
        equal("21", live.mMap.get("display-mode"), "live value retained");
        check(live.mMap.containsKey("historic-field"), "live history retained locally");
        IntObject intObject = new IntObject();
        check(AvmMode.set(intObject, 8), "int object overload");
        equal(0, intObject.id, "int object camera id");
        check(AvmMode.earlySet(intObject, 16), "early int object overload");
        equal("16", intObject.received.mMap.get("display-mode"), "early int object value");
        StringObject stringObject = new StringObject();
        check(AvmMode.set(stringObject, 8), "string object overload");
        equal("0", stringObject.id, "string object id");
        check(AvmMode.earlySet(stringObject, 21), "early string object");
        equal("21", stringObject.received.mMap.get("display-mode"), "early string object value");
        EarlyInt earlyInt = new EarlyInt();
        check(AvmMode.earlySet(earlyInt, 8), "early int raw");
        equal("display-mode=8", earlyInt.payload, "early int raw value");
        EarlyObject earlyObject = new EarlyObject();
        check(AvmMode.earlySet(earlyObject, 8), "early object");
        equal(1, earlyObject.received.mMap.size(), "early object scrubbed");
        NoConstructorManager noConstructor = new NoConstructorManager();
        check(!AvmMode.set(noConstructor, 8) && !noConstructor.called, "no isolated constructor fails closed");
        UnverifiableManager unverifiable = new UnverifiableManager();
        check(!AvmMode.set(unverifiable, 8) && !unverifiable.called, "unverifiable object fails closed");
        DirtyManager dirty = new DirtyManager();
        check(!AvmMode.set(dirty, 8) && !dirty.called, "writer adding unrequested field fails closed");
        SharedManager shared = new SharedManager();
        check(!AvmMode.set(shared, 8) && !shared.called, "shared map fails closed");
        equal("original", Shared.shared.get("preserve"), "shared live history not cleared");
        FlatOnlyManager flatOnly = new FlatOnlyManager();
        check(AvmMode.set(flatOnly, 8), "flatten verified object without map");
        equal(8, flatOnly.received.mode, "flatten verified value");
        PrivatePutManager privatePut = new PrivatePutManager();
        check(AvmMode.set(privatePut, 8), "private put writer");
        equal(1, privatePut.received.mMap.size(), "private put isolated");
        RejectObject rejectObject = new RejectObject();
        check(!AvmMode.set(rejectObject, 8), "object false/negative/exception rejected");
        equal(3, rejectObject.calls, "object priority attempts all compatible overloads");
        check(AvmMode.lastError().contains("object refused"), "object cause retained");
        InnerManager inner = new InnerManager();
        check(AvmMode.set(inner, 8), "framework nonstatic inner builder constructor");
        check(inner.received != inner.live, "inner builder independently constructed");
        equal(1, inner.received.mMap.size(), "inner builder isolated payload");
        equal("do-not-send", inner.live.mMap.get("default"), "inner live state not cleared");

        equal(0, AvmMode.current(new Manager(new GetterParams())), "getter level including valid zero");
        equal(16, AvmMode.current(new Manager(new FlattenParams("other=1;display-mode=16;"))), "flatten level");
        equal(8, AvmMode.current(new Manager(object.received)), "map level with throwing flatten");
        equal(21, AvmMode.current(new Manager(live)), "writes do not fake readback success");
        equal(-1, AvmMode.current(new Manager(new Params())), "missing field unknown");
        equal(-1, AvmMode.current(new Manager(new FlattenParams("channel0-display-mode=8;"))), "no substring match");
        equal(-1, AvmMode.current(new Manager(new FlattenParams("display-mode=-2;"))), "negative unknown normalized");
        equal(-1, AvmMode.current(new Manager(new FlattenParams("display-mode=999999999999;"))), "overflow unknown");
        equal(-1, AvmMode.current(null), "null readback unknown");
        equal(-1, AvmMode.current(new Manager(null)), "null params unknown");
        check(!AvmMode.set(null, 8), "null manager send fails");
        check(AvmMode.setChannel(raw, 0, 4), "channel command compatible");
        equal("channel0-display-mode=4", raw.payload, "channel raw only requested key");
        check(!AvmMode.setChannel(raw, 2, 8), "reject invalid channel");
        check(AvmMode.setWorkState(raw, 1), "work state compatible");
        equal("work-status=1", raw.payload, "work state raw only requested key");
        check(AvmMode.lastError() == null, "successful dispatch clears prior error");
        check(AvmMode.readBack(new Manager(new FlattenParams("display-mode=8;channel0-display-mode=4;channel1-display-mode=16;work-status=1;")))
                .equals("display=8 ch0=4 ch1=16 work=1"), "readBack all fields flatten level");
        check(AvmMode.readBack(new Manager(new GetterParams())).startsWith("display=0 "),
                "readBack getter level includes zero");
        Params mapRead = new Params();
        mapRead.mMap.put("display-mode", "8");
        mapRead.mMap.put("channel0-display-mode", "4");
        mapRead.mMap.put("channel1-display-mode", "16");
        mapRead.mMap.put("work-status", "1");
        equal("display=8 ch0=4 ch1=16 work=1", AvmMode.readBack(new Manager(mapRead)),
                "readBack all fields map level with throwing flatten");
        check(AvmMode.dump(raw).contains("historic-field"), "dump compatibility");
        check(AvmMode.dumpMethods(raw).contains("setCmdControlParameters(int,java.lang.String)"), "method dump signature");
        System.out.println("AvmModeHostTest: " + checks + " checks passed");
    }
}
