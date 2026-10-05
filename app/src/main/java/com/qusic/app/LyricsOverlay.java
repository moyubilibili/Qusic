package com.qusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 桌面歌词悬浮窗（MD3 胶囊）。
 *
 * <p>造型遵循 Material Design 3 的**浮动胶囊**：
 *
 * <pre>
 *   ╭──────────────────────────────╮
 *   │ ▍故事的小黄花                 │  ← 当前行：强调色竖条 + onSurface 粗体
 *   │   从出生那年就飘着             │  ← 下一行：onSurfaceVariant 淡显
 *   ╰──────────────────────────────╯
 * </pre>
 *
 * <ul>
 *   <li>容器：24dp 圆角 {@code surfaceContainerHigh}，带柔和投影与细描边</li>
 *   <li>左侧强调竖条跟随封面主色，并随节拍轻微呼吸</li>
 *   <li>换行时旧行上移淡出、新行下方浮现（不是硬切）</li>
 *   <li>单击展开控制条（播放/暂停、锁定、关闭），拖动可移动</li>
 *   <li>控件的显隐、位置都用属性动画，不是瞬变</li>
 * </ul>
 */
public class LyricsOverlay extends View {

    public interface OnAction { void onAction(int what); }
    public static final int ACTION_TOGGLE = 1, ACTION_CLOSE = 2, ACTION_LOCK = 3;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF();

    private String curLine = "";
    private String nextLine = "";
    private String prevLine = "";
    private float lineAnim = 1f;        // 0..1 换行进度
    private int mainLines = 1;          // 当前行占几行（1 或 2），决定胶囊高度
    private float breath;               // 呼吸相位
    private float controlsT;            // 0..1 控制条展开
    private boolean playing = true;
    private boolean locked;

    private int accent = 0xFF6750A4;
    private final OnAction cb;

    // 触摸
    private float downX, downY;
    private float lastRawX, lastRawY;
    private boolean moved;
    private int touchSlop;

    public LyricsOverlay(Context c, OnAction cb) {
        super(c);
        this.cb = cb;
        setLayerType(LAYER_TYPE_HARDWARE, null);
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        setAlpha(0f);
        animate().alpha(1f).setDuration(220).start();
    }

    public void setAccent(int color) {
        if (color != 0 && color != accent) { accent = color; invalidate(); }
    }

    public void setPlaying(boolean b) { playing = b; invalidate(); }
    public boolean locked() { return locked; }
    public void setLocked(boolean b) { locked = b; invalidate(); }

    /** 拖动回调：参数是本次移动的增量（像素） */
    public interface OnDragBy { void onDragBy(float dx, float dy); }
    private OnDragBy dragCb;
    public void setOnDragBy(OnDragBy c) { this.dragCb = c; }

    /** 设置正在唱的那一句；变化时触发过渡动画 */
    public void setLines(String prev, String cur, String next) {
        boolean changed = cur != null && !cur.equals(curLine);
        prevLine = this.curLine;
        this.curLine = cur == null ? "" : cur;
        this.nextLine = next == null ? "" : next;
        if (changed) lineAnim = 0f;
        invalidate();
    }

    /** 由外部按帧驱动（呼吸 + 换行过渡） */
    public void tick(float dt) {
        boolean need = false;
        breath += dt * 0.9f;
        if (breath > 1f) breath -= 1f;
        need = true;
        if (lineAnim < 1f) { lineAnim = Math.min(1f, lineAnim + dt * 3.2f); need = true; }
        float target = controlsT > 0.5f ? 1f : 0f;
        float shown = controlsVisible ? 1f : 0f;
        if (Math.abs(controlsT - shown) > 0.002f) {
            controlsT += (shown - controlsT) * Math.min(1f, dt * 12f);
            need = true;
        }
        if (need) invalidate();
    }

    private boolean controlsVisible;

    public void toggleControls() {
        controlsVisible = !controlsVisible;
        invalidate();
    }

    /** 给阴影留的边距 —— 不留的话阴影会被视图边界切掉，看起来像抠图没扣干净 */
    private float pad() { return dp(12); }

    /** 当前内容需要的高度（含阴影留白） */
    public int desiredHeight() {
        return (int) (contentHeight() + pad() * 2);
    }

    /** 胶囊本身的高度 */
    private float contentHeight() {
        return mainLines == 2 ? dp(80) : dp(58);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        setMeasuredDimension(w, desiredHeight());
    }

    /** 内容高度变化时通知外部调整窗口（返回 true 表示变了） */
    public boolean heightDirty() {
        int want = desiredHeight();
        if (want != lastHeight) { lastHeight = want; return true; }
        return false;
    }
    private int lastHeight;

