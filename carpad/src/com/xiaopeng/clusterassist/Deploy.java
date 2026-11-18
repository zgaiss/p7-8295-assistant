package com.xiaopeng.clusterassist;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * 部署与运维操作：全部通过 FTP 落到仪表 /data/p7assist/。
 *
 * 约定（与仪表端守护进程一致）：
 *   config.json   写完即热加载，无需重启
 *   ext.json      车机推送的电压/导航/日夜，超过 ttlMs 忽略
 *   status.json   守护进程每 2 秒回写
 *   p7assist.new  新二进制，守护进程发现后校验 ELF 并原地 exec 升级
 *   restart       触发守护进程重新载入
 *   stop          让守护进程清屏、关层并退出（之后需要 shell 才能再拉起）
 */
public class Deploy {
    public static final String DIR = "p7assist/";
    public static final String P_BIN = DIR + "p7assist";
    public static final String P_NEW = DIR + "p7assist.new";
    public static final String P_CFG = DIR + "config.json";
    public static final String P_EXT = DIR + "ext.json";
    public static final String P_STATUS = DIR + "status.json";
    public static final String P_LOG = DIR + "log/hud.log";
    public static final String P_BOOTLOG = DIR + "log/boot.log";
    public static final String P_CTL = DIR + "p7assistctl";
    public static final String P_FONTS = DIR + "fonts/Alibaba-PuHuiTi-Regular.ttf";
    /** 充电动画帧包（121 帧 JPEG，约 5.2MB），供仪表端循环播放 */
    public static final String P_CHG = DIR + "charge.bin";
    /** 视频模式：仪表本地播放器（静态 ARM）+ P7VP 帧包 */
    public static final String P_PLAYER = DIR + "p7videoplay";
    public static final String P_PACK = DIR + "charge.pack";
    public static final String P_BOOT = DIR + "p7boot.sh";
    public static final String P_WATCH = DIR + "p7watch.sh";
    public static final String P_INSTALL = DIR + "install-autostart.sh";
    public static final String P_UNINSTALL = DIR + "uninstall-autostart.sh";

    private final Context ctx;
    private final Prefs prefs;
    private Ftp ftp;
    /** 上次 finalizeInstall 是否成功用 SITE CHMOD 赋了权限（决定 shell 命令长短） */
    public boolean lastChmodOK = false;
    /** 上次 finalizeInstall 的逐步诊断，失败时显示在弹窗里，便于远程排查 */
    public String lastDiag = "";

    public Deploy(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
    }

    public Ftp ftp() {
        if (ftp == null) ftp = new Ftp(prefs.host, prefs.port, prefs.user, prefs.pass);
        return ftp;
    }

    public void reset() {
        if (ftp != null) ftp.close();
        ftp = null;
    }

    public boolean ping() {
        return ftp().ping();
    }

    public String statusRaw() throws Exception {
        byte[] b = ftp().get(P_STATUS);
        return new String(b, "UTF-8");
    }

    public JSONObject status() throws Exception {
        return new JSONObject(statusRaw());
    }

    /**
     * 投一帧充电动画给仪表（charge.frm）。
     *
     * 为什么由车机出帧：仪表那颗 CPU 自己播全屏动画，一帧（解码+转画布+上屏）
     * 要 400ms 以上，跟着 30fps 的渲染循环画就是整块卡死。车机算力够，负责出帧，
     * 仪表只做解码+上屏。
     * 先写 .tmp 再改名，仪表那边靠 mtime 变化判断有新帧，不会读到半截文件。
     */
    public boolean pushChargeFrame(byte[] jpeg) throws Exception {
        if (jpeg == null || jpeg.length < 512) return false;
        String tmp = DIR + "charge.frm.tmp";
        String dst = DIR + "charge.frm";
        ftp().put(tmp, jpeg);
        if (!ftp().rename(tmp, dst)) {
            // 某些 FTP 实现要求目标不存在；删一次再改名
            ftp().delete(dst);
            return ftp().rename(tmp, dst);
        }
        return true;
    }

