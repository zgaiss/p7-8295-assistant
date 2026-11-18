package com.xiaopeng.clusterassist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 歌词位置拖动画布。
 *
 * 按仪表 1920×720 等比映射，用户在本 View 上拖动「歌词块」设定它在仪表左上角区域的位置。
 * 画布按车机大屏实际可用尺寸铺开（外部给它足够大的 LayoutParams），触摸热区大、便于操作。
 *
 * - 歌词块包围盒 640×186（与仪表端 layout.go 的 lyricBoxW/H 一致），出界吸附到 [0,1920-640]×[0,720-186]。
 * - 拖动时实时回调 (dx,dy)，并画出可达范围预览与当前坐标。
 * - 仅调左上角歌词位置；「歌词显示在下方」是否生效由页面文案提示，本 View 不判断。
 */
public class LyricPositionMap extends View {
    public interface OnPosChanged {
        void onChanged(int dx, int dy, boolean dragging);
    }

    // 仪表逻辑坐标
    private static final float IC_W = 1920f, IC_H = 720f;
    /** 原始包围盒（仪表端 layout.go 的 lyricBoxW/H）；缩放时按比例放大这个基准 */
    private static final float BASE_W = 640f, BASE_H = 186f;
    private float boxW = BASE_W, boxH = BASE_H;
    private float maxX = IC_W - BASE_W; // 1280
    private float maxY = IC_H - BASE_H; // 534

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint car = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rangeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rangeEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float dpi;

    private int dx = 0, dy = 0;
    private boolean dragging = false;
    private float grabX, grabY; // 按下点相对块左上角的偏移（逻辑坐标）
    private boolean showRange = true;
    private OnPosChanged listener;

    public LyricPositionMap(Context c) {
        super(c);
        dpi = c.getResources().getDisplayMetrics().density;
        bg.setColor(0xFF0E1116);
        panel.setColor(0xFF171D24);
        car.setColor(0xFF232B33);
        zone.setStyle(Paint.Style.STROKE);
        zone.setStrokeWidth(1.4f * dpi);
        zone.setColor(0xFF2E3A44);
        zone.setPathEffect(new DashPathEffect(new float[]{6 * dpi, 5 * dpi}, 0));
        rangeFill.setColor(0x142BD88F);
        rangeEdge.setStyle(Paint.Style.STROKE);
        rangeEdge.setStrokeWidth(1.2f * dpi);
        rangeEdge.setColor(0x662BD88F);
        rangeEdge.setPathEffect(new DashPathEffect(new float[]{5 * dpi, 4 * dpi}, 0));
        blockFill.setColor(0xCC1B2A33);
        blockEdge.setStyle(Paint.Style.STROKE);
        blockEdge.setStrokeWidth(2.2f * dpi);
        blockEdge.setColor(0xFF2BD88F);
        accent.setColor(0xFF2BD88F);
        text.setColor(0xFFD6E2E8);
        text.setTextSize(13 * dpi);
        hint.setColor(0xFF7E8D98);
        hint.setTextSize(11 * dpi);
    }

    public void setListener(OnPosChanged l) { listener = l; }
    public void setShowRange(boolean show) { showRange = show; invalidate(); }

    /** 设置位置（自动钳制到屏内），dx/dy 为仪表绝对像素偏移。 */
    /** 设置歌词块整体缩放：包围盒与可达范围一起变，越界的 dx/dy 自动钳回来 */
    public void setScale(float k) {
        if (k <= 0) k = 1f;
        boxW = BASE_W * k;
        boxH = BASE_H * k;
        maxX = IC_W - boxW;
        maxY = IC_H - boxH;
        dx = clamp(dx, 0, (int) maxX);
        dy = clamp(dy, 0, (int) maxY);
        invalidate();
    }

    public void setPos(int nx, int ny) {
        dx = clamp(nx, 0, (int) maxX);
        dy = clamp(ny, 0, (int) maxY);
        invalidate();
    }
    public int getDx() { return dx; }
    public int getDy() { return dy; }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // 逻辑坐标 -> 屏幕像素
    private float sx(float icx) { return icx / IC_W * getWidth(); }
    private float sy(float icy) { return icy / IC_H * getHeight(); }
    // 屏幕像素 -> 逻辑坐标
    private float ix(float px) { return px / getWidth() * IC_W; }
    private float iy(float py) { return py / getHeight() * IC_H; }

