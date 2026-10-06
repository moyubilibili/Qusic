package com.qusic.app;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Qusic 主界面。
 *
 * <p>布局是「内容层 + 悬浮层」的堆叠结构，让液态玻璃栏能真正浮在内容上：
 * <pre>
 *   ┌─────────────────────────────┐
 *   │  页面容器（滚动/切换）        │  ← 内容层，可被下方玻璃模糊
 *   │                             │
 *   │   ┌───────────────────┐     │
 *   │   │  迷你播放条（玻璃）│     │  ← 悬浮层
 *   │   └───────────────────┘     │
 *   │   ┌───────────────────┐     │
 *   │   │  液态底栏（玻璃）  │     │
 *   │   └───────────────────┘     │
 *   └─────────────────────────────┘
 * </pre>
 *
 * <p>页面切换用「淡入 + 上浮 + 轻微缩放」，配合底栏指示器的弹簧动画。
 */
public class MainActivity extends Activity implements PlayerService.Listener {

    /** 导入音乐的 requestCode */
    public static final int REQ_IMPORT = 2001;
    /** 导出歌单到 .Qusic */
    public static final int REQ_EXPORT_PLAYLIST = 3001;
    /** 从 .Qusic 导入歌单 */
    public static final int REQ_IMPORT_PLAYLIST = 3002;
    public static final int REQ_IMPORT_TREE = 2002;      // 选文件夹（整个目录导入）
    public static final int REQ_PICK_AUDIO_PERM = 4001;
    /** 待导出的歌单（SAF 异步返回，得先记住内容） */
    private long pendingExportId;
    private String pendingExportJson;

    private FrameLayout root;
    private FrameLayout pageHost;
    private LiquidNavBar navBar;
    private MiniPlayerBar miniBar;
    private FluidCloud fluidCloud;

    private HomePage homePage;
    private LibraryPage libraryPage;
    private SearchPage searchPage;
    private CommunityPage communityPage;
    /** 歌单详情：覆盖整屏的独立页（连底栏也盖住） */
    private PlaylistDetailPage playlistDetail;
    /** 社区帖子详情：独立的二级页面 */
    private PostDetailPage postDetail;
    private AboutPage aboutPage;
    private View currentPage;
    private int currentTab = 0;

