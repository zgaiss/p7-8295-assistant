package com.xiaopeng.clusterassist;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional vendor CameraManager command adapter. No Android dependency is needed here.
 * A successful command means only that the reflected API did not reject it; neither that
 * result nor a value obtained from getCmdControlParameters proves actual camera pixels.
 * Firmware-specific early/open ordering must be validated on an isolated target device.
 */
public final class AvmMode {
    public static final int MODE_3D = 4;
    public static final int MODE_QUAD = 8;
    public static final int MODE_FRONT = 16;
    public static final int MODE_360 = 21;

    private static volatile String lastError;

    private AvmMode() { }

    public static String lastError() { return lastError; }

    /** Dispatch only this key; never flatten and resend a readback object's history. */
    public static boolean set(Object cm, int mode) {
        return dispatch(cm, false, "setAvmDisplayMode", "display-mode", mode);
    }

    /** Optional API, not present in the reference repository or on all firmware. */
    public static boolean earlySet(Object cm, int mode) {
        return dispatch(cm, true, "setAvmDisplayMode", "display-mode", mode);
    }

    public static boolean setChannel(Object cm, int channel, int mode) {
        if (channel != 0 && channel != 1) {
            lastError = "invalid channel: " + channel;
            return false;
        }
        return dispatch(cm, false, channel == 0 ? "setChannel0DisplayMode" : "setChannel1DisplayMode",
                channel == 0 ? "channel0-display-mode" : "channel1-display-mode", mode);
    }

    public static boolean setWorkState(Object cm, int value) {
        return dispatch(cm, false, "setAvmWorkState", "work-status", value);
    }

    private static boolean dispatch(Object cm, boolean early, String setter, String key, int value) {
        StringBuilder errors = new StringBuilder();
        if (cm == null) {
            lastError = "CameraManager=null";
            return false;
        }
        String raw = key + "=" + value;
        List<Method> methods = new ArrayList<>();
        try {
            for (Method method : cm.getClass().getMethods()) {
                String name = method.getName();
                if ((!early && name.equals("setCmdControlParameters"))
                        || (early && name.equalsIgnoreCase("earlysetCmdControlParameters"))) {
                    methods.add(method);
                }
            }
        } catch (Throwable t) {
            lastError = "enumerate vendor methods: " + cause(t);
            return false;
        }
        // Raw strings have a known payload even when get/flatten is absent or throws.
        for (int kind = 0; kind < 3; kind++) {
            for (Method method : methods) {
                Class<?>[] types = method.getParameterTypes();
                Object[] args = null;
                if (kind == 0 && types.length == 2 && types[0] == int.class && types[1] == String.class)
                    args = new Object[]{0, raw};
                if (kind == 1 && types.length == 2 && types[0] == String.class && types[1] == String.class)
                    args = new Object[]{"0", raw};
                if (kind == 2 && types.length == 1 && types[0] == String.class)
                    args = new Object[]{raw};
                if (args != null && invoke(method, cm, errors, args)) return success();
            }
        }
        // Object-only firmware: construct and scrub a separate builder, never mutate live readback.
        boolean hasObjectOverload = false;
        for (Method method : methods) {
            Class<?>[] types = method.getParameterTypes();
            if ((types.length == 1 && !types[0].isPrimitive() && types[0] != String.class)
                    || (types.length == 2 && (types[0] == int.class || types[0] == String.class)
                    && !types[1].isPrimitive() && types[1] != String.class)) hasObjectOverload = true;
        }
        if (hasObjectOverload) {
            Object params = newParams(cm, setter, key, value, raw, errors);
            if (params != null) {
                for (int kind = 0; kind < 3; kind++) {
                    for (Method method : methods) {
                        Class<?>[] types = method.getParameterTypes();
                        Object[] args = null;
                        if (kind == 0 && types.length == 1 && types[0].isInstance(params))
                            args = new Object[]{params};
                        if (kind == 1 && types.length == 2 && types[0] == int.class && types[1].isInstance(params))
                            args = new Object[]{0, params};
                        if (kind == 2 && types.length == 2 && types[0] == String.class && types[1].isInstance(params))
                            args = new Object[]{"0", params};
                        if (args != null && invoke(method, cm, errors, args)) return success();
                    }
                }
            }
        }
        lastError = (early ? "early" : "set") + " rejected/unsupported: " + raw
                + (errors.length() == 0 ? "; no compatible overload" : "; " + errors);
        return false;
    }

