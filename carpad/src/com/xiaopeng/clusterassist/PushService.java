package com.xiaopeng.clusterassist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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

    // 车机逐帧投帧通道（chgAnimTick / ChargeAnim）已于 2026-11-20 移除。
    //
    // 原因：充电动画现在只走仪表本地播放器（p7videoplay 读 charge.pack 本地播）。
    // 保留这条降级通道的后果是——播放器坏了、资源没到位时，画面还在动，
    // 只是慢到 11fps，人根本看不出"播放器已经失效"，只会觉得"有点卡"。
    // 用户的明确要求是：失效就回原厂界面，让问题暴露出来。
    // 所以这里不再有任何推帧逻辑，仪表没收到帧就不接管充电页。

    /** 盲区快速轮询：200ms 一次读常驻探针缓存（近零延迟），变化立即推送 */
    private final Runnable bsdTick = new Runnable() {
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
            handler.postDelayed(this, 50);
        }
    };

    // ---- 打灯影像（仪表半屏）----
    //
    // 唯一的门控入口：200ms 轮询转向灯，side 变了才去动相机与推流。三条硬规则：
    //   ① 读不到（null）保持现状，绝不把"未知"当成"灭"，否则过隧道/信号抖动就闪；
    //   ② 倒挡一律不显示——倒车时视线在倒车影像上，半屏影像只会添乱；
    //   ③ 熄灯立刻停相机（放开 HAL，不和原厂 AVM 抢），推流 socket 保持不动，
    //      仪表那边靠"800ms 无新帧自动撤区"收尾，比发撤区帧少一次往返。
    private static final int MIRROR_POLL_MS = 200;
    private MirrorPush mirrorPush;
    private MirrorSource mirrorSrc;
    private int mirrorSide;   // 0=无 1=左 2=右
    private boolean mirrorPermWarned;
    private long mirrorLogAt;
    private long prefsReloadAt;
    private long xuiRetryAt;

    // AVM 视角切换：display-mode 决定相机流的内容（默认是 360 俯视+前视拼接，
    // 切成四宫格才是四路鱼眼）。avmPrev 记住进入前的视角，松灯后原样还回去，
    // 避免把车机的环视视角留在四宫格上。
    private static final int AVM_SETTLE_MS = 250;
    private android.hardware.camera2.CameraManager camMgr;
    private int avmPrev = -1;
    private boolean avmApplied;

    private final Runnable mirrorTick = new Runnable() {
        @Override public void run() {
            try {
                mirrorStep();
            } catch (Throwable t) {
                android.util.Log.w("P7Mirror", "轮询异常: " + t);
            }
            handler.postDelayed(this, MIRROR_POLL_MS);
        }
    };

    private void mirrorStep() {
        if (prefs == null) return;
        // prefs 本身是 doPush（10 秒一轮）里才重新加载的，镜像轮询 200ms 一拍却一直在看旧对象：
        // 用户在设置页刚打开开关，要等最多 10 秒才生效，很容易被当成"开关没作用"。
        // 开关处于关闭态时顺手重载一次（每秒最多一次），打开后 1 秒内就能起效。
        if (!prefs.master || !prefs.mirror) {
            long t = System.currentTimeMillis();
            if (t - prefsReloadAt >= 1000) {
                prefsReloadAt = t;
                prefs = Prefs.load(this);
            }
            if (!prefs.master || !prefs.mirror) {
                stopMirror(true);
                return;
            }
        }
        long nowMs = System.currentTimeMillis();
        // XUI 订阅失败（车机没起来 / 服务还没就绪）时 5 秒重试一次，不影响 200ms 主轮询节拍
        if (!XuiLight.isReady() && nowMs - xuiRetryAt >= 5000) {
            xuiRetryAt = nowMs;
            XuiLight.start();
        }

        // 信号源：本固件上 CarProperty 被 CarPermissionManager 拦死，只有 XUI 可用；
        // 保留 CarProperty 作为兜底（老固件/其他车型）。
        boolean xui = XuiLight.isReady();
        Boolean l;
        Boolean r;
        Integer gear;
        if (xui) {
            l = XuiLight.isLeftOn();
            r = XuiLight.isRightOn();
            gear = XuiLight.gear() >= 0 ? Integer.valueOf(XuiLight.gear()) : null;
        } else {
            l = CarBridge.readTurnLeft();
            r = CarBridge.readTurnRight();
            gear = CarBridge.readGear();
        }

        // 诊断心跳（3 秒一次）：读不到（null）和读到"灭"（false）行为完全不同——前者永不触发，
        // 而原实现在这两种情况下都不打日志，现场只能看到"打灯没反应"却分不清是信号没读到
        // 还是开关没存进去。把来源、原始值和 lastError 一起打出来，一眼定位。
        if (nowMs - mirrorLogAt >= 3000) {
            mirrorLogAt = nowMs;
            Log.i("P7Mirror", "poll src=" + (xui ? "xui" : "carprop")
                    + " l=" + l + " r=" + r + " gear=" + gear + " side=" + mirrorSide
                    + " ev=" + XuiLight.eventCount()
                    + " err=" + (xui ? XuiLight.lastError() : CarBridge.lastError()));
        }

        // 任一方向读不到就保持现状：把"未知"当成"灭"会让画面在信号抖动时反复开关，
        // 而反复开关相机才是真正会打断原厂 AVM 的动作。
        if (l == null || r == null) return;
        boolean rev = xui ? XuiLight.isReverse() : (gear != null && gear == CarBridge.GEAR_REVERSE);

        int side = 0;
        // 双闪（两边同时亮）不触发，与原厂「打灯联动」语义一致
        if (!rev && l != null && r != null && l && r) {
            side = 0;
        } else if (!rev && l != null && l) {
            side = 1;
        } else if (!rev && r != null && r) {
            side = 2;
        }

        if (side == mirrorSide) return;
        if (side == 0) {
            stopMirror(false);
            return;
        }
        startMirror(side);
    }

    private void startMirror(int side) {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!mirrorPermWarned) {
                mirrorPermWarned = true;
                Log.w("P7Mirror", "缺少 CAMERA 权限，影像不启动（adb shell pm grant 可补）");
            }
            return;
        }
        // AVM 四宫格与取流必须同源：AvmMode 的 display-mode 固定下发到 cameraId=0
        // （真机 logcat: setCmdControlParameters ... cameraId =0 params =display-mode=8）。
        // 原仓库同样是「下发 0 + openCamera ids[0]=video51」。之前左右灯分别用
        // mirrorCamL=0 / mirrorCamR=1，右灯取 video52(camera 1) 时四宫格根本不作用在
        // 这一路，表现为「下发成功但画面不变」。故只要开了 AVM，一律用 camera 0 取流。
        // 没开 AVM 时保留老配置（三分屏侧栏 ROI 路径），避免影响既有行为。
        // 取景参数以 Prefs 为唯一真相源：进程冷启动后 MirrorSource 静态字段还是代码默认值，
        // 打灯时现读一次恢复用户在 APP 里调好的 zoom/pan（APP 步进改参数时也会实时改静态字段）。
        MirrorSource.applyFromPrefs(Prefs.load(this));
        final int cam = prefs.mirrorAvm ? 0 : (side == 1 ? prefs.mirrorCamL : prefs.mirrorCamR);
        // 先停旧源：换侧要换相机，旧的不放手新的打不开（HAL 是独占的）
        if (mirrorSrc != null) {
            mirrorSrc.stop();
            mirrorSrc = null;
        }
        if (mirrorPush == null) mirrorPush = new MirrorPush();
        if (!mirrorPush.isRunning()) mirrorPush.start(prefs.host, prefs.mirrorPort);
        mirrorSide = side;
        mirrorPermWarned = false;

        // AVM 视角必须在打开相机之前下发：相机流的内容由 display-mode 决定，
        // 开完相机再切就还是旧视角（表现为"切了没反应"）。
        boolean avm = prefs.mirrorAvm && applyAvm();
        if (!avm) {
            openMirror(cam, side);
            return;
        }
        // 车机切换视角是异步的，给它一点时间再开相机，否则拿到的仍是旧模式的流。
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mirrorSide == side && mirrorSrc == null) openMirror(cam, side);
            }
        }, AVM_SETTLE_MS);
    }

    private void openMirror(int cam, int side) {
        // avmApplied=true 表示四宫格已成功下发，相机流是 2×2 鱼眼 → 按象限裁；
        // 否则退回三分屏侧栏 ROI。crop 参数在两种模式下都要求"裁出该侧视野"。
        boolean crop = prefs.mirrorAvm;
        boolean quad = crop && avmApplied;
        mirrorSrc = new MirrorSource(this, cam, side, crop, quad, mirrorPush);
        mirrorSrc.start();
        Log.i("P7Mirror", "打灯 " + (side == 1 ? "左" : "右") + "：相机 id=" + cam
                + " 裁切=" + crop + " 四宫格=" + quad + " 视角now=" + AvmMode.current(camMgr));
    }

    /**
     * 打开相机之前预置 AVM 四宫格视角。
     *
     * <p>真机定位结论（V5.10.0.8814，对照能出鱼眼的 P7打灯转向辅助 v1.46）：
     * 切不到鱼眼的根因是「补齐了原厂那套配套字段」——overlay-workst/out-mode/
     * transbody 一起写时，相机服务的 XpCameraCmdController.setAvmControlMode()
     * 根本不被触发，视角停在 360 三分屏。v1.46 只写裸 display-mode=8，controller
     * 立即应用（displaymode:8, 配套字段全 -1），随后开 camera 0 出四路鱼眼。
     * 所以 AvmMode 现在刻意只写裸字段；这里普通 set 优先、early 兜底。</p>
     *
     * <p>无论下发是否成功，MirrorSource 都能工作：四宫格生效就按象限裁，没生效
     * 就退回三分屏侧栏 ROI。两条路都不依赖这里一定成功。</p>
     */
    private boolean applyAvm() {
        if (camMgr == null) {
            camMgr = (android.hardware.camera2.CameraManager)
                    getSystemService(Context.CAMERA_SERVICE);
        }
        if (camMgr == null) return false;
        if (avmApplied) return true;
        if (avmPrev < 0) avmPrev = AvmMode.current(camMgr);
        // 裸 display-mode 下发（对齐 v1.46 成功链路）；失败再退 early
        boolean ok = AvmMode.set(camMgr, AvmMode.MODE_QUAD);
        if (!ok) ok = AvmMode.earlySet(camMgr, AvmMode.MODE_QUAD);
        avmApplied = ok;
        int nowMode = AvmMode.current(camMgr);
        Log.i("P7Mirror", "AVM: 预置四宫格 " + (ok ? "已下发" : "失败")
                + " prev=" + avmPrev + " now=" + nowMode
                + " readBack=" + AvmMode.readBack(camMgr)
                + " err=" + AvmMode.lastError());
        return ok;
    }

    /** 松灯后把视角还回去；没成功切过就不用还。 */
    private void restoreAvm() {
        if (!avmApplied) return;
        avmApplied = false;
        int back = avmPrev > 0 ? avmPrev : AvmMode.MODE_360;
        // 还原同样只写裸 display-mode（与 v1.46 成功链路一致）
        boolean ok = AvmMode.set(camMgr, back);
        if (!ok) ok = AvmMode.earlySet(camMgr, back);
        Log.i("P7Mirror", "AVM: 还原 display-mode=" + back + (ok ? " 成功" : " 失败")
                + " now=" + AvmMode.current(camMgr) + " err=" + AvmMode.lastError());
        avmPrev = -1;
    }

    private void stopMirror(boolean closeSocket) {
        if (mirrorSrc != null) {
            mirrorSrc.stop();
            mirrorSrc = null;
        }
        mirrorSide = 0;
        restoreAvm();
        if (closeSocket && mirrorPush != null) {
            mirrorPush.stop();
            mirrorPush = null;
        }
    }

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

        // 只读诊断：把车机 AVM 参数原文打一次。不同固件的键名不一定是 display-mode，
        // 鱼眼视角切不过去时，靠这一行日志才能判断该改哪个键，而不是瞎试。
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    if (camMgr == null) {
                        camMgr = (android.hardware.camera2.CameraManager)
                                getSystemService(Context.CAMERA_SERVICE);
                    }
                    Log.i("P7Mirror", "AVM 参数: " + AvmMode.dump(camMgr));
                } catch (Throwable t) {
                    Log.w("P7Mirror", "AVM 参数读取异常: " + t);
                }
            }
        }, 5000);
        handler.post(lyricTick);
        handler.post(bsdTick);
        handler.post(tlTick);
        handler.post(mirrorTick);
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

    /** 判断当前是否夜间：与车机 App 主题共用 NightMode 的同一套逻辑 */
    private boolean isNight() {
        return NightMode.isNight(this);
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
        if (handler != null) handler.removeCallbacks(mirrorTick);
        stopMirror(true);
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
