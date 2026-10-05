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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 首页：问候语 + 正在播放 hero 卡片 + 快捷入口 + 最近导入。
 *
 * <p>hero 卡片是自绘组件，带封面主色光晕与播放律动；
 * 曲库为空时显示导入引导而不是假装有内容。
 */
public class HomePage {

    public interface OnOpenTab { void open(int tab); }

    private final MainActivity act;
    private View root;
    private HeroCard hero;
    private LinearLayout recentBox;
    private TextView greeting;
    private Runnable onOpenPlayer;
    private OnOpenTab onOpenTab;

    public HomePage(MainActivity a) {
        this.act = a;
        build();
    }

    public View view() { return root; }
    public void setOnOpenPlayer(Runnable r) { this.onOpenPlayer = r; }
    public void setOnOpenTab(OnOpenTab t) { this.onOpenTab = t; }

    private void build() {
        Tokens t = Theme.t();
        Context c = act;

        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(t.surface);
        root = wrap;

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        wrap.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout col = Ui.column(c);
        int pad = Ui.px(c, 20);
        col.setPadding(pad, Ui.statusBarHeight(c) + Ui.px(c, 22), pad, act.contentBottomInset());
        sv.addView(col);

        greeting = new TextView(c);
        greeting.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        greeting.setTextSize(13);
        greeting.setTypeface(Ui.tfMed());
        greeting.setText(greetingText());
        col.addView(greeting);

        TextView brand = new TextView(c);
        brand.setText("Qusic");
        brand.setTextColor(t.onSurface);
        brand.setTextSize(34);
        brand.setTypeface(Ui.tfBlack());
        col.addView(brand);

        TextView tagline = new TextView(c);
        tagline.setText("你的本地音乐，放得体面一点");
        tagline.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        tagline.setTextSize(13);
        tagline.setPadding(0, Ui.px(c, 2), 0, Ui.px(c, 18));
        col.addView(tagline);

        hero = new HeroCard(c);
        hero.setOnOpen(new Runnable() { @Override public void run() {
            if (onOpenPlayer != null) onOpenPlayer.run();
        }});
        hero.setOnImport(new Runnable() { @Override public void run() {
            if (onOpenTab != null) onOpenTab.open(1);
        }});
        col.addView(hero, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(c, 208)));

        col.addView(sectionTitle(c, "快捷入口", t));

        LinearLayout quick = Ui.row(c);
        quick.setPadding(0, Ui.px(c, 2), 0, Ui.px(c, 16));
        quick.addView(chip(c, "曲库", "list", t, new Runnable() {
            @Override public void run() { if (onOpenTab != null) onOpenTab.open(1); }
        }), weighted());
        quick.addView(space(c, 8));
        quick.addView(chip(c, "搜索", "search", t, new Runnable() {
            @Override public void run() { if (onOpenTab != null) onOpenTab.open(2); }
        }), weighted());
        quick.addView(space(c, 8));
        quick.addView(chip(c, "配色", "palette", t, new Runnable() {
            @Override public void run() {
                Theme.setSeed(Tokens.SEEDS[new java.util.Random().nextInt(Tokens.SEEDS.length)]);
            }
        }), weighted());
        quick.addView(space(c, 8));
        quick.addView(chip(c, Theme.isDark() ? "浅色" : "深色",
                Theme.isDark() ? "sun" : "moon", t,
                new Runnable() { @Override public void run() { Theme.toggleDark(); } }), weighted());
        col.addView(quick);

        col.addView(sectionTitle(c, "最近导入", t));
        recentBox = Ui.column(c);
        col.addView(recentBox);

