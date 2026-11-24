import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** One connection, one known property, five reads, then exit. No Android compile dependency. */
public final class CarReadProbe {
    private static volatile String stage = "runtime";
    private static void begin(String name) { stage = name; System.out.println("BEGIN " + name); }
    private static Object call(Class<?> type, String name, Object target) throws Exception {
        return type.getMethod(name).invoke(target);
    }
    public static void main(String[] args) {
        if (args.length == 1 && "--help".equals(args[0])) {
            System.out.println("CarReadProbe: shell UID2000; speed 0x21601225 area0; 5 reads at 1s; 15s limit.");
            return;
        }
        if (args.length != 0) { System.err.println("Usage: CarReadProbe [--help]"); System.exit(2); }
        Thread watchdog = new Thread(() -> {
            try { Thread.sleep(15000); }
            catch (InterruptedException e) { return; }
            System.err.println("FAIL timeout stage=" + stage + " limit_s=15");
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
