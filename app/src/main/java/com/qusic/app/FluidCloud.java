package com.qusic.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 流体云（Fluid Cloud）—— App 内的实况胶囊。
 *
 * <p>参考 ColorOS 流体云的交互语言，做成一个会「呼吸、流动、形变」的胶囊：
 * <ul>
 *   <li><b>胶囊态</b>（width≈148dp）：小封面 + 声波律动条 + 歌名</li>
 *   <li><b>展开态</b>（width≈300dp）：封面 + 歌名/歌手 + 进度环 + 控制键</li>
 * </ul>
 *
 * <p>「液态」体现在三处：
 * <ol>
 *   <li>形变：胶囊↔卡片的形态用超椭圆路径连续插值，中间态是真正的圆角过渡</li>
 *   <li>流动：内部有一条沿胶囊流动的彩色高光带（跟封面主色）</li>
 *   <li>律动：播放时右侧三根声波柱按正弦律动，频率随「假想节奏」变化</li>
 * </ol>
 *
 * <p>它是一个可拖拽的悬浮 View，默认吸附在顶部状态栏下方。
 */
public class FluidCloud extends FrameLayout implements PlayerService.Listener {

    public interface OnOpenPlayer { void open(); }

    private static final float W_COLLAPSED = 146f;   // dp
    private static final float H_COLLAPSED = 42f;
    private static final float W_EXPANDED = 300f;
    private static final float H_EXPANDED = 84f;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final RectF bounds = new RectF();
    private final RectF bgRect = new RectF();
    private final RectF coverRect = new RectF();
    private final RectF btnPlay = new RectF(), btnNext = new RectF();

    private Bitmap cover;
    private Song song;
    private boolean playing;
    private float progress;

    private float expand = 0f;        // 0=胶囊 1=卡片
    private float flow = 0f;          // 流动相位
    private float wave = 0f;          // 声波相位
    private float dragY = 0f;         // 拖拽偏移
    private float alpha = 0f;         // 出现透明度
    private long lastFrame;

    private float downRawX, downRawY;
    private boolean dragging;
    private boolean moved;
    private ValueAnimator expandAnim;
    private OnOpenPlayer cb;
    private int coverHash = -1;
    private boolean docked = true;

