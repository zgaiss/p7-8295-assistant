package com.xiaopeng.clusterassist;

import android.app.UiModeManager;
import android.content.Context;
import android.util.Log;

import java.util.Calendar;

/**
 * 统一昼夜判断：车机 App 自己的 UI 配色和推给仪表的 night 字段必须用同一套逻辑。
 *
 * 为什么不能直接看 Configuration.uiMode：
 * 这台车机的昼夜切换并不总是走标准的 UiMode 配置变更，App 界面会停在启动时的配色。
 * 而 `persist.sys.theme` 只在用户**锁定**白天/夜间时被写入，切回「自动」后它会停留在
 * 上一次锁定的值（实测：锁定夜间 → 切回自动仍是 2）。之前仪表只看它，结果白天中控
 * 已经变白、仪表还在画夜间配色。`last_override_night_mode` 和
 * `ui_night_mode_last_computed` 实测全程不动，也是死值。
 *
 * 现在按"用户意图 + 天文时间"两级判断：
 *   1. UiModeManager.getNightMode()：1=锁定白天 / 2=锁定夜间 → 直接采用（最准）；
 *   2. 0=自动 → 按经纬度算日出日落（车机存着 persist.sys.theme.latitude/longitude）；
 *   3. 兜底：6:00–18:00 为白天。
 *
 * 判断结果通过 logcat 的 P7Night tag 输出，实车核对时直接抓：
 *   adb logcat -s P7Night
 */
public final class NightMode {
    private NightMode() { }

    private static final String TAG = "P7Night";
    private static String lastDiag;

    private static void diag(String s) {
        if (!s.equals(lastDiag)) {
            lastDiag = s;
            Log.i(TAG, s);
        }
    }

    public static boolean isNight(Context c) {
        int mode = -1;
        try {
            UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            if (um != null) mode = um.getNightMode();
        } catch (Throwable ignored) { }
        if (mode == UiModeManager.MODE_NIGHT_YES) { diag("锁定夜间"); return true; }
        if (mode == UiModeManager.MODE_NIGHT_NO) { diag("锁定白天"); return false; }

        Boolean bySun = nightBySun();
        if (bySun != null) return bySun.booleanValue();

        diag("无有效经纬度，回落 6:00-18:00");
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h < 6 || h >= 18;
    }

    /** 自动档：按经纬度算日出日落；经纬度不可用返回 null */
    private static Boolean nightBySun() {
        double lat = numProp("persist.sys.theme.latitude");
        double lon = numProp("persist.sys.theme.longitude");
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
        Calendar now = Calendar.getInstance();
        double rise = sunEvent(now, lat, lon, true);
        double set = sunEvent(now, lat, lon, false);
        if (Double.isNaN(rise) || Double.isNaN(set)) return null;
        double h = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0;
        boolean day = h >= rise && h < set;
        diag(String.format(java.util.Locale.US,
                "自动：日出 %.2f 日落 %.2f 现在 %.2f → %s", rise, set, h, day ? "白天" : "夜间"));
        return Boolean.valueOf(!day);
    }

    private static double numProp(String k) {
        try {
            String v = getSystemProperty(k);
            return v == null || v.trim().isEmpty() ? Double.NaN : Double.parseDouble(v.trim());
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** NOAA 简化算法：返回本地小时（0–24）；极昼/极夜返回 NaN */
    private static double sunEvent(Calendar day, double lat, double lon, boolean isRise) {
        int n = day.get(Calendar.DAY_OF_YEAR);
        double tzH = day.getTimeZone().getOffset(day.getTimeInMillis()) / 3600000.0;
        double lngHour = lon / 15.0;
        double t = n + ((isRise ? 6 : 18) - lngHour) / 24.0;
        double M = 0.9856 * t - 3.289;
        double L = M + 1.916 * Math.sin(Math.toRadians(M)) + 0.020 * Math.sin(Math.toRadians(2 * M)) + 282.634;
        L = ((L % 360) + 360) % 360;
        double ra = Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(L))));
        ra = ((ra % 360) + 360) % 360;
        ra += (Math.floor(L / 90) * 90) - (Math.floor(ra / 90) * 90);
        ra /= 15.0;
        double sinDec = 0.39782 * Math.sin(Math.toRadians(L));
        double cosDec = Math.cos(Math.asin(sinDec));
        double zenith = Math.toRadians(90.833);
        double cosH = (Math.cos(zenith) - sinDec * Math.sin(Math.toRadians(lat)))
                / (cosDec * Math.cos(Math.toRadians(lat)));
        if (cosH > 1 || cosH < -1) return Double.NaN; // 极昼/极夜
        double H = isRise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        H /= 15.0;
        double T = H + ra - 0.06571 * t - 6.622;
        double utc = ((T - lngHour) % 24 + 24) % 24;
        return ((utc + tzH) % 24 + 24) % 24;
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method m = c.getMethod("get", String.class);
            Object v = m.invoke(null, key);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
