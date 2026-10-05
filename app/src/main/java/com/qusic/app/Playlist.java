package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 歌单。
 *
 * <p>数据模型刻意做得很简单：一个歌单就是「名字 + 一串歌」。
 *
 * <p>和 {@link History} 一样，在线歌曲**只存元数据不存播放直链**
 * （直链带时效签名，存下来必然失效），播放时按需重新解析。
 *
 * <p>同一个歌单内不允许重复添加同一首歌；本地曲目被移除后，
 * 歌单里的条目会变成「失效」状态，界面上会标注出来而不是直接崩掉。
 */
public final class Playlist {

    private static final String PREFS = "qusic_playlists";
    private static final String KEY = "lists_v1";

    /** 一个歌单 */
    public static class Item {
        public long id;
        public String name = "";
        public long created;
        public final List<Song> songs = new ArrayList<>();

        public int size() { return songs.size(); }
    }

    private static final List<Item> sLists = new ArrayList<>();
    private static final Object LOCK = new Object();
    private static SharedPreferences sPrefs;
    private static boolean sLoaded;

    private Playlist() {}

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
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item();
                    it.id = o.optLong("id");
                    it.name = o.optString("name", "未命名歌单");
                    it.created = o.optLong("created");
                    JSONArray ss = o.optJSONArray("songs");
                    if (ss != null) {
                        for (int j = 0; j < ss.length(); j++) {
                            Song s = songFromJson(ss.getJSONObject(j));
                            if (s != null) it.songs.add(s);
                        }
                    }
                    sLists.add(it);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── 查询 ────────────────────────────────────────────────────────────────
    public static List<Item> all() {
        synchronized (LOCK) { return new ArrayList<>(sLists); }
    }

    public static int count() {
        synchronized (LOCK) { return sLists.size(); }
    }

    public static Item byId(long id) {
        synchronized (LOCK) {
            for (Item it : sLists) if (it.id == id) return it;
            return null;
        }
    }

    // ── 修改 ────────────────────────────────────────────────────────────────
    public static Item create(Context ctx, String name) {
        if (ctx != null) init(ctx);
        Item it = new Item();
        it.id = System.currentTimeMillis();
        it.name = (name == null || name.trim().length() == 0) ? "新建歌单" : name.trim();
        it.created = it.id;
        synchronized (LOCK) { sLists.add(0, it); }
        persist(ctx);
        return it;
    }

    public static void rename(Context ctx, long id, String name) {
        Item it = byId(id);
        if (it == null) return;
        it.name = (name == null || name.trim().length() == 0) ? it.name : name.trim();
        persist(ctx);
    }

    public static void delete(Context ctx, long id) {
        synchronized (LOCK) {
            for (int i = 0; i < sLists.size(); i++) {
                if (sLists.get(i).id == id) { sLists.remove(i); break; }
            }
        }
        persist(ctx);
    }

    /** @return true=添加成功；false=这首歌已经在歌单里了 */
    public static boolean add(Context ctx, long id, Song song) {
        if (song == null) return false;
        Item it = byId(id);
        if (it == null) return false;
        synchronized (LOCK) {
            for (Song s : it.songs) {
                if (same(s, song)) return false;
            }
            it.songs.add(copyOf(song));
        }
        persist(ctx);
        return true;
    }

    public static void remove(Context ctx, long id, Song song) {
        Item it = byId(id);
        if (it == null || song == null) return;
        synchronized (LOCK) {
            for (int i = 0; i < it.songs.size(); i++) {
                if (same(it.songs.get(i), song)) { it.songs.remove(i); break; }
            }
        }
        persist(ctx);
    }

    /** 某个歌单是否已包含这首歌（用于菜单里显示「已在歌单中」） */
    public static boolean contains(long id, Song song) {
        Item it = byId(id);
        if (it == null || song == null) return false;
        for (Song s : it.songs) if (same(s, song)) return true;
        return false;
    }

    private static boolean same(Song a, Song b) {
        if (a.source != b.source) return false;
        if (b.online) return a.neteaseId != 0 && a.neteaseId == b.neteaseId
                && (a.mime == null ? "" : a.mime).equals(b.mime == null ? "" : b.mime);
        return a.id == b.id || (a.uri != null && a.uri.equals(b.uri));
    }

    /** 存副本，避免后续解析出的直链污染歌单数据 */
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
        return c;
    }

    // ── 添加到歌单的界面（曲库页和播放页共用）────────────────────────────
    /**
     * 弹出「添加这首歌到哪个歌单」。
     *
     * @param onChanged 添加完成后回调（用于刷新界面），可为 null
     */
    public static void showAddDialog(final android.app.Activity act, final Song song,
                                     final Runnable onChanged) {
        if (act == null || song == null) return;
        init(act);
        final List<Item> lists = all();
        final List<String> names = new ArrayList<>();
        names.add("＋ 新建歌单…");
        for (Item it : lists) {
            names.add(it.name + (contains(it.id, song) ? "（已添加）" : ""));
        }
        new android.app.AlertDialog.Builder(act)
                .setTitle("添加「" + song.title + "」到歌单")
                .setItems(names.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            askNameAndCreate(act, song, onChanged);
                        } else {
                            Item it = lists.get(which - 1);
                            boolean ok = add(act, it.id, song);
                            android.widget.Toast.makeText(act,
                                    ok ? "已添加到「" + it.name + "」"
                                       : "这首歌已经在「" + it.name + "」里了",
                                    android.widget.Toast.LENGTH_SHORT).show();
                            if (onChanged != null) onChanged.run();
                        }
                    }
                }).show();
    }

    /** 新建歌单并添加这首歌 */
    public static void askNameAndCreate(final android.app.Activity act, final Song song,
                                        final Runnable onChanged) {
        final android.widget.EditText et = new android.widget.EditText(act);
        et.setHint("歌单名字");
        et.setTextSize(15);
        et.setSingleLine(true);
        int pad = (int) (20 * act.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(act);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(et);
        new android.app.AlertDialog.Builder(act)
                .setTitle(song == null ? "新建歌单" : "新建歌单并添加")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Item it = create(act, et.getText().toString());
                        if (song != null) add(act, it.id, song);
                        android.widget.Toast.makeText(act, "已添加到「" + it.name + "」",
                                android.widget.Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    }
                }).show();
    }

    // ── 持久化 ──────────────────────────────────────────────────────────────
    private static void persist(Context ctx) {
        if (ctx != null) init(ctx);
        if (sPrefs == null) return;
        try {
            JSONArray arr = new JSONArray();
            synchronized (LOCK) {
                for (Item it : sLists) {
                    JSONObject o = new JSONObject();
                    o.put("id", it.id);
                    o.put("name", it.name);
                    o.put("created", it.created);
                    JSONArray ss = new JSONArray();
                    for (Song s : it.songs) ss.put(songToJson(s));
                    o.put("songs", ss);
                    arr.put(o);
                }
            }
            sPrefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    private static JSONObject songToJson(Song s) throws Exception {
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

    private static Song songFromJson(JSONObject o) {
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
