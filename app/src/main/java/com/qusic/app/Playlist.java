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
    private static final int FORMAT_VERSION = 2;

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
        return exportJson(it, null, null);
    }

    /**
     * 导出为 .Qusic。
     *
     * @param matches    本地曲目 → 匹配到的在线曲目。给了就用在线版导出，
     *                   接收方可以直接播放。null 表示不做匹配。
     * @param unmatched  输出参数：没匹配上的本地曲目会被塞进这个 list
     *
     * <p><b>关键点：本地曲目不写 uri。</b>
     * 本地歌的 uri 是 {@code content://...}，只在**本机**有效，
     * 发给别人就是一串无意义的字符串。所以本地曲目一律不写 uri，
     * 改为写 {@code localOnly: true}，让接收方知道「这首得自己导入」。
     */
    public static String exportJson(Item it, java.util.Map<Long, Song> matches,
                                    List<Song> unmatched) {
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
                // 本地曲目：优先用匹配到的在线版
                Song use = s;
                boolean localOnly = false;
                if (!s.online) {
                    Song m = (matches == null) ? null : matches.get(s.id);
                    if (m != null && m.online) {
                        use = m;
                    } else {
                        localOnly = true;
                        if (unmatched != null) unmatched.add(s);
                    }
                }
                JSONObject o = new JSONObject();
                o.put("title", use.title == null ? "" : use.title);
                o.put("artist", use.artist == null ? "" : use.artist);
                o.put("album", use.album == null ? "" : use.album);
                o.put("duration", use.durationMs);
                o.put("source", use.source);
                o.put("online", use.online);
                o.put("platformId", use.neteaseId);
                o.put("hash", use.mime == null ? "" : use.mime);
                // ★ 本地曲目不写 uri（对别人无效）
                o.put("uri", use.online && use.uri != null ? use.uri : "");
                o.put("coverUrl", use.coverUrl == null ? "" : use.coverUrl);
                if (localOnly) {
                    o.put("localOnly", true);   // 接收方据此提示「需要自己导入」
                }
                arr.put(o);
            }
            root.put("songs", arr);
            return root.toString(2);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 这张歌单里有几首是纯本地曲目（别人听不了） */
    public static int countLocal(Item it) {
        if (it == null) return 0;
        int n = 0;
        for (Song s : it.songs) if (!s.online) n++;
        return n;
    }

    /** 导入结果 */
    public static class ImportResult {
        public Item item;
        public String error;
        /** 其中有多少首是「需要自己导入」的本地曲目 */
        public int localOnlyCount;
        /** 其中有多少首可以直接在线播放 */
        public int playableCount;
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
                // 对方分享时没匹配到在线音源的本地曲目
                if (o.optBoolean("localOnly", false)) {
                    s.localOnly = true;
                    r.localOnlyCount++;
                }
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
            // 统计：能直接播的 vs 需要自己导入的
            for (Song s : it.songs) {
                if (s.online || !s.localOnly) r.playableCount++;
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
        new MdDialog.Builder(act)
                .title("添加「" + song.title + "」到歌单")
                .items(names.toArray(new String[0]),
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

    /**
     * 批量添加：一次把多首歌加进某个歌单。
     *
     * <p>「（已添加）」只在**全部**都已经在歌单里时才标 —— 部分已有时标了反而误导。
     */
    public static void showAddDialogFor(final android.app.Activity act,
                                        final List<Song> songs,
                                        final Runnable onChanged) {
        if (act == null || songs == null || songs.isEmpty()) return;
        init(act);
        final List<Item> lists = all();

        boolean allIn = new ArrayList<Item>(lists).size() > 0;
        final List<String> names = new ArrayList<>();
        names.add("＋ 新建歌单…");
        for (Item it : lists) {
            boolean every = true;
            for (Song s : songs) {
                if (!contains(it.id, s)) { every = false; break; }
            }
            names.add(it.name + (every ? "（已全部添加）" : ""));
        }

        new MdDialog.Builder(act)
                .title("把 " + songs.size() + " 首歌添加到歌单")
                .items(names.toArray(new String[0]),
                        new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            askNameAndCreateFor(act, songs, onChanged);
                        } else {
                            Item it = lists.get(which - 1);
                            int ok = 0, dup = 0;
                            for (Song s : songs) {
                                if (add(act, it.id, s)) ok++; else dup++;
                            }
                            StringBuilder m = new StringBuilder();
                            m.append("已添加 ").append(ok).append(" 首到「")
                             .append(it.name).append("」");
                            if (dup > 0) m.append("，").append(dup).append(" 首已存在");
                            android.widget.Toast.makeText(act, m.toString(),
                                    android.widget.Toast.LENGTH_SHORT).show();
                            if (onChanged != null) onChanged.run();
                        }
                    }
                }).show();
    }

    /** 新建歌单并批量添加 */
    public static void askNameAndCreateFor(final android.app.Activity act,
                                           final List<Song> songs, final Runnable onChanged) {
        final MdField et = new MdField(act, "歌单名字");
        et.focus();
        new MdDialog.Builder(act)
                .title("新建歌单并添加 " + (songs == null ? 0 : songs.size()) + " 首")
                .content(et)
                .negative("取消", null)
                .positive("创建", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        Item it = create(act, et.text());
                        int ok = 0;
                        if (songs != null) for (Song s : songs) if (add(act, it.id, s)) ok++;
                        android.widget.Toast.makeText(act,
                                "已添加 " + ok + " 首到「" + it.name + "」",
                                android.widget.Toast.LENGTH_SHORT).show();
                        if (onChanged != null) onChanged.run();
                    }
                }).show();
    }

    /** 新建歌单并添加这首歌 */
    public static void askNameAndCreate(final android.app.Activity act, final Song song,
                                        final Runnable onChanged) {
        // MD3 输入框：系统 EditText 放进 AlertDialog 是 Material 1 的样子，跟界面不搭
        final MdField et = new MdField(act, "歌单名字");
        et.focus();
        new MdDialog.Builder(act)
                .title(song == null ? "新建歌单" : "新建歌单并添加")
                .content(et)
                .negative("取消", null)
                .positive("创建", new MdDialog.OnClick() {
                    @Override public void onClick() {
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
