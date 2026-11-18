package com.xiaopeng.clusterassist;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * 极简 adb 客户端：连车机自己的 adbd（127.0.0.1:5050），以 shell 身份执行命令。
 *
 * 为什么需要它：小鹏的 CarPermissionManager 会拦住普通应用读 CarProperty
 * （实测 SecurityException: CarPermissionManager has not grated! ret:2），
 * 只有 shell 身份能读。原版 worker.jar 也是这么做的。
 *
 * 协议要点（全部小端）：
 *   包头 24 字节 = command, arg0, arg1, data_length, data_check(=CRC32，不是字节求和！), magic(command 取反)
 *   ⚠️ 历史教训：data_check 曾误写字节求和。adbd 收包先验 CRC32，不对就静默丢包——
 *      CNXN 数据为空（求和=CRC32=0）碰巧能通，但带数据的签名/公钥包全部被丢，
 *      车机框架永远收不到密钥，授权弹窗永不出现，表现为「等待授权超时」死循环。
 *      对照 P7全视影像 v1.40（车友 adb-auth-demo 方案）修正，实测弹窗恢复。
 *   握手：CNXN(0x01000001, maxdata, "host::\0") → 可能收到 AUTH(1, token) → 回 AUTH(2, RSA-SHA1 签名)
 *         → 若密钥未被授权，服务端再发一个 AUTH(1, token) → 回 AUTH(3, base64(adb 公钥) + " 名字\0")
 *         → 车机屏幕弹「允许 USB 调试」，用户点允许后服务端回 CNXN
 *         等待期间保持连接不关（弹窗绑在这条 TCP 连接上，关了弹窗就消失），
 *         每 30s 重发一次公钥防止弹窗被系统收走（P7全视影像 同款策略，180s 上限）。
 *   执行：OPEN(localId, "shell:cmd\0") → OKAY(remoteId, localId) → WRTE 收输出并回 OKAY → CLSE 结束
 */
public final class AdbClient {
    private static final String TAG = "P7Adb";

    private static final int A_VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 256 * 1024;

    private static final int CMD_CNXN = 0x4e584e43;
    private static final int CMD_AUTH = 0x48545541;
    private static final int CMD_OPEN = 0x4e45504f;
    private static final int CMD_OKAY = 0x59414b4f;
    private static final int CMD_CLSE = 0x45534c43;
    private static final int CMD_WRTE = 0x45545257;

    /** 当前握手的授权阶段（给 LocalAdb.authStatus 界面显示用）："" / 签名验证中 / 等待车机确认 / 已授权 */
    public static volatile String phase = "";

    private final PrivateKey key;
    private final RSAPublicKey pub;
    private final String keyName;

    private Socket sock;
    private InputStream in;
    private OutputStream out;
    private int nextLocal = 1;

    public AdbClient(PrivateKey key, RSAPublicKey pub, String keyName) {
        this.key = key;
        this.pub = pub;
        this.keyName = keyName;
    }

    public synchronized boolean isConnected() {
        return sock != null && sock.isConnected() && !sock.isClosed();
    }

    /** 建立连接并完成握手；authWaitMs 是等待用户点「允许」的最长时间 */
    public synchronized void connect(String host, int port, int authWaitMs) throws Exception {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 3000);
        s.setTcpNoDelay(true);
        sock = s;
        in = s.getInputStream();
        out = s.getOutputStream();

        s.setSoTimeout(5000);
        send(CMD_CNXN, A_VERSION, MAX_PAYLOAD, ("host::\0").getBytes("UTF-8"));
        phase = "签名验证中";

