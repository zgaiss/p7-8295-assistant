package com.xiaopeng.clusterassist;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 充电动画「投屏」发送端。
 *
 * 为什么由车机出帧：仪表那颗 CPU 自己播全屏动画，一帧（解码 + 转画布 + 上屏）
 * 要 400ms 以上，被 30fps 的渲染循环拖着画就整块卡死。车机算力够，把帧推过去，
 * 仪表只负责解码 + 上屏。
 *
 * 通道：FTP 写 /data/p7assist/charge.frm（先写 .tmp 再 rename，仪表不会读到半截）。
 * 节奏：目标 90ms 一帧（约 11fps），按实际发送耗时自适应——发完一帧若还没到
 *       周期就补 sleep，超过周期就立刻发下一帧（不再人为加固定间隔）。
 *       底层 FTP 实测 14KB 一帧只要 9~10ms，所以瓶颈不在链路上。
 *
 * 停止后仪表端 2.5 秒收不到新帧会自己回落到"内置静态图"，不画了但也不卡。
 */
public final class ChargeAnim {
    private static final String TAG = "P7ChgAnim";
    /** 帧文件在 assets 下的目录与索引上限（f_000.jpg …） */
    private static final String DIR = "chargeanm/";
    private static final int MAX_FRAMES = 160;
    /** 目标帧周期（毫秒）：90ms ≈ 11fps。发送耗时超过它就不补 sleep，直接发下一帧。 */
    private static final long TARGET_MS = 90;
    /** 兜底最小间隔，防止某次发送异常快把仪表打爆 */
    private static final long MIN_GAP_MS = 45;

    // ---- 实测统计（给「投屏调试」显示真实帧率，别再靠猜） ----
    private static volatile long statSumMs = 0;
    private static volatile long statMaxMs = 0;
    private static volatile long statN = 0;
    private static volatile long winStart = 0;
    private static volatile long winFrames = 0;
    private static volatile double statFps = 0;

    private static final AtomicBoolean wanted = new AtomicBoolean(false);
    private static volatile Thread worker;
    private static volatile Context appCtx;
    private static volatile Deploy deployRef;
    /** 调试：暂停投帧到这个时刻（模拟车机断开，验证仪表兜底自播） */
    private static volatile long pauseUntil = 0;
    private static volatile long lastPushAt = 0;
    private static volatile long pushedCount = 0;
    private static volatile int curIdx = 0;
    private static volatile String lastErr = "";
    private static final Object pushLock = new Object();
    private static int frameCount = -1;

    private ChargeAnim() { }

    /** 由 PushService 定期调用：是否需要持续投帧 */
    public static void setWanted(Context ctx, Deploy deploy, boolean want) {
        appCtx = ctx.getApplicationContext();
        deployRef = deploy;
        wanted.set(want);
        if (want) ensureWorker();
    }

    public static boolean isRunning() {
        return worker != null && worker.isAlive();
    }