    /** 读仪表端充电原始值（chgraw.json，由 daemon 每秒落盘）；读不到返回 null */
    public String chargeRaw() {
        try {
            byte[] b = ftp().get(DIR + "chgraw.json");
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    public String logTail(int maxBytes) {
        try {
            byte[] b = ftp().get(P_LOG);
            int from = Math.max(0, b.length - maxBytes);
            return new String(b, from, b.length - from, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 推送显示设置（全局开关也在里面）。先传 .new 再改名，避免仪表读到半截 JSON。 */
    public void pushConfig(Prefs p) throws Exception {
        byte[] data = p.toJson().getBytes("UTF-8");
        ftp().put(P_CFG + ".new", data);
        if (!ftp().rename(P_CFG + ".new", P_CFG)) {
            ftp().put(P_CFG, data);
        }
    }

    /** 推送充电动画帧包；仪表端已是同一份（大小一致）就跳过。返回上传字节数，0 = 无需上传 */
    public long pushChargeAnim() throws Exception {
        long want = assetsSize("charge.bin");
        if (want <= 0) return 0;
        if (ftp().size(P_CHG) == want) return 0;
        File f = assetToFile("charge.bin", "charge.bin");
        ftp().putFile(P_CHG, f, null);
        return want;
    }

    /**
     * 视频模式资源：本地播放器 + 帧包。
     *
     * 逐帧推 JPEG 的瓶颈不在网络：仪表每帧 decode 20~34ms + 缩放 18~28ms + 写屏 37~46ms，
     * 一帧就要 90ms（≈11fps）。改成"帧包一次性传完 + 仪表本地 24fps 播放"之后实测满帧，
     * 见《充电视频模式》说明。这里只在文件大小不一致时上传，避免每次开充电页都传 6.5MB。
     *
     * @return 本次上传的字节数（0 = 两边一致，无需上传）
     */
    public long pushChargeVideo(Ftp.Progress progress) throws Exception {
        long total = 0;
        total += pushAssetIfDiff("p7videoplay", P_PLAYER, progress);
        total += pushAssetIfDiff("charge.pack", P_PACK, progress);
        if (total > 0) {
            // FTP 落地是 644，播放器必须可执行；SITE CHMOD 不支持时仪表端会在启动前兜底
            ftp().chmod(0755, P_PLAYER);
        }
        return total;
    }

    private long pushAssetIfDiff(String asset, String remote, Ftp.Progress progress) throws Exception {
        long want = assetsSize(asset);
        if (want <= 0) return 0;
        if (ftp().size(remote) == want) return 0;
        File f = assetToFile(asset, asset.replace('.', '_') + ".bin");
        ftp().putFile(remote, f, progress);
        return want;
    }

    /** 推送 ext.json（电压/导航/日夜），同样走 .new + rename */
    public void pushExt(String json) throws Exception {
        byte[] data = json.getBytes("UTF-8");
        ftp().put(P_EXT + ".new", data);
        if (!ftp().rename(P_EXT + ".new", P_EXT)) {
            ftp().put(P_EXT, data);
        }
    }

    /** 设置仪表播放帧率（8~30）并让播放器重启一次 */
    public void setVideoFps(int fps) throws Exception {
        ftp().put(DIR + "videofps", (fps + "").getBytes("UTF-8"));
        ftp().put(DIR + "restartvideo", new byte[]{'1'});
    }

    /** 仪表端视频资源大小：[播放器, 帧包]，缺失为 0 */
    public long[] videoResSize() {
        try {
            return new long[]{ftp().size(P_PLAYER), ftp().size(P_PACK)};
        } catch (Throwable t) {
            return new long[]{0, 0};
        }
    }

    /** 让仪表重启本地播放器（视频模式调试用） */
    public void requestRestartVideo() throws Exception {
        ftp().put(DIR + "restartvideo", new byte[]{'1'});
    }

    /** 读仪表端播放器日志尾部（看真实 fps/解码/写屏耗时） */
    public String videoLogTail(int maxBytes) {
        try {
            byte[] b = ftp().get(DIR + "log/p7video.log");
            if (b == null) return null;
            int from = Math.max(0, b.length - maxBytes);
            return new String(b, from, b.length - from, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 触发守护进程重新载入（等价于 restart 文件） */
    public void requestReload() throws Exception {
        ftp().put(DIR + "restart", new byte[]{'1'});
    }

    /** 上传新二进制；守护进程在线会自动升级 */
    public void pushBinary(Ftp.Progress progress) throws Exception {
        File f = assetToFile("p7assist", "p7assist.bin");
        ftp().putFile(P_NEW, f, progress);
        long remote = ftp().size(P_NEW);
        if (remote != f.length()) {
            throw new Exception("上传校验失败：本地 " + f.length() + " 字节，远端 " + remote + " 字节");
        }
    }

    /** 一键部署：建目录 + 上传全部文件（二进制 + 全部脚本 + 默认配置），不覆盖已存在的 config */
    public void install(Ftp.Progress progress) throws Exception {
        ftp().mkdir("p7assist");
        ftp().mkdir("p7assist/log");
        ftp().mkdir("p7assist/fonts");
        // 中文字体：歌词显示必需（仪表内置 cjk 字体只有十几个标签字，缺它会显示豆腐块）
        if (ftp().size(P_FONTS) != assetsSize("Alibaba-PuHuiTi-Regular.ttf")) {
            File f = assetToFile("Alibaba-PuHuiTi-Regular.ttf", "puhui.ttf");
            ftp().putFile(P_FONTS, f, progress);
        }
        ftp().put(P_CTL, readAsset("p7assistctl"));
        ftp().put(P_BOOT, readAsset("p7boot.sh"));
        ftp().put(P_WATCH, readAsset("p7watch.sh"));
        ftp().put(P_INSTALL, readAsset("install-autostart.sh"));
        ftp().put(P_UNINSTALL, readAsset("uninstall-autostart.sh"));
        // 尽力赋权限：升级场景重传脚本可能被 FTP 覆盖写成 644，这里补上（SITE CHMOD 不支持也没关系，
        // 首次部署时 finalizeInstall 会再兜底；脚本本身用 sh 调用不依赖 x 位）
        for (String f : new String[]{P_CTL, P_BOOT, P_WATCH, P_INSTALL, P_UNINSTALL}) {
            ftp().chmod(0755, f);
        }
        pushBinary(progress);
        // 视频模式资源（播放器 ~2.5MB + 帧包 ~4MB）：首次部署就带上，
        // 之后开通充电页时按大小比对，一致就不再传
        try {
            if (pushChargeVideo(progress) > 0) {
                ftp().chmod(0755, P_PLAYER);
            }
        } catch (Throwable ignored) { }
        if (ftp().size(P_CFG) <= 0) {
            ftp().put(P_CFG, prefs.toJson().getBytes("UTF-8"));
        }
    }

    /**
     * 首次部署收尾（守护进程不在线时调用）：尽量用 FTP 自动完成「转正 + 赋权限 + 可能的执行」。
     * 返回 true = 已全自动拉起（真免 shell）；false = 还需用户在 shell 里执行一条命令。
     */
    public boolean finalizeInstall() {
        StringBuilder d = new StringBuilder();
        lastChmodOK = false;
        // 1) 转正：p7assist.new -> p7assist（FTP 改名；失败不阻断，脚本会自己兜底）
        try {
            if (ftp().size(P_NEW) > 0) {
                if (ftp().size(P_BIN) > 0) ftp().delete(P_BIN);
                boolean ok = ftp().rename(P_NEW, P_BIN);
                d.append("FTP 转正 ").append(ok ? "成功" : "失败(交给脚本)").append("\n");
            }
        } catch (Throwable t) {
            d.append("FTP 转正异常 ").append(t).append("\n");
        }

        // 2) 管理通道 30510：先直连探活；不通就走「车机 shell 侧 nc 中继」。
        //    外部实测：部分固件上普通 App 直连会「连得上但返回 0 字节」，
        //    而历史首次部署中由 adb shell(uid=shell) 起的 busybox nc 每次都有响应。
        //    注意：本方法必须在后台线程调用，主线程会被 NetworkOnMainThreadException 拦下。
        // 末尾 echo 保证管理服务一定有 stdout 返回（p7boot.sh 自身输出全写进了日志文件）
        String boot = "sh /data/p7assist/p7boot.sh; echo P7BOOT_OK";
        String rpcHost = prefs.host;
        int rpcPort = IcRpc.IC_RPC_PORT;
        IcRelay relay = null;
        boolean reachable = false;

        try {
            String probe = IcRpc.execVia(rpcHost, rpcPort, "echo IC_OK");
            reachable = probe != null && probe.contains("IC_OK");
            d.append("30510 直连 ").append(reachable ? "OK" : "返回 0 字节/无 IC_OK").append("\n");
        } catch (Throwable t) {
            d.append("30510 直连 ").append(t.getClass().getSimpleName()).append(" ")
                    .append(t.getMessage()).append("\n");
        }

        if (!reachable) {
            try {
                relay = new IcRelay(ctx, prefs.host);
                relay.start();
                d.append("已起车机 shell 中继 :").append(IcRelay.PORT).append("\n");
                for (String h : relay.loopbackHosts()) {
                    try {
                        String p = IcRpc.execVia(h, IcRelay.PORT, "echo IC_OK");
                        if (p != null && p.contains("IC_OK")) {
                            rpcHost = h;
                            rpcPort = IcRelay.PORT;
                            reachable = true;
                            d.append("中继探活 OK (").append(h).append(")\n");
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
                if (!reachable) d.append("中继探活 无应答\n");
            } catch (Throwable t) {
                d.append("中继建立失败 ").append(t.getMessage()).append("\n");
            }
        }

        if (reachable) {
            try {
                // nc -e 每接受一个连接就退出、由 while 重启监听，探活后留一点时间再发部署命令
                if (relay != null) {
                    try { Thread.sleep(400); } catch (Throwable ignored) { }
                }
                String out = IcRpc.execVia(rpcHost, rpcPort, boot);
                lastChmodOK = true;
                d.append("30510 执行(").append(rpcHost).append(") ")
                        .append(out == null ? "(无输出)" : out.trim()).append("\n");
                if (waitDaemon(10000)) {
                    lastDiag = d.toString();
                    if (relay != null) relay.stop();
                    return true;
                }
                d.append("守护进程未就绪\n");
            } catch (Throwable t) {
                d.append("30510 执行异常 ").append(t.getClass().getSimpleName())
                        .append(" ").append(t.getMessage()).append("\n");
            }
        }
        if (relay != null) relay.stop();

        // 3) 实车结论：该仪表 FTP 对 SITE HELP 一律回 500，SITE CHMOD / SITE EXEC 都不支持。
        //    这里只做探测记录，不再把它们的返回值当作「部署成功」的依据。
        boolean chmodOk = true;
        for (String f : new String[]{P_BIN, P_CTL, P_BOOT, P_WATCH, P_INSTALL, P_UNINSTALL}) {
            if (!ftp().chmod(0755, f)) { chmodOk = false; break; }
        }
        lastChmodOK = chmodOk;
        d.append("SITE CHMOD ").append(chmodOk ? "支持" : "不支持(该固件预期)").append("\n");
        String r = ftp().siteExec(boot);
        d.append("SITE EXEC ").append(r.length() == 0 ? "不支持" : r.trim()).append("\n");
        d.append("→ 30510 直连与 shell 中继都不通，只能在仪表 shell 里执行那条命令\n");
        lastDiag = d.toString();
        return probeAlive(1500);
    }

    /**
     * 判断守护进程是否活着：不看绝对时间戳，只看 status.json 是否被持续改写。
     * 仪表 RTC 不走（实车重启后时钟停在 2019），它写的 ts 与车机时间不可比，
     * 用 System.currentTimeMillis()-ts 会同时产生「假在线」（ts 偏大，差值为负）和
     * 「假离线」（ts 偏小，差值巨大）。间隔采样两次，ts 变化即说明进程在写。
     */
    /**
     * 一键卸载：把仪表端还原成出厂状态（停进程 → 移除开机自启 → 删除 /data/p7assist）。
     *
     * 顺序不能颠倒：
     *   1. 先杀看门狗 p7watch.sh —— 否则它几秒内就会把 daemon 拉回来；
     *   2. 再杀 daemon —— 运行中的二进制即使文件被删也会继续占着内存，画面不会消失；
     *   3. 最后才删目录 + 移除 rc.local 自启行。
     *
     * 成功判据是仪表侧回显 UNINSTALL_OK（即删除后目录确实不存在）。
     * 必须在后台线程调用（走 30510 socket / 中继）。
     */
    public boolean uninstall(StringBuilder diag) {
        StringBuilder d = new StringBuilder();
        String cmd = "for p in $(ps | grep 'p7watch.sh' | grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done; "
                + "for p in $(pidof p7assist); do kill -9 $p 2>/dev/null; done; "
                + "sleep 1; "
                + "for p in $(pidof p7assist); do kill -9 $p 2>/dev/null; done; "
                // 关键：kill -9 是 SIGKILL，daemon 里的信号处理（清零 fb1 + FBIOBLANK 关层）
                // 根本没机会执行，前景层最后一帧会一直留在屏幕上，必须重启仪表才消失。
                // 进程此刻已停、没人再重画，这里显式跑一次 cleanup 清屏并关闭前景层。
                + "if [ -x /data/p7assist/p7assist ]; then /data/p7assist/p7assist cleanup >/dev/null 2>&1 || true; fi; "
                + "sleep 1; "
                + "sh /data/p7assist/uninstall-autostart.sh >/dev/null 2>&1 || true; "
                + "rm -rf /data/p7assist; "
                + "if [ -d /data/p7assist ]; then echo STILL_THERE; else echo UNINSTALL_OK; fi";

        String out = null;
        try {
            out = IcRpc.execVia(prefs.host, IcRpc.IC_RPC_PORT, cmd);
        } catch (Throwable t) {
            d.append("30510 直连失败: ").append(t.getMessage()).append("\n");
        }
        if (out == null || !out.contains("UNINSTALL_OK")) {
            IcRelay relay = null;
            try {
                relay = new IcRelay(ctx, prefs.host);
                relay.start();
                for (String h : relay.loopbackHosts()) {
                    try {
                        String r = IcRpc.execVia(h, IcRelay.PORT, cmd);
                        if (r != null && r.contains("UNINSTALL_OK")) {
                            out = r;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable t) {
                d.append("中继失败: ").append(t.getMessage()).append("\n");
            } finally {
                if (relay != null) {
                    try { relay.stop(); } catch (Throwable ignored) { }
                }
            }
        }
        boolean ok = out != null && out.contains("UNINSTALL_OK");
        if (ok) {
            d.append("已停止仪表进程并删除 /data/p7assist\n");
            d.append("已移除开机自启（rc.local）\n");
            reset();
        } else {
            d.append("未能确认卸载，仪表返回: ")
                    .append(out == null ? "(无响应)" : out.trim()).append("\n");
        }
        if (diag != null) diag.append(d);
        return ok;
    }

    public boolean probeAlive(int timeoutMs) {
        long last = Long.MIN_VALUE;
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try {
                long ts = status().optLong("ts", 0);
                if (last != Long.MIN_VALUE && ts != last) return true;
                last = ts;
            } catch (Throwable ignored) { }
            try { Thread.sleep(1200); } catch (Throwable ignored) { }
        }
        return false;
    }

    private boolean waitDaemon(int timeoutMs) {
        return probeAlive(timeoutMs);
    }

    /** 兼容旧调用：只表示 status.json 存在，不再用于判定存活（存活请用 probeAlive） */
    public boolean daemonAlive(JSONObject st) {
        return st != null && st.optLong("ts", 0) > 0;
    }

    public byte[] readAsset(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    /** 读取 asset 的大小（字节），用于判断是否与远端一致、避免重复上传大文件 */
    public long assetsSize(String name) throws Exception {
        InputStream in = ctx.getAssets().open(name);
        long n = 0;
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) n += r;
        in.close();
        return n;
    }

    public File assetToFile(String asset, String name) throws Exception {
        byte[] b = readAsset(asset);
        File f = new File(ctx.getCacheDir(), name);
        java.io.FileOutputStream o = new java.io.FileOutputStream(f);
        o.write(b);
        o.flush();
        o.close();
        return f;
    }

    /** 读回仪表上的二进制算 md5（3.8MB，几秒钟），用于校验部署是否完整 */
    public String remoteBinaryMd5() throws Exception {
        byte[] b = ftp().get(P_BIN);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] d = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public long remoteBinarySize() {
        return ftp().size(P_BIN);
    }

    /** p7boot.sh 每次执行都会追加一行；有内容 = 程序确实被拉起过，不是「文件躺在里面」 */
    public long remoteBootLogSize() {
        return ftp().size(P_BOOTLOG);
    }

    public long remoteStatusSize() {
        return ftp().size(P_STATUS);
    }

    /** 字体是否已部署到位（按大小判断，8.6M 大文件不做 md5 以免拖慢校验） */
    public boolean remoteFontOk() {
        try {
            long want = assetsSize("Alibaba-PuHuiTi-Regular.ttf");
            long got = ftp().size(P_FONTS);
            return got == want;
        } catch (Throwable t) {
            return false;
        }
    }

    public String localBinaryMd5() {
        try {
            byte[] b = readAsset("p7assist");
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
