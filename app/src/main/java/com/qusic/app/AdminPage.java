package com.qusic.app;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 管理页。
 *
 * <p>在此之前 {@code role = 1} 只是个展示用的徽章 —— 社区出了垃圾内容，
 * 只能 SSH 上去手写 SQL 删。这个页面对接服务端的 {@code admin_*} 接口，
 * 把常用操作搬到手上。
 *
 * <h3>权限</h3>
 * 这一页**只是入口的显隐**。真正的判定在服务端 ——
 * 每个 {@code admin_*} 接口都会重新查一次 {@code role >= 1}，
 * 客户端就算把入口改出来，请求也会被 403 挡回去。
 *
 * <h3>危险操作的确认</h3>
 * 封禁、下架、重置密码都弹二次确认，而且说清楚**会发生什么**
 * （比如「重置密码会踢掉他所有设备的登录」），而不是干巴巴一句「确定吗」。
 */
public class AdminPage {

    private final Activity act;
    private View root;

    private LinearLayout body;
    private TextView[] tabBar;
    private int tab = 0;                 // 0=概览 1=用户 2=歌单 3=举报 4=日志
    private boolean busy;

    public AdminPage(Activity a) { this.act = a; build(); }

    public View view() { return root; }

    // ── 骨架 ──────────────────────────────────────────────────────────────

