package com.qusic.app;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页：本地曲库 / 酷我 / 酷狗 / 网易云，用分段控件切换。
 *
 * <p>在线模式统一是「搜索 → 点结果时解析直链 → 播放」。
 * 拿不到直链时会明确区分原因（需要付费 / 该曲无版权 / 网络问题），而不是静默失败。
 */
public class SearchPage {

    // 0=本地 1=酷我 2=网易云
    private static final int SRC_LOCAL = 0, SRC_KUWO = 1, SRC_NETEASE = 2;

    private final MainActivity act;
    private View root;
    private SongListView listView;
    private EditText input;
    private TextView hint;
    private LibraryPage.SegmentedBar srcBar;
    private LinearLayout chips;

    private String query = "";
    private int source = SRC_LOCAL;
    private boolean loading;
    private List<Song> onlineResults = new ArrayList<>();

    public SearchPage(MainActivity a) {
        this.act = a;
        build();
    }

    public View view() { return root; }

    private void build() {
        Tokens t = Theme.t();
        Context c = act;

        FrameLayout wrap = new FrameLayout(c);
        wrap.setBackgroundColor(t.surface);
        root = wrap;

        LinearLayout col = Ui.column(c);
        wrap.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int pad = Ui.px(c, 20);
        LinearLayout header = Ui.column(c);
        header.setPadding(pad, Ui.statusBarHeight(c) + Ui.px(c, 22), pad, 0);

        TextView title = new TextView(c);
        title.setText("搜索");
        title.setTextColor(t.onSurface);
        title.setTextSize(30);
        title.setTypeface(Ui.tfBlack());
        header.addView(title);

        // 来源切换
        srcBar = new LibraryPage.SegmentedBar(c, new String[]{"本地", "酷我", "网易云"});
        srcBar.setOnChange(new LibraryPage.SegmentedBar.OnChange() {
            @Override public void onChange(int i) {
                if (source == i) return;
                source = i;
                onlineResults.clear();
                doSearch();
            }
        });
        LinearLayout.LayoutParams slp0 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(c, 44));
        slp0.topMargin = Ui.px(c, 12);
        header.addView(srcBar, slp0);

