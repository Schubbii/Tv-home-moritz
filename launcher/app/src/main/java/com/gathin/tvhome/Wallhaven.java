package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Wallhaven's public search API (https://wallhaven.cc/help/api), used without an API key: only safe-for-work
 * results ({@code purity=100}), at least 3840x2160 ({@code atleast}) in 16:9 ({@code ratios=16x9}), so every result
 * is a 4K wallpaper. No key, no account; the site allows about 45 calls a minute.
 */
final class Wallhaven {
    static final String API = "https://wallhaven.cc/api/v1/search";

    /** One search result: the full-size picture, a small preview and its size in pixels. */
    static final class Result {
        final String id, path, thumb, resolution, fileType;
        final int width, height;
        final long fileSize;

        Result(String id, String path, String thumb, String resolution, String fileType,
               int width, int height, long fileSize) {
            this.id = id;
            this.path = path;
            this.thumb = thumb;
            this.resolution = resolution;
            this.fileType = fileType;
            this.width = width;
            this.height = height;
            this.fileSize = fileSize;
        }
    }

    /** A page of results plus whether there are more pages. */
    static final class Page {
        final List<Result> results;
        final int current, last;

        Page(List<Result> results, int current, int last) {
            this.results = results;
            this.current = current;
            this.last = last;
        }
    }

    private Wallhaven() {}

    /**
     * The search address. {@code categories} is Wallhaven's three-digit switch for general, anime and people
     * ("110" = general and anime). An empty query lists the most popular 4K wallpapers instead.
     */
    static String url(String query, String categories, int page) {
        String q = query == null ? "" : query.trim();
        StringBuilder url = new StringBuilder(API).append("?purity=100")
                .append("&categories=").append(categories)
                .append("&atleast=3840x2160&ratios=16x9")
                .append("&sorting=").append(q.isEmpty() ? "toplist" : "relevance")
                .append("&page=").append(Math.max(1, page));
        if (!q.isEmpty()) url.append("&q=").append(encode(q));
        return url.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            return value;
        }
    }

    /** Parses a search answer; results smaller than 3840x2160 (should the filter ever let one through) are dropped. */
    static Page parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray data = root.optJSONArray("data");
        List<Result> results = new ArrayList<>();
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                int w = item.optInt("dimension_x", 0), h = item.optInt("dimension_y", 0);
                String path = item.optString("path", "");
                if (w < 3840 || h < 2160 || !path.startsWith("https://")) continue;
                JSONObject thumbs = item.optJSONObject("thumbs");
                String thumb = thumbs == null ? "" : thumbs.optString("large", thumbs.optString("small", ""));
                results.add(new Result(item.optString("id", ""), path, thumb,
                        item.optString("resolution", w + "x" + h), item.optString("file_type", ""),
                        w, h, item.optLong("file_size", 0)));
            }
        }
        JSONObject meta = root.optJSONObject("meta");
        int current = meta == null ? 1 : meta.optInt("current_page", 1);
        int last = meta == null ? 1 : meta.optInt("last_page", current);
        return new Page(results, current, last);
    }
}
