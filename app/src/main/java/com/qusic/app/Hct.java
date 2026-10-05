package com.qusic.app;

import android.graphics.Color;
import android.graphics.RectF;

/**
 * MD3 色彩工具：HCT 近似实现（CAM16 简化 → L* 明度 + 色相 + 色度）。
 *
 * <p>Material Design 3 的色调板（Tonal Palette）以「色调 tone」为唯一维度：
 * tone 就是 CIELAB 的 L*（0=纯黑, 100=纯白）。同一个种子色推导出 0~100 的
 * 13 个色调，再按亮/暗主题取不同角色，就能得到一整套和谐配色。
 *
 * <p>本类不依赖任何第三方库，纯数学实现，保证在 AIDE 里零依赖打包。
 */
public final class Hct {

    private Hct() {}

    // ── sRGB <-> XYZ(D65) <-> Lab ────────────────────────────────────────────
    private static double srgbToLinear(double c) {
        c /= 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double linearToSrgb(double c) {
        double v = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1.0 / 2.4) - 0.055;
        return v * 255.0;
    }

    /** sRGB 0..255 → XYZ */
    private static double[] toXyz(int argb) {
        double r = srgbToLinear(Color.red(argb));
        double g = srgbToLinear(Color.green(argb));
        double b = srgbToLinear(Color.blue(argb));
        return new double[]{
                r * 0.4124564 + g * 0.3575761 + b * 0.1804375,
                r * 0.2126729 + g * 0.7151522 + b * 0.0721750,
                r * 0.0193339 + g * 0.1191920 + b * 0.9503041
        };
    }

    private static int fromXyz(double x, double y, double z) {
        double r = x * 3.2404542 + y * -1.5371385 + z * -0.4985314;
        double g = x * -0.9692660 + y * 1.8760108 + z * 0.0415560;
        double b = x * 0.0556434 + y * -0.2040259 + z * 1.0572252;
        return Color.rgb(clamp255(linearToSrgb(r)), clamp255(linearToSrgb(g)), clamp255(linearToSrgb(b)));
    }