    @Override protected void onDraw(Canvas c) {
        Tokens t = Theme.t();
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float r = dp(22);
        float pd = pad();
        box.set(pd, pd, w - pd, h - pd);

        // ── 容器：surfaceContainerHigh + 投影 ──
        // 两个要点：
        // 1. 填充必须**完全不透明**。半透明时 shadowLayer 会从填充里透上来，
        //    在胶囊内侧糊出一圈脏边（之前「像抠图没扣全」就是这个）。
        // 2. 胶囊要在视图内缩，给阴影留出渲染空间，否则阴影被视图边界切掉。
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(dp(9), 0, dp(3.5f), 0x40000000);
        p.setColor(t.surfaceContainerHigh);
        c.drawRoundRect(box, r, r, p);
        p.clearShadowLayer();

        // 细描边，深色背景下也能看清边界
        p.reset();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(1));
        p.setColor(Hct.withAlpha(t.outlineVariant, 0.5f));
        c.drawRoundRect(box, r, r, p);

        // 顶部的强调色微光
        p.reset();
        p.setShader(new LinearGradient(0, 0, w * 0.55f, 0,
                new int[]{Hct.withAlpha(accent, 0.16f), 0x00000000},
                null, Shader.TileMode.CLAMP));
        c.drawRoundRect(box, r, r, p);
        p.setShader(null);

        // ── 左侧强调竖条（随呼吸轻微伸缩）──
        float pulse = 0.5f + 0.5f * (float) Math.sin(breath * Math.PI * 2);
        float barH = h * (0.34f + 0.08f * pulse);
        float barCy = h / 2f;
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setColor(Hct.withAlpha(accent, playing ? 1f : 0.45f));
        c.drawRoundRect(new RectF(dp(11), barCy - barH / 2, dp(11) + dp(3.5f), barCy + barH / 2),
                dp(2), dp(2), p);

        // 右侧给控制条预留空间（控制条展开时让位）
        float ctrlW = dp(96) * controlsT;
        float textLeft = dp(24);
        float textRight = w - dp(18) - ctrlW;

        int save = c.save();
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        c.clipPath(clip);

        // ── 文本排版：自动缩放 + 必要时折两行 ──
        // 之前直接 ellipsize 截断，长句必然显示不全。
        // 现在先把字号逐级调小试着塞进一行，实在塞不下才折成两行（胶囊会加高）。
        float contentLeft = textLeft;
        float contentRight = textRight;
        float textW = Math.max(dp(60), contentRight - contentLeft);

        float ea = ease(lineAnim);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(Ui.tfBold());

        // 逐级缩小找能放下的字号
        float[] sizes = {16.5f, 15.5f, 14.5f, 13.5f, 12.5f};
        float useSize = sizes[sizes.length - 1];
        for (float sz : sizes) {
            p.setTextSize(dp(sz));
            if (p.measureText(curLine) <= textW) { useSize = sz; break; }
        }
        p.setTextSize(dp(useSize));

        // 最小字号仍放不下 → 折两行
        String l1 = curLine, l2 = "";
        boolean two = false;
        if (p.measureText(curLine) > textW) {
            two = true;
            int cut = bestBreak(curLine, p, textW);
            l1 = curLine.substring(0, cut);
            l2 = curLine.substring(cut);
            // 第二行再缩一点，尽量放全
            float sz2 = useSize;
            while (sz2 > 11f) {
                p.setTextSize(dp(sz2));
                if (p.measureText(l2) <= textW) break;
                sz2 -= 0.5f;
            }
            useSize = sz2;
            p.setTextSize(dp(useSize));
        }
        mainLines = two ? 2 : 1;

        float contentH = contentHeight();
        float cy0 = pd + (two ? contentH * 0.32f : contentH * 0.46f) + useSize * 0.36f;

        // 旧行向上淡出
        if (prevLine.length() > 0 && ea < 0.98f && !two) {
            p.setColor(Hct.withAlpha(t.onSurface, 0.30f * (1f - ea)));
            c.drawText(shrink(prevLine, p, textW), contentLeft, cy0 - dp(11) * ea, p);
        }
        // 新行（第一行）
        p.setColor(Hct.withAlpha(t.onSurface, ea));
        c.drawText(l1, contentLeft, cy0 + dp(8) * (1f - ea), p);

        // 第二行
        if (two) {
            p.setColor(Hct.withAlpha(t.onSurface, 0.92f * ea));
            c.drawText(l2, contentLeft, cy0 + dp(useSize * 1.28f), p);
        }

