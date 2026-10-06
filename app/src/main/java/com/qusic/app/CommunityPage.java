package com.qusic.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 社区页。
 *
 * <p>体积考虑：**不新增任何资源**，图标全部复用已有的，
 * 控件也复用 {@link MdDialog} / {@link MdField} / 曲库页的卡片样式。
 */
public class CommunityPage {

    private final MainActivity act;
    private final View root;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView whoLabel, statusLabel;

    private String sort = "new";
    private String query = "";
    /** 0=列表，否则是正在看的帖子 id */
    private long openId;
    private boolean loading;

    public CommunityPage(MainActivity a) {
        this.act = a;
        Context c = a;
        Tokens t = Theme.t();
        int pad = Ui.px(c, 18);

        LinearLayout col = Ui.column(c);
        col.setBackgroundColor(t.surface);

        // ── 顶部 ──
        LinearLayout head = Ui.column(c);
        // 顶部要避开状态栏，否则标题被压住一半
        head.setPadding(pad, Ui.statusBarHeight(c) + Ui.px(c, 10), pad, Ui.px(c, 6));

        TextView title = new TextView(c);
        title.setText("社区");
        title.setTextColor(t.onSurface);
        title.setTextSize(26);
        title.setTypeface(Ui.tfBold());
        head.addView(title);

        LinearLayout whoRow = Ui.row(c);
        whoRow.setPadding(0, Ui.px(c, 6), 0, Ui.px(c, 10));
        whoLabel = new TextView(c);
        whoLabel.setTextSize(12);
        whoLabel.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        whoRow.addView(whoLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView authBtn = new TextView(c);
        authBtn.setTextSize(12.5f);
        authBtn.setTypeface(Ui.tfBold());
        authBtn.setTextColor(t.primary);
        authBtn.setPadding(Ui.px(c, 12), Ui.px(c, 6), Ui.px(c, 4), Ui.px(c, 6));
        authBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (Community.loggedIn(act)) showLogout(); else showAuth();
            }
        });
        head.addView(whoRow);

        // 分段：最新 / 最热
        LibraryPage.SegmentedBar seg =
                new LibraryPage.SegmentedBar(c, new String[]{"最新", "最热"});
        seg.setOnChange(new LibraryPage.SegmentedBar.OnChange() {
            @Override public void onChange(int i) {
                sort = i == 0 ? "new" : "hot";
                openId = 0;
                load();
            }
        });
        head.addView(seg, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(c, 42)));

        // 搜索
        LinearLayout searchBox = Ui.row(c);
        searchBox.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 8));
        MdField sf = new MdField(c, "搜索歌单标题");
        sf.setTextSize(14);
        sf.setOnSubmit(new Runnable() {
            @Override public void run() {
                query = sf.text().trim();
                openId = 0;
                load();
            }
        });
        searchBox.addView(sf, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(searchBox);

        statusLabel = new TextView(c);
        statusLabel.setTextSize(12);
        statusLabel.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.8f));
        statusLabel.setPadding(0, 0, 0, Ui.px(c, 6));
        head.addView(statusLabel);

        col.addView(head);

        // ── 内容 ──
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        body = Ui.column(c);
        body.setPadding(pad, 0, pad, act.contentBottomInset());
        scroll.addView(body);
        col.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        root = col;
        refreshWho();
    }

    public View view() { return root; }

    /** 每次切到本页时调用 */
    public void onShown() {
        refreshWho();
        if (body.getChildCount() == 0) load();
    }

    private void refreshWho() {
        Tokens t = Theme.t();
        if (Community.loggedIn(act)) {
            whoLabel.setText("已登录：" + Community.userName(act));
        } else {
            whoLabel.setText("未登录 · 登录后可以发布歌单、点赞和评论");
        }
    }

    // ── 列表 ────────────────────────────────────────────────────────────────
    public void load() {
        if (loading) return;
        loading = true;
        statusLabel.setText("加载中…");
        Community.list(sort, 1, query, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                loading = false;
                if (error != null) { statusLabel.setText(error); return; }
                @SuppressWarnings("unchecked")
                List<Community.Post> items = (List<Community.Post>) data;
                statusLabel.setText(items.isEmpty()
                        ? (query.length() > 0 ? "没搜到相关歌单" : "还没有人分享，来做第一个吧")
                        : items.size() + " 个歌单");
                buildList(items);
            }
        });
    }

    private void buildList(List<Community.Post> items) {
        Context c = act;
        Tokens t = Theme.t();
        body.removeAllViews();
        for (final Community.Post p : items) {
            LinearLayout card = Ui.column(c);
            card.setPadding(Ui.px(c, 16), Ui.px(c, 14), Ui.px(c, 16), Ui.px(c, 14));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.surfaceContainerHigh);
            bg.setCornerRadius(Ui.px(c, 18));
            card.setBackground(bg);

            TextView tt = new TextView(c);
            tt.setText(p.title);
            tt.setTextColor(t.onSurface);
            tt.setTextSize(15.5f);
            tt.setTypeface(Ui.tfBold());
            tt.setMaxLines(2);
            card.addView(tt);

            if (p.note.length() > 0) {
                TextView nt = new TextView(c);
                nt.setText(p.note);
                nt.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
                nt.setTextSize(12.5f);
                nt.setMaxLines(2);
                nt.setPadding(0, Ui.px(c, 4), 0, 0);
                card.addView(nt);
            }

            TextView meta = new TextView(c);
            meta.setText(p.author + " · " + p.count + " 首 · ♥ " + p.likes
                    + " · 浏览 " + p.views);
            meta.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.7f));
            meta.setTextSize(11.5f);
            meta.setPadding(0, Ui.px(c, 8), 0, 0);
            card.addView(meta);

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticLight(v);
                    openId = p.id;
                    openPost(p.id);
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.px(c, 10);
            body.addView(card, lp);
        }
    }

    // ── 详情 ────────────────────────────────────────────────────────────────
    public void openPost(final long id) {
        statusLabel.setText("加载中…");
        Community.get(id, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { statusLabel.setText(error); return; }
                buildDetail((Community.Post) data);
                Community.comments(id, new Community.Callback() {
                    @Override public void onResult(Object d2, String e2) {
                        if (e2 == null) {
                            @SuppressWarnings("unchecked")
                            List<Community.Comment> cs = (List<Community.Comment>) d2;
                            renderComments(cs);
                        }
                    }
                });
            }
        });
    }

    private LinearLayout commentBox;

    private void buildDetail(final Community.Post p) {
        Context c = act;
        Tokens t = Theme.t();
        body.removeAllViews();
        statusLabel.setText("");

        TextView back = new TextView(c);
        back.setText("‹ 返回列表");
        back.setTextSize(13);
        back.setTypeface(Ui.tfMed());
        back.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
        back.setPadding(0, 0, 0, Ui.px(c, 10));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); openId = 0; load(); }
        });
        body.addView(back);

        TextView tt = new TextView(c);
        tt.setText(p.title);
        tt.setTextColor(t.onSurface);
        tt.setTextSize(20);
        tt.setTypeface(Ui.tfBold());
        body.addView(tt);

        TextView meta = new TextView(c);
        meta.setText(p.author + " 分享 · " + p.count + " 首 · ♥ " + p.likes
                + " · 浏览 " + p.views);
        meta.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.8f));
        meta.setTextSize(12);
        meta.setPadding(0, Ui.px(c, 6), 0, Ui.px(c, 10));
        body.addView(meta);

        if (p.note.length() > 0) {
            TextView nt = new TextView(c);
            nt.setText(p.note);
            nt.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
            nt.setTextSize(13.5f);
            nt.setLineSpacing(Ui.px(c, 4), 1f);
            nt.setPadding(Ui.px(c, 14), Ui.px(c, 12), Ui.px(c, 14), Ui.px(c, 12));
            GradientDrawable nb = new GradientDrawable();
            nb.setColor(t.surfaceContainerHighest);
            nb.setCornerRadius(Ui.px(c, 14));
            nt.setBackground(nb);
            body.addView(nt);
        }

        // 主按钮：导入
        LinearLayout btnRow = Ui.row(c);
        btnRow.setPadding(0, Ui.px(c, 16), 0, Ui.px(c, 4));

        TextView imp = new TextView(c);
        imp.setText("导入到我的歌单");
        imp.setTextSize(13.5f);
        imp.setTypeface(Ui.tfBold());
        imp.setTextColor(t.onPrimary);
        imp.setGravity(Gravity.CENTER);
        imp.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 12));
        GradientDrawable ib = new GradientDrawable();
        ib.setColor(t.primary);
        ib.setCornerRadius(Ui.px(c, 22));
        imp.setBackground(ib);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); doImport(p); }
        });
        btnRow.addView(imp, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView lk = new TextView(c);
        lk.setText("♥ 赞");
        lk.setTextSize(13.5f);
        lk.setTypeface(Ui.tfMed());
        lk.setTextColor(t.onSecondaryContainer);
        lk.setGravity(Gravity.CENTER);
        lk.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 12));
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(t.secondaryContainer);
        lb.setCornerRadius(Ui.px(c, 22));
        lk.setBackground(lb);
        lk.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); doLike(p.id); }
        });
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.5f);
        llp.leftMargin = Ui.px(c, 8);
        btnRow.addView(lk, llp);
        body.addView(btnRow);

        // 举报（很小的文字按钮，不抢眼但要存在）
        TextView rp = new TextView(c);
        rp.setText("举报这个歌单");
        rp.setTextSize(11.5f);
        rp.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.6f));
        rp.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 4));
        rp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); doReport(p.id); }
        });
        body.addView(rp);

        // 评论区
        TextView cTitle = new TextView(c);
        cTitle.setText("评论");
        cTitle.setTextSize(14);
        cTitle.setTypeface(Ui.tfBold());
        cTitle.setTextColor(t.onSurface);
        cTitle.setPadding(0, Ui.px(c, 18), 0, Ui.px(c, 6));
        body.addView(cTitle);

        commentBox = Ui.column(c);
        body.addView(commentBox);

        // 发表评论
        LinearLayout inputRow = Ui.row(c);
        inputRow.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 10));
        final MdField cf = new MdField(c, "写点什么…");
        cf.setTextSize(14);
        cf.setOnSubmit(new Runnable() {
            @Override public void run() {
                String s = cf.text().trim();
                if (s.length() == 0) return;
                doComment(p.id, s, cf);
            }
        });
        inputRow.addView(cf, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(inputRow);

        scroll.post(new Runnable() { @Override public void run() { scroll.scrollTo(0, 0); } });
    }

    private void renderComments(List<Community.Comment> cs) {
        if (commentBox == null) return;
        Context c = act;
        Tokens t = Theme.t();
        commentBox.removeAllViews();
        if (cs.isEmpty()) {
            TextView tv = new TextView(c);
            tv.setText("还没有评论");
            tv.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.7f));
            tv.setTextSize(12.5f);
            commentBox.addView(tv);
            return;
        }
        for (Community.Comment cm : cs) {
            LinearLayout row = Ui.column(c);
            row.setPadding(0, Ui.px(c, 7), 0, Ui.px(c, 7));
            TextView a = new TextView(c);
            a.setText(cm.author);
            a.setTextSize(12);
            a.setTypeface(Ui.tfBold());
            a.setTextColor(Hct.withAlpha(t.primary, 0.9f));
            row.addView(a);
            TextView b = new TextView(c);
            b.setText(cm.body);
            b.setTextSize(13.5f);
            b.setTextColor(t.onSurface);
            b.setLineSpacing(Ui.px(c, 3), 1f);
            row.addView(b);
            commentBox.addView(row);
        }
    }

    // ── 动作 ────────────────────────────────────────────────────────────────
    private void doImport(final Community.Post p) {
        if (p.payload.length() == 0) { toast("这个歌单内容为空"); return; }
        Playlist.ImportResult r = Playlist.parseImport(p.payload, p.title);
        if (r.error != null) { toast(r.error); return; }
        // 重名加序号
        String name = r.item.name, base = name;
        int n = 2;
        for (Playlist.Item it : Playlist.all()) {
            if (it.name.equals(name)) { name = base + " (" + n + ")"; n++; }
        }
        r.item.name = name;
        Playlist.Item created = Playlist.create(act, name);
        int ok = 0;
        for (Song sg : r.item.songs) if (Playlist.add(act, created.id, sg)) ok++;
        act.refreshAllPages();
        new MdDialog.Builder(act)
                .title("导入成功")
                .message("「" + name + "」已加入你的歌单，共 " + ok + " 首。\n"
                        + "到「曲库 → 歌单」里就能看到。")
                .positive("好", null)
                .show();
    }

    private void doLike(final long id) {
        if (!Community.loggedIn(act)) { showAuth(); return; }
        Community.like(act, id, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { toast(error); return; }
                Object liked = Json.path(data, "liked");
                toast("true".equals(String.valueOf(liked)) ? "已点赞" : "已取消点赞");
                openPost(id);
            }
        });
    }

    private void doComment(final long id, final String text, final MdField field) {
        if (!Community.loggedIn(act)) { showAuth(); return; }
        Community.comment(act, id, text, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { toast(error); return; }
                field.setText("");
                openPost(id);
            }
        });
    }

    private void doReport(final long id) {
        if (!Community.loggedIn(act)) { showAuth(); return; }
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
                        Community.report(act, id, r, new Community.Callback() {
                            @Override public void onResult(Object d, String e) {
                                toast(e == null ? "已收到举报，我们会尽快处理" : e);
                            }
                        });
                    }
                }).show();
    }

    /** 从「曲库 → 歌单」调起的「分享到社区」 */
    public void share(final Playlist.Item it) {
        if (!Community.loggedIn(act)) {
            new MdDialog.Builder(act)
                    .title("需要先登录")
                    .message("分享歌单到社区需要一个账号。")
                    .negative("取消", null)
                    .positive("登录 / 注册", new MdDialog.OnClick() {
                        @Override public void onClick() { showAuth(); }
                    }).show();
            return;
        }
        if (it.size() == 0) { toast("空歌单没什么可分享的"); return; }

        final MdField titleF = new MdField(act, "标题");
        titleF.setText(it.name);
        final MdField noteF = new MdField(act, "推荐语（可选）");

        LinearLayout box = Ui.column(act);
        box.addView(titleF);
        box.addView(noteF);

        new MdDialog.Builder(act)
                .title("分享到社区")
                .message("会分享歌单的曲目信息，不含任何音频文件。")
                .content(box)
                .negative("取消", null)
                .positive("发布", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String t = titleF.text().trim();
                        if (t.length() == 0) { toast("标题不能为空"); return; }
                        String json = Playlist.exportJson(it);
                        Community.publish(act, t, noteF.text().trim(), json,
                                new Community.Callback() {
                            @Override public void onResult(Object d, String e) {
                                toast(e == null ? "已发布到社区" : e);
                                if (e == null) { openId = 0; load(); }
                            }
                        });
                    }
                }).show();
    }

    // ── 登录 / 注册 ─────────────────────────────────────────────────────────
    private void showAuth() {
        final MdField nameF = new MdField(act, "用户名");
        final MdField mailF = new MdField(act, "邮箱（注册才需要）");
        final MdField passF = new MdField(act, "密码");
        passF.setPassword(true);

        LinearLayout box = Ui.column(act);
        box.addView(nameF);
        box.addView(mailF);
        box.addView(passF);

        new MdDialog.Builder(act)
                .title("登录 / 注册")
                .message("填邮箱就是注册新账号，不填就是登录已有账号。\n"
                        + "注册后要去邮箱点验证链接，才能发布和评论。")
                .content(box)
                .negative("取消", null)
                .positive("继续", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String n = nameF.text().trim();
                        String m = mailF.text().trim();
                        String p = passF.text();
                        if (n.length() == 0 || p.length() == 0) { toast("用户名和密码都要填"); return; }
                        if (m.length() > 0) doRegister(n, m, p); else doLogin(n, p);
                    }
                }).show();
    }

    private void doLogin(String n, String p) {
        Community.login(act, n, p, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { toast(error); return; }
                refreshWho();
                if ("0".equals(String.valueOf(data))) {
                    toast("登录成功，但邮箱还没验证，去收件箱点一下链接");
                } else {
                    toast("登录成功");
                }
                load();
            }
        });
    }

    private void doRegister(final String n, final String m, final String p) {
        Community.register(act, n, m, p, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                if (error != null) { toast(error); return; }
                Object sent = Json.path(data, "mail_sent");
                new MdDialog.Builder(act)
                        .title("注册成功")
                        .message("true".equals(String.valueOf(sent))
                                ? "验证邮件已发出，去收件箱点一下链接，然后回来登录。"
                                : "账号建好了，但验证邮件没发出去。请让管理员检查服务器的邮件配置。")
                        .positive("好", null)
                        .show();
            }
        });
    }

    private void showLogout() {
        new MdDialog.Builder(act)
                .title("退出登录")
                .message("当前账号：" + Community.userName(act))
                .negative("取消", null)
                .positive("退出", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        Community.logout(act);
                        refreshWho();
                        openId = 0;
                        load();
                    }
                }).show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(act, s, android.widget.Toast.LENGTH_SHORT).show();
    }
}
