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
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional local consumer of the SAME RGB565 frames as the cluster. Never opens a camera. */
final class MirrorOverlay {
    private static final String TAG = "P7MirrorOverlay";
    private static final long STALE_MS = 800;
    private static final int LOCAL_W = 960;
    private static final int LOCAL_H = 540;
    private static final int BYTES = LOCAL_W * LOCAL_H * 2;
    private static final int MIN_W_DP = 240;
    private static final int DEFAULT_W_DP = 480;
    private static final int MARGIN_DP = 16;
    private static final int GRIP_DP = 64;

    /** Persist one side's edited geometry. Called on the main thread after a drag. */
    interface Store {
        void save(int side, int xDp, int yDp, int wDp);
    }

    private final Context context;
    private final WindowManager windows;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MirrorPreviewFrames frames = new MirrorPreviewFrames(BYTES);
    private final byte[] renderPixels = new byte[BYTES];
    private final ByteBuffer renderBuffer = ByteBuffer.wrap(renderPixels);
    private final AtomicBoolean renderPosted = new AtomicBoolean();
    private final Store store;
    private final GestureDetector gestures;
    private volatile boolean destroyed;
    private boolean enabled, attached, editing;
    private int side;
    private int geomX = -1, geomY = -1, geomW = -1;
    private float downRawX, downRawY;
    private int startX, startY, startW;
    private boolean resizing, moved;
    private FrameView view;
    private WindowManager.LayoutParams layout;
    private long shownAt, retryAt;

