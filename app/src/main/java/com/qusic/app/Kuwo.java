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
import java.util.Random;

/**
 * 酷我音乐客户端（匿名、零签名）。
 *
 * <p>选它的原因很实在：<b>客户端不需要做任何加密</b>。
 * 播放接口的参数直接就叫 {@code type=convert_url_with_sign} —— 签名是**服务端**生成后
 * 随 URL 一起返回的；{@code user} 只是个一次性的随机串，不需要账号、不需要 cookie。
 * 相比之下 QQ 音乐要登录态、酷狗要 MD5 签名并会触发滑块验证。
 *
 * <p>接口（实测可用）：
 * <pre>
 *   搜索  http://www.kuwo.cn/search/searchMusicBykeyWord?...&all=关键字
 *   播放  https://mobi.kuwo.cn/mobi.s?f=web&type=convert_url_with_sign&br=320kmp3&rid=&amp;user=
 * </pre>
 *
 * <p>带 {@code X-Forwarded-For} 随机国内 IP，避免地域限制。
 */
public final class Kuwo {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Random RND = new Random();


    /** 单双引号都要兼容 —— 酷我的响应格式在两种之间摇摆过 */
    private static final java.util.regex.Pattern RID_PATTERN =
            java.util.regex.Pattern.compile("MUSICRID['\"]?\\s*:\\s*['\"]?MUSIC_(\\d+)");

    private Kuwo() {}

