package com.qusic.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Qusic 应用图标（统一绘制）。
 *
 * <p>之前那个「音符」是个含糊的色块，辨识度很差。这里换成真正的
 * <b>双八分音符（beamed eighth notes）</b>：两个符头 + 两根符干 + 一条符杠，
 * 形状明确、缩到 24px 也能认出是音乐。
 *
 * <p>外观：超椭圆底 + primary→tertiary 渐变 + 左上柔光 + 底部环境暗部 +
 * 一圈内高光，让它看起来像一枚有厚度的实体图标，而不是一块纯色。
 */
public final class AppMark {

    private AppMark() {}

    /** 在给定矩形内画一枚完整的应用图标 */
    public static void draw(Canvas c, RectF r, Tokens t) {
        float w = r.width(), h = r.height();
        float rad = Math.min(w, h) * 0.26f;

        Path shape = new Path();
        shape.addRoundRect(r, rad, rad, Path.Direction.CW);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[]{t.primary, Hct.blendLab(t.primary, t.tertiary, 0.78f)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        p.setShader(null);

        // 左上柔光
        p.setShader(new RadialGradient(r.left + w * 0.30f, r.top + h * 0.20f, w * 1.05f,
                new int[]{Hct.withAlpha(Color.WHITE, 0.42f), Hct.withAlpha(Color.WHITE, 0f)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        p.setShader(null);

        // 右下暗部（体积感）
        p.setShader(new RadialGradient(r.right - w * 0.15f, r.bottom + h * 0.10f, h * 0.95f,
                new int[]{Hct.withAlpha(0xFF000000, 0.22f), Hct.withAlpha(0xFF000000, 0f)},
                null, Shader.TileMode.CLAMP));
        c.drawPath(shape, p);
        p.setShader(null);

        // 音符
        drawNotes(c, r, t.onPrimary);

        // 内高光边
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, Math.min(w, h) * 0.012f));
        p.setColor(Hct.withAlpha(Color.WHITE, 0.35f));
        c.drawPath(shape, p);
    }

    /** 双八分音符，画在矩形的中间偏下区域 */
    public static void drawNotes(Canvas c, RectF r, int color) {
        float w = r.width(), h = r.height();
        // 以矩形中心为基准，整体占 ~54% 宽、~58% 高
        float cx = r.centerX(), cy = r.centerY();
        float bw = w * 0.54f, bh = h * 0.58f;
        float left = cx - bw / 2f, top = cy - bh / 2f;

        float headRx = bw * 0.175f, headRy = bh * 0.125f;
        float stemW = Math.max(1.6f, bw * 0.085f);
        float beamH = bh * 0.13f;

        float h1x = left + headRx, h1y = top + bh - headRy;          // 左符头
        float h2x = left + bw - headRx, h2y = top + bh - headRy * 3.1f;  // 右符头（更高）
        float s1x = h1x + headRx - stemW / 2f;
        float s2x = h2x + headRx - stemW / 2f;
        float top1 = top + beamH * 0.9f;
        float top2 = top;

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);

        // 符干
        Path sp = new Path();
        sp.addRect(s1x, top1, s1x + stemW, h1y + headRy * 0.2f, Path.Direction.CW);
        sp.addRect(s2x, top2, s2x + stemW, h2y + headRy * 0.2f, Path.Direction.CW);
        c.drawPath(sp, p);

        // 符杠（连接两符干顶部，略带斜度）
        Path beam = new Path();
        beam.moveTo(s1x, top1);
        beam.lineTo(s2x + stemW, top2);
        beam.lineTo(s2x + stemW, top2 + beamH);
        beam.lineTo(s1x, top1 + beamH);
        beam.close();
        c.drawPath(beam, p);

        // 两个符头（椭圆，略微倾斜更像乐谱）
        c.save();
        c.rotate(-18f, h1x, h1y);
        RectF e1 = new RectF(h1x - headRx, h1y - headRy, h1x + headRx, h1y + headRy);
        c.drawOval(e1, p);
        c.restore();

        c.save();
        c.rotate(-18f, h2x, h2y);
        RectF e2 = new RectF(h2x - headRx, h2y - headRy, h2x + headRx, h2y + headRy);
        c.drawOval(e2, p);
        c.restore();
    }
}
