package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "Preferite" screensaver scene: a slideshow of the favourite wallpapers (the Christmas ones while the Christmas
 * mode is on), or of the active ones when there is no favourite, with a note in the panel. Each photo fades in and
 * zooms very slowly (Ken Burns); the order is random without the last few; the interval is Syntax TV's (10 to 60 s).
 * A small panel says title, credit and source; switched off in Syntax TV, only the credit Unsplash and Wikimedia
 * Commons ask for stays. Works offline: the photos are the TV's own copies. At most two photos in memory (the one on
 * screen and the next), decoded to screen size with inSampleSize, in RGB_565.
 */
final class FavoritesScene implements DreamScene {

    private static final String TAG = "TvHome.Favorites";
    private static final long FADE_MS = 1_500;
    private static final float ZOOM = 1.07f;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final FavoritesData.Picker picker = new FavoritesData.Picker(random);
    private final ImageView[] views = new ImageView[2];
    private final LinearLayout panel;
    private final View shade;
    private final TextView title, credit, note;
    private final boolean showPanel;
    private final long intervalMs;
    private final int screenW, screenH;
    private final Bitmap[] bitmaps = new Bitmap[2];
    private final android.animation.Animator[] zooms = new android.animation.Animator[2];
    private int front = -1;
    private boolean running;

    /** The next photo decoded ahead, with its texts; null until ready. */
    private Bitmap pending;
    private String[] pendingText;

