package com.qusic.app;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * 切换配色 / 深浅色时的**圆形扩散**转场。
 *
 * <h3>原来的问题</h3>
 * 以前是直接 {@code Activity.recreate()} —— 整个界面「啪」地闪一下变成新配色，
 * 很生硬，也看不出是从哪儿变的。
 *
 * <h3>现在的做法</h3>
 * <ol>
 *   <li>切换前把**当前界面截图**存下来（此时还是旧配色）</li>
 *   <li>切换配色并重建 Activity</li>
 *   <li>把旧截图盖在最上层，然后用
 *       {@link ViewAnimationUtils#createCircularReveal} 从**被点的那个按钮**开始，
 *       把旧图一圈圈擦掉 —— 新配色就像从按钮里扩散出来一样</li>
 * </ol>
 *
 * <p>关键点：截图必须在 recreate **之前**取，那时候旧界面还在；
 * 所以用静态字段暂存，跨 Activity 重建传递。
 */
public final class ThemeReveal {

    /** 旧界面截图（跨 recreate 暂存） */
    private static Bitmap sSnapshot;
    /** 动画圆心（相对根视图） */
    private static float sCx = -1f, sCy = -1f;

    /** 硬边圆形扩散：适合明暗切换（边界清晰，一眼看出换了主题） */
    public static final int MODE_CIRCLE = 0;
    /** 软边墨水扩散：适合配色切换（颜色像墨滴一样化开） */
    public static final int MODE_INK = 1;

    private static int sMode = MODE_CIRCLE;

    /**
     * 圆形扩散的方向，每次切换自动交替：
     * true  = 新界面从中心向外推开
     * false = 新界面从四周向中心合拢
     */
    private static boolean sFromCenterOut = true;

    private ThemeReveal() {}

    /** 在切换主题前调用（传被点击的按钮，动画就从它开始扩散） */
    public static void arm(Activity a, View origin) { arm(a, origin, MODE_CIRCLE); }

    /**
     * @param mode {@link #MODE_CIRCLE} 硬边圆（明暗切换用）
     *             {@link #MODE_INK} 软边墨水（配色切换用）
     */
    public static void arm(Activity a, View origin, int mode) {
        sMode = mode;
        if (mode == MODE_CIRCLE) sFromCenterOut = !sFromCenterOut;   // 交替
        if (a == null) { clear(); return; }
        try {
            View decor = a.getWindow().getDecorView();
            int w = decor.getWidth(), h = decor.getHeight();
            if (w <= 0 || h <= 0) { armCenter(a); return; }

            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bmp));
            sSnapshot = bmp;

            if (origin != null) {
                int[] lo = new int[2], ld = new int[2];
                origin.getLocationInWindow(lo);
                decor.getLocationInWindow(ld);
                sCx = lo[0] - ld[0] + origin.getWidth() / 2f;
                sCy = lo[1] - ld[1] + origin.getHeight() / 2f;
            } else {
                sCx = w / 2f; sCy = h / 2f;
            }
        } catch (Throwable t) {
            clear();   // 截图失败就退化成普通切换，不能让功能挂掉
        }
    }

    /** 没传按钮时从屏幕中心扩散 */
    public static void armCenter(Activity a) { armCenter(a, sMode); }

    public static void armCenter(Activity a, int mode) {
        sMode = mode;
        if (mode == MODE_CIRCLE) sFromCenterOut = !sFromCenterOut;   // 交替
        if (a == null) { clear(); return; }
        try {
            View decor = a.getWindow().getDecorView();
            int w = decor.getWidth(), h = decor.getHeight();
            if (w > 0 && h > 0) {
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                decor.draw(new Canvas(bmp));
                sSnapshot = bmp;
            }
            sCx = w / 2f; sCy = h / 2f;
        } catch (Throwable t) {
            clear();
        }
    }

    /** 重建完成后调用：按 arm() 时选定的模式播放转场 */
    public static void play(final Activity a, ViewGroup content) {
        final Bitmap bmp = sSnapshot;
        if (bmp == null || a == null) return;
        sSnapshot = null;

        // 必须挂到 decor 上而不是内容区 —— 截图截的是整个 decor（含状态栏/导航栏），
        // 挂到内容区会被压扁。
        View decorView = a.getWindow().getDecorView();
        if (!(decorView instanceof ViewGroup)) return;
        final ViewGroup host = (ViewGroup) decorView;

        if (sMode == MODE_INK) playInk(a, host, bmp);
        else                   playCircle(a, host, bmp);
    }

    /**
     * 硬边圆形扩散 —— 明暗切换用，边界清晰。
     *
     * <p>方向每次交替：一次从中心向外推开，一次从四周向中心合拢。
     */
    private static void playCircle(final Activity a, final ViewGroup host, final Bitmap bmp) {
        final float cx = sCx, cy = sCy;
        final boolean out = sFromCenterOut;

        final CircleRevealView rv = new CircleRevealView(a, bmp, cx, cy, out);
        rv.setClickable(true);
        host.addView(rv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        host.post(new Runnable() {
            @Override public void run() {
                float w = host.getWidth(), h = host.getHeight();
                if (w <= 0 || h <= 0) { finish(host, rv, bmp); return; }
                final float maxR = CircleRevealView.maxRadiusFor(w, h, cx, cy);

                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(Theme.dur(560));
                va.setInterpolator(Theme.EMPHASIZED);
                va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator an) {
                        float p = (Float) an.getAnimatedValue();
                        // 从中心向外：半径 0 → maxR（旧界面被从中间掏空）
                        // 从外向内  ：半径 maxR → 0（旧界面缩向中心）
                        rv.setRadius(out ? maxR * p : maxR * (1f - p));
                    }
                });
                va.addListener(new AnimatorListenerAdapter() {
                    private boolean done = false;
                    @Override public void onAnimationEnd(Animator an) {
                        if (done) return;
                        done = true;
                        finish(host, rv, bmp);
                    }
                });
                va.start();
            }
        });
    }

    /** 软边墨水扩散 —— 配色切换用，颜色像墨滴一样化开 */
    private static void playInk(final Activity a, final ViewGroup host, final Bitmap bmp) {
        final int ink = Theme.t().primary;
        final InkRevealView ink0 = new InkRevealView(a, bmp, sCx, sCy, ink);
        ink0.setClickable(true);
        host.addView(ink0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        host.post(new Runnable() {
            @Override public void run() {
                float w = host.getWidth(), h = host.getHeight();
                if (w <= 0 || h <= 0) { finish(host, ink0, bmp); return; }

                final float cx = sCx, cy = sCy;
                final float maxR = InkRevealView.maxRadiusFor(w, h, cx, cy);

                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(Theme.dur(760));
                va.setInterpolator(Theme.EMPHASIZED);
                va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator an) {
                        float p = (Float) an.getAnimatedValue();
                        float r = maxR * Math.min(1f, p * 1.12f);
                        float inkA = p < 0.7f ? 1f : (1f - (p - 0.7f) / 0.3f);
                        ink0.setProgress(r, inkA);
                    }
                });
                va.addListener(new AnimatorListenerAdapter() {
                    private boolean done = false;
                    @Override public void onAnimationEnd(Animator an) {
                        if (done) return;
                        done = true;
                        finish(host, ink0, bmp);
                    }
                });
                va.start();
            }
        });
    }

    private static void finish(ViewGroup host, View overlay, Bitmap bmp) {
        try { host.removeView(overlay); } catch (Throwable ignored) {}
        try { if (!bmp.isRecycled()) bmp.recycle(); } catch (Throwable ignored) {}
    }

    private static void clear() {
        sSnapshot = null;
        sCx = sCy = -1f;
    }
}
