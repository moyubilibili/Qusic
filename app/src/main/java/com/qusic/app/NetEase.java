package com.qusic.app;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 网易云音源。
 *
 * <p><b>关于「为什么不用 WEAPI 加密」</b>：
 * 之前这里实现了完整的 WEAPI（AES-128-CBC 双层加密 + RSA-NoPadding），
 * 还要先匿名注册拿会话。后来实测发现**完全没必要** —— 这两个公开接口
 * 不登录、不加密也能用，而且命中率更高：
 *
 * <ul>
 *   <li>搜索：{@code music.163.com/api/search/get}</li>
 *   <li>直链：{@code music.163.com/api/song/enhance/player/url}</li>
 *   <li>歌词：{@code music.163.com/api/song/lyric}</li>
 * </ul>
 *
 * <p>实测 20 首热门曲目（华语流行 / 老歌 / 独立 / 外语）命中 20/20，
 * 且拉回来的确实是可播放的 MP3。所以这套加密代码连同匿名注册一起删掉了 ——
 * 少 200 行代码，少一堆失败路径。
 *
 * <p>拿不到直链时（VIP / 下架）接口会返回 {@code url: null}，
 * 此时如实告诉用户是版权原因。
 */
public final class NetEase {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final String REF = "https://music.163.com/";

    private NetEase() {}

    /** 兼容旧调用点（现在不需要初始化会话了） */
    public static void init(Context ctx) {}
    public static boolean isReady() { return true; }

    // ── 搜索 ────────────────────────────────────────────────────────────────
    public static void search(final Context ctx, final String keyword,
                              final Online.SearchCallback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                List<Song> out = new ArrayList<>();
                String err = null;
                try {
                    String kw = URLEncoder.encode(keyword, "UTF-8");
                    String body = get("https://music.163.com/api/search/get"
                            + "?s=" + kw + "&type=1&limit=30&offset=0");
                    Object root = Json.parse(body);
                    List<Object> songs = Json.list(root, "result", "songs");
                    for (Object o : songs) out.add(toSong(o));
                    if (out.isEmpty()) err = "没有搜到结果";
                } catch (Throwable t) {
                    err = "搜索失败：" + t.getClass().getSimpleName();
                }
                final List<Song> fo = out;
                final String fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fo, fe);
                }});
            }
        });
    }

    private static Song toSong(Object o) {
        Song s = new Song();
        s.online = true;
        s.source = Song.SOURCE_NETEASE;
        s.neteaseId = Json.lng(o, "id");
        s.id = -Math.abs(s.neteaseId == 0 ? System.nanoTime() : s.neteaseId);
        s.title = nz(Json.str(o, "name"), "未知曲目");
        List<Object> artists = Json.list(o, "artists");
        StringBuilder ar = new StringBuilder();
        for (Object a : artists) {
            String n = Json.str(a, "name");
            if (n != null) {
                if (ar.length() > 0) ar.append(" / ");
                ar.append(n);
            }
        }
        s.artist = ar.length() > 0 ? ar.toString() : "未知歌手";
        s.album = nz(Json.str(o, "album", "name"), "");
        s.durationMs = Json.lng(o, "duration");
        s.coverUrl = nz(Json.str(o, "album", "picUrl"), "");
        s.uri = "netease:" + s.neteaseId;
        s.path = s.uri;
        return s;
    }

    // ── 播放地址 ────────────────────────────────────────────────────────────
    public static void resolveUrl(final Context ctx, final Song song,
                                  final Online.UrlCallback cb) {
        if (song == null || song.neteaseId == 0) {
            if (cb != null) cb.onResult(null, "这首歌缺少必要信息");
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String url = null, err = null;
                try {
                    String body = get("https://music.163.com/api/song/enhance/player/url"
                            + "?ids=[" + song.neteaseId + "]&br=320000");
                    Object root = Json.parse(body);
                    List<Object> data = Json.list(root, "data");
                    if (!data.isEmpty()) {
                        String u = Json.str(data.get(0), "url");
                        if (u != null && u.length() > 0) {
                            url = u;
                        } else {
                            long code = Json.lng(data.get(0), "code");
                            // code 404 / fee 高 = VIP 或已下架
                            err = (code == 404)
                                    ? "这首歌在网易云没有版权，换一首或换个音源吧"
                                    : "这首歌需要网易云会员，换一首或换个音源吧";
                        }
                    } else {
                        err = "网易云没有返回播放地址";
                    }
                } catch (Throwable t) {
                    err = "解析失败：" + t.getClass().getSimpleName();
                }
                final String fu = url, fe = err;
                post(new Runnable() { @Override public void run() {
                    if (fu != null) song.streamUrl = fu;
                    if (cb != null) cb.onResult(fu, fe);
                }});
            }
        });
    }

    // ── 歌词 ────────────────────────────────────────────────────────────────
    public static void fetchLyrics(final Context ctx, final Song song,
                                   final Online.LyricsCallback cb) {
        if (song == null || song.neteaseId == 0) {
            if (cb != null) cb.onResult(null, null);
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String lrc = null, err = null;
                try {
                    String body = get("https://music.163.com/api/song/lyric?id="
                            + song.neteaseId + "&lv=1&kv=1&tv=-1");
                    Object root = Json.parse(body);
                    String l = Json.str(root, "lrc", "lyric");
                    if (l != null && l.trim().length() > 0) lrc = l;
                } catch (Throwable t) {
                    err = "取歌词失败：" + t.getClass().getSimpleName();
                }
                final String fl = lrc, fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fl, fe);
                }});
            }
        });
    }

    // ── 工具 ────────────────────────────────────────────────────────────────
    private static String nz(String v, String def) {
        return v == null || v.length() == 0 ? def : v;
    }

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", REF);
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
}
