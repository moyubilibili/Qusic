package com.qusic.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
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
 * 迷你播放条（悬停于液态底栏之上）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>自己也是一块液态玻璃，与底栏形成「双层玻璃」的层次</li>
 *   <li>左侧专辑封面 + 中段标题跑马灯 + 右侧播放/暂停</li>
 *   <li>底部一条极细的进度线，颜色跟随主题</li>
 *   <li>无歌曲时自动收起（高度动画到 0）</li>
 * </ul>
 */
public class MiniPlayerBar extends View implements PlayerService.Listener {

    public interface OnOpenPlayer { void open(); }

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF bounds = new RectF();
    private final RectF bgRect = new RectF();
    private final RectF coverRect = new RectF();
    private final RectF playRect = new RectF();

    private Song song;
    private float progress;       // 0..1
    private float appear;         // 0..1 出现动画
    private float marquee;        // 标题跑马灯相位
    private boolean playing;
    private OnOpenPlayer cb;
    private int coverHash;
    private Bitmap cover;

    public MiniPlayerBar(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        startMarquee();
    }

    public void setOnOpenPlayer(OnOpenPlayer c) { this.cb = c; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (PlayerService.instance() != null) PlayerService.instance().addListener(this);
        syncFromService();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (PlayerService.instance() != null) PlayerService.instance().removeListener(this);
    }

    private void syncFromService() {
        PlayerService s = PlayerService.instance();
        if (s == null) return;
        song = s.current();
        playing = s.isPlaying();
        int d = s.duration();
        progress = d > 0 ? (float) s.position() / d : 0f;
        loadCover();
        animateAppear(song != null);
        invalidate();
    }

