package com.xiaopeng.clusterassist;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional local consumer of the SAME RGB565 frames as the cluster. Never opens a camera. */
final class MirrorOverlay {
    private static final String TAG = "P7MirrorOverlay";
    private static final long STALE_MS = 800;
    private static final int BYTES = MirrorPush.W * MirrorPush.H * 2;
    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MirrorPreviewFrames frames = new MirrorPreviewFrames(BYTES);
    private final byte[] renderPixels = new byte[BYTES];
    private final ByteBuffer renderBuffer = ByteBuffer.wrap(renderPixels);
    private final AtomicBoolean renderPosted = new AtomicBoolean();
    private volatile boolean destroyed;
    private boolean enabled, attached;
    private FrameView view;
    private WindowManager.LayoutParams layout;
    private long shownAt, retryAt;

    MirrorOverlay(Context context) {
        this.context = context.getApplicationContext();
        windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    // Lifecycle methods run on the service's main thread. Only the returned sink runs on camera owner.
    void setEnabled(boolean value) {
        enabled = value && !destroyed;
        frames.setEnabled(enabled);
        if (!enabled) removeWindow();
    }

    MirrorFrameSink begin(int side) {
        final long token = frames.begin(side);
        removeWindow();
        return new MirrorFrameSink() {
            @Override public void submit(byte[] pixels, int direction) {
                if (destroyed || !frames.offer(token, pixels, direction, SystemClock.elapsedRealtime())) return;
                if (renderPosted.compareAndSet(false, true)) main.post(render);
            }
        };
    }

    void hide() {
        frames.stop();
        removeWindow();
    }

    void destroy() {
        destroyed = true;
        enabled = false;
        frames.setEnabled(false);
        hide();
        main.removeCallbacks(render);
        main.removeCallbacks(expire);
        // Do not recycle a Bitmap that a compositor/render thread may still reference.
    }

    private boolean permitted() {
        try {
            return !destroyed && enabled && windows != null
                    && context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    && Settings.canDrawOverlays(context);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private final Runnable render = new Runnable() {
        @Override public void run() {
            renderPosted.set(false);
            if (!permitted()) {
                frames.setEnabled(false);
                removeWindow();
                return;
            }
            MirrorPreviewFrames.Frame frame = frames.takeInto(renderPixels, SystemClock.elapsedRealtime(), STALE_MS);
            if (frame == null || SystemClock.elapsedRealtime() < retryAt) return;
            try {
                if (view == null) view = new FrameView(context);
                renderBuffer.rewind();
                view.bitmap.copyPixelsFromBuffer(renderBuffer);
                view.side = frame.side;
                updateLayout();
                long remaining = STALE_MS - (SystemClock.elapsedRealtime() - frame.at);
                if (remaining <= 0) {
                    removeWindow();
                    return;
                }
                if (!attached) {
                    attached = true; // Also attempt cleanup if a vendor addView partially succeeds.
                    windows.addView(view, layout);
                    main.removeCallbacks(expire);
                    main.postDelayed(expire, Math.max(0,
                            STALE_MS - (SystemClock.elapsedRealtime() - frame.at)));
                }
                view.setVisibility(View.VISIBLE);
                shownAt = frame.at;
                view.invalidate();
            } catch (RuntimeException error) {
                Log.w(TAG, "悬浮窗显示失败，保留仪表推流: " + error);
                retryAt = SystemClock.elapsedRealtime() + 2000;
                removeWindow(); // Keep the preference; fresh frames may retry after backoff.
            }
        }
    };

    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            long age = SystemClock.elapsedRealtime() - shownAt;
            if (!permitted() || age < 0 || age >= STALE_MS) {
                removeWindow();
            } else {
                main.postDelayed(this, STALE_MS - age);
            }
        }
    };

    private void updateLayout() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        int margin = Math.max(1, Math.round(16 * metrics.density));
        int width = Math.max(1, Math.min(Math.round(480 * metrics.density),
                Math.min(metrics.widthPixels * 2 / 5, (metrics.heightPixels - 2 * margin) * 16 / 9)));
        int height = Math.max(1, width * 9 / 16);
        int gravity = Gravity.CENTER_VERTICAL | (view.side == 1 ? Gravity.LEFT : Gravity.RIGHT);
        if (layout != null && layout.width == width && layout.height == height
                && layout.gravity == gravity && layout.x == margin) return;
        if (layout == null) {
            layout = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // Non-interactive: never take touch/focus away from the host app. Android 12
            // restricts touch-through of application overlays above opacity 0.8.
            layout.alpha = 0.8f;
            layout.setTitle("P7 打灯影像");
        }
        layout.width = width;
        layout.height = height;
        layout.gravity = gravity;
        layout.x = margin;
        if (attached) windows.updateViewLayout(view, layout);
    }

    private void removeWindow() {
        main.removeCallbacks(expire);
        if (view != null) view.setVisibility(View.GONE);
        if (!attached || windows == null) return;
        try {
            windows.removeViewImmediate(view);
            attached = false;
        } catch (IllegalArgumentException alreadyRemoved) {
            attached = false;
        } catch (RuntimeException error) {
            // Even if the vendor WindowManager rejects removal, pixels are already hidden
            // and this non-touchable view cannot intercept controls. Subsequent stops retry.
            Log.w(TAG, "移除影像窗失败（已隐藏）: " + error);
        }
    }

    private static final class FrameView extends View {
        final Bitmap bitmap = Bitmap.createBitmap(MirrorPush.W, MirrorPush.H, Bitmap.Config.RGB_565);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Rect bounds = new Rect();
        int side;

        FrameView(Context context) { super(context); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            bounds.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bitmap, null, bounds, paint);
            float unit = getResources().getDisplayMetrics().density;
            paint.setColor(0xBB101820);
            canvas.drawRect(0, 0, getWidth(), 30 * unit, paint);
            paint.setTextSize(16 * unit);
            paint.setColor(Color.WHITE);
            canvas.drawText(side == 1 ? "左转影像" : "右转影像", 10 * unit, 21 * unit, paint);
        }
    }
}
