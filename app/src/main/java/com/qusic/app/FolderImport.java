package com.qusic.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * 从**整个文件夹**导入音乐。
 *
 * <h3>为什么需要这个</h3>
 * 之前的导入只能一个个挑文件 —— 音乐都在一个目录里的人，得点几十次。
 * 这个类让用户直接选一个文件夹，然后递归把里面所有音频都收进来。
 *
 * <h3>为什么不用 DocumentFile</h3>
 * {@code androidx.documentfile.DocumentFile} 是最省事的做法，但它是 **AndroidX 库** ——
 * 本项目零第三方依赖，为了一个功能引入 AndroidX 不划算。
 * 直接用系统的 {@link DocumentsContract} 查询即可，功能完全一样。
 */
public final class FolderImport {

    /** 扫描结果 */
    public interface Callback {
        /**
         * @param uris    找到的音频文件
         * @param folders 扫过的子目录数
         * @param capped  是否因为数量上限而提前停止
         */
        void onResult(List<Uri> uris, int folders, boolean capped);
        /** 扫描过程中的进度（每扫完一个目录回调一次），在子线程 */
        void onProgress(int folders, int found);
    }

    /** 上限：防止误选根目录把整个存储扫一遍 */
    private static final int MAX_FILES = 5000;
    private static final int MAX_FOLDERS = 800;

    private static final String[] AUDIO_EXT = {
        ".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus",
        ".wma", ".ape", ".mka", ".mp4", ".3gp", ".amr", ".aiff", ".dsf",
    };

    private FolderImport() {}

    /** 目录选择器的 Intent（SAF） */
    public static Intent pickerIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return i;
    }

    /**
     * 递归扫描整棵目录树。
     *
     * <p>用**广度优先**而不是递归函数 —— 目录层级深的时候递归容易爆栈。
     */
    public static void scan(final Context ctx, final Uri treeUri, final Callback cb) {
        Library.pool().execute(new Runnable() {
            @Override public void run() {
                final List<Uri> out = new ArrayList<>();
                int folders = 0;
                boolean capped = false;

                try {
                    ContentResolver cr = ctx.getContentResolver();

                    // 先要持久权限，否则重启后就访问不了了
                    try {
                        cr.takePersistableUriPermission(treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Throwable ignored) {}

                    String rootId = DocumentsContract.getTreeDocumentId(treeUri);
                    Deque<String> queue = new ArrayDeque<>();
                    queue.add(rootId);

                    while (!queue.isEmpty()) {
                        if (out.size() >= MAX_FILES || folders >= MAX_FOLDERS) {
                            capped = true;
                            break;
                        }
                        String dirId = queue.poll();
                        folders++;

                        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                                treeUri, dirId);
                        Cursor cur = null;
                        try {
                            cur = cr.query(children, new String[]{
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                            }, null, null, null);
                            if (cur == null) continue;

                            while (cur.moveToNext()) {
                                String docId = cur.getString(0);
                                String name  = cur.getString(1);
                                String mime  = cur.getString(2);

                                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                                    queue.add(docId);
                                    continue;
                                }
                                if (isAudio(name, mime)) {
                                    out.add(DocumentsContract.buildDocumentUriUsingTree(
                                            treeUri, docId));
                                    if (out.size() >= MAX_FILES) break;
                                }
                            }
                        } catch (Throwable ignored) {
                            // 单个目录读不了（权限/损坏）就跳过，不影响其他目录
                        } finally {
                            if (cur != null) try { cur.close(); } catch (Throwable ignored) {}
                        }

                        if (cb != null) cb.onProgress(folders, out.size());
                    }
                } catch (Throwable ignored) {}

                final int ff = folders;
                final boolean fc = capped;
                if (cb != null) cb.onResult(out, ff, fc);
            }
        });
    }

    /**
     * 判断是不是音频。
     *
     * <p>不能只看 MIME —— 很多网盘/第三方 provider 对 flac、ape 这类
     * 会返回 {@code application/octet-stream}，甚至 {@code null}。
     * 所以再按扩展名兜一层。
     */
    private static boolean isAudio(String name, String mime) {
        if (mime != null) {
            if (mime.startsWith("audio/")) return true;
            // 有些 provider 把 m4a 报成 video/mp4（因为容器相同）
            if (mime.equals("video/mp4") || mime.equals("video/x-m4v")) {
                return hasAudioExt(name);
            }
            if (!mime.equals("application/octet-stream") && !mime.isEmpty()) return false;
        }
        return hasAudioExt(name);
    }

    private static boolean hasAudioExt(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        for (String e : AUDIO_EXT) {
            if (n.endsWith(e)) return true;
        }
        return false;
    }
}
