package com.xiaopeng.clusterassist;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 红绿灯数据来源：「红绿灯领航」(com.xiaopeng.light) 内置的原生 SDK 把每一次路口查询
 * 写进了自己的日志文件，0.5 秒一行，明文、无需登录、无需它自己的浮窗。
 *
 * <pre>
 * /sdcard/Android/data/com.xiaopeng.light/cache/log/&lt;日期&gt;_&lt;时间&gt;_&lt;设备ID&gt;.log
 *
 * GetLightInfo: ret=0, cur_tm=1794754891.859, lastreq=1794754889.599,
 *   cur_light: 114.5122400 30.5488600, ctrl=1, dist=186, spd_limit=0, pref=2, gl=50332693.
 *   phases=[0: dir=2, cur=2 1794754892151, nxt=0 0; 1: dir=1, cur=1 0, nxt=0 0; ]
 * </pre>
 *
 * 字段：dir 1=左转 2=直行 3=右转；cur/nxt 1=红 2=绿 3=黄 0=无；
 * cur/nxt 后面那个数是<b>该相位的结束时刻</b>（毫秒 Unix 时间戳，0 = 没有读秒）。
 *
 * 两个关键点：
 * <ul>
 *   <li>这个 App 跑在同一台车机上，所以日志里的 cur_tm 与 System.currentTimeMillis()
 *       是同一个时钟域 —— 可以直接算出「这一行有多旧」，也能直接用结束时刻算倒计时。
 *       不要拿仪表那边的 UTC 去对，中控是本地时区，差 8 小时。</li>
 *   <li>它自己的悬浮窗在这台车上 inflate 失败（view_window_nav 第 32 行，每 0.5 秒 NPE），
 *       我们只取数据、不碰它的界面。</li>
 * </ul>
 *
 * 普通 App 读不到别的应用的 Android/data（分区存储），必须借本地 adb 的 shell 身份，
 * 和电压/盲区一样走 {@link LocalAdb}。
 */
public final class TlSource {

    private static final String TAG = "P7TL";

    /**
     * 一次 shell 调用干三件事：找到最新的日志文件、读尾部 32KB、顺带取它的 mtime。
     * 日志一直在滚，只有尾部有意义；32KB 足够覆盖最后几十行 GetLightInfo。
     */
    private static final String CMD =
            "d=/sdcard/Android/data/com.xiaopeng.light/cache/log;"
                    + " f=$(ls -t $d/*.log 2>/dev/null | head -n 1);"
                    + " test -n \"$f\" && tail -c 32768 $f";

    /**
     * 行首：ret、cur_tm，以及可选的 dist / spd_limit。
     *
     * dist/spd_limit 必须做成可选：新版红绿灯 App 的日志已经写成
     *   GetLightInfo: ret=0, cur_tm=1794924057.651, lastreq=1794924028.371
     * 不再带这两个字段。原来正则强制要求它们，导致 h.find() 永远失败、
     * freshAt 一直是 0、active() 恒为 false —— 表现就是"开了开关但没有红绿灯"，
     * 而链路自检会误报成"我们这边没解析到"。
     */
    private static final Pattern HEAD = Pattern.compile(
            "GetLightInfo: ret=(-?\\d+), cur_tm=([\\d.]+)"
                    + "(?:.*?dist=(-?\\d+), spd_limit=(-?\\d+))?");

    /** 单个相位：槽号、方向、当前色、当前色结束时刻、下一色、下一色结束时刻 */
    private static final Pattern PHASE = Pattern.compile(
            "(\\d+):\\s*dir=(\\d+),\\s*cur=(\\d+)\\s+(\\d+),\\s*nxt=(\\d+)\\s+(\\d+)");

    /** 日志与解析时间的最大允许偏差：SDK 只在有路口查询时写日志，停着不动就不写了 */
    private static final long MAX_AGE_MS = 3000;

