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

    private FrameLayout root;
    private FrameLayout pageHost;
    private LiquidNavBar navBar;
    private MiniPlayerBar miniBar;
    private FluidCloud fluidCloud;

    private HomePage homePage;
    private LibraryPage libraryPage;
    private SearchPage searchPage;
    private AboutPage aboutPage;
    private View currentPage;
    private int currentTab = 0;

    private PlayerService player;
    private boolean bound;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
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
            case 3: next = aboutPage.view(); break;
        }
        if (next == null) return;

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
                    if (Theme.autoUpdate() && info.apkUrl.length() > 0) {
                        // 自动更新：不打断用户，后台下载，完成后系统提示安装
                        Toast.makeText(self, "发现新版本 " + info.tag + "，正在后台下载…",
                                Toast.LENGTH_SHORT).show();
                        Updater.downloadAndInstall(self, info);
                    } else {
                        Updater.showUpdateDialog(self, info);
                    }
                } else if (manual) {
                    Toast.makeText(self, "已是最新版本 " + Updater.localVersionName(self),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
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
        if (aboutPage != null) aboutPage.refreshStats();
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
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
        if (currentTab != 0) { switchTab(0, true); return; }
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
