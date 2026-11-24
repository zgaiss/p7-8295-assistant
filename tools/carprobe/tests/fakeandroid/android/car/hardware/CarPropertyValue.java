package android.car.hardware;
public final class CarPropertyValue {
    private final Object value; private final int status;
    public CarPropertyValue(Object value, int status) { this.value = value; this.status = status; }
    public Object getValue() {
        if (status != 0) throw new IllegalStateException("unavailable");
        return value;
    }
    public int getStatus() { return status; }
    public long getTimestamp() { return 12345; }
}
