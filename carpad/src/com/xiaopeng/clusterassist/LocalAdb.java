package com.xiaopeng.clusterassist;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * 本地 adb 通道：给 App 一条 shell。
 *
 * 车机的 adbd 监听 127.0.0.1:5050（service.adb.tcp.port=5050，ro.adb.secure=1）。
 * 首次连接会用新密钥向 adbd 报名，车机屏幕弹出「允许 USB 调试」，用户点一次允许后
 * 该密钥写入 /data/misc/adb/adb_keys，之后长期有效。
 */
public final class LocalAdb {
    private static final String TAG = "P7Adb";
    private static final String SP = "p7adb";
    private static final String KEY = "priv";
    private static final String KEY_PUB = "pub";

    public static final int PORT = 5050;
    public static final String HOST = "127.0.0.1";

    private static int portCache;

    /** adbd 的 TCP 端口从 getprop 读，读不到退回 5050 */
    private static int port() {
        if (portCache > 0) return portCache;
        int p = PORT;
        try {
            Process pr = Runtime.getRuntime().exec(new String[]{"getprop", "service.adb.tcp.port"});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pr.getInputStream()));
            String s = br.readLine();
            br.close();
            if (s != null && s.trim().length() > 0) p = Integer.parseInt(s.trim());
        } catch (Throwable ignored) { }
        portCache = p;
        return p;
    }

    private static AdbClient client;
    private static String lastState = "未连接";
    private static long lastTryMs;
    private static volatile boolean busy;
    private static volatile boolean probeReady;
    // 实测这台车的 adbd 对新连接的认证是坏的（详见日志 P7Adb），
    // 连续失败几次后拉长重试间隔，别让它每 70 秒空转一次。
    private static volatile int failCount;
    private static volatile long nextTryMs;

    // 常驻盲区探针：启动一次 app_process 后持续读 BSD，避免每次轮询的启动开销，做到近零延迟。
    private static volatile int bsdLiveL = -1, bsdLiveR = -1;   // -1 = 尚未读到
    private static volatile long bsdLiveAt = 0;
    private static volatile boolean bsdMonitorStarted = false;
    private static volatile long bsdNextRetry = 0;
    private static final Object bsdStartLock = new Object();
    /**
     * 状态文件兜底：探针除了打 stdout，还会把当前左右盲区原子写进
     * /data/local/tmp/p7bsd.state。这里每 2 秒 cat 一次。
     *
     * 为什么需要：常驻 shell 流一旦丢行（adbd 分段、缓冲区、终端语义都可能），
     * App 侧就永远停在旧值，而进程看起来一切正常——实车表现就是"盲区灯明明亮了，
     * 仪表一直不动"。文件单写者 + 原子 rename，读一次就是确切值，不受流状态影响。
     */
    private static volatile int fileL = -1, fileR = -1;
    private static volatile long fileAt = 0;
    private static volatile boolean filePollerStarted = false;
    /** 正在握手/等待授权的那条连接。resetKey 靠它把 190 秒的阻塞握手立刻打断。 */
    private static volatile AdbClient connecting;
    /**
     * dumpsys 兜底：直接读 CarPropertyService 自己记的 lastEvent。
     *
     * 为什么需要：探针走的 getProperty 会"冻结"——读到 1 之后服务端再变 0 它也收不到，
     * 表现就是圆弧亮了不灭（实测 dumpsys 里 1→0 都有，探针却停在 1）。
     * dumpsys 跑在 system_server 内部直读缓存，不受这条客户端路径影响，是可靠的二次源。
     * 实测 `--services CarPropertyService` 只要 30ms，1 秒一次开销很小。
     */
    private static volatile int dumpL = -1, dumpR = -1;
    private static volatile long dumpAt = 0;
    private static volatile boolean dumpPollerStarted = false;
    /** 授权未通过后的退避截止时间：期间 ensure() 直接返回，不重连、不重复弹框 */
    private static volatile long authBlockUntil = 0;
    private static AdbClient bsdClient;

    public static String state() {
        return lastState;
    }

    private static volatile KeyPair kpCache;

    /**
     * 取本地 adb 密钥对，**必须同步**。
     *
     * 密钥不内置：APK 里不带任何私钥（内置容易出问题 —— 换钥匙要重新打包，且每份
     * APK 都带着同一把私钥）。密钥全程在车机本地处理：
     *   生成（本机随机生成）→ 保存（SharedPreferences）→ 授权（adbd 弹「允许 USB 调试」，
     *   用户在车机屏幕点允许后写入 /data/misc/adb/adb_keys，长期有效）。
     *
     * 取值优先级：
     *   1. SharedPreferences 存档 —— 重启/升级沿用同一把钥匙，不打扰用户。
     *      但必须校验私钥/公钥是同一对：旧版取钥没加锁，两个线程可能各写一半，
     *      存档里留下"A 的私钥 + B 的公钥"：签名用 A、车机验的是 B，授权框点了
     *      「允许」也永远连不上（车机存下的是 B）。这种脏数据必须丢弃。
     *   2. 本地生成 —— 没有存档时生成一把新的并立即存档（同步落盘）。
     *      kpCache 进程内只取一次；「重新授权」按钮会清掉缓存和存档，强制换新钥匙。
     */
    private static synchronized KeyPair keyPair(Context c) {
        if (kpCache != null) return kpCache;
        SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
        // 1) 存档里的钥匙（校验配对）
        KeyPair kp = tryPair(sp.getString(KEY, null), sp.getString(KEY_PUB, null));
        if (kp != null) {
            kpCache = kp;
            return kpCache;
        }
        // 2) 本地生成并保存（功能优先；授权弹框由 adbd 负责，见 AdbClient.connect）
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kg = g.generateKeyPair();
            sp.edit()
                    .putString(KEY, Base64.encodeToString(kg.getPrivate().getEncoded(), Base64.NO_WRAP))
                    .putString(KEY_PUB, Base64.encodeToString(kg.getPublic().getEncoded(), Base64.NO_WRAP))
                    .commit();
            kpCache = kg;
            return kpCache;
        } catch (Throwable t2) {
            lastState = "密钥生成失败: " + t2;
            Log.w(TAG, lastState);
            return null;
        }
    }

    /** 解析 base64 的 PKCS#8 私钥 + X.509 公钥；两者模数不一致则返回 null */
    private static KeyPair tryPair(String privB64, String pubB64) {
        if (privB64 == null || pubB64 == null) return null;
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey pv = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)));
            PublicKey pb = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)));
            if (pv instanceof RSAPrivateCrtKey && pb instanceof RSAPublicKey) {
                if (!((RSAPrivateCrtKey) pv).getModulus().equals(((RSAPublicKey) pb).getModulus())) {
                    Log.w(TAG, "存档里的 adb 私钥与公钥不匹配，已丢弃");
                    return null;
                }
            }
            return new KeyPair(pb, pv);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 授权状态查询（给界面显示）。状态机：
     *   已授权（本地 adb 已连接）
     *   等待车机确认 —— AdbClient 正在握手里（已上报公钥），车机屏幕应弹「允许 USB 调试」
     *   未授权 — 等待确认超时 / 连接失败等
     */
    public static String authStatus() {
        AdbClient c = client;
        if (c != null && c.isConnected()) return "已授权（本地 adb 已连接）";
        String ph = AdbClient.phase;
        if ("等待车机确认".equals(ph)) return "等待车机确认 — 请在车机屏幕点「允许 USB 调试」";
        String st = lastState == null ? "" : lastState;
        if (st.contains("等待授权超时")) return "未授权 — 等待确认超时（车机屏幕是否弹了「允许 USB 调试」？可点「重新授权」换钥匙再试）";
        if (st.contains("授权协商反复失败")) return "未授权 — 授权协商反复失败（可点「重新授权」换钥匙再试）";
        if ("未连接".equals(st)) return "未授权（尚未尝试连接）";
        return "未连接 — " + st;
    }

    /**
     * 清掉所有退避与失败计数，让下一次连接立刻真正发起。
     *
     * 界面上的「连接并查询授权」必须先进这里。ensure() 在授权失败后会退避 20 秒
     * （`authBlockUntil`），连续失败还会退避 30 秒——用户在退避期内点按钮，
     * ensure() 直接返回 null，等于**什么都没做**，看起来就是"点了拉不起来"。
     */
    public static void clearBackoff() {
        authBlockUntil = 0;
        nextTryMs = 0;
        failCount = 0;
    }

    /** 重新授权：清掉本地密钥，下次连接生成新钥匙、重新走一遍弹框授权 */    public static void resetKey(final Context c) {
        // 1) 先掐断"正在等授权"的那条连接。
        //    ensure() 是 synchronized（锁 LocalAdb.class），而它在 connect() 里最长阻塞 190 秒；
        //    主线程如果直接进 synchronized，就会卡在这把锁上——实测点了「重新授权」确认后
        //    整个界面卡死随后闪退，就是这个原因。关掉 socket 让 connect 立刻抛异常退出，
        //    类锁马上释放。
        AdbClient cc = connecting;
        if (cc != null) {
            try { cc.close(); } catch (Throwable ignored) { }
        }
        // 2) 清存档密钥 + 内存缓存：都不依赖类锁，主线程立刻做，保证下次一定用新钥匙。
        //    （kpCache 是 volatile，直接置空是安全的；若留到后台线程清，用户马上点
        //     「连接并查询」时会拿旧钥匙再试一次，看起来就像"点了没反应"。）
        kpCache = null;
        try {
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                    .remove(KEY).remove(KEY_PUB).commit();
        } catch (Throwable ignored) { }
        // 3) 其余内存状态放到后台清：即使类锁还没释放，主线程也不会被卡住。
        new Thread(new Runnable() {
            @Override public void run() {
                synchronized (LocalAdb.class) {
                    kpCache = null;
                    client = null;
                    probeReady = false;
                    authBlockUntil = 0;
                    failCount = 0;
                    nextTryMs = 0;
                    lastState = "未连接";
                    AdbClient.phase = "";
                }
                Log.i(TAG, "已清除本地 adb 密钥，下次连接将重新生成并弹框授权");
            }
        }).start();
    }

    /** 确保连接可用；失败返回 null 并写入 lastState */
    private static synchronized AdbClient ensure(Context c) {
        if (client != null && client.isConnected()) return client;
        // 上一次授权没通过（用户没点允许 / 点了取消）：退避期内直接返回，
        // 既不重复阻塞 20 秒，也不会把授权框一次次叠出来。
        if (System.currentTimeMillis() < authBlockUntil) return null;
        KeyPair kp = keyPair(c);
        if (kp == null || !(kp.getPublic() instanceof RSAPublicKey)) {
            lastState = "密钥不可用";
            return null;
        }
        PrivateKey privKey = kp.getPrivate();
        RSAPublicKey pubKey = (RSAPublicKey) kp.getPublic();
        // 两轮：用户点「允许」之后 adbd 会重启一次，第一条连接常被直接掐断。
        // 稍等再连一次即可（此时公钥已在 adb_keys 里，不会再弹框）。
        for (int attempt = 0; attempt < 2; attempt++) {
            AdbClient nc = new AdbClient(privKey, pubKey, "p7assist@xpeng");
            connecting = nc;   // 暴露给 resetKey：用户点「重新授权」时能立刻掐断这次握手
            try {
                nc.connect(HOST, port(), 190000); // 配对窗口 180s（等用户在车机点「允许」，期间每 30s 重发公钥）
                client = nc;
                lastState = "已连接";
                Log.i(TAG, "本地 adb 已连接");
                return client;
            } catch (Throwable t) {
                String msg = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
                lastState = msg;
                Log.w(TAG, "本地 adb 连接失败(第" + (attempt + 1) + "次): " + msg);
                nc.close();
                client = null;
                // "等授权超时"= 用户还没点「允许」。这时候绝不能立刻重试：
                // 每重试一次 adbd 就会再弹一个同样的框，用户看到的就是"要按两次"。
                if (msg.indexOf("授权超时") >= 0) break;
                if (attempt == 0) {
                    try { Thread.sleep(900); } catch (InterruptedException ignored) { }
                }
            } finally {
                connecting = null;
            }
        }
        // 两种可能：用户还没点「允许」，或点了取消。统一退避 20 秒再试，
        // 避免每 10 秒就重连一次、把授权框反复弹出来。
        authBlockUntil = System.currentTimeMillis() + 20 * 1000L;
        return null;
    }

    /** 专用 adb 连接 + 长驻命令流；两样都要关（先关流再关连接）。 */
    public static final class ShellLink {
        public final AdbClient client;
        public final AdbClient.ShellStream stream;

        ShellLink(AdbClient c, AdbClient.ShellStream s) {
            client = c;
            stream = s;
        }

        public void close() {
            try { stream.close(); } catch (Throwable ignored) { }
            try { client.close(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 新开一条**独立**连接跑长驻命令，返回可逐行读的流（例如 logcat）。
     *
     * <p>为什么不复用共享连接：持有流期间同一个 AdbClient 不能再跑 shell()（adbd 会把
     * 两路数据串在一起），电压/胎压轮询会跟着坏掉。盲区常驻探针也是单独开一条的，
     * 这里沿用同一套做法。连接沿用已授权的同一把钥匙，不会再弹「允许 USB 调试」。
     *
     * <p>必须在后台线程调用（内部可能阻塞到用户点授权）。
     */
    public static ShellLink openDedicatedShell(Context ctx, String cmd) throws Exception {
        if (ensure(ctx) == null) throw new java.io.IOException("本地 adb 未就绪：" + lastState);
        KeyPair kp = keyPair(ctx);
        if (kp == null || !(kp.getPublic() instanceof RSAPublicKey)) {
            throw new java.io.IOException("adb 密钥不可用");
        }
        AdbClient adb = new AdbClient(kp.getPrivate(), (RSAPublicKey) kp.getPublic(), "p7assist@xpeng");
        try {
            adb.connect(HOST, port(), 30000);
            return new ShellLink(adb, adb.openShell(cmd));
        } catch (Exception e) {
            try { adb.close(); } catch (Throwable ignored) { }
            throw e;
        }
    }

    /**
     * 通知监听组件。歌词和导航都靠它读 MediaSession。
     */
    public static final String NOTIF_COMP =
            "com.xiaopeng.clusterassist/com.xiaopeng.clusterassist.NavWatcher";

    /** 是否已拿到通知使用权 */
    public static boolean notifGranted(Context c) {
        try {
            String v = android.provider.Settings.Secure.getString(
                    c.getContentResolver(), "enabled_notification_listeners");
            return v != null && v.contains(NOTIF_COMP);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 自授权通知使用权。
     *
     * 这台车机没有可跳转的「通知使用权」设置页（Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
     * 没人处理），App 里那个按钮点下去等于没反应。但我们本来就有 uid=shell 的本地 adb，
     * 而 shell 可以执行系统的 `cmd notification allow_listener`，直接把自己加进
     * enabled_notification_listeners —— 这是绕开系统设置页的唯一可靠通道。
     *
     * 必须在后台线程调用（内部会连 adb）。
     */
    public static boolean grantNotificationListener(Context c) {
        try {
            String out = shell(c, "cmd notification allow_listener " + NOTIF_COMP
                    + "; settings get secure enabled_notification_listeners", 15000);
            boolean ok = out != null && out.contains("NavWatcher");
            Log.i(TAG, "自授权通知使用权: " + (ok ? "成功" : "失败"));
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "自授权通知使用权失败: " + t);
            return false;
        }
    }

    /**
     * 部署专用：借车机 shell（uid=shell）执行一条命令。
     * 某些固件上普通 App 直连仪表 30510 会被静默丢弃（连得上但返回 0 字节），
     * 必须由 shell 进程发起连接，这里用来拉起 nc 中继。
     * 首次本地 adb 授权可能要等用户点「允许」，务必在后台线程调用。
     */
    public static String shell(Context ctx, String cmd, int timeoutMs) throws Exception {
        AdbClient adb = ensure(ctx);
        if (adb == null) throw new Exception("本地 adb 不可用：" + lastState);
        return adb.shell(cmd, timeoutMs);
    }

    /**
     * 通过 shell + app_process 读车机 CarProperty（shell 身份才有权限）。
     * 返回 {volt, bsdLeft, bsdRight, soc}，读不到返回 null。
     */
    /** 把内置的探针 jar 写到车机 /data/local/tmp（分块 base64，避免命令过长） */
    private static boolean ensureProbe(Context ctx, AdbClient adb) {
        if (probeReady) return true;
        try {
            byte[] jar = readAsset(ctx, "p7volt.jar");
            String have = adb.shell("wc -c < /data/local/tmp/p7volt.jar 2>/dev/null", 6000);
            if (have != null && have.trim().equals(String.valueOf(jar.length))) {
                probeReady = true;
                return true;
            }
            String b64 = Base64.encodeToString(jar, Base64.NO_WRAP);
            adb.shell("rm -f /data/local/tmp/p7volt.jar", 6000);
            for (int i = 0; i < b64.length(); i += 3000) {
                String chunk = b64.substring(i, Math.min(i + 3000, b64.length()));
                String dir = (i == 0) ? ">" : ">>";
                adb.shell("printf %s " + chunk + " | base64 -d " + dir + " /data/local/tmp/p7volt.jar", 10000);
            }
            String chk = adb.shell("wc -c < /data/local/tmp/p7volt.jar", 6000);
            boolean ok = chk != null && chk.trim().equals(String.valueOf(jar.length));
            if (ok) probeReady = true;
            Log.i(TAG, "探针部署" + (ok ? "成功" : "失败") + " (" + jar.length + " vs " + (chk == null ? "?" : chk.trim()) + ")");
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "探针部署异常: " + t);
            return false;
        }
    }

    private static byte[] readAsset(Context ctx, String name) throws Exception {
        java.io.InputStream in = ctx.getAssets().open(name);
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 一次轮询的结果 */
    public static final class Signals {
        public float volt = Float.NaN;
        public float soc = -1;
        public int bsdLeft = -1;
        public int bsdRight = -1;
        /** 四轮胎压（bar，FL FR RL RR），来自车机 CarProperty 的 kPa 值，精度到 0.01 */
        public float[] tpms = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        /** 车内温度（℃，HVAC_INNER_TEMPERATURE），仪表总线上没有真实车内温度 */
        public float cabin = Float.NaN;
        /** 本次是否成功跑通（通道可用） */
        public boolean ok;
    }

    /**
     * 一趟轮询做完两件事：读 CarProperty（电压等）+ 拉一次导航日志。
     * 两者都依赖同一条本地 adb 通道，合在一起可以少一次握手和少一次线程调度。
     */
    public static Signals read(Context ctx, boolean wantVolt, boolean wantNav) {
        Signals sig = new Signals();
        long now = System.currentTimeMillis();
        // 首次授权最长要等 65 秒，这段时间内不再发起新的探测（否则线程会堆起来）
        // 节流降到 500ms：盲区(BSD)需要近实时，性能换延迟。
        if (busy || now < nextTryMs || now - lastTryMs < 500) return sig;
        busy = true;
        lastTryMs = now;
        boolean ok = false;
        try {
            AdbClient adb = ensure(ctx);
            if (adb == null) return sig;
            ok = true;

            if (wantVolt) {
                if (!ensureProbe(ctx, adb)) {
                    lastState = "探针部署失败";
                    return sig;
                }
                String cmd = "ANDROID_ART_ROOT=/apex/com.android.art"
                        + " ANDROID_I18N_ROOT=/apex/com.android.i18n"
                        + " ANDROID_TZDATA_ROOT=/apex/com.android.tzdata"
                        + " CLASSPATH=/data/local/tmp/p7volt.jar"
                        + " /system/bin/app_process /system/bin p7volt.Probe";
                String out = adb.shell(cmd, 12000);
                float[] r = parse(out);
                if (r == null) {
                    lastState = "探针无输出: " + (out == null ? "" : out.trim());
                    Log.w(TAG, lastState);
                } else {
                    sig.volt = r[0];
                    sig.bsdLeft = r[1] >= 0 ? (int) r[1] : -1;
                    sig.bsdRight = r[2] >= 0 ? (int) r[2] : -1;
                    sig.soc = r[3];
                    // 车内温度（℃，HVAC_INNER_TEMPERATURE）
                    if (r.length > 8 && !Float.isNaN(r[8])) sig.cabin = r[8];
                    // kPa -> bar，只取合理的 0.5~5.0
                    for (int i = 0; i < 4; i++) {
                        if (r.length > 4 + i && !Float.isNaN(r[4 + i])) {
                            float bar = r[4 + i] / 100f;
                            if (bar > 0.5f && bar < 5.0f) sig.tpms[i] = bar;
                        }
                    }
                    lastState = "OK";
                }
            }

            if (wantNav) {
                NavSource.poll(adb);
            }

            sig.ok = true;
            return sig;
        } catch (Throwable t) {
            lastState = "执行失败: " + t.getMessage();
            Log.w(TAG, lastState);
            client = null;   // 下次重连
            return sig;
        } finally {
            busy = false;
            if (ok) {
                failCount = 0;
            } else if (++failCount >= 5) {
                failCount = 0;
                // 盲区需要近实时，连续失败只退避 30 秒，别 10 分钟不试（之前会让盲区长时间无数据）
                nextTryMs = System.currentTimeMillis() + 30 * 1000L;
                Log.i(TAG, "连续失败，30 秒后再试");
            }
        }
    }

    /**
     * 启动常驻盲区探针：部署 p7bsd.jar，用独立 adb 连接跑一个常驻 app_process，
     * 后台线程持续读 "BSDL=x BSDR=y"，更新 volatile 缓存。之后 readBsd() 几乎零开销。
     */
    public static void startBsdMonitor(final Context ctx) {
        // 状态文件轮询独立于常驻探针：探针流反复掉线时它也能拿到值。
        // 这里只 new Thread，绝不在主线程做 IO。
        if (!filePollerStarted) {
            synchronized (bsdStartLock) {
                if (!filePollerStarted) {
                    filePollerStarted = true;
                    startBsdFilePoller(ctx);
                }
            }
        }
        // dumpsys 兜底线程：与探针、文件轮询互不影响，1 秒一次
        if (!dumpPollerStarted) {
            synchronized (bsdStartLock) {
                if (!dumpPollerStarted) {
                    dumpPollerStarted = true;
                    startDumpsysPoller(ctx);
                }
            }
        }
        // 注意：本方法会被主线程每 200ms 调一次，**绝对不能是 synchronized**，
        // 也不能在这里做任何网络操作。ensure() 在等用户点「允许」时最长阻塞 20 秒，
        // 一旦主线程排队等这把类锁，就会出现 Input dispatching timeout / ANR。
        // 这里只用一个极短的临界区做"只启动一次"的判定。
        synchronized (bsdStartLock) {
            if (bsdMonitorStarted) return;
            long now = System.currentTimeMillis();
            if (now < bsdNextRetry) return;
            bsdMonitorStarted = true;
            bsdNextRetry = now + 120 * 1000L; // 授权未通过时不反复重连，120 秒内不再发起
        }
        new Thread(new Runnable() {
            @Override public void run() {
                // 先让共享连接把授权流程走完，再开探针这条连接。
                // 两条连接同时发起认证时，adbd 会为每条未授权的连接各弹一次框
                // ——这是"要按两次允许"的另一半原因。现在最多只会弹一次。
                if (ensure(ctx) == null) {
                    Log.w(TAG, "共享连接未就绪，暂不启动盲区探针: " + lastState);
                    bsdMonitorStarted = false;
                    return;
                }
                KeyPair kp = keyPair(ctx);
                if (kp == null || !(kp.getPublic() instanceof RSAPublicKey)) {
                    bsdMonitorStarted = false;
                    return;
                }
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        // 此时公钥已在 adb_keys 里，这条连接不会触发授权框
                        AdbClient adb = new AdbClient(kp.getPrivate(), (RSAPublicKey) kp.getPublic(), "p7assist@xpeng");
                        adb.connect(HOST, port(), 30000);
                        bsdClient = adb;
                        // 部署 p7bsd.jar
                        byte[] jar = readAsset(ctx, "p7bsd.jar");
                        String have = adb.shell("wc -c < /data/local/tmp/p7bsd.jar 2>/dev/null", 6000);
                        if (have == null || !have.trim().equals(String.valueOf(jar.length))) {
                            String b64 = Base64.encodeToString(jar, Base64.NO_WRAP);
                            adb.shell("rm -f /data/local/tmp/p7bsd.jar", 6000);
                            for (int i = 0; i < b64.length(); i += 3000) {
                                String chunk = b64.substring(i, Math.min(i + 3000, b64.length()));
                                String dir = (i == 0) ? ">" : ">>";
                                adb.shell("printf %s " + chunk + " | base64 -d " + dir + " /data/local/tmp/p7bsd.jar", 10000);
                            }
                        }
                        // 启动常驻探针
                        String cmd = "ANDROID_ART_ROOT=/apex/com.android.art"
                                + " ANDROID_I18N_ROOT=/apex/com.android.i18n"
                                + " ANDROID_TZDATA_ROOT=/apex/com.android.tzdata"
                                + " CLASSPATH=/data/local/tmp/p7bsd.jar"
                                + " /system/bin/app_process /system/bin p7bsd.Probe";
                        AdbClient.ShellStream stream = adb.openShell(cmd);
                        Log.i(TAG, "常驻盲区探针已启动");
                        while (true) {
                            String line = stream.readLine(30000);
                            if (line == null) {
                                // 流断开（adbd 重启/连接抖动）。原来的 120 秒退避会让盲区
                                // 整整两分钟不刷新，这里改成 5 秒快速重连。
                                bsdNextRetry = System.currentTimeMillis() + 5000;
                                Log.w(TAG, "盲区探针流断开，5 秒后重连");
                                break;
                            }
                            // 探针一行可能带多个键值（BSDL=0 BSDR=0），必须逐个 token 解析。
                            // 旧代码只取第一个 '='，把 "0 BSDR=0" 当整数解析必然失败、又被静默吞掉，
                            // 于是 bsdLiveL/R 永远是 -1、readBsd() 永远 null —— 盲区只能靠 5 秒一次的旧值。
                            for (String tk : line.trim().split("\\s+")) {
                                int eq = tk.indexOf('=');
                                if (eq <= 0) continue;
                                String k = tk.substring(0, eq);
                                String v = tk.substring(eq + 1);
                                try {
                                    if (k.equals("BSDL")) bsdLiveL = Integer.parseInt(v);
                                    else if (k.equals("BSDR")) bsdLiveR = Integer.parseInt(v);
                                    bsdLiveAt = System.currentTimeMillis();
                                } catch (NumberFormatException ignored) { }
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "常驻盲区探针失败(第" + (attempt + 1) + "次): " + (t.getMessage() != null ? t.getMessage() : t));
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                    }
                }
                bsdMonitorStarted = false; // 允许下次重试（但受 bsdNextRetry 60 秒退避约束）
            }
        }).start();
    }

    /** 停止车机上所有常驻探针进程并清空缓存（卸载还原时用，避免残留进程继续占用 adb） */
    public static void stopProbes(Context ctx) {
        bsdMonitorStarted = false;
        bsdLiveL = -1;
        bsdLiveR = -1;
        bsdLiveAt = 0;
        try {
            shell(ctx, "for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E 'p7bsd.jar|p7full.jar' "
                    + "| grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; echo PROBES_STOPPED", 8000);
            Log.i(TAG, "已停止盲区探针");
        } catch (Throwable t) {
            Log.w(TAG, "停止探针失败: " + t);
        }
    }

    /**
     * 每秒读一次 CarPropertyService 的 lastEvent（只 dump 这一个服务，设备侧 grep 后
     * 只回传两行）。这条路径不受 getProperty 冻结影响，作为最高优先级的兜底。
     */
    private static void startDumpsysPoller(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() {
                // 注意：lastEvent 那一段只在全量 dump 里（--services CarPropertyService
                // 不含它），实测全量 + 设备侧 grep 只要 30ms，回传也只有两行。
                final String cmd = "dumpsys car_service 2>/dev/null"
                        + " | grep -oE 'SCU_BSD_(LEFT|RIGHT)_WARNING.*int32Values: \\[[0-9-]+\\]'";
                while (true) {
                    try {
                        String out = shell(ctx, cmd, 4000);
                        if (out != null && out.length() > 0) {
                            int l = -1, r = -1;
                            for (String ln : out.split("\n")) {
                                int p = ln.indexOf("int32Values: [");
                                int q = p < 0 ? -1 : ln.indexOf(']', p);
                                if (p < 0 || q < 0) continue;
                                int v;
                                try {
                                    v = Integer.parseInt(ln.substring(p + 14, q).trim());
                                } catch (NumberFormatException e) {
                                    continue;
                                }
                                if (ln.contains("SCU_BSD_LEFT_WARNING")) l = v;
                                else if (ln.contains("SCU_BSD_RIGHT_WARNING")) r = v;
                            }
                            // 两侧都要拿到才采信：某一侧从未产生事件时 dumpsys 没有对应行，
                            // 此时保持探针的值，不要用一个 -1 把已知状态抹掉。
                            if (l >= 0 && r >= 0) {
                                dumpL = l;
                                dumpR = r;
                                dumpAt = System.currentTimeMillis();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }).start();
    }

    /** 读常驻盲区探针的最新值；未就绪或数据已失效返回 null */
    public static int[] readBsd() {
        long now = System.currentTimeMillis();
        int l = -1, r = -1;
        if (bsdLiveL >= 0 && bsdLiveR >= 0
                // 8 秒没有新数据就视为失效（探针心跳约 1-2 秒）。探针流一旦断开，
                // bsdLiveL/R 会永久停在最后一个值，宁可短暂不显示，也不要显示过期的安全警告。
                && now - bsdLiveAt <= 8000) {
            l = bsdLiveL;
            r = bsdLiveR;
        }
        // 文件兜底：只有当流明显停滞（文件读取时刻比流最后一次更新还晚 1.5 秒以上）
        // 才采信文件值，避免文件 2 秒轮询的滞后把刚更新的流值顶回去。
        if (fileL >= 0 && fileR >= 0 && now - fileAt <= 5000 && fileAt > bsdLiveAt + 1500) {
            l = fileL;
            r = fileR;
        }
        // dumpsys 优先级最高：探针的 getProperty 会冻结（读到 1 就不再更新），
        // 而 dumpsys 是服务端缓存直读，3 秒内采信它。
        if (dumpL >= 0 && dumpR >= 0 && now - dumpAt <= 3000) {
            l = dumpL;
            r = dumpR;
        }
        if (l < 0 || r < 0) return null;
        return new int[]{l, r};
    }

    /**
     * 每 2 秒读一次探针写的状态文件。
     * 走共享 adb 连接（一次 cat，几十毫秒），与电压探针串行但不会互相卡死。
     */
    private static void startBsdFilePoller(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        String out = shell(ctx, "cat /data/local/tmp/p7bsd.state 2>/dev/null", 4000);
                        if (out != null && out.indexOf("BSDL=") >= 0) {
                            int l = -1, r = -1;
                            long up = 0;   // 探针写入时刻（车机本地毫秒）
                            for (String tk : out.trim().split("\\s+")) {
                                int eq = tk.indexOf('=');
                                if (eq <= 0) continue;
                                String k = tk.substring(0, eq), v = tk.substring(eq + 1);
                                try {
                                    if (k.equals("BSDL")) l = Integer.parseInt(v);
                                    else if (k.equals("BSDR")) r = Integer.parseInt(v);
                                    else if (k.equals("UP")) up = Long.parseLong(v);
                                } catch (NumberFormatException ignored) { }
                            }
                            // 必须校验文件内容自己的时间戳：探针死掉后文件会永久停在最后一个值，
                            // 只看"什么时候读的"会把过期值当成实时值，圆弧就永远卡在旧状态。
                            long now = System.currentTimeMillis();
                            if (l >= 0 && r >= 0 && up > 0 && now - up <= 5000) {
                                fileL = l;
                                fileR = r;
                                fileAt = now;
                            } else {
                                fileL = -1;
                                fileR = -1;
                                fileAt = 0;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }).start();
    }

    /** 解析探针输出：VOLT=14.20 BSDL=0 BSDR=0 SOC=79 CABIN=19.0 TPFL=241.648 ... TPRR=244.394 */
    private static float[] parse(String out) {
        if (out == null || out.indexOf("VOLT=") < 0) return null;
        float volt = Float.NaN, bl = -1, br = -1, soc = -1, cabin = Float.NaN;
        float[] tp = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        for (String tok : out.split("[\\s,]+")) {
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            String k = tok.substring(0, eq).trim();
            String v = tok.substring(eq + 1).trim();
            try {
                if (k.equals("VOLT")) volt = Float.parseFloat(v);
                else if (k.equals("BSDL")) bl = Float.parseFloat(v);
                else if (k.equals("BSDR")) br = Float.parseFloat(v);
                else if (k.equals("SOC")) soc = Float.parseFloat(v);
                else if (k.equals("CABIN")) cabin = Float.parseFloat(v);
                else if (k.equals("TPFL")) tp[0] = Float.parseFloat(v);
                else if (k.equals("TPFR")) tp[1] = Float.parseFloat(v);
                else if (k.equals("TPRL")) tp[2] = Float.parseFloat(v);
                else if (k.equals("TPRR")) tp[3] = Float.parseFloat(v);
            } catch (NumberFormatException ignored) { }
        }
        if (Float.isNaN(volt) && bl < 0) return null;
        return new float[]{volt, bl, br, soc, tp[0], tp[1], tp[2], tp[3], cabin};
    }
}
