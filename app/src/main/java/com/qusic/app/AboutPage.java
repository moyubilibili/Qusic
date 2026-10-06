package com.qusic.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 关于页：应用信息、主题定制（种子色 / 明暗 / 动画）、音乐库管理与统计。
 *
 * <p>顶部是会呼吸的 Logo（自绘），点一下放烟花。
 */
public class AboutPage {

    private final MainActivity act;
    private View root;
    private LinearLayout col;
    private LinearLayout seedRow;
    private TextView statsLabel;
    private View logo;

    public AboutPage(MainActivity a) {
        this.act = a;
        build();
    }

    public View view() { return root; }

    private void build() {
        Tokens t = Theme.t();
        Context c = act;

        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(t.surface);
        root = wrap;

        final InkDissolve ink = new InkDissolve(c);
        ink.setClickable(false);

        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipToPadding(false);
        wrap.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        col = Ui.column(c);
        int pad = Ui.px(c, 20);
        col.setPadding(pad, Ui.statusBarHeight(c) + Ui.px(c, 22), pad, act.contentBottomInset());
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        sv.addView(col);

        logo = new View(c) {
            private final Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF lr = new RectF();
            @Override protected void onDraw(Canvas cv) {
                lr.set(0, 0, getWidth(), getHeight());
                AppMark.draw(cv, lr, Theme.t());
            }
        };
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                Ui.px(c, 108), Ui.px(c, 108));
        llp.bottomMargin = Ui.px(c, 14);
        col.addView(logo, llp);

        TextView name = new TextView(c);
        name.setText("Qusic");
        name.setTextColor(t.onSurface);
        name.setTextSize(30);
        name.setTypeface(Ui.tfBlack());
        name.setGravity(Gravity.CENTER);
        col.addView(name);

