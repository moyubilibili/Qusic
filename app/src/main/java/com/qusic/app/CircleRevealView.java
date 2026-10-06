package com.qusic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 圆形扩散转场（支持两个方向）。
 *
 * <h3>方向的区别</h3>
 * <ul>
 *   <li><b>从中心向外</b>：新界面像涟漪一样从点击处推开，旧界面被从中间「吃」掉</li>
 *   <li><b>从外向内</b>：旧界面像合拢一样缩向点击处，新界面从四周涌进来</li>
 * </ul>
 *
 * <h3>怎么用同一个 View 做两个方向</h3>
 * 这个 View 画的是**旧界面**。
 * <ul>
 *   <li>「从外向内」→ 旧界面只保留在**圆内**，半径从大到小 → 旧界面缩成一个点</li>
 *   <li>「从中心向外」→ 旧界面只保留在**圆外**，半径从小到大 → 旧界面被从中间掏空</li>
 * </ul>
 * 「只保留圆外」用 {@link Path.FillType#INVERSE_EVEN_ODD} 实现 ——
 * 一个圆配反向填充，等于「除了这个圆以外的全部区域」。
 */
public class CircleRevealView extends View {

    private final Bitmap oldUi;
    private final float cx, cy;
    /** true = 新界面从中心向外出现 */
    private final boolean fromCenterOut;

    private float radius = 0f;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF dst = new RectF();

    public CircleRevealView(Context c, Bitmap oldUi, float cx, float cy, boolean fromCenterOut) {
        super(c);
        this.oldUi = oldUi;
        this.cx = cx;
        this.cy = cy;
        this.fromCenterOut = fromCenterOut;
    }

    public void setRadius(float r) {
        this.radius = r;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        if (oldUi == null || oldUi.isRecycled()) return;
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        dst.set(0, 0, w, h);

        clip.reset();
        if (fromCenterOut) {
            // 只保留「圆外」—— 圆越大，旧界面剩得越少，
            // 于是新界面是从中心向外露出来的
            clip.setFillType(Path.FillType.INVERSE_EVEN_ODD);
        }
        clip.addCircle(cx, cy, Math.max(0f, radius), Path.Direction.CW);

        c.save();
        c.clipPath(clip);
        c.drawBitmap(oldUi, null, dst, bmpPaint);
        c.restore();
    }

    /** 到最远角的距离 */
    public static float maxRadiusFor(float w, float h, float cx, float cy) {
        float dx = Math.max(cx, w - cx);
        float dy = Math.max(cy, h - cy);
        return (float) Math.hypot(dx, dy);
    }
}
