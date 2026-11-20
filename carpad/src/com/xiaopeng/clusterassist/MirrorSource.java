package com.xiaopeng.clusterassist;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;

/**
 * 打灯影像的采集端：Camera2 取 YUV_420_888 → 最近邻采样成 960×540 RGB565 → 交给 MirrorPush。
 *
 * <p>为什么不直接拿原厂 AVM 的画面：那条路只有两条，<br>
 * ① 让原厂 AVM 把画面渲染到我们的 Surface —— 没有公开接口，拿不到；<br>
 * ② 抓 SurfaceFlinger/display 层 —— 需要系统权限，且会与仪表写 fb1 抢时序。<br>
 * 所以走 Camera2 物理相机，这也是另一套打灯影像实现（P7_ViewAssist）在实车上验证过的路径。</p>
 *
 * <p>采样与转换在一趟里做完：每个输出像素 (x,y) 按比例取源像素 (sx,sy)，
 * 顺便算 565。这样不需要额外的缩放库，也不需要中间 Bitmap。</p>
 *
 * <p>线程：Camera2 回调在自建 HandlerThread 上，转换完调
 * {@link MirrorPush#submit} 入队就返回，绝不碰网络、不阻塞相机。</p>
 */
public final class MirrorSource {
    private static final String TAG = "P7MirrorSrc";

    private static final int OUT_W = MirrorPush.W;
    private static final int OUT_H = MirrorPush.H;
    private static final int OUT_LEN = OUT_W * OUT_H * 2;

    /**
     * 目标帧率。
     *
     * <p>640×360 raw 一帧 460KB，链路实测 11MB/s → 上限约 23fps；取 20fps
     * 留一点余量（20fps × 460KB = 9.2MB/s，占 84%）。之前 960×540 时
     * 10fps 已经是带宽上限，再往上调只会让 write 阻塞、丢帧。</p>
     */
    private static final int TARGET_FPS = 20;

    /**
     * 四宫格取景微调（运行时可调，通过 AvmReceiver 广播 --ef zoom/panx/pany）。
     *
     * <p> quadrant 裁切默认取整格（zoom=1, pan=0）正好是该侧完整鱼眼。想"往车尾偏、
     * 放大车尾、舍弃车头"时，调 zoom>1 缩小窗口（放大），再用 panx/pany 把窗口中心
     * 往车尾方向挪。取值：zoom∈[1,3]，panx/pany∈[-1,1]（相对半格的比例）。</p>
     */
    // 左右各一套：索引即 side（1=左灯/左下格，2=右灯/右下格；[0] 不用）。
    // 两侧鱼眼互为镜像，车尾偏向在各自画面里方向相反，必须分开存。
    // 默认值经真机调定：放大 2 倍突出车尾；右灯窗口右移(panx=+0.8)、左灯镜像(-0.8)。
    private static volatile float[] quadZoom = {1.0f, 2.0f, 2.0f};
    private static volatile float[] quadPanX = {0.0f, -0.8f, +0.8f};
    private static volatile float[] quadPanY = {0.0f, 0.0f, 0.0f};
    private static volatile boolean quadCropEnabled = true;  // 关=整格不微调
    private static volatile int quadCropSeq = 0;

    /** 用持久化设置初始化取景（APP 启动/打灯前调用），保证重启后参数不丢。 */
    public static void applyFromPrefs(Prefs p) {
        if (p == null) return;
        quadCropEnabled = p.quadCropEnable;
        quadZoom[1] = p.quadZoomL; quadPanX[1] = p.quadPanXL; quadPanY[1] = p.quadPanYL;
        quadZoom[2] = p.quadZoomR; quadPanX[2] = p.quadPanXR; quadPanY[2] = p.quadPanYR;
        quadCropSeq++;
        Log.i(TAG, "取景从Prefs恢复 enable=" + quadCropEnabled + " " + quadCropInfo());
    }

    /** 取景微调总开关；关掉后按整格显示。 */
    public static void setQuadCropEnabled(boolean enabled) {
        if (quadCropEnabled == enabled) return;
        quadCropEnabled = enabled;
        quadCropSeq++;
        Log.i(TAG, "取景微调开关=" + enabled);
    }

