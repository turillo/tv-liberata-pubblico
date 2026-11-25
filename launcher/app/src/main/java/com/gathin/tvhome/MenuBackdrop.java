package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The settings pages' background (Temi, "Sfondo dei menu"). The photo backgrounds never blur anything while a menu
 * opens: each wallpaper gets, once and in the background, two small copies in the cache (480x270 blurred and
 * darkened; 960x540 sharp), and a menu only decodes the ready copy. A photo without its copy yet shows the dark glow
 * meanwhile, and its copy is made right then for the next time. Copies of photos no longer in the archive are deleted.
 */
final class MenuBackdrop {

    private static final String TAG = "TvHome.MenuBackdrop";
    static final int BLUR_W = 480, BLUR_H = 270, SHARP_W = 960, SHARP_H = 540;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "menu-backdrop");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private MenuBackdrop() {
    }

    /** Puts the chosen background behind a settings page. */
    static void apply(View outer) {
        Context c = outer.getContext();
        outer.setBackground(drawable(c, Theme.backdrop(c)));
    }

    static Drawable drawable(Context c, int kind) {
        switch (kind) {
            case Theme.BG_BLUR: {
                Bitmap b = copy(c, true);
                if (b != null) return new BitmapDrawable(c.getResources(), b);
                return glow(c);
            }
            case Theme.BG_PHOTO: {
                Bitmap b = copy(c, false);
                if (b == null) return glow(c);
                // Dark on the left, where the menus are, almost clear on the right.
                GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{0xF0050608, 0xB0050608, 0x30050608});
                return new LayerDrawable(new Drawable[]{new BitmapDrawable(c.getResources(), b), shade});
            }
            case Theme.BG_LOGO:
                return logo(c);
            default:
                return glow(c);
        }
    }

    /** Almost black, with two soft halos of the main colour in the corners. */
    static Drawable glow(Context c) {
        int accent = Theme.color(c);
        int w = c.getResources().getDisplayMetrics().widthPixels;
        GradientDrawable top = new GradientDrawable();
        top.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        top.setColors(new int[]{Theme.withAlpha(accent, 0x46), Theme.withAlpha(accent, 0)});
        top.setGradientCenter(0.08f, 0.05f);
        top.setGradientRadius(w * 0.45f);
        GradientDrawable bottom = new GradientDrawable();
        bottom.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bottom.setColors(new int[]{Theme.withAlpha(accent, 0x32), Theme.withAlpha(accent, 0)});
        bottom.setGradientCenter(0.95f, 0.98f);
        bottom.setGradientRadius(w * 0.40f);
        return new LayerDrawable(new Drawable[]{new ColorDrawable(0xFF06080C), top, bottom});
    }

    /** Dark, with TV Liberata's lock big and almost invisible (about 7%) at the top right. */
    static Drawable logo(Context c) {
        Drawable mark = c.getDrawable(R.mipmap.ic_launcher);
        int h = c.getResources().getDisplayMetrics().heightPixels;
        if (mark == null) return new ColorDrawable(0xFF07090E);
        mark = mark.mutate();
        mark.setAlpha(18);   // 7% of 255
        LayerDrawable layers = new LayerDrawable(new Drawable[]{new ColorDrawable(0xFF07090E), mark});
        int size = Math.round(h * 0.72f);
        layers.setLayerSize(1, size, size);
        layers.setLayerGravity(1, Gravity.TOP | Gravity.END);
        layers.setLayerInsetTop(1, -Math.round(size * 0.08f));
        layers.setLayerInsetEnd(1, -Math.round(size * 0.10f));
        return layers;
    }

    // ---- the cached copies ----------------------------------------------------------------------------------

    /** The wallpaper on Home now (WallpaperPlayer's "image"), in the archive; null when none. */
    static File currentPhoto(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences(WallpaperPlayer.PREFS, Context.MODE_PRIVATE);
        // The archive's photo on Home is "bing_image"; "image" is a theme's own wallpaper (not in the archive).
        for (String key : new String[]{"bing_image", "image"}) {
            String name = p.getString(key, "");
            if (name == null || name.isEmpty()) continue;
            for (boolean xmas : new boolean[]{false, true}) {
                File dir = WallpaperLibrary.archive(c, xmas);
                if (dir == null) continue;
                File f = new File(dir, name);
                if (f.isFile()) return f;
            }
        }
        // Nothing recorded yet: the newest photo of the archive.
        File[] photos = WallpaperLibrary.photos(c);
        return photos.length > 0 ? photos[0] : null;
    }

    private static File dir(Context c) {
        return new File(c.getCacheDir(), "menu-bg");
    }

    static File copyFile(Context c, String id, boolean blurred) {
        return new File(dir(c), id + (blurred ? "-blur.jpg" : "-sharp.jpg"));
    }

    /** The ready copy of the current photo, or null (then it is made in the background for next time). */
    private static Bitmap copy(Context c, boolean blurred) {
        File photo = currentPhoto(c);
        if (photo == null) return null;
        File f = copyFile(c, WallpaperLibrary.id(photo), blurred);
        if (f.isFile() && f.lastModified() >= photo.lastModified()) {
            Bitmap b = BitmapFactory.decodeFile(f.getPath());
            if (b != null) return b;
        }
        prepareAsync(c);
        return null;
    }

    /** Makes the current photo's copies off the main thread, and deletes those of removed photos. */
    static void prepareAsync(Context context) {
        Context c = context.getApplicationContext();
        WORKER.execute(() -> {
            try {
                File photo = currentPhoto(c);
                if (photo != null) prepare(c, photo);
                prune(c);
            } catch (RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "menu background not prepared: " + e);
            }
        });
    }

    static void prepare(Context c, File photo) {
        String id = WallpaperLibrary.id(photo);
        File blur = copyFile(c, id, true), sharp = copyFile(c, id, false);
        if (blur.isFile() && sharp.isFile() && blur.lastModified() >= photo.lastModified()) return;
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return;
        Bitmap src = decode(photo, SHARP_W, SHARP_H);
        if (src == null) return;
        Bitmap sharpCopy = cover(src, SHARP_W, SHARP_H);
        save(sharpCopy, sharp);
        // Blur: shrink to a tenth, box-blur twice, grow back with filtering, darken.
        Bitmap small = cover(src, BLUR_W / 8, BLUR_H / 8);
        src.recycle();
        int[] px = new int[small.getWidth() * small.getHeight()];
        small.getPixels(px, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
        px = boxBlur(boxBlur(px, small.getWidth(), small.getHeight(), 2), small.getWidth(), small.getHeight(), 2);
        small.setPixels(px, 0, small.getWidth(), 0, 0, small.getWidth(), small.getHeight());
        Bitmap blurred = Bitmap.createBitmap(BLUR_W, BLUR_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(blurred);
        canvas.drawBitmap(small, null, new Rect(0, 0, BLUR_W, BLUR_H), new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.drawColor(0x99000000);   // darkened: white text stays readable on any photo
        small.recycle();
        save(blurred, blur);
        blurred.recycle();
        sharpCopy.recycle();
    }

    /** Copies of photos no longer in either archive go. */
    static void prune(Context c) {
        File[] copies = dir(c).listFiles();
        if (copies == null) return;
        Set<String> ids = new HashSet<>();
        for (boolean xmas : new boolean[]{false, true}) for (File f : WallpaperLibrary.photos(c, xmas)) ids.add(WallpaperLibrary.id(f));
        for (File f : copies) {
            String id = idOf(f.getName());
            if (id != null && !ids.contains(id)) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** "ID-blur.jpg" or "ID-sharp.jpg" to "ID"; null for anything else. Plain Java, tested. */
    static String idOf(String name) {
        if (name.endsWith("-blur.jpg")) return name.substring(0, name.length() - "-blur.jpg".length());
        if (name.endsWith("-sharp.jpg")) return name.substring(0, name.length() - "-sharp.jpg".length());
        return null;
    }

    private static Bitmap decode(File photo, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photo.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(photo.getPath(), o);
    }

    /** Centre-cropped to w x h. */
    private static Bitmap cover(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        return out;
    }

    /** A box blur of the given radius, horizontal then vertical. Plain Java, tested. */
    static int[] boxBlur(int[] in, int w, int h, int r) {
        int[] tmp = new int[in.length], out = new int[in.length];
        pass(in, tmp, w, h, r, true);
        pass(tmp, out, w, h, r, false);
        return out;
    }

    private static void pass(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int p = horizontal ? in[line * w + j] : in[j * w + line];
                    a += p >>> 24;
                    rr += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                    n++;
                }
                int v = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
                if (horizontal) out[line * w + i] = v;
                else out[i * w + line] = v;
            }
        }
    }

    private static void save(Bitmap b, File f) {
        try (OutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } catch (IOException e) {
            Log.w(TAG, "copy not saved: " + e.getMessage());
        }
    }
}
