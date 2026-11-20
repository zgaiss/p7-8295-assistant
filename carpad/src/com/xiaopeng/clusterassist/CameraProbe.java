package com.xiaopeng.clusterassist;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 相机探针：把车机上每一路相机各拍一张存成 JPEG，用来搞清楚「哪路是哪个视角」。
 *
 * <p>动机：本车把 AVM 合成画面当一路相机暴露出来，默认抓到的是「360 俯视 + 前视」
 * 拼接图（带"前视"水印），打灯要的侧后方广角根本不在里面。厂商相机配置
 * {@code /vendor/etc/camera/cam_avm.xml} 显示底层有 4 路 1920×1536 的鱼眼输入，
 * 但哪一路以什么 id 暴露给应用、尺寸是多少，只有实测才知道。挨个打开拍一张，
 * 是最快也最没有副作用的做法（每次只开 1~3 秒，只读预览，不写任何系统状态）。</p>
 *
 * <p>输出：{@code /sdcard/Android/data/<pkg>/files/probe/cam_<id>.jpg}，
 * 同时把每一路的 facing、尺寸列表、实际取到的尺寸打进日志。</p>
 */
public final class CameraProbe {

    private static final String TAG = "P7CamProbe";
    private static final int TIMEOUT_S = 4;

    private CameraProbe() { }

    /** 逐路探测。必须在后台线程调用（会阻塞若干秒）。 */
    public static void runAll(Context ctx) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) {
            Log.w(TAG, "无 CameraManager");
            return;
        }
        File dir = new File(ctx.getExternalFilesDir(null), "probe");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "创建目录失败: " + dir);
        }
        String[] ids;
        try {
            ids = cm.getCameraIdList();
        } catch (Throwable t) {
            Log.w(TAG, "取相机列表失败: " + t);
            return;
        }
        Log.i(TAG, "共 " + ids.length + " 路相机: " + Arrays.toString(ids));
        for (String id : ids) {
            try {
                probeOne(ctx, cm, id, dir);
            } catch (Throwable t) {
                Log.w(TAG, "id=" + id + " 探测异常: " + t);
            }
        }
        Log.i(TAG, "探测结束，输出目录 " + dir.getAbsolutePath());
    }

    /** 只探某一路（模式对比时用）。 */
    public static void runOne(Context ctx, String id) {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return;
        File dir = new File(ctx.getExternalFilesDir(null), "probe");
        if (!dir.exists() && !dir.mkdirs()) return;
        try {
            probeOne(ctx, cm, id, dir);
        } catch (Throwable t) {
            Log.w(TAG, "id=" + id + " 探测异常: " + t);
        }
    }

    private static void probeOne(Context ctx, CameraManager cm, String id, File dir) throws Exception {
        HandlerThread ht = new HandlerThread("probe-" + id);
        ht.start();
        Handler h = new Handler(ht.getLooper());
        final CountDownLatch got = new CountDownLatch(1);
        final ImageReader[] readerRef = new ImageReader[1];
        final CameraDevice[] devRef = new CameraDevice[1];
        final String[] err = new String[1];

        try {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            Integer orient = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) {
                Log.w(TAG, "id=" + id + " 无 YUV 输出尺寸，跳过");
                return;
            }
            Log.i(TAG, "id=" + id + " facing=" + facing + " orient=" + orient
                    + " 尺寸数=" + sizes.length + " 最大的几个=" + topSizes(sizes));

            Size sz = pickSize(sizes);
            final ImageReader reader = ImageReader.newInstance(sz.getWidth(), sz.getHeight(),
                    ImageFormat.YUV_420_888, 1);
            readerRef[0] = reader;
            final File out = new File(dir, "cam_" + id + ".jpg");
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        saveJpeg(img, out);
                        Log.i(TAG, "id=" + id + " 已保存 " + out.getName()
                                + " " + img.getWidth() + "x" + img.getHeight());
                    } catch (Throwable t) {
                        err[0] = "保存失败: " + t;
                    } finally {
                        if (img != null) {
                            try { img.close(); } catch (Throwable ignored) { }
                        }
                        got.countDown();
                    }
                }
            }, h);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    devRef[0] = d;
                    try {
                        d.createCaptureSession(
                                java.util.Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(CameraCaptureSession s) {
                                        try {
                                            CaptureRequest.Builder b = d.createCaptureRequest(
                                                    CameraDevice.TEMPLATE_PREVIEW);
                                            b.addTarget(reader.getSurface());
                                            s.setRepeatingRequest(b.build(), null, h);
                                        } catch (Throwable t) {
                                            err[0] = "请求失败: " + t;
                                            got.countDown();
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession s) {
                                        err[0] = "会话配置失败";
                                        got.countDown();
                                    }
                                }, h);
                    } catch (Throwable t) {
                        err[0] = "建会话失败: " + t;
                        got.countDown();
                    }
                }
                @Override public void onDisconnected(CameraDevice d) {
                    err[0] = "断开";
                    got.countDown();
                }
                @Override public void onError(CameraDevice d, int code) {
                    err[0] = "打开错误 code=" + code;
                    got.countDown();
                }
            }, h);

            if (!got.await(TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "id=" + id + " 超时未出帧（可能被系统占用）");
            } else if (err[0] != null) {
                Log.w(TAG, "id=" + id + " " + err[0]);
            }
        } finally {
            try { if (devRef[0] != null) devRef[0].close(); } catch (Throwable ignored) { }
            try { if (readerRef[0] != null) readerRef[0].close(); } catch (Throwable ignored) { }
            ht.quitSafely();
        }
    }

    /** 优先 1280×720 左右：够看清内容，又不至于让每路探测太慢。 */
    private static Size pickSize(Size[] sizes) {
        Size best = sizes[0];
        long want = 1280L * 720L;
        long bestDiff = Long.MAX_VALUE;
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            long diff = Math.abs(area - want);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    private static String topSizes(Size[] sizes) {
        Size[] copy = sizes.clone();
        Arrays.sort(copy, new java.util.Comparator<Size>() {
            @Override public int compare(Size a, Size b) {
                return Long.compare((long) b.getWidth() * b.getHeight(),
                        (long) a.getWidth() * a.getHeight());
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, copy.length); i++) {
            if (i > 0) sb.append(", ");
            sb.append(copy[i].getWidth()).append('x').append(copy[i].getHeight());
        }
        return sb.toString();
    }

    /** YUV_420_888 → NV21 → JPEG。只用于诊断，慢一点没关系。 */
    private static void saveJpeg(Image img, File out) throws Exception {
        int w = img.getWidth(), h = img.getHeight();
        byte[] nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();

        ByteBuffer yb = p[0].getBuffer();
        int yRow = p[0].getRowStride(), yPix = p[0].getPixelStride();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                nv21[y * w + x] = yb.get(y * yRow + x * yPix);
            }
        }
        ByteBuffer ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
        int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
        int off = w * h;
        for (int y = 0; y < h / 2; y++) {
            for (int x = 0; x < w / 2; x++) {
                nv21[off + y * w + x * 2] = vb.get(y * vRow + x * vPix);
                nv21[off + y * w + x * 2 + 1] = ub.get(y * uRow + x * uPix);
            }
        }

        YuvImage yi = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        FileOutputStream fos = new FileOutputStream(out);
        try {
            yi.compressToJpeg(new Rect(0, 0, w, h), 80, fos);
        } finally {
            fos.close();
        }
    }
}
