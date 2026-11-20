package com.xiaopeng.clusterassist;

import android.hardware.camera2.CameraManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AVM 视角切换（display-mode）。
 *
 * <p>为什么需要它：车机把「AVM 环视合成画面」当作一路相机暴露给应用。默认视角是
 * 2D 俯视 + 前视的拼接图，打灯时想要的是该侧的广角鱼眼。车机内置的 CameraManager
 * 上有一组厂商扩展接口 {@code getCmdControlParameters} / {@code setCmdControlParameters}，
 * 通过 parameter 里的 {@code display-mode} 可以切换到四路鱼眼四宫格（mode=8）。
 * 切过去之后相机流本身就变成 2×2 的四路鱼眼拼图，我们再把对应象限裁出来。</p>
 *
 * <p>实测取值（另一套打灯影像实现 P7_ViewAssist 在实车标定，本车 V5.10.0.8814 同源）：
 * 4 = 3D 环视、8 = 四路鱼眼四宫格、16 = 2D 单路前视、21 = 360 三分屏。</p>
 *
 * <p>容错红线：参数对象不存在、反射失败、任何异常，一律静默降级并记一条日志，
 * 绝不影响打灯影像的主流程（拿不到鱼眼就按原样推整幅画面）。</p>
 */
public final class AvmMode {

    private static final String TAG = "P7Avm";

    public static final int MODE_3D = 4;
    public static final int MODE_QUAD = 8;      // 四路鱼眼四宫格
    public static final int MODE_FRONT = 16;    // 2D 单路前视
    public static final int MODE_360 = 21;      // 360 三分屏

    private static final Pattern DISPLAY_MODE = Pattern.compile("display-mode=(-?\\d+)");

    /** 上一次失败原因，给心跳日志用。成功时置 null。 */
    private static String lastError;

    private AvmMode() { }

    public static String lastError() {
        return lastError;
    }

    /** 取厂商扩展参数对象；取不到返回 null。 */
    private static Object getParams(CameraManager cm) {
        try {
            Method m = cm.getClass().getMethod("getCmdControlParameters");
            return m.invoke(cm);
        } catch (Throwable t) {
            lastError = "getCmdControlParameters: " + t;
            return null;
        }
    }

