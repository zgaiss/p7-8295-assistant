package com.xiaopeng.clusterassist;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * 胶囊开关，尺寸与配色照原版 PillToggle：轨道 48×22dp、圆点 16dp、边距 3dp；
 * 开：#0DCA85（夜 #089969），关：#CDD4DD（夜 #31333A）。
 */
public class PillSwitch extends View {

    public interface OnChange {
        void onChanged(PillSwitch sw, boolean checked);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean checked;
    private float anim;
    private ValueAnimator animator;
    private OnChange listener;
    private final boolean night;
    private final float dpi;

    public PillSwitch(Context c) {
        super(c);
        night = (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        dpi = c.getResources().getDisplayMetrics().density;
        dot.setColor(0xFFFFFFFF);
        setClickable(true);
    }

    public boolean isChecked() { return checked; }

    public void setChecked(boolean v) {
        if (checked == v) return;
        checked = v;
        animateTo(v);
        if (listener != null) listener.onChanged(this, v);
    }

    public void setCheckedSilently(boolean v) {
        checked = v;
        anim = v ? 1f : 0f;
        invalidate();
    }

    public void setOnChange(OnChange l) { listener = l; }

    @Override public boolean performClick() {
        setChecked(!checked);
        return super.performClick();
    }

    private void animateTo(boolean v) {
        float to = v ? 1f : 0f;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(anim, to);
        animator.setDuration(140);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                anim = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        int on = night ? 0xFF089969 : 0xFF0DCA85;
        int off = night ? 0xFF31333A : 0xFFCDD4DD;
        track.setColor(checked || anim > 0.5f ? on : off);
        float r = h / 2f;
        canvas.drawRoundRect(rect, r, r, track);
        float d = 16f * dpi;
        float margin = 3f * dpi;
        float x = margin + anim * (w - margin * 2 - d);
        canvas.drawCircle(x + d / 2f, r, d / 2f, dot);
    }
}
