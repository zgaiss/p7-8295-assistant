package com.xiaopeng.clusterassist;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 视觉规范取自原「P7仪表助手」v1.1.0：
 *   左侧 176dp 导航 + 右侧面板；10sp 灰色区域标签；开关在左、标题 15sp、说明 13sp；
 *   按钮 15sp / 44dp 高 / 12dp 圆角；输入框 44dp 高 / 10dp 圆角；分割线 1dp。
 * 配色与原版一一对应（日间/夜间两套）。
 */
public class Ui {
    /**
     * 全局字号放大系数。
     *
     * 原版（v1.1.0）是按「坐在驾驶位、手伸到 15 寸中控屏」定的字号：标题 15sp、说明 13sp、
     * 区域标签 10sp。实车看这些字偏小，需要低头凑近才看得清。这里只放大字号，
     * dp 尺寸（行高、间距、圆角、开关大小）一概不动，所以版面不会重排、也不会挤：
     * 15sp→17.7sp、13sp→15.3sp、10sp→11.8sp。
     *
     * 之所以不调 densityDpi（现在是 192）来放大：那会连间距、内边距一起放大，
     * 176dp 侧栏和各行宽度都会溢出。
     */
    public static final float FS = 1.18f;

    /**
     * 全局尺寸系数（dp 与字号同乘）。
     *
     * 车机给本 App 的窗口只有约 1440×1080 物理像素（density 1.2 → 1200dp 宽），
     * 而这套界面最初是按 2400×1200 全屏（2000dp）画的：直接铺到 1200dp 窗口里，
     * 字和按钮都只有设计值的六成，实车看就是"字非常小、按钮点不准"。
     *
     * 参考原版 P7ClusterAssist 的做法（它 attachBaseContext 设 192dpi 之后，
     * 又在 onCreate 里按 2.0/dAct 整树补偿 ≈1.67 倍）。这里取 1.5 倍：
     * 字比原版略大一档、控件同步变大，同时不把 176dp 侧栏和两列按钮挤到换行。
     */
    public static final float SCALE = 1.5f;

    public final Context c;
    public final Typeface bold;
    public final Typeface xpFont;   // 阿里巴巴普惠体（对齐原版）；加载失败为 null
    public final boolean night;

    public final int bgRoot, bgMain, bgNavSel, textTitle, textNav, textSub, textGrp,
            textChip, textSubtle, textVer, status, tagGreen, tagWarn, tagRed,
            btnDarkText, sep, btnGreen, btnDark, inputBg, inputStroke, logBg,
            btnPriText, stepperBg, stepperSym, chipOff;

