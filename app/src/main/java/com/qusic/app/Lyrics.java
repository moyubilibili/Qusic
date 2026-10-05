package com.qusic.app;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 歌词。
 *
 * <p><b>重要：所有 I/O 都在后台线程完成，{@link #textFor} 只读缓存。</b>
 * 早期版本在 {@code onDraw} 里直接调用 MediaMetadataRetriever，
 * 等于在主线程绘制期间做磁盘 I/O，会直接卡死并被系统判 ANR（表现为「闪退」）。
 * 现在改为：切歌时用 {@link #preload} 异步加载，绘制时只取内存缓存。
 *
 * <p>歌词来源依次为：
 * <ol>
 *   <li>同名 .lrc 文件（file:// 走文件系统，content:// 走 DocumentsContract）</li>
 *   <li>音频内嵌歌词（ID3 USLT / Vorbis LYRICS）</li>
 *   <li>都没有 → null，界面显示占位文案</li>
 * </ol>
 */
public final class Lyrics {

    private static final Map<Long, Entry> CACHE = new HashMap<>();
    private static final Map<Long, Boolean> LOADING = new HashMap<>();
    private static final Pattern TIME =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");

    private static class Entry {
        String text;
        long[] times;   // 与 text 的行一一对应；无时间轴时为 null
    }

    private Lyrics() {}

    /** 只读缓存：绝不触发 I/O，可安全地在 onDraw 里调用 */
    public static String textFor(Song s) {
        if (s == null) return null;
        Entry e;
        synchronized (CACHE) { e = CACHE.get(s.id); }
        return e == null ? null : e.text;
    }

    /** 与 {@link #textFor} 行数一致的时间戳（毫秒）；无时间轴返回 null */
    public static long[] timesFor(Song s) {
        if (s == null) return null;
        Entry e;
        synchronized (CACHE) { e = CACHE.get(s.id); }
        return e == null ? null : e.times;
    }

    public static boolean isLoaded(Song s) {
        if (s == null) return true;
        synchronized (CACHE) { return CACHE.containsKey(s.id); }
    }

    /**
     * 异步预加载歌词。切歌时调用一次即可。
     * 加载完成后在主线程回调（用于触发重绘）。
     */
    public static void preload(final Context ctx, final Song s, final Runnable onDone) {
        if (s == null || ctx == null) return;
        synchronized (CACHE) {
            if (CACHE.containsKey(s.id)) { if (onDone != null) onDone.run(); return; }
        }
        synchronized (LOADING) {
            if (Boolean.TRUE.equals(LOADING.get(s.id))) return;
            LOADING.put(s.id, Boolean.TRUE);
        }
        final Context app = ctx.getApplicationContext();
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                Entry e = null;
                try {
                    e = load(app, s);
                } catch (Throwable ignored) {
                }
                synchronized (CACHE) { CACHE.put(s.id, e); }
                synchronized (LOADING) { LOADING.remove(s.id); }
                if (onDone != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(onDone);
                }
            }
        });
    }

    // ── 加载（只在后台线程调用） ────────────────────────────────────────────
    private static Entry load(Context ctx, Song s) {
        // 先看磁盘缓存：在线歌词取一次就够了，没必要每次播放都联网
        Entry cached = fromDiskCache(ctx, s);
        if (cached != null) return cached;

        Entry e;
        if (s.online) {
            // 在线曲目：本地文件那三条路都不可能命中，必须去音源取
            e = fromOnline(ctx, s);
        } else {
            e = fromLrcFile(s);
            if (e == null) e = fromSiblingLrc(ctx, s);
            if (e == null) e = fromEmbedded(ctx, s);
        }
        if (e != null) saveDiskCache(ctx, s, e);
        return e;
    }

    /** 在线歌词：酷我 / 网易云 */
    private static Entry fromOnline(Context ctx, Song s) {
        final Object lock = new Object();
        final String[] box = new String[1];
        final boolean[] done = new boolean[1];
        Online.LyricsCallback cb = new Online.LyricsCallback() {
            @Override public void onResult(String lrc, String error) {
                synchronized (lock) {
                    box[0] = lrc;
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        };
        try {
            if (s.source == Song.SOURCE_KUWO) Kuwo.fetchLyrics(ctx, s, cb);
            else if (s.source == Song.SOURCE_KUGOU) Kugou.fetchLyrics(ctx, s, cb);
            else if (s.source == Song.SOURCE_NETEASE) NetEase.fetchLyrics(ctx, s, cb);
            else return null;
        } catch (Throwable t) {
            return null;
        }
        // 等回调（本来就在后台线程，阻塞没问题）
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 12000;
            while (!done[0] && System.currentTimeMillis() < deadline) {
                try { lock.wait(500); } catch (InterruptedException ignored) {}
            }
        }
        if (box[0] == null || box[0].trim().length() == 0) return null;
        return parse(box[0]);
    }

    // ── 歌词磁盘缓存 ────────────────────────────────────────────────────────
    private static File lyricDir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "lyrics");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File lyricFile(Context ctx, Song s) {
        String key = s.source + "_" + s.neteaseId + "_" + Integer.toHexString(
                (s.title + s.artist).hashCode());
        return new File(lyricDir(ctx), key + ".lrc");
    }

    private static Entry fromDiskCache(Context ctx, Song s) {
        if (!s.online) return null;
        try {
            File f = lyricFile(ctx, s);
            if (!f.exists() || f.length() == 0) return null;
            return parse(readAll(new FileInputStream(f)));
        } catch (Throwable t) {
            return null;
        }
    }

    private static void saveDiskCache(Context ctx, Song s, Entry e) {
        if (!s.online || e == null || e.text == null) return;
        try {
            File f = lyricFile(ctx, s);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            // 存原始 LRC 文本（带时间轴），方便下次直接解析
            if (e.times != null && e.times.length == e.text.split("\n").length) {
                String[] lines = e.text.split("\n");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < lines.length; i++) {
                    long t = e.times[i];
                    sb.append(String.format(java.util.Locale.US, "[%02d:%05.2f]%s\n",
                            t / 60000, (t % 60000) / 1000.0, lines[i]));
                }
                fo.write(sb.toString().getBytes("UTF-8"));
            } else {
                fo.write(e.text.getBytes("UTF-8"));
            }
            fo.close();
        } catch (Throwable ignored) {}
    }

    /** 情况一：路径就是文件（file:// 或裸路径） */
    private static Entry fromLrcFile(Song s) {
        String path = filePathOf(s);
        if (path == null) return null;
        int dot = path.lastIndexOf('.');
        if (dot < 0) return null;
        String base = path.substring(0, dot);
        String[] cands = {base + ".lrc", base + ".LRC", base + ".txt"};
        for (String c : cands) {
            File f = new File(c);
            try {
                if (!f.exists() || !f.canRead() || f.length() == 0) continue;
                Entry e = parse(readAll(new FileInputStream(f)));
                if (e != null && e.text != null && e.text.trim().length() > 0) return e;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 情况二：content:// —— 用 DocumentsContract 找同目录下的同名 .lrc */
    private static Entry fromSiblingLrc(Context ctx, Song s) {
        String uriStr = s.uri;
        if (uriStr == null || !uriStr.startsWith("content://")) return null;
        try {
            Uri uri = Uri.parse(uriStr);
            String docId = android.provider.DocumentsContract.getDocumentId(uri);
            if (docId == null) return null;
            int slash = docId.lastIndexOf('/');
            if (slash <= 0) return null;
            String parentId = docId.substring(0, slash);
            String name = docId.substring(slash + 1);
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);

            Uri tree = android.provider.DocumentsContract
                    .buildTreeDocumentUri(uri.getAuthority(), parentId);
            Uri children = android.provider.DocumentsContract
                    .buildChildDocumentsUriUsingTree(tree, parentId);
            android.database.Cursor c = ctx.getContentResolver().query(children,
                    new String[]{
                            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c == null) return null;
            String targetId = null;
            try {
                while (c.moveToNext()) {
                    String dn = c.getString(1);
                    if (dn == null) continue;
                    String lower = dn.toLowerCase();
                    if (!lower.endsWith(".lrc")) continue;
                    String base = dn.substring(0, dn.length() - 4);
                    if (base.equalsIgnoreCase(name)) {
                        targetId = c.getString(0);
                        break;
                    }
                }
            } finally {
                c.close();
            }
            if (targetId == null) return null;
            Uri lrcUri = android.provider.DocumentsContract
                    .buildDocumentUriUsingTree(tree, targetId);
            InputStream in = ctx.getContentResolver().openInputStream(lrcUri);
            if (in == null) return null;
            Entry e = parse(readAll(in));
            in.close();
            if (e != null && e.text != null && e.text.trim().length() > 0) return e;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 情况三：音频内嵌歌词 */
    private static Entry fromEmbedded(Context ctx, Song s) {
        if (s.uri == null || s.uri.length() == 0) return null;
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, Uri.parse(s.uri));
            String lyr = getMeta(mmr, "lyrics");
            if (isEmpty(lyr)) lyr = getMeta(mmr, "LYRICS");
            if (isEmpty(lyr)) lyr = getMeta(mmr, "unsyncedlyrics");
            if (!isEmpty(lyr)) return parse(lyr);
        } catch (Throwable ignored) {
        } finally {
            try { mmr.release(); } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String filePathOf(Song s) {
        if (s.uri != null && s.uri.startsWith("file://")) {
            try { return Uri.parse(s.uri).getPath(); } catch (Throwable ignored) {}
        }
        if (s.path != null && s.path.length() > 0 && !s.path.startsWith("content://")) {
            return s.path;
        }
        return null;
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().length() == 0; }

    /** getMetadata(String) 在部分 API 级别的 android.jar 里没有导出，用反射最稳 */
    private static String getMeta(MediaMetadataRetriever mmr, String key) {
        try {
            java.lang.reflect.Method m =
                    MediaMetadataRetriever.class.getMethod("getMetadata", String.class);
            Object r = m.invoke(mmr, key);
            return r instanceof String ? (String) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        int guard = 0;
        while ((line = br.readLine()) != null && guard++ < 4000) sb.append(line).append('\n');
        br.close();
        if (sb.length() > 0 && sb.charAt(0) == '\uFEFF') sb.deleteCharAt(0);
        return sb.toString();
    }

    /**
     * 解析 LRC。一行可挂多个时间戳（[00:12.00][01:30.00]歌词），会展开并排序。
     */
    private static Entry parse(String raw) {
        if (raw == null) return null;
        String[] lines = raw.replace("\r", "").split("\n");
        java.util.List<Long> ts = new java.util.ArrayList<>();
        java.util.List<String> tx = new java.util.ArrayList<>();
        boolean hasTime = false;

        for (String line : lines) {
            String l = line.trim();
            if (l.length() == 0) continue;
            Matcher m = TIME.matcher(l);
            java.util.List<Long> found = new java.util.ArrayList<>();
            int end = 0;
            while (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                int frac = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    frac = Integer.parseInt(f);
                    if (f.length() == 1) frac *= 100;
                    else if (f.length() == 2) frac *= 10;
                }
                found.add(min * 60000L + sec * 1000L + frac);
                end = m.end();
            }
            String content = l.substring(end).trim();
            if (found.isEmpty()) {
                if (l.startsWith("[")) continue;   // 元信息行
                ts.add(-1L);
                tx.add(l);
            } else {
                hasTime = true;
                for (long tt : found) { ts.add(tt); tx.add(content); }
            }
        }
        if (tx.isEmpty()) return null;

        if (hasTime) {
            java.util.List<Integer> idx = new java.util.ArrayList<>();
            for (int i = 0; i < ts.size(); i++) idx.add(i);
            final java.util.List<Long> key = ts;
            java.util.Collections.sort(idx, new java.util.Comparator<Integer>() {
                @Override public int compare(Integer a, Integer b) {
                    return Long.compare(key.get(a), key.get(b));
                }
            });
            java.util.List<Long> nt = new java.util.ArrayList<>();
            java.util.List<String> nx = new java.util.ArrayList<>();
            for (int i : idx) { nt.add(ts.get(i)); nx.add(tx.get(i)); }
            ts = nt; tx = nx;
        }

        Entry e = new Entry();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tx.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(tx.get(i));
        }
        e.text = sb.toString();
        if (hasTime) {
            e.times = new long[ts.size()];
            for (int i = 0; i < ts.size(); i++) e.times[i] = ts.get(i);
        }
        return e;
    }

    public static void clear() {
        synchronized (CACHE) { CACHE.clear(); }
        synchronized (LOADING) { LOADING.clear(); }
    }
}
