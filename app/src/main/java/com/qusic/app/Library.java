package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音乐库（导入制）。
 *
 * <p>与「扫描全盘」不同，这里<strong>只显示用户主动导入的歌曲</strong>：
 * 通过系统文件选择器（SAF）一次选多首，导入后把清单持久化，
 * 下次启动直接恢复。不会去翻用户的整个存储。
 *
 * <p>用 SAF 而不是直接读路径，好处是不需要「所有文件访问权限」，
 * 也能拿到持久读权限（takePersistableUriPermission），重启后依然可播。
 *
 * <p>封面三级回退：内嵌封面 → 磁盘缓存 → 主题渐变占位。
 */
public final class Library {

    private static final String PREFS = "qusic_library";
    private static final String KEY_SONGS = "songs_v1";
    private static final String CACHE_DIR = "cover_cache";

    private static final List<Song> sSongs = new ArrayList<>();
    private static final List<Album> sAlbums = new ArrayList<>();
    private static final Object LOCK = new Object();
    private static boolean sLoaded;
    private static long sTotalDuration;

    private static LruCache<String, Bitmap> sMem;
    private static File sDiskDir;
    private static SharedPreferences sPrefs;
    private static ExecutorService sPool;

    public static class Album {
        public String name;
        public String artist;
        public List<Song> songs = new ArrayList<>();
        public Bitmap cover;
        public long durationMs;
    }

    private Library() {}

    public static ExecutorService pool() {
        if (sPool == null) sPool = Executors.newFixedThreadPool(3);
        return sPool;
    }

