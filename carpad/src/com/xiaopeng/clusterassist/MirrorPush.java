package com.xiaopeng.clusterassist;

import android.util.Log;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;

/**
 * 打灯盲区影像推送：车机 → 仪表 TCP（默认 172.20.1.40:9002）。
 *
 * <p>协议（24 字节小端头 + 载荷，见仪表端 instrument/mirror.go）：</p>
 * <pre>
 *   0  4  "P7VD"
 *   4  1  ver = 1
 *   5  1  codec（0=raw 1=zstd 2=deflate）
 *   6  1  side（0=撤区 1=左 2=右）
 *   7  1  flags（保留 0）
 *   8  4  seq
 *  12  2  w（当前 640）
 *  14  2  h（当前 360）
 *  16  4  clen（载荷字节数）
 *  20  4  ulen（= w*h*2）
 * </pre>
 *
 * <p>为什么是 RGB565 raw，而不是 JPEG / H.264：</p>
 * <ul>
 *   <li>链路实测 11.01MB/s（92Mbps）。960×540 裸 565 是 1.01MB/帧，10.9fps 封顶；
 *       降到 640×360（460KB）后上限抬到约 23fps，仪表用最近邻放大 1.5× 补回面积，
 *       放大耗时约 6ms，比 deflate 解压的 80ms 低一个量级。</li>
 *   <li>车机端是纯 Java，只有 JDK 自带的 Deflater；协议仍保留 codec=2（deflate）
 *       作为回退，仪表两端都支持。</li>
 *   <li>仪表 A9 软解 JPEG 约 26ms、H.264 需 VPU 硬解（无交叉工具链），一期都不选。</li>
 * </ul>
 *
 * <p>线程模型：</p>
 * <ul>
 *   <li>采集线程调 {@link #submit} —— 只做一次数组拷贝 + 入队，绝不阻塞、绝不抛异常；</li>
 *   <li>发送线程负责连接、压缩、写 socket，断线自动重连（1s 退避）。</li>
 * </ul>
 * 队列深度 1：仪表本来就只显示最新一帧，积压只会让画面延迟越来越大。
 */
public final class MirrorPush implements MirrorFrameSink {
    private static final String TAG = "P7MirrorPush";

    /**
     * 源帧尺寸 640×360，由仪表最近邻放大 1.5× 填满 960×540 影像区。
     *
     * <p>为什么降到 640×360：960×540 裸 RGB565 一帧 1.01MB，链路实测 11MB/s，
     * 也就是 10.9fps 的硬天花板——不管仪表解不解码都只能 10fps。降到 640×360
     * 后一帧 460KB，链路允许约 23fps；代价是清晰度下降，由仪表放大补回面积。</p>
     *
     * <p>协议头里的 w/h 就是这两个值，仪表按 w/h 选通道：960×540 走整行 memcpy，
     * 640×360 走放大。</p>
     */
    public static final int W = 640;
    public static final int H = 360;
    private static final int U_LEN = W * H * 2;
    private static final int HDR_LEN = 24;

    private static final int CODEC_RAW = 0;
    private static final int CODEC_DEFLATE = 2;

    /**
     * 传输模式：true = 原始 RGB565（仪表端只做一次 memcpy + 放大，零解码 CPU）。
     *
     * <p>实车数据（640×360，20fps）：一帧 460KB，占带宽 9.2MB/s，link 实测 11MB/s
     * 有余量。960×540 时期 raw 只能 10fps，那是带宽上限，不是 raw 本身的错。</p>
     *
     * <p>纠正一个曾经的误判：早前 raw 模式下画面全黑，当时以为"每帧写 1MB 打断了
     * 相机供帧"。后来在 deflate 模式下复现了同样的黑帧，且帧内只有 3 种像素值、
     * 99.97% 同值——那是 camera HAL 崩过之后返回的占位帧，与传输模式无关。
     * 停掉占用 ISP 的 com.xiaopeng.xmart.camera 并重启服务后恢复正常。</p>
     */
    private static final boolean RAW_MODE = true;
    private static final int SIDE_NONE = 0;
    private static final int SIDE_LEFT = 1;
    private static final int SIDE_RIGHT = 2;

    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int RECONNECT_MS = 1000;

    /** 一帧：像素 + 方向。像素数组来自复用池，发完归还。 */
    private static final class Frame {
        byte[] pix;
        int side;
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(1);
    private final ArrayDeque<byte[]> pool = new ArrayDeque<>();
    private final Deflater def = new Deflater(Deflater.BEST_SPEED, false);
    private final byte[] cbuf = new byte[U_LEN + 4096];
    private final byte[] hdr = new byte[HDR_LEN];

    private Thread thread;
    private volatile Socket sock;
    private String host = "172.20.1.40";
    private int port = 9002;
    private int seq;

    /** 统计（给界面/日志看，排查时不用抓包） */
    private volatile int sent, dropped, errors;
    private volatile long lastSentAt;
    private long statAt, statSent, statCompressMs, statWriteMs, statBytes;

    public int sentFrames() { return sent; }
    public int droppedFrames() { return dropped; }
    public int errors() { return errors; }
    public long lastSentAt() { return lastSentAt; }

