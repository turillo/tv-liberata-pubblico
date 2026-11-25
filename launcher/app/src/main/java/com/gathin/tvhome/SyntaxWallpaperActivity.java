package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV's wallpaper screen: an archive as a grid of thumbnails. OK switches a photo on or off for the
 * rotation (off ones are dimmed); a long OK or the Menu key opens Preview, Favourite and Delete. Delete always asks
 * first, then removes the photo and its thumbnail and blacklists it so the download never brings it back.
 * Opened from Wallpaper it shows the general archive; from the Natale menu ({@link #EXTRA_XMAS}) the Christmas
 * archive, with its own interval and sources.
 */
public final class SyntaxWallpaperActivity extends Activity {

    private static final int COLUMNS = 5;
    /** Intent extra: true for the Christmas archive. */
    static final String EXTRA_XMAS = "xmas";
    private boolean xmas;

    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** About 60 thumbnails (115 KB each). */
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(Device.lite(this) ? 3 << 20 : 7 << 20) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private File[] photos = new File[0];
    /** The whole archive; {@link #photos} is what the source filter lets through. */
    private File[] allPhotos = new File[0];
    private String filter = "all";
    private final java.util.Map<String, TextView> filterChips = new java.util.LinkedHashMap<>();
    private List<String> ids;
    private WallpaperState state;
    private TextView counter;
    private TextView[] intervalChips;
    private TextView onWake;
    private TextView useChip;
    private RecyclerView grid;
    private Adapter adapter;
    private boolean opened;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        xmas = getIntent().getBooleanExtra(EXTRA_XMAS, false);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(xmas ? R.string.xmas_wallpapers_title : R.string.syntax_wallpaper), null);
        counter = SyntaxUi.text(this, 15, R.string.font_light, SyntaxUi.MUTED);
        counter.setPadding(0, SyntaxUi.dp(this, 8), 0, 0);
        column.addView(counter);

        LinearLayout bar = SyntaxUi.chipRow(this);
        bar.setPadding(0, SyntaxUi.dp(this, 20), 0, SyntaxUi.dp(this, 14));
        if (xmas) {
            // The Christmas wallpapers on Home (with the Christmas mode on), or not.
            useChip = SyntaxUi.chip(this, "");
            useChip.setOnClickListener(v -> {
                SyntaxSettings s = SyntaxPrefs.get(this);
                s.setXmasSwitch(SyntaxSettings.XMAS_WALLPAPERS, !s.xmasSwitch(SyntaxSettings.XMAS_WALLPAPERS));
                showUse();
            });
            SyntaxUi.addChip(bar, useChip);
            SyntaxUi.toggle(useChip);
            showUse();
        }
        TextView all = SyntaxUi.chip(this, getString(R.string.syntax_all));
        TextView none = SyntaxUi.chip(this, getString(R.string.syntax_none));
        all.setOnClickListener(v -> setAll(true));
        none.setOnClickListener(v -> setAll(false));
        SyntaxUi.addChip(bar, all);
        SyntaxUi.addChip(bar, none);
        TextView every = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.MUTED);
        every.setText(R.string.syntax_rotation);
        every.setPadding(SyntaxUi.dp(this, 28), 0, SyntaxUi.dp(this, 14), 0);
        bar.addView(every);
        String[] labels = {getString(R.string.syntax_15m), getString(R.string.syntax_30m), getString(R.string.syntax_1h)};
        intervalChips = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            int minutes = SyntaxSettings.WALLPAPER_MINUTES[i];
            intervalChips[i] = SyntaxUi.chip(this, labels[i]);
            intervalChips[i].setOnClickListener(v -> {
                if (xmas) SyntaxPrefs.get(this).setXmasWallpaperMinutes(minutes);
                else SyntaxPrefs.get(this).setWallpaperMinutes(minutes);
                showInterval();
            });
            SyntaxUi.addChip(bar, intervalChips[i]);
        }
        onWake = SyntaxUi.chip(this, "");
        onWake.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setWallpaperOnWake(!s.wallpaperOnWake());
            showInterval();
        });
        SyntaxUi.addChip(bar, onWake);
        SyntaxUi.toggle(onWake);
        SyntaxUi.group(every, intervalChips);
        column.addView(bar);

        // Sources, and the filter: all photos or one source's.
        LinearLayout filters = SyntaxUi.chipRow(this);
        filters.setPadding(0, 0, 0, SyntaxUi.dp(this, 10));
        TextView sources = SyntaxUi.chip(this, getString(R.string.syntax_sources) + "  ›");
        sources.setTextSize(14);
        sources.setPadding(SyntaxUi.dp(this, 16), SyntaxUi.dp(this, 8), SyntaxUi.dp(this, 16), SyntaxUi.dp(this, 8));
        sources.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxSourcesActivity.class)
                .putExtra(EXTRA_XMAS, xmas)));
        SyntaxUi.addChip(filters, sources);
        TextView show = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.MUTED);
        show.setText(R.string.syntax_show);
        show.setPadding(SyntaxUi.dp(this, 14), 0, SyntaxUi.dp(this, 14), 0);
        filters.addView(show);
        java.util.List<String> keys = new java.util.ArrayList<>();
        keys.add("all");
        keys.addAll(xmas ? SyntaxSettings.XMAS_SOURCES : WallpaperSources.ALL);
        for (String key : keys) {
            TextView chip = SyntaxUi.chip(this, "all".equals(key) ? getString(R.string.syntax_all) : sourceName(key));
            // Smaller than the other chips, so all six fit on one line.
            chip.setTextSize(14);
            chip.setPadding(SyntaxUi.dp(this, 14), SyntaxUi.dp(this, 8), SyntaxUi.dp(this, 14), SyntaxUi.dp(this, 8));
            chip.setOnClickListener(v -> {
                filter = key;
                applyFilter();
            });
            filterChips.put(key, chip);
            SyntaxUi.addChip(filters, chip);
        }
        SyntaxUi.group(show, filterChips.values().toArray(new TextView[0]));
        column.addView(filters);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        int pad = SyntaxUi.dp(this, 10);
        grid.setPadding(pad, pad, pad, SyntaxUi.dp(this, 40));
        grid.setItemAnimator(null);
        adapter = new Adapter();
        grid.setAdapter(adapter);
        column.addView(grid, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(outer);
        reload();
        showInterval();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void reload() {
        loader.execute(() -> {
            WallpaperLibrary.migrate(this);
            File[] list = WallpaperLibrary.photos(this, xmas);
            WallpaperState s = WallpaperLibrary.load(this, xmas);
            handler.post(() -> {
                if (isFinishing()) return;
                allPhotos = list;
                state = s;
                applyFilter();
                // The first photo takes the focus on opening (the D-pad goes up to the buttons).
                if (photos.length > 0 && !opened) grid.post(() -> {
                    RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
                    if (first != null) {
                        first.itemView.requestFocus();
                        opened = true;
                    }
                });
            });
        });
    }

    /** The grid shows the filter's photos; the chips say which filter is on. */
    private void applyFilter() {
        List<String> all = WallpaperLibrary.ids(allPhotos);
        List<String> keep = WallpaperSources.filter(all, filter);
        File[] shown = new File[keep.size()];
        int n = 0;
        for (File f : allPhotos) if (keep.contains(WallpaperLibrary.id(f))) shown[n++] = f;
        photos = shown;
        ids = keep;
        for (java.util.Map.Entry<String, TextView> e : filterChips.entrySet()) e.getValue().setSelected(e.getKey().equals(filter));
        adapter.notifyDataSetChanged();
        showCounter();
    }

    static String sourceName(String source) {
        switch (source) {
            case WallpaperSources.WIKIMEDIA: return "Wikimedia";
            case WallpaperSources.OLD_APOD: return "NASA APOD";   // photos kept from before
            case WallpaperSources.UNSPLASH: return "Unsplash";
            case WallpaperSources.OLD_PEXELS: return "Pexels";   // photos kept from before
            default: return "Bing";
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        reload();   // back from Sorgenti: new photos may have arrived
    }

    private void showCounter() {
        counter.setText(photos.length == 0 ? getString(xmas ? R.string.xmas_wallpapers_empty : R.string.syntax_wallpaper_empty)
                : getString(R.string.syntax_counter, state.activeCount(ids), photos.length));
    }

    private void showInterval() {
        SyntaxSettings settings = SyntaxPrefs.get(this);
        int minutes = xmas ? settings.xmasWallpaperMinutes() : settings.wallpaperMinutes();
        for (int i = 0; i < intervalChips.length; i++) intervalChips[i].setSelected(SyntaxSettings.WALLPAPER_MINUTES[i] == minutes);
        boolean wake = settings.wallpaperOnWake();
        onWake.setSelected(wake);
        onWake.setText((wake ? "✓ " : "") + getString(R.string.syntax_wallpaper_on_wake));
    }

    private void showUse() {
        boolean on = SyntaxPrefs.get(this).xmasSwitch(SyntaxSettings.XMAS_WALLPAPERS);
        useChip.setSelected(on);
        useChip.setText((on ? "✓ " : "") + getString(R.string.xmas_wallpapers_use));
    }

    private void setAll(boolean on) {
        if (state == null) return;
        state.setAllActive(ids, on);
        save();
        adapter.notifyDataSetChanged();
    }

    private void save() {
        WallpaperState snapshot = WallpaperState.fromJson(state.toJson());
        loader.execute(() -> {
            if (!WallpaperLibrary.save(this, xmas, snapshot)) handler.post(() -> SyntaxDialog.message(this,
                    getString(R.string.syntax_error), getString(R.string.syntax_not_saved)));
        });
        showCounter();
    }

    // ---- per photo ----------------------------------------------------------------------------------------

    private void toggle(int position) {
        String id = ids.get(position);
        state.setActive(id, !state.active(id));
        save();
        adapter.notifyItemChanged(position);
    }

    private void menu(int position) {
        String id = ids.get(position);
        boolean favorite = state.favorite(id);
        SyntaxDialog.show(this, title(id), null, new String[]{getString(R.string.syntax_preview),
                getString(favorite ? R.string.syntax_unfavorite : R.string.syntax_favorite),
                getString(R.string.syntax_delete)}, 0, which -> {
            if (which == 0) preview(position);
            else if (which == 1) {
                state.setFavorite(id, !favorite);
                save();
                adapter.notifyItemChanged(position);
            } else confirmDelete(position);
        });
    }

    private void confirmDelete(int position) {
        String id = ids.get(position);
        // "No" is focused first: deleting takes a deliberate move to "Sì".
        SyntaxDialog.show(this, getString(R.string.syntax_delete_question), title(id),
                new String[]{getString(R.string.syntax_yes), getString(R.string.syntax_no)}, 1, which -> {
                    if (which != 0) return;
                    thumbs.remove(id);
                    loader.execute(() -> {
                        boolean ok = WallpaperLibrary.delete(this, xmas, id);
                        handler.post(() -> {
                            if (!ok) SyntaxDialog.message(this, getString(R.string.syntax_error),
                                    getString(R.string.syntax_not_deleted));
                            reload();
                        });
                    });
                });
    }

    private String title(String id) {
        String t = state == null ? null : state.titles.get(id);
        return t != null && !t.isEmpty() ? t : WallpaperLibrary.fallbackTitle(id);
    }

    /** The photo full screen with its Bing title and description; Back closes it. */
    private void preview(int position) {
        File photo = photos[position];
        String id = ids.get(position);
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout caption = new LinearLayout(this);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        int pad = SyntaxUi.dp(this, 56);
        caption.setPadding(pad, SyntaxUi.dp(this, 80), pad, SyntaxUi.dp(this, 40));
        TextView from = SyntaxUi.text(this, 13, R.string.font_regular, SyntaxUi.ACCENT);
        from.setText(sourceName(WallpaperSources.sourceOf(id)).toUpperCase(java.util.Locale.ROOT));
        from.setLetterSpacing(0.12f);
        from.setPadding(0, 0, 0, SyntaxUi.dp(this, 8));
        caption.addView(from);
        TextView t = SyntaxUi.text(this, 28, R.string.font_medium, SyntaxUi.TEXT);
        t.setText(title(id));
        t.setMaxLines(2);
        caption.addView(t);
        String credit = state.credits.get(id);
        if (credit != null && !credit.isEmpty()) {
            TextView c = SyntaxUi.text(this, 15, R.string.font_light, 0xCCFFFFFF);
            c.setText(credit);
            c.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
            caption.addView(c);
        }
        frame.addView(caption, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        d.setContentView(frame);
        Window w = d.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        d.show();
        DisplayMetrics m = getResources().getDisplayMetrics();
        loader.execute(() -> {
            Bitmap b = decodeScreen(photo, m.widthPixels, m.heightPixels);
            handler.post(() -> {
                if (!d.isShowing()) return;
                if (b != null) image.setImageBitmap(b);
                else t.setText(R.string.syntax_unreadable);
            });
        });
    }

    private static Bitmap decodeScreen(File f, int w, int h) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- grid ----------------------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(parent);
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            h.bind(position);
        }

        @Override
        public int getItemCount() {
            return photos.length;
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View shade;
        final TextView star, off, source;
        String bound;

        Holder(ViewGroup parent) {
            super(new FrameLayout(parent.getContext()));
            FrameLayout cell = (FrameLayout) itemView;
            int w = (getResources().getDisplayMetrics().widthPixels - SyntaxUi.dp(SyntaxWallpaperActivity.this, 128 + 20))
                    / COLUMNS - SyntaxUi.dp(SyntaxWallpaperActivity.this, 16);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(w, w * 9 / 16);
            int m = SyntaxUi.dp(SyntaxWallpaperActivity.this, 8);
            lp.setMargins(m, m, m, m);
            cell.setLayoutParams(lp);
            cell.setBackground(SyntaxUi.cardBackground(SyntaxWallpaperActivity.this, 12, 0xFF0B1018));
            int inset = SyntaxUi.dp(SyntaxWallpaperActivity.this, 3);
            cell.setPadding(inset, inset, inset, inset);
            SyntaxUi.focusable(cell, 1.08f);
            image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setBackground(SyntaxUi.rounded(SyntaxWallpaperActivity.this, 9, 0xFF0B1018, 0, 0));
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            shade = new View(parent.getContext());
            shade.setBackground(SyntaxUi.rounded(SyntaxWallpaperActivity.this, 9, 0xB3000000, 0, 0));
            cell.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            off = SyntaxUi.text(parent.getContext(), 13, R.string.font_regular, 0xCCFFFFFF);
            off.setText(R.string.syntax_off_label);
            cell.addView(off, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            star = SyntaxUi.text(parent.getContext(), 22, R.string.font_medium, SyntaxUi.STAR);
            star.setText("★");
            star.setShadowLayer(6, 0, 1, 0xCC000000);
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
            sl.setMargins(0, m / 2, m, 0);
            cell.addView(star, sl);
            source = SyntaxUi.text(parent.getContext(), 10, R.string.font_medium, 0xE6FFFFFF);
            source.setLetterSpacing(0.08f);
            source.setPadding(m, m / 4, m, m / 4);
            source.setBackground(SyntaxUi.rounded(SyntaxWallpaperActivity.this, 6, 0xB3000000, 0, 0));
            FrameLayout.LayoutParams srcl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
            srcl.setMargins(m, 0, 0, m);
            cell.addView(source, srcl);
            cell.setOnClickListener(v -> {
                int p = getAdapterPosition();
                if (p != RecyclerView.NO_POSITION) toggle(p);
            });
            cell.setOnLongClickListener(v -> {
                int p = getAdapterPosition();
                if (p != RecyclerView.NO_POSITION) menu(p);
                return true;
            });
            cell.setOnKeyListener((v, code, event) -> {
                if (code != KeyEvent.KEYCODE_MENU || event.getAction() != KeyEvent.ACTION_UP) return code == KeyEvent.KEYCODE_MENU;
                int p = getAdapterPosition();
                if (p != RecyclerView.NO_POSITION) menu(p);
                return true;
            });
        }

        void bind(int position) {
            String id = ids.get(position);
            boolean active = state.active(id);
            shade.setVisibility(active ? View.GONE : View.VISIBLE);
            off.setVisibility(active ? View.GONE : View.VISIBLE);
            star.setVisibility(state.favorite(id) ? View.VISIBLE : View.GONE);
            source.setText(sourceName(WallpaperSources.sourceOf(id)).toUpperCase(java.util.Locale.ROOT));
            itemView.setContentDescription(title(id));
            if (id.equals(bound) && image.getDrawable() != null) return;
            bound = id;
            Bitmap cached = thumbs.get(id);
            image.setImageBitmap(cached);
            if (cached != null) return;
            File photo = photos[position];
            loader.execute(() -> {
                Bitmap b = WallpaperLibrary.thumbnail(SyntaxWallpaperActivity.this, photo);
                handler.post(() -> {
                    if (b != null) thumbs.put(id, b);
                    if (id.equals(bound)) image.setImageBitmap(b);
                });
            });
        }
    }
}
