package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易云音乐客户端（匿名模式）。
 *
 * <p><b>刻意不接入账号登录。</b>第三方客户端登录网易云账号会被风控冻结（社区有大量
 * 实例），所以这里只用匿名身份：先按官方要求铺一套设备指纹 cookie，再调
 * {@code register/anonimous} 拿一个全新的匿名账号。它和你自己的账号完全无关，
 * 因此不存在封号风险。
 *
 * <p>协议要点（weapi）：
 * <pre>
 *   inner  = base64(AES-128-CBC(json, presetKey, iv))
 *   params = base64(AES-128-CBC(inner, secretKey, iv))
 *   encSecKey = hex(RSA-NoPadding(reverse(secretKey), pubkey))   // 左侧补零到 128 字节
 * </pre>
 * 全部用 JDK 自带的 javax.crypto / java.security 实现，不引第三方库。
 *
 * <p>接口选择：{@code cloudsearch/get/web} 会被风控（code 50000005），
 * 实测 {@code search/get} 可用，所以走后者。
 */
public final class NetEase {

    private static final String IV = "0102030405060708";
    private static final String PRESET = "0CoJUm6Qyw8W8jud";
    private static final String PUB =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7cl"
            + "FSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMld"
            + "czhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final String B62 =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final Random RND = new Random();
    private static String sCookie = "";
    private static boolean sInited;
    private static String sDeviceId = "";
    private static SharedPreferences sPrefs;


    private NetEase() {}

    /** 恢复上次的匿名身份（cookie 里有登录态，复用可少一次注册） */
    public static void init(Context ctx) {
        if (sPrefs == null) {
            sPrefs = ctx.getApplicationContext()
                    .getSharedPreferences("qusic_netease", Context.MODE_PRIVATE);
            sCookie = sPrefs.getString("cookie", "");
            sDeviceId = sPrefs.getString("device", "");
        }
    }

    public static boolean isReady() { return sInited; }

    // ── 会话 ────────────────────────────────────────────────────────────────
    private static void ensureSession(Context ctx) {
        if (sInited) return;
        init(ctx);
        if (sDeviceId == null || sDeviceId.length() == 0) sDeviceId = hex(16);
        seedCookies();
        try {
            String r = weapi("register/anonimous",
                    "{\"username\":\"" + anonUsername(sDeviceId) + "\",\"rememberLogin\":\"true\"}");
            Object root = Json.parse(r);
            long uid = Json.lng(root, "userId");
            sInited = uid > 0;
            if (sPrefs != null) {
                sPrefs.edit().putString("cookie", sCookie).putString("device", sDeviceId).apply();
            }
        } catch (Throwable t) {
            sInited = false;
        }
    }

    /** 官方要求的设备指纹 cookie；缺了会被风控（code 400 / 50000005） */
    private static void seedCookies() {
        String nuid = hex(16);
        long ts = System.currentTimeMillis();
        if (sCookie == null || sCookie.length() == 0) {
            sCookie = "_ntes_nuid=" + nuid
                    + "; _ntes_nnid=" + nuid + "," + ts
                    + "; WNMCID=" + randLower(6) + "." + ts + ".01.0"
                    + "; WEVNSM=1.0.0"
                    + "; __remember_me=true"
                    + "; ntes_kaola_ad=1"
                    + "; osver=14"
                    + "; deviceId=" + sDeviceId
                    + "; os=android"
                    + "; appver=9.1.65"
                    + "; channel=xiaomi";
        }
    }

