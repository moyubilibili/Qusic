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

    /** 长按某一行的回调（用于「添加到歌单」这类操作） */
    public interface OnLongPick { void onLongPick(List<Song> visible, int index); }

    /** 多选状态变化的回调（选择数量变了、或退出了多选） */
    public interface OnSelectionChanged { void onSelectionChanged(int count, boolean selecting); }

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

    // ── 多选 ──────────────────────────────────────────────────────────────
    /** 是否处于多选模式 */
    private boolean selecting = false;
    /** 已选中的歌曲 id */
    private final java.util.LinkedHashSet<Long> selected = new java.util.LinkedHashSet<>();
    private OnSelectionChanged selCb;
    private OnPick cb;

    private float scrollY = 0f;       // 当前滚动偏移（px）
    private float maxScroll = 0f;
    private float overscroll = 0f;    // 超出边界的偏移（正=顶部下拉，负=底部上拉）
    private float overV = 0f;         // overscroll 的速度，弹簧用
    /** 未阻尼的原始超出量。必须单独记 —— 拿阻尼后的值当基数会让阻尼失效 */
    private float overRaw = 0f;
    /** 顶部渐隐的渐变对象缓存（每帧 new 会制造 GC 抖动） */
    private LinearGradient fadeShader;
    private int fadeKey = 0;
    /** 无封面时占位渐变的缓存 */
    private LinearGradient phShader;
    private Tokens phTheme;

    private float downY, downX;
    private float lastY;
    private boolean dragging, moved;
    private float touchSlop;
    private VelocityTracker vt;
    private float velocity;

    /**
     * 惯性交给系统的 {@link android.widget.OverScroller}。
     *
     * <p>之前是自己写指数摩擦，调来调去都不对 —— 而「关于」等页面用的是系统
     * ScrollView，手感天然一致。既然系统已经有一流的滑动物理，就没必要重造：
     * 这样曲库的滑动跟其他页面**完全一样**。
     *
     * <p>OverScroller 还顺带处理了「冲过边界再弹回来」（overY 参数），
     * 比手写弹簧更自然。
     */
    private android.widget.OverScroller flinger;
    private boolean flinging = false;
    private int pressed = -1;
    private float pressAnim = 0f;

    private long currentId = -1;
    private boolean playing;
    private float beat = 0f;
    private float entrance = 0f;

    /** 高亮关键字（搜索页用） */
    private String highlight;

    private float lastFrame;

    private OnLongPick longCb;
    /** 长按判定 */
    private boolean longFired;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (!dragging || moved || pressed < 0) return;
            longFired = true;
            Ui.hapticStrong(SongListView.this);
            // 长按 = 进入多选，并选中这一首。
            // 这样「加一首」和「加一批」是同一个入口 —— 选一首就是加一首。
            if (selecting) {
                // 已经在多选里了：长按当成反选
                Song s = data.get(pressed);
                if (selected.contains(s.id)) selected.remove(s.id);
                else selected.add(s.id);
                notifySelection();
            } else {
                enterSelection(data.get(pressed));
            }
            pressed = -1;
            invalidate();
        }
    };

    public SongListView(Context c) {
        super(c);
        touchSlop = Ui.dp(c, 8);
        flinger = new android.widget.OverScroller(c);
        // 这个 View 自己画所有内容，不需要系统帮它做硬件层缓存。
        // 之前这里是 LAYER_TYPE_HARDWARE —— 那是给「内容不变、只做位移/透明」
        // 的 View 用的；列表内容每次滚动都在变，缓存层每帧都要重画一遍，
        // 反而多一次离屏合成。
        setLayerType(LAYER_TYPE_NONE, null);
        startLoop();
    }

    public void setOnPick(OnPick c) { this.cb = c; }
    public void setOnLongPick(OnLongPick c) { this.longCb = c; }
    public void setOnSelectionChanged(OnSelectionChanged c) { this.selCb = c; }

    // ── 多选 API ───────────────────────────────────────────────────────────
    public boolean isSelecting() { return selecting; }
    public int selectedCount() { return selected.size(); }

    /** 进入多选并选中这一首 */
    public void enterSelection(Song first) {
        selecting = true;
        selected.clear();
        if (first != null) selected.add(first.id);
        startLoop();
        invalidate();
        notifySelection();
    }

    public void exitSelection() {
        if (!selecting && selected.isEmpty()) return;
        selecting = false;
        selected.clear();
        invalidate();
        notifySelection();
    }

    /** 全选当前列表 */
    public void selectAll() {
        selected.clear();
        for (Song s : data) selected.add(s.id);
        invalidate();
        notifySelection();
    }

    /** 反选（全选状态下再点就是取消全选） */
    public void toggleSelectAll() {
        if (selected.size() >= data.size()) { selected.clear(); }
        else { for (Song s : data) selected.add(s.id); }
        invalidate();
        notifySelection();
    }

    /** 当前选中的歌曲（按列表顺序） */
    public List<Song> selectedSongs() {
        List<Song> out = new ArrayList<>();
        for (Song s : data) if (selected.contains(s.id)) out.add(s);
        return out;
    }

    private void notifySelection() {
        if (selCb != null) selCb.onSelectionChanged(selected.size(), selecting);
    }

    public void setData(List<Song> list) {
        List<Song> next = list != null ? list : new ArrayList<Song>();
        // 内容没变就别重置滚动位置，否则任何一次 setData 都会让列表跳回顶部
        boolean same = sameContent(data, next);
        data = next;
        if (!same) {
            scrollY = 0;
            overscroll = 0;
            entrance = 0f;
            startLoop();
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
                startLoop();
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
            if (playing) startLoop();
        }
        startLoop();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PlayerService s = PlayerService.instance();
        if (s != null) s.removeListener(this);
    }

    private boolean looping = false;

    /**
     * 启动帧循环。
     *
     * <p>**只在真正需要动画时运行**。之前的写法是无条件 postOnAnimation，
     * 于是即使列表完全静止，UI 线程也在 120Hz 空转，跟滚动本身抢时间片 ——
     * 这是「一卡一卡」的一个来源。
     */
    private void startLoop() {
        if (looping) return;
        looping = true;
        postOnAnimation(loop);
    }

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
            // 惯性滑动 —— 交给系统 OverScroller
            if (!dragging && flinging) {
                if (flinger.computeScrollOffset()) {
                    // 惯性期间 scrollY 直接就是位置（含冲过边界的部分），
                    // 所以 overscroll 归零，避免两套偏移叠加。
                    scrollY = flinger.getCurrY();
                    overscroll = 0;
                    overV = 0;
                    need = true;
                } else {
                    flinging = false;
                    scrollY = Math.max(0, Math.min(maxScroll, scrollY));
                }
            }

            // 回弹：弹簧 + 阻尼（欠阻尼 → 会轻微过冲再稳下来，比单纯的指数衰减自然）
            if (!dragging && maxScroll > 0
                    && (Math.abs(overscroll) > 0.5f || Math.abs(overV) > 1f)) {
                final float K = 240f;    // 刚度
                final float C = 24f;     // 阻尼系数
                overV += (-K * overscroll - C * overV) * dt;
                overscroll += overV * dt;
                if (Math.abs(overscroll) < 0.5f && Math.abs(overV) < 8f) {
                    overscroll = 0; overV = 0;
                }
                // 限幅，防止极端情况下拉太远
                float lim = Math.max(getHeight(), 1) * 0.55f;
                if (overscroll > lim) { overscroll = lim; overV = 0; }
                if (overscroll < -lim) { overscroll = -lim; overV = 0; }
                need = true;
            }
            // 按压动画
            if (pressed >= 0 && pressAnim < 1f) { pressAnim = Math.min(1f, pressAnim + dt * 8f); need = true; }
            if (pressed < 0 && pressAnim > 0f) { pressAnim = Math.max(0f, pressAnim - dt * 8f); need = true; }

            if (need) {
                invalidate();
                // 对齐 vsync，别加 16ms 延迟 ——
                // postOnAnimationDelayed(f, 16) 是「下一帧再等 16ms」，
                // 实际间隔变成 ~32ms，滚动就只有 30fps。
                postOnAnimation(loop);
            } else {
                // 没有要动的东西了 → 停掉循环，把 UI 线程让出来
                looping = false;
            }
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

        // 边缘渐隐（渐变对象缓存起来 —— 每帧 new 一个会持续制造垃圾）
        p.reset();
        if (fadeShader == null || fadeKey != t.surface) {
            fadeKey = t.surface;
            fadeShader = new LinearGradient(0, 0, 0, dp(24),
                    new int[]{t.surface, Hct.withAlpha(t.surface, 0f)},
                    null, Shader.TileMode.CLAMP);
        }
        p.setShader(fadeShader);
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
        boolean checked = selecting && selected.contains(s.id);

        rowRect.set(dp(10), top + dy + dp(2), getWidth() - dp(10), top + dy + rh - dp(2));

        // 多选时：勾选框占走左边一块，整行内容右移
        final float checkW = selecting ? dp(46) : 0f;
        if (selecting) rowRect.left += checkW;

        // ★ 性能关键：saveLayer 会给这一行**单独分配一块离屏缓冲**，
        // 每个可见行每帧一次 —— 8 行就是每帧 8 块，必掉帧。
        // 而入场上浮动画结束后 e 恒为 1，alpha=255 的图层完全是多余的。
        // 所以只在 e 真的小于 1（入场动画进行中）时才开图层。
        int save = c.save();
        final boolean needLayer = e < 0.995f;
        if (needLayer) {
            c.saveLayerAlpha(rowRect.left - dp(4), rowRect.top - dp(4),
                    rowRect.right + dp(4), rowRect.bottom + dp(4),
                    (int) (255 * e), Canvas.ALL_SAVE_FLAG);
        }

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

        // ── 多选勾选框 ──
        if (selecting) {
            float r = dp(10);
            float cx = rowRect.left - checkW / 2f;
            float cy = rowRect.centerY();
            p.reset(); p.setStyle(Paint.Style.FILL);
            if (checked) {
                p.setColor(t.primary);
                c.drawCircle(cx, cy, r, p);
                // 对勾：两笔
                p.setColor(t.onPrimary);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(2));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setStrokeJoin(Paint.Join.ROUND);
                tmp.reset();
                tmp.moveTo(cx - r * 0.42f, cy + r * 0.02f);
                tmp.lineTo(cx - r * 0.10f, cy + r * 0.34f);
                tmp.lineTo(cx + r * 0.46f, cy - r * 0.34f);
                c.drawPath(tmp, p);
                p.setStyle(Paint.Style.FILL);
            } else {
                p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.35f));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.6f));
                c.drawCircle(cx, cy, r, p);
                p.setStyle(Paint.Style.FILL);
            }
        }

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

        if (needLayer) c.restore();
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
            // 占位：主题渐变 + 音符（渐变缓存，封面加载期间会连续画很多帧）
            p.reset(); p.setStyle(Paint.Style.FILL);
            // blendLab 走 Lab 色彩空间往返（含 cbrt），不便宜。
            // 之前把它的「结果」当缓存键，等于每帧照样算一遍 —— 缓存了个寂寞。
            // 改成按 Tokens 实例判断：主题不变时 Tokens 是同一个对象。
            if (phShader == null || phTheme != t) {
                phTheme = t;
                int c1 = Hct.blendLab(t.primaryContainer, t.surface, 0.2f);
                int c2 = Hct.blendLab(t.tertiaryContainer, t.surface, 0.35f);
                phShader = new LinearGradient(coverRect.left, coverRect.top,
                        coverRect.right, coverRect.bottom,
                        new int[]{c1, c2}, null, Shader.TileMode.CLAMP);
            }
            p.setShader(phShader);
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
                startLoop();          // 触摸就要开始动，把循环拉起来
                downX = x; downY = y; lastY = y;
                dragging = true; moved = false; velocity = 0;
                pressed = rowAt(y);
                pressAnim = 0f;
                longFired = false;
                removeCallbacks(longPress);
                if (longCb != null && pressed >= 0) postDelayed(longPress, 480);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dy = y - lastY;
                if (!moved && Math.abs(y - downY) > touchSlop) {
                    moved = true; pressed = -1;
                    removeCallbacks(longPress);          // 开始滚动就不算长按了
                }
                if (moved) {
                    scrollY -= dy;

                    // 内容没超出一屏 → 根本不该能拖，更不该回弹。
                    // （标准 Android 的 OVER_SCROLL_IF_CONTENT_SCROLLS 就是这个行为）
                    if (maxScroll <= 0) {
                        scrollY = 0; overscroll = 0; overRaw = 0; overV = 0;
                    } else if (scrollY < 0) {
                        overRaw += dy;                       // 原始超出量累加
                        if (overRaw < 0) overRaw = 0;
                        scrollY = 0; overV = 0;
                        overscroll = -rubber(overRaw);       // 显示时才阻尼
                    } else if (scrollY > maxScroll) {
                        overRaw += dy;
                        if (overRaw > 0) overRaw = 0;
                        scrollY = maxScroll; overV = 0;
                        overscroll = rubber(-overRaw);
                    } else {
                        scrollY = Math.max(0, Math.min(maxScroll, scrollY));
                        overscroll = 0; overRaw = 0; overV = 0;
                    }
                }
                lastY = y;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                dragging = false;
                removeCallbacks(longPress);
                if (longFired) {           // 长按已触发，别再当成点击
                    longFired = false;
                    pressed = -1;
                    invalidate();
                    return true;
                }
                overRaw = 0;   // 拖拽结束，原始量交给弹簧
                startLoop();   // 接下来是惯性/回弹，循环必须醒着
                if (vt != null) {
                    vt.computeCurrentVelocity(1000);
                    // 约定：上滑 getYVelocity() < 0；scrollY 增大 = 内容向上。
                    // 两者同号，所以**不能取反**。
                    velocity = vt.getYVelocity();
                    vt.recycle(); vt = null;
                }

                if (maxScroll > 0) {
                    if (overscroll != 0f || overV != 0f) {
                        // 拖拽时被拉出了边界：把「逻辑位置」（含超出量）交给
                        // springBack 弹回去。overscroll 为负=顶部拉出，
                        // 为正=底部拉出，所以相加就得到界外的真实位置。
                        overV = 0;
                        scrollY = scrollY + overscroll;
                        overscroll = 0;
                        flinger.springBack(0, (int) scrollY, 0, 0, 0, (int) maxScroll);
                        flinging = true;
                    } else if (Math.abs(velocity) > 30f) {
                        // 正常甩动：交给系统物理。
                        //
                        // ★ 注意 velocity 要取反：
                        //   OverScroller 的 velocityY 正值 = 朝 Y 增大的方向；
                        //   而本 View 的 scrollY 增大 = 内容向上 = 手指上滑
                        //   = getYVelocity() 为负。两者约定相反，所以传 -velocity。
                        //
                        // 最后一个参数 overY 是允许冲出边界的距离，
                        // OverScroller 会自己冲出去再弹回来。
                        flinger.fling(0, (int) scrollY, 0, (int) -velocity,
                                0, 0, 0, (int) maxScroll, 0, Ui.px(getContext(), 90));
                        flinging = true;
                    }
                }
                velocity = 0;
                if (!moved) {
                    int i = rowAt(y);
                    if (i >= 0 && i == pressed) {
                        Ui.hapticLight(this);
                        if (selecting) {
                            // 多选模式下点击 = 勾选/取消，不播放
                            Song s = data.get(i);
                            if (selected.contains(s.id)) selected.remove(s.id);
                            else selected.add(s.id);
                            invalidate();
                            notifySelection();
                        } else if (cb != null) {
                            cb.onPick(data, i);
                        }
                    }
                }
                pressed = -1;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /**
     * 橡皮筋阻尼：拉得越远，同样的手指位移换来的偏移越小。
     *
     * <p>用 {@code x / (1 + x/L)} 这种渐进式而不是简单地乘个系数 ——
     * 线性阻尼拉到很远还是会一直跟手，手感发飘。
     */
    private float rubber(float x) {
        if (x <= 0) return 0;
        // L 越大越松。之前 0.75 偏紧，拉半天不动；0.42 大约「拉 300px 出 200px」，
        // 既明确「到头了」，又不至于像卡住。
        float L = Math.max(getHeight(), 1) * 0.42f;
        return L * (x / (L + x));
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
        startLoop();
        invalidate();
    }
    @Override public void onPlayStateChanged(boolean pl) { playing = pl; invalidate(); }
    @Override public void onProgress(long pos, long dur) {}
    @Override public void onQueueChanged() {}
    @Override public void onRepeatShuffleChanged(int repeat, int shuffle) {}
}