    private static final class Ph {
        int dir;
        int cur;
        long curEnd;
        int nxt;
        long nxtEnd;
    }

    private static volatile List<Ph> phases = new ArrayList<>();
    private static volatile int ret = 2;
    private static volatile int distM;
    private static volatile int lim;
    /** 最近一次读到「新鲜」日志的时刻；0 = 从未读到 */
    private static volatile long freshAt;
    /** 相位签名，用于判断要不要立刻重推 */
    private static volatile String lastSig = "";

    /** 调试模拟：不读真实日志，本地循环造相位。仅由 PushService 每轮按开关同步。 */
    private static volatile boolean demo = false;

    /** 模拟相位一个循环 30 秒；前 20 秒双灯（左转+直行），后 10 秒单灯（直行居中） */
    private static final long DEMO_CYCLE = 30000;

    private TlSource() {
    }

    /** 切换调试模拟；关掉时立刻清掉模拟相位，避免仪表继续画假灯 */
    public static void setDemo(boolean on) {
        if (demo == on) return;
        demo = on;
        lastSig = "";
        freshAt = 0;
        ret = 2;
        phases = new ArrayList<>();
        distM = 0;
        lim = 0;
        Log.i(TAG, "调试模拟 " + (on ? "开启" : "关闭"));
    }

    /** 是否还有有效相位（决定轮询频率：有相位 500ms，没有就放慢到 2s） */
    public static boolean active() {
        if (demo) return true;
        long f = freshAt;
        return f > 0 && System.currentTimeMillis() - f < 4000 && ret == 0;
    }

    /**
     * 拉一次日志并解析。返回 true 表示「相位变了，值得立刻推一次」。
     * 失败（adb 没通、文件读不到）返回 false，保留旧值，由 {@link #active()} 的超时兜底。
     */
    public static boolean poll(Context ctx) {
        if (demo) return pollDemo();
        String out;
        try {
            out = LocalAdb.shell(ctx, CMD, 4000);
        } catch (Throwable t) {
            Log.w(TAG, "读红绿灯日志失败: " + t.getMessage());
            return false;
        }
        if (out == null || out.isEmpty()) return false;

        int at = out.lastIndexOf("GetLightInfo:");
        if (at < 0) return false;
        String tail = out.substring(at);

        Matcher h = HEAD.matcher(tail);
        if (!h.find()) return false;

        long r = parseLong(h.group(1), -1);
        double curTm = parseDouble(h.group(2), 0) * 1000.0;
        long now = System.currentTimeMillis();
        long age = now - (long) curTm;
        // 车机时钟被改过（或这台车不在中国时区）时 age 会是负数，一并按旧数据处理
        if (r != 0 || curTm <= 0 || age > MAX_AGE_MS || age < -MAX_AGE_MS) {
            setEmpty();
            return changed();
        }

        List<Ph> list = new ArrayList<>();
        Matcher m = PHASE.matcher(tail);
        while (m.find()) {
            int dir = (int) parseLong(m.group(2), 0);
            int cur = (int) parseLong(m.group(3), 0);
            long curEnd = parseLong(m.group(4), 0);
            int nxt = (int) parseLong(m.group(5), 0);
            long nxtEnd = parseLong(m.group(6), 0);
            // 卡片只有左转、直行两行（右转、掉头数据源也不常给）
            if (dir != 1 && dir != 2) continue;
            if (cur == 0 && nxt == 0) continue;
            Ph p = new Ph();
            p.dir = dir;
            p.cur = cur;
            p.curEnd = curEnd;
            p.nxt = nxt;
            p.nxtEnd = nxtEnd;
            list.add(p);
        }

        phases = list;
        distM = (int) parseLong(h.group(3), 0);
        lim = (int) parseLong(h.group(4), 0);
        freshAt = now;

        // 只有「能算出倒计时」的相位才算有内容；一个都没有就整块不画
        boolean usable = false;
        for (Ph p : list) {
            if (effectiveLeft(p, now) > 0) {
                usable = true;
                break;
            }
        }
        ret = usable ? 0 : 2;
        return changed();
    }

