package com.qusic.app;

import java.util.List;

/** 在线音源的共用回调类型（酷我 / 网易云 都实现同一套）。 */
public final class Online {

    public interface SearchCallback { void onResult(List<Song> songs, String error); }
    public interface UrlCallback { void onResult(String url, String error); }

    /** 当前默认的在线音源：酷我（零签名、免登录、免费 320k） */
    public static final int DEFAULT = Song.SOURCE_KUWO;

    private Online() {}
}
