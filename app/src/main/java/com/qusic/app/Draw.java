package com.qusic.app;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 绘图工具：液态玻璃、超椭圆、柔和阴影、渐变工具。
 *
 * <p>「液态玻璃」由四层叠加而成，缺一层就会显得像塑料：
 * <ol>
 *   <li>背景模糊（调用方提供已模糊的底图）</li>
 *   <li>半透明填充 + 垂直亮度渐变（上亮下暗，模拟折射）</li>
 *   <li>1px 内描边高光（左上偏亮，右下偏暗）</li>
 *   <li>顶部一道更亮的弧形高光 + 外投影</li>
 * </ol>
 */
public final class Draw {

    private Draw() {}

    // ── 超椭圆（squircle）：iOS 图标的连续圆角 ─────────────────────────────
    /**
     * 生成超椭圆路径。n=2 是普通椭圆，n 越大越接近矩形；
     * iOS 风格取 n≈4~5，圆角过渡是连续的，比普通 roundRect 更「润」。
     */
    public static Path squircle(RectF r, float corner, int n) {
        Path p = new Path();
        float cx = r.centerX(), cy = r.centerY();
        float hw = r.width() / 2f, hh = r.height() / 2f;
        // 用参数方程采样：|x/a|^n + |y/b|^n = 1
        int steps = 96;
        // 把 corner 映射为「方度」：圆角越小，n 越大（越接近矩形）
        float sq = Math.max(2f, Math.min(8f, 2f + (1f - corner / Math.min(hw, hh)) * 6f));
        if (n > 0) sq = n;
        for (int i = 0; i <= steps; i++) {
            double a = i * 2 * Math.PI / steps;
            double ct = Math.cos(a), st = Math.sin(a);
            float x = (float) (cx + hw * Math.signum(ct) * Math.pow(Math.abs(ct), 2.0 / sq));
            float y = (float) (cy + hh * Math.signum(st) * Math.pow(Math.abs(st), 2.0 / sq));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    /** 快速圆角矩形路径 */
    public static Path roundRect(RectF r, float rad) {
        Path p = new Path();
        p.addRoundRect(r, rad, rad, Path.Direction.CW);
        return p;
    }

    // ── 柔和阴影 ────────────────────────────────────────────────────────────
    /**
     * 用 BlurMaskFilter 画柔和外阴影。
     * 部分机型（尤其关闭硬件加速时）BlurMaskFilter 不可用，会自动退化为实心描边。
     */
    public static void softShadow(Canvas c, Path shape, int color, float blur, float dx, float dy, Paint work) {
        work.reset();
        work.setStyle(Paint.Style.FILL);
        work.setColor(color);
        work.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, blur), BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(dx, dy);
        c.drawPath(shape, work);
        c.restore();
        work.setMaskFilter(null);
    }

    /** 多层阴影：小范围紧贴 + 大范围弥散，观感比单层真实得多 */
    public static void layeredShadow(Canvas c, Path shape, int color, float radius, float dy, Paint work) {
        softShadow(c, shape, Hct.withAlpha(color, 0.42), radius * 0.5f, 0, dy * 0.45f, work);
        softShadow(c, shape, Hct.withAlpha(color, 0.22), radius * 1.5f, 0, dy, work);
        softShadow(c, shape, Hct.withAlpha(color, 0.10), radius * 3.2f, 0, dy * 1.8f, work);
    }

    // ── 液态玻璃 ────────────────────────────────────────────────────────────
    /**
     * 绘制一块液态玻璃。
     *
     * @param c        画布
     * @param shape    形状路径
     * @param bounds   形状包围盒（用于渐变方向）
     * @param blurBg   已模糊的背景位图（可为 null，退化为纯半透明）
     * @param bgRect   模糊位图对应的目标区域
     * @param tint     玻璃染色（通常是 surface 或 primary）
     * @param tintA    染色透明度
     * @param hi       高光色（通常白色）
     * @param edgeA    描边透明度
     * @param specA    镜面高光强度
     */
    public static void liquidGlass(Canvas c, Path shape, RectF bounds,
                                   Bitmap blurBg, RectF bgRect,
                                   int tint, float tintA, int hi, float edgeA, float specA,
                                   Paint p) {
        int save = c.save();
        c.clipPath(shape);

        // 1) 模糊底图
        if (blurBg != null && !blurBg.isRecycled() && bgRect != null && bgRect.width() > 1) {
            p.reset(); p.setFilterBitmap(true); p.setAlpha(255);
            c.drawBitmap(blurBg, null, bgRect, p);
        }

        // 2) 半透明染色 + 垂直亮度渐变（液态感的核心：上亮下暗）
        p.reset(); p.setStyle(Paint.Style.FILL);
        int top = Hct.composite(tint, tintA * 1.24, 0xFF000000);
        int bot = Hct.composite(tint, tintA * 0.72, 0xFF000000);
        p.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{Hct.withAlpha(top, Math.min(1f, tintA * 1.5f)),
                        Hct.withAlpha(tint, tintA * 0.86f),
                        Hct.withAlpha(bot, Math.min(1f, tintA * 1.1f))},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        p.setShader(null);

        // 3) 斜向镜面高光（左上到中部的白色柔光）
        if (specA > 0.001f) {
            p.setShader(new LinearGradient(bounds.left, bounds.top,
                    bounds.left + bounds.width() * 0.85f, bounds.top + bounds.height() * 0.9f,
                    new int[]{Hct.withAlpha(hi, specA), Hct.withAlpha(hi, specA * 0.24f),
                            Hct.withAlpha(hi, 0f)},
                    new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(shape, p);
            p.setShader(null);
        }
        c.restoreToCount(save);

        // 4) 内描边：顶部一道亮边（模拟玻璃厚度折射）
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, bounds.height() * 0.012f));
        p.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{Hct.withAlpha(hi, edgeA), Hct.withAlpha(hi, edgeA * 0.30f),
                        Hct.withAlpha(hi, edgeA * 0.55f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        Path inset = new Path(shape);
        c.drawPath(inset, p);
        p.setShader(null);
    }

    /** 顶部弧形高光条：让玻璃看起来有厚度 */
    public static void topHighlight(Canvas c, RectF b, float radius, int hi, float alpha, Paint p) {
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.2f, b.height() * 0.02f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setShader(new LinearGradient(b.left, 0, b.right, 0,
                new int[]{Hct.withAlpha(hi, 0f), Hct.withAlpha(hi, alpha),
                        Hct.withAlpha(hi, alpha * 0.85f), Hct.withAlpha(hi, 0f)},
                new float[]{0f, 0.22f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        float inset = p.getStrokeWidth() / 2f + 1f;
        RectF r = new RectF(b.left + inset + radius * 0.18f, b.top + inset,
                b.right - inset - radius * 0.18f, b.top + inset + radius * 1.5f);
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
    }

    /** 细描边 */
    public static void stroke(Canvas c, Path shape, int color, float w, Paint p) {
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setColor(color);
        c.drawPath(shape, p);
    }

    /** 环状进度条（带圆头） */
    public static void arc(Canvas c, RectF oval, float start, float sweep, int color, float w, Paint p) {
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(color);
        c.drawArc(oval, start, sweep, false, p);
    }

    /** 把 Canvas 缩放到「设计稿坐标系」，让布局在不同屏幕上等比一致 */
    public static float fitScale(float availW, float designW) {
        return availW / designW;
    }

    // ── 位图绘制辅助 ────────────────────────────────────────────────────────
    private static final android.graphics.Rect SRC_I = new android.graphics.Rect();
    private static final android.graphics.Rect DST_I = new android.graphics.Rect();

    /** 计算 center-crop 的源矩形（把封面裁满目标区域，不变形） */
    public static android.graphics.Rect centerCropSrc(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(dst.width() / bw, dst.height() / bh);
        float w = dst.width() / scale, h = dst.height() / scale;
        float l = (bw - w) / 2f, t = (bh - h) / 2f;
        SRC_I.set(Math.round(l), Math.round(t), Math.round(l + w), Math.round(t + h));
        return SRC_I;
    }

    /**
     * 把位图 center-crop 绘制到目标矩形。
     * 注意：Canvas.drawBitmap 只接受 {@link android.graphics.Rect}，不接受 RectF。
     */
    public static void bitmapCrop(Canvas c, Bitmap b, RectF dst, Paint p) {
        if (b == null || b.isRecycled() || dst.width() <= 0 || dst.height() <= 0) return;
        DST_I.set(Math.round(dst.left), Math.round(dst.top),
                Math.round(dst.right), Math.round(dst.bottom));
        p.setFilterBitmap(true);
        c.drawBitmap(b, centerCropSrc(b, dst), DST_I, p);
    }

    /** 把位图「铺满并居中」绘制到整块画布（全屏模糊底图用） */
    public static void bitmapFill(Canvas c, Bitmap b, int w, int h, Paint p) {
        if (b == null || b.isRecycled() || w <= 0 || h <= 0) return;
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        float dw = b.getWidth() * scale, dh = b.getHeight() * scale;
        DST_I.set(Math.round((w - dw) / 2f), Math.round((h - dh) / 2f),
                Math.round((w + dw) / 2f), Math.round((h + dh) / 2f));
        SRC_I.set(0, 0, b.getWidth(), b.getHeight());
        p.setFilterBitmap(true);
        c.drawBitmap(b, SRC_I, DST_I, p);
    }
}