        long deadline = System.currentTimeMillis() + authWaitMs;
        long lastPub = 0;   // 0 = 尚未上报过公钥
        int rounds = 0;
        int tokenRounds = 0;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                phase = "";
                throw new IOException("等待授权超时（车机上是否点了「允许 USB 调试」？）");
            }
            Packet p = readPacket(6000);
            if (p == null) {
                // 等授权期间 socket 绝不能关（弹窗绑在这条连接上）；
                // 每 30s 重发一次公钥，防止弹窗被系统收走（P7全视影像 同款策略）
                if (lastPub > 0 && System.currentTimeMillis() - lastPub > 30000) {
                    Log.i(TAG, "配对等待中：30s 无响应，重发公钥保持授权弹窗");
                    send(CMD_AUTH, 3, 0, publicKeyPayload());
                    lastPub = System.currentTimeMillis();
                }
                continue;
            }

            if (p.cmd == CMD_CNXN) {
                s.setSoTimeout(8000);
                phase = "已授权";
                Log.i(TAG, "握手完成: " + new String(p.data, "UTF-8").trim());
                return;
            }
            if (p.cmd == CMD_AUTH) {
                if (p.arg0 == 1) { // TOKEN
                    // 和官方 adb 客户端一致：第一次用签名试，若服务端又发来一个 token，
                    // 说明它没有我们的公钥（签名自然对不上），此时必须主动上报公钥，
                    // 由设备弹出「允许 USB 调试」。一直重签同一个 token 永远不会弹窗。
                    if (tokenRounds++ == 0) {
                        byte[] sig = sign(p.data);
                        send(CMD_AUTH, 2, 0, sig);
                    } else {
                        if (++rounds > 6) { phase = ""; throw new IOException("授权协商反复失败"); }
                        Log.i(TAG, "上报公钥，等待车机确认");
                        phase = "等待车机确认";
                        send(CMD_AUTH, 3, 0, publicKeyPayload());
                        lastPub = System.currentTimeMillis();
                    }
                } else if (p.arg0 == 3) { // 服务端明确要求公钥
                    if (++rounds > 6) { phase = ""; throw new IOException("授权协商反复失败"); }
                    Log.i(TAG, "密钥未授权，发送公钥，等待车机确认");
                    phase = "等待车机确认";
                    send(CMD_AUTH, 3, 0, publicKeyPayload());
                    lastPub = System.currentTimeMillis();
                } else {
                    throw new IOException("未知 AUTH 类型 " + p.arg0);
                }
                continue;
            }
            throw new IOException("握手阶段收到意外指令 0x" + Integer.toHexString(p.cmd));
        }
    }

    /** 执行一条 shell 命令并返回输出 */
    public synchronized String shell(String cmd, int timeoutMs) throws IOException {
        if (!isConnected()) throw new IOException("未连接");
        int local = nextLocal++;
        send(CMD_OPEN, local, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + timeoutMs;
        int remote = -1;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                try { send(CMD_CLSE, local, remote < 0 ? 0 : remote, null); } catch (IOException ignored) { }
                throw new IOException("shell 超时");
            }
            Packet p = readPacket(6000);
            if (p == null) continue;

            if (p.cmd == CMD_OKAY) {
                // 服务端接受了 OPEN，记下它那边的流 id
                if (p.arg1 == local || p.arg1 == 0) remote = p.arg0;
                continue;
            }
            if (p.cmd == CMD_WRTE) {
                // 只收属于本条命令的数据：上一条命令残留的 WRTE 必须丢掉，
                // 否则会把上一条的输出混进来（曾导致探针解析失败、电压时有时无）
                if (isMine(p, local, remote)) {
                    if (p.data != null && p.data.length > 0) buf.write(p.data);
                    send(CMD_OKAY, local, p.arg0, null);
                    if (remote < 0) remote = p.arg0;
                } else {
                    send(CMD_OKAY, local, p.arg0, null);
                }
                continue;
            }
            if (p.cmd == CMD_CLSE) {
                if (isMine(p, local, remote)) break;
                continue;
            }
        }
        return new String(buf.toByteArray(), "UTF-8");
    }

    /**
     * 流式 shell：启动一个常驻命令，返回可逐次读数据的流句柄。
     * 用于常驻探针（如盲区轮询），避免每次 app_process 启动的开销。
     * 注意：持有此流期间，不要在同一 AdbClient 上再跑 shell()（会串流）。
     */
    public synchronized ShellStream openShell(String cmd) throws IOException {
        if (!isConnected()) throw new IOException("未连接");
        int local = nextLocal++;
        // 保持 shell: （adbd 会分配 pty）。曾试过改成 exec: 想绕开终端缓冲，
        // 但在这台车的 adbd 上 exec: 的流打不开——实测探针进程始终没被拉起来、
        // jar 也从未重传，盲区直接全无数据。pty 这条路虽然可能丢行，
        // 但至少能跑；丢行由 LocalAdb 里的「状态文件兜底」补齐。
        send(CMD_OPEN, local, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
        return new ShellStream(local);
    }

    public final class ShellStream {
        final int local;
        int remote = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();

        ShellStream(int local) { this.local = local; }

        /** 读一行（阻塞，最多 timeoutMs）；流结束或异常返回 null。 */
        public synchronized String readLine(int timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                if (System.currentTimeMillis() > deadline) return null;
                Packet p;
                try {
                    p = readPacket(6000);
                } catch (IOException e) {
                    return null;
                }
                if (p == null) continue;
                if (p.cmd == CMD_OKAY) {
                    if (p.arg1 == local || p.arg1 == 0) remote = p.arg0;
                    continue;
                }
                if (p.cmd == CMD_WRTE) {
                    if (isMine(p, local, remote)) {
                        if (remote < 0) remote = p.arg0;
                        if (p.data != null) {
                            for (byte b : p.data) {
                                if (b == '\n') {
                                    String s = new String(line.toByteArray());
                                    line.reset();
                                    try { send(CMD_OKAY, local, p.arg0, null); } catch (IOException ignored) { }
                                    return s;
                                }
                                line.write(b);
                            }
                        }
                        try { send(CMD_OKAY, local, p.arg0, null); } catch (IOException ignored) { }
                    } else {
                        try { send(CMD_OKAY, local, p.arg0, null); } catch (IOException ignored) { }
                    }
                    continue;
                }
                if (p.cmd == CMD_CLSE) {
                    if (isMine(p, local, remote)) return null;
                    continue;
                }
            }
        }

        public synchronized void close() {
            try { send(CMD_CLSE, local, remote < 0 ? 0 : remote, null); } catch (IOException ignored) { }
        }
    }

    private static boolean isMine(Packet p, int local, int remote) {
        return p.arg1 == local || (remote >= 0 && p.arg0 == remote);
    }

    public synchronized void close() {
        try { if (sock != null) sock.close(); } catch (IOException ignored) { }
        sock = null; in = null; out = null;
    }

    // ---------- 帧收发 ----------

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        if (data != null) crc.update(data);   // 协议要求 CRC32；求和会被 adbd 静默丢包
        int check = (int) crc.getValue();
        ByteArrayOutputStream h = new ByteArrayOutputStream(24);
        le(h, cmd); le(h, arg0); le(h, arg1);
        le(h, len); le(h, check); le(h, cmd ^ 0xffffffff);
        out.write(h.toByteArray());
        if (len > 0) out.write(data);
        out.flush();
    }

    private static final class Packet {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private Packet readPacket(int timeoutMs) throws IOException {
        if (sock != null) sock.setSoTimeout(timeoutMs);
        byte[] h;
        try {
            h = readFully(24);
        } catch (java.net.SocketTimeoutException e) {
            return null;   // 等授权期间没数据是正常的，交给上层按 deadline 判断
        }
        if (h == null) return null;
        Packet p = new Packet();
        p.cmd = getInt(h, 0);
        p.arg0 = getInt(h, 4);
        p.arg1 = getInt(h, 8);
        int len = getInt(h, 12);
        if (len < 0 || len > MAX_PAYLOAD * 2) throw new IOException("包长度异常 " + len);
        p.data = len > 0 ? readFully(len) : new byte[0];
        return p;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return b;
    }

    private static void le(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static int getInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private byte[] sign(byte[] token) throws IOException {
        try {
            Signature s = Signature.getInstance("SHA1withRSA");
            s.initSign(key);
            s.update(token);
            return s.sign();
        } catch (Exception e) {
            throw new IOException("签名失败: " + e);
        }
    }

    /**
     * adb 公钥格式（2048 位，共 524 字节），字段顺序与字节序都必须是：
     *   uint32 words(=64, 小端) + uint32 n0inv(小端) + n(256B, 小端) + rr(256B, 小端) + uint32 e(小端)
     * 然后 base64 + " " + 名字 + NUL。
     *
     * 注意：n 和 rr 是「小端」写出的，且 n0inv 在 n 之前。写错顺序时 adbd 会打
     * "Invalid base64 key" 并且不弹授权框（表现为连接一直超时）。
     */
    private byte[] publicKeyPayload() throws IOException {
        BigInteger n = pub.getModulus();
        BigInteger e = pub.getPublicExponent();
        int words = (n.bitLength() + 31) / 32;

        BigInteger mod32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.modInverse(mod32).negate().mod(mod32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2 * words * 32).mod(n);

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        le(b, words);
        le(b, n0inv.intValue());
        b.write(leFixed(n, words * 4), 0, words * 4);
        b.write(leFixed(rr, words * 4), 0, words * 4);
        le(b, e.intValue());

        String s = Base64.encodeToString(b.toByteArray(), Base64.NO_WRAP) + " " + keyName + "\0";
        return s.getBytes("UTF-8");
    }

    /** 把大整数按「小端」填进固定长度数组（BigInteger.toByteArray 是大端）。 */
    private static byte[] leFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        for (int i = 0; i < len && i < raw.length; i++) {
            out[i] = raw[raw.length - 1 - i];
        }
        return out;
    }
}
