package com.qusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

/**
 * MD3 风格的输入框（filled text field）。
 *
 * <p>直接用系统 {@link EditText} 放进对话框出来是 Material 1 的样子 ——
 * 方角、系统下划线，跟整套界面完全不搭。这里按 Material Design 3 规范自绘，
 * 关键是那个**浮动标签**：
 *
 * <ul>
 *   <li>空且未聚焦时，标签停在输入位置，充当 placeholder</li>
 *   <li>聚焦或有内容时，标签缩小并上浮到容器顶部（这是 MD3 输入框最好认的特征）</li>
 *   <li>容器：12dp 圆角 + {@code surfaceContainerHighest}</li>
 *   <li>底部指示线：未聚焦 1dp，聚焦 2dp {@code primary}，且从中心向两侧展开</li>
 * </ul>
 */
public class MdField extends FrameLayout {

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final EditText input;

    private String label;
    private float focus;        // 0..1 聚焦进度
    private float floatT;       // 0..1 标签上浮进度
    private boolean focused;
    private float indicatorW;   // 指示线展开比例

    public MdField(Context c) { this(c, (String) null); }

    public MdField(Context c, String hint) {
        super(c);
        setWillNotDraw(false);
        label = hint == null ? "" : hint;

        input = new EditText(c);
        input.setBackground(null);                    // 去掉系统自带下划线
        input.setTextSize(15);
        input.setSingleLine(true);
        input.setGravity(Gravity.BOTTOM | Gravity.START);
        input.setHint("");                            // placeholder 由我们自己画
        input.setPadding(0, 0, 0, 0);
        applyColors();

        // 给浮动标签留出上方空间
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(30), Gravity.BOTTOM);
        lp.leftMargin = (int) dp(16);
        lp.rightMargin = (int) dp(16);
        lp.bottomMargin = (int) dp(9);
        addView(input, lp);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { kick(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) { focused = has; kick(); }
        });
    }

    public MdField(Context c, AttributeSet a) { this(c, (String) null); }

    private void kick() { postOnAnimation(anim); }

    private final Runnable anim = new Runnable() {
        @Override public void run() {
            boolean up = focused || input.getText().length() > 0;
            float tf = up ? 1f : 0f;
            float ff = focused ? 1f : 0f;

            float nt = floatT + (tf - floatT) * 0.30f;
            float nf = focus + (ff - focus) * 0.30f;
            float ni = indicatorW + (ff - indicatorW) * 0.24f;

            boolean moving = Math.abs(nt - tf) > 0.005f || Math.abs(nf - ff) > 0.005f
                    || Math.abs(ni - ff) > 0.005f;
            floatT = nt; focus = nf; indicatorW = ni;
            invalidate();
            if (moving) postOnAnimation(this);
        }
    };

    private void applyColors() {
        Tokens t = Theme.t();
        input.setTextColor(t.onSurface);
    }

    public EditText input() { return input; }
    public String text() { return input.getText().toString(); }

    public void setText(CharSequence s) {
        input.setText(s);
        input.setSelection(input.length());
        kick();
    }

    public void setLabel(String h) { label = h == null ? "" : h; invalidate(); }

    /** 调整输入字号（标签会跟着小一号） */
    public void setTextSize(float sp) {
        input.setTextSize(sp);
        baseSize = sp;
        invalidate();
    }
    private float baseSize = 15f;

    /** 密码模式 */
    public void setPassword(boolean b) {
        input.setInputType(b
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.setTypeface(android.graphics.Typeface.create(
                b ? "sans-serif" : "sans-serif", android.graphics.Typeface.NORMAL));
        invalidate();
    }

    /** 键盘「完成/搜索」时触发 */
    public void setOnSubmit(final Runnable r) {
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(android.widget.TextView v, int actionId,
                                                    android.view.KeyEvent e) {
                if (e == null || e.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
                    if (r != null) r.run();
                    return true;
                }
                return false;
            }
        });
    }

    public void setNumeric(boolean b) {
        input.setInputType(b ? InputType.TYPE_CLASS_NUMBER
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
    }

    public void focus() {
        input.requestFocus();
        android.view.inputmethod.InputMethodManager im =
                (android.view.inputmethod.InputMethodManager)
                        getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) {
            im.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
        kick();
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        super.onMeasure(wSpec, hSpec);
        int h = (int) dp(56);
        setMeasuredDimension(getMeasuredWidth(), h);
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        Tokens t = Theme.t();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float r = dp(12);
        box.set(0, 0, w, h);
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setColor(t.surfaceContainerHighest);
        c.drawRoundRect(box, r, r, p);

        // ── 浮动标签 ──
        // floatT=0：停在输入位置当 placeholder；floatT=1：缩小上浮
        float sizeBig = sp(baseSize), sizeSmall = sp(baseSize * 0.77f);
        float sz = sizeBig + (sizeSmall - sizeBig) * floatT;
        p.reset();
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(floatT > 0.5f ? Ui.tfMed() : Ui.tf());
        p.setTextSize(sz);
        p.setColor(focused
                ? Hct.blendLab(t.onSurfaceVariant, t.primary, focus)
                : Hct.withAlpha(t.onSurfaceVariant, 0.9f));

        float xBig = dp(16);
        float xSmall = dp(12);
        float x = xBig + (xSmall - xBig) * floatT;
        // 未上浮时垂直居中偏下（贴近输入基线）；上浮后贴近顶部
        float yBig = dp(30);
        float ySmall = dp(17);
        float y = yBig + (ySmall - yBig) * floatT;
        if (label.length() > 0) {
            c.save();
            c.clipRect(0, 0, w, h);
            c.drawText(label, x, y, p);
            c.restore();
        }

        // ── 底部指示线 ──
        float iy = h - dp(1.5f);
        float cur = w * indicatorW;
        float x0 = (w - cur) / 2f, x1 = (w + cur) / 2f;
        p.reset();
        if (indicatorW > 0.02f) {
            p.setColor(Hct.blendLab(t.onSurfaceVariant, t.primary, indicatorW));
            float th = dp(1) + dp(1.2f) * indicatorW;
            c.drawRect(0, iy, w, iy + dp(1), p);          // 底：始终有一条
            p.setColor(t.primary);
            c.drawRect(x0, iy, x1, iy + th, p);           // 聚焦：加粗并展开
        } else {
            p.setColor(t.onSurfaceVariant);
            c.drawRect(0, iy, w, iy + dp(1), p);
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }
}
