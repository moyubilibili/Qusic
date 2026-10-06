package com.qusic.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单详情 —— 独立的整屏页面。
 *
 * <p>之前这个详情是**嵌在「曲库」页里面的**：标题栏、分段控件、底部导航全都还在，
 * 看起来就是「歌单页面和主界面糊在一起」，层级关系完全不对。
 *
 * <p>现在它是一块覆盖整个界面的独立页面（连底栏也盖住）：
 * <ul>
 *   <li>有自己的顶栏：返回箭头 + 歌单名 + 播放全部 / 分享 / 导出</li>
 *   <li>从右侧滑入 + 淡入，返回时滑出 —— 有明确的「进入下一层」的感觉</li>
 *   <li>返回键优先关闭它（见 MainActivity.onBackPressed）</li>
 * </ul>
 */
public class PlaylistDetailPage {

    public interface OnClose { void onClose(); }

    private final MainActivity act;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout body;
    private long listId;
    private OnClose cb;

    public PlaylistDetailPage(MainActivity a) {
        this.act = a;
        Context c = a;
        Tokens t = Theme.t();

        root = new FrameLayout(c);
        root.setBackgroundColor(t.surface);
        root.setClickable(true);          // 盖住下面的点击

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body = Ui.column(c);
        // 底部要留出手势条/导航栏，否则最后一行歌会被系统条挡住
        body.setPadding(Ui.px(c, 18), 0, Ui.px(c, 18),
                Ui.px(c, 28) + Ui.navBarHeight(c));
        scroll.addView(body);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public View view() { return root; }
    public long listId() { return listId; }

    public void setOnClose(OnClose c) { this.cb = c; }

    public void bind(long id) {
        this.listId = id;
        rebuild();
    }

    /** 每次数据变化后重绘 */
    public void rebuild() {
        Context c = act;
        Tokens t = Theme.t();
        Playlist.Item it = Playlist.byId(listId);
        if (it == null) { if (cb != null) cb.onClose(); return; }

        body.removeAllViews();

        // ── 顶栏：返回 + 操作 ──
        LinearLayout bar = Ui.row(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, Ui.statusBarHeight(c) + Ui.px(c, 8), 0, Ui.px(c, 6));

        HomePage.IconView back = new HomePage.IconView(c, "chevron",
                t.onSurfaceVariant);
        back.setRotation(180f);   // chevron 指向左
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); close(); }
        });
        Ui.pressable(back);
        bar.addView(back, new LinearLayout.LayoutParams(Ui.px(c, 24), Ui.px(c, 24)));

        TextView nameTop = new TextView(c);
        nameTop.setText(it.name);
        nameTop.setTextSize(14);
        nameTop.setTypeface(Ui.tfMed());
        nameTop.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        nameTop.setMaxLines(1);
        nameTop.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = Ui.px(c, 12);
        bar.addView(nameTop, nlp);
        body.addView(bar);

        // ── 大标题 ──
        TextView title = new TextView(c);
        title.setText(it.name);
        title.setTextColor(t.onSurface);
        title.setTextSize(28);
        title.setTypeface(Ui.tfBold());
        title.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 4));
        body.addView(title);

        TextView meta = new TextView(c);
        meta.setText(it.size() + " 首");
        meta.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.8f));
        meta.setTextSize(12.5f);
        meta.setPadding(0, 0, 0, Ui.px(c, 16));
        body.addView(meta);

        // ── 主操作 ──
        LinearLayout acts = Ui.row(c);
        acts.setPadding(0, 0, 0, Ui.px(c, 14));

        final List<Song> songs = new ArrayList<>(it.songs);

        TextView play = new TextView(c);
        play.setText("播放全部");
        play.setTextSize(14);
        play.setTypeface(Ui.tfBold());
        play.setTextColor(t.onPrimary);
        play.setGravity(Gravity.CENTER);
        play.setPadding(0, Ui.px(c, 13), 0, Ui.px(c, 13));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(t.primary);
        pb.setCornerRadius(Ui.px(c, 24));
        play.setBackground(pb);
        Ui.pressable(play);
        play.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (songs.isEmpty()) {
                    android.widget.Toast.makeText(act, "这个歌单还是空的",
                            android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
                playFrom(songs, 0);
            }
        });
        acts.addView(play, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        acts.addView(smallBtn(c, t, "分享", new Runnable() {
            @Override public void run() { act.shareToCommunity(Playlist.byId(listId)); }
        }));
        body.addView(acts);

        LinearLayout acts2 = Ui.row(c);
        acts2.setPadding(0, 0, 0, Ui.px(c, 14));
        acts2.addView(smallBtn(c, t, "导出 .Qusic", new Runnable() {
            @Override public void run() { act.exportPlaylist(listId); }
        }));
        acts2.addView(smallBtn(c, t, "重命名", new Runnable() {
            @Override public void run() { renameDialog(); }
        }));
        acts2.addView(smallBtn(c, t, "删除", new Runnable() {
            @Override public void run() { deleteDialog(); }
        }));
        body.addView(acts2);

        // ── 曲目 ──
        if (songs.isEmpty()) {
            TextView tv = new TextView(c);
            tv.setText("这个歌单还是空的。\n到「歌曲」或「最近」里长按一首歌，选「添加到歌单」。");
            tv.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
            tv.setTextSize(13);
            tv.setLineSpacing(Ui.px(c, 5), 1f);
            body.addView(tv);
            return;
        }

        for (int i = 0; i < songs.size(); i++) {
            final Song sg = songs.get(i);
            final int idx = i;
            LinearLayout row = Ui.row(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.px(c, 8), 0, Ui.px(c, 8));

            TextView num = new TextView(c);
            num.setText(String.valueOf(i + 1));
            num.setTextSize(12.5f);
            num.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.6f));
            num.setGravity(Gravity.CENTER);
            row.addView(num, new LinearLayout.LayoutParams(Ui.px(c, 26),
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            HomePage.CoverThumb thumb = new HomePage.CoverThumb(c, sg);
            row.addView(thumb, new LinearLayout.LayoutParams(Ui.px(c, 44), Ui.px(c, 44)));

            LinearLayout col = Ui.column(c);
            col.setPadding(Ui.px(c, 12), 0, 0, 0);
            TextView tt = new TextView(c);
            tt.setText(sg.title);
            tt.setTextColor(t.onSurface);
            tt.setTextSize(14);
            tt.setTypeface(Ui.tfMed());
            tt.setMaxLines(1);
            tt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(tt);
            String sub = sg.subtitle() + " · " + sg.durationText();
            if (sg.online) sub = Online.sourceName(sg.source) + " · " + sub;
            TextView s2 = new TextView(c);
            s2.setText(sub);
            s2.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
            s2.setTextSize(11.5f);
            s2.setMaxLines(1);
            s2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s2);
            row.addView(col, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            HomePage.IconView del = new HomePage.IconView(c, "close",
                    Hct.withAlpha(t.onSurfaceVariant, 0.45f));
            Ui.pressable(del);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticLight(v);
                    Playlist.remove(act, listId, sg);
                    rebuild();
                }
            });
            row.addView(del, new LinearLayout.LayoutParams(Ui.px(c, 16), Ui.px(c, 16)));

            Ui.pressable(row);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticLight(v);
                    playFrom(songs, idx);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.hapticStrong(v);
                    Playlist.showAddDialog(act, sg, new Runnable() {
                        @Override public void run() { rebuild(); }
                    });
                    return true;
                }
            });
            body.addView(row);
        }
    }

    private TextView smallBtn(Context c, Tokens t, String label, final Runnable r) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(12.5f);
        b.setTypeface(Ui.tfMed());
        b.setTextColor(t.onSecondaryContainer);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.px(c, 14), Ui.px(c, 10), Ui.px(c, 14), Ui.px(c, 10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.secondaryContainer);
        g.setCornerRadius(Ui.px(c, 20));
        b.setBackground(g);
        Ui.pressable(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); r.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.px(c, 8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 本地直放；在线曲目先解析直链（跟搜索页同样的逻辑） */
    private void playFrom(final List<Song> songs, final int index) {
        final Song s = songs.get(index);
        PlayerService ps = PlayerService.instance();
        if (!s.online) {
            if (ps != null) ps.playList(songs, index);
            return;
        }
        if (s.streamUrl != null && s.streamUrl.length() > 0) {
            if (ps != null) ps.playList(songs, index);
            return;
        }
        android.widget.Toast.makeText(act, "正在解析「" + s.title + "」…",
                android.widget.Toast.LENGTH_SHORT).show();
        Online.UrlCallback cb = new Online.UrlCallback() {
            @Override public void onResult(String url, String error) {
                if (url != null) {
                    PlayerService p2 = PlayerService.instance();
                    if (p2 != null) p2.playList(songs, index);
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

    private void renameDialog() {
        final Playlist.Item it = Playlist.byId(listId);
        if (it == null) return;
        final MdField f = new MdField(act, "歌单名字");
        f.setText(it.name);
        new MdDialog.Builder(act)
                .title("重命名歌单")
                .content(f)
                .negative("取消", null)
                .positive("保存", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        Playlist.rename(act, listId, f.text());
                        rebuild();
                    }
                }).show();
    }

    private void deleteDialog() {
        final Playlist.Item it = Playlist.byId(listId);
        if (it == null) return;
        new MdDialog.Builder(act)
                .title("删除歌单？")
                .message("「" + it.name + "」会被删除。\n只是移除歌单，不会删除任何歌曲文件。")
                .negative("取消", null)
                .positive("删除", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        Playlist.delete(act, listId);
                        close();
                    }
                }).show();
    }

    public void close() {
        if (cb != null) cb.onClose();
    }
}
