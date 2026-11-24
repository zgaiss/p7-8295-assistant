package cp;

import android.os.IBinder;
import android.os.ServiceManager;
import android.os.DeadObjectException;
import android.car.ICar;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.ICarProperty;

/** Contract-shaped host fixtures only: not proof of target Android compatibility. */
public final class ReflectionTest {
    public static final class Property implements ICarProperty {
        boolean alive = true, deadRead; int status, reads;
        public String getInterfaceDescriptor() { return "android.car.hardware.property.ICarProperty"; }
        public boolean isBinderAlive() { return alive; }
        public CarPropertyValue getProperty(int id, int area) throws Exception {
            reads++;
            if (area != 0) throw new AssertionError("area");
            if (deadRead) throw new DeadObjectException();
            if (id == 999) throw new IllegalArgumentException("missing");
            return new CarPropertyValue(42, status);
        }
    }
    public static final class Car implements ICar {
        Property property = new Property();
        public String getInterfaceDescriptor() { return "android.car.ICar"; }
        public boolean isBinderAlive() { return true; }
        public IBinder getCarService(String name) {
            if (!"property".equals(name)) throw new AssertionError("service");
            return property;
        }
    }
    public static void main(String[] args) throws Exception {
        AndroidSource source = new AndroidSource();
        boolean failed = false;
        try { source.beginCycle(); } catch (IllegalStateException e) { failed = true; }
        CoreTest.check(failed && source.connections() == 0, "absent service retryable");
        Car car = new Car(); ServiceManager.service = car;
        source.beginCycle(); Core.Reading r = source.read(1);
        CoreTest.check(r.value.equals(42) && r.timestamp == 12345 && r.status == 0, "reflection value metadata");
        int lookups = ServiceManager.lookups;
        failed = false;
        try { source.read(999); } catch (IllegalArgumentException e) { failed = true; }
        CoreTest.check(failed, "unwrap individual property error");
        source.beginCycle(); source.read(1);
        CoreTest.check(ServiceManager.lookups == lookups && source.connections() == 1, "missing property does not reconnect");
        car.property.status = 2; r = source.read(1);
        CoreTest.check(r.status == 2 && r.value == null, "nonzero status never calls unavailable getValue");
        car.property.deadRead = true;
        try { source.read(1); } catch (DeadObjectException expected) { }
        int reads = car.property.reads;
        failed = false;
        try { source.read(2); } catch (IllegalStateException e) { failed = true; }
        CoreTest.check(failed && car.property.reads == reads && ServiceManager.lookups == lookups,
                "dead Binder not reconnected per property");
        car.property = new Property(); source.beginCycle(); source.read(1);
        CoreTest.check(source.connections() == 2, "next cycle reconnect after death");
        car.property.alive = false; car.property = new Property();
        // Old binder object's alive=false is still held by AndroidSource.
        source.beginCycle();
        CoreTest.check(source.connections() == 3, "precycle dead Binder detected");
        source.close();
        System.out.println("ReflectionTest: 8 contract checks PASS (host fake Android only)");
    }
}
