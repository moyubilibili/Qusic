package com.qusic.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/**
 * 「滑动开始」控件。
 *
 * <p>为什么不用普通按钮：本机的屏下指纹节点（{@code oplus_fp_input}）同时也是
 * 唯一的 TOUCHSCREEN 设备，它会偶发地抛出**坐标固定、无位移**的幽灵触摸。
 * 普通按钮会被这种幽灵事件直接点掉（欢迎页会「自己」跳过）。
 * 而滑动需要一段真实的、持续的位移，幽灵事件满足不了，因此天然免疫。
 *
 * <p>交互：按住圆钮向右拖，拖到底触发；中途松手会弹回。
 */
public class SlideToActivate extends View {

    public interface OnActivated { void onActivated(); }

    private static final float KNOB_RATIO = 0.86f;   // 圆钮直径 / 控件高度

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF track = new RectF();
    private final RectF knob = new RectF();

    private float progress;         // 0..1
    private boolean dragging;
    private float downX, startProgress;
    private float hintPhase;
    private long lastFrame;
    private OnActivated cb;
    private boolean fired;

    public SlideToActivate(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        postOnAnimation(loop);
    }

    public void setOnActivated(OnActivated c) { this.cb = c; }

    private float dp(float v) { return Ui.dp(getContext(), v); }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            if (!dragging && progress < 0.02f) {
                hintPhase += dt * 0.9f;
                if (hintPhase > 1f) hintPhase -= 1f;
            }
            invalidate();
            postOnAnimationDelayed(this, 16);
        }
    };

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) dp(58);
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        track.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(track, h / 2f, h / 2f, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas c) {
        Tokens t = Theme.t();
        float w = getWidth(), h = getHeight();
        if (w <= 1) return;
        float r = h / 2f;

        // 轨道
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(t.surfaceContainerHigh);
        c.drawRoundRect(track, r, r, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Hct.withAlpha(t.outlineVariant, 0.6f));
        c.drawRoundRect(new RectF(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f)), r, r, p);

        float knobD = h * KNOB_RATIO;
        float margin = (h - knobD) / 2f;
        float travel = w - knobD - margin * 2f;
        float kx = margin + travel * progress;

        int save = c.save();
        c.clipPath(clip);

        // 已滑过的部分：渐变填充
        if (progress > 0.001f) {
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, 0, w, 0,
                    new int[]{t.primary, Hct.blendLab(t.primary, t.tertiary, 0.65f)},
                    null, Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(0, 0, kx + knobD / 2f + margin, h), r, r, p);
            p.setShader(null);
        }

        // 提示文字（未滑动时呼吸）
        float hintA = 1f - Math.min(1f, progress * 3f);
        if (hintA > 0.02f) {
            float pulse = 0.62f + 0.38f * (float) Math.abs(Math.sin(hintPhase * Math.PI));
            p.reset(); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Ui.tfMed()); p.setTextSize(dp(14.5f));
            int onTrack = Hct.lstar(t.surfaceContainerHigh) > 55 ? 0xFF1B1B1F : 0xFFFFFFFF;
            p.setColor(Hct.withAlpha(onTrack, 0.75f * hintA * pulse));
            c.drawText("滑动开始使用  →", w / 2f + dp(18), h / 2f + dp(5.2f), p);
        }

        // 滑到底时显示的文字
        if (progress > 0.75f) {
            float a = (progress - 0.75f) / 0.25f;
            p.reset(); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Ui.tfBold()); p.setTextSize(dp(14.5f));
            p.setColor(Hct.withAlpha(t.onPrimary, a));
            c.drawText("松手进入", w / 2f + dp(26), h / 2f + dp(5.2f), p);
        }

        c.restoreToCount(save);

        // 圆钮
        knob.set(kx, margin, kx + knobD, margin + knobD);
        Draw.softShadow(c, pathOf(knob), Hct.withAlpha(t.shadowTint, 0.35f), dp(6), 0, dp(2), p);

        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(knob.left, knob.top, knob.right, knob.bottom,
                new int[]{Color.WHITE, Hct.blendLab(Color.WHITE, t.primaryContainer, 0.35f)},
                null, Shader.TileMode.CLAMP));
        c.drawCircle(knob.centerX(), knob.centerY(), knobD / 2f, p);
        p.setShader(null);

        // 圆钮上的箭头
        RectF ib = new RectF(knob);
        ib.inset(knobD * 0.32f, knobD * 0.32f);
        Icons.draw(c, "chevron", ib, t.primary, 1.1f, p);
    }

    private final Path tmp = new Path();
    private Path pathOf(RectF r) {
        tmp.reset();
        tmp.addCircle(r.centerX(), r.centerY(), r.width() / 2f, Path.Direction.CW);
        return tmp;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float h = getHeight();
        float knobD = h * KNOB_RATIO;
        float margin = (h - knobD) / 2f;
        float travel = getWidth() - knobD - margin * 2f;
        if (travel <= 0) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 只有按在圆钮附近才算开始拖动
                float kx = margin + travel * progress + knobD / 2f;
                if (Math.abs(e.getX() - kx) > knobD * 0.85f) return false;
                dragging = true;
                fired = false;
                downX = e.getX();
                startProgress = progress;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                float np = startProgress + (e.getX() - downX) / travel;
                np = Math.max(0f, Math.min(1f, np));
                if (np - progress > 0.06f) Ui.hapticTick(this);
                progress = np;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                dragging = false;
                if (progress >= 0.9f && !fired) {
                    // 滑到底：触发激活
                    fired = true;
                    progress = 1f;
                    Ui.hapticStrong(this);
                    invalidate();
                    if (cb != null) cb.onActivated();
                } else {
                    // 弹回
                    ValueAnimator va = ValueAnimator.ofFloat(progress, 0f);
                    va.setDuration(Theme.dur(320));
                    va.setInterpolator(Theme.SPRING);
                    va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(ValueAnimator a) {
                            progress = (float) a.getAnimatedValue();
                            invalidate();
                        }
                    });
                    va.start();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
