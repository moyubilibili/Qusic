package com.qusic.app;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * MD3 布局与控件工厂 + 通用交互反馈。
 *
 * <p>全部用代码构建（不依赖 XML 布局），配合自绘控件实现 MD3 的
 * 「形态 + 状态层 + 涟漪 + 触感」四件套。
 */
public final class Ui {

    private Ui() {}

    public static float dp(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static int screenW(Context c) { return c.getResources().getDisplayMetrics().widthPixels; }
    public static int screenH(Context c) { return c.getResources().getDisplayMetrics().heightPixels; }

    /**
     * 状态栏高度。
     * 优先用系统资源；ColorOS 等 ROM 上该资源常读不到，退回 WindowInsets。
     */
    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            int h = c.getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        return systemInset(c, WindowInsets.Type.statusBars());
    }

    /**
     * 底部系统栏（手势条 / 导航栏）高度。
     *
     * <p>注意：ColorOS 上 {@code navigation_bar_height} 资源读不到（返回 0），
     * 所以必须回退到真实的 WindowInsets，否则底栏会被手势条盖住。
     */
    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (id > 0) {
            int h = c.getResources().getDimensionPixelSize(id);
            if (h > 0) return h;
        }
        int fromInsets = systemInset(c, WindowInsets.Type.navigationBars());
        return fromInsets > 0 ? fromInsets : (int) dp(c, 24);
    }

    /** 从当前 Activity 窗口读取系统栏 inset（Activity 不可用时返回 0） */
    private static int systemInset(Context c, int type) {
        android.app.Activity a = activityOf(c);
        if (a == null || a.getWindow() == null || a.getWindow().getDecorView() == null) return 0;
        try {
            WindowInsets wi = a.getWindow().getDecorView().getRootWindowInsets();
            if (wi == null) return 0;
            android.graphics.Insets in = wi.getInsets(type);
            if (type == WindowInsets.Type.statusBars()) return in.top;
            if (type == WindowInsets.Type.navigationBars()) return in.bottom;
            return in.bottom;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 判断是不是「指纹传感器产生的幽灵触摸」。
     *
     * <p>本机（ColorOS/OPPO）的屏下指纹节点 {@code oplus_fp_input} 会注册成
     * TOUCHSCREEN 输入设备，并在某些时刻抛出真实坐标的触摸事件。
     * 这些事件会落在当时屏幕上的任意控件上，造成「按钮自己点了」这种诡异现象。
     * 因此全应用统一把它们丢弃。
     */
    public static boolean isPhantomFingerprintTouch(android.view.MotionEvent e) {
        if (e == null) return false;
        try {
            android.view.InputDevice d = android.view.InputDevice.getDevice(e.getDeviceId());
            if (d == null) return false;
            String n = d.getName();
            if (n == null) return false;
            n = n.toLowerCase(java.util.Locale.US);
            return n.contains("fp_input") || n.contains("fingerprint")
                    || n.contains("fpc") || n.contains("goodix_fp");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从任意 Context 里找出宿主 Activity */
    public static android.app.Activity activityOf(Context c) {
        while (c instanceof android.content.ContextWrapper) {
            if (c instanceof android.app.Activity) return (android.app.Activity) c;
            c = ((android.content.ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    public static Typeface tf() { return Typeface.create("sans-serif", Typeface.NORMAL); }
    public static Typeface tfMed() { return Typeface.create("sans-serif-medium", Typeface.NORMAL); }
    public static Typeface tfBold() { return Typeface.create("sans-serif", Typeface.BOLD); }
    public static Typeface tfBlack() { return Typeface.create("sans-serif-black", Typeface.NORMAL); }

    public static Paint text(int color, float sp, Typeface tf, Context c) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(color);
        p.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                c.getResources().getDisplayMetrics()));
        p.setTypeface(tf);
        return p;
    }

    /** 等宽数字（进度时间不跳动） */
    public static void tabularNums(Paint p) {
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return l;
    }

    public static FrameLayout frame(Context c) {
        FrameLayout f = new FrameLayout(c);
        f.setClipChildren(false);
        return f;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static FrameLayout.LayoutParams flp(int w, int h) {
        return new FrameLayout.LayoutParams(w, h);
    }

    public static int px(Context c, float dp) { return (int) dp(c, dp); }

    // ── 触感反馈 ────────────────────────────────────────────────────────────
    /** 轻点反馈（列表项、按钮） */
    public static void hapticLight(View v) {
        try {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {}
    }

    /** 长按 / 切换反馈（更「重」的一下） */
    public static void hapticStrong(View v) {
        try {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {}
    }

    /** 时钟滴答（拖进度条时用） */
    public static void hapticTick(View v) {
        try {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK,
                    android.view.HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {}
    }

    /** 圆角矩形辅助 */
    public static RectF box(float l, float t, float r, float b) { return new RectF(l, t, r, b); }

    // ── 数字格式化 ──────────────────────────────────────────────────────────
    public static String mmss(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }

    public static String duration(long ms) {
        long m = ms / 60000;
        if (m < 60) return m + " 分钟";
        return (m / 60) + " 小时 " + (m % 60) + " 分";
    }

    public static String fileSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(java.util.Locale.US, "%.2f GB", b / 1073741824.0);
    }

    /**
     * 高亮搜索关键字：返回需要着色的字符区间（找不到返回 null）。
     * 供自绘列表使用，避免用 Spannable 带来的额外分配。
     */
    public static int[] matchRange(String text, String q) {
        if (text == null || q == null || q.length() == 0) return null;
        int i = text.toLowerCase().indexOf(q.toLowerCase());
        return i < 0 ? null : new int[]{i, i + q.length()};
    }
}