    /**
     * 运行时改四宫格取景；下一帧 convert 检测 seq 变化重建采样表，立刻生效。
     *
     * @param side 0=左右两套都设，1=只设左灯，2=只设右灯
     */
    public static void setQuadCrop(int side, float zoom, float panX, float panY) {
        float z = Math.max(1.0f, Math.min(3.0f, zoom));
        float px = Math.max(-1.0f, Math.min(1.0f, panX));
        float py = Math.max(-1.0f, Math.min(1.0f, panY));
        if (side == 0) {
            quadZoom[1] = z; quadPanX[1] = px; quadPanY[1] = py;
            quadZoom[2] = z; quadPanX[2] = px; quadPanY[2] = py;
        } else if (side == 1 || side == 2) {
            quadZoom[side] = z; quadPanX[side] = px; quadPanY[side] = py;
        }
        quadCropSeq++;
        Log.i(TAG, "取景调整 side=" + side
                + " 左[" + quadZoom[1] + "," + quadPanX[1] + "," + quadPanY[1] + "]"
                + " 右[" + quadZoom[2] + "," + quadPanX[2] + "," + quadPanY[2] + "]");
    }

    /** 兼容旧调用：左右两套一起设。 */
    public static void setQuadCrop(float zoom, float panX, float panY) {
        setQuadCrop(0, zoom, panX, panY);
    }

    public static String quadCropInfo() {
        return "左[zoom=" + quadZoom[1] + " pan=" + quadPanX[1] + "," + quadPanY[1] + "]"
                + " 右[zoom=" + quadZoom[2] + " pan=" + quadPanX[2] + "," + quadPanY[2] + "]";
    }

    private int lastQuadCropSeq = -1;

    private final Context ctx;
    private final int cameraId;
    private final int side;
    /** true = 取打灯侧的广角视野（ROI 裁切）；false = 整幅 360 画面。 */
    private final boolean crop;
    /**
     * true = 相机流是 2×2 四宫格鱼眼，按象限裁（左灯取左下、右灯取右下）；
     * false = 相机流是 360 三分屏，走 {@link #crop} 的侧栏 ROI。
     */
    private final boolean quad;
    private final MirrorPush push;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;

    private byte[] out = new byte[OUT_LEN];
    private byte[] yArr = new byte[0], uArr = new byte[0], vArr = new byte[0];
    private int[] sxTab, syTab;
    private int tabSrcW = -1, tabSrcH = -1;
    private long lastFrameAt;
    /** 采集侧统计：每秒一行，用来区分「相机给不出帧」和「转换太慢」 */
    private long statAt;
    private int statFrames;
    private long statConvertMs;
    private volatile boolean closed;
    private volatile String lastError = "";

    public MirrorSource(Context ctx, int cameraId, int side, boolean crop, boolean quad, MirrorPush push) {
        this.ctx = ctx.getApplicationContext();
        this.cameraId = cameraId;
        this.side = side;
        this.crop = crop;
        this.quad = quad;
        this.push = push;
    }

    public String lastError() { return lastError; }

    public void start() {
        try {
            thread = new HandlerThread("p7mirror-cam");
            thread.start();
            handler = new Handler(thread.getLooper());
            handler.post(new Runnable() {
                @Override public void run() { openCamera(); }
            });
        } catch (Throwable t) {
            lastError = "启动失败: " + t;
            Log.w(TAG, lastError);
        }
    }

