package com.qusic.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 社区帖子详情 —— 独立的**二级页面**。
 *
 * <p>之前它是嵌在社区页里的：上面那块（登录卡、最新/最热、搜索框）一直挂着，
 * 详情只能挤在剩下的空间里。**评论一多就完了** —— 内容和评论一起在一个
 * 滚动区里滚，输入框也在最底下，想看评论得先滚过整个帖子。
 *
 * <p>现在分成三层，各司其职：
 * <pre>
 *   ┌──────────────────────────┐
 *   │ ‹ 返回        帖子        │ ← 固定顶栏
 *   ├──────────────────────────┤
 *   │ 标题 / 作者 / 推荐语       │
 *   │ 操作按钮                  │ ← 只有这块滚
 *   │ 评论列表…                 │
 *   ├──────────────────────────┤
 *   │ [写点什么…]        发送    │ ← 固定底栏，永远够得着
 *   └──────────────────────────┘
 * </pre>
 */
public class PostDetailPage {

    public interface OnClose { void onClose(); }

    private final MainActivity act;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final LinearLayout commentBox;
    private final MdField input;

    private long postId;
    private Community.Post post;
    private OnClose cb;

    public PostDetailPage(MainActivity a) {
        this.act = a;
        Context c = a;
        Tokens t = Theme.t();

        root = new FrameLayout(c);
        root.setBackgroundColor(t.surface);
        root.setClickable(true);

        LinearLayout col = Ui.column(c);

        // ── 固定顶栏 ──
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(Ui.px(c, 18), Ui.statusBarHeight(c) + Ui.px(c, 8),
                Ui.px(c, 18), Ui.px(c, 8));

        HomePage.IconView back = new HomePage.IconView(c, "chevron", t.onSurfaceVariant);
        back.setRotation(180f);
        Ui.pressable(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); close(); }
        });
        top.addView(back, new LinearLayout.LayoutParams(Ui.px(c, 24), Ui.px(c, 24)));

        TextView topTitle = new TextView(c);
        topTitle.setText("帖子");
        topTitle.setTextSize(14);
        topTitle.setTypeface(Ui.tfMed());
        topTitle.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.px(c, 12);
        top.addView(topTitle, tlp);
        col.addView(top);

        // 顶栏下的细分隔线
        View divider = new View(c);
        divider.setBackgroundColor(Hct.withAlpha(t.outlineVariant, 0.55f));
        col.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(c, 1))));

        // ── 只有中间这块滚动 ──
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body = Ui.column(c);
        body.setPadding(Ui.px(c, 18), Ui.px(c, 14), Ui.px(c, 18), Ui.px(c, 18));
        scroll.addView(body);
        col.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── 固定底栏：评论输入 ──
        View divider2 = new View(c);
        divider2.setBackgroundColor(Hct.withAlpha(t.outlineVariant, 0.55f));
        col.addView(divider2, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, Ui.px(c, 1))));

        LinearLayout bottom = Ui.row(c);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(Ui.px(c, 18), Ui.px(c, 8), Ui.px(c, 18),
                Ui.navBarHeight(c) + Ui.px(c, 8));

        input = new MdField(c, "写点什么…");
        input.setTextSize(14);
        input.setOnSubmit(new Runnable() {
            @Override public void run() { sendComment(); }
        });
        bottom.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView send = new TextView(c);
        send.setText("发送");
        send.setTextSize(13.5f);
        send.setTypeface(Ui.tfBold());
        send.setTextColor(t.onPrimary);
        send.setGravity(Gravity.CENTER);
        send.setPadding(Ui.px(c, 18), Ui.px(c, 11), Ui.px(c, 18), Ui.px(c, 11));
        GradientDrawable sg = new GradientDrawable();
        sg.setColor(t.primary);
        sg.setCornerRadius(Ui.px(c, 22));
        send.setBackground(sg);
        Ui.pressable(send);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); sendComment(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.px(c, 10);
        bottom.addView(send, slp);
        col.addView(bottom);

        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        commentBox = Ui.column(c);
    }

    public View view() { return root; }
    public boolean isFor(long id) { return postId == id; }
    public void setOnClose(OnClose c) { this.cb = c; }

    public void close() { if (cb != null) cb.onClose(); }

    /** 打开某个帖子 */
    public void bind(long id) {
        this.postId = id;
        this.post = null;
        body.removeAllViews();
        commentBox.removeAllViews();
        input.setText("");

        TextView loading = new TextView(act);
        loading.setText("加载中…");
        loading.setTextColor(Hct.withAlpha(Theme.t().onSurfaceVariant, 0.8f));
        loading.setTextSize(13);
        body.addView(loading);

        Community.get(id, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (!isFor(id)) return;                 // 期间又切了别的帖子
                if (error != null) { showError(error); return; }
                post = (Community.Post) data;
                renderPost();
                loadComments(id);
            }
        });
    }

    private void showError(String msg) {
        body.removeAllViews();
        TextView tv = new TextView(act);
        tv.setText(msg);
        tv.setTextColor(Hct.withAlpha(Theme.t().onSurfaceVariant, 0.9f));
        tv.setTextSize(13.5f);
        body.addView(tv);
    }

    private void loadComments(long id) {
        Community.comments(id, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) return;
                @SuppressWarnings("unchecked")
                List<Community.Comment> cs = (List<Community.Comment>) data;
                renderComments(cs);
            }
        });
    }

    private void renderPost() {
        Context c = act;
        Tokens t = Theme.t();
        body.removeAllViews();
        if (post == null) return;

        // 标题
        TextView title = new TextView(c);
        title.setText(post.title);
        title.setTextColor(t.onSurface);
        title.setTextSize(23);
        title.setTypeface(Ui.tfBold());
        title.setLineSpacing(Ui.px(c, 4), 1f);
        body.addView(title);

        // 作者 + 开发者徽章 + 元信息
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 12));
        row.addView(authorView(c, t, post.author, post.role));
        TextView meta = new TextView(c);
        meta.setText(" 分享 · " + post.count + " 首 · ♥ " + post.likes
                + " · 浏览 " + post.views);
        meta.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.8f));
        meta.setTextSize(12);
        row.addView(meta);
        body.addView(row);

        // 推荐语
        if (post.note.length() > 0) {
            TextView nt = new TextView(c);
            nt.setText(post.note);
            nt.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
            nt.setTextSize(13.5f);
            nt.setLineSpacing(Ui.px(c, 5), 1f);
            nt.setPadding(Ui.px(c, 14), Ui.px(c, 13), Ui.px(c, 14), Ui.px(c, 13));
            GradientDrawable nb = new GradientDrawable();
            nb.setColor(t.surfaceContainerHigh);
            nb.setCornerRadius(Ui.px(c, 16));
            nt.setBackground(nb);
            body.addView(nt);
        }

        // 操作
        LinearLayout acts = Ui.row(c);
        acts.setPadding(0, Ui.px(c, 16), 0, Ui.px(c, 4));

        TextView imp = new TextView(c);
        imp.setText("导入到我的歌单");
        imp.setTextSize(14);
        imp.setTypeface(Ui.tfBold());
        imp.setTextColor(t.onPrimary);
        imp.setGravity(Gravity.CENTER);
        imp.setPadding(0, Ui.px(c, 13), 0, Ui.px(c, 13));
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(t.primary);
        ib.setCornerRadius(Ui.px(c, 24));
        imp.setBackground(ib);
        Ui.pressable(imp);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); doImport(); }
        });
        acts.addView(imp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView lk = new TextView(c);
        lk.setText("♥ 赞");
        lk.setTextSize(14);
        lk.setTypeface(Ui.tfMed());
        lk.setTextColor(t.onSecondaryContainer);
        lk.setGravity(Gravity.CENTER);
        lk.setPadding(Ui.px(c, 20), Ui.px(c, 13), Ui.px(c, 20), Ui.px(c, 13));
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(t.secondaryContainer);
        lb.setCornerRadius(Ui.px(c, 24));
        lk.setBackground(lb);
        Ui.pressable(lk);
        lk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); doLike(); }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Ui.px(c, 10);
        acts.addView(lk, llp);
        body.addView(acts);

        TextView rp = new TextView(c);
        rp.setText("举报这个歌单");
        rp.setTextSize(11.5f);
        rp.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.55f));
        rp.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 6));
        rp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); doReport(); }
        });
        body.addView(rp);

        // 评论区标题
        TextView ct = new TextView(c);
        ct.setText("评论");
        ct.setTextSize(15);
        ct.setTypeface(Ui.tfBold());
        ct.setTextColor(t.onSurface);
        ct.setPadding(0, Ui.px(c, 20), 0, Ui.px(c, 8));
        body.addView(ct);
        body.addView(commentBox);
    }

    private void renderComments(List<Community.Comment> cs) {
        Context c = act;
        Tokens t = Theme.t();
        commentBox.removeAllViews();
        if (cs == null || cs.isEmpty()) {
            TextView tv = new TextView(c);
            tv.setText("还没有评论，来说两句");
            tv.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.7f));
            tv.setTextSize(12.5f);
            commentBox.addView(tv);
            return;
        }
        for (Community.Comment cm : cs) {
            LinearLayout row = Ui.column(c);
            row.setPadding(0, Ui.px(c, 8), 0, Ui.px(c, 8));
            LinearLayout aRow = Ui.row(c);
            aRow.setGravity(Gravity.CENTER_VERTICAL);
            aRow.addView(authorView(c, t, cm.author, cm.role));
            row.addView(aRow);
            TextView b = new TextView(c);
            b.setText(cm.body);
            b.setTextSize(13.5f);
            b.setTextColor(t.onSurface);
            b.setLineSpacing(Ui.px(c, 4), 1f);
            b.setPadding(0, Ui.px(c, 3), 0, 0);
            row.addView(b);
            commentBox.addView(row);
        }
    }

    /** 作者名 + 开发者徽章 */
    private LinearLayout authorView(Context c, Tokens t, String name, int role) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = new TextView(c);
        n.setText(name);
        n.setTextSize(12.5f);
        n.setTypeface(Ui.tfBold());
        n.setTextColor(Hct.withAlpha(t.primary, 0.95f));
        row.addView(n);
        if (role == 1) {
            TextView badge = new TextView(c);
            badge.setText("开发者");
            badge.setTextSize(9.5f);
            badge.setTypeface(Ui.tfBold());
            badge.setTextColor(t.onPrimaryContainer);
            badge.setPadding(Ui.px(c, 7), Ui.px(c, 2), Ui.px(c, 7), Ui.px(c, 2));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.primaryContainer);
            bg.setCornerRadius(Ui.px(c, 20));
            badge.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.px(c, 5);
            row.addView(badge, lp);
        }
        return row;
    }

    // ── 动作 ────────────────────────────────────────────────────────────────
    private void doImport() {
        if (post == null) return;
        if (post.payload.length() == 0) { toast("这个歌单内容为空"); return; }
        Playlist.ImportResult r = Playlist.parseImport(post.payload, post.title);
        if (r.error != null) { toast(r.error); return; }
        String name = r.item.name, base = name;
        int n = 2;
        for (Playlist.Item it : Playlist.all()) {
            if (it.name.equals(name)) { name = base + " (" + n + ")"; n++; }
        }
        r.item.name = name;
        Playlist.Item created = Playlist.create(act, name);
        int ok = 0, needSelf = 0;
        for (Song sg : r.item.songs) {
            if (Playlist.add(act, created.id, sg)) ok++;
            if (sg.localOnly && !sg.online) needSelf++;
        }
        act.refreshAllPages();

        StringBuilder msg = new StringBuilder();
        msg.append("「").append(name).append("」已加入你的歌单，共 ").append(ok).append(" 首。\n");
        msg.append("到「曲库 → 歌单」里就能看到。");
        if (needSelf > 0) {
            msg.append("\n\n注意：其中有 ").append(needSelf).append(" 首是对方自己导入的本地歌曲，")
               .append("没能匹配到在线音源，所以暂时放不了 —— ")
               .append("需要你自己导入对应的音频文件才能听。");
        }
        new MdDialog.Builder(act)
                .title("导入成功")
                .message(msg.toString())
                .positive("好", null)
                .show();
    }

    private void doLike() {
        if (post == null) return;
        if (!Community.loggedIn(act)) { toast("先登录才能点赞"); return; }
        Community.like(act, postId, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { toast(error); return; }
                Object liked = Json.path(data, "liked");
                toast("true".equals(String.valueOf(liked)) ? "已点赞" : "已取消点赞");
                bind(postId);   // 刷新，但保持在这一页
            }
        });
    }

    private void sendComment() {
        final String text = input.text().trim();
        if (text.length() == 0) return;
        if (!Community.loggedIn(act)) { toast("先登录才能评论"); return; }
        Community.comment(act, postId, text, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { toast(error); return; }
                input.setText("");
                loadComments(postId);
                // 滚到底部，让用户立刻看到自己刚发的
                scroll.post(new Runnable() {
                    @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    private void doReport() {
        if (!Community.loggedIn(act)) { toast("先登录才能举报"); return; }
        final MdField f = new MdField(act, "举报理由");
        new MdDialog.Builder(act)
                .title("举报这个歌单")
                .message("请简单说明原因（比如内容不当、广告）。")
                .content(f)
                .negative("取消", null)
                .positive("提交", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String r = f.text().trim();
                        if (r.length() == 0) return;
                        Community.report(act, postId, r, new Community.Callback() {
                            @Override public void onResult(Object d, String e) {
                                toast(e == null ? "已收到举报，我们会尽快处理" : e);
                            }
                        });
                    }
                }).show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
