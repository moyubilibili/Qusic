package com.qusic.app;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.List;

/**
 * 全屏播放页。
 *
 * <p>用透明主题 + 自绘 View 实现「从迷你条无缝放大」的转场观感：
 * Activity 本身淡入，内部 {@link NowPlayingView} 播放上浮+缩放动画。
 *
 * <p>另外提供队列面板（底部滑出）与返回手势。
 */
public class PlayerActivity extends Activity {

    private NowPlayingView np;
    private FrameLayout root;

    @Override protected void onCreate(Bundle st) {
        super.onCreate(st);
        Theme.init(this);
        Theme.tintStatusBar(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        np = new NowPlayingView(this);
        np.setOnAddToPlaylist(new Runnable() {
            @Override public void run() {
                Song cur = PlayerService.sCurrent;
                if (cur == null) cur = PlayerService.instance() == null ? null
                        : PlayerService.instance().current();
                if (cur != null) Playlist.showAddDialog(PlayerActivity.this, cur, null);
            }
        });
        np.setOnBack(new NowPlayingView.OnBack() {
            @Override public void back() { finish(); }
        });
        root.addView(np, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        np.playOpenAnimation();
    }

    @Override protected void onResume() {
        super.onResume();
        if (np != null) np.setActive(true);
    }

    @Override protected void onPause() {
        super.onPause();
        if (np != null) np.setActive(false);
    }

    @Override public void finish() {
        super.finish();
        // 播放页滑回底部，主界面回到前台
        overridePendingTransition(R.anim.main_return, R.anim.player_exit);
    }

    @Override public void onBackPressed() {
        // 让播放页自己处理（歌词展开时先收起）
        finish();
    }

    /** 播放队列面板（底部滑出，液态玻璃） */
    public void showQueue() {
        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            w.setAttributes(lp);
            w.setWindowAnimations(0);
        }

        Tokens t = Theme.t();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.px(this, 20);
        box.setPadding(pad, pad, pad, Ui.px(this, 28));
        box.setBackground(roundBg(t.surfaceContainerHigh, Ui.px(this, 28), t));

        // 标题
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("播放队列");
        title.setTextColor(t.onSurface);
        title.setTextSize(18);
        title.setTypeface(Ui.tfBold());
        box.addView(title);

        PlayerService s = PlayerService.instance();
        List<Song> q = s != null ? s.queue() : new java.util.ArrayList<Song>();
        int cur = s != null ? s.currentIndex() : -1;

        android.widget.TextView sub = new android.widget.TextView(this);
        sub.setText(q.size() + " 首 · " + (cur >= 0 ? "正在播放第 " + (cur + 1) + " 首" : ""));
        sub.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        sub.setTextSize(12);
        sub.setPadding(0, Ui.px(this, 4), 0, Ui.px(this, 10));
        box.addView(sub);

        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int maxH = (int) (Ui.screenH(this) * 0.52f);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(maxH, q.size() * Ui.px(this, 56) + 8)));

        for (int i = 0; i < q.size(); i++) {
            final int idx = i;
            Song sg = q.get(i);
            android.widget.TextView row = new android.widget.TextView(this);
            boolean isCur = i == cur;
            row.setText((isCur ? "▶  " : "     ") + sg.title + "\n     " + sg.subtitle());
            row.setTextColor(isCur ? t.primary : t.onSurface);
            row.setTextSize(13.5f);
            row.setTypeface(isCur ? Ui.tfBold() : Ui.tf());
            row.setLineSpacing(0, 1.15f);
            row.setPadding(Ui.px(this, 8), Ui.px(this, 10), Ui.px(this, 8), Ui.px(this, 10));
            row.setBackground(tapBg(t, isCur));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Ui.hapticLight(v);
                    PlayerService ps = PlayerService.instance();
                    if (ps != null) ps.playIndex(idx);
                    d.dismiss();
                }
            });
            list.addView(row);
        }
        if (q.isEmpty()) {
            android.widget.TextView empty = new android.widget.TextView(this);
            empty.setText("队列是空的");
            empty.setTextColor(t.onSurfaceVariant);
            empty.setPadding(Ui.px(this, 8), Ui.px(this, 20), 0, Ui.px(this, 20));
            list.addView(empty);
        }
        sv.addView(list);
        box.addView(sv);

        d.setContentView(box);
        d.show();
        // 滑入动画
        if (w != null) {
            View dv = w.getDecorView();
            dv.setTranslationY(Ui.screenH(this));
            dv.animate().translationY(0).setDuration(Theme.dur(420))
                    .setInterpolator(Theme.EMPHASIZED).start();
        }
    }

    private android.graphics.drawable.Drawable roundBg(int color, float r, Tokens t) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        g.setStroke(Ui.px(this, 1), Hct.withAlpha(t.outlineVariant, 0.5f));
        return g;
    }

    private android.graphics.drawable.Drawable tapBg(Tokens t, boolean cur) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(cur ? Hct.composite(t.primary, 0.12, t.surfaceContainerHigh) : Color.TRANSPARENT);
        g.setCornerRadius(Ui.px(this, 12));
        return g;
    }

}
