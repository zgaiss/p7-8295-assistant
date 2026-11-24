package cp;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** No Android compile dependency. Requires the target's android.car.jar at runtime. */
public final class AndroidSource implements Core.Source {
    private volatile Object api;
    private Object binder;
    private Method read, alive;
    private Method getValue, getTimestamp, getStatus;
    private long connections;
    private final ClassLoader loader = AndroidSource.class.getClassLoader();
    @Override public void beginCycle() throws Exception {
        if (api != null) {
            if (Boolean.TRUE.equals(alive.invoke(binder))) return;
            api = null;
        }
        Class<?> ib = Class.forName("android.os.IBinder");
        Object carBinder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "car_service");
        if (carBinder == null) throw new IllegalStateException("car_service_unavailable");
        Object car = loader.loadClass("android.car.ICar$Stub")
                .getMethod("asInterface", ib).invoke(null, carBinder);
        Object propertyBinder = loader.loadClass("android.car.ICar")
                .getMethod("getCarService", String.class).invoke(car, "property");
        if (propertyBinder == null) throw new IllegalStateException("property_service_unavailable");
        // Vendor builds may keep this interface in android.car or in hardware.property.
        // Use the actual Binder descriptor, not a guessed transaction code or parcel layout.
        String descriptor = (String) ib.getMethod("getInterfaceDescriptor").invoke(propertyBinder);
        if (!"android.car.hardware.property.ICarProperty".equals(descriptor)
                && !"android.car.ICarProperty".equals(descriptor))
            throw new IllegalStateException("unsupported_property_interface");
        Object property = loader.loadClass(descriptor + "$Stub")
                .getMethod("asInterface", ib).invoke(null, propertyBinder);
        read = loader.loadClass(descriptor).getMethod("getProperty", int.class, int.class);
        Class<?> value = loader.loadClass("android.car.hardware.CarPropertyValue");
        getValue = value.getMethod("getValue"); getTimestamp = value.getMethod("getTimestamp");
        getStatus = value.getMethod("getStatus"); alive = ib.getMethod("isBinderAlive");
        binder = propertyBinder; api = property; connections++;
    }
    @Override public Core.Reading read(int id) throws Exception {
        Object current = api;
        if (current == null) throw new IllegalStateException("binder_unavailable_until_next_cycle");
        try {
            Object value = read.invoke(current, id, 0);
            if (value == null) return null;
            int status = ((Number) getStatus.invoke(value)).intValue();
            return new Core.Reading(status == 0 ? getValue.invoke(value) : null,
                    ((Number) getTimestamp.invoke(value)).longValue(), status);
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            // Missing/denied individual properties do NOT force reconnection.
            for (Throwable t = cause; t != null; t = t.getCause()) {
                if ("android.os.DeadObjectException".equals(t.getClass().getName())
                        || "android.os.DeadSystemException".equals(t.getClass().getName())) {
                    api = null; break;
                }
            }
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        }
    }
    @Override public long connections() { return connections; }
    @Override public void close() { api = null; }
    public static Core.Clock clock() {
        Method elapsed = null;
        try { elapsed = Class.forName("android.os.SystemClock").getMethod("elapsedRealtimeNanos"); }
        catch (ReflectiveOperationException ignored) { /* host tests */ }
        final Method method = elapsed;
        return new Core.Clock() {
            public long nanos() {
                if (method != null) try { return ((Number) method.invoke(null)).longValue(); }
                catch (ReflectiveOperationException e) { throw new IllegalStateException("clock_unavailable", e); }
                return System.nanoTime();
            }
            public long wallMillis() { return System.currentTimeMillis(); }
        };
    }
}
