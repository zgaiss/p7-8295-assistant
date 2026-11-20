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
    // 转向灯：与车机 dumpsys car_service 里的
    // Property=BCM_LTURNLAMP_ST(557849623, 0x21401c17) 一一对应。
    private static final int PROP_TURN_LEFT = 0x21401c17;
    private static final int PROP_TURN_RIGHT = 0x21401c18;
    private static final int PROP_GEAR = 0x21401205;      // VCU_CURRENT_GEARLEV

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
        return raw(id, false);
    }

    /**
     * 读一个属性；quiet=true 时失败不写日志。
     *
     * <p>转向灯/挡位是 200ms 轮询，属性一旦在这个固件上不存在，
     * 每次失败都打一行日志就是每秒 5 行的噪声，把真正的问题埋掉。</p>
     */
    private static Object raw(int id, boolean quiet) {
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
            if (!quiet) Log.w(TAG, String.format("读 0x%08x 失败: %s", id, lastErr));
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

    // ---- 打灯影像用到的三个信号（200ms 轮询，读不到返回 null 表示"未知"）----

    /** 左转向灯；null=读不到（调用方应保持上一状态，不要当成"灭"） */
    public static Boolean readTurnLeft() {
        Object v = raw(PROP_TURN_LEFT, true);
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return null;
    }

    /** 右转向灯 */
    public static Boolean readTurnRight() {
        Object v = raw(PROP_TURN_RIGHT, true);
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return null;
    }

    /**
     * 挡位（VCU_CURRENT_GEARLEV 原始值）。
     *
     * <p>P7 车机上 P=4，按 P/R/N/D 递减推断 R=3（另一套打灯影像实现的实车结论）。
     * 若实车日志显示 R 是别的值，只改 {@link #GEAR_REVERSE} 即可。</p>
     */
    public static Integer readGear() {
        Object v = raw(PROP_GEAR, true);
        if (v instanceof Number) return ((Number) v).intValue();
        return null;
    }

    /** 倒挡档位值 */
    public static final int GEAR_REVERSE = 3;

    private static String shortErr(Throwable t) {
        String s = t.getClass().getSimpleName();
        if (t.getMessage() != null) s += ": " + t.getMessage();
        if (s.length() > 90) s = s.substring(0, 90);
        return s;
    }
}
