package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The artwork of an app's tile, from what the installed app gives the system and nothing else (no logos in this APK).
 * An app with a TV banner keeps it. An app with only an icon (Amazon's Appstore, phone apps) gets a 16:9 tile made
 * here: the icon at the highest density it ships, drawn sharp, centred at about 58% of the tile's height, never
 * stretched, on its own main colour (the icon's background colour when it has one, so the tile reads as one banner;
 * otherwise a darker shade of its opaque pixels, with a soft gradient). The tile has no text: the name shows under it
 * on focus. Made tiles are cached as PNG files keyed by the app's version and update time, so Home does not redraw
 * them and an updated app gets a new one.
 */
final class TileArt {

    private static final String TAG = "TvHome.TileArt";
    /** The made tile, 16:9: sharper than any card on a 1080p screen (a 180 dp card at xhdpi is 360 px wide). */
    static final int W = 400, H = 225;
    /** The icon's height in the tile. */
    static final float ICON = 0.58f;
    /** The drawing rules' version, in the cache folder's name. */
    static final int STYLE = 2;

    private TileArt() {
    }

    /**
     * The app's TV banner at full quality (the activity's, then the application's), or null when it has none or it
     * is not banner-shaped (a small square "banner" is a blur on a 16:9 tile).
     */
    static Drawable banner(Context c, ActivityInfo ai) {
        PackageManager pm = c.getPackageManager();
        int id = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
        Drawable banner = null;
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(ai.applicationInfo);
                int density = Math.max(DisplayMetrics.DENSITY_XHIGH, c.getResources().getDisplayMetrics().densityDpi);
                banner = res.getDrawableForDensity(id, density, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
                // the plain loader below
            }
        }
        if (banner == null) banner = ai.loadBanner(pm);
        return banner != null && bannerShaped(banner.getIntrinsicWidth(), banner.getIntrinsicHeight()) ? banner : null;
    }

    /** A drawable with no intrinsic size (a colour, a shape) still fills the tile. Plain Java, tested. */
    static boolean bannerShaped(int width, int height) {
        return width <= 0 || height <= 0 || width >= height * 1.4f;
    }

    /** The banner, or else a tile made from the icon (cached). Null only when the app gives nothing at all. */
    static Drawable art(Context c, ActivityInfo ai) {
        Drawable banner = banner(c, ai);
        if (banner != null) return banner;
        Bitmap tile = iconTile(c, ai);
        return tile == null ? null : new BitmapDrawable(c.getResources(), tile);
    }

    /** The icon tile, from the cache when the app has not changed since it was made. */
    static Bitmap iconTile(Context c, ActivityInfo ai) {
        File file = cacheFile(c, ai);
        if (file != null && file.isFile()) {
            Bitmap cached = BitmapFactory.decodeFile(file.getPath());
            if (cached != null) return cached;
        }
        Drawable icon = bestIcon(c, ai);
        if (icon == null) return null;
        Bitmap tile;
        try {
            tile = make(icon);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no tile for " + ai.packageName + ": " + e);
            return null;
        }
        if (file != null) save(c, file, tile, ai.packageName);
        return tile;
    }

    /** The icon at the highest density the app ships (xxxhdpi, then xxhdpi), else the system's choice. */
    static Drawable bestIcon(Context c, ActivityInfo ai) {
        PackageManager pm = c.getPackageManager();
        int id = ai.getIconResource();
        if (id != 0) {
            try {
                Resources res = pm.getResourcesForApplication(ai.applicationInfo);
                for (int d : new int[]{DisplayMetrics.DENSITY_XXXHIGH, DisplayMetrics.DENSITY_XXHIGH}) {
                    try {
                        Drawable icon = res.getDrawableForDensity(id, d, null);
                        if (icon != null) return icon;
                    } catch (Resources.NotFoundException ignored) {
                        // next density
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
                // the system's choice below
            }
        }
        try {
            return ai.loadIcon(pm);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Draws the 16:9 tile: background colour and gradient, then the icon centred, proportions kept. */
    static Bitmap make(Drawable icon) {
        Bitmap art = render(icon);
        int bg = background(art);
        boolean own = ownBackground(art);
        if (own) {
            // The icon has its own background (Amazon's Appstore: white): the tile continues it in one flat colour, so
            // no edge of the icon's square shows, and the icon's margin of that colour is trimmed so its drawing,
            // not its padding, gets the tile's 58%.
            Bitmap trimmed = trim(art, bg);
            if (trimmed != art) {
                art.recycle();
                art = trimmed;
            }
        }
        Bitmap tile = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(tile);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (own) paint.setColor(bg);
        else paint.setShader(new LinearGradient(0, 0, W, H, shade(bg, 0.06f), shade(bg, -0.10f), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, W, H, paint);
        int[] box = fit(art.getWidth(), art.getHeight(), W, H, ICON);
        canvas.drawBitmap(art, null, new android.graphics.Rect(box[0], box[1], box[0] + box[2], box[1] + box[3]),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        art.recycle();
        return tile;
    }

    /**
     * Where the icon goes: {x, y, width, height}, centred, its height {@code part} of the tile's (narrower when a
     * wide icon would not fit), its proportions kept. Plain Java, tested.
     */
    static int[] fit(int iconW, int iconH, int tileW, int tileH, float part) {
        float ratio = iconW > 0 && iconH > 0 ? iconW / (float) iconH : 1f;
        int h = Math.round(tileH * part), w = Math.round(h * ratio);
        int maxW = Math.round(tileW * 0.8f);   // a wide drawing (a trimmed logo) narrows to fit, never stretched
        if (w > maxW) {
            w = maxW;
            h = Math.round(w / ratio);
        }
        return new int[]{(tileW - w) / 2, (tileH - h) / 2, w, h};
    }

    /**
     * The icon as a sharp bitmap at its own size (about 512 px for the big densities). An adaptive icon is drawn whole,
     * its foreground over its background, without the launcher's mask: on the tile it is a rounded square anyway.
     */
    private static Bitmap render(Drawable icon) {
        int size = 512;
        int w = icon.getIntrinsicWidth() > 0 ? icon.getIntrinsicWidth() : size;
        int h = icon.getIntrinsicHeight() > 0 ? icon.getIntrinsicHeight() : size;
        float scale = Math.min(1f, size / (float) Math.max(w, h));   // no bigger than needed, never upscaled here
        if (icon instanceof AdaptiveIconDrawable) {
            w = h = size;
            scale = 1f;
        }
        int bw = Math.max(1, Math.round(w * scale)), bh = Math.max(1, Math.round(h * scale));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(b);
        if (icon instanceof AdaptiveIconDrawable) {
            AdaptiveIconDrawable a = (AdaptiveIconDrawable) icon;
            // The layers are 108 dp of which 72 show: draw them so the visible part fills the bitmap.
            int extra = Math.round(bw * 0.25f);
            for (Drawable layer : new Drawable[]{a.getBackground(), a.getForeground()}) {
                if (layer == null) continue;
                layer.setBounds(-extra, -extra, bw + extra, bh + extra);
                layer.draw(canvas);
            }
        } else {
            icon.setBounds(0, 0, bw, bh);
            icon.draw(canvas);
        }
        return b;
    }

    /** The tile's colour, from the icon's pixels. */
    private static int[] corners(Bitmap art) {
        int w = art.getWidth(), h = art.getHeight();
        return new int[]{art.getPixel(w / 12, h / 12), art.getPixel(w - 1 - w / 12, h / 12),
                art.getPixel(w / 12, h - 1 - h / 12), art.getPixel(w - 1 - w / 12, h - 1 - h / 12)};
    }

    private static boolean ownBackground(Bitmap art) {
        return sameOpaque(corners(art));
    }

    /** The four corners are one opaque colour: the icon has its own background. Plain Java, tested. */
    static boolean sameOpaque(int[] corners) {
        for (int cl : corners) if (a(cl) != 255 || !close(cl, corners[0])) return false;
        return true;
    }

    /** The icon without its margin of background colour, with a little room kept around the drawing. */
    private static Bitmap trim(Bitmap art, int bg) {
        int w = art.getWidth(), h = art.getHeight();
        int[] row = new int[w];
        int top = h, bottom = -1, left = w, right = -1;
        for (int y = 0; y < h; y++) {
            art.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (a(row[x]) > 40 && !close(row[x] | 0xFF000000, bg)) {
                    if (y < top) top = y;
                    if (y > bottom) bottom = y;
                    if (x < left) left = x;
                    if (x > right) right = x;
                }
            }
        }
        int[] box = trimBox(left, top, right, bottom, w, h);
        if (box == null) return art;
        return Bitmap.createBitmap(art, box[0], box[1], box[2], box[3]);
    }

    /**
     * {x, y, width, height} of the drawing found between left..right and top..bottom, with 8% room around it, inside
     * the icon; null when nothing was found or there is nothing to trim. Plain Java, tested.
     */
    static int[] trimBox(int left, int top, int right, int bottom, int w, int h) {
        if (right < left || bottom < top) return null;
        int pad = Math.round(Math.max(right - left, bottom - top) * 0.08f);
        int x0 = Math.max(0, left - pad), y0 = Math.max(0, top - pad);
        int x1 = Math.min(w - 1, right + pad), y1 = Math.min(h - 1, bottom + pad);
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return null;
        return new int[]{x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }

    private static int background(Bitmap art) {
        int w = art.getWidth(), h = art.getHeight();
        int step = Math.max(1, Math.min(w, h) / 48);
        int[] px = new int[((w + step - 1) / step) * ((h + step - 1) / step)];
        int n = 0;
        for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) px[n++] = art.getPixel(x, y);
        return choose(px, n, corners(art));
    }

    /**
     * The colour: when the icon's four corners are one opaque colour, that one (the icon has its own background, the
     * tile continues it); otherwise the average of the opaque pixels, darkened so the icon stands out. Plain Java,
     * tested.
     */
    static int choose(int[] pixels, int count, int[] corners) {
        if (sameOpaque(corners)) return corners[0] | 0xFF000000;
        long r = 0, g = 0, b = 0;
        int opaque = 0;
        for (int i = 0; i < count; i++) {
            int p = pixels[i];
            if (a(p) < 200) continue;
            r += red(p);
            g += green(p);
            b += blue(p);
            opaque++;
        }
        if (opaque == 0) return 0xFF1B2330;   // a transparent icon: the cards' own dark
        int avg = rgb((int) (r / opaque), (int) (g / opaque), (int) (b / opaque));
        // Behind the icon: the same colour, darker, so the icon (about this colour) reads against it.
        return shade(avg, luminance(avg) > 0.5 ? -0.55f : -0.35f);
    }

    private static int a(int c) {
        return c >>> 24;
    }

    private static int red(int c) {
        return (c >> 16) & 0xFF;
    }

    private static int green(int c) {
        return (c >> 8) & 0xFF;
    }

    private static int blue(int c) {
        return c & 0xFF;
    }

    private static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean close(int x, int y) {
        return Math.abs(red(x) - red(y)) + Math.abs(green(x) - green(y)) + Math.abs(blue(x) - blue(y)) < 36;
    }

    static double luminance(int c) {
        return (0.2126 * red(c) + 0.7152 * green(c) + 0.0722 * blue(c)) / 255.0;
    }

    /** Lighter (amount > 0, towards white) or darker (towards black). Plain Java, tested. */
    static int shade(int c, float amount) {
        int target = amount >= 0 ? 255 : 0;
        float t = Math.abs(amount);
        return rgb(Math.round(red(c) + (target - red(c)) * t), Math.round(green(c) + (target - green(c)) * t),
                Math.round(blue(c) + (target - blue(c)) * t));
    }

    // ---- cache ----------------------------------------------------------------------------------------------

    /** tiles/<package>~<activity>-<version code>-<update time>.png: an updated app has a new name, so a new tile. */
    private static File cacheFile(Context c, ActivityInfo ai) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(ai.packageName, 0);
            File dir = new File(c.getCacheDir(), "tiles-" + STYLE);   // a new look gets a new folder: old tiles are not reused
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            return new File(dir, key(ai.packageName + "~" + ai.name, Catalog.versionCode(pi), pi.lastUpdateTime) + ".png");
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The cache file's name. Plain Java, tested. */
    static String key(String component, long versionCode, long updated) {
        return component.replaceAll("[^A-Za-z0-9._~-]", "_") + "-" + versionCode + "-" + updated;
    }

    private static void save(Context c, File file, Bitmap tile, String pkg) {
        // Older tiles of the same app are of an older version: they go.
        File[] old = file.getParentFile().listFiles((d, name) -> name.startsWith(pkg + "~") && !name.equals(file.getName()));
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        try (OutputStream out = new FileOutputStream(file)) {
            tile.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            Log.w(TAG, "tile not cached: " + e.getMessage());
        }
    }
}
