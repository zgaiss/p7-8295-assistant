package com.xiaopeng.clusterassist;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 转向灯 / 挡位信号，走 XUI contextinfo。
 *
 * <p>为什么不走 CarProperty：本车 V5.10 固件上 ICarProperty.getProperty 被厂商的
 * CarPermissionManager 拦下（{@code SecurityException: CarPermissionManager has not grated}），
 * 第三方 app 读任何 BCM_/VCU_ 属性都返回 null，且没有任何公开途径申请该授权。</p>
 *
 * <p>XUI contextinfo 是厂商对外开放的通道：权限
 * {@code com.xiaopeng.xuimanager.permission.XUI_CONTEXTINFO} 是 prot=normal，装上即授予，
 * 不需要平台签名。已用独立探针实测：277 秒 / 673 事件，{@code onBcmLightMode(int,int)}
 * 的第一参数就是车辆属性号（0x21401c17 左 / 0x21401c18 右），第二参数 1/0 以 480ms 翻转，
 * 即转向灯闪烁状态。</p>
 *
 * <p>关键点：转向灯在闪，所以「灭」事件不代表关灯。这里只记录「最近一次亮」的时刻，
 * 由 {@link #HOLD_MS} 决定灯是否仍然亮着——否则镜像会跟着闪光每 480ms 开关一次。</p>
 */
public class XuiLight {
    private static final String TAG = "P7XuiLight";

    private static final String DESC_XUI = "com.xiaopeng.xuimanager.IXUIService";
    private static final String DESC_CTX = "com.xiaopeng.xuimanager.contextinfo.IContextInfo";
    private static final String DESC_EVT = "com.xiaopeng.xuimanager.contextinfo.IContextInfoEventListener";

    private static final int TR_GET_XUI_SERVICE = 1;      // IXUIService.getXUIService(String)
    private static final int TR_REGISTER_LISTENERS = 13;  // IContextInfo.registerListeners(listener, int)

    private static final int E_GEAR = 16;    // onGearChanged(int)
    private static final int E_LIGHT = 30;   // onBcmLightMode(int id, int on)
    private static final int MAX_CODE = 40;  // 已知事件号上界，其余事务一律应答避免服务端重试

    /** onBcmLightMode 的第一参数就是车辆属性号，与 dumpsys car_service 里的 id 一致 */
    private static final int ID_TURN_LEFT = 0x21401c17;   // BCM_LTURNLAMP_ST
    private static final int ID_TURN_RIGHT = 0x21401c18;  // BCM_RTURNLAMP_ST

    /** XUI 挡位编码：1=P 2=R 3=N 4=D（实测 P/N/D，R 为推断值）。与 CarProperty 的 VCU 编码不同。 */
    public static final int GEAR_REVERSE = 2;

    /** 转向灯闪烁周期约 960ms（亮 480 / 灭 480）。窗口取 1.6s：能跨过一个「灭」的空档，
     *  又能在真熄灯后 1.6s 内撤区，不会让画面赖在屏幕上。 */
    private static final long HOLD_MS = 1600;

    private static volatile long leftOnAt;
    private static volatile long rightOnAt;
    private static volatile int gear = -1;
    private static volatile boolean ready;
    private static volatile String lastErr = "未启动";
    private static volatile int events;

    /** listener 必须强引用住：被 GC 后调用方持的是死 binder，事件静默断流（现象就是打灯没反应）。 */
    private static Binder listener;
    private static IBinder ctxBinder;

    public static synchronized boolean start() {
        if (ready) return true;
        try {
            IBinder xui = serviceManagerGet("XuiServiceManager");
            if (xui == null) return fail("没有 XuiServiceManager");
            ctxBinder = getXuiService(xui, "contextinfo");
            if (ctxBinder == null) return fail("getXUIService(contextinfo) 返回 null");
            if (listener == null) listener = new EventSink();
            if (!registerListeners(ctxBinder, listener, 1)) return fail("registerListeners 失败");
            ready = true;
            lastErr = null;
            Log.i(TAG, "contextinfo 已订阅");
            return true;
        } catch (Throwable t) {
            return fail(String.valueOf(t));
        }
    }

    public static synchronized void stop() {
        // 不主动反注册：车机上没有稳定的 unregister 事务号，直接丢弃引用即可，
        // 进程退出时 binder 自然断开。这里只把状态清掉，避免残留「亮」被误判。
        ready = false;
        lastErr = "已停止";
        leftOnAt = 0;
        rightOnAt = 0;
        gear = -1;
    }

    public static boolean isReady() {
        return ready;
    }

    public static String lastError() {
        return lastErr;
    }

    public static int eventCount() {
        return events;
    }

    public static int gear() {
        return gear;
    }

    public static boolean isReverse() {
        return gear == GEAR_REVERSE;
    }

    public static boolean isLeftOn() {
        return ready && SystemClock.elapsedRealtime() - leftOnAt < HOLD_MS;
    }

    public static boolean isRightOn() {
        return ready && SystemClock.elapsedRealtime() - rightOnAt < HOLD_MS;
    }

    private static boolean fail(String why) {
        lastErr = why;
        Log.w(TAG, "订阅失败: " + why);
        return false;
    }

    private static IBinder serviceManagerGet(String name) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method m = sm.getMethod("getService", String.class);
            return (IBinder) m.invoke(null, name);
        } catch (Throwable t) {
            lastErr = "ServiceManager.getService 失败: " + t;
            return null;
        }
    }

    private static IBinder getXuiService(IBinder root, String serviceName) {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_XUI);
            d.writeString(serviceName);
            if (!root.transact(TR_GET_XUI_SERVICE, d, r, 0)) return null;
            r.readException();
            return r.readStrongBinder();
        } catch (Throwable t) {
            lastErr = "getXUIService 失败: " + t;
            return null;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static boolean registerListeners(IBinder ctx, IBinder sink, int flags) {
        Parcel d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESC_CTX);
            d.writeStrongBinder(sink);
            d.writeInt(flags);
            boolean ok = ctx.transact(TR_REGISTER_LISTENERS, d, r, 0);
            r.readException();
            Log.i(TAG, "registerListeners(flags=" + flags + ") transact=" + ok);
            return ok;
        } catch (Throwable t) {
            lastErr = "registerListeners 失败: " + t;
            return false;
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    private static final class EventSink extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code >= 1 && code <= MAX_CODE) {
                data.enforceInterface(DESC_EVT);
                try {
                    if (code == E_LIGHT) {
                        int id = data.readInt();
                        int on = data.readInt();
                        if (on != 0) {
                            long now = SystemClock.elapsedRealtime();
                            if (id == ID_TURN_LEFT) leftOnAt = now;
                            else if (id == ID_TURN_RIGHT) rightOnAt = now;
                        }
                        events++;
                    } else if (code == E_GEAR) {
                        gear = data.readInt();
                        events++;
                    }
                } catch (Throwable t) {
                    // 绝不能让异常回抛到 binder 线程池：一抛就断流
                    Log.w(TAG, "事件 " + code + " 解析异常: " + t);
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    }
}
