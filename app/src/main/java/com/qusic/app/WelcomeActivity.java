package com.qusic.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 首次启动的欢迎 / 激活页。
 *
 * <p>只展示一次：点「开始使用」后写入标记，之后启动直接进主界面。
 *
 * <p>离场动效：整个界面像墨一样向上散去 —— 内容整体上浮淡出，
 * 同时由 {@link InkDissolve} 从界面各处升起柔边墨团，散尽后进入主界面。
 */
public class WelcomeActivity extends Activity {

    private FrameLayout root;
    private InkDissolve ink;
    private LinearLayout col;
    private boolean finishing;

    @Override protected void onCreate(Bundle st) {
        super.onCreate(st);
        Theme.init(this);
        Library.init(this);

        // 已经激活过就直接进主界面，不留痕迹
        if (getSharedPreferences("qusic_state", MODE_PRIVATE)
                .getBoolean("activated", false)) {
            goMain();
            return;
        }

        Theme.tintStatusBar(this);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        buildUi();
    }

    private void buildUi() {
        final Tokens t = Theme.t();
        root = new FrameLayout(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                float w = getWidth(), h = getHeight();
                p.reset();
                p.setShader(new LinearGradient(0, 0, w * 0.4f, h,
                        new int[]{t.surface, Hct.blendLab(t.surface, t.primaryContainer, 0.55f),
                                Hct.blendLab(t.surface, t.tertiaryContainer, 0.35f)},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
                p.setShader(null);
            }
        };
        root.setWillNotDraw(false);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        root.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col = Ui.column(this);
        int pad = Ui.px(this, 30);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(pad, Ui.statusBarHeight(this) + Ui.px(this, 36), pad,
                Ui.navBarHeight(this) + Ui.px(this, 30));
        sv.addView(col);

        // ── 应用图标（统一用 AppMark 绘制）──
        final View logo = new View(this) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF r = new RectF();
            @Override protected void onDraw(Canvas c) {
                r.set(0, 0, getWidth(), getHeight());
                AppMark.draw(c, r, Theme.t());
            }
        };
        logo.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                Ui.px(this, 116), Ui.px(this, 116));
        col.addView(logo, llp);
        logo.setAlpha(0f);
        logo.setScaleX(0.6f);
        logo.setScaleY(0.6f);

        // ── 标题 ──
        TextView name = new TextView(this);
        name.setText("Qusic");
        name.setTextColor(t.onSurface);
        name.setTextSize(38);
        name.setTypeface(Ui.tfBlack());
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.px(this, 20), 0, 0);
        name.setAlpha(0f);
        col.addView(name);

        TextView tag = new TextView(this);
        tag.setText("你的本地音乐，放得体面一点");
        tag.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.9f));
        tag.setTextSize(13.5f);
        tag.setGravity(Gravity.CENTER);
        tag.setPadding(0, Ui.px(this, 6), 0, Ui.px(this, 26));
        tag.setAlpha(0f);
        col.addView(tag);

        // ── 特性 ──
        String[][] feats = {
                {"note", "自己导入，不翻你的手机", "只显示你亲手挑进来的歌"},
                {"palette", "Material Design 3", "一颗种子色推导整套主题"},
                {"equalizer", "精美的播放页", "封面取色、光晕律动、滚动歌词"},
                {"search", "还能搜网易云", "匿名模式，不需要登录账号"},
        };
        View[] rows = new View[feats.length];
        for (int i = 0; i < feats.length; i++) {
            rows[i] = featureRow(t, feats[i][0], feats[i][1], feats[i][2]);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            flp.bottomMargin = Ui.px(this, 8);
            rows[i].setAlpha(0f);
            col.addView(rows[i], flp);
        }

        TextView note = new TextView(this);
        note.setText("完全离线运行 · 不扫描存储 · 不上传任何数据");
        note.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.6f));
        note.setTextSize(11.5f);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, Ui.px(this, 22), 0, Ui.px(this, 14));
        note.setAlpha(0f);
        col.addView(note);

        // ── 滑动开始 ──
        // 用「滑动」而不是「点按」：本机屏下指纹节点同时也是唯一的触摸设备，
        // 会偶发抛出坐标固定、没有位移的幽灵触摸，点按型按钮会被它直接点掉。
        // 滑动需要真实持续的位移，天然免疫。
        final SlideToActivate slide = new SlideToActivate(this);
        slide.setAlpha(0f);
        col.addView(slide, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 墨层放在最上面
        ink = new InkDissolve(this);
        root.addView(ink, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        // ── 入场编排 ──
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(120).setDuration(Theme.dur(560))
                .setInterpolator(Theme.SPRING).start();
        fadeUp(name, 320, 26);
        fadeUp(tag, 400, 22);
        for (int i = 0; i < rows.length; i++) fadeUp(rows[i], 560 + i * 85, 22);
        fadeUp(note, 980, 16);
        fadeUp(slide, 1060, 26);

        slide.setOnActivated(new SlideToActivate.OnActivated() {
            @Override public void onActivated() {
                if (finishing) return;
                finishing = true;
                // 内容整体上浮淡出，像被墨水带走
                float h = root.getHeight() > 0 ? root.getHeight() : Ui.screenH(WelcomeActivity.this);
                col.animate()
                        .translationY(-h * 0.22f)
                        .alpha(0f)
                        .setDuration(Theme.dur(760))
                        .setInterpolator(Theme.EMPHASIZED)
                        .start();
                root.animate().alpha(0f).setStartDelay(Theme.dur(560))
                        .setDuration(Theme.dur(420)).start();
                ink.start(t.primary, new InkDissolve.OnDone() {
                    @Override public void onFinished() {
                        getSharedPreferences("qusic_state", MODE_PRIVATE)
                                .edit().putBoolean("activated", true).apply();
                        goMain();
                    }
                });
            }
        });
    }

    private View featureRow(Tokens t, String icon, String title, String sub) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.px(this, 16), Ui.px(this, 13), Ui.px(this, 16), Ui.px(this, 13));
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setColor(Hct.composite(t.surfaceContainerHigh, 0.72f, t.surface));
        g.setCornerRadius(Ui.px(this, 18));
        g.setStroke(Ui.px(this, 1), Hct.withAlpha(t.outlineVariant, 0.5f));
        row.setBackground(g);

        HomePage.IconView iv = new HomePage.IconView(this, icon, t.primary);
        row.addView(iv, new LinearLayout.LayoutParams(Ui.px(this, 24), Ui.px(this, 24)));

        LinearLayout c2 = Ui.column(this);
        c2.setPadding(Ui.px(this, 15), 0, 0, 0);

        TextView ti = new TextView(this);
        ti.setText(title);
        ti.setTextColor(t.onSurface);
        ti.setTextSize(14);
        ti.setTypeface(Ui.tfMed());
        c2.addView(ti);

        TextView su = new TextView(this);
        su.setText(sub);
        su.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        su.setTextSize(11.5f);
        c2.addView(su);

        row.addView(c2, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void fadeUp(View v, long delay, int dyDp) {
        v.setTranslationY(Ui.px(this, dyDp));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(delay).setDuration(Theme.dur(480))
                .setInterpolator(Theme.EMPHASIZED).start();
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }


    @Override public void onBackPressed() {
        // 欢迎页不允许返回跳过
    }
}
