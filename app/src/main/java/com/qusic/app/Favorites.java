package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 收藏（我喜欢的歌）。
 *
 * <h3>为什么不用「歌单」实现</h3>
 * 歌单是用户自己建、可以改名和删除的；收藏是**内置的一个固定容器**，
 * 不该出现在「管理歌单」的列表里，也不该被误删。所以单独存一份。
 *
 * <h3>存的是快照，不是引用</h3>
 * 跟 {@link History} 一样存整个 Song 的元数据。原因是：
 * <ul>
 *   <li>在线歌的直链带时效签名，存下来必然失效，只能存元数据下次重解析</li>
 *   <li>本地歌即便 SAF 授权丢了，也还能用 mediaId 走回退链</li>
 * </ul>
 *
 * <h3>顺序</h3>
 * 用 {@link LinkedHashSet} 保证**按收藏的先后顺序**，最新的排在最后。
 * 展示时倒序输出，于是最近收藏的在最上面。
 */
public final class Favorites {

    private static final String PREFS = "qusic_favorites";
    private static final String KEY = "items_v1";
    /** 上限：防止无限增长 */
    private static final int MAX = 2000;

    private static final List<Song> sItems = new ArrayList<>();
    private static final Set<Long> sIds = new LinkedHashSet<>();
    private static final Object LOCK = new Object();
    private static SharedPreferences sPrefs;
    private static boolean sLoaded;

    private Favorites() {}

    public static void init(Context ctx) {
        if (sLoaded) return;
        sPrefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sLoaded = true;
        String json = sPrefs.getString(KEY, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            synchronized (LOCK) {
                for (int i = 0; i < arr.length(); i++) {
                    Song s = fromJson(arr.getJSONObject(i));
                    if (s != null) { sItems.add(s); sIds.add(s.id); }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 是否已收藏 */
    public static boolean has(Song s) {
        if (s == null) return false;
        synchronized (LOCK) { return sIds.contains(s.id); }
    }

    public static int count() {
        synchronized (LOCK) { return sItems.size(); }
    }

    /** 全部收藏，最近收藏的在前 */
    public static List<Song> items() {
        synchronized (LOCK) {
            List<Song> out = new ArrayList<>(sItems.size());
            for (int i = sItems.size() - 1; i >= 0; i--) out.add(sItems.get(i));
            return out;
        }
    }

    /** 切换收藏状态，返回**切换后**是否已收藏 */
    public static boolean toggle(Context ctx, Song s) {
        init(ctx);
        if (s == null) return false;
        boolean now;
        synchronized (LOCK) {
            if (sIds.contains(s.id)) {
                sIds.remove(s.id);
                for (int i = 0; i < sItems.size(); i++) {
                    if (sItems.get(i).id == s.id) { sItems.remove(i); break; }
                }
                now = false;
            } else {
                sIds.add(s.id);
                sItems.add(s);
                while (sItems.size() > MAX) {
                    Song old = sItems.remove(0);
                    sIds.remove(old.id);
                }
                now = true;
            }
        }
        persist();
        return now;
    }

    /** 明确设置（用于「取消收藏」这种不依赖当前状态的场景） */
    public static void remove(Context ctx, Song s) {
        init(ctx);
        if (s == null) return;
        synchronized (LOCK) {
            sIds.remove(s.id);
            for (int i = 0; i < sItems.size(); i++) {
                if (sItems.get(i).id == s.id) { sItems.remove(i); break; }
            }
        }
        persist();
    }

    public static void clear(Context ctx) {
        init(ctx);
        synchronized (LOCK) { sItems.clear(); sIds.clear(); }
        persist();
    }

    private static void persist() {
        if (sPrefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            synchronized (LOCK) { for (Song s : sItems) arr.put(toJson(s)); }
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
        o.put("mime", s.mime == null ? "" : s.mime);
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
