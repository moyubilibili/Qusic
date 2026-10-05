package com.qusic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 曲库页（导入制）。
 *
 * <p>核心是「导入」：通过 SAF 文件选择器让用户自己挑歌，导入后才出现在列表里。
 * 空状态给一个显眼的导入入口，而不是自动把全盘音乐倒出来。
 *
 * <p>三种浏览方式（歌曲 / 专辑 / 歌手）用 MD3 分段控件切换。
 */
public class LibraryPage {

    private static final int MODE_SONGS = 0, MODE_ALBUMS = 1, MODE_ARTISTS = 2,
            MODE_HISTORY = 3;

    private final MainActivity act;
    private View root;
    private SongListView listView;
    private LinearLayout albumBox;
    private android.widget.ScrollView albumScroll;
    private LinearLayout emptyBox;
    private android.widget.ScrollView historyScroll;
    private LinearLayout historyBox;
    private SegmentedBar segmented;
    private TextView countLabel;
    private TextView importBtn;

    private int mode = MODE_SONGS;
    private int sortMode = 0;

    public LibraryPage(MainActivity a) {
        this.act = a;
        build();
    }

    public View view() { return root; }

    /** 拉起系统文件选择器导入音乐 */
    public void triggerImport() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            act.startActivityForResult(
                    android.content.Intent.createChooser(i, "选择要导入的音乐"), MainActivity.REQ_IMPORT);
        } catch (Throwable t) {
            android.widget.Toast.makeText(act, "没有可用的文件选择器",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void build() {
        Tokens t = Theme.t();
        Context c = act;

        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(t.surface);
        root = wrap;

        LinearLayout col = Ui.column(c);
        wrap.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int pad = Ui.px(c, 20);
        LinearLayout header = Ui.column(c);
        header.setPadding(pad, Ui.statusBarHeight(c) + Ui.px(c, 22), pad, 0);

        TextView title = new TextView(c);
        title.setText("曲库");
        title.setTextColor(t.onSurface);
        title.setTextSize(30);
        title.setTypeface(Ui.tfBlack());
        header.addView(title);

        countLabel = new TextView(c);
        countLabel.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        countLabel.setTextSize(12.5f);
        countLabel.setPadding(0, Ui.px(c, 2), 0, Ui.px(c, 14));
        header.addView(countLabel);

        segmented = new SegmentedBar(c, new String[]{"歌曲", "专辑", "歌手", "最近"});
        segmented.setOnChange(new SegmentedBar.OnChange() {
            @Override public void onChange(int i) { mode = i; applyMode(); }
        });
        header.addView(segmented, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(c, 44)));

        // 操作行
        LinearLayout ops = Ui.row(c);
        ops.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 8));

        importBtn = new TextView(c);
        importBtn.setText("＋ 导入音乐");
        importBtn.setTextSize(12.5f);
        importBtn.setTypeface(Ui.tfBold());
        importBtn.setTextColor(t.onPrimary);
        importBtn.setPadding(Ui.px(c, 14), Ui.px(c, 8), Ui.px(c, 14), Ui.px(c, 8));
        importBtn.setBackground(pill(t.primary, Ui.px(c, 20)));
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); triggerImport(); }
        });
        ops.addView(importBtn);

        final TextView sortBtn = new TextView(c);
        sortBtn.setTextSize(12.5f);
        sortBtn.setTypeface(Ui.tfMed());
        sortBtn.setTextColor(t.onSurfaceVariant);
        sortBtn.setPadding(Ui.px(c, 12), Ui.px(c, 8), Ui.px(c, 12), Ui.px(c, 8));
        sortBtn.setBackground(pill(t.surfaceContainerHigh, Ui.px(c, 20)));
        sortBtn.setText(sortLabel());
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                sortMode = (sortMode + 1) % 3;
                sortBtn.setText(sortLabel());
                refresh();
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.px(c, 10);
        ops.addView(sortBtn, slp);

        final TextView shuffleAll = new TextView(c);
        shuffleAll.setText("随机播放");
        shuffleAll.setTextSize(12.5f);
        shuffleAll.setTypeface(Ui.tfMed());
        shuffleAll.setTextColor(t.onSurfaceVariant);
        shuffleAll.setPadding(Ui.px(c, 12), Ui.px(c, 8), Ui.px(c, 12), Ui.px(c, 8));
        shuffleAll.setBackground(pill(t.surfaceContainerHigh, Ui.px(c, 20)));
        shuffleAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                PlayerService s = PlayerService.instance();
                List<Song> all = Library.songs();
                if (s != null && !all.isEmpty()) {
                    if (s.shuffleMode() != PlayerService.SHUFFLE_ON) s.toggleShuffle();
                    s.playList(all, 0);
                }
            }
        });
        LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shlp.leftMargin = Ui.px(c, 10);
        ops.addView(shuffleAll, shlp);

        header.addView(ops);
        col.addView(header);

        // 歌曲列表
        listView = new SongListView(c);
        listView.setOnPick(new SongListView.OnPick() {
            @Override public void onPick(List<Song> visible, int index) {
                PlayerService s = PlayerService.instance();
                if (s != null) s.playList(visible, index);
            }
        });
        col.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 专辑 / 歌手
        albumScroll = new android.widget.ScrollView(c);
        albumScroll.setVerticalScrollBarEnabled(false);
        albumScroll.setClipToPadding(false);
        albumScroll.setVisibility(View.GONE);
        albumBox = Ui.column(c);
        albumBox.setPadding(pad, Ui.px(c, 6), pad, act.contentBottomInset());
        albumScroll.addView(albumBox);
        col.addView(albumScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 历史列表（独立一个，因为它可能包含在线歌曲，点击行为不同）
        historyScroll = new android.widget.ScrollView(c);
        historyScroll.setVerticalScrollBarEnabled(false);
        historyScroll.setClipToPadding(false);
        historyScroll.setVisibility(View.GONE);
        historyBox = Ui.column(c);
        historyBox.setPadding(pad, Ui.px(c, 6), pad, act.contentBottomInset());
        historyScroll.addView(historyBox);
        col.addView(historyScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 空状态
        emptyBox = Ui.column(c);
        emptyBox.setGravity(Gravity.CENTER);
        emptyBox.setPadding(pad, 0, pad, act.contentBottomInset());
        buildEmpty();
        col.addView(emptyBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        refresh();
    }

    private void buildEmpty() {
        Context c = act;
        Tokens t = Theme.t();
        emptyBox.removeAllViews();

        HomePage.IconView ic = new HomePage.IconView(c, "note", Hct.withAlpha(t.primary, 0.9f));
        emptyBox.addView(ic, new LinearLayout.LayoutParams(Ui.px(c, 76), Ui.px(c, 76)));

        TextView h = new TextView(c);
        h.setText("曲库还是空的");
        h.setTextColor(t.onSurface);
        h.setTextSize(18);
        h.setTypeface(Ui.tfBold());
        h.setGravity(Gravity.CENTER);
        h.setPadding(0, Ui.px(c, 18), 0, Ui.px(c, 6));
        emptyBox.addView(h);

        TextView d = new TextView(c);
        d.setText("Qusic 不会去翻你的手机。\n点下面的按钮，自己挑歌导入进来。");
        d.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        d.setTextSize(13);
        d.setGravity(Gravity.CENTER);
        d.setLineSpacing(Ui.px(c, 5), 1f);
        emptyBox.addView(d);

        TextView b = new TextView(c);
        b.setText("导入音乐");
        b.setTextColor(t.onPrimary);
        b.setTextSize(14.5f);
        b.setTypeface(Ui.tfBold());
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.px(c, 34), Ui.px(c, 13), Ui.px(c, 34), Ui.px(c, 13));
        b.setBackground(pill(t.primary, Ui.px(c, 24)));
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); triggerImport(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.px(c, 22);
        emptyBox.addView(b, blp);

        TextView hint = new TextView(c);
        hint.setText("支持 MP3 / FLAC / M4A / WAV / OGG 等格式");
        hint.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.6f));
        hint.setTextSize(11.5f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.px(c, 16), 0, 0);
        emptyBox.addView(hint);
    }

    private Drawable pill(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    private String sortLabel() {
        return sortMode == 0 ? "标题" : sortMode == 1 ? "歌手" : "时长";
    }

    private void applyMode() {
        if (mode == MODE_HISTORY) {
            listView.setVisibility(View.GONE);
            albumScroll.setVisibility(View.GONE);
            historyScroll.setVisibility(View.VISIBLE);
            buildHistory();
            return;
        }
        historyScroll.setVisibility(View.GONE);
        if (Library.isEmpty()) { refresh(); return; }
        boolean songs = mode == MODE_SONGS;
        listView.setVisibility(songs ? View.VISIBLE : View.GONE);
        albumScroll.setVisibility(songs ? View.GONE : View.VISIBLE);
        if (!songs) buildGroups();
    }

    public void refresh() {
        if (listView == null) return;
        boolean has = Library.count() > 0;

        emptyBox.setVisibility(has || mode == MODE_HISTORY ? View.GONE : View.VISIBLE);
        // 「最近」即使曲库为空也有意义（在线听过的歌也在历史里）
        segmented.setVisibility(View.VISIBLE);
        importBtn.setVisibility(has ? View.VISIBLE : View.GONE);
        boolean hist = mode == MODE_HISTORY;
        listView.setVisibility(has && mode == MODE_SONGS ? View.VISIBLE : View.GONE);
        albumScroll.setVisibility(has && mode != MODE_SONGS && !hist ? View.VISIBLE : View.GONE);
        historyScroll.setVisibility(hist ? View.VISIBLE : View.GONE);

        List<Song> all = new ArrayList<>(Library.songs());
        sort(all);
        listView.setData(all);

        if (mode == MODE_HISTORY) {
            countLabel.setText(History.count() + " 首最近播放");
        } else {
            countLabel.setText(has
                    ? Library.count() + " 首 · " + Ui.duration(Library.totalDurationMs())
                            + " · " + Library.albums().size() + " 张专辑"
                    : "还没有导入任何音乐");
        }

        if (hist) buildHistory();
        else if (has && mode != MODE_SONGS) buildGroups();
    }

    private void sort(List<Song> l) {
        Comparator<Song> cmp;
        if (sortMode == 1) cmp = new Comparator<Song>() {
            @Override public int compare(Song a, Song b) {
                int r = a.artist.compareToIgnoreCase(b.artist);
                return r != 0 ? r : a.title.compareToIgnoreCase(b.title);
            }
        };
        else if (sortMode == 2) cmp = new Comparator<Song>() {
            @Override public int compare(Song a, Song b) { return Long.compare(b.durationMs, a.durationMs); }
        };
        else cmp = new Comparator<Song>() {
            @Override public int compare(Song a, Song b) { return a.title.compareToIgnoreCase(b.title); }
        };
        Collections.sort(l, cmp);
    }

    private void buildGroups() {
        Context c = act;
        Tokens t = Theme.t();
        albumBox.removeAllViews();

        if (mode == MODE_ALBUMS) {
            for (Library.Album a : Library.albums()) {
                albumBox.addView(albumRow(c, t, a.name, a.artist, a.songs, a.durationMs));
            }
        } else {
            java.util.LinkedHashMap<String, List<Song>> map = new java.util.LinkedHashMap<>();
            for (Song s : Library.songs()) {
                String k = (s.artist == null || s.artist.length() == 0) ? "未知歌手" : s.artist;
                List<Song> l = map.get(k);
                if (l == null) { l = new ArrayList<>(); map.put(k, l); }
                l.add(s);
            }
            for (java.util.Map.Entry<String, List<Song>> e : map.entrySet()) {
                long total = 0;
                for (Song s : e.getValue()) total += s.durationMs;
                albumBox.addView(albumRow(c, t, e.getKey(), e.getValue().size() + " 首",
                        e.getValue(), total));
            }
        }
    }

    private View albumRow(Context c, Tokens t, final String name, String sub,
                          final List<Song> songs, long totalMs) {
        LinearLayout row = Ui.row(c);
        row.setPadding(0, Ui.px(c, 8), 0, Ui.px(c, 8));

        HomePage.CoverThumb thumb = new HomePage.CoverThumb(c, songs.get(0));
        row.addView(thumb, new LinearLayout.LayoutParams(Ui.px(c, 56), Ui.px(c, 56)));

        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.px(c, 14), 0, 0, 0);

        TextView title = new TextView(c);
        title.setText(name);
        title.setTextColor(t.onSurface);
        title.setTextSize(15);
        title.setTypeface(Ui.tfBold());
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title);

        TextView s2 = new TextView(c);
        s2.setText(sub + " · " + Ui.duration(totalMs));
        s2.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        s2.setTextSize(11.5f);
        s2.setMaxLines(1);
        s2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(s2);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        HomePage.IconView chev = new HomePage.IconView(c, "chevron",
                Hct.withAlpha(t.onSurfaceVariant, 0.5f));
        row.addView(chev, new LinearLayout.LayoutParams(Ui.px(c, 18), Ui.px(c, 18)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                PlayerService s = PlayerService.instance();
                if (s != null) s.playList(songs, 0);
            }
        });
        return row;
    }

    // ── 最近播放 ────────────────────────────────────────────────────────────
    private void buildHistory() {
        Context c = act;
        Tokens t = Theme.t();
        historyBox.removeAllViews();

        final List<Song> items = History.items();
        if (items.isEmpty()) {
            TextView tv = new TextView(c);
            tv.setText("还没有播放记录。\n听过的歌会出现在这里，在线的也能直接重播。");
            tv.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
            tv.setTextSize(13);
            tv.setLineSpacing(Ui.px(c, 5), 1f);
            tv.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 10));
            historyBox.addView(tv);
            return;
        }

        // 顶部工具行：清空
        LinearLayout tools = Ui.row(c);
        tools.setPadding(0, Ui.px(c, 2), 0, Ui.px(c, 10));
        TextView all = new TextView(c);
        all.setText("播放全部");
        all.setTextSize(12.5f);
        all.setTypeface(Ui.tfBold());
        all.setTextColor(t.onPrimary);
        all.setPadding(Ui.px(c, 14), Ui.px(c, 8), Ui.px(c, 14), Ui.px(c, 8));
        all.setBackground(pill(t.primary, Ui.px(c, 20)));
        all.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                playFromHistory(items, 0);
            }
        });
        tools.addView(all);

        TextView clr = new TextView(c);
        clr.setText("清空");
        clr.setTextSize(12.5f);
        clr.setTypeface(Ui.tfMed());
        clr.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
        clr.setPadding(Ui.px(c, 12), Ui.px(c, 8), Ui.px(c, 12), Ui.px(c, 8));
        clr.setBackground(pill(t.surfaceContainerHigh, Ui.px(c, 20)));
        clr.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                new android.app.AlertDialog.Builder(act)
                        .setTitle("清空播放历史？")
                        .setMessage("只是清掉记录，不会删除任何歌曲文件。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                History.clear(act);
                                refresh();
                            }
                        }).show();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.px(c, 10);
        tools.addView(clr, clp);
        historyBox.addView(tools);

        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            historyBox.addView(historyRow(c, t, items.get(i), new Runnable() {
                @Override public void run() { playFromHistory(items, idx); }
            }));
        }
    }

    private View historyRow(Context c, Tokens t, final Song s, final Runnable onPlay) {
        LinearLayout row = Ui.row(c);
        row.setPadding(0, Ui.px(c, 7), 0, Ui.px(c, 7));

        HomePage.CoverThumb thumb = new HomePage.CoverThumb(c, s);
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

        // 在线歌曲标注来源，避免和本地混淆
        String sub = s.subtitle() + " · " + s.durationText();
        if (s.online) {
            sub = (s.source == Song.SOURCE_KUWO ? "酷我" : "网易云") + " · " + sub;
        }
        TextView s2 = new TextView(c);
        s2.setText(sub);
        s2.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        s2.setTextSize(11.5f);
        s2.setMaxLines(1);
        s2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(s2);

        row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 单条删除
        HomePage.IconView del = new HomePage.IconView(c, "close",
                Hct.withAlpha(t.onSurfaceVariant, 0.5f));
        del.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                History.remove(act, s);
                refresh();
            }
        });
        row.addView(del, new LinearLayout.LayoutParams(Ui.px(c, 16), Ui.px(c, 16)));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (onPlay != null) onPlay.run();
            }
        });
        return row;
    }

    /**
     * 从历史播放。
     *
     * <p>在线歌曲的直链是**带时效签名**的，历史里只存了元数据，
     * 所以这里必须先重新解析一次；解析好再交给播放服务。
     */
    private void playFromHistory(final List<Song> items, final int index) {
        if (items.isEmpty()) return;
        final Song s = items.get(index);

        if (!s.online) {
            PlayerService ps = PlayerService.instance();
            if (ps != null) ps.playList(items, index);
            return;
        }
        if (s.streamUrl != null && s.streamUrl.length() > 0) {
            PlayerService ps = PlayerService.instance();
            if (ps != null) ps.playList(items, index);
            return;
        }

        // 需要重新解析直链
        android.widget.Toast.makeText(act, "正在解析「" + s.title + "」…",
                android.widget.Toast.LENGTH_SHORT).show();
        Online.UrlCallback cb = new Online.UrlCallback() {
            @Override public void onResult(String url, String error) {
                if (url != null) {
                    PlayerService ps = PlayerService.instance();
                    if (ps != null) ps.playList(items, index);
                } else {
                    android.widget.Toast.makeText(act,
                            error == null ? "这首歌暂时放不了" : error,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        };
        if (s.source == Song.SOURCE_KUWO) Kuwo.resolveUrl(act, s, cb);
        else NetEase.resolveUrl(act, s, cb);
    }

    // ── MD3 分段控件 ────────────────────────────────────────────────────────
    /** 带滑动胶囊指示器的分段控件 */
    public static class SegmentedBar extends View {
        public interface OnChange { void onChange(int i); }

        private final String[] labels;
        private final android.graphics.Paint p =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        private float indPos = 0, indTarget = 0;
        private int selected = 0;
        private OnChange cb;

        public SegmentedBar(Context c, String[] labels) {
            super(c);
            this.labels = labels;
        }

        public void setOnChange(OnChange c) { cb = c; }

        public void select(int i, boolean anim) {
            if (i < 0 || i >= labels.length) return;
            selected = i;
            if (!anim) {
                indPos = indTarget = i;
                invalidate();
                if (cb != null) cb.onChange(i);
                return;
            }
            final float from = indPos;
            indTarget = i;
            android.animation.ValueAnimator va =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(Theme.dur(400));
            va.setInterpolator(Theme.EMPHASIZED);
            va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    float f = (float) a.getAnimatedValue();
                    indPos = from + (indTarget - from) * f;
                    invalidate();
                }
            });
            va.start();
            if (cb != null) cb.onChange(i);
        }

        private float dp(float v) { return Ui.dp(getContext(), v); }

        @Override protected void onDraw(Canvas c) {
            Tokens t = Theme.t();
            float w = getWidth(), h = getHeight();
            float r = h / 2f;

            p.reset(); p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(t.surfaceContainerHigh);
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r, r, p);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Hct.withAlpha(t.outline, 0.5f));
            rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f));
            c.drawRoundRect(rect, r, r, p);

            float cellW = (w - dp(8)) / labels.length;
            float selW = cellW - dp(4);
            float left = dp(4) + cellW * indPos + (cellW - selW) / 2f;
            p.reset(); p.setStyle(android.graphics.Paint.Style.FILL);
            p.setColor(t.secondaryContainer);
            rect.set(left, dp(4), left + selW, h - dp(4));
            float ir = (h - dp(8)) / 2f;
            c.drawRoundRect(rect, ir, ir, p);

            p.setTextAlign(android.graphics.Paint.Align.CENTER);
            for (int i = 0; i < labels.length; i++) {
                float cx = dp(4) + cellW * i + cellW / 2f;
                float sel = 1f - Math.min(1f, Math.abs(indPos - i));
                p.setColor(Hct.blendLab(t.onSurfaceVariant, t.onSecondaryContainer, sel));
                p.setTextSize(dp(13f));
                p.setTypeface(sel > 0.5f ? Ui.tfBold() : Ui.tfMed());
                c.drawText(labels[i], cx, h / 2f + dp(4.6f), p);
            }
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                float cellW = (getWidth() - dp(8)) / labels.length;
                int i = (int) ((e.getX() - dp(4)) / cellW);
                i = Math.max(0, Math.min(labels.length - 1, i));
                Ui.hapticLight(this);
                if (i != selected) select(i, true);
            }
            return true;
        }
    }
}