    private static String anonUsername(String deviceId) throws Exception {
        String key = "3go8&$8*3*3h0k(2)2";
        byte[] d = deviceId.getBytes(StandardCharsets.UTF_8);
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] x = new byte[d.length];
        for (int i = 0; i < d.length; i++) x[i] = (byte) (d[i] ^ k[i % k.length]);
        String h = b64(MessageDigest.getInstance("MD5").digest(x));
        return b64((deviceId + " " + h).getBytes(StandardCharsets.UTF_8));
    }

    // ── 对外 API ────────────────────────────────────────────────────────────
    /** 搜索歌曲（后台线程执行，回调在主线程） */
    public static void search(final Context ctx, final String keyword, final Online.SearchCallback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String err = null;
                List<Song> out = new ArrayList<>();
                try {
                    ensureSession(ctx);
                    String r = weapi("search/get",
                            "{\"s\":\"" + esc(keyword) + "\",\"type\":1,\"limit\":30,"
                            + "\"offset\":0,\"total\":true}");
                    Object root = Json.parse(r);
                    List<Object> songs = Json.list(root, "result", "songs");
                    for (Object o : songs) out.add(toSong(o));
                    if (out.isEmpty()) {
                        Object code = Json.path(root, "code");
                        err = code != null ? "接口返回 code=" + code + "（可能被风控，稍后再试）"
                                           : "没有搜到结果";
                    }
                } catch (Throwable t) {
                    err = "网络请求失败：" + t.getClass().getSimpleName();
                }
                final List<Song> fo = out;
                final String fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fo, fe);
                }});
            }
        });
    }

    /** 解析播放地址（后台线程执行，回调在主线程）。返回的 url 可能为空（VIP/下架） */
    public static void resolveUrl(final Context ctx, final Song song, final Online.UrlCallback cb) {
        if (song == null) return;
        if (song.streamUrl != null && song.streamUrl.length() > 0) {
            if (cb != null) cb.onResult(song.streamUrl, null);
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String url = null, err = null;
                try {
                    ensureSession(ctx);
                    String r = weapi("song/enhance/player/url/v1",
                            "{\"ids\":\"[" + song.neteaseId + "]\",\"level\":\"standard\","
                            + "\"encodeType\":\"mp3\"}");
                    Object root = Json.parse(r);
                    List<Object> data = Json.list(root, "data");
                    if (!data.isEmpty()) {
                        String u = Json.str(data.get(0), "url");
                        int code = (int) Json.lng(data.get(0), "code");
                        if (u != null && u.startsWith("http")) url = u;
                        else if (code == 404) err = "这首要 VIP 或已下架，换一首试试";
                        else err = "拿不到播放地址（code=" + code + "）";
                    } else {
                        err = "接口没有返回数据";
                    }
                } catch (Throwable t) {
                    err = "网络请求失败：" + t.getClass().getSimpleName();
                }
                if (url != null) song.streamUrl = url;
                final String fu = url, fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fu, fe);
                }});
            }
        });
    }

    /** 把一条 JSON 歌曲转成 Song */
    private static Song toSong(Object o) {
        Song s = new Song();
        s.online = true;
        s.source = Song.SOURCE_NETEASE;
        s.neteaseId = Json.lng(o, "id");
        s.id = -Math.abs(s.neteaseId == 0 ? System.nanoTime() : s.neteaseId);  // 负数避免和本地 id 撞
        s.title = nz(Json.str(o, "name"), "未知曲目");
        s.durationMs = Json.lng(o, "duration");

        // 歌手：可能多个，用 / 连接
        List<Object> artists = Json.list(o, "artists");
        if (artists.isEmpty()) artists = Json.list(o, "ar");
        StringBuilder ab = new StringBuilder();
        for (Object a : artists) {
            String n = Json.str(a, "name");
            if (n != null && n.length() > 0) {
                if (ab.length() > 0) ab.append(" / ");
                ab.append(n);
            }
        }
        s.artist = ab.length() > 0 ? ab.toString() : "未知歌手";

        Object album = Json.path(o, "album");
        if (album == null) album = Json.path(o, "al");
        s.album = nz(Json.str(album, "name"), "");
        s.coverUrl = nz(Json.str(album, "picUrl"), "");
        if (s.coverUrl.length() == 0) s.coverUrl = nz(Json.str(album, "picUrl_str"), "");

        s.uri = "netease:" + s.neteaseId;
        s.path = s.uri;
        return s;
    }

    private static String nz(String v, String def) {
        return v == null || v.length() == 0 ? def : v;
    }

    // ── weapi 加密 ──────────────────────────────────────────────────────────
    private static String weapi(String path, String json) throws Exception {
        String secret = randB62(16);
        String params = aesCbc(aesCbc(json, PRESET), secret);
        String enc = rsaHex(secret);
        return post("https://music.163.com/weapi/" + path,
                "params=" + URLEncoder.encode(params, "UTF-8") + "&encSecKey=" + enc);
    }

    private static String aesCbc(String text, String key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(IV.getBytes(StandardCharsets.UTF_8)));
        return b64(c.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String rsaHex(String secret) throws Exception {
        byte[] data = secret.getBytes(StandardCharsets.UTF_8);
        byte[] rev = new byte[data.length];
        for (int i = 0; i < data.length; i++) rev[i] = data[data.length - 1 - i];
        PublicKey pk = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUB)));
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, pk);
        byte[] block = new byte[128];                 // 左侧补零到模长
        System.arraycopy(rev, 0, block, 128 - rev.length, rev.length);
        byte[] out = c.doFinal(block);
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://music.163.com");
        c.setRequestProperty("Origin", "https://music.163.com");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        if (!url.contains("login")) sCookie = addNmtid(sCookie);
        if (sCookie.length() > 0) c.setRequestProperty("Cookie", sCookie);
        OutputStream o = c.getOutputStream();
        o.write(body.getBytes(StandardCharsets.UTF_8));
        o.close();
        mergeCookie(c.getHeaderField("Set-Cookie"));
        StringBuilder sb = new StringBuilder();
        java.io.InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
        }
        c.disconnect();
        return sb.toString();
    }

    private static String addNmtid(String ck) {
        if (ck == null || ck.contains("NMTID=")) return ck == null ? "" : ck;
        return ck.isEmpty() ? "NMTID=" + hex(16) : ck + "; NMTID=" + hex(16);
    }

    private static void mergeCookie(String sc) {
        if (sc == null) return;
        for (String part : sc.split(",")) {
            String kv = part.split(";")[0].trim();
            if (!kv.contains("=")) continue;
            String name = kv.substring(0, kv.indexOf('='));
            if (name.startsWith("Expires") || name.startsWith("Path")
                    || name.startsWith("Domain") || name.startsWith("Max-Age")
                    || name.startsWith("SameSite")) continue;
            if (sCookie.contains(name + "=")) continue;
            sCookie = sCookie.isEmpty() ? kv : sCookie + "; " + kv;
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────
    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }

    private static String randB62(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(B62.charAt(RND.nextInt(B62.length())));
        return sb.toString();
    }

    private static String randLower(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append((char) ('a' + RND.nextInt(26)));
        return sb.toString();
    }

    private static String hex(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(String.format("%02x", RND.nextInt(256)));
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
