package com.xiaopeng.clusterassist;

import android.util.Log;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 导航数据来源：车机导航应用（com.xiaopeng.montecarlo）通过 logcat 输出路况信息。
 *
 * 参考实现（v1.1.0）用 shell 身份跑：
 *   logcat -v epoch -d -t 400 NAV_XUINavigationInfoUpload:I NAV_BaseGuidePanel:I *:S
 * 然后从每行里识别四种事件：
 *   updateNaviStop           停止导航 → 清空
 *   updateNaviStatus         导航状态，含 "naviStatus":N，N>0 表示在导航
 *   updateInfo remain:xxx    面板文本，含 "x.x公里 / y分钟 / HH:MM到"
 *   updateNaviInfo data={..} JSON，navi.routeRemainDist / routeRemainTime / routeRemainTimeHourMinuteStr
 *
 * 注意：普通应用读不到系统日志（logd 按 uid 过滤），所以这里必须走 shell，
 * 也就是依赖 {@link LocalAdb} 建立的本地 adb 通道。
 */
public final class NavSource {

    private static final String TAG = "P7Nav";

    /** logcat 里导航事件所在的 tag */
    private static final String CMD =
            "/system/bin/logcat -v epoch -d -t 4000"
                    + " -s NAV_XUINavigationInfoUpload NAV_BaseGuidePanel";

    private static final Pattern P_EPOCH = Pattern.compile("[0-9]+\\.[0-9]+");
    private static final Pattern P_STATUS = Pattern.compile("\"naviStatus\":(\\d+)");
    private static final Pattern P_DIST = Pattern.compile("([0-9]+(?:\\.[0-9])?)公里|([0-9]{1,4})米");
    private static final Pattern P_TIME = Pattern.compile("(?:(\\d{1,2})小时)?(\\d{1,3})分钟");
    private static final Pattern P_ARRIVE = Pattern.compile("(\\d{1,2}):(\\d{2})到");
    private static final Pattern P_HM = Pattern.compile("(\\d{1,2}):(\\d{2})");

    /** 最近一次有效的导航数据 */
    public static volatile double distM = -1;
    public static volatile double timeS = -1;
    public static volatile String arrive = "";
    public static volatile long at = 0;
    /** 最近一次判定「未在导航」的时刻，避免刚停车时残留旧数据 */
    private static volatile long stoppedAt = 0;

    private NavSource() {
    }

    public static boolean fresh() {
        long a = at;
        return a > 0 && System.currentTimeMillis() - a < 45_000 && distM >= 0 && timeS >= 0;
    }

    public static void clear() {
        distM = -1;
        timeS = -1;
        arrive = "";
        at = 0;
    }

    /** 拉一次日志并解析；成功（无论有无导航）返回 true */
    public static boolean poll(AdbClient adb) {
        if (adb == null) return false;
        String out;
        try {
            out = adb.shell(CMD, 9000);
        } catch (Throwable t) {
            Log.w(TAG, "读导航日志失败: " + t.getMessage());
            return false;
        }
        if (out == null) return false;
        // 空输出说明当前没有导航事件：可能是没开导航，也可能是日志已经滚过去了。
        // 这里不清空数据，交给 fresh() 的超时判定，避免路口停顿时卡片闪烁。
        parseLines(out);
        return true;
    }

