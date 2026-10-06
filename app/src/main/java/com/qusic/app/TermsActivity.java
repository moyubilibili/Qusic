package com.qusic.app;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 免责条款 / 用户协议页。
 *
 * <p>首次启动时显示一次，必须同意才能进主界面。
 *
 * <p>MD3 的做法：不用 AlertDialog（内容太长），而是一个**全屏的引导页**：
 * 大标题 + 可滚动正文 + 底部两个按钮。正文滚到底之前主按钮保持禁用态，
 * 避免用户看都没看就点同意。
 */
public class TermsActivity extends Activity {

    public static final String EXTRA_FROM_WELCOME = "from_welcome";

    private boolean fromWelcome;
    private boolean agreed;
    private ScrollView scroll;
    private TextView agreeBtn;

    @Override protected void onCreate(Bundle st) {
        super.onCreate(st);
        Theme.init(this);
        fromWelcome = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_WELCOME, false);

        Tokens t = Theme.t();
        int pad = Ui.px(this, 24);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.surface);

        LinearLayout col = Ui.column(this);
        col.setPadding(pad, Ui.statusBarHeight(this) + Ui.px(this, 20), pad, Ui.px(this, 18));

        // ── 标题 ──
        TextView title = new TextView(this);
        title.setText("使用前请阅读");
        title.setTextColor(t.onSurface);
        title.setTextSize(26);
        title.setTypeface(Ui.tfBold());
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Qusic · 本地音乐播放器");
        sub.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        sub.setTextSize(13);
        sub.setPadding(0, Ui.px(this, 6), 0, Ui.px(this, 16));
        col.addView(sub);

        // ── 正文（可滚动）──
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);

        TextView body = new TextView(this);
        body.setText(TERMS);
        body.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
        body.setTextSize(13.5f);
        body.setLineSpacing(Ui.px(this, 6), 1f);
        body.setPadding(Ui.px(this, 18), Ui.px(this, 18), Ui.px(this, 18), Ui.px(this, 18));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.surfaceContainerLow);
        bg.setCornerRadius(Ui.px(this, 20));
        body.setBackground(bg);
        scroll.addView(body);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.bottomMargin = Ui.px(this, 16);
        col.addView(scroll, slp);

        // ── 底部提示 + 按钮 ──
        final TextView hint = new TextView(this);
        hint.setText("请向下滚动阅读完整条款");
        hint.setTextColor(Hct.withAlpha(t.primary, 0.9f));
        hint.setTextSize(12);
        hint.setPadding(0, 0, 0, Ui.px(this, 10));
        col.addView(hint);

        LinearLayout btns = Ui.row(this);

        TextView deny = new TextView(this);
        deny.setText("不同意");
        deny.setTextSize(14.5f);
        deny.setTypeface(Ui.tfMed());
        deny.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
        deny.setGravity(Gravity.CENTER);
        deny.setPadding(0, Ui.px(this, 14), 0, Ui.px(this, 14));
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(t.surfaceContainerHighest);
        dbg.setCornerRadius(Ui.px(this, 24));
        deny.setBackground(dbg);
        deny.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.hapticLight(v);
                new MdDialog.Builder(TermsActivity.this)
                        .title("不同意就无法使用")
                        .message("Qusic 需要你同意条款才能继续。\n"
                                + "不同意的话，点「退出」关闭应用。")
                        .negative("再看看", null)
                        .positive("退出", new MdDialog.OnClick() {
                            @Override public void onClick() { finishAffinity(); }
                        }).show();
            }
        });
        btns.addView(deny, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.42f));

        agreeBtn = new TextView(this);
        agreeBtn.setText("同意并继续");
        agreeBtn.setTextSize(14.5f);
        agreeBtn.setTypeface(Ui.tfBold());
        agreeBtn.setGravity(Gravity.CENTER);
        agreeBtn.setPadding(0, Ui.px(this, 14), 0, Ui.px(this, 14));
        agreeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!agreed) return;
                Ui.hapticLight(v);
                Theme.setTermsAgreed(TermsActivity.this, true);
                if (fromWelcome) {
                    // 从欢迎页来的：继续走欢迎页的流程
                    setResult(RESULT_OK);
                } else {
                    startActivity(new android.content.Intent(TermsActivity.this, MainActivity.class));
                }
                finish();
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 0.58f);
        alp.leftMargin = Ui.px(this, 10);
        btns.addView(agreeBtn, alp);
        col.addView(btns);

        applyAgreeState(t, false);

        // 滚到底才允许同意
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override public void onScrollChange(View v, int x, int y, int ox, int oy) {
                if (agreed) return;
                View child = scroll.getChildAt(0);
                if (child == null) return;
                int diff = child.getBottom() - (scroll.getHeight() + scroll.getScrollY());
                if (diff <= Ui.px(TermsActivity.this, 12)) {
                    agreed = true;
                    hint.setText("已阅读完毕");
                    applyAgreeState(Theme.t(), true);
                }
            }
        });

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // 入场淡入
        col.setAlpha(0f);
        col.animate().alpha(1f).setDuration(Theme.dur(260)).start();
    }

    private void applyAgreeState(Tokens t, boolean on) {
        if (agreeBtn == null) return;
        GradientDrawable g = new GradientDrawable();
        g.setColor(on ? t.primary : t.surfaceContainerHighest);
        g.setCornerRadius(Ui.px(this, 24));
        agreeBtn.setBackground(g);
        agreeBtn.setTextColor(on ? t.onPrimary
                : Hct.withAlpha(t.onSurfaceVariant, 0.5f));
        agreeBtn.setAlpha(on ? 1f : 0.75f);
    }

    private static final String TERMS =
        "一、软件性质\n\n"
      + "Qusic 是一个开源的音乐播放器，仅供学习与技术研究使用。"
      + "本软件不提供、不存储、不分发任何音频内容。\n\n"
      + "二、音乐来源\n\n"
      + "· 本地音乐：由你自己通过系统文件选择器导入，只保存在你的设备上，"
      + "不会上传到任何服务器。\n\n"
      + "· 在线搜索：使用的是**第三方非官方接口**（酷我、网易云）。"
      + "这些接口不稳定、随时可能失效，搜索结果与播放能力均不受本软件控制。"
      + "相关音乐版权归各平台及版权方所有。\n\n"
      + "三、请支持正版\n\n"
      + "本软件的在线功能仅为技术演示。如果你喜欢某首歌曲，"
      + "请到官方平台购买或开通会员，以支持创作者。\n\n"
      + "四、社区功能（可选）\n\n"
      + "社区用于分享**歌单的曲目信息**（歌名、歌手、专辑、音源编号），"
      + "不包含任何音频文件。你可以随时在「关于」里关闭社区，"
      + "关闭后本软件不会向社区服务器发起任何请求。\n\n"
      + "五、免责声明\n\n"
      + "本软件按「现状」提供，不附带任何明示或暗示的担保。"
      + "因使用本软件产生的任何直接或间接损失，作者不承担责任。\n\n"
      + "使用者应自行确保其使用行为符合所在地区的法律法规。\n\n"
      + "六、隐私\n\n"
      + "· 本地播放功能完全离线，不收集任何数据\n"
      + "· 只有在你主动使用在线搜索或社区时才会联网\n"
      + "· 社区账号仅需要用户名、邮箱和密码，密码经过 bcrypt 加密存储\n\n"
      + "如果你同意以上条款，请点击下方按钮继续。";
}
