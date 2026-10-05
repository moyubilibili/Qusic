package com.qusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * 图标库。
 *
 * <p>不再手绘路径 —— 全部改用 <b>Google Material Icons</b> 的官方矢量图形
 * （Apache-2.0），以 {@code res/drawable/ic_*.xml} 的形式随包发布：
 * 形状准确、在任意尺寸下都清晰，也不会像手绘路径那样歪歪扭扭。
 *
 * <p>用法与之前完全一致：{@link #draw} 按名字取图并按目标矩形缩放绘制，
 * 颜色通过 {@code setTint} 实时染色，因此主题换色时图标会跟着变。
 */
public final class Icons {

    private static Context sCtx;
    private static final Map<String, Drawable> CACHE = new HashMap<>();
    /** 复用同一份 Drawable 时避免并发/重入问题 */
    private static final Object LOCK = new Object();

    private Icons() {}

    /** 在 Application 启动时调用一次即可 */
    public static void init(Context c) {
        if (sCtx == null && c != null) sCtx = c.getApplicationContext();
    }

    private static Drawable get(String name) {
        if (sCtx == null) return null;
        synchronized (LOCK) {
            Drawable d = CACHE.get(name);
            if (d != null) return d;
        }
        try {
            int id = sCtx.getResources().getIdentifier(
                    "ic_" + name, "drawable", sCtx.getPackageName());
            if (id == 0) return null;
            Drawable d = sCtx.getDrawable(id);
            if (d == null) return null;
            d = d.mutate();
            synchronized (LOCK) { CACHE.put(name, d); }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 绘制图标。
     *
     * @param box   目标矩形（图标按比例缩放填满，保持正方形最好）
     * @param color 染色
     */
    public static void draw(Canvas c, String name, RectF box, int color, float scale, Paint p) {
        Drawable d = get(name);
        if (d == null || box.width() <= 1 || box.height() <= 1) return;
        d.setTint(color);
        d.setBounds(Math.round(box.left), Math.round(box.top),
                Math.round(box.right), Math.round(box.bottom));
        d.draw(c);
    }

    /** 带透明度的版本（用于动画中的淡入淡出） */
    public static void draw(Canvas c, String name, RectF box, int color, float alpha, float scale) {
        Drawable d = get(name);
        if (d == null || box.width() <= 1 || box.height() <= 1) return;
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        d.setTint(color);
        d.setAlpha(a);
        d.setBounds(Math.round(box.left), Math.round(box.top),
                Math.round(box.right), Math.round(box.bottom));
        d.draw(c);
        d.setAlpha(255);
    }

    // ── 底栏用的 IconDraw（保持既有接口不变） ───────────────────────────────
    public interface NavIcon { void draw(Canvas c, RectF box, int color, float sel); }

    public static final NavIcon HOME = new NavIcon() {
        @Override public void draw(Canvas c, RectF box, int color, float sel) {
            Icons.draw(c, "home", box, color, 1f, null);
        }
    };
    public static final NavIcon LIBRARY = new NavIcon() {
        @Override public void draw(Canvas c, RectF box, int color, float sel) {
            Icons.draw(c, "library", box, color, 1f, null);
        }
    };
    public static final NavIcon SEARCH = new NavIcon() {
        @Override public void draw(Canvas c, RectF box, int color, float sel) {
            Icons.draw(c, "search", box, color, 1f, null);
        }
    };
    public static final NavIcon ABOUT = new NavIcon() {
        @Override public void draw(Canvas c, RectF box, int color, float sel) {
            Icons.draw(c, "info", box, color, 1f, null);
        }
    };
}