    private void map(RectF dst, float x0, float y0, float x1, float y1) {
        dst.set(sx(x0), sy(y0), sx(x1), sy(y1));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        cv.drawRoundRect(new RectF(0, 0, w, h), 12 * dpi, 12 * dpi, bg);

        // 仪表底：车模 + 四个功能区虚线框（与「显示内容」页示意图一致）
        map(r, 850, 130, 1070, 590);
        cv.drawRoundRect(r, 8 * dpi, 8 * dpi, car);
        region(cv, "中央车模", 800, 200, 1120, 650);
        region(cv, "左下角", 360, 636, 660, 716);
        region(cv, "底部中央", 700, 656, 1260, 718);
        region(cv, "右下角", 1296, 536, 1836, 640);
        // 左上角歌词默认区（虚线）
        region(cv, "歌词区(左上)", 0, 0, 640, 186);

        // 可达范围预览：块左上角在 [0,maxX]×[0,maxY] 时块可覆盖的整片区域
        if (showRange) {
            map(r, 0, 0, maxX + boxW, maxY + boxH);
            cv.drawRoundRect(r, 10 * dpi, 10 * dpi, rangeFill);
        }

        // 当前歌词块
        map(r, dx, dy, dx + boxW, dy + boxH);
        cv.drawRoundRect(r, 10 * dpi, 10 * dpi, blockFill);
        cv.drawRoundRect(r, 10 * dpi, 10 * dpi, blockEdge);

        // 块内：三行歌词示意（歌名/当前/下一句），随块平移，让用户知道拖的是哪块
        float t = 12 * dpi;
        float lx = r.left + 16 * dpi;
        cv.drawText("♫ 歌名 · 歌手", lx, r.top + 26 * dpi + t, hint);
        cv.drawText("正在唱的歌词", lx, r.top + 26 * dpi + 34 * dpi + t, text);
        cv.drawText("下一句歌词预告", lx, r.top + 26 * dpi + 62 * dpi + t, hint);
        // 左上角强调竖条
        cv.drawRoundRect(new RectF(r.left + 6 * dpi, r.top + 34 * dpi + t,
                r.left + 10 * dpi, r.top + 74 * dpi + t), 2 * dpi, 2 * dpi, accent);

        // 角标：当前坐标
        String coord = "X " + dx + "   Y " + dy;
        float tw = text.measureText(coord);
        cv.drawText(coord, r.left + 16 * dpi, r.bottom - 12 * dpi, text);
        cv.drawText("拖动绿色框调整位置", w - hint.measureText("拖动绿色框调整位置") - 14 * dpi,
                h - 10 * dpi, hint);
    }

    private void region(Canvas cv, String label, float x0, float y0, float x1, float y1) {
        map(r, x0, y0, x1, y1);
        cv.drawRoundRect(r, 5 * dpi, 5 * dpi, zone);
        cv.drawText(label, r.left + 4 * dpi, r.top + 12 * dpi, hint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float px = ev.getX(), py = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 关键：接手手势，禁止外层 ScrollView 拦截。
                // 不加这句，稍微斜着拖一点，父容器就判成竖向滚动、给我们发 CANCEL，
                // 拖动中途断掉、页面跟着滑走——就是"拖着拖着变成滑页面"的原因。
                android.view.ViewParent vp = getParent();
                if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
                float ix = ix(px), iy = iy(py);
                // 命中块内则抓取；点在块外也直接把块吸过来（大屏上更好操作）
                if (ix >= dx && ix <= dx + boxW && iy >= dy && iy <= dy + boxH) {
                    grabX = ix - dx;
                    grabY = iy - dy;
                } else {
                    grabX = boxW / 2;
                    grabY = boxH / 2;
                }
                dragging = true;
                apply(ix - grabX, iy - grabY);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                apply(ix(px) - grabX, iy(py) - grabY);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging && listener != null) listener.onChanged(dx, dy, false);
                dragging = false;
                // 手势结束，把拦截权交还父容器，页面可以正常滚动
                android.view.ViewParent vp = getParent();
                if (vp != null) vp.requestDisallowInterceptTouchEvent(false);
                return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    /** 用逻辑坐标更新位置并吸附，拖动中每次都回调。 */
    private void apply(float nx, float ny) {
        int ndx = clamp(Math.round(nx), 0, (int) maxX);
        int ndy = clamp(Math.round(ny), 0, (int) maxY);
        if (ndx == dx && ndy == dy) return;
        dx = ndx;
        dy = ndy;
        invalidate();
        if (listener != null) listener.onChanged(dx, dy, true);
    }
}