    /** 当前相位剩余毫秒；已走完则用下一相位顶上（与仪表端 tlFromExt 的规则一致） */
    private static long effectiveLeft(Ph p, long now) {
        long left = p.curEnd - now;
        if (left > 0) return left;
        if (p.nxt != 0 && p.nxtEnd > now) return p.nxtEnd - now;
        return -1;
    }

    /** 相位签名：方向 + 当前色 + 下一色。秒数变化不算，仪表端自己走秒。 */
    private static boolean changed() {
        StringBuilder b = new StringBuilder();
        b.append(ret).append('|');
        for (Ph p : phases) {
            b.append(p.dir).append(':').append(p.cur).append(':').append(p.nxt).append(';');
        }
        String sig = b.toString();
        if (sig.equals(lastSig)) return false;
        lastSig = sig;
        Log.i(TAG, "红绿灯相位变化: " + sig);
        return true;
    }

    /**
     * 调试模拟：30 秒一循环，红 15s → 绿 12s → 黄 3s。
     * 前 20 秒给左转+直行两个方向（双灯布局），后 10 秒只给直行（单灯居中），
     * 停车时也能把两种版式、三种灯色、读秒递减和相位切换全部过一遍。
     * 造出来的字段与真实日志完全一致，仪表端走的是同一条解析路径。
     */
    private static boolean pollDemo() {
        long now = System.currentTimeMillis();
        long t = now % DEMO_CYCLE;
        List<Ph> list = new ArrayList<>();
        list.add(demoPhase(2, t, now));
        if (t < 20000) list.add(demoPhase(1, (now + 9000) % DEMO_CYCLE, now));
        phases = list;
        distM = 186;
        lim = 40;
        freshAt = now;
        ret = 0;
        return changed();
    }

    private static Ph demoPhase(int dir, long t, long now) {
        int cur, nxt;
        long remain, nxtMs;
        if (t < 15000) {
            cur = 1; remain = 15000 - t; nxt = 2; nxtMs = 12000;        // 红 → 绿
        } else if (t < 27000) {
            cur = 2; remain = 27000 - t; nxt = 3; nxtMs = 3000;         // 绿 → 黄
        } else {
            cur = 3; remain = DEMO_CYCLE - t; nxt = 1; nxtMs = 15000;    // 黄 → 红
        }
        Ph p = new Ph();
        p.dir = dir;
        p.cur = cur;
        p.curEnd = now + remain;
        p.nxt = nxt;
        p.nxtEnd = now + remain + nxtMs;
        return p;
    }

    private static void setEmpty() {
        if (ret == 2 && phases.isEmpty()) return;
        ret = 2;
        phases = new ArrayList<>();
        distM = 0;
        lim = 0;
        // freshAt 不动：这里只是「日志是旧的」，不代表 adb 断了
    }

    /**
     * 生成 ext.json 里的 tl 字段（含键名，直接拼在 payload 里）。
     * 倒计时在这里按「此刻」重算，所以哪怕 payload 是缓存值，秒数也不会停在旧值上。
     */

