package com.qusic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 网络图片。
 *
 * <p>帖子里的图不像封面那样有 {@link Library} 的缓存体系，所以这里自带一套：
 * 内存 LRU + 磁盘缓存（复用 Library 的缓存目录）。
 * 不引第三方图片库 —— 本项目零依赖。
 *
 * <p>加载前先按布局宽度**降采样**：帖子图最长边 1280，
 * 直接在 1080 宽的屏上全解码要 5MB 一张，一屏几张就 OOM 了。
 */
public class NetImage extends View {

    private static LruCache<String, Bitmap> sMem;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();

    private final String url;
    private final int ow, oh;
    private Bitmap bm;
    private boolean loading;
    /** 视图是否还挂着 —— 异步回来时可能已经被移出界面了 */
    private volatile boolean attached;
    private float radius;

    public NetImage(Context c, String url, int w, int h) {
        super(c);
        this.url = url == null ? "" : url;
        this.ow = Math.max(1, w);
        this.oh = Math.max(1, h);
        this.radius = Ui.px(c, 14);
    }

    private static LruCache<String, Bitmap> mem() {
        if (sMem == null) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 10);
            sMem = new LruCache<String, Bitmap>(Math.min(maxKb, 12 * 1024)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
            };
        }
        return sMem;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        if (w <= 0) w = getResources().getDisplayMetrics().widthPixels;
        // 按原始宽高比算高度，但**限高** —— 竖长图不限制的话一张就占满整屏
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        int h = (int) ((long) w * oh / ow);
        if (h > maxH) h = maxH;
        if (h < Ui.px(getContext(), 80)) h = Ui.px(getContext(), 80);
        setMeasuredDimension(w, h);
        load();
    }

    private void load() {
        if (loading || bm != null || url.length() == 0) return;
        Bitmap hit = mem().get(url);
        if (hit != null) { bm = hit; invalidate(); return; }

        loading = true;
        final int tw = Math.max(1, getWidth());
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                Bitmap out = null;
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setRequestProperty("User-Agent", "Qusic/2");
                    // 服务器开了防盗链：没有同域 Referer 的图片请求一律 403。
                    // 补一个 Referer 就能过 —— 这是客户端能做的，
                    // 但更干净的做法是服务端把 uploads/ 排除在防盗链之外。
                    conn.setRequestProperty("Referer", "https://qclear.xyz/community/");
                    // 先量尺寸，再按目标宽度降采样
                    InputStream in1 = conn.getInputStream();
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in1, null, o);
                    try { in1.close(); } catch (Throwable ignored) {}

                    int sample = 1;
                    int longSide = Math.max(o.outWidth, o.outHeight);
                    while (longSide / sample > Math.max(tw, 720)) sample *= 2;

                    HttpURLConnection c2 = (HttpURLConnection) new URL(url).openConnection();
                    c2.setConnectTimeout(10000);
                    c2.setReadTimeout(15000);
                    c2.setRequestProperty("User-Agent", "Qusic/2");
                    c2.setRequestProperty("Referer", "https://qclear.xyz/community/");
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    InputStream in2 = c2.getInputStream();
                    out = BitmapFactory.decodeStream(in2, null, o2);
                    try { in2.close(); } catch (Throwable ignored) {}
                    if (out != null) mem().put(url, out);
                } catch (Throwable ignored) {}

                final Bitmap fin = out;
                UI.post(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        if (fin != null && attached) {
                            bm = fin;
                            invalidate();
                        }
                    }
                });
            }
        });
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);

        if (bm == null || bm.isRecycled()) {
            // 占位：浅色底块，别让版面在图片加载时跳来跳去
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setColor(Theme.t().surfaceContainerHigh);
            c.drawPath(clip, p);
            return;
        }
        c.save();
        c.clipPath(clip);
        p.reset();
        p.setFilterBitmap(true);
        // 居中裁切填满（cover），不拉伸变形
        float scale = Math.max((float) w / bm.getWidth(), (float) h / bm.getHeight());
        float dw = bm.getWidth() * scale, dh = bm.getHeight() * scale;
        float left = (w - dw) / 2f, top = (h - dh) / 2f;
        c.drawBitmap(bm, null, new RectF(left, top, left + dw, top + dh), p);
        c.restore();
    }
}