    private static boolean success() { lastError = null; return true; }

    private static boolean accepted(Object result) {
        return !Boolean.FALSE.equals(result)
                && (!(result instanceof Number) || ((Number) result).doubleValue() >= 0);
    }

    private static boolean invoke(Method method, Object target, StringBuilder errors, Object... args) {
        try {
            method.setAccessible(true);
            Object result = method.invoke(target, args);
            if (accepted(result)) return true;
            append(errors, method.toGenericString() + " returned " + result);
        } catch (Throwable t) {
            append(errors, method.toGenericString() + ": " + cause(t));
        }
        return false;
    }

    private static void append(StringBuilder errors, String message) {
        if (errors.length() > 0) errors.append("; ");
        errors.append(message);
    }

    private static String cause(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        return t.toString();
    }

    private static Object getParams(Object cm) throws Exception {
        if (cm == null) throw new IllegalArgumentException("CameraManager=null");
        Method method = cm.getClass().getMethod("getCmdControlParameters");
        method.setAccessible(true);
        Object params = method.invoke(cm);
        if (params == null) throw new IllegalStateException("getCmdControlParameters returned null");
        return params;
    }

    private static Object newParams(Object cm, String setter, String key, int value,
                                    String raw, StringBuilder errors) {
        try {
            Object live = getParams(cm);
            Class<?> cls = live.getClass();
            Object fresh;
            try {
                Constructor<?> constructor = cls.getDeclaredConstructor();
                constructor.setAccessible(true);
                fresh = constructor.newInstance();
            } catch (NoSuchMethodException noDefault) {
                // A non-static framework inner builder may require the CameraManager owner.
                Class<?> enclosing = cls.getEnclosingClass();
                if (enclosing == null || !enclosing.isInstance(cm)) throw noDefault;
                Constructor<?> constructor = cls.getDeclaredConstructor(enclosing);
                constructor.setAccessible(true);
                fresh = constructor.newInstance(cm);
            }
            Map<?, ?> map = map(fresh);
            if (map != null) {
                // A constructor may share a static default map. Never clear a live alias.
                if (map == map(live)) throw new IllegalStateException("new builder shares readback mMap");
                map.clear();
            }
            if (!writeInt(fresh, setter, key, value, errors)) return null;
            // Prefer flatten as a complete serialization check. If unavailable, require an
            // independently constructed, scrubbed map with exactly the requested key/value.
            try {
                Method flatten = cls.getMethod("flatten");
                flatten.setAccessible(true);
                Object serialized = flatten.invoke(fresh);
                if (!(serialized instanceof String) || !onlyRaw((String) serialized, raw))
                    throw new IllegalStateException("builder contains unrequested fields: " + serialized);
            } catch (NoSuchMethodException | InvocationTargetException unavailable) {
                if (map == null || map.size() != 1 || !map.containsKey(key)
                        || !String.valueOf(value).equals(String.valueOf(map.get(key))))
                    throw new IllegalStateException("cannot verify isolated builder payload", unavailable);
            }
            return fresh;
        } catch (Throwable t) {
            append(errors, "isolated params: " + cause(t));
            return null;
        }
    }

    private static boolean onlyRaw(String flat, String raw) {
        return flat.equals(raw) || flat.equals(raw + ";");
    }