    public FluidCloud(Context c) {
        super(c);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setOnOpenPlayer(OnOpenPlayer c) { this.cb = c; }

    /** 挂到 Activity 根布局 */
    public void attachTo(ViewGroup root) {
        root.addView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = Ui.statusBarHeight(getContext()) + Ui.px(getContext(), 8);
        setLayoutParams(lp);
        appear();
    }

    private void appear() {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(Theme.dur(620));
        va.setInterpolator(Theme.SPRING);
        va.setStartDelay(260);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                alpha = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
        startLoop();
    }

    public void hideCloud() {
        ValueAnimator va = ValueAnimator.ofFloat(alpha, 0f);
        va.setDuration(Theme.dur(360));
        va.setInterpolator(Theme.EMPHASIZED_ACCEL);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                alpha = (float) a.getAnimatedValue();
                setAlpha(Math.max(0f, alpha));
                invalidate();
            }
        });
        va.start();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) { s.addListener(this); syncFrom(s); }
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) s.removeListener(this);
    }

    private void syncFrom(PlayerService s) {
        song = s.current();
        playing = s.isPlaying();
        int d = s.duration();
        progress = d > 0 ? (float) s.position() / d : 0f;
        loadCover();
    }

    private void startLoop() {
        postOnAnimation(loop);
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!isShown()) { postOnAnimationDelayed(loop, 500); return; }
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;

            // 流动高光
            flow += dt * (playing ? 0.30f : 0.10f);
            if (flow > 1f) flow -= 1f;
            // 声波
            wave += dt * (playing ? 2.6f : 0.9f);
            if (wave > 1f) wave -= 1f;

            // 平滑跟随展开目标
            invalidate();
            postOnAnimationDelayed(loop, 16);
        }
    };

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        float e = ease(expand);
        int w = (int) (dp(W_COLLAPSED) + (dp(W_EXPANDED) - dp(W_COLLAPSED)) * e);
        int h = (int) (dp(H_COLLAPSED) + (dp(H_EXPANDED) - dp(H_COLLAPSED)) * e);
        // 给阴影留边距
        setMeasuredDimension(w + (int) dp(20), h + (int) dp(22));
    }

    private static float ease(float x) { return x * x * (3 - 2 * x); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        rebuildBounds();
    }

    private void rebuildBounds() {
        float m = dp(10), mTop = dp(10);
        bounds.set(m, mTop, getWidth() - m, getHeight() - dp(12));
        float r = bounds.height() / 2f;
        shape.reset();
        shape.addRoundRect(bounds, r, r, Path.Direction.CW);

        float e = ease(expand);
        float pad = dp(7);
        float cs = bounds.height() - pad * 2;
        coverRect.set(bounds.left + pad, bounds.top + pad, bounds.left + pad + cs, bounds.bottom - pad);

        float bs = dp(36) * e;
        if (bs > 1) {
            btnNext.set(bounds.right - dp(10) - bs, bounds.centerY() - bs / 2,
                    bounds.right - dp(10), bounds.centerY() + bs / 2);
            btnPlay.set(btnNext.left - bs - dp(10), btnNext.top, btnNext.left - dp(10), btnNext.bottom);
        }
    }

    @Override protected void onDraw(Canvas c) {
        if (alpha <= 0.01f || bounds.width() <= 1) return;
        Tokens t = Theme.t();

        int save = c.save();
        c.translate(0, dragY);
        if (alpha < 1f) {
            c.translate(0, (1 - alpha) * -dp(28));
            setAlpha(Math.min(1f, alpha));
        }

        // MD3 表面（实色 + 阴影 + 描边）
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(t.surfaceContainerHigh);
        p.setShadowLayer(dp(10), 0, dp(4), t.shadow(0.22f));
        c.drawPath(shape, p);
        p.clearShadowLayer();
        Draw.stroke(c, shape, Hct.withAlpha(t.outlineVariant, 0.5f), dp(1f), p);

        drawFlow(c, t);
        drawCover(c, t);
        drawContent(c, t);
        if (expand > 0.05f) drawButtons(c, t);

        Draw.topHighlight(c, bounds, bounds.height() / 2f, Color.WHITE, 0.36f, p);
        c.restoreToCount(save);
    }

    /** 沿胶囊流动的彩色高光带（液态感的来源） */
    private void drawFlow(Canvas c, Tokens t) {
        int accent = coverAccent != 0 ? coverAccent : t.primary;
        int save = c.save();
        c.clipPath(shape);

        // 一条从左上到右下扫过的光带
        float sweep = bounds.width() * 1.6f;
        float x = bounds.left - sweep * 0.4f + flow * sweep;
        p.reset();
        p.setShader(new LinearGradient(x - sweep * 0.3f, 0, x + sweep * 0.3f, 0,
                new int[]{Hct.withAlpha(accent, 0f), Hct.withAlpha(accent, 0.30f),
                        Hct.withAlpha(Color.WHITE, 0.16f), Hct.withAlpha(accent, 0f)},
                new float[]{0f, 0.40f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(bounds, p);
        p.setShader(null);

        // 底部一层淡淡的强调色环境光
        p.setShader(new LinearGradient(0, bounds.bottom - bounds.height() * 0.6f, 0, bounds.bottom,
                new int[]{Hct.withAlpha(accent, 0f), Hct.withAlpha(accent, 0.16f)},
                null, Shader.TileMode.CLAMP));
        c.drawRect(bounds, p);
        p.setShader(null);
        c.restoreToCount(save);
    }

    private int coverAccent = 0;

    private void drawCover(Canvas c, Tokens t) {
        float cs = coverRect.width();
        float r = cs * 0.28f;
        if (expand > 0.05f) r = cs * (0.28f - 0.12f * ease(expand));
        Path cp = Draw.roundRect(coverRect, r);

        if (cover != null && !cover.isRecycled()) {
            int save = c.save();
            c.clipPath(cp);
            p.reset(); p.setFilterBitmap(true);
            Draw.bitmapCrop(c, cover, coverRect, p);
            c.restoreToCount(save);
        } else {
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(coverRect.left, coverRect.top, coverRect.right, coverRect.bottom,
                    new int[]{t.primaryContainer, Hct.blendLab(t.primaryContainer, t.tertiary, 0.6f)},
                    null, Shader.TileMode.CLAMP));
            c.drawPath(cp, p);
            p.setShader(null);
            RectF ib = new RectF(coverRect);
            ib.inset(cs * 0.30f, cs * 0.30f);
            Icons.draw(c, "note", ib, Hct.withAlpha(t.onPrimaryContainer, 0.8f), 1f, p);
        }
        Draw.stroke(c, cp, Hct.withAlpha(Color.WHITE, 0.22f), dp(0.8f), p);
    }

    private static RectF centerCrop(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(dst.width() / bw, dst.height() / bh);
        float w = dst.width() / scale, h = dst.height() / scale;
        float l = (bw - w) / 2f, tp = (bh - h) / 2f;
        return new RectF(l, tp, l + w, tp + h);
    }

    private void drawContent(Canvas c, Tokens t) {
        float e = ease(expand);
        float left = coverRect.right + dp(9);

        // 右侧留给：胶囊态=声波；展开态=按钮
        float right = bounds.right - dp(10);
        if (e > 0.05f) right = btnPlay.left - dp(6);

        // 歌名
        p.reset(); p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.tfMed());
        p.setTextSize(dp(12.5f + 1.5f * e));
        p.setColor(t.onSurface);
        String title = song != null ? song.title : "Qusic";
        float maxW = Math.max(0, right - left);
        String shown = ellipsize(title, p, maxW);
        float ty = bounds.centerY() + (e > 0.05f ? -dp(3) : dp(4.5f));
        c.drawText(shown, left, ty, p);

        if (e > 0.05f) {
            // 展开态：歌手
            p.setTypeface(Ui.tf());
            p.setTextSize(dp(11));
            p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f * e));
            c.drawText(ellipsize(song != null ? song.subtitle() : "", p, maxW),
                    left, ty + dp(16), p);

            // 进度条（细线）
            float py = bounds.bottom - dp(11);
            float pw = Math.max(0, right - left);
            p.reset(); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.22f * e));
            c.drawLine(left, py, left + pw, py, p);
            p.setColor(Hct.withAlpha(coverAccent != 0 ? coverAccent : t.primary, e));
            c.drawLine(left, py, left + pw * clamp01(progress), py, p);
        } else {
            // 胶囊态：声波律动
            drawWave(c, t, right);
        }
    }

    /** 三根律动的声波柱 */
    private void drawWave(Canvas c, Tokens t, float right) {
        int accent = coverAccent != 0 ? coverAccent : t.primary;
        float cx = right - dp(13);
        float cy = bounds.centerY();
        float barW = dp(2.6f);
        float gap = dp(3.4f);
        for (int i = 0; i < 3; i++) {
            float ph = wave * (float) Math.PI * 2 + i * 1.1f;
            float amp = playing ? 1f : 0.28f;
            float hh = dp(4) + (float) Math.abs(Math.sin(ph)) * dp(11) * amp;
            float x = cx + (i - 1) * (barW + gap);
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setColor(Hct.withAlpha(accent, playing ? 0.95f : 0.45f));
            RectF bar = new RectF(x - barW / 2, cy - hh / 2, x + barW / 2, cy + hh / 2);
            c.drawRoundRect(bar, barW / 2, barW / 2, p);
        }
    }

    private void drawButtons(Canvas c, Tokens t) {
        float e = ease(expand);
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(t.primary, 0.16f * e));
        c.drawCircle(btnPlay.centerX(), btnPlay.centerY(), btnPlay.width() / 2f, p);

        RectF ib = new RectF(btnPlay);
        ib.inset(btnPlay.width() * 0.33f, btnPlay.width() * 0.33f);
        Icons.draw(c, playing ? "pause" : "play", ib, Hct.withAlpha(t.primary, e), 1f, p);

        RectF ib2 = new RectF(btnNext);
        ib2.inset(btnNext.width() * 0.33f, btnNext.width() * 0.33f);
        Icons.draw(c, "next", ib2, Hct.withAlpha(t.onSurfaceVariant, e), 1f, p);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static String ellipsize(String s, Paint p, float max) {
        if (s == null) return "";
        if (max <= 0) return "";
        if (p.measureText(s) <= max) return s;
        String ell = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + ell) <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + ell;
    }

    // ── 展开/收起 ───────────────────────────────────────────────────────────
    private void setExpand(boolean to) {
        if (expandAnim != null) expandAnim.cancel();
        final float from = expand;
        final float target = to ? 1f : 0f;
        expandAnim = ValueAnimator.ofFloat(0f, 1f);
        expandAnim.setDuration(Theme.dur(520));
        expandAnim.setInterpolator(Theme.SPRING);
        expandAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float f = (float) a.getAnimatedValue();
                expand = from + (target - from) * f;
                requestLayout();
                invalidate();
            }
        });
        expandAnim.start();
    }

    // ── 触摸：拖拽 + 点击 ───────────────────────────────────────────────────
    @Override public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = e.getRawX(); downRawY = e.getRawY();
                dragging = true; moved = false;
                setAlpha(0.9f);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true;
                if (moved) {
                    dragY = dy;
                    setTranslationX(dx);
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                setAlpha(1f);
                if (!moved) {
                    // 点击：展开态下按键 → 控制；否则切换展开/打开播放页
                    Ui.hapticLight(this);
                    PlayerService s = PlayerService.instance();
                    if (expand > 0.5f && s != null) {
                        if (btnPlay.contains(x, y)) { s.toggle(); }
                        else if (btnNext.contains(x, y)) { s.next(true); }
                        else { setExpand(false); }
                    } else {
                        setExpand(true);
                    }
                } else {
                    // 归位动画
                    final float ty = dragY, tx = getTranslationX();
                    ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(Theme.dur(420));
                    va.setInterpolator(Theme.SPRING);
                    va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                        @Override public void onAnimationUpdate(ValueAnimator a) {
                            float f = (float) a.getAnimatedValue();
                            dragY = ty * (1 - f);
                            setTranslationX(tx * (1 - f));
                            invalidate();
                        }
                    });
                    va.start();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void loadCover() {
        if (song == null) { cover = null; return; }
        if (coverHash == (int) song.id && cover != null) return;
        coverHash = (int) song.id;
        cover = song.cover;
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = Library.coverSync(getContext(), song, 320);
                if (b == null) return;
                final int avg = Library.averageColor(b);
                post(new Runnable() { @Override public void run() {
                    if (song == null || (int) song.id != coverHash) return;
                    cover = b;
                    coverAccent = Theme.accentFromCover(avg);
                    invalidate();
                }});
            }
        });
    }

    // ── PlayerService.Listener ──────────────────────────────────────────────
    @Override public void onSongChanged(Song s, int index) { song = s; loadCover(); invalidate(); }
    @Override public void onPlayStateChanged(boolean pl) { playing = pl; invalidate(); }
    @Override public void onProgress(long pos, long dur) {
        progress = dur > 0 ? (float) pos / dur : 0f;
    }
    @Override public void onQueueChanged() {}
    @Override public void onRepeatShuffleChanged(int repeat, int shuffle) {}
}
