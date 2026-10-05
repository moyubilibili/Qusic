package com.qusic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 播放服务：MediaPlayer + 播放队列 + 系统媒体通知。
 *
 * <p>用前台服务承载播放，保证锁屏/后台不断音；通知是 MediaStyle，
 * 系统（含 ColorOS 流体云）会把它收进媒体卡片，锁屏可直接控制。
 */
public class PlayerService extends Service {

    public static final String CH_ID = "qusic_playback";
    public static final int NOTI_ID = 1001;

    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    public static final int SHUFFLE_OFF = 0, SHUFFLE_ON = 1;

    // ── 单例状态（界面直接读，避免绑定服务的时序问题） ──────────────────────
    private static final PlayerService sInst_placeholder = null;

    public interface Listener {
        void onSongChanged(Song s, int index);
        void onPlayStateChanged(boolean playing);
        void onProgress(long pos, long dur);
        void onQueueChanged();
        void onRepeatShuffleChanged(int repeat, int shuffle);
    }

    /** Binder 内部类必须是非静态的，才能引用外部服务实例 */
    public class Local extends Binder {
        public PlayerService svc() { return PlayerService.this; }
    }

    private final IBinder binder = new Local();
    private MediaPlayer mp;

    /** 系统媒体会话：锁屏、媒体卡片、ColorOS 流体云都靠它识别「正在播放」 */
    private MediaSession mediaSession;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final List<Song> queue = new ArrayList<>();
    private final List<Song> order = new ArrayList<>();   // 实际播放顺序（含随机）
    private final List<Listener> listeners = new ArrayList<>();
    private final Random rnd = new Random();

    private int index = -1;
    private int repeat = REPEAT_ALL, shuffle = SHUFFLE_OFF;
    private boolean preparing;
    private int pendingSeek = -1;
    private boolean userPaused;
    /** 连续播放失败次数：防止全部放不了时无限跳歌（表现是一直抽搐停不下来） */
    private int consecutiveErrors;
    private int lastErrorCode = -1;
    private static final int MAX_CONSECUTIVE_ERRORS = 3;
    private SharedPreferences prefs;

    // 供跨页面读取的静态快照
    private static PlayerService sInst;
    public static PlayerService instance() { return sInst; }

    public static List<Song> sQueueSnapshot = new ArrayList<>();
    public static Song sCurrent;
    public static boolean sPlaying;
    public static long sPosition, sDuration;

