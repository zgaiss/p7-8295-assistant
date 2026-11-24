package com.xiaopeng.clusterassist;

import android.content.Context;
import android.graphics.ImageFormat;
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
 * 打灯影像的采集端：Camera2 取 YUV_420_888 → 最近邻采样成 640×360 RGB565 → 交给 MirrorPush。
 *
 * <p>为什么不直接拿原厂 AVM 的画面：那条路只有两条，<br>
 * ① 让原厂 AVM 把画面渲染到我们的 Surface —— 没有公开接口，拿不到；<br>
 * ② 抓 SurfaceFlinger/display 层 —— 需要系统权限，且会与仪表写 fb1 抢时序。<br>
 * 所以走 Camera2 合成相机流，与参考实现 P7_ViewAssist 使用同一路径。</p>
 *
 * <p>采样与转换在一趟里做完：每个输出像素 (x,y) 按比例取源像素 (sx,sy)，
 * 顺便算 565。这样不需要额外的缩放库，也不需要中间 Bitmap。</p>
 *
 * <p>线程：Camera2 回调在自建 HandlerThread 上，转换完调
 * {@link MirrorFrameSink#submit} 分发到仪表及可选车机悬浮窗后就返回，不执行网络或 UI 操作。</p>
 */
public final class MirrorSource {
    private static final String TAG = "P7MirrorSrc";

    private static final int OUT_W = MirrorPush.W;
    private static final int OUT_H = MirrorPush.H;
    private static final int OUT_LEN = OUT_W * OUT_H * 2;
    private static final int LOCAL_W = 960;
    private static final int LOCAL_H = 540;
    private static final int LOCAL_LEN = LOCAL_W * LOCAL_H * 2;

    /**
     * 目标帧率。
     *
     * <p>跟随相机自适应：不强制固定帧率区间，夜间 AE 可降到 15fps 保证曝光，
     * 白天最高 30fps。这里只做 30fps 上限保护；640×360 raw 30fps 约 13.5MB/s，
     * 超过链路实测 11MB/s，因此 30 只在链路允许时短暂出现，仪表端按实收帧直写。</p>
     */
    private static final int TARGET_FPS = 30;

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
    /** Request quad mode and gate frames on parameter readback; otherwise pass the manual stream through. */
    private final boolean quad;
    private final MirrorFrameSink push;
    private final MirrorFrameSink localSink;
    private final int knownAvmMode;
    private final byte[] localOut = new byte[LOCAL_LEN];
    private int[] localSx, localSy;
    private int srcW, srcH, yRowF, uRowF, vRowF, yPixF, uPixF, vPixF;
    private int roiX, roiY, roiW, roiH;

    // One process-wide owner survives stop() to drain late HAL callbacks. Never quit it
    // while an asynchronous open/close can still deliver a CameraDevice.
    private static final Handler OWNER;
    private static MirrorSource lease;
    private static final java.util.concurrent.atomic.AtomicLong NATIVE_EPOCH =
            new java.util.concurrent.atomic.AtomicLong();
    private final long restorationEpoch = NATIVE_EPOCH.get();

    /** Invalidate even an old source whose asynchronous stop was already queued. */
    public static void yieldToNative() { NATIVE_EPOCH.incrementAndGet(); }
    static {
        HandlerThread ownerThread = new HandlerThread("p7mirror-cam");
        ownerThread.start();
        OWNER = new Handler(ownerThread.getLooper());
    }
    private final Handler handler = OWNER;
    private boolean opening;
    private boolean configuring;
    private MirrorAvmSession avm;
    private volatile boolean restoreMode = true;
    private long readyAt;
    private long modeCheckAt;
    private int modeChecks;
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

    public MirrorSource(Context ctx, int cameraId, int side, boolean quad, MirrorFrameSink push) {
        this(ctx, cameraId, side, quad, push, null, -1);
    }

    public MirrorSource(Context ctx, int cameraId, int side, boolean quad, MirrorFrameSink push,
                        int knownAvmMode) {
        this(ctx, cameraId, side, quad, push, null, knownAvmMode);
    }

    public MirrorSource(Context ctx, int cameraId, int side, boolean quad, MirrorFrameSink push,
                        MirrorFrameSink localSink, int knownAvmMode) {
        this.knownAvmMode = knownAvmMode;
        this.localSink = localSink;
        this.ctx = ctx.getApplicationContext();
        this.cameraId = cameraId;
        this.side = side;
        this.quad = quad;
        this.push = push;
    }

    public String lastError() { return lastError; }

    public void start() {
        final long deadline = android.os.SystemClock.elapsedRealtime() + 3000;
        handler.post(new Runnable() {
            @Override public void run() {
                if (closed) return;
                if (lease != null && lease != MirrorSource.this) {
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        fail("上一相机会话尚未关闭，暂停影像");
                    } else {
                        handler.postDelayed(this, 100);
                    }
                    return;
                }
                lease = MirrorSource.this;
                openCamera();
            }
        });
    }

    public boolean isStopped() { return closed; }

    public void stop() { stop(true); }

    public void stop(boolean restore) {
        if (!restore) restoreMode = false;
        closed = true; // Immediately invalidate queued open/configure/frame callbacks.
        handler.post(new Runnable() {
            @Override public void run() {
                closeOnOwner();
            }
        });
    }

    private void fail(String error) {
        lastError = error;
        Log.w(TAG, error);
        closed = true;
        closeOnOwner();
    }

    private void closeOnOwner() {
        readyAt = 0;
        if (avm != null) {
            if (restoreMode && restorationEpoch == NATIVE_EPOCH.get()) avm.close();
            avm = null;
        }
        try {
            if (session != null) { session.close(); session = null; }
        } catch (Throwable ignored) { }
        if (device != null) {
            // Keep reader/surface and lease alive until the matching onClosed.
            try { device.close(); } catch (Throwable t) { Log.w(TAG, "等待相机关闭: " + t); }
        } else if (!opening) {
            releaseReader();
        }
    }

    private void releaseReader() {
        if (reader != null) { reader.close(); reader = null; }
        if (lease == this) lease = null;
    }

    private boolean cancelledOnOwner() {
        if (!closed && restorationEpoch == NATIVE_EPOCH.get()) return false;
        closed = true;
        closeOnOwner();
        return true;
    }

    private void openCamera() {
        if (cancelledOnOwner()) return;
        try {
            final CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (mgr == null) { fail("无 CameraManager"); return; }
            String[] ids = mgr.getCameraIdList();
            if (ids.length == 0) { fail("无相机设备"); return; }
            String id = pickId(ids);
            if (cancelledOnOwner()) return;
            if (quad) {
                avm = new MirrorAvmSession(new MirrorAvmSession.Control() {
                    @Override public int current() { return AvmMode.current(mgr); }
                    @Override public boolean set(int mode) {
                        // Recheck after synchronous queries: native takeover may have arrived meanwhile.
                        if (!restoreMode || restorationEpoch != NATIVE_EPOCH.get() || (mode == 8 && closed)) {
                            return false;
                        }
                        return AvmMode.set(mgr, mode);
                    }
                }, knownAvmMode, knownAvmMode >= 0);
                if (!avm.prepare()) {
                    fail("无法记录进入前 AVM 模式，不切换鱼眼");
                    return;
                }
            }
            if (cancelledOnOwner()) return;
            CameraCharacteristics cc = mgr.getCameraCharacteristics(id);
            if (cancelledOnOwner()) return;
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) { fail("无 StreamConfigurationMap"); return; }
            Size sz = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (sz == null) { fail("无可用 YUV 尺寸"); return; }
            reader = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(listener, handler);
            Log.i(TAG, "打开相机 id=" + id + " " + sz + " side=" + side + " quad=" + quad);
            if (cancelledOnOwner()) return;
            opening = true;
            mgr.openCamera(id, callback, handler);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    // Timeout invalidates the intent, never the pending HAL ownership.
                    if (!closed && opening) fail("等待相机打开超时，保留关闭屏障");
                }
            }, 3000);
        } catch (Throwable t) {
            opening = false;
            fail("打开相机失败: " + t);
        }
    }

    private String pickId(String[] ids) {
        String want = String.valueOf(cameraId);
        for (String id : ids) if (id.equals(want)) return id;
        // ViewAssist maps AVM command channel 0 to the first exposed stream.
        // A missing manually selected camera must NOT silently show another view.
        if (quad) return ids[0];
        throw new IllegalArgumentException("配置的相机不存在: " + want);
    }

    /**
     * 选一个 16:9 的输出尺寸，优先 ≥960×540 里面积最小的（够清晰又不浪费带宽），
     * 没有 16:9 就退最大的。转换那一步自带缩放，所以尺寸不必正好 960×540。
     */
    private Size pickSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size smallest169 = null;
        Size best169 = null;
        Size largest = null;
        for (Size s : sizes) {
            boolean ratio169 = Math.abs(s.getWidth() * 9 - s.getHeight() * 16) <= 16 * 4;
            if (largest == null || s.getWidth() * s.getHeight() > largest.getWidth() * largest.getHeight()) {
                largest = s;
            }
            if (!ratio169) continue;
            if (s.getWidth() < OUT_W || s.getHeight() < OUT_H) continue;
            if (smallest169 == null
                    || s.getWidth() * s.getHeight() < smallest169.getWidth() * smallest169.getHeight()) {
                smallest169 = s;
            }
            // Quad crops one quarter; 1920x1080 gives 960x540 per cell instead of 640x362.
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080
                    && (best169 == null
                    || s.getWidth() * s.getHeight() > best169.getWidth() * best169.getHeight())) {
                best169 = s;
            }
        }
        if (quad && best169 != null) return best169;
        if (smallest169 != null) return smallest169;
        return best169 != null ? best169 : largest;
    }

    private final CameraDevice.StateCallback callback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice d) {
            opening = false;
            device = d;
            if (closed || lease != MirrorSource.this) { d.close(); return; }
            if (quad) {
                if (avm == null || !avm.activate()) {
                    fail("AVM 四宫格下发失败，不推送旧视角: " + AvmMode.lastError());
                    return;
                }
                awaitMode();
            } else {
                startSession();
            }
        }

        @Override public void onDisconnected(CameraDevice d) {
            opening = false;
            device = d;
            restoreMode = false; // Native AVM is taking ownership; never overwrite it.
            fail("相机断开（可能被原厂 AVM 抢占）");
        }

        @Override public void onError(CameraDevice d, int code) {
            opening = false;
            device = d;
            fail("相机错误 code=" + code);
        }

        @Override public void onClosed(CameraDevice d) {
            if (device != d) return;
            device = null;
            opening = false;
            releaseReader();
        }
    };

    private void awaitMode() {
        if (closed) return;
        if (avm != null && avm.ready()) {
            // Readback is a parameter-level check, NOT proof of optical content.
            // Also allow queued frames from the old composition to drain.
            readyAt = android.os.SystemClock.elapsedRealtime() + 250;
            startSession();
        } else if (++modeChecks < 10) {
            handler.postDelayed(new Runnable() {
                @Override public void run() { awaitMode(); }
            }, 100);
        } else {
            fail("AVM 模式读回未确认四宫格，停止影像: " + AvmMode.lastError());
        }
    }

    private void startSession() {
        if (closed || device == null || reader == null) return;
        final CameraDevice owner = device;
        try {
            CaptureRequest.Builder b = owner.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            Surface surface = reader.getSurface();
            b.addTarget(surface);
            configuring = true;
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (!closed && configuring && device == owner) fail("等待相机会话配置超时");
                }
            }, 3000);
            owner.createCaptureSession(java.util.Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            configuring = false;
                            if (closed || device != owner || lease != MirrorSource.this) {
                                s.close();
                                return;
                            }
                            session = s;
                            try {
                                s.setRepeatingRequest(b.build(), null, handler);
                            } catch (Throwable t) {
                                fail("下发预览请求失败: " + t);
                            }
                        }

                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            configuring = false;
                            s.close();
                            if (!closed && device == owner) fail("createCaptureSession 配置失败");
                        }
                    }, handler);
        } catch (Throwable t) {
            fail("建会话失败: " + t);
        }
    }

    private final ImageReader.OnImageAvailableListener listener =
            new ImageReader.OnImageAvailableListener() {
        @Override public void onImageAvailable(ImageReader r) {
            if (closed) return;
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img == null || closed) return;
                if (quad) {
                    long clock = android.os.SystemClock.elapsedRealtime();
                    if (clock < readyAt) return;
                    if (clock - modeCheckAt >= 200) {
                        modeCheckAt = clock;
                        if (avm == null || !avm.ready()) {
                            restoreMode = false;
                            fail("AVM 模式已被其他影像接管，停止推帧");
                            return;
                        }
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastFrameAt < 1000 / TARGET_FPS) return;
                lastFrameAt = now;
                long t0 = System.currentTimeMillis();
                convert(img);
                if (localSink != null) {
                    try {
                        convertLocal();
                        localSink.submit(localOut, side);
                    } catch (RuntimeException localError) {
                        Log.w(TAG, "本地高清帧失败: " + localError);
                    }
                }
                statConvertMs += System.currentTimeMillis() - t0;
                if (!closed) push.submit(out, side);
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
        srcW = img.getWidth();
        srcH = img.getHeight();
        yRowF = p[0].getRowStride(); yPixF = p[0].getPixelStride();
        uRowF = p[1].getRowStride(); uPixF = p[1].getPixelStride();
        vRowF = p[2].getRowStride(); vPixF = p[2].getPixelStride();

        yArr = ensure(yArr, yRowF * srcH + 16);
        uArr = ensure(uArr, uRowF * ((srcH + 1) / 2) + 16);
        vArr = ensure(vArr, vRowF * ((srcH + 1) / 2) + 16);
        copy(yb, yArr);
        copy(ub, uArr);
        copy(vb, vArr);

        // 源尺寸变了、或运行时取景参数被广播改了，都重建采样表（下一帧即生效）
        if (tabSrcW != srcW || tabSrcH != srcH || lastQuadCropSeq != quadCropSeq) {
            lastQuadCropSeq = quadCropSeq;
            buildTabs(srcW, srcH);
        }
        render(out, OUT_W, OUT_H, sxTab, syTab);
    }

    /** Local-only high-resolution frame for the head-unit overlay; network stays 640×360. */
    private void convertLocal() {
        if (localSink == null || localSx == null) return;
        render(localOut, LOCAL_W, LOCAL_H, localSx, localSy);
    }

    private void render(byte[] dst, int dw, int dh, int[] sx, int[] sy) {
        int i = 0;
        for (int y = 0; y < dh; y++) {
            int sampleY = sy[y];
            int yOff = sampleY * yRowF;
            int uOff = (sampleY >> 1) * uRowF;
            int vOff = (sampleY >> 1) * vRowF;
            for (int x = 0; x < dw; x++) {
                int sampleX = sx[x];
                int yv = yArr[yOff + sampleX * yPixF] & 0xFF;
                int u = (uArr[uOff + (sampleX >> 1) * uPixF] & 0xFF) - 128;
                int v = (vArr[vOff + (sampleX >> 1) * vPixF] & 0xFF) - 128;

                int r = yv + ((91881 * v) >> 16);
                int g = yv - ((22554 * u + 46802 * v) >> 16);
                int bl = yv + ((116130 * u) >> 16);
                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (bl < 0) bl = 0; else if (bl > 255) bl = 255;

                int v565 = ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (bl >> 3);
                dst[i++] = (byte) (v565 & 0xFF);
                dst[i++] = (byte) ((v565 >> 8) & 0xFF);
            }
        }
    }

    /** Output-to-source sampling map. Quad ROI is used only behind the AVM session gate.
     * Firmware must still be checked for correct physical left/right mapping on a parked vehicle.
     */
    private void buildTabs(int srcW, int srcH) {
        int[] r = computeRoi(srcW, srcH);
        roiX = r[0]; roiY = r[1]; roiW = r[2]; roiH = r[3];
        if (sxTab == null) { sxTab = new int[OUT_W]; syTab = new int[OUT_H]; }
        fillTabs(sxTab, syTab, OUT_W, OUT_H);
        if (localSink != null) {
            if (localSx == null) { localSx = new int[LOCAL_W]; localSy = new int[LOCAL_H]; }
            fillTabs(localSx, localSy, LOCAL_W, LOCAL_H);
        }
        tabSrcW = srcW;
        tabSrcH = srcH;
    }

    private void fillTabs(int[] sx, int[] sy, int dw, int dh) {
        for (int x = 0; x < dw; x++) {
            sx[x] = Math.min(srcW - 1, roiX + (int) ((long) x * roiW / dw));
        }
        for (int y = 0; y < dh; y++) {
            sy[y] = Math.min(srcH - 1, roiY + (int) ((long) y * roiH / dh));
        }
    }

    private int[] computeRoi(int srcW, int srcH) {
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
        }
        return new int[]{x0, y0, rw, rh};
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
