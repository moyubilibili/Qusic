package com.qusic.app;

import android.content.Context;

import java.util.List;

/**
 * 把「本地歌曲」匹配到「在线音源」上的同一首歌。
 *
 * <h3>为什么需要这个</h3>
 * 本地歌在导出成歌单时只能存歌名、歌手这些**文字信息** —— 音频文件本身
 * 不会（也不该）被上传。别人导入你的歌单后，看到的是歌名，点开却放不了，
 * 因为他手机里没有那个文件。
 *
 * <p>但「你手机里的《晴天》」和「别人手机里的《晴天》」虽然不是同一个文件，
 * **酷我上的《晴天》却是同一首**。所以分享时拿文字信息去在线音源找一遍，
 * 找到就能让接收方直接播放。
 *
 * <h3>怎么判断「是同一首」</h3>
 * 只靠歌名会匹配到翻唱、现场版、DJ 版。所以做三重比对：
 * <ol>
 *   <li><b>歌名</b> —— 归一化后（去空格、标点、大小写、括号后缀）必须高度一致</li>
 *   <li><b>歌手</b> —— 归一化后一致，或一方包含另一方（应对「周杰伦」vs「周杰伦/Jay Chou」）</li>
 *   <li><b>时长</b> —— 差距在 ±8 秒内。**这一条最关键**，它能把翻唱和现场版筛掉</li>
 * </ol>
 *
 * <p>低于阈值就判定「没找到」，宁可让用户自己导入，也不要给一首错歌。
 */
public final class SongMatch {

    /** 匹配结果 */
    public interface Callback {
        /** @param matched 匹配到的在线歌曲；null 表示没找到 */
        void onResult(Song matched, int score);
    }

    /** 低于这个分数就认为不是同一首 */
    private static final int THRESHOLD = 60;

    /**
     * 「一票否决」的版本标记。
     *
     * <p>归一化会把括号内容剥掉，于是「晴天 (KTV版伴奏)」和「晴天」变得一模一样，
     * 时长也一样 —— 结果伴奏被当成了原曲。所以要在**原始标题**上再做一次检查。
     */
    private static final String[] REJECT_MARKERS = {
        "伴奏", "伴唱", "卡拉ok", "ktv", "消音", "纯音乐", "instrumental",
        "铃声", "彩铃", "试听", "片段", "抢先", "预告",
        "翻唱", "cover", "模仿", "山寨", "恶搞",
        "dj", "混音", "remix", "慢摇", "加快版", "放慢版", "升调", "降调",
        "钢琴版", "吉他版", "古筝", "八音盒", "口琴", "尤克里里",
        "数羊", "助眠", "白噪音", "胎教",
    };

    /** 会扣分但不至于否决的（现场版其实能听，只是不如录音室版） */
    private static final String[] SOFT_MARKERS = {
        "live", "现场", "演唱会", "音乐会", "mv", "音乐节", "不插电", "unplugged",
    };

    private SongMatch() {}

    /**
     * 先搜酷我，搜不到再搜网易云。
     */
    public static void match(final Context ctx, final Song local, final Callback cb) {
        if (local == null) { cb.onResult(null, 0); return; }
        final String kw = buildKeyword(local);
        if (kw.length() == 0) { cb.onResult(null, 0); return; }

        Kuwo.search(ctx, kw, new Online.SearchCallback() {
            @Override public void onResult(List<Song> songs, String error) {
                Song best = pick(songs, local);
                if (best != null) { cb.onResult(best, score(best, local)); return; }
                // 酷我没有 → 试网易云
                NetEase.search(ctx, kw, new Online.SearchCallback() {
                    @Override public void onResult(List<Song> s2, String e2) {
                        Song b2 = pick(s2, local);
                        cb.onResult(b2, b2 == null ? 0 : score(b2, local));
                    }
                });
            }
        });
    }

