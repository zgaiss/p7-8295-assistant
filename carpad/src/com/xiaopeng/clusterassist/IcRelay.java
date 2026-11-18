package com.xiaopeng.clusterassist;

import android.content.Context;
import android.util.Base64;

/**
 * 车机 shell 侧的 30510 中继（复刻实车验证过的 tools/p7tunnel.sh）。
 *
 * 背景：部分固件上普通 App（uid=u0_a*）直连仪表 30510 会出现
 * 「TCP 连得上、帧逐字节正确、但返回 0 字节」；而历史首次部署时，
 * 由 adb shell（uid=shell）启动的 busybox nc 每次都能拿到正常响应。
 * 因此直连失败时，借本地 adb 在车机上起一个 nc -l 中继，
 * App 改连 127.0.0.1:9000，由 shell 进程把帧转发到仪表 30510。
 *
 * 用完必须 stop()：这条中继是通往仪表 root shell 的入口，不能留在车上。
 */
public final class IcRelay {
    public static final int PORT = 9000;
    private static final String SCRIPT = "/data/local/tmp/p7relay30510.sh";
    /** 用变量拼接，避免这条命令自身的命令行被 grep 匹配到、把自己也 kill 掉（p7tunnel.sh 的写法） */
    private static final String KILL =
            "P=" + PORT + "; for p in $(ps -A -o PID,ARGS 2>/dev/null | grep \"nc -l -p $P\""
                    + " | grep -v grep | awk '{print $1}'); do kill -9 $p 2>/dev/null; done";

    private final Context ctx;
    private final String icIp;
    private boolean started;

    public IcRelay(Context ctx, String icIp) {
        this.ctx = ctx;
        this.icIp = (icIp == null || icIp.isEmpty()) ? "172.20.1.40" : icIp;
    }

    public void start() throws Exception {
        String script = "#!/system/bin/sh\nexec /system/bin/busybox nc " + icIp + " 30510\n";
        String b64 = Base64.encodeToString(script.getBytes("UTF-8"), Base64.NO_WRAP);
        LocalAdb.shell(ctx, "echo " + b64 + " | base64 -d > " + SCRIPT
                + "; chmod 755 " + SCRIPT + "; echo RELAY_SCRIPT_OK", 15000);
        LocalAdb.shell(ctx, KILL + "; echo RELAY_CLEAN", 12000);
        // nc -e 每接受一个连接就退出，用 while 让它继续监听（与 p7tunnel.sh 一致）
        LocalAdb.shell(ctx, "setsid sh -c 'while true; do /system/bin/busybox nc -l -p " + PORT
                + " -e " + SCRIPT + "; done' >/dev/null 2>&1 & echo RELAY_UP", 12000);
        started = true;
        Thread.sleep(900);
    }

    /** 中继可能监听在 IPv6 [::]，依次尝试这些回环地址 */
    public String[] loopbackHosts() {
        return new String[]{"127.0.0.1", "::1", "localhost"};
    }

    public void stop() {
        if (!started) return;
        started = false;
        try {
            LocalAdb.shell(ctx, KILL + "; rm -f " + SCRIPT + "; echo RELAY_DOWN", 12000);
        } catch (Throwable ignored) { }
    }
}