    private static boolean writeInt(Object params, String setter, String key, int value, StringBuilder errors) {
        Class<?> cls = params.getClass();
        try {
            if (invoke(cls.getMethod(setter, int.class), params, errors, value)) return true;
        } catch (NoSuchMethodException ignored) { }
        try {
            if (invoke(cls.getMethod("set", String.class, int.class), params, errors, key, value)) return true;
        } catch (NoSuchMethodException ignored) { }
        for (Class<?> type = cls; type != null; type = type.getSuperclass()) {
            try {
                if (invoke(type.getDeclaredMethod("put", String.class, String.class), params,
                        errors, key, String.valueOf(value))) return true;
            } catch (NoSuchMethodException ignored) { }
        }
        append(errors, "no accepted writer for " + key);
        return false;
    }

    private static Map<?, ?> map(Object params) throws IllegalAccessException {
        for (Class<?> type = params.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField("mMap");
                if (Modifier.isStatic(field.getModifiers())) return null;
                field.setAccessible(true);
                Object value = field.get(params);
                return value instanceof Map ? (Map<?, ?>) value : null;
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    /** Getter -> flatten -> mMap; -1 means unknown, not an original factory default.
     * The vendor object may be cached: readback is not proof of HAL/pixel application. */
    public static int current(Object cm) {
        try {
            int result = readInt(getParams(cm), "getAvmDisplayMode", "display-mode");
            if (result < 0) lastError = "display-mode readback unavailable";
            return result;
        } catch (Throwable t) {
            lastError = "read display-mode: " + cause(t);
            return -1;
        }
    }

    private static int nonnegative(Object value) {
        try {
            int result = Integer.parseInt(String.valueOf(value));
            return result >= 0 ? result : -1;
        } catch (RuntimeException ignored) { return -1; }
    }

    private static int readInt(Object params, String getter, String key) {
        try {
            Method method = params.getClass().getMethod(getter);
            method.setAccessible(true);
            int value = nonnegative(method.invoke(params));
            if (value >= 0) return value;
        } catch (Throwable ignored) { }
        try {
            Method method = params.getClass().getMethod("flatten");
            method.setAccessible(true);
            Object value = method.invoke(params);
            if (value instanceof String) {
                Matcher matcher = Pattern.compile("(?:^|;)" + Pattern.quote(key)
                        + "=(-?\\d+)(?=;|$)").matcher((String) value);
                if (matcher.find()) {
                    int parsed = nonnegative(matcher.group(1));
                    if (parsed >= 0) return parsed;
                }
            }
        } catch (Throwable ignored) { }
        try {
            Map<?, ?> values = map(params);
            return values == null ? -1 : nonnegative(values.get(key));
        } catch (Throwable ignored) { return -1; }
    }

    public static String readBack(Object cm) {
        try {
            Object params = getParams(cm);
            return "display=" + readInt(params, "getAvmDisplayMode", "display-mode")
                    + " ch0=" + readInt(params, "getChannel0DisplayMode", "channel0-display-mode")
                    + " ch1=" + readInt(params, "getChannel1DisplayMode", "channel1-display-mode")
                    + " work=" + readInt(params, "getAvmWorkState", "work-status");
        } catch (Throwable t) { return "params unavailable: " + cause(t); }
    }

    /** Only command parameters, not arbitrary framework fields, are included in diagnostics. */
    public static String dump(Object cm) {
        try {
            Object params = getParams(cm);
            return "class=" + params.getClass().getName() + " mMap=" + map(params) + " " + readBack(cm);
        } catch (Throwable t) { return "params unavailable: " + cause(t); }
    }

    public static String dumpMethods(Object cm) {
        if (cm == null) return "CameraManager=null";
        StringBuilder result = new StringBuilder("CameraManager command methods:");
        for (Method method : cm.getClass().getMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("cmd") || name.contains("avm")) result.append('\n').append(method.toGenericString());
        }
        return result.toString();
    }
}