    private PlayerService player;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            // 服务连上了：让迷你播放条补一次状态同步（它在视图挂载时可能还没服务）
            if (miniBar != null) miniBar.attachToService();
            player = ((PlayerService.Local) b).svc();
            bound = true;
            player.addListener(MainActivity.this);
            onSongChanged(player.current(), player.currentIndex());
            onPlayStateChanged(player.isPlaying());
        }
        @Override public void onServiceDisconnected(ComponentName n) { player = null; bound = false; }
    };

    @Override protected void onCreate(Bundle st) {
        super.onCreate(st);
        Theme.init(this);
        Library.init(this);   // 恢复上次导入的曲库
        History.init(this);   // 恢复播放历史
        Playlist.init(this);  // 恢复歌单
        Community.init(this); // 社区登录状态
        Theme.setListener(new Theme.Listener() {
            @Override public void onThemeChanged(Tokens t) { recreate(); }
        });
        Theme.tintStatusBar(this);
        // 注意：不要用 FLAG_LAYOUT_NO_LIMITS —— 它会让窗口铺到手势条下面，
        // 底栏会被系统导航栏盖住。改用正常的 edge-to-edge + WindowInsets 处理。
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        buildUi();
        startAndBindPlayer();
        requestPermissionsIfNeeded();

        maybeAutoCheckUpdate();

        if (getIntent() != null && getIntent().getBooleanExtra("open_player", false)) {
            root.postDelayed(new Runnable() { @Override public void run() { openPlayer(); } }, 260);
        }
    }

    /** 启动时静默检查更新，每天最多一次，不打扰用户 */
    private void maybeAutoCheckUpdate() {
        final android.content.SharedPreferences sp =
                getSharedPreferences("qusic_state", MODE_PRIVATE);
        long last = sp.getLong("last_update_check", 0);
        if (System.currentTimeMillis() - last < 24L * 3600 * 1000) return;
        root.postDelayed(new Runnable() {
            @Override public void run() {
                checkForUpdates(false);
                sp.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
            }
        }, 1800);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("open_player", false)) openPlayer();
    }

    // ── 构建界面 ────────────────────────────────────────────────────────────
    private void buildUi() {
        Tokens t = Theme.t();
        root = new FrameLayout(this);
        root.setBackgroundColor(t.surface);
        root.setClipChildren(false);

        pageHost = new FrameLayout(this);
        pageHost.setClipChildren(false);
        root.addView(pageHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 页面实例
        homePage = new HomePage(this);
        homePage.setOnOpenPlayer(new Runnable() { @Override public void run() { openPlayer(); } });
        homePage.setOnOpenTab(new HomePage.OnOpenTab() {
            @Override public void open(int tab) { switchTab(tab, true); }
        });
        libraryPage = new LibraryPage(this);
        searchPage = new SearchPage(this);
        // 歌单详情：独立整屏页。加在 root 最后 → 覆盖页面、迷你条和底栏，
        // 这样它才是「另一层」，而不是嵌在曲库页里跟主界面糊在一起。
        playlistDetail = new PlaylistDetailPage(this);
        playlistDetail.setOnClose(new PlaylistDetailPage.OnClose() {
            @Override public void onClose() { closePlaylistDetail(); }
        });

        postDetail = new PostDetailPage(this);
        postDetail.setOnClose(new PostDetailPage.OnClose() {
            @Override public void onClose() { closePostDetail(); }
        });

        communityPage = new CommunityPage(this);
        aboutPage = new AboutPage(this);

        float density = getResources().getDisplayMetrics().density;
        int navH = (int) (78 * density);          // 与 LiquidNavBar.onMeasure 一致
        // 底栏到屏幕底部的距离 = 系统手势条 inset + 一点呼吸空间
        int gestureInset = Ui.navBarHeight(this);
        int bottomPad = gestureInset + (int) (8 * density);

        // 迷你播放条（悬在底栏之上）
        miniBar = new MiniPlayerBar(this);
        miniBar.setOnOpenPlayer(new MiniPlayerBar.OnOpenPlayer() {
            @Override public void open() { openPlayer(); }
        });
        FrameLayout.LayoutParams miniLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        miniLp.gravity = Gravity.BOTTOM;
        miniLp.leftMargin = (int) (6 * density);
        miniLp.rightMargin = (int) (6 * density);
        miniLp.bottomMargin = navH + gestureInset + (int) (6 * density);
        root.addView(miniBar, miniLp);

        // MD3 底栏（要在 miniBar 之上，否则会被盖住）
        navBar = new LiquidNavBar(this, 0);
        navBar.applyCommunity(Theme.community());
        navBar.setOnTabSelected(new LiquidNavBar.OnTabSelected() {
            @Override public void onSelect(int index) {
                switchTab(index, false);
            }
        });
        // MD3 标准：整条贴底、通栏，不留外边距
        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, navH + gestureInset);
        navLp.gravity = Gravity.BOTTOM;
        navLp.leftMargin = 0;
        navLp.rightMargin = 0;
        navLp.bottomMargin = 0;
        navBar.setBottomInset(gestureInset);
        root.addView(navBar, navLp);
        navBar.bringToFront();
        this.navBottomPad = bottomPad;
        this.navHeight = navH;

        // 内置流体云胶囊（浮在最上层，可在「关于」页关闭）
        if (Theme.cloudEnabled()) {
            fluidCloud = new FluidCloud(this);
            fluidCloud.setOnOpenPlayer(new FluidCloud.OnOpenPlayer() {
                @Override public void open() { openPlayer(); }
            });
            fluidCloud.attachTo(root);
        }

        setContentView(root);

        // 等窗口确定后再按真实 inset 校正一次位置（首帧 WindowInsets 可能还没到）
        root.post(new Runnable() {
            @Override public void run() { applyInsets(); }
        });

        switchTab(0, false);
        navBar.playEntrance();
        applyCloudInset(PlayerService.sCurrent != null);
    }

    private int navBottomPad, navHeight, navGestureInset = -1;

    /**
     * 流体云可见时给内容页顶部让位。
     * 否则胶囊会直接压在页面大标题上（之前就是这个毛病）。
     */
    private void applyCloudInset(boolean hasSong) {
        if (pageHost == null) return;
        // 没在播放就彻底不显示胶囊，避免白白盖住页面大标题
        boolean show = Theme.cloudEnabled() && hasSong && fluidCloud != null;
        if (fluidCloud != null) {
            fluidCloud.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        float d = getResources().getDisplayMetrics().density;
        final int target = show ? (int) (62 * d) : 0;
        final int from = pageHost.getPaddingTop();
        if (from == target) return;
        ValueAnimator va = ValueAnimator.ofInt(from, target);
        va.setDuration(Theme.dur(320));
        va.setInterpolator(Theme.EMPHASIZED);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                pageHost.setPadding(0, (Integer) a.getAnimatedValue(), 0, 0);
            }
        });
        va.start();
    }

    /** 依据真实 WindowInsets 校正底栏位置（分辨率/手势条变化时重新调用） */
    private void applyInsets() {
        if (navBar == null) return;
        float density = getResources().getDisplayMetrics().density;
        int gestureInset = Ui.navBarHeight(this);
        int bottomPad = gestureInset + (int) (8 * density);
        if (gestureInset == navGestureInset) return;
        navGestureInset = gestureInset;

        FrameLayout.LayoutParams navLp = (FrameLayout.LayoutParams) navBar.getLayoutParams();
        navLp.bottomMargin = 0;
        navLp.height = navHeight + gestureInset;
        navBar.setBottomInset(gestureInset);
        navBar.setLayoutParams(navLp);

        if (miniBar != null) {
            FrameLayout.LayoutParams miniLp = (FrameLayout.LayoutParams) miniBar.getLayoutParams();
            miniLp.bottomMargin = bottomPad + navHeight - (int) (4 * density);
            miniBar.setLayoutParams(miniLp);
        }
        refreshAllPages();
    }

    /** 页面内容需要给悬浮的底栏让出空间（底栏 + 迷你条 + 手势条） */
    public int contentBottomInset() {
        float d = getResources().getDisplayMetrics().density;
        return Ui.navBarHeight(this) + (int) (8 * d) + (int) (78 * d) + (int) (72 * d);
    }

    /** 防止 navBar.select → onSelect → switchTab → navBar.select 的重入 */
    private boolean switchingTab;

    private void switchTab(int index, boolean animated) {
        currentTab = index;
        View next = null;
        switch (index) {
            case 0: next = homePage.view(); break;
            case 1: next = libraryPage.view(); break;
            case 2: next = searchPage.view(); break;
            case 3: next = Theme.community() ? communityPage.view() : aboutPage.view(); break;
            case 4: next = aboutPage.view(); break;
        }
        if (next == null) return;
        if (index == 3 && Theme.community() && communityPage != null) communityPage.onShown();

        if (navBar != null && !switchingTab) {
            switchingTab = true;
            navBar.select(index, animated);
            switchingTab = false;
        }

        if (currentPage == next) return;

        final View outgoing = currentPage;
        final View incoming = next;
        currentPage = incoming;

        if (!animated && outgoing == null) {
            if (incoming.getParent() == null) pageHost.addView(incoming,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            incoming.setAlpha(1f);
            incoming.setTranslationY(0);
        } else {
            if (incoming.getParent() == null) pageHost.addView(incoming,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            // 进入：从下方浮起 + 淡入
            final float dir = index > (outgoing != null ? tabIndexOf(outgoing) : 0) ? 1f : -1f;
            incoming.setAlpha(0f);
            incoming.setTranslationY(24 * getResources().getDisplayMetrics().density * dir);
            incoming.setScaleX(0.985f);
            incoming.setScaleY(0.985f);
            incoming.animate()
                    .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                    .setDuration(Theme.dur(420))
                    .setInterpolator(Theme.EMPHASIZED)
                    .start();

            if (outgoing != null) {
                outgoing.animate()
                        .alpha(0f)
                        .translationY(-18 * getResources().getDisplayMetrics().density * dir)
                        .setDuration(Theme.dur(240))
                        .setInterpolator(Theme.EMPHASIZED_ACCEL)
                        .withEndAction(new Runnable() {
                            @Override public void run() { pageHost.removeView(outgoing); }
                        }).start();
            }
        }

        // 切页后让流体云重新吸附
    }

    private int tabIndexOf(View v) {
        if (v == homePage.view()) return 0;
        if (v == libraryPage.view()) return 1;
        if (v == searchPage.view()) return 2;
        if (v == aboutPage.view()) return 3;
        return 0;
    }

    public void openPlayer() {
        Intent i = new Intent(this, PlayerActivity.class);
        startActivity(i);
        // 播放页从底部滑上来，主界面同时轻微缩小变暗 —— 形成前后层次
        overridePendingTransition(R.anim.player_enter, R.anim.main_recede);
    }

    // ── 播放服务 ────────────────────────────────────────────────────────────
    private void startAndBindPlayer() {
        Intent i = new Intent(this, PlayerService.class);
        // 只绑定，不用 startForegroundService：前台服务由「开始播放」时自己拉起，
        // 否则进 App 就得在 5 秒内 startForeground，容易被系统判 ANR。
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    /**
     * 申请权限。
     *
     * <p>导入本身走 SAF，不需要读全盘；但 READ_MEDIA_AUDIO 用作**播放回退**：
     * SAF 的持久授权一旦丢失，就靠它读 MediaStore 里的同一首歌。
     * 注意：拿到这个权限也**不会**去扫描用户的音乐库，只是让已导入的歌能继续播。
     */
    private void requestPermissionsIfNeeded() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 101);
            return;
        }
        refreshAllPages();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        // 拒绝通知权限也能正常用，只是没有媒体通知
        refreshAllPages();
    }

    /**
     * 检查更新。
     * @param manual true = 用户主动点的（无论有没有新版都给反馈）
     */
    public void checkForUpdates(final boolean manual) {
        final android.app.Activity self = this;
        if (manual) Toast.makeText(this, "正在检查更新…", Toast.LENGTH_SHORT).show();
        Updater.check(this, new Updater.Callback() {
            @Override public void onResult(Updater.Info info, String error) {
                if (isFinishing()) return;
                if (error != null) {
                    if (manual) Toast.makeText(self, error, Toast.LENGTH_LONG).show();
                    return;
                }
                if (info.hasUpdate) {
                    // 不管是不是自动更新，**都要弹窗** ——
                    // 用户有权知道有新版本、更新了什么。
                    // 区别只是：自动模式下弹窗里会说明「已开始后台下载」，并即刻下载。
                    Updater.showUpdateDialog(self, info, Theme.autoUpdate());
                    if (Theme.autoUpdate() && info.apkUrl.length() > 0) {
                        Updater.downloadAndInstall(self, info);
                    }
                } else if (manual) {
                    Toast.makeText(self, "已是最新版本 " + Updater.localVersionName(self),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    // ── 桌面歌词 ────────────────────────────────────────────────────────────
    public static final int REQ_OVERLAY = 5001;

    /** 开关桌面歌词悬浮窗 */
    public void toggleLyricsWindow() {
        if (LyricsWindowService.isRunning()) {
            LyricsWindowService.stop(this);
            Toast.makeText(this, "桌面歌词已关闭", Toast.LENGTH_SHORT).show();
            refreshAllPages();
            return;
        }
        // 先确认有悬浮窗权限；没有就引导去设置页
        if (!LyricsWindowService.canDraw(this)) {
            new MdDialog.Builder(this)
                    .title("需要「显示在其他应用上层」权限")
                    .message("桌面歌词要浮在别的应用上面，得先授权。\n点「去设置」后在列表里找到 Qusic 并打开开关。")
                    .negative("取消", null)
                    .positive("去设置", new MdDialog.OnClick() {
                        @Override public void onClick() {
                            try {
                                startActivityForResult(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:" + getPackageName())),
                                        REQ_OVERLAY);
                            } catch (Throwable t) {
                                Toast.makeText(MainActivity.this, "打不开设置页，请手动到「设置 → 应用 → 特殊权限」里开启",
                                        Toast.LENGTH_LONG).show();
                            }
                        }
                    }).show();
            return;
        }
        LyricsWindowService.start(this);
        Toast.makeText(this, "桌面歌词已开启，可拖动调整位置", Toast.LENGTH_SHORT).show();
        refreshAllPages();
    }

    // ── .Qusic 歌单导入导出 ────────────────────────────────────────────────
    /** 导出某个歌单：让用户选保存位置，文件名默认「歌单名.Qusic」 */
    public void exportPlaylist(long id) {
        Playlist.Item it = Playlist.byId(id);
        if (it == null) { Toast.makeText(this, "歌单不存在了", Toast.LENGTH_SHORT).show(); return; }
        if (it.size() == 0) { Toast.makeText(this, "空歌单没什么可导出的", Toast.LENGTH_SHORT).show(); return; }
        String json = Playlist.exportJson(it);
        if (json == null) { Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show(); return; }
        pendingExportId = id;
        pendingExportJson = json;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Playlist.MIME);
        i.putExtra(Intent.EXTRA_TITLE, Playlist.suggestFileName(it));
        try {
            startActivityForResult(i, REQ_EXPORT_PLAYLIST);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_LONG).show();
        }
    }


    /** 导入 .Qusic */
    public void importPlaylist() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // 只用 "*/*"，**不要**再传 EXTRA_MIME_TYPES。
        // .Qusic 是自定义后缀，系统不认识它，会归成 application/octet-stream；
        // 一旦给了 EXTRA_MIME_TYPES，选择器就会按那个白名单过滤，
        // 结果 .Qusic 文件直接变成灰色选不中（之前就是这个毛病）。
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT_PLAYLIST);
        } catch (Throwable t) {
            Toast.makeText(this, "没有可用的文件管理器", Toast.LENGTH_LONG).show();
        }
    }

    /** SAF 返回的 Uri → 文本 */
    private String readUri(android.net.Uri uri) throws Exception {
        java.io.InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ── 歌单详情（独立整屏页）────────────────────────────────────────────
    private boolean detailOpen;

    /** 打开某个歌单的详情页 */
    public void openPlaylistDetail(long id) {
        Playlist.Item it = Playlist.byId(id);
        if (it == null) return;

        playlistDetail.bind(id);

        if (!detailOpen) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(playlistDetail.view(), lp);
            detailOpen = true;
        }
        // 明确置顶：光靠「最后添加」在某些情况下不够稳
        // （比如迷你播放条升起来的时候），显式提到最前并抬 Z。
        root.bringChildToFront(playlistDetail.view());
        playlistDetail.view().setTranslationZ(Ui.px(this, 12));

        // 从右侧滑入 + 淡入，明确是「进入下一层」
        View v = playlistDetail.view();
        v.animate().cancel();
        v.setTranslationX(getResources().getDisplayMetrics().widthPixels * 0.22f);
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f)
                .setDuration(Theme.dur(260))
                .setInterpolator(Theme.EMPHASIZED)
                .start();
        Ui.hapticLight(v);
    }

    /** 关闭歌单详情 */
    public void closePlaylistDetail() {
        if (!detailOpen) return;
        detailOpen = false;
        final View v = playlistDetail.view();
        v.animate().cancel();
        v.animate().translationX(getResources().getDisplayMetrics().widthPixels * 0.22f)
                .alpha(0f)
                .setDuration(Theme.dur(200))
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        root.removeView(v);
                        v.setAlpha(1f);
                        v.setTranslationX(0f);
                    }
                }).start();
        refreshAllPages();
    }

    public boolean isDetailOpen() { return detailOpen; }

    // ── 社区帖子详情（独立二级页面）────────────────────────────────────────
    private boolean postOpen;

    /** 打开社区帖子详情 */
    public void openPostDetail(long id) {
        postDetail.bind(id);
        if (!postOpen) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            root.addView(postDetail.view(), lp);
            postOpen = true;
        }
        root.bringChildToFront(postDetail.view());
        postDetail.view().setTranslationZ(Ui.px(this, 12));
        View v = postDetail.view();
        v.animate().cancel();
        v.setTranslationX(getResources().getDisplayMetrics().widthPixels * 0.22f);
        v.setAlpha(0f);
        v.animate().translationX(0f).alpha(1f)
                .setDuration(Theme.dur(260))
                .setInterpolator(Theme.EMPHASIZED).start();
        Ui.hapticLight(v);
    }

    public void closePostDetail() {
        if (!postOpen) return;
        postOpen = false;
        final View v = postDetail.view();
        v.animate().cancel();
        v.animate().translationX(getResources().getDisplayMetrics().widthPixels * 0.22f)
                .alpha(0f).setDuration(Theme.dur(200))
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        root.removeView(v);
                        v.setAlpha(1f);
                        v.setTranslationX(0f);
                    }
                }).start();
        if (communityPage != null) communityPage.load();
    }

    public boolean isPostOpen() { return postOpen; }

    // ── 整个文件夹导入 ─────────────────────────────────────────────────────
    /**
     * 扫描用户选的目录，把里面所有音频导入。
     *
     * <p>扫描可能要几秒（取决于文件数），所以先弹一个进度对话框，
     * 而不是让界面看起来卡住了。
     */
    private void importFolder(final android.net.Uri treeUri) {
        final TextView status = new TextView(this);
        status.setTextSize(13.5f);
        status.setTextColor(Hct.withAlpha(Theme.t().onSurfaceVariant, 0.95f));
        status.setLineSpacing(Ui.px(this, 4), 1f);
        status.setText("正在扫描文件夹…");

        final android.app.Dialog dlg = new MdDialog.Builder(this)
                .title("导入文件夹")
                .message("会递归查找这个目录（含子目录）里的所有音频文件。")
                .content(status)
                .cancelable(false)
                .positive("取消", new MdDialog.OnClick() {
                    @Override public void onClick() { /* 让扫描跑完，只是关掉对话框 */ }
                })
                .show();

        final long t0 = System.currentTimeMillis();
        FolderImport.scan(this, treeUri, new FolderImport.Callback() {
            @Override public void onProgress(final int folders, final int found) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        status.setText("已扫描 " + folders + " 个目录，找到 " + found + " 首…");
                    }
                });
            }

            @Override public void onResult(final java.util.List<android.net.Uri> uris,
                                           final int folders, final boolean capped) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (uris.isEmpty()) {
                            if (dlg.isShowing()) dlg.dismiss();
                            new MdDialog.Builder(MainActivity.this)
                                    .title("没找到音频")
                                    .message("这个目录（含子目录）里没有发现音频文件。\n\n"
                                            + "支持 MP3 / FLAC / M4A / WAV / OGG / APE 等格式。\n"
                                            + "如果你确定里面有歌，可能是文件扩展名不常见，"
                                            + "可以改用「导入音乐」手动挑。")
                                    .positive("好", null).show();
                            return;
                        }
                        status.setText("找到 " + uris.size() + " 首，正在读取信息…");
                        doImportFolder(uris, folders, capped, dlg, t0);
                    }
                });
            }
        });
    }

    private void doImportFolder(final java.util.List<android.net.Uri> uris, final int folders,
                                final boolean capped, final android.app.Dialog dlg,
                                final long t0) {
        Library.importUris(this, uris, new Library.ImportCallback() {
            @Override public void onDone(final int added, final int skipped,
                                         final java.util.List<Song> songs) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (dlg.isShowing()) dlg.dismiss();
                        refreshAllPages();
                        long ms = System.currentTimeMillis() - t0;
                        StringBuilder m = new StringBuilder();
                        m.append("扫描了 ").append(folders).append(" 个目录，")
                         .append("用时 ").append(ms / 1000.0).append(" 秒。\n\n")
                         .append("新导入 ").append(added).append(" 首");
                        if (skipped > 0) {
                            m.append("，跳过 ").append(skipped).append(" 首")
                             .append("（已在曲库里，或读不出音频信息）");
                        }
                        m.append("。");
                        if (capped) {
                            m.append("\n\n注意：文件太多，这次只导入了前 ")
                             .append(uris.size()).append(" 首。")
                             .append("可以再选一次子目录继续导入。");
                        }
                        new MdDialog.Builder(MainActivity.this)
                                .title(added > 0 ? "导入完成" : "没有新增歌曲")
                                .message(m.toString())
                                .positive("好", null).show();
                    }
                });
            }
        });
    }

    /** 开关社区。关掉后底栏回到 4 项，给只想当播放器用的人。 */
    public void toggleCommunity() {
        boolean next = !Theme.community();
        Theme.setCommunity(next);
        if (navBar != null) navBar.applyCommunity(next);
        // 关掉时若正停在社区页，跳回首页
        if (!next && currentTab == 3) switchTab(0, false);
        refreshAllPages();
        Toast.makeText(this, next ? "社区已开启" : "社区已隐藏，底栏回到 4 项",
                Toast.LENGTH_SHORT).show();
    }

    /** 分享歌单到社区（曲库页调用） */
    public void shareToCommunity(Playlist.Item it) {
        if (communityPage != null) communityPage.share(it);
    }

    /** 供「关于」页调用 */
    public void checkForUpdatesManually() { checkForUpdates(true); }

    /** 打开文件选择器，让用户自己挑歌导入 */
    public void importMusic() {
        libraryPage.triggerImport();
    }

    /** 刷新所有页面（导入 / 删除 / 清空后调用） */
    public void refreshAllPages() {
        if (libraryPage != null) libraryPage.refresh();
        if (homePage != null) homePage.refresh();
        if (searchPage != null) searchPage.refresh();
        if (communityPage != null && currentTab == 3) communityPage.load();
        if (aboutPage != null) aboutPage.refreshStats();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);

        // ── 悬浮窗授权回来 ──
        if (req == REQ_IMPORT_TREE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                importFolder(data.getData());
            }
            return;
        }
        if (req == REQ_OVERLAY) {
            if (LyricsWindowService.canDraw(this)) {
                LyricsWindowService.start(this);
                Toast.makeText(this, "桌面歌词已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "还没有授权，桌面歌词无法显示", Toast.LENGTH_LONG).show();
            }
            refreshAllPages();
            return;
        }

        // ── 导出歌单 ──
        if (req == REQ_EXPORT_PLAYLIST) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            try {
                java.io.OutputStream os = getContentResolver().openOutputStream(data.getData());
                if (os == null) throw new java.io.IOException("无法写入");
                os.write(pendingExportJson.getBytes("UTF-8"));
                os.flush();
                os.close();
                Playlist.Item it = Playlist.byId(pendingExportId);
                Toast.makeText(this, "已导出"
                        + (it == null ? "" : "「" + it.name + "」")
                        + "，可以发给别人了", Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(this, "导出失败：" + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
            pendingExportJson = null;
            return;
        }

        // ── 导入歌单 ──
        if (req == REQ_IMPORT_PLAYLIST) {
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            android.net.Uri u = data.getData();
            try {
                String text = readUri(u);
                // 从文件名兜底取歌单名
                String fallback = null;
                String last = u.getLastPathSegment();
                if (last != null) {
                    int slash = last.lastIndexOf('/');
                    if (slash >= 0) last = last.substring(slash + 1);
                    if (last.toLowerCase().endsWith(Playlist.EXT.toLowerCase())) {
                        last = last.substring(0, last.length() - Playlist.EXT.length());
                    }
                    if (last.trim().length() > 0) fallback = last.trim();
                }
                Playlist.ImportResult r = Playlist.parseImport(text, fallback);
                if (r.error != null) {
                    Toast.makeText(this, r.error, Toast.LENGTH_LONG).show();
                    return;
                }
                // 同名歌单加个后缀，避免看着像覆盖了
                String base = r.item.name;
                String name = base;
                int n = 2;
                for (Playlist.Item e : Playlist.all()) {
                    if (e.name.equals(name)) { name = base + " (" + n + ")"; n++; }
                }
                r.item.name = name;
                Playlist.Item created = Playlist.create(this, name);
                int ok = 0;
                for (Song sg : r.item.songs) {
                    if (Playlist.add(this, created.id, sg)) ok++;
                }
                refreshAllPages();
                String msg = "已导入「" + name + "」：" + ok + " 首";
                if (!r.item.songs.isEmpty() && !r.item.songs.get(0).online) {
                    msg += "（本地歌曲按标题+歌手匹配，换了设备可能对不上）";
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            } catch (Throwable t) {
                Toast.makeText(this, "导入失败：" + t.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (req != REQ_IMPORT || result != RESULT_OK || data == null) return;

        final java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        // 多选
        android.content.ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                android.net.Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;

        Toast.makeText(this, "正在导入 " + uris.size() + " 个文件…", Toast.LENGTH_SHORT).show();
        Library.importUris(this, uris, new Library.ImportCallback() {
            @Override public void onDone(int added, int skipped, java.util.List<Song> addedSongs) {
                refreshAllPages();
                StringBuilder msg = new StringBuilder();
                msg.append("导入完成：新增 ").append(added).append(" 首");
                if (skipped > 0) msg.append("，跳过 ").append(skipped).append(" 个");
                Toast.makeText(MainActivity.this, msg.toString(), Toast.LENGTH_LONG).show();
            }
        });
    }

    // ── 生命周期 ────────────────────────────────────────────────────────────
    @Override protected void onResume() {
        super.onResume();
        Theme.tintStatusBar(this);
        homePage.onShow();
        if (aboutPage != null) aboutPage.refreshStats();
        // 从后台回来时 inset 可能变了（旋转 / 切换手势导航）
        if (root != null) root.post(new Runnable() { @Override public void run() { applyInsets(); } });
    }

    @Override protected void onDestroy() {
        Theme.setListener(null);
        if (bound) { try { unbindService(conn); } catch (Throwable ignored) {} bound = false; }
        if (player != null) player.removeListener(this);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        // 歌单详情页优先关闭
        if (detailOpen) { closePlaylistDetail(); return; }
        // 社区帖子详情（二级页面）优先关闭
        if (postOpen) { closePostDetail(); return; }
        // 不在首页时，返回键回首页，而不是退出
        if (currentTab != 0) {
            switchTab(0, true);
            return;
        }
        super.onBackPressed();
    }

    // ── PlayerService.Listener ──────────────────────────────────────────────
    @Override public void onSongChanged(Song s, int index) {
        runOnUiThread(new Runnable() { @Override public void run() {
            // 只更新真正关心「当前播放」的页面。
            // 千万别在这里 refreshAllPages()：搜索页会因此重新发起网络搜索，
            // 而 setData() 会把列表滚回顶部 —— 也就是「点一下歌就跳回最上面」。
            if (homePage != null) homePage.onSongChanged();
            applyCloudInset(s != null);
        }});
    }
    @Override public void onPlayStateChanged(boolean playing) {
        runOnUiThread(new Runnable() { @Override public void run() {
            applyCloudInset(PlayerService.sCurrent != null);
        }});
    }
    @Override public void onProgress(long pos, long dur) {}
    @Override public void onQueueChanged() {}
    @Override public void onRepeatShuffleChanged(int repeat, int shuffle) {}

}