        // 搜索框
        LinearLayout searchBox = Ui.row(c);
        searchBox.setPadding(Ui.px(c, 14), 0, Ui.px(c, 14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.surfaceContainerHigh);
        bg.setCornerRadius(Ui.px(c, 26));
        bg.setStroke(Ui.px(c, 1), Hct.withAlpha(t.outline, 0.5f));
        searchBox.setBackground(bg);

        HomePage.IconView icon = new HomePage.IconView(c, "search",
                Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        searchBox.addView(icon, new LinearLayout.LayoutParams(Ui.px(c, 19), Ui.px(c, 19)));

        input = new EditText(c);
        input.setHint("歌名、歌手或专辑");
        input.setHintTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.6f));
        input.setTextColor(t.onSurface);
        input.setTextSize(14.5f);
        input.setBackground(null);
        input.setSingleLine(true);
        input.setPadding(Ui.px(c, 10), Ui.px(c, 12), 0, Ui.px(c, 12));
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) {
                query = s.toString().trim();
                if (source == SRC_LOCAL) doSearch();   // 本地实时过滤
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent e) {
                if (source != SRC_LOCAL) { hideIme(); doSearch(); return true; }
                return false;
            }
        });
        searchBox.addView(input, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 在线模式的「搜索」按钮
        final TextView goBtn = new TextView(c);
        goBtn.setText("搜索");
        goBtn.setTextSize(12.5f);
        goBtn.setTypeface(Ui.tfBold());
        goBtn.setTextColor(t.onPrimary);
        goBtn.setPadding(Ui.px(c, 13), Ui.px(c, 7), Ui.px(c, 13), Ui.px(c, 7));
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(t.primary);
        gb.setCornerRadius(Ui.px(c, 20));
        goBtn.setBackground(gb);
        goBtn.setVisibility(View.GONE);
        goBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Ui.hapticLight(v); hideIme(); doSearch(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = Ui.px(c, 8);
        searchBox.addView(goBtn, glp);
        this.goBtn = goBtn;

        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.px(c, 50));
        sbLp.topMargin = Ui.px(c, 12);
        header.addView(searchBox, sbLp);

        hint = new TextView(c);
        hint.setTextColor(Hct.withAlpha(t.onSurfaceVariant, 0.85f));
        hint.setTextSize(12.5f);
        hint.setPadding(0, Ui.px(c, 12), 0, Ui.px(c, 4));
        header.addView(hint);

        col.addView(header);

        listView = new SongListView(c);
        listView.setOnPick(new SongListView.OnPick() {
            @Override public void onPick(List<Song> visible, int index) {
                if (source != SRC_LOCAL) playOnline(visible, index);
                else {
                    PlayerService s = PlayerService.instance();
                    if (s != null) s.playList(visible, index);
                }
            }
        });
        col.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        updateSourceUi();
        doSearch();
    }

    private TextView goBtn;

    private void updateSourceUi() {
        boolean online = source != SRC_LOCAL;
        goBtn.setVisibility(online ? View.VISIBLE : View.GONE);
        if (source == SRC_KUWO) input.setHint("搜酷我的歌，回车或点搜索");
        else if (source == SRC_NETEASE) input.setHint("搜网易云的歌，回车或点搜索");
        else input.setHint("歌名、歌手或专辑");
    }

    /** 当前在线来源的名字 */
    private String sourceName() {
        return Online.sourceName(source == SRC_KUWO
                ? Song.SOURCE_KUWO : Song.SOURCE_NETEASE);
    }

    /** 按来源分发搜索 */
    private void searchOnline(String kw) {
        Online.SearchCallback cb = new Online.SearchCallback() {
            @Override public void onResult(List<Song> songs, String error) {
                if (error != null) hint.setText(error);
                else hint.setText(sourceName() + "找到 " + songs.size() + " 首 · 点一下就开始播放");
                listView.setData(songs);
            }
        };
        if (source == SRC_KUWO) Kuwo.search(act, kw, cb);
        else NetEase.search(act, kw, cb);
    }

    /** 按来源解析播放地址 */
    private void resolveOnline(final Song s, final List<Song> visible, final int index) {
        Online.UrlCallback cb = new Online.UrlCallback() {
            @Override public void onResult(String url, String error) {
                loading = false;
                if (url != null) {
                    hint.setText("正在播放：" + s.title);
                    PlayerService ps = PlayerService.instance();
                    if (ps != null) ps.playList(visible, index);
                } else {
                    hint.setText(error == null ? "这首歌暂时放不了" : error);
                    android.widget.Toast.makeText(act,
                            error == null ? "这首歌暂时放不了" : error,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        };
        if (source == SRC_KUWO) Kuwo.resolveUrl(act, s, cb);
        else NetEase.resolveUrl(act, s, cb);
    }

    private void hideIme() {
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    /** 在线播放：先解析直链，再交给播放服务 */
    private void playOnline(final List<Song> visible, final int index) {
        if (loading) return;
        final Song s = visible.get(index);
        if (s.streamUrl != null && s.streamUrl.length() > 0) {
            PlayerService ps = PlayerService.instance();
            if (ps != null) ps.playList(visible, index);
            return;
        }
        loading = true;
        hint.setText("正在解析播放地址…");
        resolveOnline(s, visible, index);
    }

    private void doSearch() {
        if (listView == null) return;
        updateSourceUi();

        if (source != SRC_LOCAL) {
            if (query.length() == 0) {
                hint.setText("输入关键字，搜索" + sourceName() + "上的歌");
                listView.setHighlight(null);
                listView.setData(new ArrayList<Song>());
                return;
            }
            hint.setText("正在搜索「" + query + "」…");
            listView.setHighlight(null);
            listView.setData(new ArrayList<Song>());
            searchOnline(query);
            return;
        }

        // 本地
        int total = Library.count();
        if (total == 0) {
            hint.setText("曲库是空的，先去「曲库」页导入音乐");
            listView.setHighlight(null);
            listView.setData(new ArrayList<Song>());
            return;
        }
        if (query.length() == 0) {
            hint.setText("输入关键字开始搜索 · 本地共 " + total + " 首");
            listView.setHighlight(null);
            listView.setData(new ArrayList<Song>());
            return;
        }
        List<Song> res = Library.search(query);
        hint.setText("本地找到 " + res.size() + " 条结果");
        listView.setHighlight(query);
        listView.setData(res);
    }

    public void refresh() { doSearch(); }

    public void focusInput() {
        if (input == null) return;
        input.requestFocus();
        InputMethodManager im = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }
}
