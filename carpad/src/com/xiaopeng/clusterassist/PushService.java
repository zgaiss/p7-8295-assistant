package com.xiaopeng.clusterassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * 数据桥：把车机侧才有的数据（12V 电压、导航余量、日夜）每 5 秒写进仪表 /data/p7assist/ext.json。
 * 仪表端只读取本地文件，不依赖车机常连；车机不在时这些字段按 TTL 自动失效。
 */
public class PushService extends Service {
    public static final String CH_ID = "p7bridge";
    public static volatile boolean running = false;
    public static volatile String lastInfo = "未开始";

    /** 慢数据（电压/胎压/车内温度/导航）10 秒一轮即可：这些量本来按分钟级变化。
     *  盲区走独立的常驻探针 + 变化即推，不受这个间隔影响。 */
    private static final int INTERVAL_MS = 10000;

    private Handler handler;
    private Prefs prefs;
    private Deploy deploy;
    private Runnable tick;
    private long lastPush;
    /** 上一次读到的好电压（探测偶尔失败时沿用，避免画面上的电瓶数字闪没） */
    private static volatile Float lastGoodVolt;
    private static volatile long lastVoltAt;
    /** 上一次读到的车内温度（℃，探测偶尔失败时沿用） */
    private static volatile Float lastGoodCabin;
    private static volatile long lastCabinAt;
    /** 上一次读到的高精度四轮胎压（bar） */
    private static volatile float[] lastGoodTpms;
    private static volatile long lastTpmsAt;
    /** 上一次读到的盲区 [左,右]（探测偶尔失败时沿用） */
    private static volatile int[] lastGoodBsd;
    private static volatile long lastBsdAt;
    /** 上一次推送的歌词行，用来判断是否需要立刻重推 */
    private String lastLyricCore = "";
    /** 歌词轮询：400ms 一次（只读 MediaSession，很轻），换行才触发一次推送。
     *  之前 1 秒一次，歌词行切换最多晚 1 秒；降到 400ms 让当前行跟得更紧。 */
    private final Runnable lyricTick = new Runnable() {
        @Override public void run() {
            try {
                maybeGrantNotif(); // 通知使用权掉了就自动补（一分钟最多一次）
                if (prefs != null && prefs.lyric) {
                    LyricSource.poll(PushService.this);
                    // 播放/暂停翻转也必须立刻推：否则要等 10s 慢循环，
                    // 仪表上的歌词会在暂停后滞留（恢复播放后也要等 10s 才回来）。
                    String core = LyricSource.cur + "\u0001" + LyricSource.next
                            + "\u0001" + (LyricSource.playing ? "1" : "0");
                    if (!core.equals(lastLyricCore)) {
                        lastLyricCore = core;
                        doPush(true); // 歌词换行：跳过 adb 轮询，用缓存值快速推送，降低当前行延迟
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("P7Lyric", "歌词轮询异常: " + t);
            }
            handler.postDelayed(this, 400);
        }
    };

    /**
     * 红绿灯轮询：有相位时 500ms、无相位 2s。
     *
     * 只读一个日志文件的尾部（一次 shell 往返），比常驻探针轻得多；相位变化才推送，
     * 秒数由仪表端按 ext.json 的 mtime 本地走，车机不需要每秒写盘。
     */
    private volatile boolean tlBusy;
    private final Runnable tlTick = new Runnable() {
        @Override public void run() {
            int next = 2000;
            try {
                // 开关切到模拟时立刻清掉真实相位，切回来时下一轮重新读日志
                TlSource.setDemo(prefs != null && prefs.tl && prefs.tlDemo);
                if (prefs != null && prefs.tl && prefs.bridge && prefs.master && !tlBusy) {
                    tlBusy = true;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            try {
                                if (TlSource.poll(PushService.this)) doPush(true);
                            } catch (Throwable t) {
                                Log.w("P7TL", "红绿灯轮询异常: " + t);
                            } finally {
                                tlBusy = false;
                            }
                        }
                    }).start();
                }
                if (prefs != null && prefs.tl) {
                    next = TlSource.active() ? 500 : 2000;
                }
            } catch (Throwable t) {
                Log.w("P7TL", "红绿灯 tick 异常: " + t);
            }
            handler.postDelayed(this, next);
        }
    };

    /**
     * 充电动画投屏：车机出帧、按节奏推给仪表。
     * 3 秒检查一次"还要不要发"——关掉充电页/调试开关后发送线程自己退出，
     * 仪表端 2.5 秒收不到新帧会自动回落到内置静态图。
     */
    private final Runnable chgAnimTick = new Runnable() {
        @Override public void run() {
            try {
                // 只在真正需要时投帧：调试模式，或"充电页开启且仪表回报正在充电"。
                // 否则行驶中会一直往仪表刷 20KB×4/s，既占带宽又白耗仪表 CPU。
                boolean want = prefs != null && (prefs.chargeDebug
                        || (prefs.chargePage && chargingForAnim()));
                // 仪表端本地播放器接管整屏时不用再推帧：那边是 charge.pack 本地 24fps 播放，
                // 再推只会白占 FTP 带宽、抢仪表 CPU（实测会让播放掉到 20fps）。
                if (want && videoModeOn()) want = false;
                ChargeAnim.setWanted(PushService.this, deploy, want);
            } catch (Throwable ignored) { }
            handler.postDelayed(this, 3000);
        }
    };

    /** 仪表是否在充电（10 秒缓存；读不到就当作"是"，宁可多投也不要不投） */
    private boolean chargingForAnim() {
        refreshChgStat();
        return chgStatCharging;
    }

    /** 仪表端本地播放器是否已接管充电页（status.json 的 chgVideo=vplay） */
    private boolean videoModeOn() {
        refreshChgStat();
        return chgStatVideo;
    }

    private void refreshChgStat() {
        long now = System.currentTimeMillis();
        if (now - chgStatAt <= 10000) return;
        chgStatAt = now;
        try {
            org.json.JSONObject st = deploy.status();
            chgStatCharging = st.optBoolean("charging", true);
            chgStatVideo = "vplay".equals(st.optString("chgVideo", ""));
        } catch (Throwable t) {
            chgStatCharging = true;
            chgStatVideo = false;
        }
    }

    private long chgStatAt = 0;
    private boolean chgStatCharging = true;
    private boolean chgStatVideo = false;

    /** 盲区快速轮询：200ms 一次读常驻探针缓存（近零延迟），变化立即推送 */    private final Runnable bsdTick = new Runnable() {
        @Override public void run() {
            // 必须兜住异常：这里一旦抛出，postDelayed 就不会再执行，盲区轮询会永久停掉
            try {
                if (prefs != null && prefs.bsd) {
                    LocalAdb.startBsdMonitor(PushService.this); // 确保常驻探针在跑
                    int[] cur = LocalAdb.readBsd();
                    if (cur != null) {
                        boolean changed = lastGoodBsd == null
                                || cur[0] != lastGoodBsd[0] || cur[1] != lastGoodBsd[1];
                        if (changed) {
                            lastGoodBsd = cur;
                            lastBsdAt = System.currentTimeMillis();
                            doPush(true); // 盲区变化：跳过 adb（bsd 用常驻探针值，其余用缓存）
                        } else {
                            lastBsdAt = System.currentTimeMillis();
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("P7Bsd", "盲区轮询异常: " + t);
            }
            handler.postDelayed(this, 200);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        handler = new Handler(Looper.getMainLooper());
        prefs = Prefs.load(this);
        deploy = new Deploy(this, prefs);
        createChannel();
        startForeground(1, buildNotification("数据桥启动中…"));

        tick = new Runnable() {
            @Override public void run() {
                doPush();
                handler.postDelayed(this, INTERVAL_MS);
            }
        };
        handler.post(tick);
        handler.post(lyricTick);
        handler.post(bsdTick);
        handler.post(chgAnimTick);
        handler.post(tlTick);
        maybeGrantNotif();
        // 启动 15 秒后做一次仪表端版本自检：APK 覆盖升级只会换车机端代码，
        // 仪表端 /data/p7assist/p7assist 还是老二进制，两边功能会不一致
        // （比如新加的盲区兜底、DBus 双源修复都不会生效）。这里自动补一次。
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                checkClusterVersion();
            }
        }, 15000);
    }

    /**
     * 仪表端版本自检：status.json 里的 version 与本机 versionName 不一致就推一次新二进制。
     *
     * 安全性：仪表端 daemon 对新二进制有「size/mtime 稳定 2 秒 + ELF 头校验」双闸
     * （见 upgrade.go），FTP 传半截或断流都不会被采纳，最多是这次升级失败、旧版本继续跑。
     * 首次部署（status.json 不存在）不在这里处理：那需要建目录、传脚本和字体，
     * 仍由「一键部署」完成。
     */
    private void checkClusterVersion() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String my;
                    try {
                        my = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    } catch (Throwable t) {
                        return;
                    }
                    org.json.JSONObject st = deploy.status();
                    String at = st.optString("version", "");
                    if (at.isEmpty() || at.equals(my)) return;
                    // 只在驻车时替换：daemon 热升级会先清掉自己的像素再 exec，
                    // 行驶中做会让叠加层空白 1~2 秒。行驶中就等下次启动/下次驻车。
                    String mode = st.optString("mode", "");
                    if (!"park".equals(mode)) {
                        Log.i("P7Update", "仪表端 " + at + " 落后于 " + my + "，当前 " + mode + "，等驻车再更新");
                        return;
                    }
                    Log.i("P7Update", "仪表端 " + at + " → 本机 " + my + "，自动推送新二进制");
                    updateNotification("仪表端更新中 " + at + " → " + my + "…");
                    deploy.pushBinary(null);
                    updateNotification("仪表端已更新到 " + my);
                    Log.i("P7Update", "新二进制已上传，等待仪表端热替换");
                } catch (Throwable t) {
                    // 仪表没部署过 / FTP 不通 / 正在升级：都不影响数据桥，静默跳过
                    Log.w("P7Update", "版本自检跳过: " + t);
                }
            }
        }).start();
    }

    private long notifNextTry = 0;

    /**
     * 自查通知使用权，缺了就用本地 adb 自动补（车机没有可跳转的设置页）。
     * 一分钟最多查一次；adb 还没授权时这次会失败，授权成功后下一分钟自动补上。
     */
    private void maybeGrantNotif() {
        if (prefs == null || !(prefs.lyric || prefs.nav)) return;
        long now = System.currentTimeMillis();
        if (now < notifNextTry) return;
        // 15 秒重试一次：adb 刚授权完就能马上补上，不用等一分钟
        notifNextTry = now + 15 * 1000L;
        if (LocalAdb.notifGranted(this)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                LocalAdb.grantNotificationListener(PushService.this);
            }
        }).start();
    }

    /** 歌词里可能有引号/反斜杠，直接拼 JSON 会坏，做个最小转义 */
    private static String esc(String v) {
        if (v == null) return "";
        StringBuilder b = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append(' ');
                case '\u0001': break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "仪表数据桥",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("P7 仪表助手 · 数据桥")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void doPush() {
        doPush(false);
    }

    /** skipAdb=true 时跳过 CarProperty/导航的 adb 轮询，直接用缓存值（歌词换行时走这条，降低延迟） */
    private void doPush(final boolean skipAdb) {
        // 本次推送的序号，越新越大；真正写盘前用它判断是否已被更新的请求取代
        final int seq = pushSeq.incrementAndGet();
        prefs = Prefs.load(this);
        if (!prefs.bridge || !prefs.master) {
            return;
        }
        final String host = prefs.host;
        if (!host.equalsIgnoreCase(deployHost)) {
            deploy.reset();
            deployHost = host;
        }
        final boolean wantVolt = prefs.volt;
        final boolean wantNav = prefs.nav;
        final boolean wantTpmsHi = prefs.tpmsHi;
        final boolean wantLyric = prefs.lyric;
        final boolean wantCabin = prefs.cabin;
        final boolean wantBsd = prefs.bsd;
        final boolean wantTl = prefs.tl;
        final int night = isNight() ? 1 : 0;

        // 取电压/导航都必须放后台：普通 uid 读 CarProperty 会被小鹏的 CarPermissionManager 拒掉，
        // 也读不到系统日志，只能走本地 adb 拿 shell（见 LocalAdb）；首次还要等用户在车机屏上
        // 点「允许 USB 调试」，可能几十秒，放主线程会 ANR。
        new Thread(new Runnable() {
            @Override public void run() {
                Float volt = null;
                float[] tp = null;
                Float cabin = null;
                Integer bsdL = null, bsdR = null;
                // skipAdb 时直接用缓存值（歌词换行快速推送）；否则走 adb 轮询。
                // 电压、导航、高精度胎压、车内温度 任一项开启才需要连本地 adb。
                if (skipAdb) {
                    if (wantVolt && lastGoodVolt != null
                            && System.currentTimeMillis() - lastVoltAt < 60000) {
                        volt = lastGoodVolt;
                    }
                    if (wantTpmsHi && lastGoodTpms != null
                            && System.currentTimeMillis() - lastTpmsAt < 60000) {
                        tp = lastGoodTpms;
                    }
                    if (wantCabin && lastGoodCabin != null
                            && System.currentTimeMillis() - lastCabinAt < 60000) {
                        cabin = lastGoodCabin;
                    }
                    if (wantBsd && lastGoodBsd != null
                            && System.currentTimeMillis() - lastBsdAt < 3000) {
                        bsdL = lastGoodBsd[0];
                        bsdR = lastGoodBsd[1];
                    }
                } else if (wantVolt || wantNav || wantTpmsHi || wantCabin) {
                    // BSD 不在这里：它由常驻探针 + 变化即推负责，不需要再连一次共享 adb
                    LocalAdb.Signals sig = LocalAdb.read(PushService.this, wantVolt || wantTpmsHi || wantCabin, wantNav);
                    // 四轮胎压：仅当用户选择「高精度胎压」时才推。默认走仪表总线（0.1 bar），
                    // 这样不依赖 adb、也不绕开原厂数据链，符合程序初衷。
                    if (sig != null && wantTpmsHi) {
                        boolean all = true;
                        for (int i = 0; i < 4; i++) {
                            if (Float.isNaN(sig.tpms[i]) || sig.tpms[i] < 0.5f || sig.tpms[i] > 5.0f) {
                                all = false;
                                break;
                            }
                        }
                        if (all) {
                            tp = sig.tpms.clone();
                            lastGoodTpms = tp;
                            lastTpmsAt = System.currentTimeMillis();
                        }
                    }
                    if (tp == null && wantTpmsHi && lastGoodTpms != null
                            && System.currentTimeMillis() - lastTpmsAt < 60000) {
                        tp = lastGoodTpms;
                    }
                    if (wantVolt && sig != null && !Float.isNaN(sig.volt) && sig.volt > 6 && sig.volt < 20) {
                        volt = sig.volt;
                        lastGoodVolt = sig.volt;
                        lastVoltAt = System.currentTimeMillis();
                    } else if (wantVolt && lastGoodVolt != null
                            && System.currentTimeMillis() - lastVoltAt < 60000) {
                        // 偶尔一次探测失败不要立刻把电压从画面上抹掉，60 秒内沿用上次的值
                        volt = lastGoodVolt;
                    }
                    // 车内温度：真实值（HVAC_INNER_TEMPERATURE），仪表总线上只有空调"设定"温度
                    if (wantCabin && sig != null && !Float.isNaN(sig.cabin) && sig.cabin > -30 && sig.cabin < 80) {
                        cabin = sig.cabin;
                        lastGoodCabin = sig.cabin;
                        lastCabinAt = System.currentTimeMillis();
                    } else if (wantCabin && lastGoodCabin != null
                            && System.currentTimeMillis() - lastCabinAt < 60000) {
                        cabin = lastGoodCabin;
                    }
                }
                // 盲区统一取常驻探针的实时缓存（100ms 采样，纯内存读取，不占 adb）。
                // 不再用 10 秒一次的 p7volt 探针旧值覆盖，避免圆弧回跳/滞后。
                // 盲区统一取常驻探针的实时缓存（纯内存读取，不占 adb）。
                // 注意这里必须带 !skipAdb：skipAdb 是「盲区变化立即推送」这条快路径，
                // 上方已经用 bsdTick 确认过的 lastGoodBsd 填好了；如果这里再读一次
                // readBsd()，读到的可能还是探针尚未刷新的旧值，会把刚确认的新值覆盖回去
                // —— 实测表现就是"圆弧灭了却迟迟不消失、还出现左右同时亮的假状态"。
                if (wantBsd && !skipAdb) {
                    int[] live = LocalAdb.readBsd();
                    if (live != null) {
                        bsdL = live[0];
                        bsdR = live[1];
                        lastGoodBsd = live;
                        lastBsdAt = System.currentTimeMillis();
                    } else if (lastGoodBsd != null
                            && System.currentTimeMillis() - lastBsdAt < 3000) {
                        bsdL = lastGoodBsd[0];
                        bsdR = lastGoodBsd[1];
                    }
                }
                final Float fvolt = volt;
                final Float fcabin = cabin;
                final Integer fbsdL = bsdL, fbsdR = bsdR;

                // 导航取数优先用日志通道（有真实剩余里程/时间），退回到通知栏解析
                final boolean navFresh;
                final double navDist, navTime;
                final String navArrive;
                if (!wantNav) {
                    navFresh = false;
                    navDist = navTime = -1;
                    navArrive = "";
                } else if (NavSource.fresh()) {
                    navFresh = true;
                    navDist = NavSource.distM;
                    navTime = NavSource.timeS;
                    navArrive = NavSource.arrive;
                } else if (NavWatcher.hasFresh()) {
                    navFresh = true;
                    navDist = NavWatcher.latest.distM;
                    navTime = NavWatcher.latest.timeS;
                    navArrive = NavWatcher.latest.arrive;
                } else {
                    navFresh = false;
                    navDist = navTime = -1;
                    navArrive = "";
                }

                StringBuilder js = new StringBuilder();
                js.append("{\"ts\":").append(System.currentTimeMillis());
                if (tp != null) {
                    js.append(",\"tpms\":[");
                    for (int i = 0; i < 4; i++) {
                        if (i > 0) js.append(',');
                        js.append(String.format(java.util.Locale.ROOT, "%.4f", tp[i]));
                    }
                    js.append(']');
                }
                if (fvolt != null) {
                    js.append(",\"volt\":").append(String.format(java.util.Locale.ROOT, "%.2f", fvolt));
                }
                if (fcabin != null) {
                    js.append(",\"cabin\":").append(String.format(java.util.Locale.ROOT, "%.1f", fcabin));
                }
                if (fbsdL != null) {
                    js.append(",\"bsd\":[").append(fbsdL).append(',').append(fbsdR).append(']');
                }
                js.append(",\"night\":").append(night);
                if (wantLyric && LyricSource.fresh()) {
                    js.append(",\"lyric\":{\"cur\":\"").append(esc(LyricSource.cur))
                      .append("\",\"next\":\"").append(esc(LyricSource.next))
                      .append("\",\"title\":\"").append(esc(LyricSource.title))
                      .append("\",\"artist\":\"").append(esc(LyricSource.artist))
                      .append("\",\"pos\":").append(String.format(java.util.Locale.ROOT, "%.3f",
                              LyricSource.progress < 0 ? 0 : LyricSource.progress))
                      .append(",\"play\":").append(LyricSource.playing ? 1 : 0)
                      .append("}");
                }
                if (navFresh) {
                    js.append(",\"nav\":{\"distM\":").append(Math.round(navDist))
                      .append(",\"timeS\":").append(Math.round(navTime))
                      .append(",\"arrive\":\"").append(navArrive == null ? "" : navArrive).append("\"}");
                }
                // 红绿灯：秒数在拼接时按"此刻"重算，所以复用缓存 payload 也不会停在旧数字上
                if (wantTl) {
                    js.append(',').append(TlSource.json());
                }
                js.append("}");

                String payload = js.toString();
                // 内容没变就 60 秒才写一次，别每 5 秒磨一次 eMMC
                String core = payload.replaceAll("\"ts\":\\d+,?", "");
                long now = System.currentTimeMillis();
                synchronized (PushService.this) {
                    if (core.equals(lastCore) && now - lastPush < 60000) {
                        return;
                    }
                }
                // 已有更新的推送在途 → 本次丢弃，避免旧 payload 覆盖新 payload
                if (seq != pushSeq.get()) {
                    return;
                }
                pushLock.acquireUninterruptibly();
                try {
                    // 等锁期间可能又来了更新的推送，再判一次
                    if (seq != pushSeq.get()) {
                        return;
                    }
                    deploy.pushExt(payload);
                    lastPush = System.currentTimeMillis();
                    synchronized (PushService.this) {
                        lastCore = core; // 成功落盘后才记为已推，失败可以重试
                    }
                    String info = (fvolt != null
                            ? String.format(java.util.Locale.ROOT, "电压 %.1fV", fvolt)
                            : "电压 — (" + LocalAdb.state() + ")")
                            + " · " + (navFresh ? "导航 " + Math.round(navDist / 1000) + "km/" + Math.round(navTime / 60) + "min" : "导航 —")
                            + (wantTl ? " · 红绿灯 " + TlSource.describe() : "");
                    lastInfo = info;
                    updateNotification(info);
                } catch (Throwable t) {
                    lastInfo = "推送失败：" + t.getMessage();
                    updateNotification(lastInfo);
                    deploy.reset();
                } finally {
                    pushLock.release();
                }
            }
        }).start();
    }

    private String deployHost = "";
    private String lastCore = "";

    /**
     * 推送序号：每次 doPush 自增。写 ext.json 前比对，不是最新就丢弃。
     *
     * 为什么需要：doPush 每次 new Thread，歌词(400ms)、盲区(变化即推)、主循环(10s) 会同时
     * 产生多个线程，各自持有一份 payload。FTP 写入快慢不一，**后完成的旧 payload 会覆盖
     * 先完成的新 payload**，表现为右侧盲区"亮了很久才显示、灭了很久才消失"。
     */
    private static final java.util.concurrent.atomic.AtomicInteger pushSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** 推送串行锁：ext.json 必须单写者，配合 pushSeq 保证落盘的一定是最新数据 */
    private static final java.util.concurrent.Semaphore pushLock =
            new java.util.concurrent.Semaphore(1, true);

    /**
     * 判断当前是否夜间。
     *
     * 踩过的坑：`persist.sys.theme` 只在用户**锁定**白天/夜间时被车机写入，切回「自动」后
     * 它会**停留在上一次锁定的值**（实测：锁定夜间 → 切回自动，仍是 2）。之前只认它，
     * 于是白天中控已经变白、仪表还在画夜间配色。`last_override_night_mode` 和
     * `ui_night_mode_last_computed` 实测全程不动，也是死值。
     *
     * 现在按"用户意图 + 天文时间"两级判断：
     *   1. UiModeManager.getNightMode()：1=锁定白天 / 2=锁定夜间 → 直接采用（最准）；
     *   2. 0=自动 → 按经纬度算日出日落（车机存着 persist.sys.theme.latitude/longitude），
     *      不依赖任何会卡住的系统状态；
     *   3. 兜底：6:00–18:00 为白天。
     */
    private boolean isNight() {
        int mode = -1;
        try {
            UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
            if (um != null) mode = um.getNightMode();
        } catch (Throwable ignored) { }
        if (mode == UiModeManager.MODE_NIGHT_YES) { nightDiag("锁定夜间"); return true; }
        if (mode == UiModeManager.MODE_NIGHT_NO) { nightDiag("锁定白天"); return false; }

        Boolean bySun = nightBySun();
        if (bySun != null) return bySun.booleanValue();

        nightDiag("无有效经纬度，回落 6:00-18:00");
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        return h < 6 || h >= 18;
    }

    private String lastNightDiag;

    private void nightDiag(String s) {
        if (!s.equals(lastNightDiag)) {
            lastNightDiag = s;
            Log.i("P7Night", s);
        }
    }

    /** 自动档：按经纬度算日出日落；经纬度不可用返回 null */
    private Boolean nightBySun() {
        double lat = numProp("persist.sys.theme.latitude");
        double lon = numProp("persist.sys.theme.longitude");
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        if (Math.abs(lat) < 0.01 && Math.abs(lon) < 0.01) return null;
        java.util.Calendar now = java.util.Calendar.getInstance();
        double rise = sunEvent(now, lat, lon, true);
        double set = sunEvent(now, lat, lon, false);
        if (Double.isNaN(rise) || Double.isNaN(set)) return null;
        double h = now.get(java.util.Calendar.HOUR_OF_DAY) + now.get(java.util.Calendar.MINUTE) / 60.0;
        boolean day = h >= rise && h < set;
        nightDiag(String.format(java.util.Locale.US,
                "自动：日出 %.2f 日落 %.2f 现在 %.2f → %s", rise, set, h, day ? "白天" : "夜间"));
        return Boolean.valueOf(!day);
    }

    private double numProp(String k) {
        try {
            String v = getSystemProperty(k);
            return v == null || v.trim().isEmpty() ? Double.NaN : Double.parseDouble(v.trim());
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** NOAA 简化算法：返回本地小时（0–24）；极昼/极夜返回 NaN */
    private static double sunEvent(java.util.Calendar day, double lat, double lon, boolean isRise) {
        int n = day.get(java.util.Calendar.DAY_OF_YEAR);
        double tzH = day.getTimeZone().getOffset(day.getTimeInMillis()) / 3600000.0;
        double lngHour = lon / 15.0;
        double t = n + ((isRise ? 6 : 18) - lngHour) / 24.0;
        double M = 0.9856 * t - 3.289;
        double L = M + 1.916 * Math.sin(Math.toRadians(M)) + 0.020 * Math.sin(Math.toRadians(2 * M)) + 282.634;
        L = ((L % 360) + 360) % 360;
        double ra = Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(L))));
        ra = ((ra % 360) + 360) % 360;
        double lq = Math.floor(L / 90) * 90;
        double raq = Math.floor(ra / 90) * 90;
        ra = (ra + (lq - raq)) / 15.0;
        double sinDec = 0.39782 * Math.sin(Math.toRadians(L));
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(Math.toRadians(90.833)) - sinDec * Math.sin(Math.toRadians(lat)))
                / (cosDec * Math.cos(Math.toRadians(lat)));
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double hh = isRise ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH));
        hh /= 15.0;
        double tt = hh + ra - 0.06571 * t - 6.622;
        double ut = ((tt - lngHour) % 24 + 24) % 24;
        return ((ut + tzH) % 24 + 24) % 24;
    }

    /** 读系统属性（反射 android.os.SystemProperties，Android 9+ 隐藏 API 需反射） */
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

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(1, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null && tick != null) handler.removeCallbacks(tick);
        if (handler != null) handler.removeCallbacks(tlTick);
        if (deploy != null) deploy.reset();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, PushService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, PushService.class));
        } catch (Throwable ignored) {
        }
    }
}
