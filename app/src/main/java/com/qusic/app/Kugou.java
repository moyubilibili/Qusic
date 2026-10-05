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
 * 酷狗音源。
 *
 * <p>这一套接口<b>不需要任何签名</b>，比新版 gateway 好得多
 * （那个要 {@code kg-thash} 之类的一堆头，而且实测返回 502）。
 *
 * <ol>
 *   <li>搜索：{@code mobilecdn.kugou.com/api/v3/search/song}</li>
 *   <li>播放地址：{@code m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=}</li>
 *   <li>歌词：{@code krcs.kugou.com/search} 拿 id+accesskey，
 *       再去 {@code lyrics.kugou.com/download} 取 base64 的 LRC</li>
 * </ol>
 *
 * <p><b>付费曲目</b>：接口会明确返回 {@code error:"需要付费"} / {@code privilege:10}，
 * 此时 url 为空。这不是故障，要如实告诉用户「这首歌需要付费」，
 * 而不是含糊地说「放不了」或者无限重试。
 */
public final class Kugou {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";

    private Kugou() {}

    // ── 搜索 ────────────────────────────────────────────────────────────────
    public static void search(final Context ctx, final String keyword,
                              final Online.SearchCallback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                List<Song> out = new ArrayList<>();
                String err = null;
                try {
                    String kw = URLEncoder.encode(keyword, "UTF-8");
                    String body = get("http://mobilecdn.kugou.com/api/v3/search/song"
                            + "?format=json&keyword=" + kw + "&page=1&pagesize=30&showtype=1",
                            "http://m.kugou.com/");
                    Object root = Json.parse(body);
                    List<Object> info = Json.list(root, "data", "info");
                    for (Object o : info) out.add(toSong(o));
                    if (out.isEmpty()) {
                        long total = Json.lng(root, "data", "total");
                        err = total == 0 ? "没有搜到结果" : "酷狗返回了异常数据";
                    }
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
        s.source = Song.SOURCE_KUGOU;

        String hash = Json.str(o, "hash");
        s.uri = "kugou:" + (hash == null ? "" : hash);
        s.path = s.uri;
        // 用 hash 的哈希当 id（hash 本身是十六进制串，放不进 long）
        s.id = -Math.abs(s.uri.hashCode());

        s.title = nz(Json.str(o, "songname"), "未知曲目");
        s.artist = nz(Json.str(o, "singername"), "未知歌手");
        s.album = nz(Json.str(o, "album_name"), "");
        long sec = Json.lng(o, "duration");
        s.durationMs = sec > 0 ? sec * 1000L : 0;
        // 搜索接口给的 hash 留一份，解析地址时要用
        s.mediaId = 0;
        s.mime = hash;   // 复用字段存 hash，播放地址解析只需要它
        // 搜索接口不返回封面字段，但可以用 album_id 拼出来。
        // 实测 http://imge.kugou.com/stdmusic/480/<album_id>.jpg 返回 200 image/jpeg
        long albumId = Json.lng(o, "album_id");
        s.coverUrl = albumId > 0
                ? "http://imge.kugou.com/stdmusic/480/" + albumId + ".jpg"
                : "";
        return s;
    }

    // ── 播放地址 ────────────────────────────────────────────────────────────
    public static void resolveUrl(final Context ctx, final Song song,
                                  final Online.UrlCallback cb) {
        if (song == null || song.mime == null || song.mime.length() == 0) {
            if (cb != null) cb.onResult(null, "这首歌缺少必要信息");
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String url = null, err = null;
                try {
                    String body = get("http://m.kugou.com/app/i/getSongInfo.php"
                            + "?cmd=playInfo&hash=" + song.mime, "http://m.kugou.com/");
                    Object root = Json.parse(body);
                    String u = Json.str(root, "url");
                    if (u != null && u.length() > 0) {
                        url = u;
                    } else {
                        // 区分「要付费」和「真的取不到」，别让用户一脸茫然
                        long priv = Json.lng(root, "privilege");
                        String e2 = Json.str(root, "error");
                        if (priv >= 10 || (e2 != null && e2.contains("付费"))) {
                            err = "这首歌在酷狗需要付费，换一首或换个音源吧";
                        } else if (e2 != null && e2.length() > 0) {
                            err = e2;
                        } else {
                            err = "酷狗没有返回播放地址";
                        }
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
    /**
     * 取歌词：先搜候选拿 id + accesskey，再下载正文。
     * 返回的是标准 LRC（可能带 [ar:] [ti:] 之类的元信息行，解析时会自动跳过）。
     */
    public static void fetchLyrics(final Context ctx, final Song song,
                                   final Online.LyricsCallback cb) {
        if (song == null || song.mime == null || song.mime.length() == 0) {
            if (cb != null) cb.onResult(null, null);
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String lrc = null, err = null;
                try {
                    String kw = URLEncoder.encode(song.title, "UTF-8");
                    long dur = song.durationMs / 1000;
                    String search = get("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi"
                            + "&keyword=" + kw + "&duration=" + dur + "&hash=" + song.mime,
                            "https://www.kugou.com/");
                    Object root = Json.parse(search);
                    List<Object> cands = Json.list(root, "candidates");
                    if (!cands.isEmpty()) {
                        Object c = cands.get(0);
                        String id = Json.str(c, "id");
                        String key = Json.str(c, "accesskey");
                        if (id != null && key != null) {
                            String body = get("https://lyrics.kugou.com/download"
                                    + "?ver=1&client=pc&id=" + id + "&accesskey=" + key
                                    + "&fmt=lrc&charset=utf8", "https://www.kugou.com/");
                            Object lr = Json.parse(body);
                            String content = Json.str(lr, "content");
                            if (content != null && content.length() > 0) {
                                lrc = new String(android.util.Base64.decode(
                                        content, android.util.Base64.DEFAULT), "UTF-8");
                            }
                        }
                    }
                    // 没有候选 = 这首歌没歌词，不算错误
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

    private static String get(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer);
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