    private static void parseLines(String out) {
        String[] lines = out.split("\n");
        boolean changed = false;
        double dDist = -1, dTime = -1;
        String dArrive = "";
        boolean hasData = false;
        boolean stop = false;

        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            long ep = epochMs(line);

            if (line.contains("updateNaviStop")) {
                stop = true;
                hasData = false;
                dDist = -1;
                dTime = -1;
                dArrive = "";
                continue;
            }
            if (line.contains("updateNaviStatus")) {
                Matcher m = P_STATUS.matcher(line);
                if (m.find()) {
                    try {
                        int st = Integer.parseInt(m.group(1));
                        if (st > 0) {
                            // 导航开始：清掉之前的停止标记
                            stoppedAt = 0;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                continue;
            }
            int i = line.indexOf("updateInfo remain:");
            if (i >= 0) {
                String tail = line.substring(i + "updateInfo remain:".length());
                double[] dt = parsePanel(tail);
                if (dt[0] >= 0) {
                    dDist = dt[0];
                    hasData = true;
                }
                if (dt[1] >= 0) {
                    dTime = dt[1];
                    hasData = true;
                }
                if (dt[2] >= 0) {
                    dArrive = fmtArrive((int) dt[2], (int) dt[3]);
                }
                continue;
            }
            i = line.indexOf("updateNaviInfo data=");
            if (i >= 0) {
                String json = line.substring(i + "updateNaviInfo data=".length()).trim();
                double[] dt = parseJson(json);
                if (dt[0] >= 0) {
                    dDist = dt[0];
                    hasData = true;
                }
                if (dt[1] >= 0) {
                    dTime = dt[1];
                    hasData = true;
                }
                if (dt[2] >= 0) {
                    dArrive = fmtArrive((int) dt[2], (int) dt[3]);
                }
            }
        }

        if (stop) {
            stoppedAt = System.currentTimeMillis();
            long ago = at;
            clear();
            Log.i(TAG, "导航结束（之前 at=" + ago + "）");
            return;
        }
        if (hasData) {
            long now = System.currentTimeMillis();
            if (now >= stoppedAt) {          // 停止之后又来了新数据 → 认为重新导航
                if (dDist >= 0) distM = dDist;
                if (dTime >= 0) timeS = dTime;
                if (!dArrive.isEmpty()) arrive = dArrive;
                at = now;
                changed = true;
            }
        }
        if (changed) {
            Log.i(TAG, String.format(java.util.Locale.ROOT,
                    "导航 dist=%.0fm time=%.0fs arrive=%s", distM, timeS, arrive));
        }
    }

    /** 取行首的时间戳（logcat -v epoch 输出 "1757756000.123"），毫秒 */
    private static long epochMs(String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0) return 0;
        if (!P_EPOCH.matcher(parts[0]).matches()) return 0;
        try {
            return (long) (Double.parseDouble(parts[0]) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析面板文本，返回 {距离m, 时间s, 到达时, 到达分}，未知项为 -1。
     */
    private static double[] parsePanel(String text) {
        double[] r = {-1, -1, -1, -1};
        Matcher md = P_DIST.matcher(text);
        if (md.find()) {
            if (md.group(1) != null) {
                r[0] = Double.parseDouble(md.group(1)) * 1000;
            } else if (md.group(2) != null) {
                r[0] = Double.parseDouble(md.group(2));
            }
        }
        Matcher mt = P_TIME.matcher(text);
        if (mt.find()) {
            double h = mt.group(1) != null ? Double.parseDouble(mt.group(1)) : 0;
            double m = mt.group(2) != null ? Double.parseDouble(mt.group(2)) : 0;
            r[1] = h * 3600 + m * 60;
        }
        Matcher ma = P_ARRIVE.matcher(text);
        if (ma.find()) {
            r[2] = Double.parseDouble(ma.group(1));
            r[3] = Double.parseDouble(ma.group(2));
        }
        return r;
    }

    /** 解析 updateNaviInfo 的 JSON，返回 {距离m, 时间s, 到达时, 到达分} */
    private static double[] parseJson(String json) {
        double[] r = {-1, -1, -1, -1};
        try {
            JSONObject root = new JSONObject(json);
            JSONObject navi = root.getJSONObject("navi");
            double dist = navi.optDouble("routeRemainDist", Double.NaN);
            double time = navi.optDouble("routeRemainTime", Double.NaN);
            String hms = navi.optString("routeRemainTimeHourMinuteStr", "");
            if (!Double.isNaN(dist) && dist >= 0 && dist < 2_000_000) {
                r[0] = dist;
            }
            if (!Double.isNaN(time) && time >= 0 && time < 86400) {
                r[1] = time;
            }
            // 实测这台车给的是 "上午12:02" / "下午3:40" 这种带前缀的形式，
            // 所以不能整串 matches，只能从里找出 HH:MM
            if (hms != null) {
                Matcher m = P_HM.matcher(hms);
                if (m.find()) {
                    r[2] = Double.parseDouble(m.group(1));
                    r[3] = Double.parseDouble(m.group(2));
                }
            }        } catch (Throwable ignored) {
            // 结构和预期不同就当作没数据
        }
        return r;
    }

    private static String fmtArrive(int h, int m) {
        if (h < 0 || m < 0) return "";
        return String.format(java.util.Locale.ROOT, "%02d:%02d", h, m);
    }
}