        // 下一行：只在主歌词是单行时显示，避免太挤
        if (!two && nextLine.length() > 0) {
            p.setTypeface(Ui.tf());
            p.setTextSize(dp(11.5f));
            p.setColor(Hct.withAlpha(t.onSurfaceVariant, 0.55f * ea));
            c.drawText(shrink(nextLine, p, textW), contentLeft,
                    pd + contentH - dp(11), p);
        }
        c.restoreToCount(save);

        // ── 控制条（单击展开）──
        if (controlsT > 0.02f) {
            float a = controlsT;
            float cy = h / 2f;
            float r0 = dp(15);
            float x = w - dp(18) - r0 - dp(92) * (1f - a) * 0f;
            // 三个圆钮：播放/暂停、锁定、关闭
            drawIconBtn(c, t, w - dp(18) - r0 - dp(30) * 2, cy, r0,
                    playing ? "pause" : "play", a, 0);
            drawIconBtn(c, t, w - dp(18) - r0 - dp(30), cy, r0,
                    locked ? "lock" : "lock_open", a, locked ? 1 : 0);
            drawIconBtn(c, t, w - dp(18) - r0, cy, r0, "close", a, 0);
        }
    }

    private void drawIconBtn(Canvas c, Tokens t, float cx, float cy, float r,
                             String icon, float alpha, int active) {
        p.reset();
        p.setStyle(Paint.Style.FILL);
        p.setColor(active == 1
                ? Hct.withAlpha(t.primary, 0.18f * alpha)
                : Hct.withAlpha(t.surfaceContainerHighest, 0.9f * alpha));
        c.drawCircle(cx, cy, r, p);
        Icons.draw(c, icon, new RectF(cx - r * 0.52f, cy - r * 0.52f, cx + r * 0.52f, cy + r * 0.52f),
                Hct.withAlpha(active == 1 ? t.primary : t.onSurfaceVariant, alpha), 1f, p);
    }

    /** 找折行点：优先在空格处断，中文则按字断，避免把词劈开 */
    private static int bestBreak(String s, Paint p, float max) {
        int lo = 1, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid)) <= max) lo = mid; else hi = mid - 1;
        }
        int cut = Math.max(1, lo);
        // 往回找个空格，让断点自然一点
        for (int i = cut; i > cut - 8 && i > 1; i--) {
            if (s.charAt(i - 1) == ' ') return i;
        }
        return cut;
    }

    /** 缩字号直到放得下（下限 10sp），仍放不下才截断 */
    private static String shrink(String s, Paint p, float max) {
        if (s == null) return "";
        float base = p.getTextSize();
        float sz = base;
        while (sz > 10f) {
            if (p.measureText(s) <= max) return s;
            sz -= 0.5f;
            p.setTextSize(sz);
        }
        String r = ellipsize(s, p, max);
        p.setTextSize(base);
        return r;
    }

    private static float ease(float x) { return x * x * (3 - 2 * x); }

    private static String ellipsize(String s, Paint p, float max) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (p.measureText(s.substring(0, mid) + "…") <= max) lo = mid; else hi = mid - 1;
        }
        return s.substring(0, Math.max(0, lo)) + "…";
    }

    // ── 手势：拖动移动、单击展开控制 ──
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastRawX = e.getRawX();
                downY = lastRawY = e.getRawY();
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float rx = e.getRawX(), ry = e.getRawY();
                if (Math.abs(rx - downX) > touchSlop
                        || Math.abs(ry - downY) > touchSlop) moved = true;
                if (moved && !locked && dragCb != null) {
                    dragCb.onDragBy(rx - lastRawX, ry - lastRawY);
                }
                lastRawX = rx; lastRawY = ry;
                return true;
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    // 点在控制条上？
                    float x = e.getX(), y = e.getY();
                    float h = getHeight(), w = getWidth();
                    if (controlsT > 0.5f && Math.abs(y - h / 2f) < dp(18)) {
                        float r0 = dp(15);
                        float[] xs = {w - dp(18) - r0 - dp(60), w - dp(18) - r0 - dp(30), w - dp(18) - r0};
                        for (int i = 0; i < 3; i++) {
                            if (Math.abs(x - xs[i]) <= r0 + dp(4)) {
                                if (cb != null) {
                                    cb.onAction(i == 0 ? ACTION_TOGGLE
                                            : i == 1 ? ACTION_LOCK : ACTION_CLOSE);
                                }
                                return true;
                            }
                        }
                    }
                    toggleControls();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }



    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
