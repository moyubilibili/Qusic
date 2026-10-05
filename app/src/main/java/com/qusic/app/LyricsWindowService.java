package com.qusic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * 桌面歌词悬浮窗服务。
 *
 * <p>用 {@code TYPE_APPLICATION_OVERLAY} 把 {@link LyricsOverlay} 贴到屏幕上，
 * 跟随 {@link PlayerService} 的播放进度滚动歌词。
 *
 * <p>几个关键点：
 * <ul>
 *   <li>必须以前台服务运行 —— 否则 Android 8+ 会在几秒内把它连同悬浮窗一起回收</li>
 *   <li>需要 {@code SYSTEM_ALERT_WINDOW} 权限；没有时引导用户去设置页开</li>
 *   <li>位置记忆在 prefs 里，下次打开回到上次的地方</li>
 *   <li>锁定后不响应拖动，避免误碰（但点击展开控制条仍然有效）</li>
 * </ul>
 */
public class LyricsWindowService extends Service implements PlayerService.Listener {

    private static final String CH_ID = "qusic_lyrics";
    private static final int NOTI_ID = 0x51C2;
    public static final String SP = "qusic_lyrics_win";

    private static boolean sRunning;
    public static boolean isRunning() { return sRunning; }

    private WindowManager wm;
    private LyricsOverlay view;
    private WindowManager.LayoutParams lp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Song song;
    private long lastLineAt;
    private String lastText;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        sRunning = true;
        ensureChannel();

        // 前台服务：让系统别把悬浮窗回收掉
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle("桌面歌词已开启")
                .setContentText("正在显示歌词悬浮窗")
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
                .setPriority(Notification.PRIORITY_LOW);
        startForeground(NOTI_ID, b.build());

        show();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH_ID, "桌面歌词",
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void show() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) { stopSelf(); return; }

        view = new LyricsOverlay(this, new LyricsOverlay.OnAction() {
            @Override public void onAction(int what) {
                PlayerService ps = PlayerService.instance();
                switch (what) {
                    case LyricsOverlay.ACTION_TOGGLE:
                        if (ps != null) ps.toggle();
                        break;
                    case LyricsOverlay.ACTION_LOCK:
                        view.setLocked(!view.locked());
                        savePos();
                        break;
                    case LyricsOverlay.ACTION_CLOSE:
                        stopSelf();
                        break;
                }
            }
        });

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                (int) (64 * getResources().getDisplayMetrics().density),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        android.content.SharedPreferences sp = getSharedPreferences(SP, MODE_PRIVATE);
        lp.x = 0;
        lp.y = sp.getInt("y", (int) (getResources().getDisplayMetrics().heightPixels * 0.72f));
        int m = (int) (12 * getResources().getDisplayMetrics().density);
        lp.width = getResources().getDisplayMetrics().widthPixels - m * 2;
        lp.x = m;

        // 拖动：直接按增量移动窗口
        view.setOnDragBy(new LyricsOverlay.OnDragBy() {
            @Override public void onDragBy(float dx, float dy) {
                lp.x += (int) dx;
                lp.y += (int) dy;
                int sw = getResources().getDisplayMetrics().widthPixels;
                int sh = getResources().getDisplayMetrics().heightPixels;
                int m = (int) (6 * getResources().getDisplayMetrics().density);
                if (lp.x < m) lp.x = m;
                if (lp.x > sw - lp.width - m) lp.x = sw - lp.width - m;
                if (lp.y < m) lp.y = m;
                if (lp.y > sh - lp.height - m) lp.y = sh - lp.height - m;
                try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
            }
        });

        try {
            wm.addView(view, lp);
        } catch (Throwable t) {
            stopSelf();
            return;
        }

        view.setLocked(sp.getBoolean("locked", false));

        PlayerService ps = PlayerService.instance();
        if (ps != null) {
            ps.addListener(this);
            onSongChanged(ps.current(), 0);
            view.setPlaying(ps.isPlaying());
        }

        // 每帧驱动呼吸与过渡
        h.post(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (view == null) return;
            view.tick(0.016f);
            h.postDelayed(this, 16);
        }
    };

    private void savePos() {
        if (lp == null) return;
        getSharedPreferences(SP, MODE_PRIVATE).edit()
                .putInt("x", lp.x)
                .putInt("y", lp.y)
                .putBoolean("locked", view != null && view.locked())
                .apply();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        sRunning = false;
        h.removeCallbacks(frame);
        PlayerService ps = PlayerService.instance();
        if (ps != null) ps.removeListener(this);
        savePos();
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Throwable ignored) {}
            view = null;
        }
        super.onDestroy();
    }

    // ── 跟随播放 ────────────────────────────────────────────────────────────
    @Override public void onSongChanged(Song s, int index) {
        song = s;
        lastText = null;
        if (view != null) {
            view.setLines("", "正在读取歌词…", "");
            if (s != null) Lyrics.preload(this, s, new Runnable() {
                @Override public void run() { refreshLine(); }
            });
        }
    }

    @Override public void onPlayStateChanged(boolean pl) {
        if (view != null) view.setPlaying(pl);
    }

    @Override public void onProgress(long pos, long dur) { refreshLine(); }

    @Override public void onQueueChanged() {}
    @Override public void onRepeatShuffleChanged(int r, int s) {}

    /** 按当前进度取歌词行，变化时才推给 View（避免每帧都触发动画） */
    private void refreshLine() {
        if (view == null || song == null) return;
        String all = Lyrics.textFor(song);
        if (all == null) return;
        long[] times = Lyrics.timesFor(song);
        String[] lines = all.split("\n");
        if (lines.length == 0) return;

        PlayerService ps = PlayerService.instance();
        long pos = ps == null ? 0 : ps.position();

        int cur = -1;
        if (times != null && times.length == lines.length) {
            for (int i = 0; i < times.length; i++) {
                if (pos >= times[i]) cur = i; else break;
            }
        }
        if (cur < 0) cur = 0;
        if (cur >= lines.length) cur = lines.length - 1;

        String now = lines[cur].trim();
        if (now.equals(lastText)) return;
        lastText = now;
        lastLineAt = System.currentTimeMillis();
        view.setLines(cur > 0 ? lines[cur - 1].trim() : "",
                now,
                cur + 1 < lines.length ? lines[cur + 1].trim() : "");
    }

    // ── 便捷开关 ────────────────────────────────────────────────────────────
    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public static void start(Context c) {
        Intent i = new Intent(c, LyricsWindowService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, LyricsWindowService.class));
    }
}
