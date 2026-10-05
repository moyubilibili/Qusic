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

    // ── .Qusic 导入 / 导出 ──────────────────────────────────────────────────
    /** 文件后缀。用大写 Q 开头，辨识度高，也方便在社群里认出来 */
    public static final String EXT = ".Qusic";
    public static final String MIME = "application/json";
    private static final int FORMAT_VERSION = 1;

    /**
     * 导出成 .Qusic 文本。
     *
     * <p>格式是自描述的 JSON，带了 {@code format} 和 {@code version} 两个字段，
     * 以后改结构时可以据此做兼容处理，而不是遇到老文件就崩。
     *
     * <p>在线歌曲只导出**元数据**，不导出播放直链 —— 直链带时效签名，
     * 导出去给别人也是一放就失效。
     */
    public static String exportJson(Item it) {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "Qusic");
            root.put("version", FORMAT_VERSION);
            root.put("name", it.name);
            root.put("created", it.created);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("count", it.songs.size());
            JSONArray arr = new JSONArray();
            for (Song s : it.songs) {
                JSONObject o = new JSONObject();
                o.put("title", s.title == null ? "" : s.title);
                o.put("artist", s.artist == null ? "" : s.artist);
                o.put("album", s.album == null ? "" : s.album);
                o.put("duration", s.durationMs);
                o.put("source", s.source);
                o.put("online", s.online);
                o.put("platformId", s.neteaseId);
                o.put("hash", s.mime == null ? "" : s.mime);   // 酷狗用
                o.put("uri", s.uri == null ? "" : s.uri);
                o.put("coverUrl", s.coverUrl == null ? "" : s.coverUrl);
                arr.put(o);
            }
            root.put("songs", arr);
            return root.toString(2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 导入结果 */
    public static class ImportResult {
        public Item item;
        public String error;
        public int total;      // 文件里的歌曲数
        public int matched;    // 能在本机曲库里找到的本地歌曲数
    }

    /**
     * 解析 .Qusic 文本。
     *
     * <p>本地歌曲会尝试按「文件名 / 标题+歌手」匹配本机已有的曲库 ——
     * 因为 SAF 的 URI 是**每台设备各自授权**的，别人导出给你的本地歌曲路径在你这里必然无效。
     * 匹配不上的不丢弃，仍然保留条目并标注出来，让用户自己决定。
     */
    public static ImportResult parseImport(String text, String fallbackName) {
        ImportResult r = new ImportResult();
        try {
            if (text == null || text.trim().length() == 0) {
                r.error = "文件是空的";
                return r;
            }
            JSONObject root = new JSONObject(text.trim());
            String fmt = root.optString("format", "");
            if (!"Qusic".equalsIgnoreCase(fmt)) {
                r.error = "这不是一个 Qusic 歌单文件";
                return r;
            }
            int ver = root.optInt("version", 0);
            if (ver > FORMAT_VERSION) {
                r.error = "这个歌单来自更新的版本（v" + ver + "），请先升级应用";
                return r;
            }
            Item it = new Item();
            it.id = System.currentTimeMillis();
            it.created = root.optLong("created", it.id);
            String nm = root.optString("name", "");
            it.name = (nm == null || nm.trim().length() == 0)
                    ? (fallbackName == null ? "导入的歌单" : fallbackName) : nm.trim();

            JSONArray arr = root.optJSONArray("songs");
            if (arr == null) { r.error = "文件里没有歌曲列表"; return r; }
            r.total = arr.length();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Song s = new Song();
                s.title = o.optString("title", "未知曲目");
                s.artist = o.optString("artist", "");
                s.album = o.optString("album", "");
                s.durationMs = o.optLong("duration");
                s.source = o.optInt("source", Song.SOURCE_LOCAL);
                s.online = o.optBoolean("online", false);
                s.neteaseId = o.optLong("platformId");
                s.mime = o.optString("hash", "");
                s.uri = o.optString("uri", "");
                s.path = s.uri;
                s.coverUrl = o.optString("coverUrl", "");
                s.id = o.optLong("id", 0);
                if (s.id == 0) s.id = s.online ? -Math.abs(s.neteaseId) : Math.abs(s.uri.hashCode());

                if (!s.online) {
                    // 本地歌曲：URI 是别人设备上的授权，在自己这儿多半无效。
                    // 尝试按「标题 + 歌手」在本地曲库里找一首对得上的。
                    Song hit = matchLocal(s);
                    if (hit != null) { s = hit; r.matched++; }
                }
                it.songs.add(s);
            }
            r.item = it;
            return r;
        } catch (Throwable t) {
            r.error = "歌单文件解析失败：" + t.getClass().getSimpleName();
            return r;
        }
    }

    /** 在本机曲库里按标题+歌手找同名歌曲 */
    private static Song matchLocal(Song want) {
        try {
            for (Song s : Library.songs()) {
                if (eq(s.title, want.title) && (eq(s.artist, want.artist) || want.artist.length() == 0)) {
                    return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    /** 建议的导出文件名（去掉不适合当文件名的字符） */
    public static String suggestFileName(Item it) {
        String n = it == null || it.name == null ? "歌单" : it.name;
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.length() == 0) n = "歌单";
        if (n.length() > 40) n = n.substring(0, 40);
        return n + EXT;
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
        // MD3 输入框：系统 EditText 放进 AlertDialog 是 Material 1 的样子，跟界面不搭
        final MdField et = new MdField(act, "歌单名字");
        et.focus();
        new android.app.AlertDialog.Builder(act)
                .setTitle(song == null ? "新建歌单" : "新建歌单并添加")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Item it = create(act, et.text());
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
