package com.qusic.app;

import android.graphics.Bitmap;

/** 一首歌。字段精简，避免大列表滚动时的内存压力（封面单独按需加载）。 */
public class Song {

    public long id;

    /** SAF Uri（导入时拿到持久读权限）。播放与取封面都用它。 */
    public String uri = "";

    /** 在线曲目（网易云）：直链播放地址，由 NetEase 解析后填入 */
    public String streamUrl = "";
    /** 在线封面地址 */
    public String coverUrl = "";
    /** 来源标记：true = 在线曲目 */
    public boolean online;
    /**
     * 来自别人的歌单、且对方没能匹配到在线音源。
     * 意思是：这首得你自己导入才能听。
     */
    public boolean localOnly;

    // ── 展示用字符串的缓存 ──
    // 元数据在导入后就不再变化，可以安全缓存。
    // 加这两个字段是因为 subtitle()/durationText() 出现在自绘列表的 onDraw 里，
    // 每帧每行都调用 —— 不缓存的话光是字符串分配就能把帧率拖垮。
    private String cachedSubtitle;
    private String cachedDuration;

    /** 在线来源 */
    public static final int SOURCE_LOCAL = 0, SOURCE_NETEASE = 1, SOURCE_KUWO = 2,
            SOURCE_KUGOU = 3;
    public int source = SOURCE_LOCAL;
    /** 网易云歌曲 id */
    public long neteaseId;

    /**
     * MediaStore 里的音频 id。
     * 从 SAF 拿到的 document URI 形如
     * {@code content://.../document/audio%3A96966}，其中 96966 就是 MediaStore 的
     * _id。存下来后即使 SAF 的持久授权丢了，也能退回
     * {@code content://media/external/audio/media/96966} 播放。
     */
    public long mediaId;

    public String title = "";
    public String artist = "";
    public String album = "";
    public long durationMs;

    /** 与 uri 等价，仅为兼容旧调用点保留 */
    public String path = "";
    public long size;
    public String mime = "";

    /** 懒加载的封面（可能为 null，界面需有占位） */
    public Bitmap cover;

    /** 封面是否正在异步加载（避免重复提交任务） */
    public boolean loading;

    /** 收藏标记 */
    public boolean favorite;

    public Song() {}

    public String durationText() {
        // 缓存：这两个方法在 onDraw 里每行每帧都被调用，
        // 每次都新建字符串的话每秒会产生上千次分配，直接拖出掉帧。
        if (cachedDuration == null) {
            long s = durationMs / 1000;
            cachedDuration = String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
        }
        return cachedDuration;
    }

    /** 展示用副标题：歌手 · 专辑 */
    public String subtitle() {
        if (cachedSubtitle == null) {
            StringBuilder sb = new StringBuilder();
            if (artist != null && artist.length() > 0 && !"<unknown>".equals(artist)) sb.append(artist);
            else sb.append("未知歌手");
            if (album != null && album.length() > 0 && !"<unknown>".equals(album)) {
                sb.append(" · ").append(album);
            }
            cachedSubtitle = sb.toString();
        }
        return cachedSubtitle;
    }

    /** 元数据变了就清掉缓存（目前只在导入时设置一次，属于防御性代码） */
    public void clearTextCache() { cachedSubtitle = null; cachedDuration = null; }

    public boolean matches(String q) {
        if (q == null || q.length() == 0) return true;
        String s = q.toLowerCase();
        return title.toLowerCase().contains(s)
                || (artist != null && artist.toLowerCase().contains(s))
                || (album != null && album.toLowerCase().contains(s));
    }

    @Override public boolean equals(Object o) {
        return o instanceof Song && ((Song) o).id == id;
    }

    @Override public int hashCode() { return (int) (id ^ (id >>> 32)); }
}
