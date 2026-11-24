package android.os;
public final class ServiceManager {
    public static IBinder service;
    public static int lookups;
    public static IBinder getService(String name) {
        if (!"car_service".equals(name)) throw new AssertionError(name);
        lookups++; return service;
    }
}
