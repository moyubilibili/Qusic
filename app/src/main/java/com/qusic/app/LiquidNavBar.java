package com.qusic.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * MD3 Navigation Bar（标准 Material Design 3 底栏）。
 *
 * <p>严格按 MD3 规范实现：
 * <ul>
 *   <li>容器高度 80dp，颜色 surfaceContainer</li>
 *   <li>每个 item：图标 24dp + 标签 12sp（activeIndicatorLabel）</li>
 *   <li>选中态：64×32dp 的 primaryContainer 胶囊指示器，图标变 onPrimaryContainer</li>
 *   <li>指示器在标签间滑动用 MD3 emphasized 曲线（不是弹簧，MD3 不用回弹）</li>
 *   <li>顶部 1dp 的 outlineVariant 分隔线</li>
 *   <li>按下时图标有轻微缩放反馈</li>
 * </ul>
 *
 * <p>不用实时背景模糊：MD3 的底栏本来就是实色的，这也顺带避免了 PixelCopy
 * 带来的性能与 ANR 风险。
 */
public class LiquidNavBar extends View {

    public interface OnTabSelected { void onSelect(int index); }

    private static final String[] LABELS = {"首页", "曲库", "搜索", "社区", "关于"};
    private static final int N = 5;

    /** MD3 规格：指示器 64×32dp */
    private static final float IND_W = 64f;
    private static final float IND_H = 32f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path indPath = new Path();
    private final RectF bounds = new RectF();
    private final RectF indRect = new RectF();
    private final RectF iconBox = new RectF();

    /** 底部系统手势条高度：底栏背景铺到屏幕最底，内容放在它上面 */
    private int bottomInset;

    private float indPos;          // 指示器当前位置（浮点索引）
    private float indTarget;
    private int selected;
    private float pressScale = 1f;
    private float entrance = 0f;

    private OnTabSelected cb;
    private final Icons.NavIcon[] icons = new Icons.NavIcon[N];
    private ValueAnimator indAnim;

    public LiquidNavBar(Context c) { this(c, 0); }

    public LiquidNavBar(Context c, int initialTab) {
        super(c);
        selected = initialTab;
        indPos = indTarget = initialTab;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        icons[0] = Icons.HOME; icons[1] = Icons.LIBRARY;
        icons[2] = Icons.SEARCH; icons[3] = Icons.COMMUNITY; icons[4] = Icons.ABOUT;
    }

    public void setOnTabSelected(OnTabSelected c) { this.cb = c; }

    /** 由 Activity 传入真实的手势条高度 */
    public void setBottomInset(int px) {
        if (px == bottomInset) return;
        bottomInset = px;
        requestLayout();
        invalidate();
    }

    /** 内容区高度（不含手势条） */
    private float contentH() { return Math.max(1f, bounds.height() - bottomInset); }
    public int selectedIndex() { return selected; }

    public void select(int i, boolean animate) {
        if (i < 0 || i >= N) return;
        boolean changed = i != selected;
        selected = i;
        if (!animate) {
            if (indAnim != null) indAnim.cancel();
            indPos = indTarget = i;
            invalidate();
        } else {
            animateIndicator(i);
        }
        if (changed && cb != null) cb.onSelect(i);
    }

