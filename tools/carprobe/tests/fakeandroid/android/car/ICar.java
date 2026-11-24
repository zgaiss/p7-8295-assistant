package android.car;
import android.os.IBinder;
public interface ICar extends IBinder {
    IBinder getCarService(String name);
    final class Stub {
        public static ICar asInterface(IBinder binder) { return (ICar) binder; }
    }
}
