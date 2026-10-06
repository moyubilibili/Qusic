package com.qusic.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 论坛发帖编辑器。
 *
 * <h3>标签怎么处理</h3>
 * **不用单独的标签输入框** —— 直接在正文里写 {@code #周杰伦}，
 * 服务端用同一个正则解析。这样用户少一个步骤，而且标签就在它出现的语境里，
 * 比单独一栏更自然。编辑器下方实时提示「会解析出 N 个标签」，
 * 让用户知道写对了没有。
 *
 * <h3>图片</h3>
 * 选图后**先在客户端压缩**（长边 1280、质量逐级降到 300KB 以内），
 * 再转 base64 上传。服务端还会用 getimagesize 再校验一次 ——
 * 客户端的限制是可以绕过的，服务端的不能。
 *
 * <h3>为什么用 base64 而不是 multipart</h3>
 * 服务端的 {@code q_input()} 只解析 JSON 和表单两种，没有处理文件上传。
 * 加 multipart 支持要动公共代码，而 base64 只是多 33% 体积 ——
 * 单张上限 300KB，多出来的 100KB 对手机网络不算什么。
 */
public class ForumComposer {

    /** 和服务端保持一致 */
    private static final int MAX_BYTES = 307200;      // 300KB
    private static final int MAX_SIDE  = 1280;
    private static final int MAX_IMAGES = 9;
    private static final int MAX_SONGS  = 5;

    public interface OnPosted { void posted(long id); }

    private final Activity act;
    private final List<String> images = new ArrayList<>();   // base64
    private final List<Community.SongRef> songs = new ArrayList<>();
    private TextView imgLabel, songLabel, tagHint;
    private OnPosted cb;

    public ForumComposer(Activity a) { this.act = a; }

    /** 打开编辑器 */
    public void show(OnPosted onPosted) {
        this.cb = onPosted;
        Context c = act;
        Tokens t = Theme.t();

        final MdField titleF = new MdField(c, "标题");
        final MdField bodyF = new MdField(c, "正文…  用 # 加标签，比如 #周杰伦");

        LinearLayout box = Ui.column(c);

        box.addView(titleF);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.px(c, 10);
        // MdField 没暴露行数接口，直接拿内部 EditText 设
        bodyF.input().setMinLines(4);
        bodyF.input().setGravity(android.view.Gravity.TOP);
        bodyF.input().addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                updateTagHint(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        box.addView(bodyF, blp);

        // 标签实时提示
        tagHint = new TextView(c);
        tagHint.setTextSize(11.5f);
        tagHint.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        tagHint.setPadding(Ui.px(c, 4), Ui.px(c, 6), 0, 0);
        box.addView(tagHint);
        updateTagHint("");

        // 附件行
        LinearLayout tools = Ui.row(c);
        tools.setPadding(0, Ui.px(c, 12), 0, 0);

        TextView imgBtn = chip(c, "＋ 图片");
        imgBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Ui.hapticLight(v);
                if (images.size() >= MAX_IMAGES) {
                    Toast.makeText(act, "最多 " + MAX_IMAGES + " 张", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (act instanceof MainActivity) {
                    ((MainActivity) act).pickImage(new MainActivity.ImagePick() {
                        @Override public void picked(Bitmap bm) { addImage(bm); }
                    });
                } else {
                    Toast.makeText(act, "当前环境不支持选图", Toast.LENGTH_SHORT).show();
                }
            }
        });
        tools.addView(imgBtn);

        TextView songBtn = chip(c, "＋ 引用歌曲");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.px(c, 8);
        songBtn.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Ui.hapticLight(v);
                pickSong();
            }
        });
        tools.addView(songBtn, slp);
        box.addView(tools);

        imgLabel = new TextView(c);
        imgLabel.setTextSize(11.5f);
        imgLabel.setTextColor(t.primary);
        imgLabel.setPadding(Ui.px(c, 4), Ui.px(c, 6), 0, 0);
        box.addView(imgLabel);

        songLabel = new TextView(c);
        songLabel.setTextSize(11.5f);
        songLabel.setTextColor(t.primary);
        songLabel.setPadding(Ui.px(c, 4), Ui.px(c, 4), 0, 0);
        box.addView(songLabel);
        refreshLabels();

        new MdDialog.Builder(act)
                .title("发帖")
                .content(box)
                .negative("取消", null)
                .positive("发布", new MdDialog.OnClick() {
                    @Override public void onClick() {
                        String ti = titleF.text().trim();
                        String bo = bodyF.text().trim();
                        if (ti.length() == 0) {
                            Toast.makeText(act, "写个标题", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (bo.length() == 0) {
                            Toast.makeText(act, "正文不能为空", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        send(ti, bo);
                    }
                })
                .show();
    }

    /** 更新「会解析出哪些标签」的提示 */
    private void updateTagHint(String body) {
        List<String> tags = parseTags(body);
        if (tags.isEmpty()) {
            tagHint.setText("还没有标签。在正文里写 #话题 就会自动加上。");
        } else {
            StringBuilder sb = new StringBuilder("将添加 ");
            sb.append(tags.size()).append(" 个标签：");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append('#').append(tags.get(i));
            }
            tagHint.setText(sb.toString());
        }
    }

    /**
     * 客户端也解析一遍标签 —— 纯粹为了给用户即时反馈。
     * **真正入库的以服务端解析结果为准**，因为客户端可以绕过。
     */
    private static List<String> parseTags(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.length() == 0) return out;
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) != '#') { i++; continue; }
            int j = i + 1;
            StringBuilder sb = new StringBuilder();
            while (j < s.length() && sb.length() < 24) {
                char ch = s.charAt(j);
                if (Character.isLetterOrDigit(ch) || ch == '_'
                        || Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                    sb.append(ch); j++;
                } else break;
            }
            if (sb.length() > 0) {
                String tag = sb.toString();
                if (!out.contains(tag)) out.add(tag);
                i = j;
            } else {
                i++;
            }
        }
        return out;
    }

    private void addImage(Bitmap bm) {
        if (bm == null) return;
        String b64 = compress(bm);
        if (b64 == null) {
            Toast.makeText(act, "图片压缩失败", Toast.LENGTH_SHORT).show();
            return;
        }
        images.add(b64);
        refreshLabels();
    }

    /**
     * 压到服务端的限制以内。
     *
     * <p>两级：先按长边缩放到 1280，再让 JPEG 质量从 92 一路降到 40，
     * 直到体积进 300KB。绝大多数照片第一步就够了，只有特别花的图才会走到第二步。
     */
    private String compress(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            int longSide = Math.max(w, h);
            Bitmap bm = src;
            if (longSide > MAX_SIDE) {
                float k = (float) MAX_SIDE / longSide;
                bm = Bitmap.createScaledBitmap(src,
                        Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
            }
            for (int q = 92; q >= 40; q -= 13) {
                ByteArrayOutputStream os = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, q, os);
                byte[] b = os.toByteArray();
                if (b.length <= MAX_BYTES || q <= 40) {
                    if (bm != src) bm.recycle();
                    return Base64.encodeToString(b, Base64.NO_WRAP);
                }
            }
            if (bm != src) bm.recycle();
        } catch (Throwable ignored) {}
        return null;
    }

    /** 从收藏里挑一首引用 */
    private void pickSong() {
        if (songs.size() >= MAX_SONGS) {
            Toast.makeText(act, "最多引用 " + MAX_SONGS + " 首", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Song> favs = Favorites.items();
        if (favs.isEmpty()) {
            Toast.makeText(act, "收藏是空的 —— 先收藏几首再来引用",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        int n = Math.min(favs.size(), 30);
        final String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            Song s = favs.get(i);
            names[i] = s.title + (s.artist == null || s.artist.length() == 0 ? "" : " — " + s.artist);
        }
        new MdDialog.Builder(act)
                .title("引用歌曲")
                .items(names, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        Song s = favs.get(which);
                        Community.SongRef r = new Community.SongRef();
                        r.source = s.source;
                        r.sid = s.neteaseId;
                        r.title = s.title == null ? "" : s.title;
                        r.artist = s.artist == null ? "" : s.artist;
                        r.album = s.album == null ? "" : s.album;
                        r.duration = s.durationMs;
                        r.cover = s.coverUrl == null ? "" : s.coverUrl;
                        songs.add(r);
                        refreshLabels();
                    }
                })
                .negative("取消", null)
                .show();
    }

    private void refreshLabels() {
        imgLabel.setText(images.isEmpty() ? "" : "已选 " + images.size() + " 张图");
        if (songs.isEmpty()) {
            songLabel.setText("");
        } else {
            StringBuilder sb = new StringBuilder("引用：");
            for (int i = 0; i < songs.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(songs.get(i).title);
            }
            songLabel.setText(sb.toString());
        }
    }

    private void send(final String title, final String body) {
        // 组装歌曲 JSON
        StringBuilder sj = new StringBuilder("[");
        for (int i = 0; i < songs.size(); i++) {
            Community.SongRef r = songs.get(i);
            if (i > 0) sj.append(',');
            sj.append("{\"source\":").append(r.source)
              .append(",\"sid\":").append(r.sid)
              .append(",\"title\":").append(Json.q(r.title))
              .append(",\"artist\":").append(Json.q(r.artist))
              .append(",\"album\":").append(Json.q(r.album))
              .append(",\"duration\":").append(r.duration)
              .append(",\"cover\":").append(Json.q(r.cover)).append('}');
        }
        sj.append(']');

        StringBuilder ij = new StringBuilder("[");
        for (int i = 0; i < images.size(); i++) {
            if (i > 0) ij.append(',');
            ij.append('"').append(images.get(i)).append('"');
        }
        ij.append(']');

        final android.app.Dialog prog = new MdDialog.Builder(act)
                .title("发布中…")
                .message(images.isEmpty() ? "正在提交" : "正在上传 " + images.size() + " 张图")
                .cancelable(false)
                .show();

        Community.postPublish(act, title, body, sj.toString(), ij.toString(),
                new Community.Callback() {
                    @Override public void onResult(final Object data, final String error) {
                        act.runOnUiThread(new Runnable() { @Override public void run() {
                            try { prog.dismiss(); } catch (Throwable ignored) {}
                            if (error != null) {
                                Toast.makeText(act, error, Toast.LENGTH_LONG).show();
                                return;
                            }
                            long id = Json.lng(data, "id");
                            Toast.makeText(act, "已发布", Toast.LENGTH_SHORT).show();
                            if (cb != null) cb.posted(id);
                        }});
                    }
                });
    }

    private TextView chip(Context c, String s) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(12.5f);
        tv.setTypeface(Ui.tfMed());
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Theme.t().primary);
        tv.setPadding(Ui.px(c, 12), Ui.px(c, 8), Ui.px(c, 12), Ui.px(c, 8));
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setColor(Hct.withAlpha(Theme.t().primary, 0.12f));
        d.setCornerRadius(Ui.px(c, 14));
        tv.setBackground(d);
        Ui.pressable(tv);
        return tv;
    }
}