    private static String buildKeyword(Song s) {
        String t = s.title == null ? "" : s.title.trim();
        String a = s.artist == null ? "" : s.artist.trim();
        if (t.length() == 0) return "";
        // 歌手名里常带多个、带斜杠，取第一个
        if (a.length() > 0) {
            int cut = a.length();
            for (String sep : new String[]{"、", "/", ",", "，", "&", ";"}) {
                int i = a.indexOf(sep);
                if (i > 0 && i < cut) cut = i;
            }
            a = a.substring(0, cut).trim();
        }
        return a.length() > 0 ? (t + " " + a) : t;
    }

    /** 从候选里挑分最高的 */
    private static Song pick(List<Song> cands, Song local) {
        if (cands == null || cands.isEmpty()) return null;
        Song best = null;
        int bestScore = 0;
        for (Song c : cands) {
            int sc = score(c, local);
            if (sc > bestScore) { bestScore = sc; best = c; }
        }
        return bestScore >= THRESHOLD ? best : null;
    }

    /**
     * 打分（0~100）。
     */
    private static int score(Song c, Song local) {
        // ① 先看原始标题有没有「一票否决」的标记
        String rc = raw(c.title);
        for (String m : REJECT_MARKERS) {
            if (rc.contains(m)) return 0;
        }

        String ct = norm(c.title), lt = norm(local.title);
        if (ct.length() == 0 || lt.length() == 0) return 0;

        int s = 0;

        // ── 歌名（最多 50 分）──
        if (ct.equals(lt)) {
            s += 50;
        } else if (ct.contains(lt) || lt.contains(ct)) {
            // 一方包含另一方：如「晴天」vs「晴天 (Live)」
            // 包含关系要扣分 —— 很可能就是加长版/现场版
            s += 30;
        } else {
            // 字面不同，用字符重合度兜底
            int ov = overlap(ct, lt);
            if (ov < 60) return 0;      // 差太多，直接判不是
            s += ov / 3;                // 最多 33
        }

        // ── 歌手（最多 25 分）──
        String ca = norm(c.artist), la = norm(local.artist);
        if (ca.length() > 0 && la.length() > 0) {
            if (ca.equals(la)) s += 25;
            else if (ca.contains(la) || la.contains(ca)) s += 18;
            else if (overlap(ca, la) >= 60) s += 10;
        } else {
            s += 12;    // 双方都没歌手信息，不因此扣分
        }

        // ── 时长（最多 25 分）★ 最关键的一条 ──
        long cd = c.durationMs, ld = local.durationMs;
        if (cd > 0 && ld > 0) {
            long diff = Math.abs(cd - ld);
            if (diff <= 3000)       s += 25;   // 3 秒内：几乎肯定同一版本
            else if (diff <= 8000)  s += 15;   // 8 秒内：可以接受
            else if (diff <= 20000) s += 3;    // 20 秒内：可能是不同版本
            else return 0;                     // 差太多，直接否掉
        } else {
            s += 10;    // 缺时长信息，保守给分
        }

        // ④ 现场版等扣分，让录音室版优先
        for (String m : SOFT_MARKERS) {
            if (rc.contains(m)) { s -= 18; break; }
        }

        return Math.max(0, Math.min(100, s));
    }

    /** 原始标题（小写、保留括号内容），用于版本标记检查 */
    private static String raw(String s) {
        return s == null ? "" : s.toLowerCase().replace(" ", "");
    }

    /**
     * 归一化：去掉空格、标点、括号内容、大小写差异。
     * 「晴天 (Live)」→「晴天」
     */
    private static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(' || ch == '（' || ch == '[' || ch == '【') { depth++; continue; }
            if (ch == ')' || ch == '）' || ch == ']' || ch == '】') { if (depth > 0) depth--; continue; }
            if (depth > 0) continue;
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
            // 其余（空格、标点、符号）一律丢弃
        }
        return sb.toString();
    }

    /** 两个字符串的字符重合度（0~100），用较短的串做基准 */
    private static int overlap(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return 0;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        int hit = 0;
        int[] used = new int[longer.length()];
        for (int i = 0; i < shorter.length(); i++) {
            char ch = shorter.charAt(i);
            for (int j = 0; j < longer.length(); j++) {
                if (used[j] == 0 && longer.charAt(j) == ch) { used[j] = 1; hit++; break; }
            }
        }
        return hit * 100 / shorter.length();
    }
}