    MirrorOverlay(Context context, Store store) {
        this.context = context.getApplicationContext();
        this.store = store;
        windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        gestures = new GestureDetector(this.context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                toggleEdit();
                return true;
            }
        });
    }

    /** User geometry for the active side; -1 falls back to that side's default. */
    void setGeometry(int xDp, int yDp, int wDp) {
        if (editing) return;
        if (geomX == xDp && geomY == yDp && geomW == wDp) return;
        geomX = xDp;
        geomY = yDp;
        geomW = wDp;
        if (attached) updateLayout();
    }

    boolean isEditing() { return editing; }

    void setEnabled(boolean value) {
        enabled = value && !destroyed;
        frames.setEnabled(enabled);
        if (!enabled) removeWindow();
    }

    MirrorFrameSink begin(int direction) {
        side = direction;
        final long token = frames.begin(direction);
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
                if (view == null) {
                    view = new FrameView(context);
                    view.setOnTouchListener(new View.OnTouchListener() {
                        @Override public boolean onTouch(View v, MotionEvent e) {
                            return MirrorOverlay.this.onTouch(v, e);
                        }
                    });
                }
                renderBuffer.rewind();
                view.bitmap.copyPixelsFromBuffer(renderBuffer);
                view.side = frame.side;
                if (!editing) updateLayout();
                long remaining = STALE_MS - (SystemClock.elapsedRealtime() - frame.at);
                if (remaining <= 0 && !editing) {
                    removeWindow();
                    return;
                }
                if (!attached) {
                    attached = true; // Also attempt cleanup if a vendor addView partially succeeds.
                    windows.addView(view, layout);
                    main.removeCallbacks(expire);
                    if (!editing) main.postDelayed(expire, Math.max(0, remaining));
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
            if (!attached || editing) return;
            long age = SystemClock.elapsedRealtime() - shownAt;
            if (!permitted() || age < 0 || age >= STALE_MS) {
                removeWindow();
            } else {
                main.postDelayed(this, STALE_MS - age);
            }
        }
    };

    private void toggleEdit() {
        if (destroyed || !attached || view == null || !permitted()) return;
        editing = !editing;
        view.editing = editing;
        if (editing) {
            main.removeCallbacks(expire);
            Log.i(TAG, "进入悬浮窗编辑：拖动移动，右下角缩放，双击完成");
        } else {
            persistGeometry();
            long age = SystemClock.elapsedRealtime() - shownAt;
            if (age < 0 || age >= STALE_MS) removeWindow();
            else main.postDelayed(expire, STALE_MS - age);
        }
        view.invalidate();
    }

    private void persistGeometry() {
        if (store == null || view == null || layout == null || side == 0) return;
        DisplayMetrics m = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(m);
        int x = Math.round(layout.x / m.density);
        int y = Math.round(layout.y / m.density);
        int w = Math.round(layout.width / m.density);
        geomX = x;
        geomY = y;
        geomW = w;
        store.save(side, x, y, w);
    }

    private boolean onTouch(View v, MotionEvent e) {
        gestures.onTouchEvent(e);
        if (!editing || layout == null || view == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                moved = false;
                startX = layout.x;
                startY = layout.y;
                startW = layout.width;
                DisplayMetrics dm = new DisplayMetrics();
                windows.getDefaultDisplay().getRealMetrics(dm);
                int grip = Math.round(GRIP_DP * dm.density);
                resizing = downRawX >= layout.x + layout.width - grip
                        && downRawY >= layout.y + layout.height - grip;
                return true;
            case MotionEvent.ACTION_MOVE:
                DisplayMetrics m = new DisplayMetrics();
                windows.getDefaultDisplay().getRealMetrics(m);
                float dx = e.getRawX() - downRawX;
                float dy = e.getRawY() - downRawY;
                if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                if (resizing) {
                    int min = Math.round(MIN_W_DP * m.density);
                    int margin = Math.round(MARGIN_DP * m.density);
                    int max = Math.min(m.widthPixels - 2 * margin,
                            (m.heightPixels - 2 * margin) * 16 / 9);
                    int w = Math.max(min, Math.min(max, startW + Math.round(dx)));
                    layout.width = w;
                    layout.height = Math.max(1, w * 9 / 16);
                    layout.x = Math.max(0, Math.min(layout.x, m.widthPixels - layout.width));
                    layout.y = Math.max(0, Math.min(layout.y, m.heightPixels - layout.height));
                } else {
                    layout.x = Math.max(0, Math.min(m.widthPixels - layout.width, startX + Math.round(dx)));
                    layout.y = Math.max(0, Math.min(m.heightPixels - layout.height, startY + Math.round(dy)));
                }
                try {
                    windows.updateViewLayout(v, layout);
                } catch (RuntimeException ignored) { }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                DisplayMetrics up = new DisplayMetrics();
                windows.getDefaultDisplay().getRealMetrics(up);
                boolean done = e.getActionMasked() == MotionEvent.ACTION_UP && !moved
                        && downRawX < layout.x + 96 * up.density
                        && downRawY < layout.y + 40 * up.density;
                persistGeometry();
                if (done) toggleEdit();
                return true;
            default:
                return true;
        }
    }

    private void updateLayout() {
        DisplayMetrics metrics = new DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        int margin = Math.max(1, Math.round(MARGIN_DP * metrics.density));
        int min = Math.round(MIN_W_DP * metrics.density);
        int max = Math.min(metrics.widthPixels - 2 * margin,
                (metrics.heightPixels - 2 * margin) * 16 / 9);
        int want = geomW > 0 ? Math.round(geomW * metrics.density) : Math.round(DEFAULT_W_DP * metrics.density);
        int width = Math.max(1, Math.min(Math.max(min, want), Math.max(min, max)));
        int height = Math.max(1, width * 9 / 16);
        int x = geomX >= 0 ? Math.round(geomX * metrics.density)
                : (side == 1 ? margin : metrics.widthPixels - width - margin);
        int y = geomY >= 0 ? Math.round(geomY * metrics.density) : (metrics.heightPixels - height) / 2;
        x = Math.max(0, Math.min(metrics.widthPixels - width, x));
        y = Math.max(0, Math.min(metrics.heightPixels - height, y));
        if (layout != null && layout.width == width && layout.height == height
                && layout.x == x && layout.y == y) return;
        if (layout == null) {
            layout = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // Touchable so a double-tap can enter edit mode; still never takes focus.
            // Fully opaque: the user asked for no transparency.
            layout.alpha = 1f;
            layout.gravity = Gravity.TOP | Gravity.LEFT;
            layout.setTitle("P7 打灯影像");
        }
        layout.width = width;
        layout.height = height;
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.x = x;
        layout.y = y;
        if (attached) windows.updateViewLayout(view, layout);
    }

    private void removeWindow() {
        editing = false;
        main.removeCallbacks(expire);
        if (view != null) {
            view.editing = false;
            view.setVisibility(View.GONE);
        }
        if (!attached || windows == null) return;
        try {
            windows.removeViewImmediate(view);
            attached = false;
        } catch (IllegalArgumentException alreadyRemoved) {
            attached = false;
        } catch (RuntimeException error) {
            // Even if the vendor WindowManager rejects removal, pixels are already hidden
            // and this view cannot take focus. Subsequent stops retry.
            Log.w(TAG, "移除影像窗失败（已隐藏）: " + error);
        }
    }

    private static final class FrameView extends View {
        final Bitmap bitmap = Bitmap.createBitmap(LOCAL_W, LOCAL_H, Bitmap.Config.RGB_565);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Rect bounds = new Rect();
        int side;
        boolean editing;

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
            if (editing) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * unit);
                paint.setColor(0xFF2ECC71);
                canvas.drawRect(1.5f * unit, 1.5f * unit,
                        getWidth() - 1.5f * unit, getHeight() - 1.5f * unit, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(getWidth() - 48 * unit, getHeight() - 48 * unit,
                        getWidth(), getHeight(), paint);
                paint.setColor(0xFF101820);
                canvas.drawText("拖动/缩放", 8 * unit, getHeight() - 8 * unit, paint);
                paint.setColor(0xCC2ECC71);
                canvas.drawRect(0, 0, 96 * unit, 40 * unit, paint);
                paint.setColor(0xFF101820);
                paint.setTextSize(18 * unit);
                canvas.drawText("完成", 22 * unit, 27 * unit, paint);
            }
        }
    }
}
