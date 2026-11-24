package android.car.hardware.property;
import android.os.IBinder;
import android.car.hardware.CarPropertyValue;
public interface ICarProperty extends IBinder {
    CarPropertyValue getProperty(int id, int area) throws Exception;
    final class Stub {
        public static ICarProperty asInterface(IBinder binder) { return (ICarProperty) binder; }
    }
}
