package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 社区 API 客户端。
 *
 * <p>零依赖：{@link HttpURLConnection} + 自带的 {@link Json}。
 *
 * <p>接口约定（服务端见 /community/）：
 * <ul>
 *   <li>永远是 HTTP 200，成败看 body 里的 {@code ok} 字段
 *       —— 因为服务器 nginx 配了 error_page，返回 4xx 会被换成 HTML</li>
 *   <li>登录后拿到 token，之后带 token 调用写接口</li>
 * </ul>
 */
public final class Community {

    /** 部署好的社区地址 */
    public static final String BASE = "https://qclear.xyz/community/index.php";
    /** 管理端接口前缀，跟普通接口同一个入口 */
    public static final String K_ROLE = "role";

    private static final String PREFS = "qusic_community";
    private static final String K_TOKEN = "token";
    private static final String K_NAME  = "name";

    private static final String UA = "Qusic-Android";

    private static SharedPreferences sPrefs;

    private Community() {}

    public static void init(Context c) {
        if (sPrefs == null) {
            sPrefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    public static String token(Context c) { init(c); return sPrefs.getString(K_TOKEN, ""); }
    public static String userName(Context c) { init(c); return sPrefs.getString(K_NAME, ""); }
    public static boolean loggedIn(Context c) { return token(c).length() > 0; }

    /**
     * 当前账号是不是管理员。
     *
     * <p>登录接口本来就返回 role，只是以前客户端把它丢了。
     * 这里存下来，社区页据此决定要不要显示管理入口。
     *
     * <p><b>注意这是展示层的判断</b> —— 真正的权限在服务端每个
     * admin_* 接口里各查一次，客户端改这个值也拿不到任何权限。
     */
    public static boolean isAdmin(Context c) {
        init(c);
        return sPrefs.getInt(K_ROLE, 0) >= 1;
    }

    public static void saveLogin(Context c, String token, String name) {
        saveLogin(c, token, name, 0);
    }

    public static void saveLogin(Context c, String token, String name, int role) {
        init(c);
        sPrefs.edit().putString(K_TOKEN, token).putString(K_NAME, name)
                .putInt(K_ROLE, role).apply();
    }

    public static void logout(Context c) {
        init(c);
        sPrefs.edit().remove(K_TOKEN).remove(K_NAME).remove(K_ROLE).apply();
    }

    // ── 回调 ────────────────────────────────────────────────────────────────
    /** 通用回调：成功给 data(String 或 List)，失败给 error */
    public interface Callback { void onResult(Object data, String error); }

    // ── 帖子模型 ────────────────────────────────────────────────────────────
    /** 板块：0 = 歌单分享，1 = 论坛 */
    public static final int BOARD_PLAYLIST = 0, BOARD_FORUM = 1;

    /** 帖子/歌单里引用的歌曲 */
    public static class SongRef {
        public int source;
        public long sid;
        public String title = "", artist = "", album = "", cover = "";
        public long duration;

        public static SongRef of(Object o) {
            SongRef r = new SongRef();
            Object src = Json.path(o, "source");
            r.source = src instanceof Number ? ((Number) src).intValue() : 0;
            Object sid = Json.path(o, "sid");
            r.sid = sid instanceof Number ? ((Number) sid).longValue() : 0;
            r.title = nz(Json.str(o, "title"));
            r.artist = nz(Json.str(o, "artist"));
            r.album = nz(Json.str(o, "album"));
            r.cover = nz(Json.str(o, "cover"));
            Object d = Json.path(o, "duration");
            r.duration = d instanceof Number ? ((Number) d).longValue() : 0;
            return r;
        }
    }

    /** 帖子里的图片 */
    public static class ImageRef {
        public String url = "";
        public int w, h, bytes;

        public static ImageRef of(Object o) {
            ImageRef r = new ImageRef();
            r.url = nz(Json.str(o, "url"));
            Object w2 = Json.path(o, "w");
            r.w = w2 instanceof Number ? ((Number) w2).intValue() : 0;
            Object h2 = Json.path(o, "h");
            r.h = h2 instanceof Number ? ((Number) h2).intValue() : 0;
            Object b = Json.path(o, "bytes");
            r.bytes = b instanceof Number ? ((Number) b).intValue() : 0;
            return r;
        }
    }

    public static class Post {
        public long id;
        public String title = "", note = "", author = "", payload = "";
        public int count, likes, views;
        public int role;          // 1 = 开发者
        public long created;

        // ── 论坛板块才有的字段 ──
        /** 0 = 歌单分享，1 = 论坛 */
        public int board;
        /** 论坛正文（歌单帖为空） */
        public String body = "";
        public List<String> tags = new ArrayList<>();
        public List<ImageRef> images = new ArrayList<>();
        public List<SongRef> songs = new ArrayList<>();

        static Post of(Object o) {
            Post p = new Post();
            p.id      = Json.lng(o, "id");
            p.title   = nz(Json.str(o, "title"));
            p.note    = nz(Json.str(o, "note"));
            p.author  = nz(Json.str(o, "author"));
            p.payload = nz(Json.str(o, "payload"));
            p.count   = (int) Json.lng(o, "count");
            p.likes   = (int) Json.lng(o, "likes");
            p.views   = (int) Json.lng(o, "views");
            p.created = Json.lng(o, "created");
            p.role    = (int) Json.lng(o, "author_role");
            p.board   = (int) Json.lng(o, "board");
            p.body    = nz(Json.str(o, "body"));
            for (Object t : Json.list(o, "tags")) {
                if (t != null) p.tags.add(String.valueOf(t));
            }
            for (Object im : Json.list(o, "images")) p.images.add(ImageRef.of(im));
            for (Object sg : Json.list(o, "songs"))  p.songs.add(SongRef.of(sg));
            return p;
        }
    }

    public static class Comment {
        public long id;
        public String body = "", author = "";
        public int role;
        public long created;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    // ══ 接口 ═══════════════════════════════════════════════════════════════

    /** 列表。sort = new | hot；q 为搜索词（可空） */
    public static void list(final String sort, final int page, final String q,
                            final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    String url = BASE + "?a=list&sort=" + sort + "&page=" + page;
                    if (q != null && q.length() > 0) {
                        url += "&q=" + java.net.URLEncoder.encode(q, "UTF-8");
                    }
                    Reply r = call(url, null);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    List<Post> out = new ArrayList<>();
                    for (Object o : Json.list(r.root, "data", "items")) out.add(Post.of(o));
                    done(cb, out, null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    /** 取单个歌单（含 payload） */
    public static void get(final long id, final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    Reply r = call(BASE + "?a=get&id=" + id, null);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    done(cb, Post.of(Json.path(r.root, "data")), null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    public static void comments(final long id, final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    Reply r = call(BASE + "?a=comments&id=" + id, null);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    List<Comment> out = new ArrayList<>();
                    for (Object o : Json.list(r.root, "data", "items")) {
                        Comment c = new Comment();
                        c.id = Json.lng(o, "id");
                        c.body = nz(Json.str(o, "body"));
                        c.author = nz(Json.str(o, "author"));
                        c.created = Json.lng(o, "created");
                        c.role = (int) Json.lng(o, "author_role");
                        out.add(c);
                    }
                    done(cb, out, null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    /** 注册。成功回调返回 null（提示去收邮件） */
    public static void register(final Context ctx, final String name, final String email,
                                final String pass, final Callback cb) {
        post(ctx, "register", "{\"name\":" + Json.q(name)
                + ",\"email\":" + Json.q(email)
                + ",\"pass\":" + Json.q(pass) + "}", cb, false);
    }

    /** 登录。成功后自动保存 token */
    public static void login(final Context ctx, final String name, final String pass,
                             final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    Reply r = call(BASE + "?a=login", "{\"name\":" + Json.q(name)
                            + ",\"pass\":" + Json.q(pass) + "}");
                    if (r.error != null) { done(cb, null, r.error); return; }
                    Object d = Json.path(r.root, "data");
                    String tk = nz(Json.str(d, "token"));
                    String nm = nz(Json.str(d, "name"));
                    boolean vf = "true".equals(String.valueOf(Json.path(d, "verified")));
                    int role = 0;
                    try { role = Integer.parseInt(String.valueOf(Json.path(d, "role"))); }
                    catch (Throwable ignored) {}
                    saveLogin(ctx, tk, nm, role);
                    done(cb, vf ? "1" : "0", null);   // 返回验证状态
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    /** 发布歌单 */
    public static void publish(final Context ctx, final String title, final String note,
                               final String payload, final Callback cb) {
        post(ctx, "publish", "{\"title\":" + Json.q(title)
                + ",\"note\":" + Json.q(note)
                + ",\"payload\":" + Json.q(payload) + "}", cb, true);
    }

    public static void like(final Context ctx, final long id, final Callback cb) {
        post(ctx, "like", "{\"id\":" + id + "}", cb, true);
    }

    public static void comment(final Context ctx, final long id, final String body,
                               final Callback cb) {
        post(ctx, "comment", "{\"id\":" + id + ",\"body\":" + Json.q(body) + "}", cb, true);
    }

    public static void report(final Context ctx, final long id, final String reason,
                              final Callback cb) {
        post(ctx, "report", "{\"id\":" + id + ",\"reason\":" + Json.q(reason) + "}", cb, true);
    }

    public static void mine(final Context ctx, final Callback cb) {
        post(ctx, "mine", "{}", cb, true);
    }

    public static void delete(final Context ctx, final long id, final Callback cb) {
        post(ctx, "delete", "{\"id\":" + id + "}", cb, true);
    }

    /** 重发验证邮件 */
    public static void resend(final Context ctx, final String email, final Callback cb) {
        post(ctx, "resend", "{\"email\":" + Json.q(email) + "}", cb, false);
    }

    // ══ 内部 ═══════════════════════════════════════════════════════════════
    private static void post(final Context ctx, final String action, final String body,
                             final Callback cb, final boolean needAuth) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    String b = body;
                    if (needAuth) {
                        // 把 token 塞进 JSON 里（服务端两种都认）
                        b = "{\"token\":" + Json.q(token(ctx)) + ","
                                + (body.length() > 2 ? body.substring(1) : "\"x\":1}");
                    }
                    Reply r = call(BASE + "?a=" + action, b);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    done(cb, Json.path(r.root, "data"), null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    /**
     * 同步当前用户的真实信息（尤其是 role）。
     *
     * <p>为什么需要这个：role 原本只在 login 时下发，于是
     * 「加 role 字段之前就登录了」的老会话永远拿不到角色，管理入口根本不显示；
     * 被提升 / 撤销管理员也得重新登录才生效。启动时拉一次就跟服务端对齐了。
     *
     * <p>失败时静默 —— 网络不好不该影响启动。
     */
    public static void syncMe(final Context ctx) {
        if (!loggedIn(ctx)) return;
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    Reply r = call(BASE + "?a=me&token=" + enc(token(ctx)), null);
                    if (r.error != null) return;
                    Object d = Json.path(r.root, "data");
                    int role = 0;
                    try { role = Integer.parseInt(String.valueOf(Json.path(d, "role"))); }
                    catch (Throwable ignored) {}
                    String nm = nz(Json.str(d, "name"));
                    init(ctx);
                    sPrefs.edit().putInt(K_ROLE, role)
                            .putString(K_NAME, nm.length() == 0 ? userName(ctx) : nm).apply();
                } catch (Throwable ignored) {}
            }
        });
    }

    // ══ 论坛 ════════════════════════════════════════════════════════════

    /** 按板块取列表。tag 非空则只看这个标签下的 */
    public static void listBoard(final int board, final String sort, final int page,
                                 final String q, final String tag, final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    StringBuilder u = new StringBuilder(BASE)
                            .append("?a=list&board=").append(board)
                            .append("&sort=").append(sort)
                            .append("&page=").append(page);
                    if (q != null && q.length() > 0) u.append("&q=").append(enc(q));
                    if (tag != null && tag.length() > 0) u.append("&tag=").append(enc(tag));
                    Reply r = call(u.toString(), null);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    List<Post> out = new ArrayList<>();
                    for (Object o : Json.list(r.root, "data", "items")) out.add(Post.of(o));
                    done(cb, out, null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    /** 热门标签 */
    public static void tags(final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    Reply r = call(BASE + "?a=tags&limit=30", null);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    List<String[]> out = new ArrayList<>();
                    for (Object o : Json.list(r.root, "data", "list")) {
                        out.add(new String[]{ nz(Json.str(o, "name")),
                                              String.valueOf(Json.lng(o, "count")) });
                    }
                    done(cb, out, null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    /**
     * 发论坛帖。
     *
     * @param images base64 数组（客户端已压缩过，服务端会再校验一次）
     * @param songsJson 引用歌曲的 JSON 数组原文，形如 [{...}]
     */
    public static void postPublish(final Context ctx, final String title, final String body,
                                   final String songsJson, final String imagesJson,
                                   final Callback cb) {
        String json = "{\"title\":" + Json.q(title)
                + ",\"body\":" + Json.q(body)
                + ",\"songs\":" + (songsJson == null ? "[]" : songsJson)
                + ",\"images\":" + (imagesJson == null ? "[]" : imagesJson) + "}";
        post(ctx, "post_publish", json, cb, true);
    }

    // ══ 管理端 ══════════════════════════════════════════════════════════
    // 服务端每个 admin_* 都会再查一次权限（role >= 1），
    // 所以这里不做客户端判断 —— 传了就传了，没权限会被 403 挡回来。

    public static void adminStats(final Context ctx, final Callback cb) {
        get(ctx, "admin_stats", "", cb);
    }

    public static void adminUsers(final Context ctx, final String q, final int offset,
                                  final Callback cb) {
        get(ctx, "admin_users", "&q=" + enc(q) + "&offset=" + offset + "&limit=50", cb);
    }

    public static void adminPlaylists(final Context ctx, final String q, final String sort,
                                      final int offset, final Callback cb) {
        get(ctx, "admin_playlists", "&q=" + enc(q) + "&sort=" + sort + "&offset=" + offset
                + "&limit=50", cb);
    }

    public static void adminReports(final Context ctx, final Callback cb) {
        get(ctx, "admin_reports", "", cb);
    }

    public static void adminAudit(final Context ctx, final Callback cb) {
        get(ctx, "admin_audit", "", cb);
    }

    public static void adminComments(final Context ctx, final long pid, final Callback cb) {
        get(ctx, "admin_comments", "&playlist_id=" + pid, cb);
    }

    /** 封禁 / 解封 / 设角色 / 重置密码。fields 是要改的字段，没传的不动 */
    public static void adminUserSet(final Context ctx, final long id, final String fields,
                                    final Callback cb) {
        post(ctx, "admin_user_set", "{\"id\":" + id + (fields.length() > 0 ? "," + fields : "") + "}", cb, true);
    }

    public static void adminDelPlaylist(final Context ctx, final long id, final Callback cb) {
        post(ctx, "admin_del_playlist", "{\"id\":" + id + "}", cb, true);
    }

    public static void adminRestorePlaylist(final Context ctx, final long id, final Callback cb) {
        post(ctx, "admin_restore_playlist", "{\"id\":" + id + "}", cb, true);
    }

    public static void adminDelComment(final Context ctx, final long id, final Callback cb) {
        post(ctx, "admin_del_comment", "{\"id\":" + id + "}", cb, true);
    }

    private static String enc(String s) {
        if (s == null) return "";
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Throwable t) { return ""; }
    }

    /**
     * 管理接口的通用 GET。
     *
     * <p>token 放在 **query string** 里 —— {@code call()} 不发 Authorization 头，
     * 而服务端的 {@code q_input()} 会把 {@code $_GET} 合并进来，
     * 所以 {@code q_auth()} 从 URL 里也能读到。
     */
    private static void get(final Context ctx, final String action, final String extra,
                            final Callback cb) {
        final String tk = token(ctx);
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                try {
                    Reply r = call(BASE + "?a=" + action + extra + "&token=" + enc(tk), null);
                    if (r.error != null) { done(cb, null, r.error); return; }
                    done(cb, Json.path(r.root, "data"), null);
                } catch (Throwable t) {
                    done(cb, null, friendly(t));
                }
            }
        });
    }

    private static class Reply { Object root; String error; }

    /** 发一次请求并解析统一响应格式 */
    private static Reply call(String url, String jsonBody) throws Exception {
        Reply r = new Reply();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        if (jsonBody != null) {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = c.getOutputStream();
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }
        StringBuilder sb = new StringBuilder();
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (in != null) {
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = br.readLine()) != null) sb.append(l);
            br.close();
        }
        c.disconnect();

        Object root = Json.parse(sb.toString());
        if (root == null) throw new IllegalStateException("服务器返回的不是 JSON");
        Object okv = Json.path(root, "ok");
        boolean ok = okv instanceof Boolean ? (Boolean) okv
                : "true".equals(String.valueOf(okv));
        if (!ok) {
            String e = Json.str(root, "error");
            r.error = (e == null || e.length() == 0) ? "请求失败" : e;
            return r;
        }
        r.root = root;
        return r;
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.contains("Unable to resolve host") || m.contains("Failed to connect")
                || m.contains("Network is unreachable")) {
            return "连不上社区服务器，检查一下网络";
        }
        if (t instanceof java.net.SocketTimeoutException) return "服务器响应超时";
        return m.length() > 0 ? m : ("请求失败：" + t.getClass().getSimpleName());
    }

    private static void done(final Callback cb, final Object data, final String err) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() { if (cb != null) cb.onResult(data, err); }
        });
    }
}