    private void animateIndicator(float target) {
        if (indAnim != null) indAnim.cancel();
        final float from = indPos;
        indTarget = target;
        indAnim = ValueAnimator.ofFloat(0f, 1f);
        indAnim.setDuration(Theme.dur(420));
        // MD3 emphasized：没有回弹，只有干净的减速
        indAnim.setInterpolator(Theme.EMPHASIZED);
        indAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                indPos = from + (indTarget - from) * f;
                invalidate();
            }
        });
        indAnim.start();
    }

    /** 入场：底栏从下方淡入上浮 */
    public void playEntrance() {
        entrance = 0f;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(Theme.dur(560));
        va.setInterpolator(Theme.EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                entrance = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = (int) dp(80) + bottomInset;   // MD3 80dp 内容 + 手势条，整条贴底
        setMeasuredDimension(w, resolveSize(h, hSpec));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bounds.set(0, 0, w, h);
        updateIndicator();
    }

    private float tabCenter(int i) {
        float cell = bounds.width() / N;
        return cell * i + cell / 2f;
    }

    private void updateIndicator() {
        float cx = tabCenter(Math.round(indPos));
        // 指示器在两标签之间插值滑动
        int lo = (int) Math.floor(indPos);
        int hi = (int) Math.ceil(indPos);
        lo = Math.max(0, Math.min(N - 1, lo));
        hi = Math.max(0, Math.min(N - 1, hi));
        float t = indPos - lo;
        float x = tabCenter(lo) + (tabCenter(hi) - tabCenter(lo)) * t;
        float cy = bounds.top + contentH() * 0.38f;
        float iw = dp(IND_W), ih = dp(IND_H);
        indRect.set(x - iw / 2, cy - ih / 2, x + iw / 2, cy + ih / 2);
        indPath.reset();
        indPath.addRoundRect(indRect, ih / 2, ih / 2, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas c) {
        if (bounds.width() <= 1) return;
        updateIndicator();
        Tokens t = Theme.t();

        int save = c.save();
        if (entrance < 1f) {
            c.translate(0, (1 - entrance) * dp(24));
            setAlpha(Math.min(1f, entrance * 1.5f));
        }
        if (pressScale != 1f) {
            // 按下时整体轻微收缩，MD3 的「状态层」反馈
            c.scale(pressScale, pressScale, bounds.centerX(), bounds.centerY());
        }

        // ① 容器背景（MD3 surfaceContainer）
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(t.surfaceContainer);
        c.drawRect(bounds, p);

        // ② 顶部 1dp 分隔线
        p.setColor(Hct.withAlpha(t.outlineVariant, 0.6f));
        c.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + Math.max(1f, dp(1)), p);

        // ③ 选中指示器（primaryContainer 胶囊）
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(t.primaryContainer);
        c.drawPath(indPath, p);

        // ④ 图标 + 标签
        for (int i = 0; i < N; i++) {
            float sel = 1f - Math.min(1f, Math.abs(indPos - i));
            float cx = tabCenter(i);
            float cy = bounds.top + contentH() * 0.38f;

            int color = Hct.blendLab(t.onSurfaceVariant, t.onPrimaryContainer, sel);

            float iconSize = dp(24);
            iconBox.set(cx - iconSize / 2, cy - iconSize / 2, cx + iconSize / 2, cy + iconSize / 2);
            Icons.NavIcon d = icons[i];
            if (d != null) d.draw(c, iconBox, color, sel);

            p.reset();
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(sel > 0.5f ? Ui.tfBold() : Ui.tfMed());
            p.setTextSize(dp(12));
            p.setColor(color);
            c.drawText(LABELS[i], cx, bounds.top + contentH() - dp(14), p);
        }

        c.restoreToCount(save);
    }

    // ── 触摸 ────────────────────────────────────────────────────────────────
    private int indexAt(float x) {
        float cell = bounds.width() / N;
        if (cell <= 0) return selected;
        return Math.max(0, Math.min(N - 1, (int) (x / cell)));
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animatePress(0.97f, 120);
                return true;
            case MotionEvent.ACTION_MOVE:
                // MD3 底栏支持横向拖动切换
                int ni = indexAt(x);
                if (ni != selected) select(ni, true);
                return true;
            case MotionEvent.ACTION_UP:
                animatePress(1f, 260);
                {
                    int idx = indexAt(x);
                    if (idx != selected) select(idx, true);
                    else Ui.hapticLight(this);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                animatePress(1f, 260);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void animatePress(float to, int dur) {
        ValueAnimator va = ValueAnimator.ofFloat(pressScale, to);
        va.setDuration(Theme.dur(dur));
        va.setInterpolator(to < 1f ? Theme.EMPHASIZED_ACCEL : Theme.EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pressScale = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
    }
}
