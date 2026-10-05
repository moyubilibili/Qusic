package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放历史（最近播放）。
 *
 * <p>只记录**真正播放过**的歌（本地和在线都记），搜索列表里点开但没播的不算。
 *
 * <p>存储上的一个关键取舍：<b>在线歌曲只存元数据，不存播放直链</b>。
 * 因为音源返回的直链是带时效签名的，存下来下次必然失效；
 * 所以只存「音源 + 平台 id + 歌名/歌手/专辑/封面/时长」，
 * 播放时再按需重新解析直链（这套逻辑 {@link Kuwo} / {@link NetEase} 已经有了）。
 *
 * <p>本地歌曲存 SAF URI 和 MediaStore id，所以即便 SAF 授权丢了，
 * 也能像曲库那样走回退链继续播。
 */
public final class History {

    private static final String PREFS = "qusic_history";
    private static final String KEY = "items_v1";
    /** 上限：超出的按时间淘汰最旧的 */
    private static final int MAX = 300;

    private static final List<Song> sItems = new ArrayList<>();
    private static final Object LOCK = new Object();
    private static SharedPreferences sPrefs;
    private static boolean sLoaded;

    private History() {}

    public static void init(Context ctx) {
        if (sLoaded) return;
        sLoaded = true;
        sPrefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sPrefs.getString(KEY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            synchronized (LOCK) {
                for (int i = 0; i < arr.length(); i++) {
                    Song s = fromJson(arr.getJSONObject(i));
                    if (s != null) sItems.add(s);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 记录一次播放。同一首歌会移到最前，而不是重复一条 */
    public static void record(Context ctx, Song song) {
        if (song == null) return;
        if (ctx != null) init(ctx);
        synchronized (LOCK) {
            // 去重：按「来源 + 平台 id」或「本地 id」判断
            for (int i = 0; i < sItems.size(); i++) {
                if (sameSong(sItems.get(i), song)) {
                    sItems.remove(i);
                    break;
                }
            }
            sItems.add(0, copyOf(song));
            while (sItems.size() > MAX) sItems.remove(sItems.size() - 1);
        }
        persist(ctx);
    }

    private static boolean sameSong(Song a, Song b) {
        if (a.source != b.source) return false;
        if (b.online) return a.neteaseId != 0 && a.neteaseId == b.neteaseId;
        return a.id == b.id || (a.uri != null && a.uri.equals(b.uri));
    }

    /** 存一份副本，避免后续播放改动（比如解析出的直链）污染历史 */
    private static Song copyOf(Song s) {
        Song c = new Song();
        c.id = s.id;
        c.source = s.source;
        c.online = s.online;
        c.neteaseId = s.neteaseId;
        c.mediaId = s.mediaId;
        c.title = s.title;
        c.artist = s.artist;
        c.album = s.album;
        c.durationMs = s.durationMs;
        c.uri = s.uri;
        c.path = s.path;
        c.coverUrl = s.coverUrl;
        c.size = s.size;
        c.mime = s.mime;
        // 注意：不复制 streamUrl —— 直链有时效，下次必须重新解析
        return c;
    }

    public static List<Song> items() {
        synchronized (LOCK) { return new ArrayList<>(sItems); }
    }

    public static int count() {
        synchronized (LOCK) { return sItems.size(); }
    }

    public static void remove(Context ctx, Song s) {
        if (s == null) return;
        synchronized (LOCK) {
            for (int i = 0; i < sItems.size(); i++) {
                if (sameSong(sItems.get(i), s)) { sItems.remove(i); break; }
            }
        }
        persist(ctx);
    }

    public static void clear(Context ctx) {
        synchronized (LOCK) { sItems.clear(); }
        if (ctx != null) {
            init(ctx);
            if (sPrefs != null) sPrefs.edit().remove(KEY).apply();
        }
    }

    // ── 持久化 ──────────────────────────────────────────────────────────────
    private static void persist(Context ctx) {
        if (ctx != null) init(ctx);
        if (sPrefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            synchronized (LOCK) {
                for (Song s : sItems) arr.put(toJson(s));
            }
            sPrefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static JSONObject toJson(Song s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", s.id);
        o.put("source", s.source);
        o.put("online", s.online);
        o.put("pid", s.neteaseId);
        o.put("mediaId", s.mediaId);
        o.put("title", s.title);
        o.put("artist", s.artist);
        o.put("album", s.album);
        o.put("duration", s.durationMs);
        o.put("uri", s.uri == null ? "" : s.uri);
        o.put("coverUrl", s.coverUrl == null ? "" : s.coverUrl);
        o.put("size", s.size);
        o.put("mime", s.mime == null ? "" : s.mime);   // 酷狗用这里存 hash
        o.put("pid2", s.neteaseId);
        return o;
    }

    private static Song fromJson(JSONObject o) {
        try {
            Song s = new Song();
            s.id = o.optLong("id");
            s.source = o.optInt("source", Song.SOURCE_LOCAL);
            s.online = o.optBoolean("online", false);
            s.neteaseId = o.optLong("pid");
            s.mediaId = o.optLong("mediaId");
            s.title = o.optString("title", "未知曲目");
            s.artist = o.optString("artist", "");
            s.album = o.optString("album", "");
            s.durationMs = o.optLong("duration");
            s.uri = o.optString("uri", "");
            s.path = s.uri;
            s.coverUrl = o.optString("coverUrl", "");
            s.size = o.optLong("size");
            s.mime = o.optString("mime", "");
            if (s.id == 0) s.id = Math.abs((s.uri + s.title).hashCode());
            return s;
        } catch (Throwable t) {
            return null;
        }
    }
}