        TextView version = new TextView(c);
        version.setText("版本 " + versionName() + " · 本地音乐播放器");
        version.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        version.setTextSize(12.5f);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, Ui.px(c, 4), 0, Ui.px(c, 16));
        col.addView(version);

        statsLabel = new TextView(c);
        statsLabel.setTextColor(t.onSurface);
        statsLabel.setTextSize(13);
        statsLabel.setLineSpacing(Ui.px(c, 5), 1f);
        statsLabel.setPadding(Ui.px(c, 18), Ui.px(c, 16), Ui.px(c, 18), Ui.px(c, 16));
        statsLabel.setBackground(card(t));
        col.addView(statsLabel, fullWidth());
        refreshStats();

        // ── 主题 ──
        col.addView(section(c, "显示与动效", t));
        LinearLayout opts = Ui.column(c);
        opts.setBackground(card(t));
        opts.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6));

        opts.addView(settingRow(c, t, "主题配色", seedName() + " · 点按更换", "palette",
                new Runnable() { @Override public void run() { showSeedPicker(); } }));
        // 管理入口：只有 role >= 1 才显示。
        // 这只是「入口的显隐」，真正的权限在服务端每个 admin_* 里各查一次。
        if (Community.isAdmin(act)) {
            opts.addView(divider(c, t));
            opts.addView(settingRow(c, t, "社区管理",
                    "封禁 / 下架 / 重置密码", "lock", new Runnable() {
                        @Override public void run() { act.openAdmin(); }
                    }));
        }
        opts.addView(divider(c, t));
        opts.addView(settingRow(c, t, "深色模式", Theme.isDark() ? "已开启" : "已关闭",
                "moon", new Runnable() {
                    @Override public void run() {
                        ThemeReveal.arm(act, act.getCurrentFocus());
                        Theme.toggleDark();
                    }
                }));
        opts.addView(divider(c, t));
        opts.addView(settingRow(c, t, "动画速度",
                Theme.animMode() == Theme.ANIM_FAST ? "轻快"
                        : Theme.animMode() == Theme.ANIM_SUBTLE ? "舒缓" : "标准",
                "equalizer", new Runnable() {
                    @Override public void run() {
                        int m = (Theme.animMode() + 1) % 3;
                        Theme.setAnimMode(m);
                        Toast.makeText(act, "动画：" + (m == 0 ? "轻快" : m == 1 ? "标准" : "舒缓"),
                                Toast.LENGTH_SHORT).show();
                        act.recreate();
                    }
                }));
        opts.addView(divider(c, t));
        opts.addView(settingRow(c, t, "内置流体云胶囊",
                Theme.cloudEnabled() ? "已开启（点按可开关）" : "已关闭（点按可开关）",
                "note", new Runnable() {
                    @Override public void run() {
                        ThemeReveal.armCenter(act);
                        Theme.setCloud(!Theme.cloudEnabled());
                        act.recreate();
                    }
                }));
        opts.addView(divider(c, t));
        opts.addView(settingRow(c, t, "随机换个配色", "点一下试试", "palette", new Runnable() {
            @Override public void run() {
                ThemeReveal.armCenter(act, ThemeReveal.MODE_INK);
                Theme.setSeed(Tokens.SEEDS[new java.util.Random().nextInt(Tokens.SEEDS.length)]);
            }
        }));
        col.addView(opts, fullWidth());

        // ── 音乐库 ──
        col.addView(section(c, "社区", t));
        LinearLayout com = Ui.column(c);
        com.setBackground(card(t));
        com.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6));
        com.addView(settingRow(c, t, "显示社区",
                Theme.community() ? "已开启 · 底栏有「社区」入口"
                                  : "已关闭 · 纯净播放器模式",
                "queue", new Runnable() {
                    @Override public void run() { act.toggleCommunity(); }
                }));
        col.addView(com, fullWidth());

        col.addView(section(c, "桌面歌词", t));
        LinearLayout lyr = Ui.column(c);
        lyr.setBackground(card(t));
        lyr.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6));
        lyr.addView(settingRow(c, t, "桌面歌词悬浮窗",
                LyricsWindowService.isRunning() ? "已开启 · 点按关闭" : "在屏幕上悬浮显示歌词",
                "note", new Runnable() {
                    @Override public void run() { act.toggleLyricsWindow(); }
                }));
        col.addView(lyr, fullWidth());

        col.addView(section(c, "更新", t));
        LinearLayout upd = Ui.column(c);
        upd.setBackground(card(t));
        upd.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6));
        upd.addView(settingRow(c, t, "检查更新",
                "当前 " + Updater.localVersionName(act) + " · 从 GitHub Releases 获取",
                "refresh", new Runnable() {
                    @Override public void run() { act.checkForUpdatesManually(); }
                }));
        upd.addView(divider(c, t));
        upd.addView(settingRow(c, t, "自动更新",
                Theme.autoUpdate() ? "已开启 · 发现新版自动下载" : "已关闭 · 发现新版先询问",
                "refresh", new Runnable() {
                    @Override public void run() {
                        Theme.setAutoUpdate(!Theme.autoUpdate());
                        act.recreate();
                    }
                }));
        upd.addView(divider(c, t));
        upd.addView(settingRow(c, t, "打开发布页面", "在浏览器里查看所有版本", "note",
                new Runnable() { @Override public void run() { Updater.openReleasesPage(act); } }));
        col.addView(upd, fullWidth());

        col.addView(section(c, "音乐库", t));
        LinearLayout lib = Ui.column(c);
        lib.setBackground(card(t));
        lib.setPadding(Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6), Ui.px(c, 6));

        lib.addView(settingRow(c, t, "导入音乐", "从文件里挑歌加进来", "note", new Runnable() {
            @Override public void run() { act.importMusic(); }
        }));
        lib.addView(divider(c, t));
        lib.addView(settingRow(c, t, "清空曲库", Library.count() + " 首已导入", "close",
                new Runnable() {
                    @Override public void run() {
                        new MdDialog.Builder(act)
                                .title("清空曲库？")
                                .message("只会把歌从 Qusic 的列表里移除，不会删除你手机里的文件。")
                                .negative("取消", null)
                                .positive("清空", new MdDialog.OnClick() {
                                    @Override public void onClick() {
                                        Library.clear(act);
                                        act.refreshAllPages();
                                    }
                                }).show();
                    }
                }));
        col.addView(lib, fullWidth());

        // ── 说明 ──
        col.addView(section(c, "每日一句", t));
        col.addView(buildQuoteCard(c, t), fullWidth());

        col.addView(section(c, "关于", t));
        TextView about = new TextView(c);
        about.setText("Qusic 是一个完全离线的本地音乐播放器。\n\n"
                + "· 音乐由你自己导入，App 不会扫描或上传你的存储\n"
                + "· Material Design 3 配色：由一颗种子色推导出整套主题\n"
                + "· 播放页跟随封面取色，带光晕与呼吸律动，可下拉看歌词\n"
                + "· 接入系统媒体会话：锁屏、媒体卡片与流体云都能控制播放\n"
                + "· 支持 Android 16 实况更新（Live Updates），可在流体云中显示进度\n\n"
                + "所有数据都留在你的手机里。");
        about.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.92f));
        about.setTextSize(12.5f);
        about.setLineSpacing(Ui.px(c, 5), 1f);
        about.setPadding(Ui.px(c, 18), Ui.px(c, 16), Ui.px(c, 18), Ui.px(c, 16));
        about.setBackground(card(t));
        col.addView(about, fullWidth());

        TextView footer = new TextView(c);
        footer.setText("Made with ♥ · Qusic");
        footer.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.55f));
        footer.setTextSize(11);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, Ui.px(c, 22), 0, 0);
        col.addView(footer);

        wrap.addView(ink, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        logo.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                ink.start(Theme.t().primary, null);
            }
        });
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 每日名言的当前索引（点「换一句」后临时改它） */
    private int quoteIdx = -1;

    private View buildQuoteCard(Context c, Tokens t) {
        final LinearLayout box = Ui.column(c);
        box.setBackground(card(t));
        box.setPadding(Ui.px(c, 20), Ui.px(c, 18), Ui.px(c, 20), Ui.px(c, 14));

        final Quotes.Quote q = quoteIdx < 0 ? Quotes.today() : Quotes.at(quoteIdx);

        // 引号装饰
        TextView mark = new TextView(c);
        mark.setText("\u201C");
        mark.setTextSize(34);
        mark.setTypeface(Ui.tfBold());
        mark.setTextColor(Hct.withAlpha(t.primary, 0.35f));
        mark.setPadding(0, 0, 0, Ui.px(c, 2));
        box.addView(mark);

        final TextView body = new TextView(c);
        body.setText(q.text);
        body.setTextColor(t.onSurface);
        body.setTextSize(15.5f);
        body.setLineSpacing(Ui.px(c, 7), 1f);
        body.setTypeface(Ui.tfMed());
        box.addView(body);

        final TextView who = new TextView(c);
        who.setText("—— " + q.author);
        who.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        who.setTextSize(12.5f);
        who.setPadding(0, Ui.px(c, 10), 0, Ui.px(c, 10));
        box.addView(who);

        LinearLayout tools = Ui.row(c);
        tools.setGravity(Gravity.END);

        TextView copy = smallTool(c, t, "复制", "content_copy", new Runnable() {
            @Override public void run() {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(
                            "Qusic 每日一句", q.text + " —— " + q.author));
                    Toast.makeText(act, "已复制", Toast.LENGTH_SHORT).show();
                }
            }
        });
        tools.addView(copy);

        TextView another = smallTool(c, t, "换一句", "refresh", new Runnable() {
            @Override public void run() {
                // 只是临时换一条看看，明天还是回到「每日」那句
                quoteIdx = new java.util.Random().nextInt(Quotes.count());
                Quotes.Quote nq = Quotes.at(quoteIdx);
                body.setText(nq.text);
                who.setText("—— " + nq.author);
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = Ui.px(c, 8);
        tools.addView(another, alp);
        box.addView(tools);

        return box;
    }

    /** 全圆角胶囊背景 */
    private android.graphics.drawable.Drawable pill(int color, float r) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    /** 卡片里的小工具按钮（文字 + 图标） */
    private TextView smallTool(Context c, Tokens t, String label, String icon,
                               final Runnable r) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(12.5f);
        b.setTypeface(Ui.tfMed());
        b.setTextColor(t.primary);
        b.setPadding(Ui.px(c, 14), Ui.px(c, 8), Ui.px(c, 14), Ui.px(c, 8));
        b.setBackground(pill(t.primaryContainer, Ui.px(c, 20)));
        Ui.pressable(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); r.run(); }
        });
        return b;
    }

    private android.graphics.drawable.Drawable card(Tokens t) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(t.surfaceContainerHigh);
        g.setCornerRadius(Ui.px(act, 20));
        g.setStroke(Ui.px(act, 1), Hct.withAlpha(t.outlineVariant, 0.55f));
        return g;
    }

    private View divider(Context c, Tokens t) {
        View v = new View(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(c, 1));
        lp.leftMargin = Ui.px(c, 52);
        v.setLayoutParams(lp);
        v.setBackgroundColor(Hct.withAlpha(t.outlineVariant, 0.45f));
        return v;
    }

    private TextView section(Context c, String s, Tokens t) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextColor(t.onSurface);
        tv.setTextSize(15);
        tv.setTypeface(Ui.tfBold());
        tv.setPadding(0, Ui.px(c, 22), 0, Ui.px(c, 10));
        return tv;
    }

    private View settingRow(Context c, Tokens t, String title, String sub, String icon,
                            final Runnable action) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12), Ui.px(c, 12));

        HomePage.IconView iv = new HomePage.IconView(c, icon, t.primary);
        row.addView(iv, new LinearLayout.LayoutParams(Ui.px(c, 22), Ui.px(c, 22)));

        LinearLayout col2 = Ui.column(c);
        col2.setPadding(Ui.px(c, 16), 0, 0, 0);
        TextView title2 = new TextView(c);
        title2.setText(title);
        title2.setTextColor(t.onSurface);
        title2.setTextSize(14);
        title2.setTypeface(Ui.tfMed());
        col2.addView(title2);

        TextView sub2 = new TextView(c);
        sub2.setText(sub);
        sub2.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        sub2.setTextSize(11.5f);
        col2.addView(sub2);

        row.addView(col2, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                if (action != null) action.run();
            }
        });
        return row;
    }

    /** 配色选择弹窗：两行色块，点选即换肤 */
    private void showSeedPicker() {
        Context c = act;
        Tokens t = Theme.t();
        LinearLayout box = Ui.column(c);
        int pad = Ui.px(c, 22);
        box.setPadding(pad, Ui.px(c, 18), pad, Ui.px(c, 8));

        TextView title = new TextView(c);
        title.setText("选择主题配色");
        title.setTextColor(t.onSurface);
        title.setTextSize(17);
        title.setTypeface(Ui.tfBold());
        box.addView(title);

        TextView sub = new TextView(c);
        sub.setText("整套配色由一颗种子色推导而来");
        sub.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        sub.setTextSize(12);
        sub.setPadding(0, Ui.px(c, 4), 0, Ui.px(c, 16));
        box.addView(sub);

        // 每行 5 个
        for (int row = 0; row < 2; row++) {
            LinearLayout line = Ui.row(c);
            line.setGravity(Gravity.CENTER);
            for (int i = row * 5; i < row * 5 + 5 && i < Tokens.SEEDS.length; i++) {
                final int color = Tokens.SEEDS[i];
                LinearLayout item = Ui.column(c);
                item.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                SeedDot dot = new SeedDot(c, color, Theme.seed() == color);
                dot.setLayoutParams(new LinearLayout.LayoutParams(
                        Ui.px(c, 44), Ui.px(c, 44)));
                item.addView(dot);
                TextView nm = new TextView(c);
                nm.setText(Tokens.SEED_NAMES[i]);
                nm.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
                nm.setTextSize(10.5f);
                nm.setGravity(Gravity.CENTER);
                nm.setPadding(0, Ui.px(c, 6), 0, 0);
                item.addView(nm);
                line.addView(item, ilp);
            }
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.bottomMargin = Ui.px(c, 14);
            box.addView(line, llp);
        }

        final android.app.Dialog d = new android.app.Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.surfaceContainerHigh);
        bg.setCornerRadius(Ui.px(c, 28));
        box.setBackground(bg);
        d.setContentView(box);
        d.show();

        // 点色块换肤并关闭
        for (int i = 0; i < box.getChildCount(); i++) {
            View v = box.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout line = (LinearLayout) v;
            for (int j = 0; j < line.getChildCount(); j++) {
                View item = line.getChildAt(j);
                if (!(item instanceof LinearLayout)) continue;
                final int idx = (i - 2) * 5 + j;
                if (idx < 0 || idx >= Tokens.SEEDS.length) continue;
                item.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        Ui.hapticLight(view);
                        d.dismiss();
                        ThemeReveal.armCenter(act, ThemeReveal.MODE_INK);
                        Theme.setSeed(Tokens.SEEDS[idx]);
                    }
                });
            }
        }
    }

    private void buildSeeds() {
        Context c = act;
        seedRow.removeAllViews();
        int size = Ui.px(c, 40);
        for (int i = 0; i < Tokens.SEEDS.length; i++) {
            final int color = Tokens.SEEDS[i];
            SeedDot dot = new SeedDot(c, color, Theme.seed() == color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.rightMargin = Ui.px(c, 9);
            lp.bottomMargin = Ui.px(c, 9);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticLight(v);
                    ThemeReveal.armCenter(act, ThemeReveal.MODE_INK);
                    Theme.setSeed(color);
                }
            });
            seedRow.addView(dot);
        }
    }

    private String versionName() {
        try {
            PackageInfo pi = act.getPackageManager().getPackageInfo(act.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable e) { return "1.0"; }
    }

    public void refreshStats() {
        if (statsLabel == null || statsLabel.getContext() == null) return;
        List<Song> all = Library.songs();
        int albums = Library.albums().size();
        long total = Library.totalDurationMs();
        int flac = 0;
        for (Song s : all) {
            String p = s.uri == null ? "" : s.uri.toLowerCase();
            if (p.contains("flac") || p.contains("wav") || p.contains("ape")) flac++;
        }
        StringBuilder sb = new StringBuilder();
        if (all.isEmpty()) {
            sb.append("曲库是空的，去下面「导入音乐」吧");
        } else {
            sb.append("曲目 ").append(all.size()).append(" 首");
            sb.append("    专辑 ").append(albums).append(" 张");
            sb.append('\n');
            sb.append("总时长 ").append(Ui.duration(total));
            sb.append("    无损 ").append(flac).append(" 首");
            sb.append('\n');
            // 「曲库总时长」= 你拥有多少音乐；「累计收听」= 你实际听了多久。
            // 同一个歌单听十遍，前者不变，后者会涨。
            sb.append("累计收听 ").append(ListenStats.human(ListenStats.totalMs()));
            long today = ListenStats.todayMs();
            if (today > 0) {
                sb.append("    今日 ").append(ListenStats.human(today));
            }
            int played = ListenStats.songsPlayed();
            if (played > 0) {
                sb.append('\n');
                sb.append("播放过 ").append(played).append(" 首");
                int sess = ListenStats.sessions();
                if (sess > 0) sb.append("    收听 ").append(sess).append(" 次");
            }
        }
        sb.append('\n');
        sb.append(Theme.isDark() ? "深色主题" : "浅色主题");
        sb.append("    种子色 ").append(seedName());
        statsLabel.setText(sb.toString());
    }

    private String seedName() {
        for (int i = 0; i < Tokens.SEEDS.length; i++) {
            if (Tokens.SEEDS[i] == Theme.seed()) return Tokens.SEED_NAMES[i];
        }
        return "自定义";
    }

    // ── 种子色圆点 ──────────────────────────────────────────────────────────
    public static class SeedDot extends View {
        private final int color;
        private final boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        public SeedDot(Context c, int color, boolean selected) {
            super(c);
            this.color = color;
            this.selected = selected;
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float r = Math.min(cx, cy) - Ui.dp(getContext(), 3);

            p.reset(); p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(cx, cy, r, p);

            p.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.5f,
                    new int[]{Hct.withAlpha(Color.WHITE, 0.35f), Hct.withAlpha(Color.WHITE, 0f)},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);

            if (selected) {
                p.reset(); p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(getContext(), 2.5f));
                p.setColor(Theme.t().onSurface);
                c.drawCircle(cx, cy, r + Ui.dp(getContext(), 3.5f), p);
            }
        }
    }
}
