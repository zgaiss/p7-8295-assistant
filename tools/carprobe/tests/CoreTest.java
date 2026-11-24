package cp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public final class CoreTest {
    private static int assertions;
    static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    static final class Clock implements Core.Clock {
        long ns = 10000000000L;
        public long nanos() { return ns; }
        public long wallMillis() { return 1800000000000L; }
    }
    static final class Source implements Core.Source {
        int[] value = {1, 2}; int status, reads, begins; boolean fail, connectFail;
        long ts = 9000000000L, conn;
        public void beginCycle() throws Exception { begins++; if (connectFail) throw new Exception("sensitive"); if (conn == 0) conn++; }
        public Core.Reading read(int id) throws Exception {
            reads++; if (fail) throw new Exception("sensitive"); return new Core.Reading(value, ts, status);
        }
        public long connections() { return conn; }
        public void close() { }
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> prop(Core c) {
        return (Map<String, Object>) ((Map<String, Object>) c.snapshot(null).get("props")).get("speed");
    }
    static double number(Map<String, Object> m, String k) { return ((Number) m.get(k)).doubleValue(); }
    public static void main(String[] args) throws Exception {
        Clock clock = new Clock(); Source source = new Source();
        Map<String, Integer> ids = new LinkedHashMap<String, Integer>(); ids.put("speed", 1);
        Core core = new Core(source, clock, 1000, ids);
        check(prop(core).get("read_age_ms") == null, "initial unknown freshness");
        check(Boolean.FALSE.equals(prop(core).get("latest_ok")), "initial not sampled");
        core.sample();
        check(number(prop(core), "ok") == 1 && number(prop(core), "err") == 0, "success counters");
        check(number(prop(core), "age_ms") == 1000, "diagnostic time offset");
        check(number(prop(core), "read_age_ms") == 0, "sample freshness");
        source.value[0] = 99;
        check(((Object[]) prop(core).get("v"))[0].equals(1), "source array defensive copy");
        Object[] exported = (Object[]) prop(core).get("v"); exported[0] = 88;
        check(((Object[]) prop(core).get("v"))[0].equals(1), "consumer cannot mutate cache");
        clock.ns += 1000000000L; source.value = new int[]{1, 2}; source.ts += 1000000000L; core.sample();
        check(number(prop(core), "chg_ms") == 1000, "equal array content does not reset change age");
        check(number(prop(core), "ts") == source.ts, "same value latest timestamp retained");
        check(number(prop(core), "read_age_ms") == 0, "same value fresh read");
        clock.ns += 1000000000L; source.value = new int[]{1, 3}; core.sample();
        check(number(prop(core), "chg_ms") == 0, "different array content resets change age");
        source.status = 2; source.value = new int[]{7, 7}; clock.ns += 1000000000L; core.sample();
        Map<String, Object> p = prop(core);
        check(((Object[]) p.get("v"))[1].equals(3), "nonzero status not adopted");
        check(number(p, "latest_st") == 2 && number(p, "st") == 0, "retained vs latest status explicit");
        check(number(p, "read_age_ms") == 1000 && number(p, "err") == 1, "nonzero status stale");
        check(Boolean.FALSE.equals(p.get("latest_ok")), "nonzero latest failure");
        source.fail = true; clock.ns += 1000000000L; core.sample(); p = prop(core);
        check(number(p, "read_age_ms") == 2000, "failed read retains growing age");
        check(p.get("latest_st") == null && "Exception".equals(p.get("latest_error")), "no sensitive error message");
        check(number(p, "ok") == 3 && number(p, "err") == 2, "independent counters");
        int before = source.reads;
        for (int i = 0; i < 5000; i++) core.snapshot("speed");
        check(source.reads == before, "cache requests never poll source");
        source.connectFail = true; core.sample();
        check(source.reads == before && source.begins == 6, "one begin per cycle, connect error skips reads");
        source.connectFail = false; source.fail = false; source.status = 0; source.conn = 0; core.sample();
        check(Boolean.TRUE.equals(prop(core).get("latest_ok")), "recovers after cycle failure");
        check(core.sequence() == 7, "seq includes failed cycles");
        check(Core.PROPERTIES.size() == 26 && !Core.PROPERTIES.containsKey("trip_a"), "no guessed ids");
        check(Json.encode(new Object[]{"\n\"\\\u0000", Double.NaN}).equals("[\"\\n\\\"\\\\\\u0000\",null]"), "json escapes and finite numbers");
        boolean refused = false;
        try { new Core(source, clock, 999); } catch (IllegalArgumentException e) { refused = true; }
        check(refused, "period floor");
        Path dir = Files.createTempDirectory("carprobe-pid-test-");
        Path pid = dir.resolve("agent.pid");
        Agent server = new Agent(new Core(new Source(), clock, 1000), 0);
        server.writePid(pid, "123");
        refused = false;
        try { server.writePid(pid, "999"); } catch (java.nio.file.FileAlreadyExistsException e) { refused = true; }
        check(refused, "PID never overwrites existing file");
        Files.write(pid, "456\n".getBytes(StandardCharsets.US_ASCII)); server.close();
        check(Files.exists(pid), "changed PID content not deleted"); Files.delete(pid);
        Agent second = new Agent(new Core(new Source(), clock, 1000), 0); second.writePid(pid, "123"); second.close();
        check(!Files.exists(pid), "owned PID cleaned up"); Files.delete(dir);
        // Multi-property failures are independent and never reconnect within a cycle.
        Map<String, Integer> mixedIds = new LinkedHashMap<String, Integer>();
        mixedIds.put("speed", 1); mixedIds.put("gear", 2);
        final int[] calls = {0, 0};
        Core mixed = new Core(new Core.Source() {
            public void beginCycle() { calls[0]++; }
            public Core.Reading read(int id) {
                calls[1]++; if (id == 1) throw new IllegalArgumentException("missing");
                return new Core.Reading(4, 0, 0);
            }
            public long connections() { return 1; }
            public void close() { }
        }, clock, 1000, mixedIds);
        mixed.sample();
        Map<String, Object> mixedProps = (Map<String, Object>) mixed.snapshot(null).get("props");
        check(((Number) ((Map<String, Object>) mixedProps.get("speed")).get("err")).intValue() == 1,
                "individual failure recorded");
        check(((Number) ((Map<String, Object>) mixedProps.get("gear")).get("ok")).intValue() == 1,
                "other property still sampled");
        check(calls[0] == 1 && calls[1] == 2, "no reconnect for missing property");
        // Bind failure cannot replace an existing PID file or start sampling.
        Path bindDir = Files.createTempDirectory("carprobe-bind-test-");
        Path bindPid = bindDir.resolve("agent.pid"); Files.write(bindPid, new byte[]{'7'});
        Agent owner = new Agent(new Core(new Source(), clock, 1000), 0);
        refused = false;
        try { new Agent(new Core(new Source(), clock, 1000), owner.port()); }
        catch (java.net.BindException e) { refused = true; }
        check(refused && Files.readAllBytes(bindPid)[0] == '7', "bind failure does not touch PID");
        owner.close(); Files.delete(bindPid); Files.delete(bindDir);
        mixed.close(); core.close();
        System.out.println("CoreTest: " + assertions + " assertions PASS");
    }
}