    /**
     * 红绿灯链路自检（给界面「校验红绿灯」按钮用）。
     *
     * 别人"开了开关却没显示"，通常卡在这几环之一：
     *   ① 红绿灯 App(com.xiaopeng.light) 没装或版本不带日志 SDK
     *   ② 日志目录不存在（App 从未跑过）
     *   ③ 有日志但很久没更新（车没在路上 / App 没在前台）
     *   ④ 日志在写但里面没有 GetLightInfo 行（没识别到路口）
     *   ⑤ 以上都正常、但我们 adb 读不到（授权/连接问题）
     * 这里一次性把 ①~④ 采回来，配合本地解析状态，直接指出卡在哪一环。
     */
    /** 从 "KEY=value" 多行文本里取某个键的值，没有返回 null */
    private static String field(String out, String key) {
        if (out == null) return null;
        for (String ln : out.split("\n")) {
            String t = ln.trim();
            if (t.startsWith(key + "=")) {
                String v = t.substring(key.length() + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 秒数说成人话 */
    private static String fmtAge(long sec) {
        if (sec < 0) return "未知";
        if (sec < 60) return sec + " 秒";
        if (sec < 3600) return (sec / 60) + " 分钟";
        return (sec / 3600) + " 小时";
    }

    /**
     * 红绿灯链路自检，返回一句通俗结论 + 原始数据。
     *
     * 别人「开了开关却不显示」，卡点通常依次是：App 没装 → 没跑过（没日志目录）→
     * 日志很久不更新 → 日志里没有路口数据 → 我们这边 adb 读不到。
     * 这里按这个顺序判一遍，直接给出该怎么办，不用用户自己看 key=value。
     */
    public static String diag(Context ctx) {
        String cmd = "echo \"PKG=$(pm list packages com.xiaopeng.light 2>/dev/null | head -1)\";"
                + " d=/sdcard/Android/data/com.xiaopeng.light/cache/log;"
                + " echo \"DIR=$(test -d $d && echo ok || echo missing)\";"
                + " f=$(ls -t $d/*.log 2>/dev/null | head -n 1);"
                + " echo \"FILE=${f:-none}\";"
                + " if [ -n \"$f\" ]; then"
                + "  echo \"SIZE=$(stat -c %s $f 2>/dev/null)\";"
                + "  echo \"AGE=$(( $(date +%s) - $(stat -c %Y $f 2>/dev/null || echo 0) ))\";"
                + "  echo \"HITS=$(tail -c 65536 $f 2>/dev/null | grep -c GetLightInfo)\";"
                + " fi";
        String out;
        try {
            out = LocalAdb.shell(ctx, cmd, 6000);
        } catch (Throwable t) {
            out = null;
        }

        String pkg = field(out, "PKG");
        String dir = field(out, "DIR");
        String file = field(out, "FILE");
        long age = -1, hits = -1, size = -1;
        try { age = Long.parseLong(field(out, "AGE")); } catch (Throwable ignored) { }
        try { hits = Long.parseLong(field(out, "HITS")); } catch (Throwable ignored) { }
        try { size = Long.parseLong(field(out, "SIZE")); } catch (Throwable ignored) { }

        String verdict;
        if (out == null || out.trim().isEmpty()) {
            verdict = "❌ 读不到车机数据：本地 adb 授权可能掉了。\n"
                    + "去「连接仪表」页点「连接并查询授权」，然后在车机屏幕点「允许」。";
        } else if (pkg == null) {
            verdict = "❌ 这台车没装「红绿灯领航」App，仪表上不会有红绿灯。\n"
                    + "装上并打开一次，再回来点校验。";
        } else if (!"ok".equals(dir) || file == null || "none".equals(file)) {
            verdict = "❌ 红绿灯 App 装了，但找不到它的日志目录。\n"
                    + "多半是装完从没打开过，或者没给存储权限。打开一次 App 再点校验。";
        } else if (age > 120) {
            verdict = "⚠️ 红绿灯 App 在，但它的日志已经 " + fmtAge(age) + "没更新了，说明当前没在工作。\n"
                    + "常见原因：没开导航 / 没到路口 / App 被后台清掉。开着导航跑到有红绿灯的路口再点一次。";
        } else if (hits <= 0) {
            verdict = "⚠️ 日志在正常写，但里面没有路口数据（最近 64KB 里 0 条）。\n"
                    + "可能是这条路线没有红绿灯，或者这个版本的红绿灯 App 不带该功能。";
        } else if (freshAt == 0) {
            verdict = "⚠️ 车机那边正常（App 在、日志在写、有路口数据），但我们这边一次都没读到过。\n"
                    + "多半是本地 adb 刚恢复还没轮询到，等 5 秒再点一次；一直这样就去「连接仪表」页重新授权。";
        } else if (ret != 0) {
            verdict = "✅ 链路完全正常，当前只是不在路口。\n"
                    + "红绿灯 App 返回「暂无路口」（ret=" + ret + "），所以仪表上暂时不显示；"
                    + "开车到有红绿灯的路口就会自动出现。";
        } else if (phases.isEmpty()) {
            verdict = "✅ 链路完全正常，当前不在路口。\n"
                    + "红绿灯 App 已连上（ret=0），但这次没有返回相位数据；"
                    + "开到有红绿灯的路口就会自动出现。";
        } else {
            verdict = "✅ 红绿灯一切正常。\n"
                    + "App 已安装，日志 " + fmtAge(age) + "前刚更新（" + (size > 0 ? (size / 1024) + "KB" : "大小未知")
                    + "），最近数据里有 " + hits + " 条路口记录，仪表端解析正常。";
        }
        StringBuilder sb = new StringBuilder(verdict);
        sb.append("\n\n———— 原始数据（排查用）————\n");
        sb.append(out == null || out.trim().isEmpty() ? "(无返回)" : out.trim());
        return sb.toString();
    }

    public static String json() {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder(256);
        if (!active()) {
            b.append("\"tl\":{\"ret\":2,\"distM\":0,\"lim\":0,\"phases\":[]}");
            return b.toString();
        }
        b.append("\"tl\":{\"ret\":0,\"distM\":").append(distM)
                .append(",\"lim\":").append(lim)
                // demo=true 时仪表端放开「驻车不画」的限制，停车也能调版式
                .append(",\"demo\":").append(demo)
                .append(",\"phases\":[");
        boolean first = true;
        int shown = 0;
        for (Ph p : phases) {
            if (effectiveLeft(p, now) <= 0) continue;
            int cur = p.cur;
            int nxt = p.nxt;
            long curEnd = p.curEnd;
            if (curEnd - now <= 0 && nxt != 0 && p.nxtEnd > now) {
                cur = nxt;
                curEnd = p.nxtEnd;
                nxt = 0;
            }
            if (!first) b.append(',');
            first = false;
            shown++;
            b.append("{\"dir\":").append(p.dir)
                    .append(",\"cur\":").append(cur)
                    .append(",\"remainMs\":").append(Math.max(0, curEnd - now))
                    .append(",\"nxt\":").append(nxt)
                    // nxtMs 是「下一相位能持续多久」的**时长**，不是它的结束时刻：
                    // 仪表端在 cur 走完时会做 nxtMs + (curEnd - now)，所以这里必须传
                    // nxtEnd - curEnd，传绝对时刻会把剩余的当前相位时长重复加进去。
                    .append(",\"nxtMs\":").append(nxt != 0 && p.nxtEnd > p.curEnd
                            ? p.nxtEnd - p.curEnd : 0)
                    .append('}');
        }
        b.append("]}");
        if (shown == 0) {
            return "\"tl\":{\"ret\":2,\"distM\":0,\"lim\":0,\"phases\":[]}";
        }
        return b.toString();
    }

    /** 调试用：最近一次解析结果的简述 */
    public static String describe() {
        if (!active()) return "无相位";
        StringBuilder b = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Ph p : phases) {
            long left = effectiveLeft(p, now);
            if (left <= 0) continue;
            if (b.length() > 0) b.append("  ");
            b.append(p.dir == 1 ? "左转" : "直行")
                    .append(p.cur == 1 ? "红" : p.cur == 2 ? "绿" : "黄")
                    .append(Math.max(0, left / 1000)).append('s');
        }
        return b.length() == 0 ? "无相位" : (demo ? "[模拟] " : "") + b.toString();
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