    public Ui(Context c) {
        this.c = c;
        night = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            bgRoot = 0xFF15161A; bgMain = 0xFF191A1F; bgNavSel = 0xFF26292E;
            textTitle = 0xFFC9CDD3; textNav = 0xFFB9BEC6; textSub = 0xFF87898F;
            textGrp = 0xFF87898F; textChip = 0xFF87898F; textSubtle = 0xFF6E747E;
            textVer = 0xFF5E6B74; status = 0xFFE4C88A;
            tagGreen = 0xFF2BD88F; tagWarn = 0xFFE4AC6F; tagRed = 0xFFE58080;
            btnDarkText = 0xFFD6D9DD; sep = 0xFF1C1D21;
            btnGreen = 0xFF089969; btnDark = 0xFF1D1E22; inputBg = 0xFF1B1C20;
            inputStroke = 0xFF26272C; logBg = 0xFF0C0C0E; btnPriText = 0xFF062B1B;
            stepperBg = 0xFF43484E; stepperSym = 0xFFC9CDD4; chipOff = 0x14FFFFFF;
        } else {
            bgRoot = 0xFFE6E9F0; bgMain = 0xFFDFE3EB; bgNavSel = 0xFFFFFFFF;
            textTitle = 0xFF2E313A; textNav = 0xFF5E6874; textSub = 0xFF70747D;
            textGrp = 0xFF70747D; textChip = 0xFF70747D; textSubtle = 0xFF8A93A0;
            textVer = 0xFF98A1AD; status = 0xFFA67C2E;
            tagGreen = 0xFF0A8F5F; tagWarn = 0xFFB0752A; tagRed = 0xFFC4574E;
            btnDarkText = 0xFF2E323B; sep = 0xFFC9D0DA;
            btnGreen = 0xFF0CC985; btnDark = 0xFFFFFFFF; inputBg = 0xFFFFFFFF;
            inputStroke = 0xFFC9D0DA; logBg = 0xFFF6F8FA; btnPriText = 0xFFFFFFFF;
            stepperBg = 0xFFD4D8E2; stepperSym = 0xFF2D303A; chipOff = 0x142E323B;
        }
        // 对齐原版：界面字体统一用阿里巴巴普惠体（完整字体，8.6MB）
        Typeface xf = null;
        try {
            xf = Typeface.createFromAsset(c.getAssets(), "Alibaba-PuHuiTi-Regular.ttf");
        } catch (Throwable ignored) {
        }
        xpFont = xf;
        bold = Typeface.create(xf != null ? xf : Typeface.DEFAULT, Typeface.BOLD);
    }

    public int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()) * SCALE);
    }

    public TextView t(String s, float sp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp * FS * SCALE);
        v.setTextColor(color);
        return v;
    }

    public GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public GradientDrawable stroke(int fill, int strokeColor, float radiusDp, float wDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setStroke(dp(wDp), strokeColor);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 面板顶部标题行：20sp 粗体 + 状态 chip（11sp，淡绿底） */
    public LinearLayout pageHeader(String title, TextView chip) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t1 = t(title, 20, textTitle);
        t1.setTypeface(bold);
        r.addView(t1);
        if (chip != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(12);
            r.addView(chip, lp);
        }
        return r;
    }

    public TextView chip(String s) {
        TextView v = t(s, 11, textChip);
        v.setBackground(round(0x2917C97B, 9));
        v.setPadding(dp(10), dp(4), dp(10), dp(4));
        return v;
    }

    /** 区域标签：10sp 灰字，例如「中央车模 · 驻车/行驶」 */
    public TextView group(String s) {
        TextView v = t(s, 10, textGrp);
        v.setPadding(0, dp(16), 0, dp(3));
        return v;
    }

    public View sep() {
        View v = new View(c);
        v.setBackgroundColor(sep);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    /** 开关项：开关在左，右侧「标题 + 标签」与 13sp 说明 */
    public LinearLayout toggleItem(String title, String tag, String desc, boolean checked,
                                   PillSwitch.OnChange onChange) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));

        PillSwitch sw = new PillSwitch(c);
        sw.setCheckedSilently(checked);
        sw.setOnChange(onChange);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(48), dp(22));
        slp.rightMargin = dp(12);
        slp.topMargin = dp(2);
        row.addView(sw, slp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(t(title, 15, textTitle));
        if (tag != null && !tag.isEmpty()) {
            TextView tg = t(tag, 10, textChip);
            tg.setBackground(round(0x2917C97B, 9));
            tg.setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = dp(8);
            head.addView(tg, tlp);
        }
        col.addView(head);
        if (desc != null && !desc.isEmpty()) {
            TextView d = t(desc, 13, textSub);
            d.setPadding(0, dp(4), 0, 0);
            col.addView(d);
        }
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setTag(sw);
        return row;
    }

    /** 步进器项：左侧标签，右侧 [− 数值 +] 圆角块 */
    public LinearLayout stepperItem(String label, String value,
                                    View.OnClickListener minus, View.OnClickListener plus) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(t(label, 15, textTitle),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(round(stepperBg, 10));

        TextView m = t("−", 22, stepperSym);
        m.setGravity(Gravity.CENTER);
        m.setOnClickListener(minus);
        box.addView(m, new LinearLayout.LayoutParams(dp(44), dp(40)));

        TextView v = t(value, 15, textTitle);
        v.setTypeface(bold);
        v.setGravity(Gravity.CENTER);
        v.setSingleLine(true);
        box.addView(v, new LinearLayout.LayoutParams(dp(86), dp(40)));

        TextView p = t("+", 22, stepperSym);
        p.setGravity(Gravity.CENTER);
        p.setOnClickListener(plus);
        box.addView(p, new LinearLayout.LayoutParams(dp(44), dp(40)));

        row.addView(box);
        row.setTag(v);
        return row;
    }

    /** 按钮：15sp / 44dp 高 / 12dp 圆角，primary 为绿底 */
    public TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView b = t(label, 15, primary ? btnPriText : btnDarkText);
        b.setTypeface(bold);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(primary ? btnGreen : btnDark, 12));
        b.setOnClickListener(l);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** 一行动作按钮（等宽、10dp 间隔、44dp 高） */
    public LinearLayout buttonRow(View... buttons) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        row.setLayoutParams(lp);
        for (int i = 0; i < buttons.length; i++) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) blp.leftMargin = dp(10);
            row.addView(buttons[i], blp);
        }
        return row;
    }

    public android.widget.EditText input(String value, int inputType, int widthDp) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setText(value);
        e.setTextSize(15 * FS * SCALE);
        e.setTextColor(textTitle);
        e.setInputType(inputType);
        e.setSingleLine(true);
        e.setBackground(stroke(inputBg, inputStroke, 10, 1));
        e.setPadding(dp(16), 0, dp(16), 0);
        e.setLayoutParams(new LinearLayout.LayoutParams(
                widthDp <= 0 ? ViewGroup.LayoutParams.MATCH_PARENT : dp(widthDp), dp(44)));
        return e;
    }

    /** 两列并排（原版里成对出现的开关就是这样摆的） */
    public LinearLayout pair(View left, View right) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(left, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rp.leftMargin = dp(28);
        if (right != null) row.addView(right, rp);
        return row;
    }

    public TextView hint(String s) {
        TextView v = t(s, 10, textSubtle);
        v.setPadding(0, dp(8), 0, 0);
        return v;
    }

    public TextView note(String s) {
        TextView v = t(s, 13, textSub);
        v.setLineSpacing(dp(4), 1f);
        return v;
    }
}
