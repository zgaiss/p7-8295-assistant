package cp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.Arrays;

/** Single-owner sampling. All consumers see a completed, immutable generation. */
public final class Core implements AutoCloseable {
    public interface Clock { long nanos(); long wallMillis(); }
    public interface Source extends AutoCloseable {
        void beginCycle() throws Exception;
        Reading read(int propertyId) throws Exception;
        long connections();
        void close();
    }
    public static final class Reading {
        public final Object value;
        public final long timestamp;
        public final int status;
        public Reading(Object value, long timestamp, int status) {
            this.value = value; this.timestamp = timestamp; this.status = status;
        }
    }
    public static final Map<String, Integer> PROPERTIES;
    static {
        LinkedHashMap<String, Integer> p = new LinkedHashMap<String, Integer>();
        p.put("speed", 0x21601225); p.put("gear", 0x21401205);
        p.put("turn_left", 0x21401c17); p.put("turn_right", 0x21401c18);
        p.put("door", 0x21411c19); p.put("belt_drv", 0x21401c0c);
        p.put("tpms_fl", 0x21601e02); p.put("tpms_fr", 0x21601e03);
        p.put("tpms_rl", 0x21601e04); p.put("tpms_rr", 0x21601e05);
        p.put("tpms_temp", 0x21411e0f); p.put("cabin_temp", 0x21601a2d);
        p.put("outside_temp", 0x11600703); p.put("hvac_set", 0x15600503);
        p.put("odo", 0x2160162e); p.put("soc", 0x21401202);
        p.put("range_cltc", 0x2160127a); p.put("range_wltp", 0x2160127b);
        p.put("chg_st", 0x21401229); p.put("chg_gun", 0x21401228);
        p.put("acc_pedal", 0x21401218); p.put("brk_pedal", 0x21401217);
        p.put("avm_st", 0x21403410); p.put("cruise", 0x2140124e);
        p.put("drive_mode", 0x2140122a); p.put("energy_avg", 0x2160121b);
        PROPERTIES = Collections.unmodifiableMap(p);
    }
    private static final class State {
        Object value; Long timestamp, readAt, changedAt; Integer status, latestStatus;
        long ok, err; boolean latestOk; String latestError = "not_sampled";
        State() { }
        State(State s) {
            value = s.value; timestamp = s.timestamp; readAt = s.readAt;
            changedAt = s.changedAt; status = s.status; latestStatus = s.latestStatus;
            ok = s.ok; err = s.err; latestOk = s.latestOk; latestError = s.latestError;
        }
    }
    private static final class Frame {
        final Map<String, State> states; final long seq, conn; final Long endedAt;
        final double pollMs;
        Frame(Map<String, State> s, long seq, long conn, Long endedAt, double pollMs) {
            this.states = s; this.seq = seq; this.conn = conn; this.endedAt = endedAt;
            this.pollMs = pollMs;
        }
    }
    private final Source source;
    private final Clock clock;
    private final int period;
    private final long startedAt;
    private final Map<String, Integer> properties;
    private volatile Frame frame;
    private volatile boolean running, sampling;
    private Thread sampler;
    public Core(Source source, Clock clock, int period) {
        this(source, clock, period, PROPERTIES);
    }
    Core(Source source, Clock clock, int period, Map<String, Integer> properties) {
        if (period < 1000) throw new IllegalArgumentException("period_must_be_at_least_1000");
        this.source = source; this.clock = clock; this.period = period;
        this.properties = new LinkedHashMap<String, Integer>(properties);
        startedAt = clock.nanos();
        Map<String, State> states = new LinkedHashMap<String, State>();
        for (String name : properties.keySet()) states.put(name, new State());
        frame = new Frame(states, 0, 0, null, 0);
    }
    public synchronized void start() {
        if (sampler != null) throw new IllegalStateException("already_started");
        running = true;
        sampler = new Thread(new Runnable() { public void run() {
            while (running) {
                long begin = clock.nanos(); sample();
                long wait = Math.max(0, period - (clock.nanos() - begin) / 1000000L);
                synchronized (Core.this) {
                    if (running && wait > 0) try { Core.this.wait(wait); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
        } }, "carprobe-sampler");
        // A stuck vendor Binder transaction must not make process shutdown hang.
        sampler.setDaemon(true); sampler.start();
    }
    void sample() {
        sampling = true; long begin = clock.nanos();
        Map<String, State> next = new LinkedHashMap<String, State>();
        String cycleError = null;
        try { source.beginCycle(); } catch (Exception e) { cycleError = error(e); }
        for (Map.Entry<String, Integer> p : properties.entrySet()) {
            State s = new State(frame.states.get(p.getKey()));
            s.latestStatus = null;
            try {
                if (cycleError != null) throw new SampleException(cycleError);
                Reading r = source.read(p.getValue());
                if (r == null) throw new SampleException("null_property");
                s.latestStatus = r.status;
                if (r.status != 0) throw new SampleException("vhal_status_nonzero");
                Object v = Json.copy(r.value);
                if (v == null) throw new SampleException("null_value");
                long now = clock.nanos();
                if (s.readAt == null || !Arrays.deepEquals(new Object[]{s.value}, new Object[]{v}))
                    s.changedAt = now;
                s.value = v; s.timestamp = r.timestamp; s.status = r.status; s.readAt = now;
                s.ok++; s.latestOk = true; s.latestError = null;
            } catch (Exception e) {
                s.err++; s.latestOk = false; s.latestError = error(e);
            }
            next.put(p.getKey(), s);
        }
        long end = clock.nanos(); Frame old = frame;
        frame = new Frame(next, old.seq + 1, source.connections(), end, (end - begin) / 1000000.0);
        sampling = false;
        synchronized (this) { notifyAll(); }
    }
    private static final class SampleException extends Exception {
        SampleException(String message) { super(message); }
    }
    static String error(Exception e) {
        // Deliberately exclude exception messages, property contents, and vehicle identifiers.
        return e instanceof SampleException ? e.getMessage() : e.getClass().getSimpleName();
    }
    public long sequence() { return frame.seq; }
    public synchronized void awaitAfter(long seq, long timeoutMs) throws InterruptedException {
        if (running && frame.seq <= seq) wait(timeoutMs);
    }
    public Map<String, Object> snapshot(String only) {
        Frame f = frame; long now = clock.nanos();
        Map<String, Object> result = map();
        result.put("t", clock.wallMillis()); result.put("seq", f.seq);
        result.put("up_s", Math.max(0, now - startedAt) / 1000000000.0);
        result.put("period_ms", period); result.put("conn", f.conn);
        result.put("poll_ms", f.pollMs); result.put("sampling", sampling);
        result.put("sample_age_ms", elapsed(now, f.endedAt));
        Map<String, Object> props = map();
        for (Map.Entry<String, State> e : f.states.entrySet()) {
            if (only != null && !only.equals(e.getKey())) continue;
            State s = e.getValue(); Map<String, Object> p = map();
            p.put("v", Json.copy(s.value)); p.put("ts", s.timestamp); p.put("st", s.status);
            p.put("age_ms", s.timestamp == null ? null : (now - s.timestamp) / 1000000.0);
            p.put("chg_ms", elapsed(now, s.changedAt)); p.put("read_age_ms", elapsed(now, s.readAt));
            p.put("ok", s.ok); p.put("err", s.err); p.put("latest_ok", s.latestOk);
            p.put("latest_st", s.latestStatus); p.put("latest_error", s.latestError);
            props.put(e.getKey(), p);
        }
        result.put("props", props); return result;
    }
    private static Object elapsed(long now, Long then) {
        return then == null ? null : Math.max(0, now - then) / 1000000.0;
    }
    static Map<String, Object> map() { return new LinkedHashMap<String, Object>(); }
    @Override public void close() {
        Thread t;
        synchronized (this) { running = false; notifyAll(); t = sampler; }
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        source.close();
    }
}
