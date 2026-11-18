package com.xiaopeng.clusterassist;

import android.content.Context;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 车机侧车辆信号读取。
 *
 * 仪表 DBus 里没有 12V 电压，只能从车机 car_service 拿。路径与原厂 worker 一致：
 *   ServiceManager.getService("car_service")
 *     → android.car.ICar.getCarService("property")
 *     → ICarProperty.getProperty(propId, area=0)
 *     → CarPropertyValue.getValue()
 *
 * 注意：这里必须走 "property" 服务，不是 "xp_vehicle"。IXpVehicle 上没有 getProperty(int,int)，
 * 调错会抛 NoSuchMethodException，表现就是电压永远读不到。
 */
public class CarBridge {
    private static final String TAG = "P7CarBridge";
    private static final int PROP_12V_VOLT = 0x21601215; // VCU_EBSBATT_VOLT（float，单位 V）
    private static final int PROP_12V_CURR = 0x21601216; // VCU_EBSBATT_CURR
    private static final int PROP_BSD_LEFT = 0x21402624; // SCU_BSD_LEFT_WARNING
    private static final int PROP_BSD_RIGHT = 0x21402623; // SCU_BSD_RIGHT_WARNING

    private static Object propApi;
    private static ClassLoader carLoader;
    private static String desc;
    private static String lastErr = "未初始化";
    private static long lastTry;
    private static Float lastVolt;

    public static String lastError() {
        return lastErr;
    }

    private static ClassLoader car() {
        if (carLoader == null) {
            try {
                carLoader = new dalvik.system.PathClassLoader("/system/framework/android.car.jar",
                        CarBridge.class.getClassLoader());
                // 探一下能否加载，失败就退回系统 classloader
                carLoader.loadClass("android.car.ICar");
            } catch (Throwable t) {
                carLoader = CarBridge.class.getClassLoader();
            }
        }
        return carLoader;
    }

    /** 反射拿到 ICarProperty 代理 */
    private static synchronized Object api() {
        if (propApi != null) return propApi;
        try {
            ClassLoader cl = car();
            IBinder carSvc = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "car_service");
            if (carSvc == null) {
                lastErr = "car_service 不存在";
                return null;
            }
            Object iCar = cl.loadClass("android.car.ICar$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, carSvc);
            Object prop = cl.loadClass("android.car.ICar")
                    .getMethod("getCarService", String.class).invoke(iCar, "property");
            if (prop == null) {
                lastErr = "property 服务不存在";
                return null;
            }
            desc = ((IBinder) prop).getInterfaceDescriptor();
            Object stub = cl.loadClass(desc + "$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, prop);
            propApi = stub;
            lastErr = null;
            Log.i(TAG, "ICarProperty 就绪: " + desc);
            return propApi;
        } catch (Throwable t) {
            lastErr = shortErr(t);
            Log.w(TAG, "拿 ICarProperty 失败: " + lastErr);
            return null;
        }
    }

    /** 读一个属性，返回原始 value 对象；失败返回 null */
    private static Object raw(int id) {
        Object api = api();
        if (api == null) return null;
        try {
            ClassLoader cl = car();
            Object v = cl.loadClass(desc).getMethod("getProperty", int.class, int.class)
                    .invoke(api, id, 0);
            return cl.loadClass("android.car.hardware.CarPropertyValue")
                    .getMethod("getValue").invoke(v);
        } catch (Throwable t) {
            lastErr = shortErr(t.getCause() != null ? t.getCause() : t);
            Log.w(TAG, String.format("读 0x%08x 失败: %s", id, lastErr));
            return null;
        }
    }

    /** 12V 电压（V）；读不到返回 null */
    public static Float readVolt(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastTry < 3000 && (lastVolt != null || lastErr != null)) {
            return lastVolt;
        }
        lastTry = now;
        Object v = raw(PROP_12V_VOLT);
        if (v instanceof Number) {
            float f = ((Number) v).floatValue();
            if (f > 6 && f < 20) {
                lastVolt = f;
                lastErr = null;
                return f;
            }
            lastErr = "电压超范围: " + f;
        }
        return lastVolt;
    }

    /** 左盲区（CarProperty 侧，作为仪表总线的补充源） */
    public static Boolean readBsdLeft(Context ctx) {
        Object v = raw(PROP_BSD_LEFT);
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return null;
    }

    /** 右盲区 */
    public static Boolean readBsdRight(Context ctx) {
        Object v = raw(PROP_BSD_RIGHT);
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return null;
    }

    /** 小电瓶 SOC（%） */
    public static Integer readEbsSoc(Context ctx) {
        Object v = raw(0x21401226);
        if (v instanceof Number) return ((Number) v).intValue();
        return null;
    }

    private static String shortErr(Throwable t) {
        String s = t.getClass().getSimpleName();
        if (t.getMessage() != null) s += ": " + t.getMessage();
        if (s.length() > 90) s = s.substring(0, 90);
        return s;
    }
}
