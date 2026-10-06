package com.qusic.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 累计收听时长。
 *
 * <h3>和「曲库总时长」的区别</h3>
 * 曲库总时长是**你拥有多少音乐**（28 首 × 4 分钟 = 112 分钟），
 * 这里是**你实际听了多久** —— 同一个歌单听十遍，曲库总时长不变，这里会涨。
 *
 * <h3>怎么计时</h3>
 * 由 {@link PlayerService} 每秒打一次点，**只在真正播放时累加**：
 * 暂停、缓冲、播完停下都不算。这样数字反映的是真实收听，而不是「打开过多少次」。
 *
 * <h3>落盘策略</h3>
 * 每秒都写 SharedPreferences 是没必要的 I/O 浪费（还费电）。
 * 所以先进内存的 {@code pending}，攒够一分钟或退到后台/暂停时才写一次。
 * 代价是进程被系统杀掉时，最多丢不到一分钟 —— 对这个量级的统计可以接受。
 */
public final class ListenStats {

    private static final String PREFS = "qusic_stats";
    private static final String K_TOTAL = "total_ms";
    private static final String K_TODAY = "today_ms";
    private static final String K_DAY = "today_day";     // 距 1970 的天数
    private static final String K_SONGS = "songs_played";
    private static final String K_SESSIONS = "sessions";

    /** 攒够这么多就落一次盘 */
    private static final long FLUSH_EVERY = 60_000L;

    private static SharedPreferences sPrefs;

    private static long sTotal, sToday;
    private static int sSongs, sSessions;
    private static long sPending;
    private static long sDay;
    private static boolean sLoaded;
    private static boolean sDirty;

    private ListenStats() {}

    public static void init(Context c) {
        if (sLoaded) return;
        sPrefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sTotal = sPrefs.getLong(K_TOTAL, 0);
        sToday = sPrefs.getLong(K_TODAY, 0);
        sSongs = sPrefs.getInt(K_SONGS, 0);
        sSessions = sPrefs.getInt(K_SESSIONS, 0);
        sDay = sPrefs.getLong(K_DAY, today());
        rollDayIfNeeded();
        sLoaded = true;
    }

    private static long today() { return System.currentTimeMillis() / 86400000L; }

    /** 跨天就把「今日」清零（累计总量不动） */
    private static void rollDayIfNeeded() {
        long t = today();
        if (sDay != t) {
            sDay = t;
            sToday = 0;
            sDirty = true;
        }
    }

    /**
     * 打一次点。由 PlayerService 每秒调用一次，**只在播放中**。
     */
    public static void tick(Context c) {
        init(c);
        rollDayIfNeeded();
        sPending += 1000L;
        sToday += 1000L;
        sTotal += 1000L;
        sDirty = true;
        if (sPending >= FLUSH_EVERY) flush(c);
    }

    /** 开始播一首新歌（换歌/从头播才算，暂停恢复不算） */
    public static void countSong(Context c) {
        init(c);
        sSongs++;
        sDirty = true;
    }

    /** 开始一次新的收听（从暂停到播放） */
    public static void countSession(Context c) {
        init(c);
        sSessions++;
        sDirty = true;
    }

    /** 落盘。暂停、退出、攒够一分钟时调用 */
    public static void flush(Context c) {
        init(c);
        if (!sDirty || sPrefs == null) { sPending = 0; return; }
        sPrefs.edit()
                .putLong(K_TOTAL, sTotal)
                .putLong(K_TODAY, sToday)
                .putLong(K_DAY, sDay)
                .putInt(K_SONGS, sSongs)
                .putInt(K_SESSIONS, sSessions)
                .apply();
        sPending = 0;
        sDirty = false;
    }

    public static long totalMs() { return sTotal + sPending; }
    public static long todayMs() { return sToday; }
    public static int songsPlayed() { return sSongs; }
    public static int sessions() { return sSessions; }

    /** 把毫秒说成人话：「3 小时 24 分」「12 分钟」「不到 1 分钟」 */
    public static String human(long ms) {
        long m = ms / 60000;
        if (m < 1) {
            long sec = ms / 1000;
            return sec <= 0 ? "还没开始听" : (sec + " 秒");
        }
        if (m < 60) return m + " 分钟";
        long h = m / 60, mm = m % 60;
        return mm == 0 ? (h + " 小时") : (h + " 小时 " + mm + " 分");
    }

    /** 「今日」也顺便报一下占比，让数字更有感觉 */
    public static String todayShare() {
        long t = totalMs();
        if (t <= 0) return "";
        int pct = (int) Math.round(sToday * 100.0 / t);
        if (pct <= 0) return "";
        return pct + "%";
    }
}
