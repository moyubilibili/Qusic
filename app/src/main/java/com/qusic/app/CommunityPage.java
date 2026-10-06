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
    private TextView whoLabel, statusLabel, authBtn, authHint;
    private LinearLayout authCard;

    /** 板块：0 = 歌单分享，1 = 论坛 */
    private int board = Community.BOARD_PLAYLIST;
    /** 当前过滤的标签，空 = 不过滤 */
    private String tagFilter = "";
    private LibraryPage.SegmentedBar boardBar;
    private TextView postBtn;
    /**
     * 一次性标志：程序性地切板块时不要把标签过滤清掉。
     *
     * <p>用户手动切板块 → 清标签（合理，标签只属于论坛）；
     * 但「点标签跳过来」也会触发板块切换，那次**必须保留**标签，
     * 否则刚点的话题立刻被清空，看起来就是「点了没反应」。
     * 用标志而不是判断调用来源，是因为 {@code select(1, true)} 的
     * onChange 是动画结束后异步触发的，那时已经分不清是谁发起的了。
     */
    private boolean keepTagOnBoardChange;
    /** 当前标签过滤条（显示「#xxx  ×」） */
    private TextView tagBar;
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

        // 登录状态条：未登录时做成一眼就能看到的大按钮，
        // 之前只是一个不起眼的小文字，很多人根本找不到登录入口。
        authCard = Ui.column(c);
        authCard.setPadding(Ui.px(c, 16), Ui.px(c, 14), Ui.px(c, 16), Ui.px(c, 14));
        GradientDrawable acBg = new GradientDrawable();
        acBg.setColor(t.secondaryContainer);
        acBg.setCornerRadius(Ui.px(c, 18));
        authCard.setBackground(acBg);

        whoLabel = new TextView(c);
        whoLabel.setTextSize(13.5f);
        whoLabel.setTypeface(Ui.tfBold());
        whoLabel.setTextColor(t.onSecondaryContainer);
        authCard.addView(whoLabel);

        authHint = new TextView(c);
        authHint.setTextSize(12);
        authHint.setTextColor(Hct.withAlpha(t.onSecondaryContainer, 0.8f));
        authHint.setPadding(0, Ui.px(c, 4), 0, Ui.px(c, 12));
        authCard.addView(authHint);

        authBtn = new TextView(c);
        authBtn.setTextSize(14);
        authBtn.setTypeface(Ui.tfBold());
        authBtn.setTextColor(t.onPrimary);
        authBtn.setGravity(Gravity.CENTER);
        authBtn.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 12));
        GradientDrawable abBg = new GradientDrawable();
        abBg.setColor(t.primary);
        abBg.setCornerRadius(Ui.px(c, 22));
        authBtn.setBackground(abBg);
        Ui.pressable(authBtn);
        authBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (Community.loggedIn(act)) showLogout(); else showAuth();
            }
        });
        authCard.addView(authBtn);

        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = Ui.px(c, 4);
        acLp.bottomMargin = Ui.px(c, 12);
        head.addView(authCard, acLp);

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

        // 板块切换。放在最上面一行 —— 先选「哪个板块」，再选「怎么排」，
        // 顺序反过来的话用户会以为排序只对当前板块生效。
        boardBar = new LibraryPage.SegmentedBar(c, new String[]{"歌单分享", "论坛"});
        boardBar.setOnChange(new LibraryPage.SegmentedBar.OnChange() {
            @Override public void onChange(int i) {
                board = i == 0 ? Community.BOARD_PLAYLIST : Community.BOARD_FORUM;
                if (keepTagOnBoardChange) {
                    keepTagOnBoardChange = false;   // 消费掉，只豁免这一次
                } else {
                    tagFilter = "";                 // 用户手动切换 → 清标签
                }
                openId = 0;
                if (postBtn != null) {
                    postBtn.setVisibility(board == Community.BOARD_FORUM
                            ? View.VISIBLE : View.GONE);
                }
                // 「热门」对论坛意义不大，两个板块的排序语义也不同，
                // 切过去时统一回到最新，避免用户困惑
                sort = "new";
                load();
            }
        });
        // 板块切换 + 发帖按钮同一行。论坛板块才显示发帖 ——
        // 歌单分享的入口在曲库那边（长按歌单 → 分享），不放这里避免两个入口打架
        LinearLayout boardRow = Ui.row(c);
        boardRow.setPadding(0, 0, 0, Ui.px(c, 8));
        boardRow.addView(boardBar, new LinearLayout.LayoutParams(
                0, Ui.px(c, 42), 1f));

        postBtn = new TextView(c);
        postBtn.setText("发帖");
        postBtn.setTextSize(13);
        postBtn.setTypeface(Ui.tfBold());
        postBtn.setGravity(Gravity.CENTER);
        postBtn.setTextColor(t.onPrimary);
        android.graphics.drawable.GradientDrawable pb =
                new android.graphics.drawable.GradientDrawable();
        pb.setColor(t.primary);
        pb.setCornerRadius(Ui.px(c, 21));
        postBtn.setBackground(pb);
        postBtn.setPadding(Ui.px(c, 16), 0, Ui.px(c, 16), 0);
        postBtn.setVisibility(View.GONE);
        postBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                new ForumComposer(act).show(new ForumComposer.OnPosted() {
                    @Override public void posted(long id) { openId = 0; load(); }
                });
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.px(c, 42));
        plp.leftMargin = Ui.px(c, 8);
        boardRow.addView(postBtn, plp);
        head.addView(boardRow);

        // 搜索
        LinearLayout searchBox = Ui.row(c);
        searchBox.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 8));
        MdField sf = new MdField(c, "搜索标题或正文");
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

        // 标签过滤条。只在有标签时出现，带一个 × 用来清掉 ——
        // 之前点了标签就出不来了，只能切一下板块才能回到全部列表，
        // 而切板块又会清标签，等于没有「清除」这个动作。
        tagBar = new TextView(c);
        tagBar.setTextSize(12.5f);
        tagBar.setTypeface(Ui.tfMed());
        tagBar.setPadding(Ui.px(c, 12), Ui.px(c, 7), Ui.px(c, 12), Ui.px(c, 7));
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(Hct.withAlpha(t.primary, 0.14f));
        tg.setCornerRadius(Ui.px(c, 15));
        tagBar.setBackground(tg);
        tagBar.setTextColor(t.primary);
        tagBar.setVisibility(View.GONE);
        tagBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                tagFilter = "";
                load();
            }
        });
        head.addView(tagBar);

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
        if (whoLabel == null) return;
        boolean in = Community.loggedIn(act);

        // 登录后整块卡片收成一行，把版面让给歌单列表 ——
        // 之前登录了还占着一大块，很浪费空间，也不像个「已登录」该有的样子。
        whoLabel.setText(in ? "已登录：" + Community.userName(act) : "还没有登录");
        authHint.setVisibility(in ? View.GONE : View.VISIBLE);
        if (!in) {
            authHint.setText("登录后就能发布自己的歌单、点赞和评论。\n没有账号的话，填上邮箱就是注册。");
        }

        // 登录后按钮变成一个小号的「退出」，不再是大主按钮
        authBtn.setText(in ? "退出登录" : "登录 / 注册");
        int padH = Ui.px(act, in ? 14 : 0);
        int padV = Ui.px(act, in ? 7 : 12);
        authBtn.setPadding(padH, padV, padH, padV);
        authBtn.setTextSize(in ? 12.5f : 14);
        authBtn.setTextColor(in ? Hct.withAlpha(Theme.t().onSurfaceVariant, 0.95f)
                                : Theme.t().onPrimary);
        GradientDrawable g = new GradientDrawable();
        g.setColor(in ? Theme.t().surfaceContainerHighest : Theme.t().primary);
        g.setCornerRadius(Ui.px(act, 22));
        authBtn.setBackground(g);

        // 卡片底色也换掉：登录后从「强调容器」变成中性
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(in ? Theme.t().surfaceContainerLow : Theme.t().secondaryContainer);
        cg.setCornerRadius(Ui.px(act, 18));
        authCard.setBackground(cg);
        int cp = Ui.px(act, in ? 12 : 16);
        authCard.setPadding(cp, cp, cp, cp);
        authCard.setOrientation(LinearLayout.VERTICAL);
        whoLabel.setTextColor(in ? Theme.t().onSurface : Theme.t().onSecondaryContainer);
        whoLabel.setTextSize(in ? 12.5f : 13.5f);

        // 登录后按钮靠右、变小，不再通栏
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) authBtn.getLayoutParams();
        if (lp != null) {
            lp.width = in ? LinearLayout.LayoutParams.WRAP_CONTENT
                          : LinearLayout.LayoutParams.MATCH_PARENT;
            lp.gravity = in ? Gravity.END : Gravity.NO_GRAVITY;
            lp.topMargin = Ui.px(act, in ? 8 : 0);
            authBtn.setLayoutParams(lp);
        }
    }

    // ── 列表 ────────────────────────────────────────────────────────────────
    public void load() {
        if (loading) return;
        loading = true;
        if (tagBar != null) {
            if (tagFilter.length() > 0) {
                tagBar.setText("#" + tagFilter + "   ✕ 点这里清除");
                tagBar.setVisibility(View.VISIBLE);
            } else {
                tagBar.setVisibility(View.GONE);
            }
        }
        statusLabel.setText("加载中…");
        Community.listBoard(board, sort, 1, query, tagFilter, new Community.Callback() {
            @Override public void onResult(Object data, String error) {
                loading = false;
                if (error != null) { statusLabel.setText(error); return; }
                @SuppressWarnings("unchecked")
                List<Community.Post> items = (List<Community.Post>) data;
                String what = board == Community.BOARD_FORUM ? "帖子" : "歌单";
                if (tagFilter.length() > 0) {
                    statusLabel.setText("#" + tagFilter + " · " + items.size() + " 条");
                } else if (items.isEmpty()) {
                    statusLabel.setText(query.length() > 0
                            ? "没搜到相关" + what
                            : (board == Community.BOARD_FORUM
                                    ? "论坛还没人发言，来说点什么吧"
                                    : "还没有人分享，来做第一个吧"));
                } else {
                    statusLabel.setText(items.size() + " 条" + what);
                }
                buildList(items);
            }
        });
    }

    /**
     * 点标签后跳到这里。
     *
     * <p>会自动切到「论坛」板块 —— 标签只用在论坛帖上，
     * 如果人还在歌单板块，过滤完会是一片空白，看起来像坏了。
     */
    public void setTagAndLoad(String tag) {
        tagFilter = tag == null ? "" : tag;
        openId = 0;
        query = "";
        board = Community.BOARD_FORUM;
        keepTagOnBoardChange = true;      // 板块切换的 onChange 会消费掉它
        if (boardBar != null) boardBar.select(1, true);
        if (postBtn != null) postBtn.setVisibility(View.VISIBLE);
        load();
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

            LinearLayout metaRow = Ui.row(c);
            metaRow.setPadding(0, Ui.px(c, 8), 0, 0);
            metaRow.setGravity(Gravity.CENTER_VERTICAL);
            metaRow.addView(authorView(c, t, p.author, p.role));
            TextView meta = new TextView(c);
            meta.setText(" · " + p.count + " 首 · ♥ " + p.likes + " · 浏览 " + p.views);
            meta.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.7f));
            meta.setTextSize(11.5f);
            metaRow.addView(meta);
            card.addView(metaRow);

            Ui.pressable(card);
            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticLight(v);
                    // 打开独立的二级页面 —— 详情不再挤在列表页里
                    act.openPostDetail(p.id);
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
        Ui.pressable(back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); goBackToList(); }
        });
        body.addView(back);

        TextView tt = new TextView(c);
        tt.setText(p.title);
        tt.setTextColor(t.onSurface);
        tt.setTextSize(20);
        tt.setTypeface(Ui.tfBold());
        body.addView(tt);

        LinearLayout mRow = Ui.row(c);
        mRow.setPadding(0, Ui.px(c, 6), 0, Ui.px(c, 10));
        mRow.setGravity(Gravity.CENTER_VERTICAL);
        mRow.addView(authorView(c, t, p.author, p.role));
        TextView meta = new TextView(c);
        meta.setText(" 分享 · " + p.count + " 首 · ♥ " + p.likes + " · 浏览 " + p.views);
        meta.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.8f));
        meta.setTextSize(12);
        mRow.addView(meta);
        body.addView(mRow);

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
        Ui.pressable(imp);
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
        Ui.pressable(lk);
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
            LinearLayout aRow = Ui.row(c);
            aRow.setGravity(Gravity.CENTER_VERTICAL);
            aRow.addView(authorView(c, t, cm.author, cm.role));
            row.addView(aRow);
            TextView b = new TextView(c);
            b.setText(cm.body);
            b.setTextSize(13.5f);
            b.setTextColor(t.onSurface);
            b.setLineSpacing(Ui.px(c, 3), 1f);
            row.addView(b);
            commentBox.addView(row);
        }
    }

    /**
     * 作者名，带「开发者」徽章。
     *
     * <p>徽章是 MD3 的**小号 assist chip** 造型：主色容器底 + 主色文字 + 全圆角，
     * 尺寸压到最小，不抢歌单标题的视觉重心。
     */
    private LinearLayout authorView(Context c, Tokens t, String name, int role) {
        LinearLayout row = Ui.row(c);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView n = new TextView(c);
        n.setText(name);
        n.setTextSize(12);
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
            bg.setCornerRadius(Ui.px(c, 20));   // 全圆角 = MD3 chip 造型
            badge.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.px(c, 5);
            row.addView(badge, lp);
        }
        return row;
    }

    /** 回到列表（详情页的返回） */
    public void goBackToList() {
        openId = 0;
        load();
    }

    /** 详情已改为独立二级页面，这里恒为 false（保留兼容） */
    public boolean inDetail() { return false; }

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

        // ── 本地曲目提醒 ──
        // 本地歌只有歌名能被分享出去，音频文件不会（也不该）上传。
        // 别人导入后会看到歌名却放不了，所以这里主动提出帮忙找在线版。
        int localN = Playlist.countLocal(it);
        final boolean[] doMatch = { localN > 0 };

        if (localN > 0) {
            android.widget.CheckBox cb = new android.widget.CheckBox(act);
            cb.setText("帮我在网上找同样的歌（推荐）");
            cb.setTextSize(13);
            cb.setChecked(true);
            cb.setTextColor(Theme.t().onSurface);
            cb.setPadding(0, Ui.px(act, 10), 0, 0);
            cb.setOnCheckedChangeListener(
                    new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(
                        android.widget.CompoundButton b, boolean checked) {
                    doMatch[0] = checked;
                }
            });
            box.addView(cb);
        }

        new MdDialog.Builder(act)
                .title("分享到社区")
                .message(localN > 0
                        ? ("这张歌单里有 " + localN + " 首是你自己导入的本地歌曲。\n"
                           + "别人没有这些文件，直接分享的话他们只能看到歌名、听不了。\n\n"
                           + "我可以拿「歌名 + 歌手 + 时长」去酷我/网易云找同一首，"
                           + "找到的话别人就能直接播放。")
                        : "会分享歌单的曲目信息，不含任何音频文件。")
                .content(box)
                .negative("取消", null)
                .positive(localN > 0 ? "分享" : "发布", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String t = titleF.text().trim();
                        if (t.length() == 0) { toast("标题不能为空"); return; }
                        if (localN > 0 && doMatch[0]) {
                            matchThenPublish(it, t, noteF.text().trim());
                        } else {
                            publishNow(it, t, noteF.text().trim(), null);
                        }
                    }
                }).show();
    }

    /** 逐个把本地曲目匹配到在线音源，然后发布 */
    private void matchThenPublish(final Playlist.Item it, final String title,
                                  final String note) {
        final List<Song> locals = new ArrayList<>();
        for (Song s : it.songs) if (!s.online) locals.add(s);

        final java.util.Map<Long, Song> matches = new java.util.HashMap<>();

        // 进度提示用自定义 content 视图 —— MdDialog 不暴露内部 TextView，
        // 想中途改文字就得自己塞一个进来。
        final TextView progText = new TextView(act);
        progText.setTextSize(13.5f);
        progText.setTextColor(Hct.withAlpha(Theme.t().onSurfaceVariant, 0.95f));
        progText.setLineSpacing(Ui.px(act, 4), 1f);
        progText.setText("正在匹配…");

        final android.app.Dialog prog = new MdDialog.Builder(act)
                .title("正在匹配在线音源")
                .message("共 " + locals.size() + " 首本地歌曲，"
                        + "拿「歌名 + 歌手 + 时长」去酷我 / 网易云找同一首。")
                .content(progText)
                .cancelable(false)
                .positive("取消", null)
                .show();

        matchOne(it, locals, 0, matches, new Runnable() {
            @Override public void run() {
                if (prog != null && prog.isShowing()) prog.dismiss();
                int ok = matches.size();
                int fail = locals.size() - ok;
                String msg = "匹配完成：" + ok + " / " + locals.size() + " 首找到了在线版本。";
                if (fail > 0) {
                    msg += "\n剩下 " + fail + " 首没找到，"
                         + "会在歌单里标注「待你自己导入」，不影响其他曲目播放。";
                }
                new MdDialog.Builder(act)
                        .title("匹配结果")
                        .message(msg)
                        .negative("取消分享", null)
                        .positive("继续发布", new MdDialog.OnClick() {
                            @Override public void onClick() {
                                publishNow(it, title, note, matches);
                            }
                        }).show();
            }
        }, new Runnable() {
            @Override public void run() {
                progText.setText("已处理 " + (matches.size() + 1) + " / " + locals.size()
                        + " 首，找到 " + matches.size() + " 首");
            }
        });
    }

    private void matchOne(final Playlist.Item it, final List<Song> locals, final int idx,
                          final java.util.Map<Long, Song> matches,
                          final Runnable done, final Runnable tick) {
        if (idx >= locals.size()) { done.run(); return; }
        tick.run();
        SongMatch.match(act, locals.get(idx), new SongMatch.Callback() {
            @Override public void onResult(Song matched, int score) {
                if (matched != null) matches.put(locals.get(idx).id, matched);
                matchOne(it, locals, idx + 1, matches, done, tick);
            }
        });
    }

    /** 真正发布 */
    private void publishNow(Playlist.Item it, String title, String note,
                            java.util.Map<Long, Song> matches) {
        final List<Song> unmatched = new ArrayList<>();
        String json = Playlist.exportJson(it, matches, unmatched);
        if (json == null) { toast("导出失败"); return; }
        Community.publish(act, title, note, json, new Community.Callback() {
            @Override public void onResult(Object d, String error) {
                if (error != null) { toast(error); return; }
                // 重新加载列表
                openId = 0;
                load();
                if (unmatched.isEmpty()) {
                    toast("已发布到社区");
                } else {
                    new MdDialog.Builder(act)
                            .title("已发布")
                            .message("有 " + unmatched.size() + " 首没能匹配到在线版本，"
                                    + "已标注为「待自己导入」。\n"
                                    + "其他曲目别人可以直接播放。")
                            .positive("好", null).show();
                }
            }
        });
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

        // MdDialog 只有左右两个按钮，所以「忘记密码」做成内容里的文字链
        TextView forgotLink = new TextView(act);
        forgotLink.setText("忘记密码？");
        forgotLink.setTextSize(12.5f);
        forgotLink.setTypeface(Ui.tfMed());
        forgotLink.setTextColor(Theme.t().primary);
        forgotLink.setPadding(Ui.px(act, 4), Ui.px(act, 10), 0, 0);
        forgotLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); showForgot(); }
        });
        Ui.pressable(forgotLink);
        box.addView(forgotLink);

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
                })
                .show();
    }

    /**
     * 忘记密码：邮件验证码 + 新密码，**全程在 App 里**。
     *
     * <p>以前只有邮件里的链接，点开是浏览器网页，改完还得手动切回 App ——
     * 手机上这个来回很烦。现在邮件里同时给一个 6 位验证码，直接输进来就行；
     * 在电脑上收邮件的人仍然可以点链接走网页。
     */
    private void showForgot() {
        final MdField mailF = new MdField(act, "注册时的邮箱");
        final MdField codeF = new MdField(act, "6 位验证码");
        codeF.setNumeric(true);
        final MdField passF = new MdField(act, "新密码（至少 6 位）");
        passF.setPassword(true);

        LinearLayout box = Ui.column(act);
        box.addView(mailF);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = Ui.px(act, 10);
        box.addView(codeF, lp1);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = Ui.px(act, 10);
        box.addView(passF, lp2);

        final TextView hint = new TextView(act);
        hint.setTextSize(11.5f);
        hint.setTextColor(Hct.withAlpha(Theme.t().onSurfaceVariant, 0.85f));
        hint.setPadding(Ui.px(act, 4), Ui.px(act, 8), 0, 0);
        hint.setText("还没收到？先点「发送验证码」，邮件里的 6 位数字填在上面。");
        box.addView(hint);

        TextView sendLink = new TextView(act);
        sendLink.setText("发送验证码");
        sendLink.setTextSize(13);
        sendLink.setTypeface(Ui.tfBold());
        sendLink.setTextColor(Theme.t().primary);
        sendLink.setPadding(Ui.px(act, 4), Ui.px(act, 10), 0, 0);
        sendLink.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                String m = mailF.text().trim();
                if (m.length() == 0) { toast("先填邮箱"); return; }
                hint.setText("正在发送…");
                Community.forgot(act, m, new Community.Callback() {
                    @Override public void onResult(Object data, String error) {
                        act.runOnUiThread(new Runnable() { @Override public void run() {
                            hint.setText(error != null ? error
                                    : "验证码已发出，10 分钟内有效。去邮箱看看。");
                        }});
                    }
                });
            }
        });
        Ui.pressable(sendLink);
        box.addView(sendLink);

        new MdDialog.Builder(act)
                .title("重置密码")
                .content(box)
                .negative("取消", null)
                .positive("重置", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String m = mailF.text().trim();
                        String c = codeF.text().trim();
                        String p = passF.text();
                        if (m.length() == 0 || c.length() == 0) { toast("邮箱和验证码都要填"); return; }
                        if (p.length() < 6) { toast("新密码至少 6 位"); return; }
                        Community.resetCode(act, m, c, p, new Community.Callback() {
                            @Override public void onResult(Object data, String error) {
                                act.runOnUiThread(new Runnable() { @Override public void run() {
                                    if (error != null) { toast(error); return; }
                                    toast("密码已重置，用新密码登录吧");
                                }});
                            }
                        });
                    }
                })
                .show();
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
