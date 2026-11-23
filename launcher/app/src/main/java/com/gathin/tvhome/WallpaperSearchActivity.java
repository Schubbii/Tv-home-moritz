package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Search 4K wallpapers": type a term on the TV (e.g. "autumn forest"), get only 3840x2160-or-larger 16:9 pictures
 * from Wallhaven as a grid of previews, press OK on one to download it into eigene/ and make it the wallpaper.
 * Uses the network only while this screen is open.
 */
public final class WallpaperSearchActivity extends Activity {
    private static final int COLUMNS = 4;
    private static final int MAX_DOWNLOAD = 40 * 1024 * 1024;
    /** Files up to this size are kept as they are; bigger ones are re-encoded to a 3840x2160 JPEG. */
    private static final long KEEP_AS_IS = 9L * 1024 * 1024;

    private final List<Wallhaven.Result> results = new ArrayList<>();
    private final ExecutorService api = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private EditText query;
    private TextView status;
    private GridView grid;
    private BaseAdapter adapter;
    private final TextView[] categoryViews = new TextView[3];
    private final boolean[] categories = {true, true, false};   // general, anime, people
    private String currentQuery = "";
    private int page = 0, lastPage = 1;
    private boolean loading, downloading, failed;
    private int generation;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float dp = metrics.density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF05070C);
        int pad = Math.round(36 * dp);
        root.setPadding(pad, Math.round(28 * dp), pad, Math.round(12 * dp));

        TextView title = label(getString(R.string.search_title), 26, 0xFFFFFFFF);
        root.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, Math.round(14 * dp), 0, Math.round(10 * dp));
        query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(R.string.search_hint);
        query.setTextColor(Color.WHITE);
        query.setHintTextColor(0x80FFFFFF);
        query.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        GradientDrawable field = new GradientDrawable();
        field.setColor(0xFF161B24);
        field.setCornerRadius(12 * dp);
        field.setStroke(Math.round(2 * dp), 0x40FFFFFF);
        query.setBackground(field);
        int fp = Math.round(14 * dp);
        query.setPadding(fp, fp / 2, fp, fp / 2);
        query.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                search();
                return true;
            }
            return false;
        });
        bar.addView(query, new LinearLayout.LayoutParams(0, Math.round(56 * dp), 1f));
        bar.addView(button(getString(R.string.search_button), v -> search(), dp));
        String[] names = {getString(R.string.search_cat_general), getString(R.string.search_cat_anime),
                getString(R.string.search_cat_people)};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView chip = button(names[i], v -> {
                categories[index] = !categories[index];
                if (!categories[0] && !categories[1] && !categories[2]) categories[index] = true;
                refreshChips();
            }, dp);
            categoryViews[i] = chip;
            bar.addView(chip);
        }
        root.addView(bar);

        status = label(getString(R.string.search_start), 16, 0xB3FFFFFF);
        status.setGravity(Gravity.START);
        status.setPadding(0, 0, 0, Math.round(10 * dp));
        root.addView(status);

        int gap = Math.round(14 * dp);
        int tileWidth = (metrics.widthPixels - 2 * pad - (COLUMNS - 1) * gap) / COLUMNS;
        int tileHeight = tileWidth * 9 / 16;
        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setClipToPadding(false);
        grid.setPadding(0, Math.round(6 * dp), 0, Math.round(24 * dp));
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(Color.TRANSPARENT);
        focus.setStroke(Math.round(3 * dp), Color.WHITE);
        focus.setCornerRadius(10 * dp);
        grid.setSelector(focus);
        grid.setDrawSelectorOnTop(true);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return results.size();
            }

            @Override
            public Object getItem(int position) {
                return results.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout tile = (LinearLayout) convertView;
                if (tile == null) tile = makeTile(tileHeight, dp);
                Wallhaven.Result item = results.get(position);
                ImageView image = (ImageView) tile.getChildAt(0);
                TextView text = (TextView) tile.getChildAt(1);
                text.setText(item.resolution + (item.fileSize > 0
                        ? String.format(Locale.ROOT, "  ·  %.1f MB", item.fileSize / 1048576f) : ""));
                image.setTag(item.thumb);
                Bitmap cached = thumbs.get(item.thumb);
                image.setImageBitmap(cached);
                if (cached == null && !item.thumb.isEmpty()) loadThumb(item.thumb, image);
                if (position >= results.size() - COLUMNS * 2) loadMore();
                return tile;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> download(results.get(position)));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refreshChips();
        query.requestFocus();
    }

    @Override
    protected void onDestroy() {
        api.shutdownNow();
        images.shutdownNow();
        thumbs.evictAll();
        super.onDestroy();
    }

    private TextView label(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private TextView button(String value, View.OnClickListener click, float dp) {
        TextView view = new TextView(this, null, 0, R.style.SettingsRow);
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(52 * dp));
        lp.setMarginStart(Math.round(12 * dp));
        view.setLayoutParams(lp);
        view.setOnClickListener(click);
        return view;
    }

    private void refreshChips() {
        for (int i = 0; i < categoryViews.length; i++) {
            categoryViews[i].setSelected(categories[i]);
            String name = categoryViews[i].getText().toString().replace("✓ ", "");
            categoryViews[i].setText(categories[i] ? "✓ " + name : name);
        }
    }

    private LinearLayout makeTile(int tileHeight, float dp) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int inset = Math.round(4 * dp);
        tile.setPadding(inset, inset, inset, inset);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF15181E);
        placeholder.setCornerRadius(6 * dp);
        image.setBackground(placeholder);
        image.setClipToOutline(true);
        tile.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeight));
        TextView text = label("", 13, 0xCCFFFFFF);
        text.setGravity(Gravity.CENTER_HORIZONTAL);
        text.setPadding(0, Math.round(6 * dp), 0, 0);
        tile.addView(text);
        return tile;
    }

    private String categoryCode() {
        return (categories[0] ? "1" : "0") + (categories[1] ? "1" : "0") + (categories[2] ? "1" : "0");
    }

    private void search() {
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(query.getWindowToken(), 0);
        currentQuery = query.getText().toString().trim();
        generation++;
        results.clear();
        adapter.notifyDataSetChanged();
        page = 0;
        lastPage = 1;
        loading = false;
        failed = false;
        loadMore();
    }

    private void loadMore() {
        if (loading || failed || page >= lastPage || api.isShutdown()) return;
        loading = true;
        int next = page + 1;
        int gen = generation;
        String url = Wallhaven.url(currentQuery, categoryCode(), next);
        status.setText(R.string.search_loading);
        api.execute(() -> {
            try {
                Wallhaven.Page result = Wallhaven.parse(new String(fetch(url, 2 * 1024 * 1024),
                        StandardCharsets.UTF_8));
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    loading = false;
                    page = result.current;
                    lastPage = result.last;
                    boolean first = results.isEmpty();
                    results.addAll(result.results);
                    adapter.notifyDataSetChanged();
                    status.setText(results.isEmpty() ? getString(R.string.search_none)
                            : getString(R.string.search_count, results.size()));
                    if (first && !results.isEmpty()) {
                        grid.setSelection(0);
                        grid.requestFocus();
                    }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (gen != generation) return;
                    loading = false;
                    failed = true;   // no automatic retries; the next search starts over
                    status.setText(getString(R.string.search_failed, String.valueOf(failure.getMessage())));
                });
            }
        });
    }

    private void loadThumb(String url, ImageView target) {
        if (images.isShutdown()) return;
        images.execute(() -> {
            try {
                byte[] data = fetch(url, 2 * 1024 * 1024);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (bitmap == null) return;
                target.post(() -> {
                    thumbs.put(url, bitmap);
                    if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                });
            } catch (Exception ignored) {
                // the tile keeps its placeholder
            }
        });
    }

    private void download(Wallhaven.Result item) {
        if (downloading || images.isShutdown()) return;
        downloading = true;
        status.setText(getString(R.string.search_downloading, item.resolution));
        images.execute(() -> {
            try {
                byte[] data = fetch(item.path, MAX_DOWNLOAD);
                String name = save(item, data);
                runOnUiThread(() -> {
                    downloading = false;
                    SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name)
                            .putLong(Config.SETTINGS_REVISION_KEY,
                                    choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                    setResult(RESULT_OK);
                    status.setText(R.string.search_saved);
                    Toast.makeText(this, R.string.search_saved, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    downloading = false;
                    status.setText(getString(R.string.search_failed, String.valueOf(failure.getMessage())));
                });
            }
        });
    }

    /**
     * Keeps a JPEG or PNG up to 9 MB as downloaded (the launcher scales it to the screen when it shows it); anything
     * larger is decoded at about 4K in RGB_565 and written as a JPEG, so the wallpaper list's 10 MB limit holds.
     */
    private String save(Wallhaven.Result item, byte[] data) throws IOException {
        File dir = WallpaperUploadActivity.ownDir(this);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) throw new IOException("Speicher nicht verfügbar");
        String id = item.id.replaceAll("[^A-Za-z0-9]", "");
        boolean png = item.path.toLowerCase(Locale.ROOT).endsWith(".png");
        if (data.length <= KEEP_AS_IS) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0) throw new IOException("Kein gültiges Bild");
            String name = "4k-" + id + (png ? ".png" : ".jpg");
            try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
                out.write(data);
            }
            return name;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0) throw new IOException("Kein gültiges Bild");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= 3840 && bounds.outHeight / (sample * 2) >= 2160) sample *= 2;
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("Bild konnte nicht gelesen werden");
        if ((long) bitmap.getWidth() * bitmap.getHeight() > WallpaperSize.MAX_PIXELS) {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 3840,
                    Math.round(3840f * bitmap.getHeight() / bitmap.getWidth()), true);
            bitmap.recycle();
            bitmap = scaled;
        }
        String name = "4k-" + id + ".jpg";
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } finally {
            bitmap.recycle();
        }
        return name;
    }

    private static byte[] fetch(String address, int limit) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TvHome");
        try {
            int code = connection.getResponseCode();
            if (code == 429) throw new IOException("Zu viele Anfragen, kurz warten");
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > limit) throw new IOException("Datei zu groß");
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
