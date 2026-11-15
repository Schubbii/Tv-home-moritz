package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallpaper picker as a grid of small previews instead of a list of file names. Thumbnails are decoded off the
 * main thread at about 480 px wide in RGB_565 (about 250 KB each), kept in a small cache while the dialog is open
 * and dropped when it closes. The last tile is "no wallpaper".
 */
final class WallpaperGrid {
    interface Listener {
        /** {@code index} is the picked picture, or {@code images.size()} for no wallpaper. */
        void picked(int index);
    }

    private static final int COLUMNS = 4;
    private static final int THUMB_WIDTH = 480;

    private WallpaperGrid() {}

    static void show(Activity activity, List<File> images, int checked, Listener listener, Runnable themeDefault) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float dp = metrics.density;
        int dialogWidth = Math.round(metrics.widthPixels * 0.9f);
        int pad = Math.round(20 * dp), gap = Math.round(14 * dp);
        int tileWidth = (dialogWidth - 2 * pad - (COLUMNS - 1) * gap - Math.round(48 * dp)) / COLUMNS;
        int tileHeight = tileWidth * 9 / 16;

        ExecutorService decoder = Executors.newFixedThreadPool(2);
        LruCache<Integer, Bitmap> cache = new LruCache<Integer, Bitmap>(16 * 1024 * 1024) {
            @Override
            protected int sizeOf(Integer key, Bitmap value) {
                return value.getByteCount();
            }
        };

        GridView grid = new GridView(activity);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setPadding(pad, pad, pad, pad);
        grid.setClipToPadding(false);
        GradientDrawable focus = new GradientDrawable();
        focus.setColor(Color.TRANSPARENT);
        focus.setStroke(Math.round(3 * dp), Color.WHITE);
        focus.setCornerRadius(10 * dp);
        grid.setSelector(focus);
        grid.setDrawSelectorOnTop(true);

        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return images.size() + 1;
            }

            @Override
            public Object getItem(int position) {
                return position < images.size() ? images.get(position) : null;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout tile = (LinearLayout) convertView;
                if (tile == null) tile = makeTile(activity, tileHeight, dp);
                ImageView image = (ImageView) tile.getChildAt(0);
                TextView label = (TextView) tile.getChildAt(1);
                image.setTag(position);
                boolean none = position >= images.size();
                String name = none ? activity.getString(R.string.wallpaper_none) : title(images.get(position));
                label.setText(position == checked ? "✓ " + name : name);
                Bitmap cached = cache.get(position);
                image.setImageBitmap(cached);
                if (cached == null && !none && !decoder.isShutdown()) {
                    File file = images.get(position);
                    ImageView target = image;
                    decoder.execute(() -> {
                        Bitmap thumb = decode(file);
                        if (thumb == null) return;
                        target.post(() -> {
                            if (decoder.isShutdown()) return;
                            cache.put(position, thumb);
                            if (Integer.valueOf(position).equals(target.getTag())) target.setImageBitmap(thumb);
                        });
                    });
                }
                return tile;
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.wallpaper_label)
                .setView(grid)
                .setNeutralButton(R.string.wallpaper_theme_default, (d, which) -> themeDefault.run())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        grid.setOnItemClickListener((parent, view, position, id) -> {
            dialog.dismiss();
            listener.picked(position);
        });
        dialog.setOnDismissListener(d -> {
            decoder.shutdownNow();
            cache.evictAll();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth, Math.round(metrics.heightPixels * 0.88f));
        }
        grid.setSelection(checked >= 0 && checked <= images.size() ? checked : 0);
        grid.requestFocus();
    }

    private static LinearLayout makeTile(Activity activity, int tileHeight, float dp) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int inset = Math.round(4 * dp);
        tile.setPadding(inset, inset, inset, inset);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable placeholder = new GradientDrawable();
        placeholder.setColor(0xFF15181E);
        placeholder.setCornerRadius(6 * dp);
        image.setBackground(placeholder);
        image.setClipToOutline(true);
        tile.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeight));
        TextView label = new TextView(activity);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTextColor(0xE6FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setPadding(0, Math.round(6 * dp), 0, 0);
        tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return tile;
    }

    /** "natur-04-lake-mystery-new-zealand.jpg" -> "natur 04 lake mystery new zealand". */
    static String title(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.replace('-', ' ').replace('_', ' ');
    }

    private static Bitmap decode(File file) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (options.outWidth / (sample * 2) >= THUMB_WIDTH) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }
}