    public synchronized void start(String host, int port) {
        if (running.get()) return;
        this.host = (host == null || host.isEmpty()) ? "172.20.1.40" : host;
        this.port = port <= 0 ? 9002 : port;
        running.set(true);
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "p7mirror-tx");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "start → " + this.host + ":" + this.port);
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) return;
        closeQuietly();
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        Frame f;
        while ((f = queue.poll()) != null) recycle(f.pix);
        Log.i(TAG, "stop（sent=" + sent + " drop=" + dropped + " err=" + errors + "）");
    }

    public boolean isRunning() { return running.get(); }

    /**
     * 交一帧 RGB565（长度必须 = 960*540*2）。
     *
     * <p>非阻塞：队列满时直接丢掉手里的旧帧换新的（画面宁可不连续，也不能延迟累积）。
     * 任何异常都在这里吞掉——影像失败绝不能影响浮窗/仪表其他功能。</p>
     */
    public void submit(byte[] pix, int side) {
        if (!running.get() || pix == null || pix.length != U_LEN) return;
        try {
            byte[] buf = obtain();
            System.arraycopy(pix, 0, buf, 0, U_LEN);
            Frame f = new Frame();
            f.pix = buf;
            f.side = normalizeSide(side);
            Frame old = queue.poll();
            if (old != null) {
                dropped++;
                recycle(old.pix);
            }
            if (!queue.offer(f)) recycle(f.pix);
        } catch (Throwable t) {
            errors++;
        }
    }

    private static int normalizeSide(int side) {
        if (side == SIDE_LEFT || side == SIDE_RIGHT) return side;
        return SIDE_NONE;
    }

    private byte[] obtain() {
        byte[] b = pool.poll();
        return b != null ? b : new byte[U_LEN];
    }

    private void recycle(byte[] b) {
        if (b != null && b.length == U_LEN && pool.size() < 3) pool.offer(b);
    }

    private void loop() {
        while (running.get()) {
            try {
                if (sock == null && !connect()) {
                    sleepQuietly(RECONNECT_MS);
                    continue;
                }
                Frame f = queue.poll(200, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                send(f);
                recycle(f.pix);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                errors++;
                Log.w(TAG, "发送异常，重连: " + t);
                closeQuietly();
                sleepQuietly(RECONNECT_MS);
            }
        }
    }

    private boolean connect() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            sock = s;
            Log.i(TAG, "已连接 " + host + ":" + port);
            return true;
        } catch (Throwable t) {
            closeQuietly(s);
            errors++;
            return false;
        }
    }

    private void send(Frame f) throws Exception {
        long t0 = System.currentTimeMillis();
        int clen;
        if (RAW_MODE) {
            clen = U_LEN;                 // 不压：载荷就是像素本身
        } else {
            clen = compress(f.pix);
        }
        long t1 = System.currentTimeMillis();
        buildHeader(f.side, clen);
        OutputStream out = sock.getOutputStream();
        out.write(hdr, 0, HDR_LEN);
        if (RAW_MODE) {
            out.write(f.pix, 0, U_LEN);   // 直接写像素数组，省掉一次整帧拷贝
        } else {
            out.write(cbuf, 0, clen);
        }
        out.flush();
        long t2 = System.currentTimeMillis();
        sent++;
        lastSentAt = t2;

        // 每秒一行统计：compress 是车机侧唯一的 CPU 大头，write 反映链路是否吃满
        statCompressMs += (t1 - t0);
        statWriteMs += (t2 - t1);
        statBytes += clen;
        statSent++;
        if (t2 - statAt >= 1000) {
            Log.i(TAG, "tx " + statSent + "fps compress=" + (statSent > 0 ? statCompressMs / statSent : 0)
                    + "ms write=" + (statSent > 0 ? statWriteMs / statSent : 0) + "ms kb="
                    + (statSent > 0 ? statBytes / statSent / 1024 : 0) + " drop=" + dropped);
            statAt = t2;
            statSent = 0;
            statCompressMs = 0;
            statWriteMs = 0;
            statBytes = 0;
        }
    }

    private int compress(byte[] pix) {
        def.reset();
        def.setInput(pix, 0, U_LEN);
        def.finish();
        int n = 0;
        while (!def.finished()) {
            int w = def.deflate(cbuf, n, cbuf.length - n);
            if (w <= 0) break;
            n += w;
        }
        return n;
    }

    private void buildHeader(int side, int clen) {
        hdr[0] = 'P'; hdr[1] = '7'; hdr[2] = 'V'; hdr[3] = 'D';
        hdr[4] = 1;                     // ver
        hdr[5] = (byte) (RAW_MODE ? CODEC_RAW : CODEC_DEFLATE);  // codec
        hdr[6] = (byte) side;
        hdr[7] = 0;                     // flags
        putInt(8, seq++);
        putShort(12, W);
        putShort(14, H);
        putInt(16, clen);
        putInt(20, U_LEN);
    }

    private void putInt(int off, int v) {
        hdr[off] = (byte) (v & 0xFF);
        hdr[off + 1] = (byte) ((v >> 8) & 0xFF);
        hdr[off + 2] = (byte) ((v >> 16) & 0xFF);
        hdr[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private void putShort(int off, int v) {
        hdr[off] = (byte) (v & 0xFF);
        hdr[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private void closeQuietly() {
        Socket s = sock;
        sock = null;
        closeQuietly(s);
    }

    private void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Throwable ignored) { }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
