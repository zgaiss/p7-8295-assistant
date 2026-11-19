package com.xiaopeng.clusterassist;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;

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
        total += pushPlayerIfDiff(progress);
        total += pushAssetIfDiff("charge.pack", P_PACK, progress);
        // 无条件补权限：pushAssetIfDiff 在远端大小相同时会跳过，
        // 早期以 644 落地的文件就再也没机会修正，播放器会一直 permission denied。
        ftp().chmod(0755, P_PLAYER);
        return total;
    }

    /**
     * 播放器按内容 md5 比对，不一致才重传。
     *
     * 为什么不能用大小比：换了几版播放器（C 版 2580952、Go 版 1966232）之后又回来改过一次，
     * 有过两个不同实现大小完全相同的情况，只看大小会把新版判成"已就绪"永远不更新。
     * 播放器只有 2MB，App 侧算 md5 是毫秒级；远端留一个 playerver 文件存上次的 md5。
     *
     * @return 本次上传字节数（0 = 仪表上已经是同一份）
     */
    private long pushPlayerIfDiff(Ftp.Progress progress) throws Exception {
        byte[] b = readAsset("p7videoplay");
        if (b.length < 100000) return 0;
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] d = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        String want = sb.toString();

        String have = null;
        try {
            byte[] r = ftp().get(DIR + "playerver");
            if (r != null) have = new String(r, "UTF-8").trim();
        } catch (Throwable ignored) {
            // 文件不存在：当成没版本，走上传
        }
        // 双保险：光看 playerver 不够 —— 只要有一处删文件时漏掉标记，
        // 就会出现"标记说已就绪、文件其实不在"的状态，播放器再也传不上去。
        // 所以除了 md5 一致，还要求远端文件真的在、大小也对。
        if (want.equals(have) && ftp().size(P_PLAYER) == b.length) return 0;

        File f = assetToFile("p7videoplay", "p7videoplay.bin");
        String tmp = P_PLAYER + ".part";
        ftp().putFile(tmp, f, progress);
        if (!ftp().rename(tmp, P_PLAYER)) {
            ftp().delete(P_PLAYER);
            if (!ftp().rename(tmp, P_PLAYER)) {
                ftp().putFile(P_PLAYER, f, progress);
                ftp().delete(tmp);
            }
        }
        ftp().put(DIR + "playerver", want.getBytes("UTF-8"));
        return b.length;
    }

    /**
     * 还原充电动画包：删掉仪表上的本地播放器和帧包。
     *
     * 依据：仪表端 daemon 每轮主循环用 videoUsable() 判断资源是否齐备
     * （p7videoplay > 100KB 且 charge.pack > 100KB）。两个文件删掉之后
     * videoUsable() 立即为假 → wantVideo 为假 → daemon 在下一轮（50ms）
     * 自动退出视频模式并 stopVideo() 停掉正在跑的播放器，
     * 不需要额外下信号，也不会留下继续占着 fb1 的孤儿进程。
     *
     * 之后充电时充电页拿不到背景图（paintChargeBg 返回 false），整块不绘制，
     * 屏幕露出原厂充电界面；要恢复本地动画，点「上传资源」即可从 APK 重新推回。
     *
     * 删除结果用 LIST 复核而不是 SIZE：SIZE 在"文件不存在"和"连接失败"时
     * 都返回 -1，分不清两者，会把断连误报成删除成功。
     *
     * @return 本次真正删掉的文件数（0 = 仪表上本来就没有，或 FTP 拒绝了 DELE）
     */
    public int restoreChargeVideo() throws Exception {
        int n = 0;
        if (ftp().delete(P_PLAYER)) n++;
        if (ftp().delete(P_PACK)) n++;
        // 顺带清掉上传中断留下的临时文件
        ftp().delete(P_PLAYER + ".part");
        ftp().delete(P_PACK + ".part");
        // 必须一起删版本标记：playerver 存的是"仪表上那份播放器的 md5"，
        // 文件删了标记还在，下次「上传资源」会因为 md5 相同直接跳过，
        // 结果就是播放器永远传不回去（2026-11-20 实车踩到）。
        ftp().delete(DIR + "playerver");
        // LIST 失败会抛异常，正好用来区分"真的没有了"和"根本没连上"
        List<String> names = ftp().list(DIR);
        boolean playerLeft = false, packLeft = false;
        for (String line : names) {
            String f = line.trim();
            int sp = f.lastIndexOf(' ');
            if (sp >= 0) f = f.substring(sp + 1);
            if ("p7videoplay".equals(f)) playerLeft = true;
            if ("charge.pack".equals(f)) packLeft = true;
        }
        if (playerLeft || packLeft) {
            StringBuilder still = new StringBuilder();
            if (playerLeft) still.append("p7videoplay");
            if (packLeft) still.append(still.length() > 0 ? "、charge.pack" : "charge.pack");
            throw new Exception("删除未生效，仪表上仍有：" + still);
        }
        return n;
    }

    private long pushAssetIfDiff(String asset, String remote, Ftp.Progress progress) throws Exception {
        long want = assetsSize(asset);
        if (want <= 0) return 0;
        if (ftp().size(remote) == want) return 0;
        File f = assetToFile(asset, asset.replace('.', '_') + ".bin");
        // 先传 <remote>.part 再 RENAME，让替换对仪表端是原子的。
        //
        // 仪表 daemon 现在会盯着 charge.pack 的 size/mtime，一变就重启播放器去加载新包
        // （播放器只在启动时 read() 一次包）。如果这里直接往正文件上覆盖，
        // daemon 可能在上传中途就看见 mtime 变化，去 load 一个半截包 → 启动失败 → 闪黑。
        // 走 .part 的话，正文件要么是旧的完整包、要么是新的完整包，中间不存在半截态。
        String tmp = remote + ".part";
        ftp().putFile(tmp, f, progress);
        if (!ftp().rename(tmp, remote)) {
            // 有些 FTP 实现不允许 RNTO 覆盖已存在文件
            ftp().delete(remote);
            if (!ftp().rename(tmp, remote)) {
                ftp().putFile(remote, f, progress);
                ftp().delete(tmp);
            }
        }
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

    /**
     * 设置写屏前是否等垂直同步。
     *
     * on：撕裂线固定在同一个位置，但帧周期被量化到 16.7ms 的倍数，
     *     一帧写不满时可能从 24fps 掉到 20fps；off：更容易跑满，撕裂位置随机。
     */
    public void setVideoVsync(String mode) throws Exception {
        if ("off".equals(mode)) {
            ftp().put(DIR + "videovsync", "off".getBytes("UTF-8"));
        } else {
            ftp().delete(DIR + "videovsync");
        }
        ftp().put(DIR + "restartvideo", new byte[]{'1'});
    }

    /**
     * 设置写屏方式：pwrite（播放器默认）或 mmap。
     *
     * 播放器里两条路：pwrite 每帧要走系统调用 + 内核 copy_from_user；
     * mmap 把显存映射进进程直接 memcpy，少一次拷贝。快不快取决于映射的缓存属性，
     * 所以做成可切换、用「刷新状态」里的写屏耗时实测对比，别靠猜。
     */
    public void setVideoWrite(String mode) throws Exception {
        if ("mmap".equals(mode)) {
            ftp().put(DIR + "videowrite", "mmap".getBytes("UTF-8"));
        } else {
            // 删文件即回到播放器默认的 pwrite
            ftp().delete(DIR + "videowrite");
        }
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

    /**
     * 诊断用：[播放器, 帧包, 播放器.part, 帧包.part] 的远端大小。
     *
     * .part 有值说明上传正在进行或中途断掉 —— 这时候「检查资源」会看到正文件缺失，
     * 很容易误判成"没传上去"，其实是还没传完。
     */
    public long[] videoResDiag() {
        try {
            return new long[]{ftp().size(P_PLAYER), ftp().size(P_PACK),
                    ftp().size(P_PLAYER + ".part"), ftp().size(P_PACK + ".part")};
        } catch (Throwable t) {
            return new long[]{0, 0, 0, 0};
        }
    }

    /** 仪表上记录的播放器版本（md5），空 = 没有记录 */
    public String remotePlayerVer() {
        try {
            byte[] b = ftp().get(DIR + "playerver");
            return b == null ? "" : new String(b, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
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
        // 视频模式资源（播放器 + 帧包）：首次部署就带上，
        // 之后开通充电页时按大小比对，一致就不再传
        try {
            if (pushChargeVideo(progress) > 0) {
                ftp().chmod(0755, P_PLAYER);
                // 换了包就让仪表重启一次播放器。播放器只在启动时把 charge.pack
                // 整个读进内存，不重启就一直播旧帧（以前要「还原」再「上传」才换得掉）。
                // 新版 daemon 自己会发现换包，但这里发出的信号对旧版同样有效，
                // 两边共用 5 秒冷却，不会重复黑屏。
                requestRestartVideo();
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