        refresh();
    }

    private View sectionTitle(Context c, String s, Tokens t) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(t.onSurface);
        tv.setTextSize(16);
        tv.setTypeface(Ui.tfBold());
        tv.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 10));
        return tv;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private View space(Context c, int dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(Ui.px(c, dp), 1));
        return v;
    }

    private View chip(Context c, String label, String icon, Tokens t, final Runnable action) {
        Tokens tk = Theme.t();
        LinearLayout box = Ui.column(c);
        box.setGravity(Gravity.CENTER);
        // 上下都留足空间，否则标签会被卡片下沿切掉
        box.setPadding(Ui.px(c, 4), Ui.px(c, 15), Ui.px(c, 4), Ui.px(c, 15));
        box.setMinimumHeight(Ui.px(c, 90));

        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(tk.surfaceContainerHigh);
        g.setCornerRadius(Ui.px(c, 18));
        g.setStroke(Ui.px(c, 1), Hct.withAlpha(tk.outlineVariant, 0.6f));
        box.setBackground(g);

        IconView iv = new IconView(c, icon, tk.primary);
        box.addView(iv, new LinearLayout.LayoutParams(Ui.px(c, 26), Ui.px(c, 26)));

        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(tk.onSurface);
        tv.setTextSize(11.5f);
        tv.setTypeface(Ui.tfMed());
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(0, Ui.px(c, 7), 0, 0);
        box.addView(tv);

        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (action != null) action.run();
            }
        });
        attachPress(box);
        return box;
    }

    private static void attachPress(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: view.setAlpha(0.72f); break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: view.setAlpha(1f); break;
                }
                return false;
            }
        });
    }

    private String greetingText() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h < 6) return "夜深了";
        if (h < 11) return "早上好";
        if (h < 14) return "中午好";
        if (h < 18) return "下午好";
        if (h < 23) return "晚上好";
        return "夜深了";
    }

    public void refresh() {
        if (hero != null) hero.refresh();
        if (recentBox == null) return;
        Context c = act;
        Tokens t = Theme.t();
        recentBox.removeAllViews();

        List<Song> all = Library.songs();
        if (all.isEmpty()) {
            TextView tv = new TextView(c);
            tv.setText("曲库是空的 —— 去「曲库」页导入你自己的音乐吧");
            tv.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
            tv.setTextSize(13);
            tv.setPadding(0, Ui.px(c, 8), 0, Ui.px(c, 8));
            recentBox.addView(tv);
            return;
        }
        int n = Math.min(6, all.size());
        for (int i = 0; i < n; i++) {
            recentBox.addView(recentRow(c, t, all.get(all.size() - 1 - i)));
        }
        TextView more = new TextView(c);
        more.setText("查看全部 " + all.size() + " 首 →");
        more.setTextColor(t.primary);
        more.setTextSize(13);
        more.setTypeface(Ui.tfMed());
        more.setPadding(0, Ui.px(c, 12), 0, 0);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (onOpenTab != null) onOpenTab.open(1);
            }
        });
        recentBox.addView(more);
    }

    private View recentRow(Context c, Tokens t, final Song s) {
        LinearLayout row = Ui.row(c);
        row.setPadding(0, Ui.px(c, 7), 0, Ui.px(c, 7));

        CoverThumb thumb = new CoverThumb(c, s);
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.px(c, 46), Ui.px(c, 46)));

        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.px(c, 12), 0, 0, 0);
        TextView title = new TextView(c);
        title.setText(s.title);
        title.setTextColor(t.onSurface);
        title.setTextSize(14);
        title.setTypeface(Ui.tfMed());
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);

        TextView sub = new TextView(c);
        sub.setText(s.subtitle() + " · " + s.durationText());
        sub.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        sub.setTextSize(11.5f);
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(sub);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                PlayerService ps = PlayerService.instance();
                if (ps != null) ps.playSong(s);
            }
        });
        return row;
    }

    public void onShow() { if (hero != null) hero.refresh(); }
    public void onSongChanged() { if (hero != null) hero.refresh(); }

    // ── 小图标 View ─────────────────────────────────────────────────────────
    public static class IconView extends View {
        private final String name;
        private int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        public IconView(Context c, String name, int color) {
            super(c);
            this.name = name; this.color = color;
        }
        public void setColor(int c) { color = c; invalidate(); }

        @Override protected void onDraw(Canvas cv) {
            box.set(0, 0, getWidth(), getHeight());
            Icons.draw(cv, name, box, color, 1f, p);
        }
    }

    /** 圆角封面缩略图（导入制：封面来自音频内嵌图片） */
    public static class CoverThumb extends View {
        private final Song song;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();

        public CoverThumb(Context c, Song s) {
            super(c);
            this.song = s;
            Library.loadCoverAsync(c, s, 220, new Runnable() {
                @Override public void run() { invalidate(); }
            });
        }

        @Override protected void onDraw(Canvas c) {
            Tokens t = Theme.t();
            r.set(0, 0, getWidth(), getHeight());
            float rad = getWidth() * 0.26f;
            path.reset();
            path.addRoundRect(r, rad, rad, Path.Direction.CW);

            Bitmap b = song.cover;
            if (b != null && !b.isRecycled()) {
                int save = c.save();
                c.clipPath(path);
                Draw.bitmapCrop(c, b, r, p);
                c.restoreToCount(save);
            } else {
                p.reset(); p.setStyle(Paint.Style.FILL);
                p.setShader(new LinearGradient(0, 0, getWidth(), getHeight(),
                        new int[]{t.primaryContainer,
                                Hct.blendLab(t.primaryContainer, t.tertiary, 0.55f)},
                        null, Shader.TileMode.CLAMP));
                c.drawPath(path, p);
                p.setShader(null);
                RectF ib = new RectF(r); ib.inset(r.width() * 0.3f, r.height() * 0.3f);
                Icons.draw(c, "note", ib, Hct.withAlpha(t.onPrimaryContainer, 0.7f), 1f, p);
            }
        }
    }

    // ── Hero 卡片 ───────────────────────────────────────────────────────────
    /** 正在播放大卡片：封面 + 光晕 + 声波律动 */
    public static class HeroCard extends View implements PlayerService.Listener {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF r = new RectF();
        private final RectF coverR = new RectF();

        private Song song;
        private Bitmap cover;
        private int coverHash = -1;
        private boolean playing;
        private float progress;
        private float wave;
        private float press;
        private Runnable onOpen, onImport;
        private long lastFrame;
        private int accent;

        public HeroCard(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        public void setOnOpen(Runnable r0) { this.onOpen = r0; }
        public void setOnImport(Runnable r0) { this.onImport = r0; }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            PlayerService s = PlayerService.instance();
            if (s != null) {
                s.addListener(this);
                onSongChanged(s.current(), s.currentIndex());
                onPlayStateChanged(s.isPlaying());
            }
            postOnAnimation(loopRunnable);
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            PlayerService s = PlayerService.instance();
            if (s != null) s.removeListener(this);
        }

        private final Runnable loopRunnable = new Runnable() {
            @Override public void run() {
                if (!isShown()) { postOnAnimationDelayed(loopRunnable, 500); return; }
                long now = System.currentTimeMillis();
                float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
                lastFrame = now;
                if (playing) { wave += dt * 2.4f; if (wave > 1f) wave -= 1f; }
                if (press > 0f) press = Math.max(0f, press - dt * 6f);
                postInvalidateOnAnimation();
                postOnAnimationDelayed(loopRunnable, 16);
            }
        };

        public void refresh() {
            PlayerService s = PlayerService.instance();
            if (s != null) onSongChanged(s.current(), s.currentIndex());
            invalidate();
        }

        private float dp(float v) { return Ui.dp(getContext(), v); }

        @Override protected void onDraw(Canvas c) {
            Tokens t = Theme.t();
            r.set(0, 0, getWidth(), getHeight());
            float rad = dp(28);
            path.reset();
            path.addRoundRect(r, rad, rad, Path.Direction.CW);

            int save = c.save();
            if (press > 0f) {
                c.scale(1f - press * 0.012f, 1f - press * 0.012f, r.centerX(), r.centerY());
            }

            int c1, c2;
            if (accent != 0) {
                c1 = Hct.blendLab(accent, t.surfaceContainerHigh, t.dark ? 0.55f : 0.62f);
                c2 = Hct.blendLab(Hct.blendLab(accent, t.tertiary, 0.35f),
                        t.surfaceContainerHigh, t.dark ? 0.40f : 0.50f);
            } else {
                c1 = t.primaryContainer;
                c2 = Hct.blendLab(t.primaryContainer, t.tertiaryContainer, 0.6f);
            }
            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                    new int[]{c1, c2}, null, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);

            if (song != null) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(wave * Math.PI * 2);
                int ac = accent != 0 ? accent : t.primary;
                p.setShader(new RadialGradient(r.right - dp(40), r.top + dp(30),
                        r.width() * (0.5f + 0.1f * pulse),
                        new int[]{Hct.withAlpha(ac, playing ? 0.34f : 0.18f), Hct.withAlpha(ac, 0f)},
                        null, Shader.TileMode.CLAMP));
                c.drawPath(path, p);
                p.setShader(null);
            }

            int save2 = c.save();
            c.clipPath(path);

            float cs = r.height() - dp(36);
            coverR.set(r.right - cs - dp(18), r.top + dp(18), r.right - dp(18), r.top + dp(18) + cs);

            if (song != null) drawCover(c, t);

            float tx = dp(22);
            float maxW = Math.max(dp(40), coverR.left - tx - dp(14));

            if (song == null) {
                int fg = Hct.onColor(c1, 0xFFFFFFFF, 0xFF1B1B1F);
                boolean empty = Library.isEmpty();
                p.reset(); p.setTextAlign(Paint.Align.LEFT);
                p.setTypeface(Ui.tfBold()); p.setTextSize(dp(19));
                p.setColor(fg);
                c.drawText(empty ? "还没有音乐" : "还没有在播的歌", tx, r.top + dp(66), p);

                p.setTypeface(Ui.tf()); p.setTextSize(dp(12.5f));
                p.setColor(Hct.withAlpha(fg, 0.75f));
                c.drawText(empty ? "点这里导入你的音乐" : "点这里去曲库挑一首",
                        tx, r.top + dp(90), p);

                RectF ib = new RectF(r.right - dp(84), r.centerY() - dp(26),
                        r.right - dp(32), r.centerY() + dp(26));
                Icons.draw(c, empty ? "list" : "note", ib, Hct.withAlpha(fg, 0.28f), 1f, p);
            } else {
                int fg = Hct.onColor(c1, 0xFFFFFFFF, 0xFF141418);

                p.reset(); p.setTextAlign(Paint.Align.LEFT);
                p.setTypeface(Ui.tfMed()); p.setTextSize(dp(11));
                p.setColor(Hct.withAlpha(fg, 0.7f));
                c.drawText("正在播放", tx, r.top + dp(38), p);

                p.setTypeface(Ui.tfBold()); p.setTextSize(dp(19));
                p.setColor(fg);
                c.drawText(ellipsize(song.title, p, maxW), tx, r.top + dp(68), p);

                p.setTypeface(Ui.tf()); p.setTextSize(dp(12.5f));
                p.setColor(Hct.withAlpha(fg, 0.72f));
                c.drawText(ellipsize(song.subtitle(), p, maxW), tx, r.top + dp(90), p);

                float py = r.top + dp(116);
                float pw = Math.min(maxW, dp(150));
                p.reset(); p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(dp(3.5f));
                p.setColor(Hct.withAlpha(fg, 0.24f));
                c.drawLine(tx, py, tx + pw, py, p);
                p.setColor(fg);
                c.drawLine(tx, py, tx + pw * clamp01(progress), py, p);

                if (playing) drawWave(c, fg, tx, py + dp(26));
                else {
                    p.reset(); p.setTextSize(dp(11)); p.setTypeface(Ui.tfMed());
                    p.setColor(Hct.withAlpha(fg, 0.6f));
                    c.drawText("已暂停", tx, py + dp(30), p);
                }
            }
            c.restoreToCount(save2);

            Draw.stroke(c, path, Hct.withAlpha(Color.WHITE, t.dark ? 0.10f : 0.35f), dp(1f), p);
            c.restoreToCount(save);
        }

        private static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

        private void drawWave(Canvas c, int fg, float x, float y) {
            float barW = dp(3f), gap = dp(4f);
            for (int i = 0; i < 5; i++) {
                float ph = wave * (float) Math.PI * 2 + i * 0.85f;
                float hh = dp(4) + (float) Math.abs(Math.sin(ph)) * dp(16);
                float px = x + i * (barW + gap);
                p.reset(); p.setStyle(Paint.Style.FILL);
                p.setColor(Hct.withAlpha(fg, 0.85f));
                RectF bar = new RectF(px, y - hh / 2, px + barW, y + hh / 2);
                c.drawRoundRect(bar, barW / 2, barW / 2, p);
            }
        }

        private void drawCover(Canvas c, Tokens t) {
            float rad = dp(18);
            Path cp = Draw.roundRect(coverR, rad);
            if (cover != null && !cover.isRecycled()) {
                int save = c.save();
                c.clipPath(cp);
                Draw.bitmapCrop(c, cover, coverR, p);
                c.restoreToCount(save);
            } else {
                p.reset(); p.setStyle(Paint.Style.FILL);
                p.setColor(Hct.withAlpha(0xFFFFFFFF, 0.16f));
                c.drawPath(cp, p);
                RectF ib = new RectF(coverR);
                ib.inset(coverR.width() * 0.3f, coverR.height() * 0.3f);
                Icons.draw(c, "note", ib, Hct.withAlpha(0xFFFFFFFF, 0.7f), 1f, p);
            }
            Draw.stroke(c, cp, Hct.withAlpha(0xFFFFFFFF, 0.28f), dp(1f), p);
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

        private void loadCover() {
            if (song == null) { cover = null; accent = 0; return; }
            if (coverHash == (int) song.id && cover != null) return;
            coverHash = (int) song.id;
            cover = song.cover;
            Library.loadCoverAsync(getContext(), song, 520, new Runnable() {
                @Override public void run() {
                    if (song == null) return;
                    cover = song.cover;
                    if (cover != null) accent = Theme.accentFromCover(Library.averageColor(cover));
                    invalidate();
                }
            });
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    press = 1f; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    press = 0f; invalidate();
                    Ui.hapticLight(this);
                    if (song == null) { if (onImport != null) onImport.run(); }
                    else if (onOpen != null) onOpen.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    press = 0f; invalidate(); return true;
            }
            return super.onTouchEvent(e);
        }

        @Override public void onSongChanged(Song s, int index) {
            song = s; loadCover(); invalidate();
        }
        @Override public void onPlayStateChanged(boolean pl) { playing = pl; invalidate(); }
        @Override public void onProgress(long pos, long dur) {
            progress = dur > 0 ? (float) pos / dur : 0f;
            invalidate();
        }
        @Override public void onQueueChanged() {}
        @Override public void onRepeatShuffleChanged(int repeat, int shuffle) {}
    }
}