    private static int clamp255(double v) {
        int i = (int) Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** ARGB → Lab（L 0..100, a/b 有符号） */
    public static double[] toLab(int argb) {
        double[] xyz = toXyz(argb);
        double xn = 0.95047, yn = 1.00000, zn = 1.08883;
        double fx = labF(xyz[0] / xn), fy = labF(xyz[1] / yn), fz = labF(xyz[2] / zn);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.cbrt(t) : (7.787 * t + 16.0 / 116.0);
    }

    private static double labFInv(double t) {
        double t3 = t * t * t;
        return t3 > 0.008856 ? t3 : (t - 16.0 / 116.0) / 7.787;
    }

    /** Lab → ARGB（超出 sRGB 色域会被硬件裁剪） */
    public static int fromLab(double L, double a, double b) {
        double fy = (L + 16) / 116, fx = fy + a / 500, fz = fy - b / 200;
        double x = 0.95047 * labFInv(fx);
        double y = 1.00000 * labFInv(fy);
        double z = 1.08883 * labFInv(fz);
        return fromXyz(x, y, z);
    }

    /** 感知明度 L*（0..100） */
    public static double lstar(int argb) {
        return toLab(argb)[0];
    }

    /**
     * 取某色调：保持种子色的色相/色度，把 L* 拉到目标 tone。
     * 色度过高会超出色域，这里按 tone 做适度衰减，避免高 tone 段发灰或断层。
     */
    public static int toneOf(int seed, double tone) {
        return toneOf(seed, tone, 1.0);
    }

    /**
     * @param chromaScale 色度缩放（1.0=原样；中性色用 0.12 左右）
     */
    public static int toneOf(int seed, double tone, double chromaScale) {
        double[] lab = toLab(seed);
        double c = Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]) * chromaScale;
        double h = Math.atan2(lab[2], lab[1]);
        // 高/低明度端压缩色度，贴合 MD3 的实际观感
        double f = 1.0;
        if (tone > 85) f = 1.0 - (tone - 85) / 15.0 * 0.72;
        else if (tone < 22) f = 1.0 - (22 - tone) / 22.0 * 0.30;
        c *= f;
        // 按色相限制最大色度（黄/青更亮，紫/蓝更暗），避免溢出后色相漂移
        double maxC = maxChromaForHue(h);
        if (c > maxC) c = maxC;
        return fromLab(tone, Math.cos(h) * c, Math.sin(h) * c);
    }

    private static double maxChromaForHue(double hRad) {
        double deg = Math.toDegrees(hRad);
        if (deg < 0) deg += 360;
        // 经验曲线：黄绿 (~100°) 容许高色度，蓝紫 (~280°) 最低
        double t = Math.cos(Math.toRadians(deg - 100));
        return 70 + 45 * t;
    }

    /** 取色相角（度，0..360） */
    public static double hueOf(int argb) {
        double[] lab = toLab(argb);
        double d = Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return d < 0 ? d + 360 : d;
    }

    /** 取色度 */
    public static double chromaOf(int argb) {
        double[] lab = toLab(argb);
        return Math.sqrt(lab[1] * lab[1] + lab[2] * lab[2]);
    }

    // ── 混色 ─────────────────────────────────────────────────────────────────
    /** 在 Lab 空间线性混合，比 sRGB 插值更干净（不会出现脏灰过渡） */
    public static int blendLab(int c1, int c2, double t) {
        double[] a = toLab(c1), b = toLab(c2);
        return fromLab(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t);
    }

    /** 带透明度叠加（alpha 混合到背景上），用于「surface tint」效果 */
    public static int composite(int fg, double alpha, int bg) {
        int a = (int) Math.round(alpha * 255);
        return Color.argb(255,
                (int) (Color.red(fg) * alpha + Color.red(bg) * (1 - alpha)),
                (int) (Color.green(fg) * alpha + Color.green(bg) * (1 - alpha)),
                (int) (Color.blue(fg) * alpha + Color.blue(bg) * (1 - alpha)));
    }

    public static int withAlpha(int color, double alpha) {
        return (Color.argb((int) Math.round(alpha * 255), Color.red(color), Color.green(color), Color.blue(color)));
    }

    /** 按感知明度在深/浅两个前景色之间选对比度更高的那个 */
    public static int onColor(int bg, int light, int dark) {
        return lstar(bg) > 55 ? dark : light;
    }

    /** WCAG 相对亮度 */
    public static double luminance(int argb) {
        double r = srgbToLinear(Color.red(argb));
        double g = srgbToLinear(Color.green(argb));
        double b = srgbToLinear(Color.blue(argb));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** WCAG 对比度（1..21） */
    public static double contrast(int c1, int c2) {
        double l1 = luminance(c1), l2 = luminance(c2);
        if (l1 < l2) { double t = l1; l1 = l2; l2 = t; }
        return (l1 + 0.05) / (l2 + 0.05);
    }

    /** 把前景色调整到至少满足 minRatio 对比度（向黑/白两个方向试） */
    public static int ensureContrast(int fg, int bg, double minRatio) {
        if (contrast(fg, bg) >= minRatio) return fg;
        double L = lstar(fg);
        int black = toneOf(fg, Math.max(0, L - 30)), white = toneOf(fg, Math.min(100, L + 30));
        boolean bgDark = lstar(bg) < 50;
        int dir = bgDark ? white : black;
        for (int i = 0; i < 12; i++) {
            if (contrast(dir, bg) >= minRatio) return dir;
            L += bgDark ? 5 : -5;
            L = Math.max(0, Math.min(100, L));
            dir = toneOf(fg, L);
            if (L <= 0 || L >= 100) break;
        }
        return bgDark ? Color.WHITE : Color.BLACK;
    }

    /** 圆形/胶囊插值辅助 */
    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    public static RectF lerp(RectF a, RectF b, float t, RectF out) {
        out.set(lerp(a.left, b.left, t), lerp(a.top, b.top, t),
                lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t));
        return out;
    }
}
