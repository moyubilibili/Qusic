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

    public static void saveLogin(Context c, String token, String name) {
        init(c);
        sPrefs.edit().putString(K_TOKEN, token).putString(K_NAME, name).apply();
    }

    public static void logout(Context c) {
        init(c);
        sPrefs.edit().remove(K_TOKEN).remove(K_NAME).apply();
    }

    // ── 回调 ────────────────────────────────────────────────────────────────
    /** 通用回调：成功给 data(String 或 List)，失败给 error */
    public interface Callback { void onResult(Object data, String error); }

    // ── 帖子模型 ────────────────────────────────────────────────────────────
    public static class Post {
        public long id;
        public String title = "", note = "", author = "", payload = "";
        public int count, likes, views;
        public int role;          // 1 = 开发者
        public long created;

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
                    saveLogin(ctx, tk, nm);
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
