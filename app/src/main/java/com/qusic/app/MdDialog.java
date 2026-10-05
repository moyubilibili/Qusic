package com.qusic.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * MD3 对话框。
 *
 * <p>系统的 {@code AlertDialog} 出来是 Material 1 的样子：方角、白底、
 * 蓝色大写按钮 —— 跟整套界面完全不搭。这里按 Material Design 3 的
 * **basic dialog** 规范自绘：
 *
 * <ul>
 *   <li>容器：28dp 圆角，{@code surfaceContainerHigh}</li>
 *   <li>标题：headline-small（20sp，{@code onSurface}）</li>
 *   <li>正文：body-medium（14sp，{@code onSurfaceVariant}）</li>
 *   <li>按钮：text button，{@code primary}，右对齐，高度 40dp</li>
 *   <li>入场：轻微放大 + 淡入</li>
 * </ul>
 */
public final class MdDialog {

    public interface OnClick { void onClick(); }

    public static class Builder {
        private final Activity act;
        private String title;
        private String message;
        private View content;
        private String[] items;
        private android.content.DialogInterface.OnClickListener itemClick;
        private String negText; private OnClick negClick;
        private String posText; private OnClick posClick;
        private boolean cancelable = true;

        public Builder(Activity a) { this.act = a; }

        public Builder title(String s) { title = s; return this; }
        public Builder message(String s) { message = s; return this; }
        public Builder content(View v) { content = v; return this; }
        public Builder items(String[] a, android.content.DialogInterface.OnClickListener l) {
            items = a; itemClick = l; return this;
        }
        public Builder negative(String t, OnClick l) { negText = t; negClick = l; return this; }
        public Builder positive(String t, OnClick l) { posText = t; posClick = l; return this; }
        public Builder cancelable(boolean b) { cancelable = b; return this; }

        public Dialog show() {
            Tokens t = Theme.t();
            int pad = Ui.px(act, 24);
            Context c = act;

            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad, pad, Ui.px(act, 12));

            if (title != null && title.length() > 0) {
                TextView tv = new TextView(c);
                tv.setText(title);
                tv.setTextColor(t.onSurface);
                tv.setTextSize(19.5f);
                tv.setTypeface(Ui.tfBold());
                box.addView(tv);
            }
            if (message != null && message.length() > 0) {
                TextView tv = new TextView(c);
                tv.setText(message);
                tv.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.95f));
                tv.setTextSize(13.5f);
                tv.setLineSpacing(Ui.px(act, 4), 1f);
                tv.setPadding(0, Ui.px(act, title != null ? 12 : 0), 0, 0);
                box.addView(tv);
            }
            if (content != null) {
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                clp.topMargin = Ui.px(act, title != null || message != null ? 16 : 0);
                box.addView(content, clp);
            }

            // 列表型（单选菜单）
            if (items != null && items.length > 0) {
                ScrollView sv = new ScrollView(c);
                sv.setVerticalScrollBarEnabled(false);
                LinearLayout list = new LinearLayout(c);
                list.setOrientation(LinearLayout.VERTICAL);
                for (int i = 0; i < items.length; i++) {
                    final int idx = i;
                    TextView row = new TextView(c);
                    row.setText(items[i]);
                    row.setTextColor(t.onSurface);
                    row.setTextSize(14.5f);
                    row.setPadding(Ui.px(act, 4), Ui.px(act, 14), Ui.px(act, 4), Ui.px(act, 14));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            Ui.hapticLight(v);
                            dismissHolder.dismiss();
                            if (itemClick != null) itemClick.onClick(null, idx);
                        }
                    });
                    list.addView(row);
                }
                sv.addView(list);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
                slp.topMargin = Ui.px(act, 8);
                box.addView(sv, slp);
            }

            // 按钮行
            if (negText != null || posText != null) {
                LinearLayout btns = new LinearLayout(c);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                btns.setGravity(Gravity.END);
                btns.setPadding(0, Ui.px(act, 12), 0, 0);
                if (negText != null) btns.addView(textButton(c, t, negText, false, new OnClick() {
                    @Override public void onClick() {
                        dismissHolder.dismiss();
                        if (negClick != null) negClick.onClick();
                    }
                }));
                if (posText != null) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = Ui.px(act, 8);
                    btns.addView(textButton(c, t, posText, true, new OnClick() {
                        @Override public void onClick() {
                            dismissHolder.dismiss();
                            if (posClick != null) posClick.onClick();
                        }
                    }), lp);
                }
                box.addView(btns);
            }

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(t.surfaceContainerHigh);
            bg.setCornerRadius(Ui.px(act, 28));
            box.setBackground(bg);

            final Dialog d = new Dialog(act);
            d.requestWindowFeature(Window.FEATURE_NO_TITLE);
            d.setCancelable(cancelable);
            d.setCanceledOnTouchOutside(cancelable);
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(0x00000000));
                w.setLayout((int) (Ui.screenW(act) * 0.88f),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            d.setContentView(box);
            dismissHolder = d;
            d.show();

            // 入场：轻微放大 + 淡入
            box.setAlpha(0f);
            box.setScaleX(0.94f);
            box.setScaleY(0.94f);
            box.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(Theme.dur(180)).start();
            return d;
        }

        private Dialog dismissHolder;

        private TextView textButton(Context c, Tokens t, String label,
                                    boolean primary, final OnClick click) {
            TextView b = new TextView(c);
            b.setText(label);
            b.setTextSize(14);
            b.setTypeface(Ui.tfBold());
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.px(act, 16), Ui.px(act, 10), Ui.px(act, 16), Ui.px(act, 10));
            b.setTextColor(primary ? t.primary : Hct.withAlpha(t.onSurfaceVariant, 0.95f));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { Ui.hapticLight(v); click.onClick(); }
            });
            return b;
        }
    }

    private MdDialog() {}
}
