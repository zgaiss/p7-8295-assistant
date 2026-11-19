package com.xiaopeng.clusterassist;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简 FTP 客户端（被动模式 PASV），只实现仪表部署需要的命令。
 * 仪表侧 FTP：172.20.1.40:2221，用户 root，密码空，根目录 chroot 在 /data。
 */
public class Ftp implements Transport {
    private final String host;
    private final int port;
    private final String user;
    private final String pass;

    private Socket ctrl;
    private InputStream cin;
    private OutputStream cout;
    private final Object lock = new Object();

    public Ftp(String host, int port, String user, String pass) {
        this.host = host;
        this.port = port;
        this.user = user == null ? "" : user;
        this.pass = pass == null ? "" : pass;
    }

    public String name() { return "FTP"; }

    public String describe() {
        return "ftp://" + host + ":" + port;
    }

    private void ensure() throws IOException {
        if (ctrl != null && ctrl.isConnected() && !ctrl.isClosed()) return;
        dropQuietly();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 6000);
        s.setSoTimeout(15000);
        ctrl = s;
        cin = new BufferedInputStream(s.getInputStream());
        cout = s.getOutputStream();
        String greet = readLine();
        if (!greet.startsWith("220")) throw new IOException("FTP 拒绝连接: " + greet);
        if (!user.isEmpty()) {
            reply("USER " + user);
            String r = reply("PASS " + pass);
            if (!r.startsWith("230") && !r.startsWith("202")) throw new IOException("FTP 登录失败: " + r);
        } else {
            reply("USER anonymous");
            reply("PASS p7@local");
        }
        reply("TYPE I");
        reply("OPTS UTF8 ON");
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = cin.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 4096) break;
        }
        if (c == -1 && b.size() == 0) throw new IOException("FTP 连接已断开");
        return b.toString("UTF-8");
    }

    private String reply(String cmd) throws IOException {
        String first;
        try {
            cout.write((cmd + "\r\n").getBytes("UTF-8"));
            cout.flush();
            first = readLine();
        } catch (IOException e) {
            // 连接已被服务端回收（空闲超时最常见）。TCP 半关闭时 isConnected()
            // 仍返回 true，ensure() 不会重建，坏连接就会被一直复用，
            // 后面每个命令都失败——现场表现就是"过一会儿什么都读不到"。
            // 所以一旦读写失败就立刻丢弃连接，让下一次 ensure() 重连。
            dropQuietly();
            throw e;
        }
        String line = first;
        // 多行应答：123-... 直到 123 空格
        if (line.length() > 3 && line.charAt(3) == '-') {
            String code = line.substring(0, 3);
            try {
                while (true) {
                    String l = readLine();
                    line = l;
                    if (l.startsWith(code + " ")) break;
                }
            } catch (IOException e) {
                dropQuietly();
                throw e;
            }
        }
        return line;
    }

    private Socket pasv() throws IOException {
        String r = reply("PASV");
        if (!r.startsWith("227")) throw new IOException("PASV 失败: " + r);
        int a = r.lastIndexOf('('), b = r.lastIndexOf(')');
        String body = (a >= 0 && b > a) ? r.substring(a + 1, b) : r;
        String[] p = body.split(",");
        if (p.length < 6) throw new IOException("PASV 应答异常: " + r);
        String ip = p[0].trim() + "." + p[1].trim() + "." + p[2].trim() + "." + p[3].trim();
        int po = Integer.parseInt(p[4].trim()) * 256 + Integer.parseInt(p[5].trim());
        Socket d = new Socket();
        d.connect(new InetSocketAddress(ip, po), 6000);
        // 数据连接也要有超时：不设的话读不到数据就永久挂着，
        // 车机上表现成"点了播放器日志一直转圈"。
        d.setSoTimeout(20000);
        return d;
    }

    @Override
    public void put(String remote, byte[] data) throws IOException {
        synchronized (lock) {
            ensure();
            Socket d = pasv();
            String r = reply("STOR " + remote);
            if (!(r.startsWith("150") || r.startsWith("125"))) {
                d.close();
                throw new IOException("STOR 失败: " + r);
            }
            try {
                OutputStream o = d.getOutputStream();
                o.write(data);
                o.flush();
            } finally {
                d.close();
            }
            String done = readLine();
            if (!done.startsWith("226") && !done.startsWith("250")) throw new IOException("上传未确认: " + done);
        }
    }

    /** 大文件上传：带进度回调 */
    public void putFile(String remote, java.io.File file, Progress p) throws IOException {
        synchronized (lock) {
            ensure();
            Socket d = pasv();
            String r = reply("STOR " + remote);
            if (!(r.startsWith("150") || r.startsWith("125"))) {
                d.close();
                throw new IOException("STOR 失败: " + r);
            }
            long total = file.length();
            long sent = 0;
            try {
                InputStream in = new java.io.FileInputStream(file);
                OutputStream o = d.getOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    sent += n;
                    if (p != null) p.onProgress(sent, total);
                }
                o.flush();
                in.close();
            } finally {
                d.close();
            }
            String done = readLine();
            if (!done.startsWith("226") && !done.startsWith("250")) throw new IOException("上传未确认: " + done);
        }
    }

    @Override
    public byte[] get(String remote) throws IOException {
        try {
            return getOnce(remote);
        } catch (IOException e) {
            // 重试一次：多数失败是长连接被服务端回收，丢掉坏连接重建就能成功。
            // 数据连接也可能是上一轮的残留状态（比如文件被正在写的进程截断），
            // 重来一遍同样是干净的。
            dropQuietly();
            return getOnce(remote);
        }
    }

    private byte[] getOnce(String remote) throws IOException {
        synchronized (lock) {
            ensure();
            Socket d = pasv();
            String r = reply("RETR " + remote);
            if (!(r.startsWith("150") || r.startsWith("125"))) {
                d.close();
                throw new IOException("RETR 失败: " + r);
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try {
                InputStream in = d.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            } finally {
                d.close();
            }
            String done = readLine();
            if (!done.startsWith("226") && !done.startsWith("250")) throw new IOException("下载未确认: " + done);
            return b.toByteArray();
        }
    }

    @Override
    public long size(String remote) {
        synchronized (lock) {
            try {
                ensure();
                String r = reply("SIZE " + remote);
                if (r.startsWith("213")) return Long.parseLong(r.substring(4).trim());
            } catch (Throwable ignored) {
            }
            return -1;
        }
    }

    public boolean mkdir(String dir) {
        synchronized (lock) {
            try {
                ensure();
                String r = reply("MKD " + dir);
                // 250 成功；257 已存在；521/550 也可视为已存在，容错
                return r.startsWith("250") || r.startsWith("257") || r.startsWith("521") || r.startsWith("550");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** SITE CHMOD 赋权限（vsftpd / busybox 常支持）；返回是否成功 */
    public boolean chmod(int mode, String remote) {
        synchronized (lock) {
            try {
                ensure();
                String r = reply("SITE CHMOD " + Integer.toOctalString(mode) + " " + remote);
                return r.startsWith("200") || r.startsWith("250");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    /** SITE EXEC 尝试远程执行（极少数服务器支持）；返回服务器原始应答，空串=不支持/失败 */
    public String siteExec(String cmd) {
        synchronized (lock) {
            try {
                ensure();
                return reply("SITE EXEC " + cmd);
            } catch (Throwable t) {
                return "";
            }
        }
    }

    public boolean rename(String from, String to) {
        synchronized (lock) {
            try {
                ensure();
                String r = reply("RNFR " + from);
                if (!r.startsWith("350")) return false;
                r = reply("RNTO " + to);
                return r.startsWith("250");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public boolean delete(String remote) {
        synchronized (lock) {
            try {
                ensure();
                String r = reply("DELE " + remote);
                return r.startsWith("250");
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public List<String> list(String dir) throws IOException {
        synchronized (lock) {
            ensure();
            Socket d = pasv();
            String r = reply("LIST " + dir);
            if (!(r.startsWith("150") || r.startsWith("125"))) {
                d.close();
                throw new IOException("LIST 失败: " + r);
            }
            List<String> out = new ArrayList<>();
            try {
                InputStream in = d.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                for (String line : b.toString("UTF-8").split("\n")) {
                    if (!line.trim().isEmpty()) out.add(line.trim());
                }
            } finally {
                d.close();
            }
            readLine();
            return out;
        }
    }

    public boolean ping() {
        synchronized (lock) {
            try {
                ensure();
                reply("NOOP");
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }

    /**
     * 直接丢弃连接，不尝试礼貌退出。
     *
     * 连接已经坏掉时（对端 FIN、RST、超时）再写 QUIT 只会阻塞或二次报错，
     * 所以这里只关 socket 并把引用清空，让下次 ensure() 重新建连。
     */
    private void dropQuietly() {
        try {
            if (ctrl != null) ctrl.close();
        } catch (Throwable ignored) {
        }
        ctrl = null;
        cin = null;
        cout = null;
    }

    private void closeQuietly() {
        try {
            if (cout != null) {
                cout.write("QUIT\r\n".getBytes());
                cout.flush();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (ctrl != null) ctrl.close();
        } catch (Throwable ignored) {
        }
        ctrl = null;
        cin = null;
        cout = null;
    }

    public interface Progress {
        void onProgress(long sent, long total);
    }

    /** 把字节写成临时文件，供大文件上传用 */
    public static java.io.File spool(byte[] data, java.io.File dir, String name) throws IOException {
        java.io.File f = new java.io.File(dir, name);
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.setLength(0);
        raf.write(data);
        raf.close();
        return f;
    }
}