    private void build() {
        Tokens t = Theme.t();
        Context c = act;

        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(t.surface);
        root = wrap;

        LinearLayout col = Ui.column(c);
        wrap.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 顶栏
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.px(c, 18);
        bar.setPadding(pad, Ui.statusBarHeight(c) + Ui.px(c, 10), pad, Ui.px(c, 10));

        TextView title = new TextView(c);
        title.setText("管理");
        title.setTypeface(Ui.tfBold());
        title.setTextSize(22);
        title.setTextColor(t.onSurface);
        bar.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = smallBtn(c, "刷新");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        bar.addView(refresh);
        col.addView(bar);

        // 分段
        LinearLayout tabs = new LinearLayout(c);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(pad, 0, pad, Ui.px(c, 8));
        String[] names = {"概览", "用户", "歌单", "举报", "日志"};
        TextView[] tv = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            tv[i] = pill(c, names[i]);
            tv[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { tab = idx; paintTabs(); load(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = i < names.length - 1 ? Ui.px(c, 6) : 0;
            tabs.addView(tv[i], lp);
        }
        tabBar = tv;
        col.addView(tabs);

        // 内容
        ScrollView sv = new ScrollView(c);
        sv.setVerticalScrollBarEnabled(false);
        body = Ui.column(c);
        body.setPadding(pad, 0, pad, Ui.px(c, 30) + Ui.navBarHeight(c));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        paintTabs();
    }

    private void paintTabs() {
        if (tabBar == null) return;
        TextView[] tv = tabBar;
        Tokens t = Theme.t();
        for (int i = 0; i < tv.length; i++) {
            boolean on = i == tab;
            tv[i].setTextColor(on ? t.onPrimary : t.onSurfaceVariant);
            tv[i].setBackground(on ? bg(t.primary, 18) : bg(0x00000000, 18));
        }
    }

    // ── 载入 ──────────────────────────────────────────────────────────────

    private void load() {
        if (busy) return;
        busy = true;
        if (tab == 0)      loadStats();
        else if (tab == 1) loadUsers("");
        else if (tab == 2) loadLists("", "new");
        else if (tab == 3) loadReports();
        else               loadAudit();
    }

    private void loadStats() {
        Community.adminStats(act, new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    busy = false;
                    if (err != null) { fail(err); return; }
                    body.removeAllViews();
                    card("社区概况", new String[][]{
                            {"注册用户", num(data, "users")},
                            {"其中已封禁", num(data, "banned")},
                            {"邮箱未验证", num(data, "unverified")},
                            {"在架歌单", num(data, "playlists")},
                            {"已下架", num(data, "removed")},
                            {"评论总数", num(data, "comments")},
                            {"举报总数", num(data, "reports")},
                            {"近 7 天新用户", num(data, "week_new")},
                            {"近 7 天新歌单", num(data, "week_pl")},
                    });
                }});
            }
        });
    }

    private void loadUsers(final String q) {
        Community.adminUsers(act, q, 0, new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    busy = false;
                    if (err != null) { fail(err); return; }
                    body.removeAllViews();
                    body.addView(searchRow("搜用户名或邮箱", q, new OnSearch() {
                        @Override public void go(String s) { busy = false; loadUsers(s); }
                    }));

                    List<Object> list = Json.list(data, "list");
                    if (list.isEmpty()) { empty("没有匹配的用户"); return; }
                    for (Object o : list) body.addView(userRow(o));
                }});
            }
        });
    }

    private void loadLists(final String q, final String sort) {
        Community.adminPlaylists(act, q, sort, 0, new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    busy = false;
                    if (err != null) { fail(err); return; }
                    body.removeAllViews();

                    LinearLayout tools = new LinearLayout(act);
                    tools.setOrientation(LinearLayout.HORIZONTAL);
                    tools.setPadding(0, 0, 0, Ui.px(act, 10));
                    final String[] sorts = {"最新", "最早", "被举报"};
                    final String[] keys = {"new", "old", "report"};
                    for (int i = 0; i < sorts.length; i++) {
                        final String k = keys[i];
                        TextView b = smallBtn(act, sorts[i]);
                        if (k.equals(sort)) b.setTextColor(Theme.t().primary);
                        b.setOnClickListener(new View.OnClickListener() {
                            @Override public void onClick(View v) {
                                busy = false; loadLists(q, k);
                            }
                        });
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                        lp.rightMargin = Ui.px(act, 6);
                        tools.addView(b, lp);
                    }
                    body.addView(tools);

                    List<Object> list = Json.list(data, "list");
                    if (list.isEmpty()) { empty("没有歌单"); return; }
                    for (Object o : list) body.addView(listRow(o));
                }});
            }
        });
    }

    private void loadReports() {
        Community.adminReports(act, new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    busy = false;
                    if (err != null) { fail(err); return; }
                    body.removeAllViews();
                    List<Object> list = Json.list(data, "list");
                    if (list.isEmpty()) { empty("还没有人举报"); return; }
                    for (Object o : list) body.addView(reportRow(o));
                }});
            }
        });
    }

    private void loadAudit() {
        Community.adminAudit(act, new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    busy = false;
                    if (err != null) { fail(err); return; }
                    body.removeAllViews();
                    List<Object> list = Json.list(data, "list");
                    if (list.isEmpty()) { empty("还没有管理操作记录"); return; }
                    for (Object o : list) body.addView(auditRow(o));
                }});
            }
        });
    }

    // ── 行 ────────────────────────────────────────────────────────────────

    private View userRow(final Object o) {
        final long id = (long) dbl(o, "id");
        String name = str(o, "name");
        String email = str(o, "email");
        boolean banned = bool(o, "banned");
        boolean verified = bool(o, "verified");
        int role = (int) dbl(o, "role");

        String sub = email;
        if (!verified) sub += "  · 邮箱未验证";
        if (role >= 1) sub += "  · 管理员";

        LinearLayout row = rowCard(name, sub, banned ? "已封禁" : null, banned);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { userActions(id, str(o, "name"), bool(o, "banned"), (int) dbl(o, "role")); }
        });
        return row;
    }

    private View listRow(final Object o) {
        final long id = (long) dbl(o, "id");
        String title = str(o, "title");
        String author = str(o, "author");
        int n = (int) dbl(o, "count");
        int rep = (int) dbl(o, "reports");
        boolean removed = bool(o, "removed");

        String sub = author + "  ·  " + n + " 首  ·  " + (int) dbl(o, "likes") + " 赞";
        if (rep > 0) sub += "  ·  ⚠ " + rep + " 次举报";

        LinearLayout row = rowCard(title, sub, removed ? "已下架" : null, removed);

        final String t = title;
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (bool(o, "removed")) {
                    confirm("恢复歌单", "把《" + t + "》重新上架？", "恢复", new Runnable() {
                        @Override public void run() { doRestore(id); }
                    });
                } else {
                    confirm("下架歌单", "《" + t + "》将不再出现在列表里。\n数据不会删除，随时可以恢复。",
                            "下架", new Runnable() {
                        @Override public void run() { doDel(id); }
                    });
                }
            }
        });
        return row;
    }

    private View reportRow(final Object o) {
        String title = str(o, "title");
        String author = str(o, "author");
        String who = str(o, "reporter");
        String why = str(o, "reason");
        final long pid = (long) dbl(o, "playlist_id");
        boolean removed = bool(o, "removed");

        LinearLayout row = rowCard("《" + title + "》", who + " 举报：" + why,
                removed ? "已下架" : null, removed);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!removed) confirm("下架歌单", "《" + str(o, "title") + "》将被下架。\n数据不删，可恢复。",
                        "下架", new Runnable() { @Override public void run() { doDel(pid); } });
            }
        });
        return row;
    }

    private View auditRow(final Object o) {
        String admin = str(o, "admin");
        String action = str(o, "action");
        String target = str(o, "target");
        long ts = (long) dbl(o, "created");
        String name = actionName(action);
        LinearLayout row = rowCard(name + "  " + target, admin + "  ·  " + ago(ts), null, false);
        row.setOnClickListener(null);
        return row;
    }

    private static String actionName(String a) {
        if ("ban".equals(a)) return "封禁";
        if ("unban".equals(a)) return "解封";
        if ("grant_admin".equals(a)) return "设为管理员";
        if ("revoke_admin".equals(a)) return "取消管理员";
        if ("verify".equals(a)) return "标记邮箱通过";
        if ("unverify".equals(a)) return "取消邮箱验证";
        if ("reset_password".equals(a)) return "重置密码";
        if ("del_playlist".equals(a)) return "下架歌单";
        if ("restore_playlist".equals(a)) return "恢复歌单";
        if ("del_comment".equals(a)) return "删除评论";
        return a;
    }

    // ── 用户操作 ──────────────────────────────────────────────────────────

    private void userActions(final long id, final String name, final boolean banned, final int role) {
        List<String> items = new ArrayList<>();
        final List<String> acts = new ArrayList<>();
        items.add(banned ? "解封" : "封禁");           acts.add("ban");
        items.add(role >= 1 ? "取消管理员" : "设为管理员"); acts.add("role");
        items.add("重置密码");                          acts.add("pass");
        items.add("标记邮箱已验证");                     acts.add("verify");

        new MdDialog.Builder(act)
                .title(name)
                .items(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        String a = acts.get(which);
                        if ("ban".equals(a)) {
                            if (banned) doUserSet(id, "\"banned\":0", "已解封");
                            else confirm("封禁 " + name,
                                    "他将无法登录，**已登录的设备也会立刻掉线**。",
                                    "封禁", new Runnable() {
                                        @Override public void run() { doUserSet(id, "\"banned\":1", "已封禁"); }
                                    });
                        } else if ("role".equals(a)) {
                            doUserSet(id, "\"role\":" + (role >= 1 ? 0 : 1), null);
                        } else if ("verify".equals(a)) {
                            doUserSet(id, "\"verified\":1", "已标记邮箱通过");
                        } else {
                            askNewPassword(id, name);
                        }
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void askNewPassword(final long id, final String name) {
        final MdField f = new MdField(act, "新密码（至少 6 位）");
        f.setPassword(true);
        new MdDialog.Builder(act)
                .title("重置 " + name + " 的密码")
                .message("密码是 bcrypt 存的，**反推不出原文**，只能设一个新的。\n" +
                         "重置后他所有设备都会被踢下线。")
                .content(f)
                .negative("取消", null)
                .positive("重置", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String p = f.text().trim();
                        if (p.length() < 6) {
                            Toast.makeText(act, "至少 6 位", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        doUserSet(id, "\"new_password\":" + Json.q(p), "已重置密码");
                    }
                })
                .show();
    }

    private void doUserSet(final long id, final String fields, final String okMsg) {
        Community.adminUserSet(act, id, fields, new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    if (err != null) { fail(err); return; }
                    String m = okMsg;
                    if (m == null) {
                        Object msg = Json.path(data, "msg");
                        m = msg == null ? "已完成" : String.valueOf(msg);
                    }
                    Toast.makeText(act, m, Toast.LENGTH_SHORT).show();
                    busy = false;
                    load();
                }});
            }
        });
    }

    private void doDel(final long id) {
        Community.adminDelPlaylist(act, id, afterOp());
    }

    private void doRestore(final long id) {
        Community.adminRestorePlaylist(act, id, afterOp());
    }

    private Community.Callback afterOp() {
        return new Community.Callback() {
            @Override public void onResult(final Object data, final String err) {
                act.runOnUiThread(new Runnable() { @Override public void run() {
                    if (err != null) { fail(err); return; }
                    Object msg = Json.path(data, "msg");
                    Toast.makeText(act, msg == null ? "已完成" : String.valueOf(msg),
                            Toast.LENGTH_SHORT).show();
                    busy = false;
                    load();
                }});
            }
        };
    }

    // ── 小组件 ────────────────────────────────────────────────────────────

    private interface OnSearch { void go(String q); }

    private View searchRow(String hint, String cur, final OnSearch cb) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, 0, 0, Ui.px(act, 12));

        final MdField f = new MdField(act, hint);
        if (cur.length() > 0) f.setText(cur);
        f.setOnSubmit(new Runnable() { @Override public void run() { cb.go(f.text().trim()); } });
        box.addView(f, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView b = smallBtn(act, "搜");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { cb.go(f.text().trim()); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.px(act, 8);
        box.addView(b, lp);
        return box;
    }

    private LinearLayout rowCard(String title, String sub, String badge, boolean dim) {
        Tokens t = Theme.t();
        LinearLayout row = Ui.column(act);
        int p = Ui.px(act, 14);
        row.setPadding(p, p, p, p);
        row.setBackground(bg(t.surfaceContainerLow, 16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.px(act, 8);
        row.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(act);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(act);
        tv.setText(title);
        tv.setTypeface(Ui.tfMed());
        tv.setTextSize(15);
        tv.setTextColor(dim ? Hct.withAlpha(t.onSurface, 0.55f) : t.onSurface);
        top.addView(tv, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (badge != null) {
            TextView b = pill(act, badge);
            b.setTextColor(t.error);
            b.setBackground(bg(Hct.withAlpha(t.error, 0.16f), 10));
            top.addView(b);
        }
        row.addView(top);

        TextView sv = new TextView(act);
        sv.setText(sub);
        sv.setTextSize(12.5f);
        sv.setTextColor(t.onSurfaceVariant);
        sv.setPadding(0, Ui.px(act, 3), 0, 0);
        row.addView(sv);
        return row;
    }

    private void card(String title, String[][] rows) {
        Tokens t = Theme.t();
        LinearLayout c = Ui.column(act);
        int p = Ui.px(act, 16);
        c.setPadding(p, p, p, p);
        c.setBackground(bg(t.surfaceContainerLow, 16));

        TextView h = new TextView(act);
        h.setText(title);
        h.setTypeface(Ui.tfBold());
        h.setTextSize(15);
        h.setTextColor(t.onSurface);
        h.setPadding(0, 0, 0, Ui.px(act, 12));
        c.addView(h);

        for (String[] r : rows) {
            LinearLayout line = new LinearLayout(act);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setPadding(0, Ui.px(act, 5), 0, Ui.px(act, 5));

            TextView k = new TextView(act);
            k.setText(r[0]);
            k.setTextSize(13.5f);
            k.setTextColor(t.onSurfaceVariant);
            line.addView(k, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView v = new TextView(act);
            v.setText(r[1]);
            v.setTypeface(Ui.tfMed());
            v.setTextSize(14);
            v.setTextColor(t.onSurface);
            line.addView(v);
            c.addView(line);
        }
        body.addView(c);
    }

    private void empty(String s) {
        TextView tv = new TextView(act);
        tv.setText(s);
        tv.setTextSize(13.5f);
        tv.setTextColor(Theme.t().onSurfaceVariant);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.px(act, 40), 0, 0);
        body.addView(tv);
    }

    private void fail(String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    private void confirm(String title, String msg, String ok, final Runnable run) {
        new MdDialog.Builder(act)
                .title(title)
                .message(msg.replace("**", ""))
                .negative("取消", null)
                .positive(ok, new MdDialog.OnClick() {
                    @Override public void onClick() { run.run(); }
                })
                .show();
    }

    /** 圆角实心背景（社区页也是这么做的） */
    private android.graphics.drawable.GradientDrawable bg(int color, float radiusDp) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(Ui.px(act, radiusDp));
        return d;
    }

    private TextView smallBtn(Context c, String s) {
        TextView tv = pill(c, s);
        tv.setPadding(Ui.px(c, 12), Ui.px(c, 6), Ui.px(c, 12), Ui.px(c, 6));
        tv.setTextColor(Theme.t().primary);
        tv.setBackground(bg(Hct.withAlpha(Theme.t().primary, 0.12f), 12));
        return tv;
    }

    private TextView pill(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(12.5f);
        tv.setTypeface(Ui.tfMed());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Ui.px(c, 10), Ui.px(c, 7), Ui.px(c, 10), Ui.px(c, 7));
        tv.setTextColor(Theme.t().onSurfaceVariant);
        return tv;
    }

    // ── JSON 取值 ─────────────────────────────────────────────────────────

    private static String str(Object o, String k) {
        String s = Json.str(o, k);
        return s == null ? "" : s;
    }

    private static double dbl(Object o, String k) {
        Object v = Json.path(o, k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Throwable t) { return 0; }
    }

    private static boolean bool(Object o, String k) {
        Object v = Json.path(o, k);
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equals(String.valueOf(v));
    }

    private static String num(Object data, String k) {
        return String.valueOf((long) dbl(data, k));
    }

    /** 「3 分钟前」这种相对时间 */
    private static String ago(long ts) {
        long d = System.currentTimeMillis() / 1000L - ts;
        if (d < 60) return "刚刚";
        if (d < 3600) return (d / 60) + " 分钟前";
        if (d < 86400) return (d / 3600) + " 小时前";
        if (d < 86400 * 30) return (d / 86400) + " 天前";
        return (d / 86400 / 30) + " 个月前";
    }
}