    /**
     * 把参数对象写回车机。注意车机上的重载不唯一，按「对象 → flatten(int,String)
     * → flatten(String) → (int,对象)」的顺序逐个试，只要有一个成功就算下发成功。
     */
    private static boolean setParams(CameraManager cm, Object params) {
        String flatten = null;
        try {
            flatten = (String) params.getClass().getMethod("flatten").invoke(params);
        } catch (Throwable ignored) {
            // flatten 拿不到不影响按对象下发
        }

        List<Method> overloads = new ArrayList<>();
        for (Method m : cm.getClass().getMethods()) {
            if ("setCmdControlParameters".equals(m.getName())) overloads.add(m);
        }
        if (overloads.isEmpty()) {
            lastError = "无 setCmdControlParameters";
            return false;
        }

        for (Method m : overloads) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 1 && pt[0].isInstance(params)) {
                if (invoke(m, cm, params)) return true;
            }
        }
        if (flatten != null) {
            for (Method m : overloads) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == int.class && pt[1] == String.class) {
                    if (invoke(m, cm, 0, flatten)) return true;
                }
            }
            for (Method m : overloads) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && pt[0] == String.class) {
                    if (invoke(m, cm, flatten)) return true;
                }
            }
        }
        for (Method m : overloads) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 2 && pt[0] == int.class && pt[1].isInstance(params)) {
                if (invoke(m, cm, 0, params)) return true;
            }
        }
        lastError = "setCmdControlParameters 所有重载均失败";
        return false;
    }

    private static boolean invoke(Method m, Object target, Object... args) {
        try {
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 读当前 display-mode；读不到返回 -1。
     *
     * <p>真机上参数对象的 getter（getAvmDisplayMode）和 flatten() 都会抛异常，
     * 但内部 {@code LinkedHashMap mMap} 可读。所以按「getter → flatten → mMap」三级读，
     * 任一级拿到就用。mMap 里键名就是 {@code display-mode}（dump 已确认）。</p>
     */
    public static int current(CameraManager cm) {
        Object params = getParams(cm);
        if (params == null) return -1;
        // ① 命名 getter
        int v = callGetterInt(params, "getAvmDisplayMode");
        if (v >= 0) return v;
        // ② flatten 字符串正则
        try {
            Object flat = params.getClass().getMethod("flatten").invoke(params);
            if (flat instanceof String) {
                Matcher m = DISPLAY_MODE.matcher((String) flat);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) { }
        // ③ 直读 mMap（本车唯一可靠的一级）
        return fromMap(params, "display-mode");
    }

    /** 调无参 int getter；失败返回 -1。 */
    private static int callGetterInt(Object params, String getter) {
        try {
            Object r = params.getClass().getMethod(getter).invoke(params);
            if (r instanceof Number) return ((Number) r).intValue();
        } catch (Throwable ignored) { }
        return -1;
    }

    /** 从参数对象内部 LinkedHashMap 里按字符串键读整数值。 */
    private static int fromMap(Object params, String key) {
        try {
            java.lang.reflect.Field mf = params.getClass().getDeclaredField("mMap");
            mf.setAccessible(true);
            Object map = mf.get(params);
            if (map instanceof java.util.Map) {
                Object v = ((java.util.Map<?, ?>) map).get(key);
                if (v == null) v = ((java.util.Map<?, ?>) map).get(key.getBytes());
                if (v instanceof Number) return ((Number) v).intValue();
                if (v instanceof String) return Integer.parseInt((String) v);
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    /**
     * dump 一份参数原文。只在排查时用：不同固件里键名可能不是 display-mode，
     * 拿原文才能确认到底该怎么写。
     */
    public static String dump(CameraManager cm) {
        Object params = getParams(cm);
        if (params == null) return "params=null err=" + lastError;
        StringBuilder sb = new StringBuilder();
        Class<?> cls = params.getClass();
        sb.append("class=").append(cls.getName());

        // flatten 在本车固件上会抛 InvocationTargetException，所以原文只能靠反射字段拿。
        // 把字段名/类型/当前值全打出来，才能知道"视角"到底存在哪个键上。
        try {
            java.lang.reflect.Field[] fs = cls.getDeclaredFields();
            sb.append(" fields=").append(fs.length).append(" [");
            for (java.lang.reflect.Field f : fs) {
                sb.append(f.getType().getSimpleName()).append(' ').append(f.getName());
                try {
                    f.setAccessible(true);
                    Object v = f.get(params);
                    String vs = String.valueOf(v);
                    if (vs.length() > 120) vs = vs.substring(0, 120) + "...";
                    sb.append('=').append(vs);
                } catch (Throwable t) {
                    sb.append("=<不可读>");
                }
                sb.append("; ");
            }
            sb.append(']');
        } catch (Throwable t) {
            sb.append(" fields 失败: ").append(t);
        }
        try {
            StringBuilder ms = new StringBuilder();
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.contains("avm") || n.contains("Avm") || n.contains("display")
                        || n.contains("Display") || n.contains("put") || n.contains("set")) {
                    ms.append(n).append('/').append(m.getParameterTypes().length).append(' ');
                }
            }
            sb.append(" methods=[").append(ms).append(']');
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /**
     * 写入 display-mode 并下发。成功返回 true。
     *
     * <p>V5.10.0.8814 真机定论（对照能出鱼眼的 P7打灯转向辅助 v1.46 抓到的下发链路）：
     * 只写裸 {@code display-mode=N} 才对。1.46 下发原文就是
     * {@code params =display-mode=8}，紧接着相机服务的 {@code XpCameraCmdController:
     * setAvmControlMode()} 被触发、打印 {@code displaymode:8, Overlaywkst:-1,...}
     * （配套字段全是 -1 = 未设置），随后 connect camera 0 出四路鱼眼。</p>
     *
     * <p>反过来，之前"补齐原厂 7 字段"（overlay-workst/out-mode/transbody...）的写法
     * 在本车是毒药：下发原文变成一长串，controller 的 setAvmControlMode() 根本不被
     * 调用，相机流停在 360 三分屏。所以这里刻意保持裸字段，不要再加任何配套字段。</p>
     */
    public static boolean set(CameraManager cm, int mode) {
        Object params = getParams(cm);
        if (params == null) {
            Log.w(TAG, "set(" + mode + ") 失败：" + lastError);
            return false;
        }
        if (!writeDisplayMode(params, mode)) {
            lastError = "display-mode 三种写法均失败";
            Log.w(TAG, "set(" + mode + ") 失败：" + lastError);
            return false;
        }
        if (!setParams(cm, params)) {
            Log.w(TAG, "set(" + mode + ") 下发失败：" + lastError);
            return false;
        }
        lastError = null;
        Log.i(TAG, "display-mode=" + mode + " 裸字段下发成功（对齐 v1.46）");
        return true;
    }

    // ---- early 预置：相机打开前下发 ----
    //
    // 实车踩到的坑：普通 setCmdControlParameters 在相机还没打开时下发，CameraService 只打
    // 一行 E/setCmdControlParameters 就把参数丢了——紧接着 statusManagerResume 打印
    // avmDisplayMode = -1（没记住任何值），相机流仍是 360 三分屏。
    // 厂商在 ICameraService 里另有一个 earlysetCmdControlParameters，语义就是「下次 open
    // 时应用」，正好匹配打灯流程「先定视角、再开相机」的顺序。

    /** 相机打开前预置 display-mode；成功返回 true。 */
    public static boolean earlySet(CameraManager cm, int mode) {
        Object params = getParams(cm);
        if (params == null) {
            Log.w(TAG, "earlySet(" + mode + ") 失败：取不到参数对象 " + lastError);
            return false;
        }
        if (!writeDisplayMode(params, mode)) {
            lastError = "early display-mode 三种写法均失败";
            Log.w(TAG, "earlySet(" + mode + ") 失败：" + lastError);
            return false;
        }
        // 与 set() 同理：只写裸 display-mode（对齐 v1.46 成功链路），不补配套字段
        String flatten = null;
        try {
            flatten = (String) params.getClass().getMethod("flatten").invoke(params);
        } catch (Throwable ignored) {
            // flatten 拿不到不影响按对象下发
        }
        if (invokeEarly(cm, flatten, params)) {
            lastError = null;
            Log.i(TAG, "early display-mode=" + mode + " 预置成功");
            return true;
        }
        Log.w(TAG, "earlySet(" + mode + ") 失败：" + lastError);
        return false;
    }

    /** 在名字同时含 early 与 CmdControlParameters 的重载里，按参数形态逐个试。 */
    private static boolean invokeEarly(CameraManager cm, String flatten, Object params) {
        List<Method> cands = new ArrayList<>();
        for (Method m : cm.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if (n.contains("cmdcontrolparameters") && n.contains("early")) cands.add(m);
        }
        if (cands.isEmpty()) {
            lastError = "CameraManager 无 early*CmdControlParameters 方法";
            return false;
        }
        if (flatten != null) {
            for (Method m : cands) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 2 && pt[0] == String.class && pt[1] == String.class
                        && invoke(m, cm, "0", flatten)) return true;
            }
            for (Method m : cands) {
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 1 && pt[0] == String.class && invoke(m, cm, flatten)) return true;
            }
        }
        for (Method m : cands) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 1 && pt[0].isInstance(params) && invoke(m, cm, params)) return true;
        }
        for (Method m : cands) {
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length == 2 && pt[0] == String.class && pt[1].isInstance(params)
                    && invoke(m, cm, "0", params)) return true;
        }
        lastError = "early 所有重载均失败";
        return false;
    }

    /** 列出 CameraManager 上所有含 cmd/avm 的方法签名，排查用。 */
    public static String dumpMethods(CameraManager cm) {
        StringBuilder sb = new StringBuilder("CameraManager 指令方法:");
        for (Method m : cm.getClass().getMethods()) {
            String n = m.getName().toLowerCase();
            if (!n.contains("cmd") && !n.contains("avm")) continue;
            StringBuilder ps = new StringBuilder();
            for (Class<?> pt : m.getParameterTypes()) {
                if (ps.length() > 0) ps.append(", ");
                ps.append(pt.getSimpleName());
            }
            sb.append("\n  ").append(m.getReturnType().getSimpleName())
                    .append(' ').append(m.getName()).append('(').append(ps).append(')');
        }
        return sb.toString();
    }

    // ---- 分通道 / 工作状态写入（诊断用）----
    //
    // 参数对象里除了 display-mode 还有 channel0/channel1-display-mode 与 work-status。
    // 实车现象：只写 display-mode=8 时相机流不变（仍是 360 俯视+前视），说明真正决定
    // 「相机输出哪几路画面」的很可能是 channel 级设置，而不是总 display-mode。
    // 这三个 setter 先做成可单独调用，方便一条广播一个变量地试。

    public static boolean setChannel(CameraManager cm, int ch, int mode) {
        Object params = getParams(cm);
        if (params == null) return false;
        String mn = ch == 0 ? "setChannel0DisplayMode" : "setChannel1DisplayMode";
        String key = ch == 0 ? "channel0-display-mode" : "channel1-display-mode";
        if (!invokeInt(params, mn, key, mode)) {
            lastError = mn + " 写入失败";
            return false;
        }
        boolean ok = setParams(cm, params);
        Log.i(TAG, key + "=" + mode + (ok ? " 已下发" : " 下发失败: " + lastError));
        return ok;
    }

    public static boolean setWorkState(CameraManager cm, int v) {
        Object params = getParams(cm);
        if (params == null) return false;
        if (!invokeInt(params, "setAvmWorkState", "work-status", v)) {
            lastError = "setAvmWorkState 写入失败";
            return false;
        }
        boolean ok = setParams(cm, params);
        Log.i(TAG, "work-status=" + v + (ok ? " 已下发" : " 下发失败: " + lastError));
        return ok;
    }

    /** 先按厂商 setter 名试，再按参数键 set(String,int) 试。 */
    private static boolean invokeInt(Object params, String setter, String key, int v) {
        Class<?> cls = params.getClass();
        try {
            cls.getMethod(setter, int.class).invoke(params, v);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            cls.getMethod("set", String.class, int.class).invoke(params, key, v);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 读回三个关键值，拼成一行日志。 */
    public static String readBack(CameraManager cm) {
        Object params = getParams(cm);
        if (params == null) return "params=null";
        // getter 在本车抛异常，统一走 callGetterInt（失败 -1）→ mMap 兜底
        return "display=" + readKey(params, "getAvmDisplayMode", "display-mode")
                + " ch0=" + readKey(params, "getChannel0DisplayMode", "channel0-display-mode")
                + " ch1=" + readKey(params, "getChannel1DisplayMode", "channel1-display-mode")
                + " work=" + readKey(params, "getAvmWorkState", "work-status");
    }

    private static String readKey(Object params, String getter, String key) {
        int v = callGetterInt(params, getter);
        if (v < 0) v = fromMap(params, key);
        return v < 0 ? "?" : String.valueOf(v);
    }

    /** 三种优先级写 display-mode：setAvmDisplayMode(int) → set(String,int) → 私有 put(String,String)。 */
    private static boolean writeDisplayMode(Object params, int mode) {
        Class<?> cls = params.getClass();
        try {
            cls.getMethod("setAvmDisplayMode", int.class).invoke(params, mode);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            cls.getMethod("set", String.class, int.class).invoke(params, "display-mode", mode);
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Method m = cls.getDeclaredMethod("put", String.class, String.class);
            m.setAccessible(true);
            m.invoke(params, "display-mode", String.valueOf(mode));
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
}
