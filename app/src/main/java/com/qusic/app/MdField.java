package com.qusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * MD3 风格的输入框。
 *
 * <p>直接用系统 {@link EditText} 放进 {@code AlertDialog} 出来是 Holo/Material 1 的样子，
 * 跟整套界面完全不搭。这里按 Material Design 3 的 **filled text field** 规范自绘：
 *
 * <ul>
 *   <li>容器：圆角矩形，{@code surfaceContainerHighest}，顶部两角 4dp、底部 0（M3 filled 造型）</li>
 *   <li>底部指示线：未聚焦 1dp {@code onSurfaceVariant}；聚焦 2dp {@code primary}</li>
 *   <li>聚焦时指示线从中心向两侧展开（240ms，MD3 emphasized 曲线）</li>
 *   <li>前后缀留白、光标与选中色都跟随主题</li>
 * </ul>
 */
public class MdField extends FrameLayout {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final EditText input;

    private float focus;      // 0..1 聚焦进度
    private boolean focused;
    private float indicatorW; // 指示线展开宽度比例

    public MdField(Context c) { this(c, (String) null); }

    public MdField(Context c, String hint) {
        super(c);
        setWillNotDraw(false);
        input = new EditText(c);
        input.setBackground(null);                 // 关键：去掉系统自带下划线
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER_VERTICAL);
        input.setHint(hint == null ? "" : hint);
        input.setPadding(0, 0, 0, 0);
        applyColors();

        int padH = (int) dp(16);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(56));
        lp.leftMargin = padH;
        lp.rightMargin = padH;
        lp.topMargin = (int) dp(8);
        addView(input, lp);

        setPadding(0, 0, 0, (int) dp(8));

        input.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(android.view.View v, boolean has) {
                focused = has;
                animate().cancel();
                animate().setDuration(Theme.dur(200)).alpha(1f).start();
                invalidate();
                postInvalidateOnAnimation();
                startFocusLoop();
            }
        });
    }

    public MdField(Context c, AttributeSet a) { this(c, (String) null); }

    /** 让指示线的聚焦动画跑起来 */
    private void startFocusLoop() {
        postOnAnimation(new Runnable() {
            @Override public void run() {
                float target = focused ? 1f : 0f;
                boolean moving = Math.abs(focus - target) > 0.01f
                        || Math.abs(indicatorW - target) > 0.01f;
                focus += (target - focus) * 0.28f;
                // 指示线：聚焦时从中心展开，失焦时收回中心
                indicatorW += (target - indicatorW) * 0.22f;
                invalidate();
                if (moving) postOnAnimation(this);
            }
        });
    }

    private void applyColors() {
        Tokens t = Theme.t();
        input.setTextColor(t.onSurface);
        input.setHintTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        try {
            // 光标与选中色跟随主题
            android.content.res.ColorStateList cur = android.content.res.ColorStateList.valueOf(t.primary);
            reflectSet("setCursorColor", cur);
            input.setHighlightColor(Hct.withAlpha(t.primary, 0.28f));
        } catch (Throwable ignored) {}
    }

    private void reflectSet(String method, Object arg) {
        try {
            android.widget.TextView.class
                    .getMethod(method, android.content.res.ColorStateList.class)
                    .invoke(input, arg);
        } catch (Throwable ignored) {}
    }

    public EditText input() { return input; }
    public String text() { return input.getText().toString(); }
    public void setText(CharSequence s) { input.setText(s); input.setSelection(input.length()); }

    public void setHint(String h) { input.setHint(h); }

    /** 数字 / 普通文本等 */
    public void setNumeric(boolean b) {
        input.setInputType(b ? (InputType.TYPE_CLASS_NUMBER)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
    }

    public void focus() {
        input.requestFocus();
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        Tokens t = Theme.t();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float r = dp(4);
        box.set(0, 0, w, h);
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setColor(t.surfaceContainerHighest);
        c.drawRoundRect(box, r, r, p);

        // 底部指示线
        float y = h - dp(1);
        float full = w;
        float cur = full * indicatorW;
        float x0 = (w - cur) / 2f, x1 = (w + cur) / 2f;
        p.setColor(focused || focus > 0.02f
                ? Hct.blendLab(t.onSurfaceVariant, t.primary, Math.max(focus, indicatorW))
                : t.onSurfaceVariant);
        float th = dp(1) + dp(1) * Math.max(focus, indicatorW);
        c.drawRect(x0, y, x1, y + th, p);

        // 聚焦时的柔和描边，强化「已激活」
        if (focus > 0.02f) {
            p.reset();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.2f));
            p.setColor(Hct.withAlpha(t.primary, 0.5f * focus));
            c.drawRoundRect(box, r, r, p);
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