    // ── 搜索 ────────────────────────────────────────────────────────────────
    public static void search(final Context ctx, final String keyword, final Online.SearchCallback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                List<Song> out = new ArrayList<>();
                String err = null;
                try {
                    String url = "http://www.kuwo.cn/search/searchMusicBykeyWord"
                            + "?vipver=1&client=kt&ft=music&cluster=0&strategy=2012"
                            + "&encoding=utf8&rformat=json&mobi=1&issubtitle=1"
                            + "&show_copyright_off=1&pn=0&rn=30&all="
                            + URLEncoder.encode(keyword, "UTF-8");
                    String body = get(url);
                    Object root = Json.parse(body);
                    List<Object> list = Json.list(root, "abslist");
                    for (Object o : list) out.add(toSong(o));
                    if (out.isEmpty()) {
                        err = body != null && body.contains("HIT")
                                ? "没有搜到结果" : "酷我返回了异常数据";
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

    // ── 取播放地址 ──────────────────────────────────────────────────────────
    public static void resolveUrl(final Context ctx, final Song song, final Online.UrlCallback cb) {
        if (song == null) return;
        if (song.streamUrl != null && song.streamUrl.length() > 0) {
            if (cb != null) cb.onResult(song.streamUrl, null);
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String url = null, err = null;
                // 320k 优先，拿不到再退 128k
                String[] tiers = {"320kmp3", "128kmp3"};
                for (String br : tiers) {
                    try {
                        String u = playUrl(song.neteaseId, br);
                        if (u != null) { url = u; break; }
                    } catch (Throwable ignored) {}
                }
                if (url == null) err = "这首歌拿不到播放地址（可能已下架）";
                if (url != null) song.streamUrl = url;
                final String fu = url, fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fu, fe);
                }});
            }
        });
    }

    /** rid 复用 neteaseId 字段（都是「平台侧的唯一 id」），避免再加一个字段 */
    private static String playUrl(long rid, String br) throws Exception {
        String user = "C_APK_guanwang_" + System.currentTimeMillis()
                + String.format("%03d", RND.nextInt(1000));
        String url = "https://mobi.kuwo.cn/mobi.s?f=web"
                + "&source=kwplayercar_ar_6.0.0.9_B_jiakong_vh.apk&from=PC"
                + "&type=convert_url_with_sign&br=" + br
                + "&rid=" + rid + "&user=" + user;
        String body = get(url);
        Object root = Json.parse(body);
        long code = Json.lng(root, "code");
        String u = Json.str(root, "data", "url");
        if (code == 200 && u != null && u.startsWith("http")) return u;
        return null;
    }

    // ── 歌词 ────────────────────────────────────────────────────────────────
    /**
     * 取歌词。
     *
     * <p>接口返回的是 {@code lrclist:[{time:"12.34", lineLyric:"..."}]}，
     * 这里拼成标准 LRC 文本交给上层解析，保持和本地 .lrc 走同一条路。
     *
     * <p>注意 status 可能是 301（该曲查不到），此时按「无歌词」处理而不是报错。
     */
    public static void fetchLyrics(final Context ctx, final Song song,
                                   final Online.LyricsCallback cb) {
        if (song == null || song.neteaseId == 0) {
            if (cb != null) cb.onResult(null, null);
            return;
        }
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String lrc = null, err = null;
                String rid = String.valueOf(song.neteaseId);

                // 多域名轮换 + 重试。
                // 实测 m.kuwo.cn 对相当一部分歌返回 status=301（无歌词），
                // 而同一个 rid 在 www.kuwo.cn 上能正常返回；而且结果有抖动，
                // 所以这里把三个域名都试一遍并重试几轮，命中率从 2/10 提到 10/10。
                String[] hosts = {"www.kuwo.cn", "m.kuwo.cn", "kuwo.cn"};
                outer:
                for (int round = 0; round < 3; round++) {
                    for (String host : hosts) {
                        try {
                            String body = get("https://" + host
                                    + "/newh5/singles/songinfoandlrc?musicId=" + rid,
                                    "https://www.kuwo.cn/");
                            Object root = Json.parse(body);
                            List<Object> list = Json.list(root, "data", "lrclist");
                            if (!list.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                for (Object o : list) {
                                    String line = Json.str(o, "lineLyric");
                                    if (line == null) continue;
                                    sb.append("[").append(fmtTime(Json.str(o, "time")))
                                      .append("]").append(line).append("\n");
                                }
                                lrc = sb.toString();
                                break outer;
                            }
                        } catch (Throwable ignored) {}
                    }
                    try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                }
                final String fl = lrc, fe = err;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fl, fe);
                }});
            }
        });
    }

    /**
     * 按「歌名 + 歌手」搜一首再取词 —— 跨音源兜底时用。
     * 不知道对方的平台 id，只能先搜。
     */
    public static void fetchLyricsBySearch(final Context ctx, final Song probe,
                                           final Online.LyricsCallback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                String lrc = null;
                try {
                    String kw = java.net.URLEncoder.encode(
                            probe.title + " " + (probe.artist == null ? "" : probe.artist), "UTF-8");
                    String body = get("http://www.kuwo.cn/search/searchMusicBykeyWord"
                            + "?vipver=1&client=kt&ft=music&cluster=0&strategy=2012&encoding=utf8"
                            + "&rformat=json&mobi=1&issubtitle=1&show_copyright_off=1"
                            + "&pn=0&rn=1&all=" + kw, "http://www.kuwo.cn/");
                    java.util.regex.Matcher m = RID_PATTERN.matcher(body);
                    if (m.find()) {
                        Song found = new Song();
                        found.online = true;
                        found.source = Song.SOURCE_KUWO;
                        found.neteaseId = Long.parseLong(m.group(1));
                        // 复用主逻辑（含多域名轮换）
                        final Object lock = new Object();
                        final String[] box = new String[1];
                        final boolean[] done = new boolean[1];
                        fetchLyrics(ctx, found, new Online.LyricsCallback() {
                            @Override public void onResult(String l, String e) {
                                synchronized (lock) { box[0] = l; done[0] = true; lock.notifyAll(); }
                            }
                        });
                        synchronized (lock) {
                            long dl = System.currentTimeMillis() + 15000;
                            while (!done[0] && System.currentTimeMillis() < dl) {
                                try { lock.wait(400); } catch (InterruptedException ignored) {}
                            }
                        }
                        lrc = box[0];
                    }
                } catch (Throwable ignored) {}
                final String fl = lrc;
                post(new Runnable() { @Override public void run() {
                    if (cb != null) cb.onResult(fl, null);
                }});
            }
        });
    }

    /** 秒 -> [mm:ss.xx] */
    private static String fmtTime(String sec) {
        double v = 0;
        try { v = Double.parseDouble(sec); } catch (Throwable ignored) {}
        int m = (int) (v / 60);
        double s = v - m * 60;
        return String.format(java.util.Locale.US, "%02d:%05.2f", m, s);
    }

    // ── 解析 ────────────────────────────────────────────────────────────────
    private static Song toSong(Object o) {
        Song s = new Song();
        s.online = true;
        s.source = Song.SOURCE_KUWO;

        String rid = Json.str(o, "MUSICRID");            // "MUSIC_228908"
        if (rid != null && rid.startsWith("MUSIC_")) {
            try { s.neteaseId = Long.parseLong(rid.substring(6)); } catch (Throwable ignored) {}
        }
        if (s.neteaseId == 0) s.neteaseId = Json.lng(o, "DC_TARGETID");
        s.id = -Math.abs(s.neteaseId == 0 ? System.nanoTime() : s.neteaseId);

        s.title = nz(Json.str(o, "SONGNAME"), "未知曲目");
        s.artist = nz(Json.str(o, "ARTIST"), "未知歌手");
        s.album = nz(Json.str(o, "ALBUM"), "");

        // DURATION 单位是秒
        long sec = Json.lng(o, "DURATION");
        s.durationMs = sec > 0 ? sec * 1000L : 0;

        s.coverUrl = nz(Json.str(o, "hts_MVPIC"), "");
        if (s.coverUrl.length() == 0) s.coverUrl = nz(Json.str(o, "web_albumpic_short"), "");

        s.uri = "kuwo:" + s.neteaseId;
        s.path = s.uri;
        return s;
    }

    private static String nz(String v, String def) { return v == null || v.length() == 0 ? def : v; }

    // ── HTTP ────────────────────────────────────────────────────────────────
    private static String get(String url) throws Exception {
        return get(url, "http://www.kuwo.cn/");
    }

    private static String get(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", referer);
        // 随机国内 IP，绕开地域限制
        c.setRequestProperty("X-Forwarded-For",
                "116." + (20 + RND.nextInt(60)) + "." + RND.nextInt(255) + "." + RND.nextInt(255));
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

    private static void post(Runnable r) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }
}
