package com.qusic.app;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * 全局外观状态：种子色、明暗、动画强度、渲染质量、强调色。
 *
 * <p>所有页面都从这里取 {@link Tokens}，任何一处调用 {@link #apply(String)}
 * 后通过 {@link Listener} 通知界面重建，实现「一键换肤」全应用即时生效。
 */
public final class Theme {

    private static final String PREFS = "qusic_theme";

    public static final String K_SEED = "seed";
    public static final String K_DARK = "dark";
    public static final String K_ANIM = "anim";
    public static final String K_FPS = "fps";
    public static final String K_LYRICS = "lyrics";
    public static final String K_CLOUD = "cloud";
    public static final String K_PSTYLE = "player_style";
    public static final String K_AUTOUPDATE = "auto_update";
    public static final String K_COMMUNITY = "show_community";

    /** 动画速度档位 */
    public static final int ANIM_FAST = 0, ANIM_NORMAL = 1, ANIM_SUBTLE = 2;

    private static Tokens sTokens;
    private static boolean sDark;
    private static int sSeed = Tokens.SEEDS[0];
    private static int sAnim = ANIM_NORMAL;
    private static int sFps = 60;
    private static boolean sLyrics = true;
    private static boolean sCloud = true;
    private static int sPlayerStyle = 0;
    private static boolean sAutoUpdate = false;
    private static boolean sCommunity = true;
    private static Listener sListener;
    private static SharedPreferences sPrefs;

    public interface Listener { void onThemeChanged(Tokens t); }

    private Theme() {}

    public static void init(Context ctx) {
        Icons.init(ctx);   // 图标库需要 Context 才能加载矢量图
        sPrefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sSeed = sPrefs.getInt(K_SEED, Tokens.SEEDS[0]);
        sDark = sPrefs.getBoolean(K_DARK, false);
        sAnim = sPrefs.getInt(K_ANIM, ANIM_NORMAL);
        sFps = sPrefs.getInt(K_FPS, 60);
        sLyrics = sPrefs.getBoolean(K_LYRICS, true);
        sCloud = sPrefs.getBoolean(K_CLOUD, true);
        sPlayerStyle = sPrefs.getInt(K_PSTYLE, 0);
        sAutoUpdate = sPrefs.getBoolean(K_AUTOUPDATE, false);
        sCommunity = sPrefs.getBoolean(K_COMMUNITY, true);
        rebuild();
    }

    private static void rebuild() { sTokens = Tokens.of(sSeed, sDark); }

    public static Tokens t() {
        if (sTokens == null) rebuild();
        return sTokens;
    }

    public static int seed() { return sSeed; }
    public static boolean isDark() { return sDark; }
    public static int animMode() { return sAnim; }
    public static int fps() { return sFps; }
    public static boolean lyricsEnabled() { return sLyrics; }
    public static boolean cloudEnabled() { return sCloud; }
    /** 播放页样式：0=大封面 1=歌词页 2=极简 */
    public static int playerStyle() { return sPlayerStyle; }

    /** 自动更新：发现新版直接后台下载，下完再提示安装 */
    public static boolean autoUpdate() { return sAutoUpdate; }

    /**
     * 是否显示社区。
     * 关掉之后底栏回到 4 项（首页/曲库/搜索/关于），
     * 给只想要纯净播放器的人用。
     */
    public static boolean community() { return sCommunity; }
    public static float animScale() {
        return sAnim == ANIM_FAST ? 0.62f : sAnim == ANIM_SUBTLE ? 1.45f : 1f;
    }

    public static void setListener(Listener l) { sListener = l; }

    public static void setSeed(int color) {
        sSeed = color;
        if (sPrefs != null) sPrefs.edit().putInt(K_SEED, color).apply();
        rebuild(); notifyChanged();
    }

    public static void setDark(boolean dark) {
        sDark = dark;
        if (sPrefs != null) sPrefs.edit().putBoolean(K_DARK, dark).apply();
        rebuild(); notifyChanged();
    }

    public static void toggleDark() { setDark(!sDark); }

    public static void setAnimMode(int m) {
        sAnim = m;
        if (sPrefs != null) sPrefs.edit().putInt(K_ANIM, m).apply();
    }

    public static void setFps(int f) {
        sFps = f;
        if (sPrefs != null) sPrefs.edit().putInt(K_FPS, f).apply();
    }

    public static void setLyrics(boolean b) {
        sLyrics = b;
        if (sPrefs != null) sPrefs.edit().putBoolean(K_LYRICS, b).apply();
    }

    public static void setCommunity(boolean b) {
        sCommunity = b;
        if (sPrefs != null) sPrefs.edit().putBoolean(K_COMMUNITY, b).apply();
    }

    public static void setAutoUpdate(boolean b) {
        sAutoUpdate = b;
        if (sPrefs != null) sPrefs.edit().putBoolean(K_AUTOUPDATE, b).apply();
    }

    public static void setPlayerStyle(int v) {
        sPlayerStyle = v;
        if (sPrefs != null) sPrefs.edit().putInt(K_PSTYLE, v).apply();
    }

    /** 内置流体云胶囊开关（系统级流体云不受此影响） */
    public static void setCloud(boolean b) {
        sCloud = b;
        if (sPrefs != null) sPrefs.edit().putBoolean(K_CLOUD, b).apply();
    }

    private static void notifyChanged() { if (sListener != null) sListener.onThemeChanged(sTokens); }

    /** 主题变化后让 Activity 重建（带淡入淡出，避免生硬闪切） */
    public static void recreateSmooth(final Activity a) {
        if (a == null || a.isFinishing()) return;
        a.recreate();
        a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    // ── MD3 运动曲线 ────────────────────────────────────────────────────────
    /** MD3 emphasized decelerate：进场/展开 */
    public static final TimeInterpolator EMPHASIZED =
            new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    /** MD3 emphasized accelerate：退场/收起 */
    public static final TimeInterpolator EMPHASIZED_ACCEL =
            new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    /** MD3 standard：常规状态切换 */
    public static final TimeInterpolator STANDARD =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** 弹性回弹：用于胶囊/按钮的 Q 弹反馈 */
    public static final TimeInterpolator SPRING =
            new PathInterpolator(0.34f, 1.56f, 0.64f, 1f);
    public static final TimeInterpolator LINEAR = new TimeInterpolator() {
        @Override public float getInterpolation(float x) { return x; }
    };

    /** 依动画档位缩放时长 */
    public static int dur(int ms) { return Math.max(60, (int) (ms * animScale())); }

    /**
     * 取一个与背景对比度足够的「封面主色」，用于播放页光晕 / 自适应强调。
     * 封面过灰时退回主题 primary，避免出现脏灰光晕。
     */
    public static int accentFromCover(int coverAverage) {
        double c = Hct.chromaOf(coverAverage);
        if (c < 12) return t().primary;
        // 提到保证饱和的色度与明度
        double h = Hct.hueOf(coverAverage);
        double cc = Math.max(38, Math.min(c * 1.5, 72));
        return Hct.fromLab(sDark ? 68 : 56, Math.cos(Math.toRadians(h)) * cc,
                Math.sin(Math.toRadians(h)) * cc);
    }

    /** 强调色 → 适配当前主题（保证在表面上可读） */
    public static int readableAccent(int accent) {
        Tokens tk = t();
        int c = Hct.ensureContrast(accent, tk.surface, 3.0);
        return c;
    }

    public static void tintStatusBar(Activity a) {
        Tokens tk = t();
        a.getWindow().setStatusBarColor(Color.TRANSPARENT);
        a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        View d = a.getWindow().getDecorView();
        int flags = d.getSystemUiVisibility();
        if (!sDark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        else flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            a.getWindow().setStatusBarColor(Color.TRANSPARENT);
            a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            a.getWindow().setNavigationBarContrastEnforced(false);
            a.getWindow().setStatusBarContrastEnforced(false);
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            a.getWindow().getAttributes().layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        d.setSystemUiVisibility(flags);
    }
}
