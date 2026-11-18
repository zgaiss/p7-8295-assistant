package com.xiaopeng.clusterassist;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * 仪表管理通道 RPC 客户端（端口 30510）。
 *
 * 协议逆向自原版 worker（p7probe.ProbeMain.icRpcText），是原厂仪表固件自带的
 * 远程命令执行接口（原版 App 的「ic-sh」就是靠它执行任意 shell 命令），
 * 不依赖任何预先部署的程序——所以可以用它实现「真·免 shell」部署。
 *
 * 帧格式（大端）：
 *   uint16 service=257, uint16 method=529, uint32 len=body+8,
 *   uint16 magic=32369(0x7E71), uint16 ver=1, byte[4]={1,0,0,0}, body(protobuf)
 * body：field1 = varint(0x7E710001) 的嵌套消息；field2 = "shell"；field2 = 命令文本。
 * 响应同帧头（尾四字节为 {1,0,128,0}），body 内 field2 的内层 string 拼接即命令输出。
 */
public final class IcRpc {
    public static final int IC_RPC_PORT = 30510;
    private static final String HEAD_IP = "172.20.1.45"; // 车机 eth0（源地址，绑不上就退回默认）
    private static final int CMD_MAX = 2400;

    private final String icIp;

    public IcRpc(String icIp) {
        this.icIp = (icIp == null || icIp.isEmpty()) ? "172.20.1.40" : icIp;
    }

    /** 执行一条 shell 命令（直连仪表 30510）；通道不可用时抛异常 */
    public String exec(String cmd) throws IOException {
        return execVia(icIp, IC_RPC_PORT, cmd);
    }

    /**
     * 执行一条 shell 命令，可指定目标地址/端口。
     * 走车机本地 nc 中继时传 127.0.0.1:9000，帧格式与直连完全一致。
     */
    public static String execVia(String host, int port, String cmd) throws IOException {
        if (cmd == null || cmd.length() > CMD_MAX) {
            throw new IOException("command_bounds");
        }
        // ---- 组装 protobuf body ----
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(8);                       // field 1, wire type 0 (varint)
        varint(inner, 1L + 2121334784L);      // 0x7E710001

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, 1, inner.toByteArray());
        field(body, 2, "shell".getBytes("UTF-8"));
        field(body, 2, cmd.getBytes("UTF-8"));

        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
        Socket socket = new Socket();
        try {
            if (!loopback) {
                try {
                    String local = localEthIp();
                    if (local != null) socket.bind(new InetSocketAddress(local, 0));
                } catch (IOException e) {
                    socket = new Socket(); // 源地址绑不上，退回系统默认
                }
            }
            socket.connect(new InetSocketAddress(host, port), 4000);
            socket.setSoTimeout(20000);

            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(257);
            out.writeShort(529);
            out.writeInt(body.size() + 8);
            out.writeShort(32369);
            out.writeShort(1);
            out.write(new byte[]{1, 0, 0, 0});
            body.writeTo(out);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int s1 = in.readUnsignedShort();
            int s2 = in.readUnsignedShort();
            int len = in.readInt();
            int s3 = in.readUnsignedShort();
            int s4 = in.readUnsignedShort();
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            int b2 = in.readUnsignedByte();
            int b3 = in.readUnsignedByte();

            if (s1 == 257 && s2 == 529 && s3 == 32369 && s4 == 1
                    && b0 == 1 && b1 == 0 && b2 == 128 && b3 == 0
                    && len >= 8 && len <= 65528) {
                byte[] resp = new byte[len - 8];
                in.readFully(resp);
                String text = decodeOutput(resp);
                if (text.isEmpty()) throw new IOException("空响应(帧头合法 len=" + len + ")");
                return text;
            }
            throw new IOException("management_response rc=" + b3 + " type=" + b2);
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** 找车机 eth 侧 172.20.1.x 的本机 IP 当源地址；找不到返回 null 让系统自动选路。
     *  原实现硬编码 172.20.1.45，别的车 eth0 不是这个地址时会绑不上，管理服务可能拒答。 */
    private static String localEthIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ns = java.net.NetworkInterface.getNetworkInterfaces();
            while (ns.hasMoreElements()) {
                java.net.NetworkInterface ni = ns.nextElement();
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address
                            && a.getHostAddress().startsWith("172.20.1.")) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ---------- protobuf 编解码（照抄原版实现） ----------

    private static void varint(ByteArrayOutputStream out, long v) {
        while (v > 127) {
            out.write((int) (v & 127) | 128);
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static void field(ByteArrayOutputStream out, int num, byte[] data) {
        varint(out, (long) num * 8 + 2);
        varint(out, data.length);
        out.write(data, 0, data.length);
    }

    private static long readVarint(byte[] b, int[] pos) {
        long v = 0;
        for (int i = 0; i < 64; i += 7) {
            if (pos[0] >= b.length) break;
            int x = b[pos[0]++] & 0xFF;
            v |= (long) (x & 127) << i;
            if ((x & 128) == 0) return v;
        }
        return v;
    }

    private static byte[] skipField(byte[] b, int[] pos, int wireType) {
        if (wireType == 0) { readVarint(b, pos); return new byte[0]; }
        long len = wireType == 2 ? readVarint(b, pos) : wireType == 1 ? 8 : wireType == 5 ? 4 : -1;
        if (len < 0 || len > b.length - pos[0]) return new byte[0];
        int start = pos[0];
        pos[0] += (int) len;
        return Arrays.copyOfRange(b, start, pos[0]);
    }

    private static String decodeOutput(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int[] pos = {0};
        while (pos[0] < b.length) {
            long tag = readVarint(b, pos);
            int fieldNum = (int) (tag >>> 3);
            int wireType = (int) (tag & 7);
            byte[] val = skipField(b, pos, wireType);
            if (fieldNum == 2 && wireType == 2) {
                int[] p2 = {0};
                while (p2[0] < val.length) {
                    long t2 = readVarint(val, p2);
                    int wt2 = (int) (t2 & 7);
                    byte[] s = skipField(val, p2, wt2);
                    if (wt2 == 2) {
                        try { sb.append(new String(s, "UTF-8")); } catch (Exception ignored) { }
                    }
                }
            }
        }
        return sb.toString();
    }
}
