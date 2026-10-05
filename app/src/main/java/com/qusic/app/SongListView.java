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
import android.view.VelocityTracker;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Qusic 歌曲列表：全自绘的高性能列表。
 *
 * <p>为什么不用 RecyclerView：本项目零依赖，且自绘能实现一些系统控件做不到的效果：
 * <ul>
 *   <li>逐项错位的入场动画（staggered entrance），滚动时新项也带淡入上浮</li>
 *   <li>正在播放项的行内「律动条」动画</li>
 *   <li>滚动时的边缘渐隐（scroll fade）</li>
 *   <li>超滚动回弹（over-scroll bounce）</li>
 * </ul>
 *
 * <p>性能：只绘制可见区域内的行，封面异步加载后局部刷新。
 */
public class SongListView extends View implements PlayerService.Listener {

    public interface OnPick {
        void onPick(List<Song> visible, int index);
        default void onLongPick(Song s, int index) {}
    }

    private static final int ROW_H = 68;   // dp

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tmp = new Path();
    private final RectF rowRect = new RectF();
    private final RectF coverRect = new RectF();

    private List<Song> data = new ArrayList<>();
    private OnPick cb;

    private float scrollY = 0f;       // 当前滚动偏移（px）
    private float maxScroll = 0f;
    private float overscroll = 0f;    // 回弹偏移

    private float downY, downX;
    private float lastY;
    private boolean dragging, moved;
    private float touchSlop;
    private VelocityTracker vt;
    private float velocity;
    private int pressed = -1;
    private float pressAnim = 0f;

    private long currentId = -1;
    private boolean playing;
    private float beat = 0f;
    private float entrance = 0f;

    /** 高亮关键字（搜索页用） */
    private String highlight;

    private float lastFrame;

    public SongListView(Context c) {
        super(c);
        touchSlop = Ui.dp(c, 8);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        startLoop();
    }

    public void setOnPick(OnPick c) { this.cb = c; }

    public void setData(List<Song> list) {
        List<Song> next = list != null ? list : new ArrayList<Song>();
        // 内容没变就别重置滚动位置，否则任何一次 setData 都会让列表跳回顶部
        boolean same = sameContent(data, next);
        data = next;
        if (!same) {
            scrollY = 0;
            overscroll = 0;
            entrance = 0f;
        } else {
            computeMax();
            if (scrollY > maxScroll) scrollY = Math.max(0, maxScroll);
            invalidate();
            return;
        }
        computeMax();
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
        invalidate();
    }

