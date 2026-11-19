package com.xiaopeng.clusterassist;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 车机端设置：全部落在 SharedPreferences，序列化成仪表 /data/p7assist/config.json。
 * 开关位与仪表端 mask 一致（见 README），方便排障。
 */
public class Prefs {
    public static final String SP = "p7native";

    public boolean master = true;
    public boolean tpmsP = true;   // 胎压 / 胎温（P 档）
    public boolean tpmsD = true;   // 胎压 / 胎温（D 档）
    public boolean volt = false;
    public boolean cabin = false;
    public boolean bsd = true;
    /** 胎压数据源：false=仪表总线（0.1 bar，默认）；true=车机 CarProperty（kPa，0.01 bar） */
    public boolean tpmsHi = false;
    public boolean nav = true;
    /** 歌词：从车机 MediaSession 的 lyricInfo 里取，推给仪表显示 */
    public boolean lyric = true;
    /** 红绿灯读秒：从「红绿灯领航」(com.xiaopeng.light) 的 SDK 日志里取相位，推给仪表画在胶囊位 */
    public boolean tl = true;
    /** 调试：不读真实日志，本地循环造红/绿/黄相位，停车时也能看版式与读秒 */
    public boolean tlDemo = false;
    /** 歌词改到仪表底部中央（原行程行位置）：只显示两行、不显示歌名，且行程行让位 */
    public boolean lyricBottom = false;
    /** 歌词块背景卡片：颜色跟随车机中控播放条底色，昼夜自动切换 */
    public boolean lyricBg = false;
    /** 暂停播放时整块隐藏歌词（默认关：暂停后停在最后一句） */
    public boolean lyricHideOnPause = false;
    /** 左上角歌词块内文字居中排布（默认左对齐） */
    public boolean lyricCenter = false;
    /** 锁定歌词位置：锁定后画布上无法拖动，防止翻设置页时误触改位置。纯车机端状态，不下发仪表 */
    public boolean lyricLock = false;
    /** 充电时用整屏充电页替代原厂充电界面 */
    public boolean chargePage = false;
    /** 充电页调试：驻车/未插枪也强制显示充电页，用演示数值 */
    public boolean chargeDebug = false;
    /** 警示条预览：把 16 条充电警示轮播一遍，仅调试用 */
    public boolean chargeWarnPreview = false;
    /** 8 态轮播：每 6 秒换一个充电界面态，仅调试用 */
    public boolean chargeStatePreview = false;
    /**
     * 弹窗避让：仪表出现原厂弹窗（ACC/空调/音量/N 档/车门/雷达）时，按侧隐藏自绘卡片。
     * 检测器在车机侧读 HAL 日志，车机屏本身什么都不改；关掉后仪表不再隐藏任何卡片。
     */
    public boolean popupHide = true;
    public boolean tripDist = true;
    public boolean tripTime = true;
    public boolean tripSpeed = true;
    public boolean tripKwh = true;
    public boolean debug = false;

    public float zoomSpeed = 60f;
    public float zoomMs = 4000f;
    public String dayNight = "auto";   // auto | day | night
    public float scale = 1.0f;
    public int dx = 0;
    public int dy = 0;
    /** 左上角歌词块拖动位置（仪表绝对像素偏移，0/0=原左上角）。仅「歌词显示在下方」关闭时生效。 */
    public int lyricPosX = 0;
    public int lyricPosY = 0;
    /** 左上角歌词块整体缩放，1.0 = 原始；仪表端与预览都按这个比例算包围盒 */
    public float lyricScale = 1f;
    public int extTtl = 120000;

    // 仪表连接
    public String host = "172.20.1.40";
    public int port = 2221;
    public String user = "root";
    public String pass = "";

    // 数据桥
    public boolean bridge = true;      // 是否把电压/导航/日夜推到仪表