    private final Runnable change = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (pending != null) show(pending, pendingText);
            pending = null;
            handler.postDelayed(this, intervalMs);
            prepare(false);
        }
    };

    FavoritesScene(Context c, FrameLayout root) {
        context = c;
        SyntaxSettings s = SyntaxPrefs.get(c);
        showPanel = s.favoritesPanel();
        intervalMs = s.favoritesSeconds() * 1000L;
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        screenW = Math.max(m.widthPixels, 640);
        screenH = Math.max(m.heightPixels, 360);
        root.setBackgroundColor(0xFF000000);
        for (int i = 0; i < 2; i++) {
            views[i] = new ImageView(c);
            views[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            views[i].setAlpha(0f);
            root.addView(views[i], ChristmasScenes.full());
        }
        // A soft shade under the panel, so the text reads on a bright photo.
        shade = new View(c);
        shade.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x99000000, 0x00000000}));
        root.addView(shade, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, SyntaxUi.dp(c, 220),
                Gravity.BOTTOM));
        panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.VERTICAL);
        title = SyntaxUi.text(c, 18, R.string.font_regular, 0xE6FFFFFF);
        credit = SyntaxUi.text(c, 12, R.string.font_light, 0xB3FFFFFF);
        note = SyntaxUi.text(c, 11, R.string.font_light, 0x99FFFFFF);
        title.setMaxLines(2);
        credit.setMaxLines(2);
        for (TextView t : new TextView[]{title, credit, note}) {
            t.setShadowLayer(6, 0, 1, 0xCC000000);
            panel.addView(t);
        }
        credit.setPadding(0, SyntaxUi.dp(c, 3), 0, 0);
        note.setPadding(0, SyntaxUi.dp(c, 6), 0, 0);
        int side = SyntaxUi.dp(c, 48);
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(Math.round(screenW * 0.55f),
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        pl.setMargins(side, 0, side, SyntaxUi.dp(c, 36) + DreamInset.bottom);
        root.addView(panel, pl);
        panel.setAlpha(0f);
    }

    @Override
    public void start() {
        if (running) return;
        running = true;
        prepare(true);   // the first photo as soon as it is decoded; then one every interval
    }

    @Override
    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        for (android.animation.Animator z : zooms) if (z != null) z.cancel();
        for (ImageView v : views) {
            v.animate().cancel();
            v.setImageDrawable(null);
        }
        decoder.shutdownNow();
        for (int i = 0; i < 2; i++) {
            if (bitmaps[i] != null) bitmaps[i].recycle();
            bitmaps[i] = null;
        }
        if (pending != null) pending.recycle();
        pending = null;
    }

    /** Decodes the next photo off the main thread (only one waits at a time); the first is shown at once. */
    private void prepare(boolean first) {
        if (decoder.isShutdown()) return;
        decoder.execute(() -> {
            String[] text = new String[4];
            Bitmap b = null;
            try {
                b = next(text);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "photo not decoded: " + e);
            }
            Bitmap ready = b;
            handler.post(() -> {
                if (!running) {
                    if (ready != null) ready.recycle();
                    return;
                }
                if (first) {
                    if (ready == null) showText(text);   // nothing to show yet: the panel says so
                    else show(ready, text);
                    handler.postDelayed(change, intervalMs);
                    if (ready != null) prepare(false);
                    return;
                }
                if (pending != null) pending.recycle();
                pending = ready;
                pendingText = text;
            });
        });
    }

    /** The next photo of the pool, decoded; {@code text} gets title, credit, source and the note. */
    private Bitmap next(String[] text) {
        SyntaxSettings s = SyntaxPrefs.get(context);
        boolean xmas = FavoritesData.useXmas(s.christmas(), WallpaperLibrary.photos(context, true).length);
        File[] photos = WallpaperLibrary.photos(context, xmas);
        WallpaperState state = WallpaperLibrary.load(context, xmas);
        FavoritesData.Pool pool = FavoritesData.pool(WallpaperLibrary.ids(photos), state);
        String id = picker.next(pool.ids);
        if (id == null) {
            text[0] = context.getString(R.string.favorites_empty);
            return null;
        }
        String t = state.titles.get(id);
        text[0] = t != null && !t.isEmpty() ? t : WallpaperLibrary.fallbackTitle(id);
        text[1] = state.credits.get(id);
        text[2] = FavoritesData.source(id);
        text[3] = pool.fallback ? context.getString(xmas ? R.string.favorites_none_xmas : R.string.favorites_none) : null;
        return decode(new File(WallpaperLibrary.archive(context, xmas), id + ".jpg"));
    }

    /** At screen size or a little above: the zoom has room without a second full-size copy. */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void show(Bitmap b, String[] text) {
        int back = front < 0 ? 0 : 1 - front;
        ImageView in = views[back];
        if (bitmaps[back] != null) bitmaps[back].recycle();
        bitmaps[back] = b;
        in.animate().cancel();
        in.setImageBitmap(b);
        in.setAlpha(0f);
        // Ken Burns: from a random corner towards the centre, slowly, through the whole interval.
        in.setScaleX(1f);
        in.setScaleY(1f);
        float dx = (random.nextBoolean() ? 1 : -1) * screenW * 0.02f, dy = (random.nextBoolean() ? 1 : -1) * screenH * 0.02f;
        in.setTranslationX(dx);
        in.setTranslationY(dy);
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        if (zooms[back] != null) zooms[back].cancel();
        zooms[back] = android.animation.ObjectAnimator.ofPropertyValuesHolder(in,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, ZOOM),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, ZOOM),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, dx, -dx),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, dy, -dy));
        zooms[back].setDuration(intervalMs + 2 * FADE_MS);
        zooms[back].setInterpolator(new android.view.animation.LinearInterpolator());
        zooms[back].start();
        if (front >= 0) {
            ImageView out = views[front];
            int old = front;
            out.animate().cancel();
            out.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
                out.setImageDrawable(null);
                if (bitmaps[old] != null && bitmaps[old] != bitmaps[back]) bitmaps[old].recycle();
                bitmaps[old] = null;
            }).start();
            in.bringToFront();
            shade.bringToFront();
            panel.bringToFront();
        }
        front = back;
        showText(text);
    }

    private void showText(String[] text) {
        if (text[0] != null && text[2] == null && text[1] == null && text[3] == null && !showPanel) return;
        String source = text[2];
        boolean credit = FavoritesData.needsCredit(source);
        if (!showPanel && !credit) {
            panel.animate().alpha(0f).setDuration(FADE_MS).start();
            return;
        }
        String line = text[1] == null || text[1].isEmpty() ? source
                : source == null || text[1].contains(source) ? text[1] : text[1] + " · " + source;
        title.setText(showPanel ? text[0] : "");
        title.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        this.credit.setText(line == null ? "" : line);
        this.credit.setVisibility(line == null ? View.GONE : View.VISIBLE);
        note.setText(text[3] == null ? "" : text[3]);
        note.setVisibility(showPanel && text[3] != null ? View.VISIBLE : View.GONE);
        panel.setAlpha(0f);
        panel.animate().alpha(1f).setDuration(FADE_MS).start();
    }
}