    @Override public void onCreate() {
        super.onCreate();
        sInst = this;
        prefs = getSharedPreferences("qusic_state", Context.MODE_PRIVATE);
        repeat = prefs.getInt("repeat", REPEAT_ALL);
        shuffle = prefs.getInt("shuffle", SHUFFLE_OFF);
        createChannel();
        initMediaSession();
        mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build());
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override public void onPrepared(MediaPlayer m) {
                preparing = false;
                consecutiveErrors = 0;   // 播成功就重置连错计数
                if (pendingSeek >= 0) { m.seekTo(pendingSeek); pendingSeek = -1; }
                m.start();
                sPlaying = true;
                if (userPaused) { m.pause(); sPlaying = false; }
                notifyPlay();
                tick();
                pushNotification();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override public void onCompletion(MediaPlayer m) { onTrackEnd(); }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override public boolean onError(MediaPlayer m, int what, int extra) {
                preparing = false;
                onSongFailed(what);
                return true;
            }
        });
    }

    @Override public IBinder onBind(Intent i) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // 关键：只要是通过 startForegroundService() 拉起来的，必须在 5 秒内
        // 调用 startForeground()，否则系统会抛
        // "Context.startForegroundService() did not then call Service.startForeground()"
        // 并 ANR。这里先无条件起一个前台通知占位，播放后再换成媒体通知。
        ensureForeground();

        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if ("com.qusic.PLAY".equals(a)) { if (!isPlaying()) toggle(); }
            else if ("com.qusic.PAUSE".equals(a)) { if (isPlaying()) toggle();
                try { stopForeground(false); } catch (Throwable ignored) {} }
            else if ("com.qusic.NEXT".equals(a)) next(true);
            else if ("com.qusic.PREV".equals(a)) prev();
            else if ("com.qusic.TOGGLE".equals(a)) toggle();
        }
        return START_STICKY;
    }

    private boolean foregroundStarted;

    /** 起一个最小化的前台通知，满足系统对前台服务的时限要求 */
    private void ensureForeground() {
        if (foregroundStarted) return;
        try {
            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
            b.setContentTitle("Qusic");
            b.setContentText("准备播放");
            b.setSmallIcon(android.R.drawable.ic_media_play);
            b.setOngoing(true);
            b.setOnlyAlertOnce(true);
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            startForeground(NOTI_ID, b.build());
            foregroundStarted = true;
        } catch (Throwable ignored) {}
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CH_ID, "播放控制",
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Qusic 音乐播放控制");
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    // ── 队列操作 ────────────────────────────────────────────────────────────
    public void playList(List<Song> list, int start) {
        if (list == null || list.isEmpty()) return;
        queue.clear();
        queue.addAll(list);
        sQueueSnapshot = new ArrayList<>(queue);
        rebuildOrder(start);
        playIndex(orderIndexFor(start));
    }

    public void playSong(Song s) {
        List<Song> all = Library.songs();
        int i = Library.indexOf(s.id);
        if (i >= 0) playList(all, i);
        else { List<Song> l = new ArrayList<>(); l.add(s); playList(l, 0); }
    }

    private void rebuildOrder(int startSong) {
        order.clear();
        order.addAll(queue);
        if (shuffle == SHUFFLE_ON) {
            Song cur = (startSong >= 0 && startSong < queue.size()) ? queue.get(startSong) : null;
            Collections.shuffle(order, rnd);
            if (cur != null) {
                int ci = indexOfSong(order, cur.id);
                if (ci > 0) { Collections.swap(order, 0, ci); }
            }
        }
    }

    private static int indexOfSong(List<Song> l, long id) {
        for (int i = 0; i < l.size(); i++) if (l.get(i).id == id) return i;
        return -1;
    }

    private int orderIndexFor(int queueIdx) {
        if (queueIdx < 0 || queueIdx >= queue.size()) return 0;
        long id = queue.get(queueIdx).id;
        int i = indexOfSong(order, id);
        return i >= 0 ? i : 0;
    }

    public void playIndex(int i) {
        if (order.isEmpty()) return;
        if (i < 0) i = 0;
        if (i >= order.size()) i = order.size() - 1;
        index = i;
        Song s = order.get(i);
        sCurrent = s;
        sDuration = s.durationMs;
        sPosition = 0;
        preparing = true;
        userPaused = false;
        try {
            mp.reset();
            // 依次尝试候选地址：
            //   在线曲目 → 直链
            //   本地曲目 → SAF URI，若持久授权丢失则退回 MediaStore URI
            // 这样即使某一条读不了，也不会直接判定「放不了」。
            java.util.List<String> cands = Library.candidates(this, s);
            boolean ok = false;
            Throwable lastErr = null;
            for (String cand : cands) {
                try {
                    if (cand.startsWith("content://") || cand.startsWith("file://")) {
                        mp.setDataSource(this, android.net.Uri.parse(cand));
                    } else {
                        mp.setDataSource(cand);
                    }
                    ok = true;
                    break;
                } catch (Throwable t) {
                    lastErr = t;
                }
            }
            if (!ok) {
                throw new IllegalStateException("没有可用的播放地址"
                        + (lastErr != null ? "：" + lastErr.getClass().getSimpleName() : ""));
            }
            mp.prepareAsync();
        } catch (Throwable t) {
            preparing = false;
            onSongFailed(-1);
            return;
        }
        notifySong();
        pushNotification();
    }

    /**
     * 单曲播放失败。
     *
     * <p>连错超过阈值就**彻底停下**并提示，而不是无限跳下一首 ——
     * 否则遇到「整页都是 VIP / 断网」时会一直抽搐着换歌，用户还停不下来。
     */
    private void onSongFailed(int what) {
        lastErrorCode = what;
        consecutiveErrors++;
        if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
            sPlaying = false;
            h.removeCallbacks(ticker);
            try { if (mp != null) mp.reset(); } catch (Throwable ignored) {}
            toast("这几首都放不了（" + describeError(lastErrorCode) + "），先停一下");
            notifyPlay();
            return;
        }
        toast("这首放不了，跳过（" + describeError(what) + "）");
        h.postDelayed(new Runnable() { @Override public void run() { next(true); } }, 350);
    }

    /**
     * 把 MediaPlayer 的错误码翻译成人话。
     *
     * <p>负数那些不是公开常量，而是底层的 {@code status_t}
     * （例如 -38 = INVALID_OPERATION），直接甩给用户没有意义。
     */
    private static String describeError(int what) {
        switch (what) {
            case -1:              return "读不到文件";
            case 1:               return "播放器内部错误";
            case 100:             return "媒体服务重启了";
            case -38:             return "播放器状态异常";
            case -1004:           return "文件读不到（权限或已损坏）";
            case -1007:           return "文件损坏";
            case -1010:           return "格式不支持";
            case -110:            return "超时（网络太慢）";
            case Integer.MIN_VALUE: return "未知错误";
            default:              return what < 0 ? "底层错误 " + what : "错误 " + what;
        }
    }

    public void toggle() {
        if (preparing) { userPaused = !userPaused; notifyPlay(); return; }
        try {
            if (mp.isPlaying()) { mp.pause(); sPlaying = false; userPaused = true; }
            else { mp.start(); sPlaying = true; userPaused = false; tick(); }
        } catch (Throwable ignored) {}
        notifyPlay();
        pushNotification();
    }

    public void play() {
        try { if (!mp.isPlaying()) { mp.start(); sPlaying = true; userPaused = false; tick(); } } catch (Throwable ignored) {}
        notifyPlay(); pushNotification();
    }

    public void pause() {
        try { if (mp.isPlaying()) { mp.pause(); sPlaying = false; userPaused = true; } } catch (Throwable ignored) {}
        notifyPlay(); pushNotification();
    }

    public void next(boolean user) {
        if (order.isEmpty()) return;
        if (shuffle == SHUFFLE_OFF && repeat == REPEAT_OFF && index >= order.size() - 1) {
            // 顺序播完即停
            try { mp.pause(); } catch (Throwable ignored) {}
            sPlaying = false; notifyPlay(); return;
        }
        playIndex((index + 1) % order.size());
    }

    public void prev() {
        if (order.isEmpty()) return;
        if (position() > 3200) { seek(0); return; }
        playIndex((index - 1 + order.size()) % order.size());
    }

    private void onTrackEnd() {
        if (repeat == REPEAT_ONE) { seek(0); play(); return; }
        next(false);
    }

    public void seek(int ms) {
        try { mp.seekTo(ms); sPosition = ms; } catch (Throwable ignored) {}
        notifyProgress();
    }

    /**
     * 当前位置。
     *
     * <p>注意：{@code preparing} 期间（prepareAsync 还没回调）调用
     * {@code getCurrentPosition()} 属于非法状态，底层会返回 INVALID_OPERATION(-38)。
     * 所以这里先做状态判断，避免制造无意义的错误。
     */
    public int position() {
        if (preparing) return (int) sPosition;
        try {
            return mp != null ? Math.max(0, mp.getCurrentPosition()) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 时长。同样要避开 preparing 状态（否则 getDuration 会报 -38） */
    public int duration() {
        if (preparing) return (int) sDuration;
        try {
            int d = mp != null ? mp.getDuration() : -1;
            return d > 0 ? d : (int) sDuration;
        } catch (Throwable t) {
            return (int) sDuration;
        }
    }

    public boolean isPlaying() {
        try { return mp != null && mp.isPlaying(); } catch (Throwable t) { return false; }
    }

    public boolean isPreparing() { return preparing; }
    public int repeatMode() { return repeat; }
    public int shuffleMode() { return shuffle; }
    public List<Song> queue() { return new ArrayList<>(order); }
    public Song current() { return sCurrent; }
    public int currentIndex() { return index; }

    public void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        prefs.edit().putInt("repeat", repeat).apply();
        notifyRS(); pushNotification();
    }

    public void toggleShuffle() {
        shuffle = shuffle == SHUFFLE_ON ? SHUFFLE_OFF : SHUFFLE_ON;
        prefs.edit().putInt("shuffle", shuffle).apply();
        Song cur = sCurrent;
        if (cur != null && !queue.isEmpty()) {
            int qi = Library.indexOf(cur.id);
            int qIdx = -1;
            for (int i = 0; i < queue.size(); i++) if (queue.get(i).id == cur.id) { qIdx = i; break; }
            rebuildOrder(qIdx);
            index = indexOfSong(order, cur.id);
            if (index < 0) index = 0;
        }
        notifyRS(); notifyQueue();
    }

    public void addToQueueNext(Song s) {
        if (s == null) return;
        if (index >= 0 && index < order.size()) order.add(index + 1, s);
        else order.add(s);
        notifyQueue();
        toast("已加入下一首播放");
    }

    public void removeFromQueue(int i) {
        if (i < 0 || i >= order.size() || order.size() <= 1) return;
        boolean wasCurrent = i == index;
        order.remove(i);
        if (i < index) index--;
        notifyQueue();
        if (wasCurrent) playIndex(Math.max(0, Math.min(index, order.size() - 1)));
    }

    private void toast(String s) {
        h.post(new Runnable() { @Override public void run() {
            Toast.makeText(PlayerService.this, s, Toast.LENGTH_SHORT).show(); } });
    }

    // ── 进度 ────────────────────────────────────────────────────────────────
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            tick();
        }
    };

    private void tick() {
        h.removeCallbacks(ticker);
        // preparing 期间不轮询：那时既没有有效进度，调用还会产生 -38 噪音
        if (!preparing && isPlaying()) {
            sPosition = position();
            notifyProgress();
            h.postDelayed(ticker, 250);
        }
    }

    // ── 监听 ────────────────────────────────────────────────────────────────
    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void notifySong() {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onSongChanged(sCurrent, index);
    }
    private void notifyPlay() {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onPlayStateChanged(isPlaying());
    }
    private void notifyProgress() {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onProgress(sPosition, duration());
    }
    private void notifyQueue() {
        sQueueSnapshot = new ArrayList<>(order);
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onQueueChanged();
    }
    private void notifyRS() {
        for (int i = listeners.size() - 1; i >= 0; i--) listeners.get(i).onRepeatShuffleChanged(repeat, shuffle);
    }

    // ── 通知（系统媒体卡片 / 锁屏 / 流体云） ────────────────────────────────
    private void pushNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (sCurrent == null) { nm.cancel(NOTI_ID); return; }

            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra("open_player", true);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);

            b.setContentTitle(sCurrent.title);
            b.setContentText(sCurrent.subtitle());
            b.setSmallIcon(android.R.drawable.ic_media_play);
            b.setContentIntent(pi);
            b.setOngoing(isPlaying());
            b.setOnlyAlertOnce(true);
            b.setShowWhen(false);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);

            Bitmap cover = sCurrent.cover;
            if (cover != null && !cover.isRecycled()) b.setLargeIcon(cover);

            b.addAction(mkAction(android.R.drawable.ic_media_previous, "上一首", "com.qusic.PREV", 1));
            b.addAction(isPlaying()
                    ? mkAction(android.R.drawable.ic_media_pause, "暂停", "com.qusic.PAUSE", 2)
                    : mkAction(android.R.drawable.ic_media_play, "播放", "com.qusic.PLAY", 3));
            b.addAction(mkAction(android.R.drawable.ic_media_next, "下一首", "com.qusic.NEXT", 4));

            // 系统媒体样式（锁屏 / 媒体卡片 / 流体云的识别依据）
            Notification.MediaStyle ms = new Notification.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2);
            if (mediaSession != null) ms.setMediaSession(mediaSession.getSessionToken());
            b.setStyle(ms);
            b.setCategory(Notification.CATEGORY_TRANSPORT);

            // Android 16 实况更新（Live Updates）→ ColorOS 流体云
            applyLiveUpdate(b);

            Notification n = b.build();
            startForeground(NOTI_ID, n);
            foregroundStarted = true;
            nm.notify(NOTI_ID, n);
            updateSession();
        } catch (Throwable ignored) {}
    }

    private Notification.Action mkAction(int icon, String title, String action, int req) {
        Intent i = new Intent(this, PlayerService.class);
        i.setAction(action);
        PendingIntent p = PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon), title, p).build();
    }

    // ── 系统媒体会话（android.media.session，平台 API，无需第三方库） ────────
    private void initMediaSession() {
        try {
            mediaSession = new MediaSession(this, "Qusic");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { play(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { next(true); }
                @Override public void onSkipToPrevious() { prev(); }
                @Override public void onSeekTo(long pos) { seek((int) pos); }
                @Override public void onStop() { pause(); }
            });
            mediaSession.setActive(true);
        } catch (Throwable t) {
            mediaSession = null;
        }
    }

    /** 把当前歌曲与播放进度同步给系统（流体云 / 锁屏据此显示） */
    private void updateSession() {
        if (mediaSession == null) return;
        try {
            Song s = sCurrent;
            if (s != null) {
                MediaMetadata.Builder mb = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration());
                if (s.cover != null && !s.cover.isRecycled()) {
                    mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, s.cover);
                }
                mediaSession.setMetadata(mb.build());
            }
            int state = isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                            | PlaybackState.ACTION_STOP)
                    .setState(state, position(), isPlaying() ? 1f : 0f)
                    .build());
        } catch (Throwable ignored) {}
    }

    /**
     * 把通知提升为「实况更新」——Android 16 的 Live Updates，
     * ColorOS 16 的流体云 / 实况窗就是消费这个。
     *
     * <p>编译用的 android.jar 是旧版，没有 {@code Notification.ProgressStyle}，
     * 因此这里全部走反射；设备不支持时静默跳过，不影响普通媒体通知。
     */
    private void applyLiveUpdate(Notification.Builder b) {
        if (Build.VERSION.SDK_INT < 36) return;
        try {
            Class<?> psCls = Class.forName("android.app.Notification$ProgressStyle");
            Object ps = psCls.getDeclaredConstructor().newInstance();

            int dur = duration();
            int pos = position();
            int pct = dur > 0 ? Math.max(0, Math.min(100, (int) ((long) pos * 100 / dur))) : 0;

            // setProgress(int)：0..100
            try {
                psCls.getMethod("setProgress", int.class).invoke(ps, pct);
            } catch (Throwable ignored) {}

            // 让系统按进度自动着色
            try {
                psCls.getMethod("setStyledByProgress", boolean.class)
                        .invoke(ps, Boolean.TRUE);
            } catch (Throwable ignored) {}

            // 起止分段点（流体云的进度条端点）
            try {
                Class<?> ptCls = Class.forName("android.app.Notification$ProgressStyle$Point");
                java.lang.reflect.Constructor<?> ctor = ptCls.getDeclaredConstructor(int.class);
                java.util.List<Object> pts = new java.util.ArrayList<Object>();
                pts.add(ctor.newInstance(0));
                pts.add(ctor.newInstance(100));
                psCls.getMethod("setProgressPoints", java.util.List.class).invoke(ps, pts);
            } catch (Throwable ignored) {}

            b.setStyle((Notification.Style) ps);

            // 请求系统把它当作常驻实况通知（流体云才会收进去）
            try {
                b.getClass().getMethod("setRequestPromotedOngoing", boolean.class)
                        .invoke(b, Boolean.TRUE);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!isPlaying()) { stopForeground(true); stopSelf(); }
    }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { if (mediaSession != null) { mediaSession.release(); mediaSession = null; } }
        catch (Throwable ignored) {}
        try { if (mp != null) { mp.release(); mp = null; } } catch (Throwable ignored) {}
        sInst = null;
        super.onDestroy();
    }
}