    public static Prefs load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        Prefs p = new Prefs();
        p.master = sp.getBoolean("master", true);
        p.tpmsP = sp.getBoolean("tpmsP", true);
        p.tpmsD = sp.getBoolean("tpmsD", true);
        p.volt = sp.getBoolean("volt", false);
        // 电压原来读不到（普通 uid 被 CarPermissionManager 拒），一直是关的；
        // 现在本地 adb 通道已打通（实测能读到 14.x V），所以再做一次一次性迁移：
        // 只有从没打开过的机器会被置为开，用户之后手动关掉仍然生效。
        if (!sp.getBoolean("voltOnAdb", false)) {
            p.volt = true;
        }
        p.cabin = sp.getBoolean("cabin", false);
        p.bsd = sp.getBoolean("bsd", true);
        p.tpmsHi = sp.getBoolean("tpmsHi", false);
        p.nav = sp.getBoolean("nav", true);
        p.lyric = sp.getBoolean("lyric", true);
        p.tl = sp.getBoolean("tl", true);
        p.tlDemo = sp.getBoolean("tlDemo", false);
        p.lyricBottom = sp.getBoolean("lyricBottom", false);
        p.lyricBg = sp.getBoolean("lyricBg", false);
        p.lyricHideOnPause = sp.getBoolean("lyricHideOnPause", false);
        p.lyricCenter = sp.getBoolean("lyricCenter", false);
        p.lyricLock = sp.getBoolean("lyricLock", false);
        p.chargePage = sp.getBoolean("chargePage", false);
        p.chargeDebug = sp.getBoolean("chargeDebug", false);
        p.chargeWarnPreview = sp.getBoolean("chargeWarnPreview", false);
        p.chargeStatePreview = sp.getBoolean("chargeStatePreview", false);
        p.popupHide = sp.getBoolean("popupHide", true);
        p.tripDist = sp.getBoolean("tripDist", true);
        p.tripTime = sp.getBoolean("tripTime", true);
        p.tripSpeed = sp.getBoolean("tripSpeed", true);
        p.tripKwh = sp.getBoolean("tripKwh", true);
        p.debug = sp.getBoolean("debug", false);
        p.zoomSpeed = sp.getFloat("zoomSpeed", 60f);
        p.zoomMs = sp.getFloat("zoomMs", 4000f);
        p.dayNight = sp.getString("dayNight", "auto");
        p.scale = sp.getFloat("scale", 1f);
        p.dx = sp.getInt("dx", 0);
        p.dy = sp.getInt("dy", 0);
        p.lyricPosX = sp.getInt("lyricPosX", 0);
        p.lyricPosY = sp.getInt("lyricPosY", 0);
        p.lyricScale = sp.getFloat("lyricScale", 1f);
        p.extTtl = Math.max(120000, sp.getInt("extTtl", 120000));
        p.host = sp.getString("host", "172.20.1.40");
        p.port = sp.getInt("port", 2221);
        p.user = sp.getString("user", "root");
        p.pass = sp.getString("pass", "");
        p.bridge = sp.getBoolean("bridge", true);
        return p;
    }

    public void save(Context c) {
        SharedPreferences.Editor e = c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit();
        e.putBoolean("master", master);
        e.putBoolean("tpmsP", tpmsP);
        e.putBoolean("tpmsD", tpmsD);
        e.putBoolean("volt", volt);
        e.putBoolean("voltDefaultOn", true);
        e.putBoolean("voltOnAdb", true);
        e.putBoolean("cabin", cabin);
        e.putBoolean("bsd", bsd);
        e.putBoolean("tpmsHi", tpmsHi);
        e.putBoolean("nav", nav);
        e.putBoolean("lyric", lyric);
        e.putBoolean("tl", tl);
        e.putBoolean("tlDemo", tlDemo);
        e.putBoolean("lyricBottom", lyricBottom);
        e.putBoolean("lyricBg", lyricBg);
        e.putBoolean("lyricHideOnPause", lyricHideOnPause);
        e.putBoolean("lyricCenter", lyricCenter);
        e.putBoolean("lyricLock", lyricLock);
        e.putBoolean("chargePage", chargePage);
        e.putBoolean("chargeDebug", chargeDebug);
        e.putBoolean("chargeWarnPreview", chargeWarnPreview);
        e.putBoolean("chargeStatePreview", chargeStatePreview);
        e.putBoolean("popupHide", popupHide);
        e.putBoolean("tripDist", tripDist);
        e.putBoolean("tripTime", tripTime);
        e.putBoolean("tripSpeed", tripSpeed);
        e.putBoolean("tripKwh", tripKwh);
        e.putBoolean("debug", debug);
        e.putFloat("zoomSpeed", zoomSpeed);
        e.putFloat("zoomMs", zoomMs);
        e.putString("dayNight", dayNight);
        e.putFloat("scale", scale);
        e.putInt("dx", dx);
        e.putInt("dy", dy);
        e.putInt("lyricPosX", lyricPosX);
        e.putInt("lyricPosY", lyricPosY);
        e.putFloat("lyricScale", lyricScale);
        e.putInt("extTtl", extTtl);
        e.putString("host", host);
        e.putInt("port", port);
        e.putString("user", user);
        e.putString("pass", pass);
        e.putBoolean("bridge", bridge);
        e.apply();
    }

    public Prefs copy() {
        Prefs p = new Prefs();
        p.master = master; p.tpmsP = tpmsP; p.tpmsD = tpmsD; p.volt = volt; p.cabin = cabin;
        p.bsd = bsd; p.nav = nav; p.lyric = lyric; p.lyricBottom = lyricBottom; p.lyricBg = lyricBg; p.lyricHideOnPause = lyricHideOnPause; p.lyricCenter = lyricCenter; p.lyricLock = lyricLock; p.chargePage = chargePage; p.chargeDebug = chargeDebug; p.chargeWarnPreview = chargeWarnPreview; p.chargeStatePreview = chargeStatePreview; p.tl = tl;
        p.tlDemo = tlDemo;
        p.tpmsHi = tpmsHi; p.tripDist = tripDist; p.tripTime = tripTime;
        p.popupHide = popupHide;
        p.tripSpeed = tripSpeed; p.tripKwh = tripKwh; p.debug = debug;
        p.zoomSpeed = zoomSpeed; p.zoomMs = zoomMs; p.dayNight = dayNight;
        p.scale = scale; p.dx = dx; p.dy = dy;
        p.lyricPosX = lyricPosX; p.lyricPosY = lyricPosY; p.lyricScale = lyricScale;
        p.extTtl = extTtl;
        p.host = host; p.port = port; p.user = user; p.pass = pass; p.bridge = bridge;
        return p;
    }

    public int mask() {
        int m = 0;
        if (volt) m |= 1;
        if (tpmsP) m |= 2;
        if (bsd) m |= 4;
        if (debug) m |= 8;
        if (cabin) m |= 16;
        if (tripDist) m |= 32;
        if (tripTime) m |= 64;
        if (tripSpeed) m |= 128;
        if (tripKwh) m |= 256;
        if (tpmsD) m |= 512;
        if (nav) m |= 1024;
        if (tl) m |= 8192;
        if (lyricBg) m |= 16384;
        if (lyricHideOnPause) m |= 32768;
        if (lyricCenter) m |= 65536;
        if (chargePage) m |= 131072;
        if (chargeDebug) m |= 262144;
        if (chargeWarnPreview) m |= 524288;
        if (chargeStatePreview) m |= 1048576;
        if (popupHide) m |= 2097152;
        return m;
    }

    /** 生成仪表端 config.json */
    public String toJson() {
        return "{"
                + "\"version\":1,"
                + "\"bsdSrc\":\"car\","
                + "\"enabled\":" + master + ","
                + "\"features\":{"
                + "\"tpmsP\":" + tpmsP + ","
                + "\"tpmsD\":" + tpmsD + ","
                + "\"volt\":" + volt + ","
                + "\"cabin\":" + cabin + ","
                + "\"bsd\":" + bsd + ","
                + "\"nav\":" + nav + ","
                + "\"lyric\":" + lyric + ","
                + "\"tl\":" + tl + ","
                + "\"lyricBottom\":" + lyricBottom + ","
                + "\"lyricBg\":" + lyricBg + ","
                + "\"lyricHideOnPause\":" + lyricHideOnPause + ","
                + "\"lyricCenter\":" + lyricCenter + ","
                + "\"chargePage\":" + chargePage + ","
                + "\"chargeDebug\":" + chargeDebug + ","
                + "\"chargeWarnPreview\":" + chargeWarnPreview + ","
                + "\"chargeStatePreview\":" + chargeStatePreview + ","
                + "\"popupHide\":" + popupHide + ","
                + "\"trip\":{\"dist\":" + tripDist + ",\"time\":" + tripTime
                + ",\"speed\":" + tripSpeed + ",\"kwh\":" + tripKwh + "},"
                + "\"debug\":" + debug + "},"
                + "\"zoom\":{\"speed\":" + fmt(zoomSpeed) + ",\"ms\":" + fmt(zoomMs) + "},"
                + "\"dayNight\":\"" + dayNight + "\","
                + "\"layout\":{\"dx\":" + dx + ",\"dy\":" + dy + ",\"scale\":" + fmt(scale) + "},"
                + "\"lyricPos\":{\"dx\":" + lyricPosX + ",\"dy\":" + lyricPosY
                + ",\"scale\":" + String.format(java.util.Locale.ROOT, "%.2f", lyricScale) + "},"
                + "\"ext\":{\"ttlMs\":" + extTtl + "}"
                + "}";
    }

    private static String fmt(float f) {
        if (f == Math.round(f)) return String.valueOf((int) f);
        return String.format(java.util.Locale.ROOT, "%.1f", f);
    }
}
