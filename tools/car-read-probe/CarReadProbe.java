import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** One connection; five speed reads or one pass over declared readable configs. No Android compile dependency. */
public final class CarReadProbe {
    private static volatile String stage = "runtime";
    private static void begin(String name) { stage = name; System.out.println("BEGIN " + name); }
    private static Object call(Class<?> type, String name, Object target) throws Exception {
        return type.getMethod(name).invoke(target);
    }
    private static String preview(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v.getClass().isArray() && depth < 2) {
            int length = java.lang.reflect.Array.getLength(v);
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < Math.min(length, 64); i++) {
                String part = preview(java.lang.reflect.Array.get(v, i), depth + 1);
                if (b.length() + part.length() + 17 > 4096) {
                    if (i != 0) b.append(',');
                    return b.append("\"[truncated]\"]").toString();
                }
                if (i != 0) b.append(',');
                b.append(part);
            }
            if (length > 64) b.append(",\"[truncated]\"");
            return b.append(']').toString();
        }
        String s = v instanceof String ? (String) v : "[type:" + v.getClass().getName() + "]";
        if (s.length() > 512) s = s.substring(0, 512) + "[truncated]";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') b.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static void readAll(Object api, Method read, Method status, Method timestamp, Method value) throws Exception {
        begin("property_list");
        Object list = read.getDeclaringClass().getMethod("getPropertyList").invoke(api);
        if (!(list instanceof java.util.List)) throw new IllegalStateException("unsupported config list type");
        java.util.List<?> configs = (java.util.List<?>) list;
        System.out.println("CONFIGS count=" + configs.size());
        if (configs.size() > 4096) throw new IllegalStateException("config limit 4096 exceeded");
        Class<?> configType = Class.forName("android.car.hardware.CarPropertyConfig");
        int attempted = 0, ok = 0, unavailable = 0, failed = 0, writeOnly = 0, noAreas = 0;
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (Object config : configs) {
            int id = ((Number) call(configType, "getPropertyId", config)).intValue();
            int access = ((Number) call(configType, "getAccess", config)).intValue();
            if ((access & 1) == 0) { writeOnly++; continue; }
            int[] areas = (int[]) call(configType, "getAreaIds", config);
            if (areas.length == 0 && Boolean.TRUE.equals(call(configType, "isGlobalProperty", config))) areas = new int[]{0};
            if (areas.length == 0) { noAreas++; continue; }
            for (int area : areas) {
                String key = String.format("id=0x%08x area=0x%x", id, area);
                if (!seen.add(key)) continue;
                if (++attempted > 8192) throw new IllegalStateException("read limit 8192 exceeded");
                begin("all_" + key);
                try {
                    long start = System.nanoTime();
                    Object result = read.invoke(api, id, area);
                    long micros = (System.nanoTime() - start) / 1000;
                    if (result == null) throw new IllegalStateException("null CarPropertyValue");
                    int st = ((Number) status.invoke(result)).intValue();
                    Object raw = st == 0 ? value.invoke(result) : null;
                    boolean valid = st == 0 && raw != null
                            && (!(raw instanceof Number) || Double.isFinite(((Number) raw).doubleValue()));
                    System.out.println("ITEM " + key + " status=" + st + " ts_ns=" + timestamp.invoke(result)
                            + " type=" + (raw == null ? "null" : raw.getClass().getName())
                            + " raw_preview=" + preview(raw, 0) + " read_us=" + micros);
                    if (valid) ok++; else unavailable++;
                } catch (Exception e) {
                    Throwable cause = e;
                    while (cause instanceof InvocationTargetException && cause.getCause() != null) cause = cause.getCause();
                    failed++;
                    System.out.println("ERROR " + key + " error=" + cause.getClass().getName());
                }
                Thread.sleep(20);
            }
        }
        System.out.println("DONE configs=" + configs.size() + " attempted=" + attempted + " ok=" + ok
                + " unavailable=" + unavailable + " failed=" + failed + " skipped_non_read=" + writeOnly + " skipped_no_areas=" + noAreas);
        if (ok == 0) System.exit(3);
    }
    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("CarReadProbe: shell UID2000; default speed 5x/15s; --all reads declared readable configs once/180s.");
            return;
        }
        boolean all = args.length == 1 && "--all".equals(args[0]);
        if (args.length != 0 && !all) { System.err.println("Usage: CarReadProbe [--help|--all]"); System.exit(2); }
        final int limit = all ? 180 : 15;
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(limit * 1000L); }
            catch (InterruptedException e) { return; }
            System.err.println("FAIL timeout stage=" + stage + " limit_s=" + limit);
            System.exit(124);
        }, "probe-deadline");
        watchdog.setDaemon(true); watchdog.start();
        try {
            begin("uid");
            int uid = ((Number) call(Class.forName("android.os.Process"), "myUid", null)).intValue();
            if (uid != 2000) throw new SecurityException("requires shell UID2000, not root");
            System.out.println("OK uid=" + uid);
            begin("classes");
            Class<?> ib = Class.forName("android.os.IBinder");
            Class<?> carType = Class.forName("android.car.ICar");
            Class<?> carStub = Class.forName("android.car.ICar$Stub");
            Class<?> valueType = Class.forName("android.car.hardware.CarPropertyValue");
            Method status = valueType.getMethod("getStatus"), timestamp = valueType.getMethod("getTimestamp");
            Method value = valueType.getMethod("getValue");
            long connectStart = System.nanoTime();
            begin("car_service");
            Object carBinder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "car_service");
            if (carBinder == null) throw new IllegalStateException("car_service absent");
            Object car = carStub.getMethod("asInterface", ib).invoke(null, carBinder);
            begin("property_service");
            Object propertyBinder = carType.getMethod("getCarService", String.class).invoke(car, "property");
            if (propertyBinder == null) throw new IllegalStateException("property service absent");
            begin("property_interface");
            String descriptor = (String) call(ib, "getInterfaceDescriptor", propertyBinder);
            System.out.println("INFO descriptor=" + descriptor);
            if (!"android.car.hardware.property.ICarProperty".equals(descriptor)
                    && !"android.car.ICarProperty".equals(descriptor))
                throw new IllegalStateException("unsupported descriptor; no guessed transactions");
            Object api = Class.forName(descriptor + "$Stub").getMethod("asInterface", ib).invoke(null, propertyBinder);
            Method read = Class.forName(descriptor).getMethod("getProperty", int.class, int.class);
            System.out.println("OK connected_once connect_us=" + (System.nanoTime() - connectStart) / 1000);
            if (all) { readAll(api, read, status, timestamp, value); return; }
            boolean allValid = true;
            for (int i = 1; i <= 5; i++) {
                begin("read_" + i);
                long start = System.nanoTime();
                Object result = read.invoke(api, 0x21601225, 0);
                long micros = (System.nanoTime() - start) / 1000;
                if (result == null) throw new IllegalStateException("null CarPropertyValue");
                int st = ((Number) status.invoke(result)).intValue();
                Object raw = st == 0 ? value.invoke(result) : null;
                boolean valid = raw instanceof Number && Double.isFinite(((Number) raw).doubleValue());
                allValid &= st == 0 && valid;
                System.out.println("SAMPLE i=" + i + " status=" + st + " ts_ns=" + timestamp.invoke(result)
                        + " raw=" + (valid ? raw : "unavailable_or_non_numeric") + " read_us=" + micros);
                if (i < 5) Thread.sleep(1000);
            }
            if (!allValid) { System.err.println("FAIL samples_unavailable_or_non_numeric"); System.exit(3); }
            System.out.println("PASS reads=5 connections=1 id=0x21601225 area=0");
        } catch (Throwable e) {
            while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
            String message = String.valueOf(e.getMessage()).replace('\n', ' ').replace('\r', ' ');
            System.err.println("FAIL stage=" + stage + " error=" + e.getClass().getName()
                    + " message=" + message.substring(0, Math.min(180, message.length())));
            System.exit(1);
        } finally { watchdog.interrupt(); }
    }
}