    public void stop() {
        closed = true;
        try {
            if (session != null) { session.close(); session = null; }
        } catch (Throwable ignored) { }
        try {
            if (device != null) { device.close(); device = null; }
        } catch (Throwable ignored) { }
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Throwable ignored) { }
        try {
            if (thread != null) { thread.quitSafely(); thread = null; }
        } catch (Throwable ignored) { }
    }

    private void openCamera() {
        if (closed) return;
        try {
            CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null) { lastError = "无 CameraManager"; return; }
            String[] ids = mgr.getCameraIdList();
            if (ids.length == 0) { lastError = "无相机设备"; return; }
            String id = pickId(ids);
            CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
            StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { lastError = "无 StreamConfigurationMap"; return; }
            Size sz = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (sz == null) { lastError = "无可用 YUV 尺寸"; return; }

            reader = ImageReader.newInstance(sz.getWidth(), sz.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(listener, handler);
            Log.i(TAG, "打开相机 id=" + id + " " + sz.getWidth() + "x" + sz.getHeight()
                    + " → " + OUT_W + "x" + OUT_H + " side=" + side);
            mgr.openCamera(id, callback, handler);
        } catch (CameraAccessException | SecurityException e) {
            lastError = "打开相机失败: " + e;
            Log.w(TAG, lastError);
        } catch (Throwable t) {
            lastError = "打开相机异常: " + t;
            Log.w(TAG, lastError);
        }
    }

    /** 指定的 id 不在列表里（换固件/换车）就退回第一路，别让功能整体失效。 */
    private String pickId(String[] ids) {
        String want = String.valueOf(cameraId);
        for (String id : ids) if (id.equals(want)) return id;
        lastError = "相机 id=" + want + " 不存在，退回 " + ids[0];
        Log.w(TAG, lastError);
        return ids[0];
    }

    /**
     * 选一个 16:9 的输出尺寸，优先 ≥960×540 里面积最小的（够清晰又不浪费带宽），
     * 没有 16:9 就退最大的。转换那一步自带缩放，所以尺寸不必正好 960×540。
     */
    private Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            boolean ratio169 = Math.abs(s.getWidth() * 9 - s.getHeight() * 16) <= 16 * 4;
            if (!ratio169) continue;
            if (s.getWidth() < OUT_W || s.getHeight() < OUT_H) continue;
            if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    private final CameraDevice.StateCallback callback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) {
            device = d;
            startSession();
        }

        @Override public void onDisconnected(CameraDevice d) {
            lastError = "相机被判连（可能被原厂 AVM 抢占）";
            Log.w(TAG, lastError);
            d.close();
            device = null;
        }

        @Override public void onError(CameraDevice d, int code) {
            lastError = "相机错误 code=" + code;
            Log.w(TAG, lastError);
            d.close();
            device = null;
        }
    };

    private void startSession() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            Surface surface = reader.getSurface();
            b.addTarget(surface);
            device.createCaptureSession(java.util.Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                s.setRepeatingRequest(b.build(), null, handler);
                            } catch (Throwable t) {
                                lastError = "下发预览请求失败: " + t;
                                Log.w(TAG, lastError);
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            lastError = "createCaptureSession 配置失败";
                            Log.w(TAG, lastError);
                        }
                    }, handler);
        } catch (Throwable t) {
            lastError = "建会话失败: " + t;
            Log.w(TAG, lastError);
        }
    }

    private final ImageReader.OnImageAvailableListener listener =
            new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) {
            if (closed) return;
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null) return;
                long now = System.currentTimeMillis();
                if (now - lastFrameAt < 1000 / TARGET_FPS) return;
                lastFrameAt = now;
                long t0 = System.currentTimeMillis();
                convert(img);
                statConvertMs += System.currentTimeMillis() - t0;
                push.submit(out, side);
                statFrames++;
                if (now - statAt >= 1000) {
                    Log.i(TAG, "capture " + statFrames + "fps convert="
                            + (statFrames > 0 ? statConvertMs / statFrames : 0) + "ms");
                    statAt = now;
                    statFrames = 0;
                    statConvertMs = 0;
                }
            } catch (Throwable t) {
                lastError = "取帧异常: " + t;
            } finally {
                if (img != null) {
                    try { img.close(); } catch (Throwable ignored) { }
                }
            }
        }
    };

    private void convert(Image img) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer();
        ByteBuffer ub = p[1].getBuffer();
        ByteBuffer vb = p[2].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int w = img.getWidth(), h = img.getHeight();

        yArr = ensure(yArr, yRow * h + 16);
        uArr = ensure(uArr, uRow * ((h + 1) / 2) + 16);
        vArr = ensure(vArr, vRow * ((h + 1) / 2) + 16);
        copy(yb, yArr);
        copy(ub, uArr);
        copy(vb, vArr);

        // 源尺寸变了、或运行时取景参数被广播改了，都重建采样表（下一帧即生效）
        if (tabSrcW != w || tabSrcH != h || lastQuadCropSeq != quadCropSeq) {
            lastQuadCropSeq = quadCropSeq;
            buildTabs(w, h);
        }

        int i = 0;
        for (int y = 0; y < OUT_H; y++) {
            int sy = syTab[y];
            int yOff = sy * yRow;
            int uOff = (sy >> 1) * uRow;
            int vOff = (sy >> 1) * vRow;
            for (int x = 0; x < OUT_W; x++) {
                int sx = sxTab[x];
                int yv = yArr[yOff + sx * yPix] & 0xFF;
                int u = (uArr[uOff + (sx >> 1) * uPix] & 0xFF) - 128;
                int v = (vArr[vOff + (sx >> 1) * vPix] & 0xFF) - 128;

                int r = yv + ((91881 * v) >> 16);
                int g = yv - ((22554 * u + 46802 * v) >> 16);
                int bl = yv + ((116130 * u) >> 16);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (bl < 0) bl = 0; else if (bl > 255) bl = 255;

                int v565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (bl >> 3);
                out[i++] = (byte) (v565 & 0xFF);
                out[i++] = (byte) ((v565 >> 8) & 0xFF);
            }
        }
    }

    /**
     * 采样表：输出 x/y → 源 x/y。只在源尺寸变化时重建一次。
     *
     * <p>默认把整幅源画面等比映射到输出。开启侧视裁切时，改为从 360 三分屏里
     * 取打灯那一侧的 16:9 子窗口，等比放大——保持宽高比，绝不拉伸。
     *
     * <p>为什么要这样做：另一套已实车验证的打灯影像（P7打灯转向辅助 v1.46）
     * 走的是「下发裸 display-mode=8 让相机服务切出四路鱼眼，再用 cam_scale 放大
     * + pan 平移把该侧露出来」。本车固件 V5.10.0.8814 上，display-mode 能否生效
     * 取决于下发是否只写裸字段（补配套字段反而让 controller 不触发，见 AvmMode）。
     * quad=true 表示四宫格已生效 → 按象限裁；否则把整幅 1280×720 按侧栏 ROI
     * 放大裁切兜底，两条路都不拉伸画面。</p>
     *
     * <p>1280×724 实测分区（逐列亮度定位分隔线）：左栏 0..395、中栏 400..879
     * （车身俯视）、右栏 884..1279，分隔线在 396..399 与 880..883。</p>
     */
    private void buildTabs(int srcW, int srcH) {
        sxTab = new int[OUT_W];
        syTab = new int[OUT_H];

        int x0 = 0, y0 = 0, rw = srcW, rh = srcH;
        if (quad) {
            // 四宫格鱼眼：每格占源画面的 1/4，打左灯取左下格、右灯取右下格。
            // 象限标定来自实车录像 camcap_rec/fixed-side-views（1920×1080 四宫格）：
            //   左下格 (0,540,960,540)=左后  右下格 (960,540,960,540)=右后
            //   右上格 (960,0,960,540)=独立后视。按 1280×720 源等比即下四分之一。
            int halfW = srcW / 2;
            int halfH = srcH / 2;
            int cellX = (side == 2) ? halfW : 0;         // 右灯取右半格，左灯取左半格
            int cellY = halfH;                           // 下排 = 后侧两路
            // 在该格内按 zoom 缩小窗口、pan 平移中心：zoom=1/pan=0 时就是整格。
            // 左右各用自己那套参数（两侧鱼眼镜像，车尾偏向相反）；总开关关掉则整格。
            float zoom = quadCropEnabled ? quadZoom[side] : 1f;
            float panX = quadCropEnabled ? quadPanX[side] : 0f;
            float panY = quadCropEnabled ? quadPanY[side] : 0f;
            float zw = halfW / zoom;
            float zh = halfH / zoom;
            float cx = cellX + halfW / 2f + panX * (halfW - zw) / 2f;
            float cy = cellY + halfH / 2f + panY * (halfH - zh) / 2f;
            x0 = Math.round(cx - zw / 2f);
            y0 = Math.round(cy - zh / 2f);
            rw = Math.round(zw);
            rh = Math.round(zh);
            x0 = Math.max(0, Math.min(x0, srcW - rw));
            y0 = Math.max(0, Math.min(y0, srcH - rh));
            Log.i(TAG, "四宫格取景 side=" + side + " ROI=" + x0 + "," + y0
                    + " " + rw + "x" + rh + " src=" + srcW + "x" + srcH
                    + " zoom=" + zoom + " pan=" + panX + "," + panY);
        } else if (crop) {
            // 兜底：相机流仍是 360 三分屏，从左右侧栏取 16:9 子窗口等比放大。
            int colW = Math.round(srcW * 0.31f);       // ≈397，正好一栏宽
            int colH = Math.round(colW * 9f / 16f);    // ≈223，16:9 不拉伸
            if (colH > srcH) {
                colH = srcH;
                colW = Math.round(colH * 16f / 9f);
            }
            x0 = (side == 2) ? (srcW - colW) : 0;     // 2 = 右灯
            y0 = (srcH - colH) / 2;
            rw = colW;
            rh = colH;
            Log.i(TAG, "三分屏侧视裁切 side=" + side + " ROI=" + x0 + "," + y0
                    + " " + rw + "x" + rh + " src=" + srcW + "x" + srcH);
        }

        for (int x = 0; x < OUT_W; x++) {
            sxTab[x] = Math.min(srcW - 1, x0 + (int) ((long) x * rw / OUT_W));
        }
        for (int y = 0; y < OUT_H; y++) {
            syTab[y] = Math.min(srcH - 1, y0 + (int) ((long) y * rh / OUT_H));
        }
        tabSrcW = srcW;
        tabSrcH = srcH;
    }

    private static byte[] ensure(byte[] a, int need) {
        return a.length >= need ? a : new byte[need];
    }

    private static void copy(ByteBuffer b, byte[] dst) {
        b.position(0);
        int n = Math.min(b.remaining(), dst.length);
        b.get(dst, 0, n);
    }
}