    /** 两份列表是否是「同一批歌、同样顺序」 */
    private static boolean sameContent(List<Song> a, List<Song> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i).id != b.get(i).id) return false;
        }
        return true;
    }

    public void setHighlight(String h) { highlight = h; invalidate(); }

    public List<Song> data() { return data; }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) {
            s.addListener(this);
            Song cur = s.current();
            currentId = cur != null ? cur.id : -1;
            playing = s.isPlaying();
        }
        startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) s.removeListener(this);
    }

    private void startLoop() { postOnAnimation(loop); }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!isShown()) { postOnAnimationDelayed(loop, 600); return; }
            long now = System.currentTimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;

            boolean need = false;
            // 正在播放项的律动
            if (playing && currentId >= 0) {
                beat += dt * 2.2f;
                if (beat > 1f) beat -= 1f;
                need = true;
            }
            // 惯性滑动
            if (!dragging && Math.abs(velocity) > 1f) {
                scrollY -= velocity * dt;
                velocity *= Math.pow(0.02f, dt);   // 摩擦
                if (Math.abs(velocity) < 20f) velocity = 0;
                if (scrollY < 0) { scrollY = 0; velocity = 0; }
                if (scrollY > maxScroll) { scrollY = maxScroll; velocity = 0; }
                need = true;
            }
            // 回弹归位
            if (!dragging && Math.abs(overscroll) > 0.5f) {
                overscroll *= Math.pow(0.001f, dt);
                if (Math.abs(overscroll) < 0.5f) overscroll = 0;
                need = true;
            }
            // 按压动画
            if (pressed >= 0 && pressAnim < 1f) { pressAnim = Math.min(1f, pressAnim + dt * 8f); need = true; }
            if (pressed < 0 && pressAnim > 0f) { pressAnim = Math.max(0f, pressAnim - dt * 8f); need = true; }

            if (need) invalidate();
            postOnAnimationDelayed(loop, 16);
        }
    };

    private float dp(float v) { return Ui.dp(getContext(), v); }
    private float rowH() { return dp(ROW_H); }

    private void computeMax() {
        maxScroll = Math.max(0, data.size() * rowH() - getHeight());
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeMax();
    }

    @Override protected void onDraw(Canvas c) {
        if (data.isEmpty()) { drawEmpty(c); return; }
        int h = getHeight();
        float rh = rowH();
        float base = -scrollY - overscroll;

        int first = Math.max(0, (int) ((-base) / rh) - 1);
        int last = Math.min(data.size() - 1, (int) ((h - base) / rh) + 1);

        Tokens t = Theme.t();

        for (int i = first; i <= last; i++) {
            float top = base + i * rh;
            drawRow(c, t, i, top, rh);
        }

        // 边缘渐隐
        p.reset();
        p.setShader(new LinearGradient(0, 0, 0, dp(24),
                new int[]{t.surface, Hct.withAlpha(t.surface, 0f)}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, getWidth(), dp(24), p);
        p.setShader(null);
    }

    private void drawEmpty(Canvas c) {
        Tokens t = Theme.t();
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        p.reset(); p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.16f));
        c.drawCircle(cx, cy - dp(30), dp(38), p);
        RectF ib = new RectF(cx - dp(20), cy - dp(50), cx + dp(20), cy - dp(10));
        Icons.draw(c, "note", ib, Hct.withAlpha(t.onSurfaceVariant, 0.55f), 1f, p);

        p.reset(); p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Ui.tfMed()); p.setTextSize(dp(15));
        p.setColor(t.onSurface);
        c.drawText("这里还很安静", cx, cy + dp(34), p);
        p.setTypeface(Ui.tf()); p.setTextSize(dp(12.5f));
        p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        c.drawText("去曲库页导入音乐，你的歌会出现在这里", cx, cy + dp(56), p);
    }

    private void drawRow(Canvas c, Tokens t, int i, float top, float rh) {
        Song s = data.get(i);
        if (top > getHeight() || top + rh < 0) return;

        // 入场：逐项错位淡入上浮
        float stagger = Math.min(1f, entrance * 1.6f - Math.min(i, 14) * 0.045f);
        if (stagger <= 0.001f) return;
        stagger = Math.max(0f, Math.min(1f, stagger));
        float e = stagger * stagger * (3 - 2 * stagger);
        float dy = (1 - e) * dp(18);

        boolean isCurrent = s.id == currentId;
        boolean isPressed = i == pressed;
        float press = isPressed ? pressAnim : 0f;

        rowRect.set(dp(10), top + dy + dp(2), getWidth() - dp(10), top + dy + rh - dp(2));

        int save = c.save();
        c.saveLayerAlpha(rowRect.left - dp(4), rowRect.top - dp(4),
                rowRect.right + dp(4), rowRect.bottom + dp(4), (int) (255 * e), Canvas.ALL_SAVE_FLAG);

        // 行背景
        float rr = dp(16);
        tmp.reset();
        tmp.addRoundRect(rowRect, rr, rr, Path.Direction.CW);
        p.reset(); p.setStyle(Paint.Style.FILL);
        if (isCurrent) {
            // 正在播放：强调色容器
            p.setColor(Hct.composite(t.primaryContainer, 0.72f * e, t.surface));
        } else if (press > 0.01f) {
            p.setColor(Hct.composite(t.primary, 0.10f * press * e, t.surface));
        } else {
            p.setColor(Color.TRANSPARENT);
        }
        c.drawPath(tmp, p);

        // 封面
        float cs = rowRect.height() - dp(12);
        coverRect.set(rowRect.left + dp(8), rowRect.centerY() - cs / 2,
                rowRect.left + dp(8) + cs, rowRect.centerY() + cs / 2);
        drawRowCover(c, t, s, isCurrent);

        // 文本
        float tx = coverRect.right + dp(12);
        float maxW = rowRect.right - tx - dp(56);
        p.reset(); p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(isCurrent ? Ui.tfBold() : Ui.tfMed());
        p.setTextSize(dp(14.5f));
        p.setColor(t.onSurface);
        String title = s.title;
        float titleY = rowRect.centerY() - dp(1.5f);
        drawHighlighted(c, title, tx, titleY, maxW, t, isCurrent, p);

        p.setTypeface(Ui.tf());
        p.setTextSize(dp(11.5f));
        p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        c.drawText(ellipsize(s.subtitle(), p, maxW), tx, titleY + dp(16.5f), p);

        // 右侧：正在播放显示律动条 / 否则显示时长
        if (isCurrent) {
            drawEqualizer(c, t, rowRect.right - dp(22), rowRect.centerY());
        } else {
            p.reset(); p.setTextAlign(Paint.Align.RIGHT);
            p.setTypeface(Ui.tfMed()); p.setTextSize(dp(11));
            p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.72f));
            c.drawText(s.durationText(), rowRect.right - dp(12), rowRect.centerY() + dp(4), p);
        }

        c.restore();
        c.restoreToCount(save);
    }

    /** 带关键字高亮的文本绘制 */
    private void drawHighlighted(Canvas c, String text, float x, float y, float maxW,
                                 Tokens t, boolean current, Paint p) {
        if (highlight == null || highlight.length() == 0) {
            c.drawText(ellipsize(text, p, maxW), x, y, p);
            return;
        }
        int[] range = Ui.matchRange(text, highlight);
        if (range == null) { c.drawText(ellipsize(text, p, maxW), x, y, p); return; }

        // 逐段绘制，命中段用强调色
        String pre = text.substring(0, range[0]);
        String hit = text.substring(range[0], Math.min(range[1], text.length()));
        String post = text.substring(Math.min(range[1], text.length()));
        float cx = x;
        int base = p.getColor();
        p.setColor(t.onSurface);
        c.drawText(pre, cx, y, p); cx += p.measureText(pre);
        p.setColor(current ? t.primary : t.tertiary);
        c.drawText(hit, cx, y, p); cx += p.measureText(hit);
        p.setColor(base);
        String rest = post;
        float remain = maxW - (cx - x);
        if (remain > 0) c.drawText(ellipsize(rest, p, remain), cx, y, p);
    }

    private void drawRowCover(Canvas c, Tokens t, Song s, boolean current) {
        float r = dp(10);
        tmp.reset();
        tmp.addRoundRect(coverRect, r, r, Path.Direction.CW);

        Bitmap bm = s.cover;
        if (bm != null && !bm.isRecycled()) {
            int save = c.save();
            c.clipPath(tmp);
            p.reset(); p.setFilterBitmap(true);
            Draw.bitmapCrop(c, bm, coverRect, p);
            c.restoreToCount(save);
        } else {
            // 占位：主题渐变 + 音符
            p.reset(); p.setStyle(Paint.Style.FILL);
            int c1 = Hct.blendLab(t.primaryContainer, t.surface, 0.2f);
            int c2 = Hct.blendLab(t.tertiaryContainer, t.surface, 0.35f);
            p.setShader(new LinearGradient(coverRect.left, coverRect.top, coverRect.right, coverRect.bottom,
                    new int[]{c1, c2}, null, Shader.TileMode.CLAMP));
            c.drawPath(tmp, p);
            p.setShader(null);
            RectF ib = new RectF(coverRect);
            ib.inset(coverRect.width() * 0.30f, coverRect.height() * 0.30f);
            Icons.draw(c, "note", ib, Hct.withAlpha(t.onPrimaryContainer, 0.55f), 1f, p);
            // 异步补图
            requestCover(s);
        }
        if (current) Draw.stroke(c, tmp, Hct.withAlpha(t.primary, 0.55f), dp(1.5f), p);
    }

    private static RectF centerCrop(Bitmap b, RectF dst) {
        float bw = b.getWidth(), bh = b.getHeight();
        float scale = Math.max(dst.width() / bw, dst.height() / bh);
        float w = dst.width() / scale, h = dst.height() / scale;
        float l = (bw - w) / 2f, tp = (bh - h) / 2f;
        return new RectF(l, tp, l + w, tp + h);
    }

    /** 可见行的封面按需异步加载 */
    private void requestCover(final Song s) {
        if (s.cover != null || s.id < 0) return;
        if (s.loading) return;
        s.loading = true;
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = Library.coverSync(getContext(), s, 220);
                post(new Runnable() { @Override public void run() {
                    s.loading = false;
                    if (b != null) { s.cover = b; invalidate(); }
                }});
            }
        });
    }

    /** 行内律动条（表示正在播放） */
    private void drawEqualizer(Canvas c, Tokens t, float cx, float cy) {
        float barW = dp(2.4f), gap = dp(3f);
        for (int i = 0; i < 3; i++) {
            float ph = beat * (float) Math.PI * 2 + i * 1.3f;
            float amp = playing ? 1f : 0.25f;
            float hh = dp(3.5f) + (float) Math.abs(Math.sin(ph)) * dp(10f) * amp;
            float x = cx - (barW + gap);
            x += i * (barW + gap);
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setColor(t.primary);
            RectF bar = new RectF(x - barW / 2, cy - hh / 2, x + barW / 2, cy + hh / 2);
            c.drawRoundRect(bar, barW / 2, barW / 2, p);
        }
    }

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

    // ── 触摸 ────────────────────────────────────────────────────────────────
    private int rowAt(float y) {
        float rh = rowH();
        int i = (int) ((y + scrollY + overscroll) / rh);
        return (i >= 0 && i < data.size()) ? i : -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        float x = e.getX(), y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; lastY = y;
                dragging = true; moved = false; velocity = 0;
                pressed = rowAt(y);
                pressAnim = 0f;
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = y - lastY;
                if (!moved && Math.abs(y - downY) > touchSlop) { moved = true; pressed = -1; }
                if (moved) {
                    scrollY -= dy;
                    // 边缘回弹
                    if (scrollY < 0) { overscroll = scrollY; scrollY = 0; }
                    else if (scrollY > maxScroll) { overscroll = scrollY - maxScroll; scrollY = maxScroll; }
                    else overscroll = 0;
                }
                lastY = y;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    velocity = -vt.getYVelocity();
                    // 边界方向不做惯性
                    if ((scrollY <= 0 && velocity < 0) || (scrollY >= maxScroll && velocity > 0)) velocity = 0;
                    vt.recycle(); vt = null;
                }
                if (!moved) {
                    int i = rowAt(y);
                    if (i >= 0 && i == pressed && cb != null) {
                        Ui.hapticLight(this);
                        cb.onPick(data, i);
                    }
                }
                pressed = -1;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** 滚到正在播放项 */
    public void scrollToCurrent() {
        int i = -1;
        for (int k = 0; k < data.size(); k++) if (data.get(k).id == currentId) { i = k; break; }
        if (i < 0) return;
        float target = i * rowH() - getHeight() / 2f + rowH() / 2f;
        ValueAnimator va = ValueAnimator.ofFloat(scrollY, Math.max(0, Math.min(maxScroll, target)));
        va.setDuration(Theme.dur(560));
        va.setInterpolator(Theme.EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                scrollY = (float) a.getAnimatedValue();
                invalidate();
            }
        });
        va.start();
    }

    // ── PlayerService.Listener ──────────────────────────────────────────────
    @Override public void onSongChanged(Song s, int index) {
        currentId = s != null ? s.id : -1;
        invalidate();
    }
    @Override public void onPlayStateChanged(boolean pl) { playing = pl; invalidate(); }
    @Override public void onProgress(long pos, long dur) {}
    @Override public void onQueueChanged() {}
    @Override public void onRepeatShuffleChanged(int repeat, int shuffle) {}
}
