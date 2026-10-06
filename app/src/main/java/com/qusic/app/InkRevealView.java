package com.qusic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 「墨水扩散」转场。
 *
 * <h3>为什么不用 createCircularReveal</h3>
 * {@code ViewAnimationUtils.createCircularReveal} 只能做**硬边**的圆形裁剪 ——
 * 边界是一条锐利的圆弧，看起来像被人拿圆规划开，不像颜色自己化开。
 *
 * <h3>这里的做法</h3>
 * 用**径向渐变当遮罩**而不是几何裁剪：
 * <ol>
 *   <li>整层画旧界面的截图</li>
 *   <li>用 {@link PorterDuff.Mode#DST_OUT} + 径向渐变，把中间「擦」出一个洞 ——
 *       关键是渐变的**最后一段从全不透明渐到全透明**，于是擦除边界是**羽化**的，
 *       旧界面在边缘处柔和地溶掉，而不是被切开</li>
 *   <li>再在扩散前锋描一圈**新主题的中心色**（径向渐变做的环），
 *       这就是「墨水正在推开」的那一层</li>
 * </ol>
 *
 * <p>半径用 EMPHASIZED 曲线推进 —— 开头快、结尾慢，像墨滴入水先猛地散开再缓缓停住。
 */
public class InkRevealView extends View {

    private final Bitmap oldUi;
    private final float cx, cy;
    private final float maxR;
    private final int inkColor;

    private float radius = 0f;
    /** 墨水前锋的浓度，末段淡出 */
    private float inkAlpha = 1f;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public InkRevealView(Context c, Bitmap oldUi, float cx, float cy, int inkColor) {
        super(c);
        this.oldUi = oldUi;
        this.cx = cx;
        this.cy = cy;
        this.inkColor = inkColor;
        // ★ 关键：DST_OUT 是「擦除」类混合模式，**必须在自己独立的图层里**才能
        // 只擦掉自己画的内容。直接画在窗口画布上的话，会连下面的新界面和
        // 窗口背景一起擦掉 —— 那就是之前那段「全黑」的来源。
        setLayerType(LAYER_TYPE_HARDWARE, null);
        // 圆要大到能盖住离圆心最远的那个角，再留一点余量给羽化带
        this.maxR = 0f;
    }

    public void setProgress(float radius, float inkAlpha) {
        this.radius = radius;
        this.inkAlpha = inkAlpha;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (oldUi == null || oldUi.isRecycled()) return;
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 留出羽化带的余量，保证最后能完全擦干净
        float feather = Math.max(w, h) * 0.28f;

        // ① 旧界面铺满
        c.drawBitmap(oldUi, null,
                new android.graphics.RectF(0, 0, w, h), bmpPaint);

        if (radius <= 0f) return;

        // ② 软边遮罩：中间擦掉，边缘羽化
        //    0 ────────── 0.62 ──────── 1.0
        //    全擦          开始渐隐      完全保留
        maskPaint.setShader(new RadialGradient(cx, cy, radius + feather,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        c.drawCircle(cx, cy, radius + feather, maskPaint);
        maskPaint.setXfermode(null);
        maskPaint.setShader(null);

        // ③ 墨水前锋：一圈新主题的中心色，位置在羽化带的中间
        if (inkAlpha > 0.01f) {
            int a = (int) (Math.min(1f, inkAlpha) * 110);   // 浓度上限，别盖住内容
            int core = (a << 24) | (inkColor & 0x00FFFFFF);
            float r = radius + feather;
            inkPaint.setShader(new RadialGradient(cx, cy, r,
                    new int[]{0x00000000, core, 0x00000000},
                    new float[]{0.52f, 0.84f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, inkPaint);
            inkPaint.setShader(null);
        }
    }

    /** 到最远角的距离（加上羽化带） */
    public static float maxRadiusFor(float w, float h, float cx, float cy) {
        float dx = Math.max(cx, w - cx);
        float dy = Math.max(cy, h - cy);
        return (float) Math.hypot(dx, dy);
    }
}
