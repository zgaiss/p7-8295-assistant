package com.xiaopeng.clusterassist;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 仪表端布局示意图（1920×720 等比缩略）。
 * 车机屏上放这张图，是为了明确「现在操作的是车机，画面在仪表上」：
 * 每个虚线框对应显示内容页里的一组开关。
 */
public class InstrumentMap extends View {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint car = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tiny = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dpi;
    private final RectF r = new RectF();

    public InstrumentMap(Context c) {
        super(c);
        dpi = c.getResources().getDisplayMetrics().density;
        bg.setColor(0xFF151A20);
        car.setColor(0xFF232B33);
        box.setStyle(Paint.Style.STROKE);
        box.setStrokeWidth(1.2f * dpi);
        box.setColor(0xFF2BD88F);
        box.setPathEffect(new DashPathEffect(new float[]{4 * dpi, 3 * dpi}, 0));
        label.setColor(0xFF9FB0BC);
        label.setTextSize(9 * dpi);
        tiny.setColor(0xFF3A4650);
        tiny.setTextSize(8 * dpi);
    }

    private void map(float x0, float y0, float x1, float y1) {
        float w = getWidth(), h = getHeight();
        r.set(x0 / 1920f * w, y0 / 720f * h, x1 / 1920f * w, y1 / 720f * h);
    }

    /** 虚线框 + 顶部居中的标签（带底色，避免压住虚线） */
    private void region(Canvas cv, String text, float x0, float y0, float x1, float y1) {
        map(x0, y0, x1, y1);
        cv.drawRoundRect(r, 6 * dpi, 6 * dpi, box);
        float tw = label.measureText(text);
        float cx = r.centerX();
        float ty = r.top - 3 * dpi;
        Paint bgp = new Paint();
        bgp.setColor(0xFF151A20);
        cv.drawRect(cx - tw / 2 - 3 * dpi, ty - 9 * dpi, cx + tw / 2 + 3 * dpi, ty + 1 * dpi, bgp);
        cv.drawText(text, cx - tw / 2, ty, label);
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        cv.drawRoundRect(new RectF(0, 0, w, h), 10 * dpi, 10 * dpi, bg);

        // 车模本身
        map(850, 130, 1070, 590);
        cv.drawRoundRect(r, 8 * dpi, 8 * dpi, car);

        // 四个功能区
        region(cv, "中央车模", 800, 200, 1120, 650);
        region(cv, "左下角", 360, 636, 660, 716);
        region(cv, "底部中央", 700, 656, 1260, 718);
        region(cv, "右下角", 1296, 536, 1836, 640);

        cv.drawText("仪表 1920 × 720", 8 * dpi, h - 6 * dpi, tiny);
    }
}
