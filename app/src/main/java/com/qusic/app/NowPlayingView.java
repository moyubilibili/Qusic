package com.qusic.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

/**
 * Apple Music 风格播放页（全自绘）。
 *
 * <p>自下而上的视觉层：
 * <ol>
 *   <li>封面模糊放大的满屏底图 + 暗化/增饱和（Apple Music 的招牌背景）</li>
 *   <li>随音乐「呼吸」的彩色光晕（跟随封面主色，节拍时脉动）</li>
 *   <li>大幅专辑封面：唱针落下后缓慢旋转的唱片质感 + 悬浮阴影</li>
 *   <li>标题/歌手 + 可拖拽进度条 + 控制按钮</li>
 *   <li>可上滑展开的歌词面板</li>
 * </ol>
 *
 * <p>手势：
 * <ul>
 *   <li>封面左右滑动 → 切歌（跟手位移 + 松手回弹）</li>
 *   <li>上滑 → 展开歌词；下滑 → 收起</li>
 *   <li>进度条拖拽带触感刻度</li>
 * </ul>
 */
public class NowPlayingView extends View implements PlayerService.Listener {

    public interface OnBack { void back(); }

    // ── 布局（在 onSizeChanged 里按屏幕算） ─────────────────────────────────
    private final RectF coverRect = new RectF();
    private final RectF seekRect = new RectF();
    private final RectF btnPlay = new RectF(), btnPrev = new RectF(), btnNext = new RectF();
    private final RectF btnShuffle = new RectF(), btnRepeat = new RectF(),
            btnQueue = new RectF(), btnHeart = new RectF();
    private final RectF lyricsPanel = new RectF();
    private final RectF backRect = new RectF();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    private Song song;
    private Bitmap cover;
    private Bitmap blurCover;      // 放大的模糊底图
    private int coverHash = -1;
    private int coverAccent = 0;

    private boolean playing;
    private float progress;        // 0..1
    private long posMs, durMs;

    // 手势状态
    /** 播放页样式：0=大封面 1=歌词页 2=极简 */
    public static final int STYLE_COVER = 0, STYLE_LYRICS = 1, STYLE_MINIMAL = 2;
    private int style = Theme.playerStyle();
    private final RectF styleBtn = new RectF();

    /** 歌词排版缓存：折行结果只算一次，绘制时零分配（否则每帧 GC 会明显卡顿） */
    private long lyricCacheId = -1;
    private float lyricCacheWidth = -1;
    private String[] lyricLines;
    private String[][] lyricWrapped;
    private long[] lyricTimes;
    private int lyricCur = -1;

    /** 歌词滚动状态 */
    private float lyricScroll, lyricScrollTarget;
    private float lyricsVel;              // 弹簧速度（让歌词面板有真实的加减速）
    private final RectF lyricArea = new RectF();
    private int lastCurLine = -1;
    private float[] lyricLineY;      // 每行当前 y（供点击定位）
    private long[] lyricLineTime;    // 每行时间
    private int lyricLineCount;

    /** 排版基准（layout() 里算好，绘制时直接用，避免各处硬编码导致重叠） */
    private float titleY, subtitleY, seekCy;

    private float dragX, dragY;
    private boolean dragging;
    private boolean seeking;
    private float seekTarget = -1;
    private float lyricsPull;      // 0..1 歌词面板展开程度
    private float lyricsTarget;
    private VelocityTracker vt;
    private int touchMode = 0;     // 1=seek, 2=cover swipe, 3=lyrics

    // 动画
    private float breath = 0f;     // 0..1 呼吸相位
    private float coverScale = 1f;
    private float entrance = 0f;
    private float lyricsScroll = 0f;
    private long lastFrame;
    private OnBack cb;
    private boolean active = true;