    private static android.os.Handler ui() {
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    // ── 载入 / 持久化 ───────────────────────────────────────────────────────
    /** 封面缓存格式版本：改了封面来源或缓存规则就 +1，会自动清掉旧缓存 */
    private static final int COVER_CACHE_VERSION = 2;

    public static void init(Context ctx) {
        if (sLoaded) return;
        sLoaded = true;
        sPrefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        purgeStaleCoverCache(ctx);
        String json = sPrefs.getString(KEY_SONGS, null);
        if (json == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            synchronized (LOCK) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Song s = new Song();
                    s.id = o.optLong("id");
                    s.title = o.optString("title", "未知曲目");
                    s.artist = o.optString("artist", "");
                    s.album = o.optString("album", "");
                    s.durationMs = o.optLong("duration");
                    s.uri = o.optString("uri");
                    s.mediaId = o.optLong("mediaId", 0);
                    s.source = o.optInt("source", Song.SOURCE_LOCAL);
                    s.online = s.source != Song.SOURCE_LOCAL;
                    s.streamUrl = o.optString("streamUrl", "");
                    s.coverUrl = o.optString("coverUrl", "");
                    s.neteaseId = o.optLong("platformId", 0);
                    if (s.mediaId == 0) s.mediaId = parseMediaId(s.uri);
                    s.path = s.uri;
                    s.size = o.optLong("size");
                    s.mime = o.optString("mime", "");
                    if (s.id == 0) s.id = Math.abs((s.uri + s.title).hashCode());
                    sSongs.add(s);
                }
            }
        } catch (Throwable ignored) {}
        rebuildAlbums();
    }

    /** 把当前列表写回磁盘（导入 / 删除后调用） */
    public static void persist(Context ctx) {
        try {
            if (sPrefs == null) {
                sPrefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            }
            JSONArray arr = new JSONArray();
            synchronized (LOCK) {
                for (Song s : sSongs) {
                    JSONObject o = new JSONObject();
                    o.put("id", s.id);
                    o.put("title", s.title);
                    o.put("artist", s.artist);
                    o.put("album", s.album);
                    o.put("duration", s.durationMs);
                    o.put("uri", s.uri);
                    o.put("mediaId", s.mediaId);
                    o.put("source", s.source);
                    o.put("streamUrl", s.streamUrl == null ? "" : s.streamUrl);
                    o.put("coverUrl", s.coverUrl == null ? "" : s.coverUrl);
                    o.put("platformId", s.neteaseId);
                    o.put("size", s.size);
                    o.put("mime", s.mime);
                    arr.put(o);
                }
            }
            sPrefs.edit().putString(KEY_SONGS, arr.toString()).apply();
        } catch (Throwable ignored) {}
    }

    // ── 查询 ────────────────────────────────────────────────────────────────
    public static List<Song> songs() { synchronized (LOCK) { return new ArrayList<>(sSongs); } }
    public static List<Album> albums() { synchronized (LOCK) { return new ArrayList<>(sAlbums); } }
    public static int count() { synchronized (LOCK) { return sSongs.size(); } }
    public static boolean isEmpty() { return count() == 0; }
    public static long totalDurationMs() { return sTotalDuration; }

    public static List<Song> search(String q) {
        List<Song> out = new ArrayList<>();
        if (q == null || q.length() == 0) return out;
        synchronized (LOCK) {
            for (Song s : sSongs) if (s.matches(q)) out.add(s);
        }
        return out;
    }

    public static Song byId(long id) {
        synchronized (LOCK) { for (Song s : sSongs) if (s.id == id) return s; }
        return null;
    }

    public static int indexOf(long id) {
        synchronized (LOCK) {
            for (int i = 0; i < sSongs.size(); i++) if (sSongs.get(i).id == id) return i;
        }
        return -1;
    }

    public static boolean hasUri(String uri) {
        if (uri == null) return false;
        synchronized (LOCK) {
            for (Song s : sSongs) if (uri.equals(s.uri)) return true;
        }
        return false;
    }

    // ── 导入 ────────────────────────────────────────────────────────────────
    public interface ImportCallback {
        /** @param added 新增数量  @param skipped 重复/失败而跳过的数量 */
        void onDone(int added, int skipped, List<Song> addedSongs);
    }

    /**
     * 导入若干 Uri（来自 SAF 文件选择器）。
     * 后台线程读元数据与时长，完成后回主线程。
     */
    public static void importUris(final Context ctx, final List<Uri> uris, final ImportCallback cb) {
        if (uris == null || uris.isEmpty()) {
            if (cb != null) cb.onDone(0, 0, new ArrayList<Song>());
            return;
        }
        pool().execute(new Runnable() {
            @Override public void run() {
                // 先拿持久读权限，保证下次启动还能播
                for (Uri u : uris) {
                    try {
                        ctx.getContentResolver().takePersistableUriPermission(
                                u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) {}
                }
                int added = 0, skipped = 0;
                final List<Song> addedSongs = new ArrayList<>();
                for (Uri u : uris) {
                    try {
                        String key = u.toString();
                        if (hasUri(key)) { skipped++; continue; }
                        Song s = readMeta(ctx, u);
                        if (s == null) { skipped++; continue; }
                        s.uri = key;
                        s.path = key;
                        synchronized (LOCK) { sSongs.add(s); }
                        addedSongs.add(s);
                        added++;
                    } catch (Throwable t) {
                        skipped++;
                    }
                }
                if (added > 0) {
                    sortSongs();
                    rebuildAlbums();
                    persist(ctx);
                }
                final int fa = added, fs = skipped;
                if (cb != null) {
                    ui().post(new Runnable() {
                        @Override public void run() { cb.onDone(fa, fs, addedSongs); }
                    });
                }
            }
        });
    }

    /** 从 Uri 读取一首歌的元数据 */
    private static Song readMeta(Context ctx, Uri u) {
        String display = null;
        long size = 0;
        try {
            Cursor c = ctx.getContentResolver().query(u, null, null, null, null);
            if (c != null) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (c.moveToFirst()) {
                    if (ni >= 0) display = c.getString(ni);
                    if (si >= 0) size = c.getLong(si);
                }
                c.close();
            }
        } catch (Throwable ignored) {}

        Song s = new Song();
        s.uri = u.toString();
        s.path = s.uri;
        s.size = size;
        try { s.mime = ctx.getContentResolver().getType(u); } catch (Throwable ignored) {}
        if (s.mime == null) s.mime = "";
        s.id = Math.abs((s.uri + "/" + (display == null ? "" : display)).hashCode());
        s.mediaId = parseMediaId(s.uri);

        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, u);
            s.title = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE));
            s.artist = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST));
            s.album = clean(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM));
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                try { s.durationMs = Long.parseLong(d.trim()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }

        if (s.title == null || s.title.length() == 0) {
            s.title = display != null ? stripExt(display) : "未知曲目";
        }
        if (s.artist == null) s.artist = "";
        if (s.album == null) s.album = "";
        return s;
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }

    /**
     * 从 SAF 的 document URI 里解析 MediaStore 音频 id。
     * 形如 {@code content://.../document/audio%3A96966} → 96966。
     */
    public static long parseMediaId(String uriStr) {
        if (uriStr == null) return 0;
        try {
            if (!uriStr.startsWith("content://")) return 0;
            String docId = android.provider.DocumentsContract.getDocumentId(
                    android.net.Uri.parse(uriStr));
            if (docId == null) return 0;
            int colon = docId.indexOf(':');
            String kind = colon > 0 ? docId.substring(0, colon) : "";
            String num = colon > 0 ? docId.substring(colon + 1) : docId;
            if (!"audio".equals(kind) && !"video".equals(kind) && colon > 0) return 0;
            return Long.parseLong(num);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** MediaStore 里的播放地址（只要有 READ_MEDIA_AUDIO 就能读，不依赖 SAF 授权） */
    public static String mediaStoreUri(Song s) {
        if (s == null || s.mediaId == 0) return null;
        return android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, s.mediaId).toString();
    }

    /**
     * 给出一组「按优先级排列」的播放地址。
     * 在线曲目 → 直链；本地曲目 → SAF URI，失败再退回 MediaStore URI。
     */
    public static java.util.List<String> candidates(Context ctx, Song s) {
        java.util.List<String> out = new java.util.ArrayList<>(3);
        if (s == null) return out;
        if (s.online && s.streamUrl != null && s.streamUrl.length() > 0) {
            out.add(s.streamUrl);
            return out;
        }
        if (s.uri != null && s.uri.length() > 0) out.add(s.uri);
        String ms = mediaStoreUri(s);
        if (ms != null && !ms.equals(s.uri)) out.add(ms);
        if (s.path != null && s.path.length() > 0 && !out.contains(s.path)) out.add(s.path);
        return out;
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    public static void remove(Context ctx, Song s) {
        if (s == null) return;
        synchronized (LOCK) { sSongs.remove(s); }
        sortSongs();
        rebuildAlbums();
        persist(ctx);
    }

    public static void clear(Context ctx) {
        synchronized (LOCK) { sSongs.clear(); }
        rebuildAlbums();
        sTotalDuration = 0;
        persist(ctx);
        clearCache(ctx);
    }

    private static void sortSongs() {
        synchronized (LOCK) {
            Collections.sort(sSongs, new Comparator<Song>() {
                @Override public int compare(Song a, Song b) {
                    int c = a.title.compareToIgnoreCase(b.title);
                    return c != 0 ? c : a.artist.compareToIgnoreCase(b.artist);
                }
            });
        }
    }

    private static void rebuildAlbums() {
        java.util.LinkedHashMap<String, Album> map = new java.util.LinkedHashMap<>();
        long total = 0;
        synchronized (LOCK) {
            for (Song s : sSongs) {
                total += s.durationMs;
                String key = (s.album == null || s.album.length() == 0 ? "未知专辑" : s.album)
                        + "\u0000" + (s.artist == null ? "" : s.artist);
                Album a = map.get(key);
                if (a == null) {
                    a = new Album();
                    a.name = (s.album == null || s.album.length() == 0) ? "未知专辑" : s.album;
                    a.artist = (s.artist == null || s.artist.length() == 0) ? "未知歌手" : s.artist;
                    map.put(key, a);
                }
                a.songs.add(s);
                a.durationMs += s.durationMs;
            }
        }
        List<Album> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<Album>() {
            @Override public int compare(Album a, Album b) { return a.name.compareToIgnoreCase(b.name); }
        });
        synchronized (LOCK) {
            sAlbums.clear();
            sAlbums.addAll(out);
        }
        sTotalDuration = total;
    }

    // ── 封面 ────────────────────────────────────────────────────────────────
    private static LruCache<String, Bitmap> mem() {
        if (sMem == null) {
            int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
            sMem = new LruCache<String, Bitmap>(Math.min(maxKb, 24 * 1024)) {
                @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
            };
        }
        return sMem;
    }

    /** 取封面（可能耗时，请勿在主线程调用） */
    public static Bitmap coverSync(Context ctx, Song s, int reqSize) {
        if (s == null) return null;
        if (s.cover != null && !s.cover.isRecycled()) return s.cover;

        // 缓存 key 里带上封面 URL 的指纹。
        // 只按 id 缓存的话，一旦封面源变了（比如酷我那次把 MV 截图换成专辑封面），
        // 老歌会一直显示磁盘上那份旧图，永远不刷新。
        String key = s.id + ":" + reqSize + ":" + urlTag(s.coverUrl);
        Bitmap cached = mem().get(key);
        if (cached != null && !cached.isRecycled()) { s.cover = cached; return cached; }

        File disk = diskFile(ctx, key);
        if (disk != null && disk.exists() && disk.length() > 0) {
            try {
                Bitmap bm = BitmapFactory.decodeFile(disk.getAbsolutePath());
                if (bm != null) { s.cover = bm; mem().put(key, bm); return bm; }
            } catch (Throwable ignored) {}
        }

        Bitmap bm = null;
        // 在线封面：下载后缓存到磁盘
        if (bm == null && s.coverUrl != null && s.coverUrl.length() > 0) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                        new java.net.URL(s.coverUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                java.io.InputStream in = conn.getInputStream();
                bm = decodeScaled(in, reqSize);
                in.close();
            } catch (Throwable ignored) {}
        }
        if (bm == null && s.uri != null && s.uri.length() > 0) {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(ctx, Uri.parse(s.uri));
                byte[] pic = mmr.getEmbeddedPicture();
                if (pic != null && pic.length > 0) {
                    bm = decodeScaled(new java.io.ByteArrayInputStream(pic), reqSize);
                }
            } catch (Throwable ignored) {
            } finally {
                try { mmr.release(); } catch (Throwable ignored) {}
            }
        }

        if (bm != null) {
            s.cover = bm;
            mem().put(key, bm);
            saveDisk(disk, bm);
        }
        return bm;
    }

    /** 异步取封面，成功后回主线程回调 */
    public static void loadCoverAsync(final Context ctx, final Song s, final int size,
                                     final Runnable onDone) {
        if (s == null) return;
        if (s.cover != null && !s.cover.isRecycled()) { if (onDone != null) onDone.run(); return; }
        if (s.loading) return;
        s.loading = true;
        pool().execute(new Runnable() {
            @Override public void run() {
                final Bitmap b = coverSync(ctx, s, size);
                s.loading = false;
                if (b != null && onDone != null) ui().post(onDone);
            }
        });
    }

    private static void saveDisk(File disk, Bitmap bm) {
        if (disk == null) return;
        try {
            java.io.FileOutputStream fo = new java.io.FileOutputStream(disk);
            bm.compress(Bitmap.CompressFormat.JPEG, 88, fo);
            fo.close();
        } catch (Throwable ignored) {}
    }

    private static Bitmap decodeScaled(InputStream in, int req) {
        if (in == null) return null;
        try {
            byte[] data = readAll(in);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int scale = 1;
            int max = Math.max(o.outWidth, o.outHeight);
            while (max / scale > req * 2) scale *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = scale;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    /** 取封面 URL 的短指纹（FNV-1a，够用且不用引入哈希库） */
    private static String urlTag(String url) {
        if (url == null || url.length() == 0) return "0";
        int h = 0x811c9dc5;
        for (int i = 0; i < url.length(); i++) {
            h ^= url.charAt(i);
            h *= 0x01000193;
        }
        return Integer.toHexString(h);
    }

    /**
     * 缓存规则换代后清一次旧封面。
     *
     * <p>key 变了以后老的 jpg 就再也匹配不上了，留着只是白占空间
     * （而且如果用户此前看的全是 MV 截图，那些文件还挺大）。
     */
    private static void purgeStaleCoverCache(Context ctx) {
        try {
            int seen = sPrefs.getInt("cover_cache_ver", 0);
            if (seen == COVER_CACHE_VERSION) return;
            File dir = new File(ctx.getCacheDir(), CACHE_DIR);
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) { try { f.delete(); } catch (Throwable ignored) {} }
            sPrefs.edit().putInt("cover_cache_ver", COVER_CACHE_VERSION).apply();
        } catch (Throwable ignored) {}
    }

    private static File diskFile(Context ctx, String key) {
        try {
            if (sDiskDir == null) {
                sDiskDir = new File(ctx.getCacheDir(), CACHE_DIR);
                if (!sDiskDir.exists()) sDiskDir.mkdirs();
            }
            return new File(sDiskDir, Integer.toHexString(key.hashCode()) + ".jpg");
        } catch (Throwable t) { return null; }
    }

    /** 封面平均色（播放页自适应背景 / 光晕） */
    public static int averageColor(Bitmap bm) {
        if (bm == null || bm.isRecycled()) return 0;
        int w = Math.min(24, bm.getWidth()), h = Math.min(24, bm.getHeight());
        if (w <= 0 || h <= 0) return 0;
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = bm.getPixel(x * bm.getWidth() / w, y * bm.getHeight() / h);
                r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
            }
        }
        if (n == 0) return 0;
        return 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
    }

    public static void clearCache(Context ctx) {
        mem().evictAll();
        synchronized (LOCK) {
            for (Song s : sSongs) s.cover = null;
            for (Album a : sAlbums) a.cover = null;
        }
        try {
            File d = new File(ctx.getCacheDir(), CACHE_DIR);
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } catch (Throwable ignored) {}
    }
}
