package com.qusic.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 内置更新检查。
 *
 * <p>数据源就是本项目的 GitHub Releases：查 {@code /releases/latest}，
 * 把 tag 解析成版本号跟本机比对，有新版就展示更新日志并提供「下载并安装」。
 *
 * <p>全部用平台 API 实现（{@code HttpURLConnection} + {@link DownloadManager}），
 * 不引任何第三方库。下载走系统的 DownloadManager，完成后拉起安装界面。
 */
public final class Updater {

    public static final String OWNER = "moyubilibili";
    public static final String REPO = "Qusic";
    private static final String LATEST_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE =
            "https://github.com/" + OWNER + "/" + REPO + "/releases";

    /** 一次检查的结果 */
    public static class Info {
        public String tag = "";          // v1.1
        public String name = "";         // 标题
        public String notes = "";        // 更新日志（markdown 原文）
        public String apkUrl = "";       // 第一个 .apk 附件的下载地址
        public String pageUrl = "";      // Release 页面
        public int remoteCode;           // 解析出的版本号
        public int localCode;            // 本机版本号
        public boolean hasUpdate;
    }

    public interface Callback { void onResult(Info info, String error); }

    private Updater() {}

    // ── 版本号 ──────────────────────────────────────────────────────────────
    /**
     * 把 "v2.2" / "2.0.1" 解析成可比较的整数。
     *
     * <p><b>进制必须和 AndroidManifest 的 versionCode 完全一致</b>：
     * <pre>
     *   versionCode = major * 10000 + minor * 1000 + patch
     *
     *   2.0    → 20000
     *   2.0.1  → 20001
     *   2.1    → 21000
     *   2.2    → 22000
     * </pre>
     *
     * <p>之前这里写的是 {@code minor * 100}，于是 "2.2" 算成 20200，
     * 比本机 versionCode 22000 还小 —— 检查更新永远显示「已是最新版本」。
     *
     * <p>注意：1.x 时期的 versionCode 用的是 {@code minor * 100}（如 1.9 = 10900），
     * 与现在的进制不同。但因为远端永远是**最新**的 release，
     * 只可能解析出 2.x 以上，所以旧的差异不会造成误判。
     */
    public static int parseVersion(String tag) {
    public static int parseVersion(String tag) {
        if (tag == null) return 0;
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        String[] parts = s.split("[.\\-+]");
        int major = 0, minor = 0, patch = 0;
        try { if (parts.length > 0) major = Integer.parseInt(parts[0].replaceAll("\\D", "")); } catch (Throwable ignored) {}
        try { if (parts.length > 1) minor = Integer.parseInt(parts[1].replaceAll("\\D", "")); } catch (Throwable ignored) {}
        try { if (parts.length > 2) patch = Integer.parseInt(parts[2].replaceAll("\\D", "")); } catch (Throwable ignored) {}
        return major * 10000 + minor * 1000 + patch;
    }

    public static int localVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String localVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ── 检查更新 ────────────────────────────────────────────────────────────
    private static final String ATOM =
            "https://github.com/" + OWNER + "/" + REPO + "/releases.atom";
    private static final String LATEST_PAGE =
            "https://github.com/" + OWNER + "/" + REPO + "/releases/latest";

    /**
     * 后台线程检查，回调在主线程。
     *
     * <p><b>为什么不直接调 api.github.com：</b>未认证的 GitHub API 是
     * <b>每 IP 每小时 60 次</b>，而很多用户走代理、IP 是共用的，很容易被耗光，
     * 表现就是「一更新就提示过于频繁」（HTTP 403 + {@code x-ratelimit-remaining: 0}）。
     *
     * <p>所以改成三级来源，优先用**不消耗 API 额度**的方式：
     * <ol>
     *   <li>{@code releases.atom} —— 标签、标题、更新日志全都有，且无限流</li>
     *   <li>{@code releases/latest} 的 302 跳转 —— 只拿标签，兜底</li>
     *   <li>api.github.com —— 最后手段，可能限流</li>
     * </ol>
     */
    public static void check(final Context ctx, final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                Info info = new Info();
                String err = null;
                info.localCode = localVersionCode(ctx);

                // ① 首选：atom feed
                try {
                    parseAtom(get(ATOM), info);
                } catch (Throwable ignored) {}

                // ② 兜底：302 重定向拿 tag
                if (info.tag.length() == 0) {
                    try {
                        String tag = tagFromRedirect();
                        if (tag.length() > 0) {
                            info.tag = tag;
                            info.name = "Qusic " + tag;
                            info.pageUrl = "https://github.com/" + OWNER + "/" + REPO + "/releases/tag/" + tag;
                        }
                    } catch (Throwable ignored) {}
                }

                // ③ 最后：GitHub API（有更新日志但可能限流）
                if (info.tag.length() == 0) {
                    try {
                        Object root = Json.parse(get(LATEST_API));
                        String msg = Json.str(root, "message");
                        if (msg != null) throw new IllegalStateException(msg);
                        info.tag = nz(Json.str(root, "tag_name"));
                        info.name = nz(Json.str(root, "name"));
                        info.notes = nz(Json.str(root, "body"));
                        info.pageUrl = nz(Json.str(root, "html_url"));
                    } catch (Throwable t) {
                        err = friendly(t);
                    }
                }

                if (info.tag.length() == 0 && err == null) {
                    err = "连不上 GitHub（可能需要代理）";
                }

                if (info.tag.length() > 0) {
                    info.remoteCode = parseVersion(info.tag);
                    info.hasUpdate = info.remoteCode > info.localCode;
                    if (info.pageUrl.length() == 0) {
                        info.pageUrl = "https://github.com/" + OWNER + "/" + REPO
                                + "/releases/tag/" + info.tag;
                    }
                    // 附件名遵循固定规则，可以直接拼出来，不必依赖 API 的资源列表
                    info.apkUrl = "https://github.com/" + OWNER + "/" + REPO
                            + "/releases/download/" + info.tag + "/Qusic-" + info.tag + ".apk";
                }

                final Info fi = info;
                final String fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fi, fe);
                }});
            }
        });
    }

    /** 解析 releases.atom：取最新一条的 tag、标题、正文 */
    private static void parseAtom(String xml, Info info) {
        if (xml == null || xml.length() == 0) return;
        // 第一个 <entry> 就是最新版本
        int e = xml.indexOf("<entry>");
        if (e < 0) return;
        int end = xml.indexOf("</entry>", e);
        String entry = end > 0 ? xml.substring(e, end) : xml.substring(e);

        java.util.regex.Matcher m = java.util.regex.Matcher.class != null
                ? java.util.regex.Pattern.compile(
                        "Repository/\\d+/([^<]+)</id>").matcher(entry) : null;
        if (m != null && m.find()) info.tag = m.group(1).trim();

        java.util.regex.Matcher t = java.util.regex.Pattern.compile(
                "<title>([^<]*)</title>").matcher(entry);
        if (t.find()) info.name = unescape(t.group(1));

        java.util.regex.Matcher c = java.util.regex.Pattern.compile(
                "<content type=\"html\">(.*?)</content>", java.util.regex.Pattern.DOTALL).matcher(entry);
        if (c.find()) info.notes = stripHtml(unescape(c.group(1)));
    }

    /** 只拿 tag：读 releases/latest 的 302 Location，不跟随跳转 */
    private static String tagFromRedirect() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST_PAGE).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Qusic-Updater");
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null) return "";
        int i = loc.lastIndexOf("/tag/");
        return i < 0 ? "" : loc.substring(i + 5).trim();
    }

    /** 把 release 正文里的 HTML 变成可读纯文本 */
    private static String stripHtml(String h) {
        if (h == null) return "";
        String s = h.replace("\r", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|h[1-6])>", "\n");
        s = s.replaceAll("(?i)<li>", "· ");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static String nz(String v) { return v == null ? "" : v; }

    private static String friendly(Throwable t) {
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        if (m.contains("rate limit") || m.contains("API rate"))
            return "GitHub 接口限流了（共享 IP 常见），稍后再试或打开代理";
        if (m.contains("Unable to resolve host") || m.contains("Failed to connect"))
            return "连不上 GitHub（可能需要代理）";
        return "检查失败：" + m;
    }

    // ── 下载并安装 ──────────────────────────────────────────────────────────
    private static long sDownloadId = -1;
    private static BroadcastReceiver sReceiver;

    /**
     * 用系统 DownloadManager 下载 APK，完成后拉起安装界面。
     * 下载过程交给系统，App 被杀也不影响。
     */
    public static void downloadAndInstall(final Activity act, final Info info) {
        if (info == null || info.apkUrl.length() == 0) {
            toast(act, "这个版本没有附带 APK 附件");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
            // 没有「安装未知应用」权限：引导用户去开
            toast(act, "请先允许 Qusic 安装应用");
            try {
                act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
            } catch (Throwable ignored) {}
            return;
        }
        try {
            String fileName = "Qusic-" + info.tag + ".apk";
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.apkUrl));
            req.setTitle("Qusic " + info.tag);
            req.setDescription("正在下载更新…");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager dm = (DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm == null) { toast(act, "系统下载服务不可用"); return; }
            sDownloadId = dm.enqueue(req);
            toast(act, "开始下载，完成后会提示安装");

            // 下载完成 → 拉起安装
            if (sReceiver != null) {
                try { act.unregisterReceiver(sReceiver); } catch (Throwable ignored) {}
            }
            sReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id != sDownloadId) return;
                    try { c.unregisterReceiver(this); } catch (Throwable ignored) {}
                    sReceiver = null;
                    try {
                        DownloadManager d = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
                        Uri uri = d == null ? null : d.getUriForDownloadedFile(id);
                        if (uri == null) { toast(c, "下载失败，可到 Release 页面手动下载"); return; }
                        Intent inst = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                        c.startActivity(inst);
                    } catch (Throwable t) {
                        toast(c, "无法自动安装，请到「下载」目录手动安装");
                    }
                }
            };
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                act.registerReceiver(sReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                act.registerReceiver(sReceiver, f);
            }
        } catch (Throwable t) {
            toast(act, "下载失败：" + t.getClass().getSimpleName());
        }
    }

    /** 打开 Release 页面（浏览器） */
    public static void openReleasesPage(Activity act) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)));
        } catch (Throwable t) {
            toast(act, "没有可用的浏览器");
        }
    }

    private static void toast(Context c, String m) {
        android.widget.Toast.makeText(c, m, android.widget.Toast.LENGTH_LONG).show();
    }

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Qusic-Updater");
        StringBuilder sb = new StringBuilder();
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
        }
        c.disconnect();
        return sb.toString();
    }

    // ── 更新弹窗 ────────────────────────────────────────────────────────────
    /** 展示更新日志 + 「下载并安装」 */
    public static void showUpdateDialog(final android.app.Activity act, final Info info) {
        showUpdateDialog(act, info, false);
    }

    /**
     * @param auto true = 已开启自动更新，弹窗里说明「正在后台下载」
     */
    public static void showUpdateDialog(final android.app.Activity act, final Info info,
                                        final boolean auto) {
        if (act == null || act.isFinishing()) return;
        Tokens t = Theme.t();
        int pad = Ui.px(act, 22);

        android.widget.LinearLayout box = Ui.column(act);
        box.setPadding(pad, Ui.px(act, 20), pad, Ui.px(act, 10));

        android.widget.TextView title = new android.widget.TextView(act);
        title.setText("发现新版本 " + info.tag);
        title.setTextColor(t.onSurface);
        title.setTextSize(19);
        title.setTypeface(Ui.tfBold());
        box.addView(title);

        android.widget.TextView sub = new android.widget.TextView(act);
        sub.setText(auto
                ? "当前 " + localVersionName(act) + " · 已开启自动更新，正在后台下载"
                : "当前版本 " + localVersionName(act));
        sub.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        sub.setTextSize(12.5f);
        sub.setPadding(0, Ui.px(act, 4), 0, Ui.px(act, 14));
        box.addView(sub);

        android.widget.TextView head = new android.widget.TextView(act);
        head.setText("更新内容");
        head.setTextColor(t.onSurface);
        head.setTextSize(13.5f);
        head.setTypeface(Ui.tfBold());
        head.setPadding(0, 0, 0, Ui.px(act, 6));
        box.addView(head);

        // 更新日志（去掉 markdown 标记，纯文本展示更好读）
        android.widget.TextView notes = new android.widget.TextView(act);
        notes.setText(plainify(info.notes));
        notes.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
        notes.setTextSize(12.5f);
        notes.setLineSpacing(Ui.px(act, 4), 1f);
        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(notes);
        int maxH = (int) (Ui.screenH(act) * 0.34f);
        sv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        box.addView(sv);

        android.widget.LinearLayout btns = Ui.row(act);
        btns.setPadding(0, Ui.px(act, 16), 0, 0);
        btns.setGravity(android.view.Gravity.END);

        android.widget.TextView later = new android.widget.TextView(act);
        later.setText("稍后");
        later.setTextSize(13.5f);
        later.setTypeface(Ui.tfMed());
        later.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
        later.setPadding(Ui.px(act, 18), Ui.px(act, 10), Ui.px(act, 18), Ui.px(act, 10));
        btns.addView(later);

        android.widget.TextView go = new android.widget.TextView(act);
        go.setText(auto ? "查看进度 / 安装"
                : (info.apkUrl.length() > 0 ? "下载并安装" : "去下载"));
        go.setTextSize(13.5f);
        go.setTypeface(Ui.tfBold());
        go.setTextColor(t.onPrimary);
        go.setPadding(Ui.px(act, 20), Ui.px(act, 10), Ui.px(act, 20), Ui.px(act, 10));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(t.primary);
        g.setCornerRadius(Ui.px(act, 22));
        go.setBackground(g);
        android.widget.LinearLayout.LayoutParams glp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = Ui.px(act, 8);
        btns.addView(go, glp);
        box.addView(btns);

        final android.app.Dialog d = new android.app.Dialog(act);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            w.setLayout((int) (Ui.screenW(act) * 0.88f),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.surfaceContainerHigh);
        bg.setCornerRadius(Ui.px(act, 28));
        box.setBackground(bg);
        d.setContentView(box);

        later.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) { d.dismiss(); }
        });
        go.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                d.dismiss();
                // 自动模式下已经在下载了，这里只负责在没下载时补一次
                if (info.apkUrl.length() > 0) {
                    if (!auto) downloadAndInstall(act, info);
                } else {
                    openReleasesPage(act);
                }
            }
        });
        d.show();
    }

    /** 把 markdown 粗化成纯文本，避免在 TextView 里满屏 # 和 * */
    private static String plainify(String md) {
        if (md == null) return "";
        String s = md.replace("\r", "");
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");      // 标题
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");  // 粗体
        s = s.replaceAll("`([^`]*)`", "$1");           // 行内代码
        s = s.replaceAll("(?m)^[-*]\\s+", "· ");      // 列表
        s = s.replaceAll("\\[(.+?)\\]\\((.+?)\\)", "$1");  // 链接
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