    private static synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int idx = 0;
                long lastFail = 0;
                while (true) {
                    if (!wanted.get()) {
                        // 不需要投了就退出线程，下次要用再拉起
                        break;
                    }
                    if (System.currentTimeMillis() < pauseUntil) {
                        // 调试：暂停期间不发帧，仪表端会自动回落到自播
                        try { Thread.sleep(150); } catch (InterruptedException ignored) { }
                        continue;
                    }
                    long t0 = System.currentTimeMillis();
                    try {
                        byte[] jpg = loadFrame(idx);
                        if (jpg != null && jpg.length > 512) {
                            Deploy d = deployRef;
                            boolean ok;
                            synchronized (pushLock) {
                                ok = d != null && d.pushChargeFrame(jpg);
                            }
                            if (ok) {
                                curIdx = idx;
                                idx = (idx + 1) % count();
                                lastPushAt = System.currentTimeMillis();
                                pushedCount++;
                            }
                        }
                    } catch (Throwable e) {
                        // 失败不退出：仪表可能刚好在重启；下次再试
                        if (System.currentTimeMillis() - lastFail > 15000) {
                            lastFail = System.currentTimeMillis();
                            Log.w(TAG, "投帧失败: " + e);
                        }
                    }
                    long used = System.currentTimeMillis() - t0;
                    noteTiming(used);
                    // 周期 = max(TARGET_MS, used + MIN_GAP_MS)：链路快就按目标帧率走，
                    // 链路慢就一路直发，不叠加固定等待。
                    long wait = TARGET_MS - used;
                    if (wait < MIN_GAP_MS) wait = MIN_GAP_MS;
                    try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
                }
                synchronized (ChargeAnim.class) { worker = null; }
            }
        }, "p7-chgeanm");
        t.setDaemon(true);
        t.start();
        worker = t;
    }

    // ---------------- 调试接口（给「充电模式」页的投屏调试用） ----------------

    /** 记录一次发送耗时，并按 1 秒窗口滚动算实际帧率 */
    private static void noteTiming(long usedMs) {
        statSumMs += usedMs;
        statMaxMs = Math.max(statMaxMs, usedMs);
        statN++;
        long now = System.currentTimeMillis();
        if (winStart == 0) winStart = now;
        winFrames++;
        long dt = now - winStart;
        if (dt >= 1000) {
            statFps = winFrames * 1000.0 / dt;
            winStart = now;
            winFrames = 0;
        }
    }

    /** 暂停投帧 ms 毫秒：用于验证"车机断开 → 仪表自动切自播" */
    public static void pauseFor(long ms) {
        pauseUntil = System.currentTimeMillis() + ms;
    }

    public static boolean isPaused() {
        return System.currentTimeMillis() < pauseUntil;
    }

    /** 立刻推一帧（不改变播放进度） */
    public static void pushOnce() {
        pushFrames(1, 0);
    }

    /** 连推 n 帧，间隔 gapMs（仪表按收到的帧数重绘，用来验证链路是否丢帧） */
    public static void pushFrames(final int n, final long gapMs) {
        final Deploy d = deployRef;
        if (d == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < n; i++) {
                    int idx = (curIdx + i) % count();
                    byte[] jpg = loadFrame(idx);
                    if (jpg == null || jpg.length < 512) continue;
                    boolean ok = false;
                    try {
                        synchronized (pushLock) { ok = d.pushChargeFrame(jpg); }
                    } catch (Throwable e) {
                        lastErr = "调试推帧失败: " + e;
                    }
                    if (ok) {
                        lastPushAt = System.currentTimeMillis();
                        pushedCount++;
                        curIdx = idx;
                    } else {
                        lastErr = "FTP 上传失败";
                    }
                    try { Thread.sleep(gapMs); } catch (InterruptedException ignored) { }
                }
            }
        }, "p7-chgeanm-dbg").start();
    }

    /** 发送侧状态文本（给界面显示） */
    public static String debugText() {
        StringBuilder sb = new StringBuilder();
        sb.append("发送线程：").append(isRunning() ? "运行中" : "已停止");
        sb.append("\n需要投帧：").append(wanted.get() ? "是" : "否");
        if (isPaused()) {
            sb.append("　⏸ 暂停中（剩 ").append((pauseUntil - System.currentTimeMillis()) / 1000).append(" 秒）");
        }
        sb.append("\n已推送：").append(pushedCount).append(" 帧")
          .append("　当前帧号：").append(curIdx).append("/").append(frameCount > 0 ? frameCount : -1);
        if (statN > 0) {
            sb.append("\n实测帧率：").append(String.format(java.util.Locale.US, "%.1f", statFps))
              .append(" fps（目标 ").append(TARGET_MS).append("ms/帧 ≈ ")
              .append(Math.round(1000.0 / TARGET_MS)).append("fps）");
            sb.append("\n每帧耗时：平均 ").append(statSumMs / statN).append("ms　峰值 ")
              .append(statMaxMs).append("ms　样本 ").append(statN);
        }
        if (lastPushAt > 0) {
            sb.append("\n最近一次推送：").append((System.currentTimeMillis() - lastPushAt) / 1000).append(" 秒前");
        }
        if (!lastErr.isEmpty()) {
            sb.append("\n最近错误：").append(lastErr);
        }
        return sb.toString();
    }

    /** 帧总数：探测 assets 里连续编号到哪一帧为止 */
    private static int count() {
        if (frameCount > 0) return frameCount;
        int n = 0;
        while (n < MAX_FRAMES) {
            try {
                InputStream in = open(String.format("f_%03d.jpg", n));
                if (in == null) break;
                in.close();
                n++;
            } catch (Throwable e) {
                break;
            }
        }
        frameCount = Math.max(1, n);
        Log.i(TAG, "投屏帧数: " + frameCount);
        return frameCount;
    }

    private static InputStream open(String name) {
        try {
            return appCtx == null ? null : appCtx.getAssets().open(DIR + name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] loadFrame(int idx) {
        InputStream in = open(String.format("f_%03d.jpg", Math.max(0, idx)));
        if (in == null) return null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(32768);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
        }
    }
}