    public NowPlayingView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        startLoops();
    }

    public void setOnBack(OnBack b) { this.cb = b; }

    public void setActive(boolean a) { active = a; if (a) startLoops(); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) { s.addListener(this); onSongChanged(s.current(), s.currentIndex()); }
        startEntrance();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) s.removeListener(this);
    }

    private void startEntrance() {
        entrance = 1f;
        ValueAnimator va = ValueAnimator.ofFloat(1f, 0f);
        va.setDuration(Theme.dur(1));
        va.start();
    }

    public void playOpenAnimation() {
        entrance = 0f;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(Theme.dur(560));
        va.setInterpolator(Theme.EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                entrance = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
    }

    // ── 持续动画：呼吸光晕 + 歌词滚动 ───────────────────────────────────────
    private void startLoops() {
        if (!active) return;
        postOnAnimation(frameLoop);
    }

    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!active || !isShown()) { postOnAnimationDelayed(frameLoop, 400); return; }
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;

            boolean need = false;
            // 呼吸：播放时快、暂停时慢
            float speed = playing ? 0.42f : 0.12f;
            float prev = breath;
            breath += dt * speed;
            if (breath > 1f) breath -= 1f;
            if (Math.abs(breath - prev) > 0.0005f) need = true;

            // 歌词面板：弹簧阻尼（比指数平滑更有质感，收尾会轻微回弹）
            if (Math.abs(lyricsPull - lyricsTarget) > 0.0008f || Math.abs(lyricsVel) > 0.0008f) {
                float k = 165f, damp = 22f;   // 更柔：慢一点、几乎不回弹
                float acc = (lyricsTarget - lyricsPull) * k - lyricsVel * damp;
                lyricsVel += acc * dt;
                lyricsPull += lyricsVel * dt;
                if (lyricsPull < 0f) { lyricsPull = 0f; lyricsVel = 0f; }
                if (lyricsPull > 1f) { lyricsPull = 1f; lyricsVel = 0f; }
                need = true;
            } else {
                lyricsPull = lyricsTarget;
                lyricsVel = 0f;
            }
            if (playing && lyricsPull > 0.5f) {
                lyricsScroll += dt * 0.06f;
                need = true;
            }

            if (need) invalidate();
            postOnAnimationDelayed(frameLoop, 16);
        }
    };

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layout(w, h);
    }

    private void layout(int w, int h) {
        float pad = dp(24);
        float statusH = Ui.statusBarHeight(getContext());
        float navInset = Ui.navBarHeight(getContext());

        float bs = dp(42);
        backRect.set(dp(16), statusH + dp(10), dp(16) + bs, statusH + dp(10) + bs);
        styleBtn.set(w - dp(16) - bs, statusH + dp(10), w - dp(16), statusH + dp(10) + bs);
        lyricsPanel.set(0, 0, w, h);

        // 封面：按样式给不同大小（歌词/极简页留出歌词空间）
        float cs = Math.min(w - pad * 2, h * (style == STYLE_COVER ? 0.42f
                : style == STYLE_LYRICS ? 0.22f : 0.15f));
        float cx = w / 2f;
        float coverTop = statusH + dp(56);
        coverRect.set(cx - cs / 2, coverTop, cx + cs / 2, coverTop + cs);

        // 排版顺序（自上而下）：封面 → 标题 → 副标题 → 进度条 → 主控制 → 次要按钮
        // 其中控制区从底部往上锚定，保证任何屏幕尺寸下都不会互相压住。
        titleY = coverRect.bottom + dp(32);
        subtitleY = titleY + dp(21);

        float secCy = h - navInset - dp(46);
        float secSize = dp(46);
        float gap = (w - pad * 2 - secSize * 4) / 3f;
        btnShuffle.set(pad, secCy - secSize / 2, pad + secSize, secCy + secSize / 2);
        btnHeart.set(btnShuffle.right + gap, secCy - secSize / 2,
                btnShuffle.right + gap + secSize, secCy + secSize / 2);
        btnQueue.set(btnHeart.right + gap, secCy - secSize / 2,
                btnHeart.right + gap + secSize, secCy + secSize / 2);
        btnRepeat.set(btnQueue.right + gap, secCy - secSize / 2,
                btnQueue.right + gap + secSize, secCy + secSize / 2);

        float controlsCy = secCy - dp(86);
        float pr = dp(38);
        btnPlay.set(cx - pr, controlsCy - pr, cx + pr, controlsCy + pr);
        btnPrev.set(btnPlay.left - dp(82), controlsCy - dp(32),
                btnPlay.left - dp(26), controlsCy + dp(32));
        btnNext.set(btnPlay.right + dp(26), controlsCy - dp(32),
                btnPlay.right + dp(82), controlsCy + dp(32));

        // 进度条紧贴副标题下方（原来放在正中会空出一大段，看着很散）
        seekCy = subtitleY + dp(40);
        // 但也要保证不压到控制键
        float maxSeekCy = controlsCy - dp(58);
        if (seekCy > maxSeekCy) seekCy = maxSeekCy;
        seekRect.set(pad, seekCy - dp(18), w - pad, seekCy + dp(18));
    }

    // ── 绘制 ────────────────────────────────────────────────────────────────
    @Override protected void onDraw(Canvas c) {
        if (song == null) { drawEmpty(c); return; }
        Tokens t = Theme.t();
        int w = getWidth(), h = getHeight();

        drawBackdrop(c, w, h);
        drawGlow(c, w, h);

        float coverOff = dragging && touchMode == 2 ? dragX : 0f;
        float coverAlpha = 1f - Math.min(0.55f, Math.abs(coverOff) / (w * 0.9f));

        // 主体内容（歌词展开时淡出并上移）
        float lp = ease(lyricsPull);
        int save = c.save();
        c.translate(0, -lp * h * 0.16f);
        c.scale(1f - lp * 0.06f, 1f - lp * 0.06f, w / 2f, h / 2f);

        drawCover(c, t, coverOff, coverAlpha);
        drawTitle(c, t);
        drawSeek(c, t);
        drawControls(c, t);
        drawSecondary(c, t);
        c.restoreToCount(save);

        // 歌词页 / 极简页：在控件下方铺一整块滚动歌词
        if (style != STYLE_COVER && lp < 0.5f) {
            float top = style == STYLE_LYRICS
                    ? subtitleY + dp(26)
                    : seekCy + dp(34);
            RectF area = new RectF(dp(28), top, w - dp(28), bottomControlsTop() - dp(10));
            lyricArea.set(area);
            if (area.height() > dp(40)) drawLyricsFull(c, t, area, 1f);
        }

        drawLyrics(c, t, lp);
        drawBackButton(c, t);
        drawStyleButton(c, t);

        // 入场：整体上浮 + 淡入
        if (entrance < 1f) {
            c.drawColor(Hct.withAlpha(t.surface, 1f - entrance));
        }
    }

    private static float ease(float x) { return x * x * (3 - 2 * x); }

    private void drawEmpty(Canvas c) {
        Tokens t = Theme.t();
        c.drawColor(t.surface);
        p.reset(); p.setColor(t.onSurfaceVariant); p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(dp(16));
        c.drawText("还没有正在播放的歌曲", getWidth() / 2f, getHeight() / 2f, p);
    }

    /** 封面模糊放大底图（Apple Music 招牌背景） */
    private void drawBackdrop(Canvas c, int w, int h) {
        Tokens t = Theme.t();
        if (blurCover != null && !blurCover.isRecycled()) {
            p.reset();
            p.setFilterBitmap(true);
            Draw.bitmapFill(c, blurCover, w, h, p);
            // 压暗 + 降饱和，保证前景可读
            c.drawColor(Hct.withAlpha(t.dark ? 0xFF000000 : 0xFF101018, t.dark ? 0.42f : 0.30f));
        } else {
            c.drawColor(t.surface);
        }
        // 底部压暗渐变，让控制按钮区更沉
        p.reset();
        p.setShader(new LinearGradient(0, h * 0.35f, 0, h,
                new int[]{0x00000000, Hct.withAlpha(0xFF000000, 0.34f), Hct.withAlpha(0xFF000000, 0.62f)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.35f, w, h, p);
        p.setShader(null);
    }

    /** 跟随封面主色的呼吸光晕 */
    private void drawGlow(Canvas c, int w, int h) {
        int accent = coverAccent != 0 ? coverAccent : Theme.t().primary;
        float pulse = 0.5f + 0.5f * (float) Math.sin(breath * Math.PI * 2);
        float amp = playing ? 1f : 0.45f;

        // 两团错位的光，形成流动感
        float r1 = w * (0.85f + 0.12f * pulse);
        drawBlob(c, coverRect.centerX() - w * 0.14f, coverRect.centerY() - h * 0.05f, r1,
                Hct.withAlpha(accent, (0.20f + 0.10f * pulse) * amp));
        float r2 = w * (0.70f + 0.16f * (1 - pulse));
        drawBlob(c, coverRect.centerX() + w * 0.20f, coverRect.centerY() + h * 0.06f, r2,
                Hct.withAlpha(Hct.blendLab(accent, Theme.t().tertiary, 0.5f),
                        (0.15f + 0.09f * (1 - pulse)) * amp));
    }

    private void drawBlob(Canvas c, float cx, float cy, float r, int color) {
        p.reset();
        p.setShader(new RadialGradient(cx, cy, Math.max(1, r),
                new int[]{color, Hct.withAlpha(color, 0f)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
        p.setShader(null);
    }

    /** 大幅专辑封面 */
    private void drawCover(Canvas c, Tokens t, float offX, float alpha) {
        float r = dp(14);
        RectF cr = new RectF(coverRect);
        cr.offset(offX, 0);

        // 悬浮阴影 + 轻微缩放（跟手时缩小）
        float sc = 1f - Math.min(0.10f, Math.abs(offX) / (getWidth() * 3f));
        float cx = cr.centerX(), cy = cr.centerY();
        RectF crs = new RectF(cx - cr.width() / 2 * sc, cy - cr.height() / 2 * sc,
                cx + cr.width() / 2 * sc, cy + cr.height() / 2 * sc);

        Path cp = Draw.roundRect(crs, r);
        Draw.layeredShadow(c, cp, 0xFF000000, dp(26), dp(14), p);

        p.reset(); p.setStyle(Paint.Style.FILL);
        if (cover != null && !cover.isRecycled()) {
            int save = c.save();
            c.clipPath(cp);
            p.setAlpha((int) (255 * alpha));
            Draw.bitmapCrop(c, cover, crs, p);
            p.setAlpha(255);
            c.restoreToCount(save);
        } else {
            p.setShader(new LinearGradient(crs.left, crs.top, crs.right, crs.bottom,
                    new int[]{t.primaryContainer, Hct.blendLab(t.primaryContainer, t.tertiary, 0.6f)},
                    null, Shader.TileMode.CLAMP));
            c.drawPath(cp, p);
            p.setShader(null);
            RectF ib = new RectF(crs);
            ib.inset(crs.width() * 0.30f, crs.height() * 0.30f);
            Icons.draw(c, "note", ib, Hct.withAlpha(t.onPrimaryContainer, 0.8f), 1f, p);
        }

        // 玻璃质感的边缘高光
        Draw.stroke(c, cp, Hct.withAlpha(Color.WHITE, 0.16f), dp(1f), p);

        // 跟手时的下一首/上一首提示
        if (Math.abs(offX) > dp(40)) {
            boolean next = offX < 0;
            float a = Math.min(0.85f, Math.abs(offX) / (getWidth() * 0.4f));
            p.reset(); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Ui.tfMed()); p.setTextSize(dp(12));
            p.setColor(Hct.withAlpha(Color.WHITE, a));
            c.drawText(next ? "下一首" : "上一首", cx, crs.bottom + dp(20), p);
        }
    }

    private static RectF centerCrop(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(dst.width() / bw, dst.height() / bh);
        float w = dst.width() / scale, h = dst.height() / scale;
        float l = (bw - w) / 2f, tp = (bh - h) / 2f;
        return new RectF(l, tp, l + w, tp + h);
    }

    private void drawTitle(Canvas c, Tokens t) {
        float y = titleY;
        float w = getWidth() - dp(48);

        p.reset(); p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.tfBold()); p.setTextSize(dp(21));
        p.setColor(0xFFFFFFFF);

        String title = song != null ? song.title : "";
        c.drawText(ellipsize(title, p, w), dp(24), y, p);

        p.setTypeface(Ui.tf()); p.setTextSize(dp(13.5f));
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.72f));
        c.drawText(ellipsize(song != null ? song.subtitle() : "", p, w), dp(24), y + dp(20), p);
    }

    private static String ellipsize(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        String ell = "…";
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + ell) <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, lo) + ell;
    }

    /** 进度条：可拖拽，带圆形滑块 */
    private void drawSeek(Canvas c, Tokens t) {
        float y = seekRect.centerY();
        float l = seekRect.left, r = seekRect.right;
        float w = r - l;
        float shown = seeking && seekTarget >= 0 ? seekTarget : progress;

        // 轨道
        p.reset(); p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(5));
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.22f));
        c.drawLine(l, y, r, y, p);
        p.setColor(0xFFFFFFFF);
        c.drawLine(l, y, l + w * clamp01(shown), y, p);

        // 滑块
        float kx = l + w * clamp01(shown);
        float kr = dp(seeking ? 8f : 6f);
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(kx, y, kr, p);

        // 时间
        long cur = seeking && seekTarget >= 0 ? (long) (seekTarget * durMs) : posMs;
        p.reset(); p.setTextSize(dp(11)); p.setTypeface(Ui.tfMed());
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.65f));
        p.setTextAlign(Paint.Align.LEFT);
        c.drawText(Ui.mmss(cur), l, y + dp(24), p);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Ui.mmss(durMs), r, y + dp(24), p);
    }

    private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private void drawControls(Canvas c, Tokens t) {
        // 上一首
        RectF ib = new RectF(btnPrev); ib.inset(dp(18), dp(18));
        Icons.draw(c, "prev", ib, 0xFFFFFFFF, 1f, p);
        // 下一首
        ib.set(btnNext); ib.inset(dp(18), dp(18));
        Icons.draw(c, "next", ib, 0xFFFFFFFF, 1f, p);

        // 播放/暂停：大圆形，半透明玻璃质感
        float r = btnPlay.width() / 2f;
        float cx = btnPlay.centerX(), cy = btnPlay.centerY();
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.16f));
        c.drawCircle(cx, cy, r, p);
        Draw.stroke(c, circlePath(cx, cy, r), Hct.withAlpha(0xFFFFFFFF, 0.20f), dp(1f), p);

        RectF pb = new RectF(btnPlay);
        pb.inset(r * 0.66f, r * 0.66f);
        Icons.draw(c, playing ? "pause" : "play", pb, 0xFFFFFFFF, 1f, p);
    }

    private final Path tmpPath = new Path();
    private Path circlePath(float cx, float cy, float r) {
        tmpPath.reset();
        tmpPath.addCircle(cx, cy, r, Path.Direction.CW);
        return tmpPath;
    }

    private void drawSecondary(Canvas c, Tokens t) {
        PlayerService s = PlayerService.instance();
        int rep = s != null ? s.repeatMode() : PlayerService.REPEAT_ALL;
        int sh = s != null ? s.shuffleMode() : PlayerService.SHUFFLE_OFF;

        int active = 0xFFFFFFFF;
        int dim = Hct.withAlpha(0xFFFFFFFF, 0.55f);

        RectF ib = new RectF(btnShuffle); ib.inset(dp(13), dp(13));
        Icons.draw(c, "shuffle", ib, sh == PlayerService.SHUFFLE_ON ? active : dim, 1f, p);

        ib.set(btnHeart); ib.inset(dp(13), dp(13));
        Icons.draw(c, "heart", ib, dim, 1f, p);

        ib.set(btnQueue); ib.inset(dp(13), dp(13));
        Icons.draw(c, "queue", ib, dim, 1f, p);

        ib.set(btnRepeat); ib.inset(dp(13), dp(13));
        Icons.draw(c, rep == PlayerService.REPEAT_ONE ? "repeat_one" : "repeat", ib,
                rep != PlayerService.REPEAT_OFF ? active : dim, 1f, p);

        // 选中态小圆点
        if (sh == PlayerService.SHUFFLE_ON) dot(c, btnShuffle);
        if (rep != PlayerService.REPEAT_OFF) dot(c, btnRepeat);
    }

    private void dot(Canvas c, RectF b) {
        p.reset(); p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF);
        c.drawCircle(b.centerX(), b.bottom + dp(1), dp(2.2f), p);
    }

    /** 可下拉展开的歌词面板 */
    private void drawLyrics(Canvas c, Tokens t, float lp) {
        if (lp < 0.005f) return;
        int w = getWidth(), h = getHeight();

        int save = c.save();
        // 底部抽屉：整块从屏幕下方滑上来，配合背后的内容缩小变暗
        float slide = (1 - lp);
        c.translate(0, slide * h * 0.92f);
        // 滑入过程中带一点点缩放，落位更「软」
        float sc = 0.96f + 0.04f * lp;
        c.scale(sc, sc, w / 2f, h / 2f);

        // 面板背景
        p.reset();
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Hct.withAlpha(0xFF0A0A12, 0.90f * lp), Hct.withAlpha(0xFF0A0A12, 0.97f * lp)},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        // 顶部把手，暗示这是一个可以下滑收起的抽屉
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.28f * lp));
        float hbW = dp(40), hbY = Ui.statusBarHeight(getContext()) + dp(12);
        c.drawRoundRect(new RectF(w / 2f - hbW / 2, hbY, w / 2f + hbW / 2, hbY + dp(4.5f)),
                dp(3), dp(3), p);

        float top = Ui.statusBarHeight(getContext()) + dp(70);
        p.reset(); p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.tfBold()); p.setTextSize(dp(13));
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.55f * lp));
        c.drawText("歌词", dp(24), top - dp(18), p);

        if (song == null) { c.restoreToCount(save); return; }

        String lyr = Lyrics.textFor(song);
        if (lyr == null || lyr.trim().length() == 0) {
            // 没有歌词 / 还没加载完：只画占位文字。
            // 注意：绝不能在 onDraw 里做 I/O —— 那会卡死主线程被判 ANR（表现为闪退）。
            p.reset(); p.setTextAlign(Paint.Align.LEFT);
            p.setTypeface(Ui.tf()); p.setTextSize(dp(15));
            p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.5f * lp));
            c.drawText(Lyrics.isLoaded(song) ? "这首歌没有歌词" : "正在读取歌词…",
                    dp(24), top + dp(20), p);
            c.restoreToCount(save);
            return;
        }
        long cur = posMs;
        String[] lines = lyr.split("\n");

        // 找当前行（有时间轴则按时间，否则按播放进度比例）
        int curLine = -1;
        long[] times = Lyrics.timesFor(song);
        if (times != null && times.length == lines.length) {
            for (int i = 0; i < times.length; i++) if (cur <= times[i]) { curLine = i - 1; break; }
            if (curLine < 0) curLine = times.length - 1;
        }

        p.setTypeface(Ui.tfMed());
        float lh = dp(34);
        float y = top + dp(20);
        float cx = dp(24);
        int count = 0;
        for (int i = 0; i < lines.length; i++) {
            if (y > h - dp(70)) break;
            boolean isCur = i == curLine;
            // 逐行错落：靠上的行先出现，形成「一层层浮上来」的感觉
            float stagger = Math.max(0f, Math.min(1f, (lp - i * 0.045f) / 0.55f));
            stagger = stagger * stagger * (3 - 2 * stagger);
            if (stagger <= 0.01f) { y += lh; continue; }
            p.setTextSize(dp(isCur ? 20 : 17));
            p.setColor(isCur ? Hct.withAlpha(0xFFFFFFFF, 1f * stagger)
                    : Hct.withAlpha(0xFFFFFFFF, 0.42f * lp * stagger));
            p.setTypeface(isCur ? Ui.tfBold() : Ui.tfMed());
            String line = lines[i].trim();
            if (line.length() == 0) { y += lh * 0.5f; continue; }
            // 长行换行
            for (String seg : wrap(line, p, w - dp(48))) {
                c.drawText(seg, cx, y, p);
                y += lh * (isCur ? 1.05f : 0.95f);
            }
            count++;
        }
        if (count == 0) {
            p.setTextSize(dp(15)); p.setTypeface(Ui.tf());
            p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.5f));
            c.drawText("这首歌还没有歌词", cx, y, p);
        }

        // 底部提示
        p.setTextSize(dp(11)); p.setTextAlign(Paint.Align.CENTER);
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.35f * lp));
        c.drawText("下滑收起", w / 2f, h - dp(24), p);

        c.restoreToCount(save);
    }

    private java.util.List<String> wrap(String s, Paint p, float max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (p.measureText(s) <= max) { out.add(s); return out; }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (p.measureText(cur.toString() + ch) > max && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private void drawBackButton(Canvas c, Tokens t) {
        float lp = ease(lyricsPull);
        // 展开歌词后返回键变为关闭
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.14f));
        c.drawCircle(backRect.centerX(), backRect.centerY(), backRect.width() / 2f, p);

        RectF ib = new RectF(backRect);
        ib.inset(dp(12), dp(12));
        if (lp > 0.5f) Icons.draw(c, "close", ib, 0xFFFFFFFF, 1f, p);
        else {
            // 向下箭头（收起播放页）
            p.reset(); p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.2f)); p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0xFFFFFFFF);
            float cx = backRect.centerX(), cy = backRect.centerY();
            c.drawLine(cx, cy - dp(5), cx, cy + dp(5), p);
            c.drawLine(cx, cy + dp(5), cx - dp(5), cy, p);
            c.drawLine(cx, cy + dp(5), cx + dp(5), cy, p);
        }

        // 顶部把手提示
        if (lp < 0.02f) {
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.30f));
            RectF hb = new RectF(getWidth() / 2f - dp(18), Ui.statusBarHeight(getContext()) + dp(8),
                    getWidth() / 2f + dp(18), Ui.statusBarHeight(getContext()) + dp(13));
            c.drawRoundRect(hb, dp(3), dp(3), p);
        }
    }

    // ── 封面加载 ────────────────────────────────────────────────────────────
    private void loadCover() {
        if (song == null) { cover = null; blurCover = null; coverHash = -1; return; }
        final int id = (int) song.id;
        if (coverHash == id && cover != null) return;
        coverHash = id;
        cover = song.cover;
        blurCover = null;
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = Library.coverSync(getContext(), song, 900);
                if (b == null) return;
                final int avg = Library.averageColor(b);
                final Bitmap blur = makeBlur(b);
                post(new Runnable() { @Override public void run() {
                    if ((int) song.id != coverHash) return;
                    cover = b; blurCover = blur;
                    coverAccent = Theme.accentFromCover(avg);
                    invalidate();
                }});
            }
        });
    }

    /** 生成大幅模糊底图（降采样 + 多轮 box blur） */
    private static Bitmap makeBlur(Bitmap src) {
        int tw = 60, th = Math.max(8, (int) ((float) src.getHeight() / src.getWidth() * tw));
        Bitmap small = Bitmap.createScaledBitmap(src, tw, th, true);
        int[] px = new int[tw * th];
        small.getPixels(px, 0, tw, 0, 0, tw, th);
        int[] tmp = new int[px.length];
        for (int pass = 0; pass < 3; pass++) {
            boxH(px, tmp, tw, th, 3);
            boxV(tmp, px, tw, th, 3);
        }
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, tw, 0, 0, tw, th);
        return out;
    }

    private static void boxH(int[] in, int[] out, int w, int h, int r) {
        for (int y = 0; y < h; y++) {
            int off = y * w, a = 0, rr = 0, g = 0, b = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[off + clamp(i, 0, w - 1)];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
            for (int x = 0; x < w; x++) {
                out[off + x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
                int ca = in[off + clamp(x - r, 0, w - 1)], cb2 = in[off + clamp(x + r + 1, 0, w - 1)];
                a += (cb2 >>> 24) - (ca >>> 24);
                rr += ((cb2 >> 16) & 0xFF) - ((ca >> 16) & 0xFF);
                g += ((cb2 >> 8) & 0xFF) - ((ca >> 8) & 0xFF);
                b += (cb2 & 0xFF) - (ca & 0xFF);
            }
        }
    }

    private static void boxV(int[] in, int[] out, int w, int h, int r) {
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, g = 0, b = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, 0, h - 1) * w + x];
                a += c >>> 24; rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
                int ca = in[clamp(y - r, 0, h - 1) * w + x], cb = in[clamp(y + r + 1, 0, h - 1) * w + x];
                a += (cb >>> 24) - (ca >>> 24);
                rr += ((cb >> 16) & 0xFF) - ((ca >> 16) & 0xFF);
                g += ((cb >> 8) & 0xFF) - ((ca >> 8) & 0xFF);
                b += (cb & 0xFF) - (ca & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    // ── 手势 ────────────────────────────────────────────────────────────────
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragX = dragY = 0;
                dragging = true;
                seeking = false;
                touchMode = 0;
                downX = x; downY = y;
                baseLyricsPull = lyricsPull;
                if (seekRect.contains(x, y)) { touchMode = 1; seeking = true; seekTarget = posOf(x); }
                else if (style != STYLE_COVER) touchMode = 0;   // 歌词页/极简页不上滑
                else if (lyricsPull > 0.5f) touchMode = 3;
                else if (coverRect.contains(x, y)) touchMode = 2;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                if (touchMode == 1) {
                    float nt = posOf(x);
                    if (Math.abs(nt - seekTarget) > 0.004f) Ui.hapticTick(this);
                    seekTarget = nt;
                    invalidate();
                } else {
                    dragX = x - downX;
                    dragY = y - downY;
                    // 纵向滑动 → 歌词面板（与拖拽起点无关，纯跟手）
                    if (Math.abs(dragY) > dp(18) && Math.abs(dragY) > Math.abs(dragX)) {
                        touchMode = 3;
                        float d = -dragY / (getHeight() * 0.42f);
                        lyricsPull = clamp01(baseLyricsPull + d);
                        invalidate();
                    } else if (Math.abs(dragX) > dp(8)) {
                        touchMode = 2;
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                PlayerService s = PlayerService.instance();
                if (touchMode == 1 && s != null && durMs > 0) {
                    s.seek((int) (seekTarget * durMs));
                    seekTarget = -1;
                } else if (touchMode == 2 && Math.abs(dragX) > dp(72) && s != null) {
                    // 滑动切歌
                    if (dragX < 0) s.next(true); else s.prev();
                    Ui.hapticStrong(this);
                } else if (touchMode == 3) {
                    lyricsTarget = lyricsPull > 0.35f ? 1f : 0f;
                } else if (touchMode == 0) {
                    handleTap(x, y);
                }
                seekTarget = -1;
                if (vt != null) { vt.recycle(); vt = null; }
                dragX = dragY = 0;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private float downX, downY;
    private float baseLyricsPull;

    private float posOf(float x) {
        float w = seekRect.width();
        return w <= 0 ? 0 : clamp01((x - seekRect.left) / w);
    }

    private void handleTap(float x, float y) {
        PlayerService s = PlayerService.instance();
        if (s == null) return;
        if (backRect.contains(x, y)) {
            if (lyricsPull > 0.5f) { lyricsTarget = 0f; Ui.hapticLight(this); }
            else if (cb != null) cb.back();
            return;
        }
        // 样式切换
        if (styleBtn.contains(x, y)) { Ui.hapticLight(this); cycleStyle(); return; }

        // 点歌词某一行 → 跳到那一句
        if (style != STYLE_COVER && lyricLineY != null && lyricArea.contains(x, y)) {
            for (int i = 0; i < lyricLineCount; i++) {
                float ly = lyricLineY[i];
                if (Math.abs(y - ly + dp(8)) < dp(19)) {
                    if (lyricLineTime != null && i < lyricLineTime.length && lyricLineTime[i] >= 0) {
                        Ui.hapticLight(this);
                        s.seek((int) lyricLineTime[i]);
                    }
                    return;
                }
            }
        }

        if (btnPlay.contains(x, y)) { Ui.hapticLight(this); s.toggle(); return; }
        if (btnPrev.contains(x, y)) { Ui.hapticLight(this); s.prev(); return; }
        if (btnNext.contains(x, y)) { Ui.hapticLight(this); s.next(true); return; }
        if (btnShuffle.contains(x, y)) { Ui.hapticLight(this); s.toggleShuffle(); return; }
        if (btnRepeat.contains(x, y)) { Ui.hapticLight(this); s.cycleRepeat(); return; }
        if (btnQueue.contains(x, y)) {
            Ui.hapticLight(this);
            if (getContext() instanceof PlayerActivity) ((PlayerActivity) getContext()).showQueue();
            return;
        }
        // 点空白处：切换歌词
        if (y < coverRect.top && y > backRect.bottom) {
            lyricsTarget = lyricsTarget > 0.5f ? 0f : 1f;
            Ui.hapticLight(this);
        }
    }

    // ── PlayerService.Listener ──────────────────────────────────────────────
    @Override public void onSongChanged(Song s, int index) {
        song = s;
        loadCover();
        lyricsScroll = 0;
        invalidate();
        loadLyricsAsync();
    }

    /** 后台预加载歌词，完成后重绘。绘制路径永不阻塞。 */
    private void loadLyricsAsync() {
        Lyrics.preload(getContext(), song, new Runnable() {
            @Override public void run() { invalidate(); }
        });
    }
    @Override public void onPlayStateChanged(boolean pl) { playing = pl; invalidate(); }
    @Override public void onProgress(long pos, long dur) {
        posMs = pos;
        durMs = dur;
        progress = dur > 0 ? (float) pos / dur : 0f;
        if (!dragging) invalidate();
    }
    @Override public void onQueueChanged() { invalidate(); }
    @Override public void onRepeatShuffleChanged(int repeat, int shuffle) { invalidate(); }

    // ══════════════════════════════════════════════════════════════════════
    //  样式切换
    // ══════════════════════════════════════════════════════════════════════
    private float bottomControlsTop() { return btnPlay.top - dp(14); }

    /** 循环切换三种播放页样式 */
    public void cycleStyle() {
        style = (style + 1) % 3;
        Theme.setPlayerStyle(style);
        lyricScroll = lyricScrollTarget = 0;
        lastCurLine = -1;
        requestLayout();
        invalidate();
    }

    private void drawStyleButton(Canvas c, Tokens t) {
        float lp = ease(lyricsPull);
        int alpha = (int) (255 * (1 - lp));
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.14f * (1 - lp)));
        c.drawCircle(styleBtn.centerX(), styleBtn.centerY(), styleBtn.width() / 2f, p);

        RectF ib = new RectF(styleBtn);
        ib.inset(dp(12), dp(12));
        // 三个样式用不同图标暗示
        String ic = style == STYLE_COVER ? "list" : style == STYLE_LYRICS ? "equalizer" : "note";
        Icons.draw(c, ic, ib, Hct.withAlpha(0xFFFFFFFF, 0.9f * (1 - lp)), 1f, p);

        if (lp < 0.05f) {
            p.reset(); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Ui.tfMed()); p.setTextSize(dp(9.5f));
            p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.45f));
            String label = style == STYLE_COVER ? "封面"
                    : style == STYLE_LYRICS ? "歌词" : "极简";
            c.drawText(label, styleBtn.centerX(), styleBtn.bottom + dp(13), p);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  整屏滚动歌词
    // ══════════════════════════════════════════════════════════════════════
    /**
     * 预排版歌词：按当前宽度折行并缓存。
     * 只在换歌或宽度变化时重算，绘制路径里不再做任何字符串分配。
     */
    private boolean prepareLyrics(float maxWidth) {
        if (song == null) return false;
        if (lyricCacheId == song.id && Math.abs(lyricCacheWidth - maxWidth) < 1f
                && lyricLines != null) return true;
        String lyr = Lyrics.textFor(song);
        if (lyr == null || lyr.trim().length() == 0) {
            lyricLines = null;
            lyricCacheId = song.id;
            lyricCacheWidth = maxWidth;
            return false;
        }
        lyricLines = lyr.split("\n");
        lyricTimes = Lyrics.timesFor(song);
        lyricWrapped = new String[lyricLines.length][];
        android.text.TextPaint tp = new android.text.TextPaint();
        tp.setTypeface(Ui.tfMed());
        tp.setTextSize(dp(19));
        for (int i = 0; i < lyricLines.length; i++) {
            String line = lyricLines[i].trim();
            if (line.length() == 0) { lyricWrapped[i] = new String[0]; continue; }
            lyricWrapped[i] = wrapCached(line, tp, maxWidth);
        }
        lyricCacheId = song.id;
        lyricCacheWidth = maxWidth;
        return true;
    }

    private static String[] wrapCached(String s, android.graphics.Paint p, float max) {
        if (p.measureText(s) <= max) return new String[]{s};
        java.util.List<String> out = new java.util.ArrayList<>(2);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (cur.length() > 0 && p.measureText(cur.toString() + ch) > max) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    /** 当前时间对应的歌词行 */
    private int currentLine(String[] lines, long[] times) {
        if (times != null && times.length == lines.length) {
            for (int i = 0; i < times.length; i++) if (posMs <= times[i]) return i - 1;
            return times.length - 1;
        }
        // 无时间轴：按播放进度比例估算
        if (durMs > 0) {
            int est = (int) ((float) posMs / durMs * lines.length);
            return Math.max(0, Math.min(lines.length - 1, est));
        }
        return -1;
    }

    private void drawLyricsFull(Canvas c, Tokens t, RectF area, float alpha) {
        if (!prepareLyrics(area.width())) {
            p.reset(); p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Ui.tf()); p.setTextSize(dp(13.5f));
            p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.45f * alpha));
            c.drawText(Lyrics.isLoaded(song) ? "这首歌还没有歌词" : "正在读取歌词…",
                    area.centerX(), area.top + dp(30), p);
            return;
        }
        String[] lines = lyricLines;
        long[] times = lyricTimes;
        int cur = currentLine(lines, times);

        float lh = dp(36);
        float total = lines.length * lh;
        // 让当前行居中
        float target = cur * lh - area.height() / 2f + lh / 2f;
        target = Math.max(0, Math.min(Math.max(0, total - area.height()), target));
        lyricScrollTarget = target;
        // 平滑跟随（当前行变化时给一点加速度，避免生硬）
        float k = lastCurLine != cur ? 0.14f : 0.09f;
        lyricScroll += (lyricScrollTarget - lyricScroll) * k;
        lastCurLine = cur;

        if (lyricLineY == null || lyricLineY.length < lines.length) {
            lyricLineY = new float[lines.length];
            lyricLineTime = new long[lines.length];
        }
        lyricLineCount = lines.length;

        int save = c.save();
        c.clipRect(area);
        p.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < lines.length; i++) {
            float y = area.top + i * lh - lyricScroll + dp(24);
            lyricLineY[i] = y;
            lyricLineTime[i] = (times != null && i < times.length) ? times[i] : -1;
            if (y < area.top - lh || y > area.bottom + lh) continue;

            String line = lines[i].trim();
            boolean isCur = i == cur;
            // 距离当前行的远近决定透明度与大小
            float d = Math.min(1f, Math.abs(i - cur) / 5f);
            float a = (isCur ? 1f : 0.42f * (1f - d * 0.75f)) * alpha;
            p.setTypeface(isCur ? Ui.tfBold() : Ui.tfMed());
            p.setTextSize(dp(isCur ? 19 : 15.5f));
            p.setColor(isCur ? 0xFFFFFFFF : Hct.withAlpha(0xFFFFFFFF, a));
            String[] segs = (lyricWrapped != null && i < lyricWrapped.length)
                    ? lyricWrapped[i] : new String[0];
            for (String seg : segs) {
                c.drawText(seg, area.left, y, p);
                y += lh * 0.92f;
            }
            if (isCur) {
                // 当前行左侧一道强调色竖条
                p.reset(); p.setStyle(Paint.Style.FILL);
                p.setColor(Hct.withAlpha(coverAccent != 0 ? coverAccent : Theme.t().primary, 0.95f));
                c.drawRoundRect(new RectF(area.left - dp(12), y - lh * 0.75f,
                        area.left - dp(9), y - lh * 0.75f + dp(16)), dp(2), dp(2), p);
            }
        }
        c.restoreToCount(save);

        // 上下渐隐，视觉上像无限滚动
        p.reset();
        p.setShader(new LinearGradient(0, area.top, 0, area.top + dp(26),
                new int[]{0x66000000, 0x00000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(area.left, area.top, area.right, area.top + dp(26), p);
        p.setShader(new LinearGradient(0, area.bottom - dp(30), 0, area.bottom,
                new int[]{0x00000000, 0x77000000}, null, Shader.TileMode.CLAMP));
        c.drawRect(area.left, area.bottom - dp(30), area.right, area.bottom, p);
        p.setShader(null);
    }

}