    private void loadCover() {
        if (song == null) { cover = null; return; }
        if (coverHash == (int) song.id && cover != null) return;
        coverHash = (int) song.id;
        cover = song.cover;
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = Library.coverSync(getContext(), song, 256);
                post(new Runnable() { @Override public void run() {
                    if (b != null && song != null && (int) song.id == coverHash) {
                        cover = b; invalidate();
                    }
                }});
            }
        });
    }

    private void animateAppear(boolean show) {
        float to = show ? 1f : 0f;
        if (Math.abs(appear - to) < 0.01f) return;
        ValueAnimator va = ValueAnimator.ofFloat(appear, to);
        va.setDuration(Theme.dur(460));
        va.setInterpolator(show ? Theme.SPRING : Theme.EMPHASIZED_ACCEL);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                appear = (float) a.getAnimatedValue();
                requestLayout();
                invalidate();
            }
        });
        va.start();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int full = (int) dp(66);
        int h = (int) (full * appear);
        setMeasuredDimension(w, Math.max(0, h));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bounds.set(dp(3), dp(3), w - dp(3), Math.max(dp(3) + 1, h - dp(3)));
        float r = dp(20);
        shape.reset();
        shape.addRoundRect(bounds, r, r, Path.Direction.CW);

        float pad = dp(9);
        float cs = bounds.height() - pad * 2;
        coverRect.set(bounds.left + pad, bounds.top + pad, bounds.left + pad + cs, bounds.bottom - pad);
        float ps = dp(38);
        playRect.set(bounds.right - pad - ps, bounds.centerY() - ps / 2,
                bounds.right - pad, bounds.centerY() + ps / 2);
    }

    @Override protected void onDraw(Canvas c) {
        if (appear < 0.02f || bounds.height() <= 1) return;
        Tokens t = Theme.t();

        int save = c.save();
        c.clipRect(0, 0, getWidth(), getHeight());

        // MD3 表面（surfaceContainerHigh + 阴影 + 描边），实色、无背景模糊
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(t.surfaceContainerHigh);
        p.setShadowLayer(dp(10), 0, dp(3), t.shadow(0.20f));
        c.drawPath(shape, p);
        p.clearShadowLayer();
        Draw.stroke(c, shape, Hct.withAlpha(t.outlineVariant, 0.5f), dp(1f), p);

        // 封面（圆角 + 阴影）
        drawCover(c, t);

        // 文本
        float textLeft = coverRect.right + dp(12);
        float textRight = playRect.left - dp(8);
        drawTexts(c, t, textLeft, textRight);

        // 播放/暂停按钮
        drawPlayButton(c, t);

        // 底部进度线
        drawProgressLine(c, t);

        c.restoreToCount(save);
    }

    private void drawCover(Canvas c, Tokens t) {
        float r = dp(11);
        Path cp = Draw.roundRect(coverRect, r);
        p.reset(); p.setStyle(Paint.Style.FILL);
        if (cover != null && !cover.isRecycled()) {
            int save = c.save();
            c.clipPath(cp);
            Draw.bitmapCrop(c, cover, coverRect, p);
            c.restoreToCount(save);
        } else {
            // 无封面：用主题色渐变占位 + 音符图标
            p.setShader(new LinearGradient(coverRect.left, coverRect.top, coverRect.right, coverRect.bottom,
                    new int[]{t.primaryContainer, Hct.blendLab(t.primaryContainer, t.tertiary, 0.55f)},
                    null, Shader.TileMode.CLAMP));
            c.drawPath(cp, p);
            p.setShader(null);
            RectF ib = new RectF(coverRect);
            ib.inset(coverRect.width() * 0.28f, coverRect.height() * 0.28f);
            Icons.draw(c, "note", ib, Hct.withAlpha(t.onPrimaryContainer, 0.85f), 1f, p);
        }
        // 封面描边（让它在玻璃上「浮」起来）
        Draw.stroke(c, cp, Hct.withAlpha(Color.WHITE, t.dark ? 0.10f : 0.35f), dp(0.8f), p);
    }

    private static RectF centerCrop(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(dst.width() / bw, dst.height() / bh);
        float w = dst.width() / scale, h = dst.height() / scale;
        float l = (bw - w) / 2f, tp = (bh - h) / 2f;
        return new RectF(l, tp, l + w, tp + h);
    }

    private void drawTexts(Canvas c, Tokens t, float left, float right) {
        float w = right - left;
        if (w <= 8) return;

        String title = song != null ? song.title : "还没有播放";
        String sub = song != null ? song.subtitle() : "去曲库挑一首吧";

        int save = c.save();
        c.clipRect(left, bounds.top, right, bounds.bottom);

        p.reset();
        p.setTypeface(Ui.tfMed());
        p.setTextSize(dp(14.5f));
        p.setColor(t.onSurface);
        float tw = p.measureText(title);
        float cx = left;
        float titleY = bounds.centerY() - dp(3);
        if (tw > w) {
            // 跑马灯：超出宽度时来回滚动
            float over = tw - w;
            float ph = (float) ((Math.sin(marquee) + 1) / 2.0);   // 0..1
            ph = ph * ph * (3 - 2 * ph);
            cx = left - over * ph;
        }
        c.drawText(title, cx, titleY, p);

        p.setTypeface(Ui.tf());
        p.setTextSize(dp(11.5f));
        p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.92f));
        String s2 = ellipsize(sub, p, w);
        c.drawText(s2, left, titleY + dp(16), p);

        c.restoreToCount(save);
    }

    private static String ellipsize(String s, Paint p, float max) {
        if (p.measureText(s) <= max) return s;
        String ell = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + ell) <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + ell;
    }

    private void drawPlayButton(Canvas c, Tokens t) {
        float r = playRect.width() / 2f;
        // 按钮底色
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(t.primary, 0.14f));
        c.drawCircle(playRect.centerX(), playRect.centerY(), r, p);

        RectF ib = new RectF(playRect);
        ib.inset(r * 0.62f, r * 0.62f);
        Icons.draw(c, playing ? "pause" : "play", ib, t.primary, 1f, p);
    }

    private void drawProgressLine(Canvas c, Tokens t) {
        float y = bounds.bottom - dp(2.5f);
        float l = bounds.left + dp(14), rr = bounds.right - dp(14);
        float w = rr - l;
        if (w <= 4) return;
        p.reset(); p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(2f));
        p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.18f));
        c.drawLine(l, y, rr, y, p);
        p.setColor(t.primary);
        c.drawLine(l, y, l + w * Math.max(0, Math.min(1, progress)), y, p);
    }

    private void startMarquee() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, (float) (Math.PI * 2));
        va.setDuration(9000);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setInterpolator(Theme.LINEAR);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                marquee = (float) a.getAnimatedValue();
                if (song != null) invalidate();
            }
        });
        va.start();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setAlpha(0.75f);
                return true;
            case MotionEvent.ACTION_UP:
                setAlpha(1f);
                if (playRect.contains(e.getX(), e.getY())) {
                    Ui.hapticLight(this);
                    PlayerService s = PlayerService.instance();
                    if (s != null) s.toggle();
                } else {
                    Ui.hapticLight(this);
                    if (cb != null) cb.open();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                setAlpha(1f);
                return true;
        }
        return super.onTouchEvent(e);
    }

    // ── PlayerService.Listener ──────────────────────────────────────────────
    @Override public void onSongChanged(Song s, int index) {
        song = s; loadCover(); animateAppear(s != null); invalidate();
    }
    @Override public void onPlayStateChanged(boolean pl) { playing = pl; invalidate(); }
    @Override public void onProgress(long pos, long dur) {
        progress = dur > 0 ? (float) pos / dur : 0f;
        invalidate();
    }
    @Override public void onQueueChanged() {}
    @Override public void onRepeatShuffleChanged(int repeat, int shuffle) {}
}
