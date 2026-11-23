package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The two wallpaper archives on the TV as Syntax TV and the rotation see them: the general one
 * (getExternalFilesDir/bing-wallpapers, Bing and the other sources) and, apart, the Christmas mode's
 * (getExternalFilesDir/xmas-wallpapers). Each has its photos, their 320x180 thumbnails (made once, kept in the cache
 * folder) and its own {@link WallpaperState} (files/wallpaper_state.json, files/xmas_wallpaper_state.json). The
 * methods without {@code xmas} are the general archive's. Every file error is caught and logged.
 */
final class WallpaperLibrary {

    private static final String TAG = "TvHome.WallpaperLib";
    static final String ARCHIVE_DIR = "bing-wallpapers", XMAS_DIR = "xmas-wallpapers";
    static final int THUMB_W = 320, THUMB_H = 180;
    private static final Object LOCK = new Object();

    private WallpaperLibrary() {
    }

    static File archive(Context c) {
        return archive(c, false);
    }

    static File archive(Context c, boolean xmas) {
        File external = c.getExternalFilesDir(null);
        return external == null ? null : new File(external, xmas ? XMAS_DIR : ARCHIVE_DIR);
    }

    /** The archived photos, newest first. */
    static File[] photos(Context c) {
        return photos(c, false);
    }

    static File[] photos(Context c, boolean xmas) {
        File dir = archive(c, xmas);
        File[] list = dir == null ? null : dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (list == null) return new File[0];
        Arrays.sort(list, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    static String id(File photo) {
        String n = photo.getName();
        return n.endsWith(".jpg") ? n.substring(0, n.length() - 4) : n;
    }

    static List<String> ids(File[] photos) {
        List<String> ids = new ArrayList<>();
        for (File f : photos) ids.add(id(f));
        return ids;
    }

    /** Active and total photos, for the cards and the counter. */
    static final class Counts {
        final int active, total;

        Counts(int active, int total) {
            this.active = active;
            this.total = total;
        }
    }

    static Counts counts(Context c) {
        return counts(c, false);
    }

    static Counts counts(Context c, boolean xmas) {
        List<String> ids = ids(photos(c, xmas));
        return new Counts(load(c, xmas).activeCount(ids), ids.size());
    }

    // ---- state -------------------------------------------------------------------------------------------

    private static File stateFile(Context c, boolean xmas) {
        return new File(c.getFilesDir(), xmas ? "xmas_wallpaper_state.json" : "wallpaper_state.json");
    }

    static WallpaperState load(Context c) {
        return load(c, false);
    }

    static WallpaperState load(Context c, boolean xmas) {
        synchronized (LOCK) {
            File f = stateFile(c, xmas);
            if (!f.isFile() || f.length() > 4 << 20) return new WallpaperState();
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return WallpaperState.fromJson(new String(out.toByteArray(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "state unreadable: " + e.getMessage());
                return new WallpaperState();
            }
        }
    }

    /** Saves through a temporary file, so a crash mid-write keeps the previous state. */
    static boolean save(Context c, WallpaperState state) {
        return save(c, false, state);
    }

    static boolean save(Context c, boolean xmas, WallpaperState state) {
        synchronized (LOCK) {
            File f = stateFile(c, xmas), tmp = new File(f.getPath() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(state.toJson().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "state not saved: " + e.getMessage());
                return false;
            }
            if (!tmp.renameTo(f)) {
                Log.w(TAG, "state not saved: rename failed");
                return false;
            }
            return true;
        }
    }

    /** Remembers a photo's Bing title and description (WallpaperPlayer, after a download). */
    static void remember(Context c, String id, String title, String credit) {
        remember(c, false, id, title, credit);
    }

    static void remember(Context c, boolean xmas, String id, String title, String credit) {
        synchronized (LOCK) {
            WallpaperState s = load(c, xmas);
            if (title != null && !title.isEmpty()) s.titles.put(id, title);
            if (credit != null && !credit.isEmpty()) s.credits.put(id, credit);
            save(c, xmas, s);
        }
    }

    /** Deletes a photo and its thumbnail and blacklists it, so the download never brings it back. */
    static boolean delete(Context c, String id) {
        return delete(c, false, id);
    }

    static boolean delete(Context c, boolean xmas, String id) {
        File dir = archive(c, xmas);
        boolean gone = true;
        if (dir != null) {
            File photo = new File(dir, id + ".jpg");
            gone = !photo.exists() || photo.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        thumbFile(c, id).delete();
        synchronized (LOCK) {
            WallpaperState s = load(c, xmas);
            s.delete(id);
            return save(c, xmas, s) && gone;
        }
    }

    /** A change to an archive's state made in one step under the lock. */
    interface Edit {
        void apply(WallpaperState state);
    }

    static boolean update(Context c, boolean xmas, Edit edit) {
        synchronized (LOCK) {
            WallpaperState s = load(c, xmas);
            edit.apply(s);
            return save(c, xmas, s);
        }
    }

    /**
     * Removes a photo over the archive's cap: the photo, its thumbnail and what the state keeps about it, but not
     * blacklisted (it was only old).
     */
    static void remove(Context c, String id) {
        remove(c, false, id);
    }

    static void remove(Context c, boolean xmas, String id) {
        File dir = archive(c, xmas);
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, id + ".jpg").delete();
        }
        //noinspection ResultOfMethodCallIgnored
        thumbFile(c, id).delete();
        synchronized (LOCK) {
            WallpaperState s = load(c, xmas);
            s.disabled.remove(id);
            s.titles.remove(id);
            s.credits.remove(id);
            save(c, xmas, s);
        }
    }

    // ---- the move to a separate Christmas archive (6 November 2026) ----------------------------------------

    /**
     * Until 6 November 2026 the Christmas photos lived in the general archive, marked in its state. This moves them,
     * once, to the Christmas archive: the files are renamed (not downloaded again) and what the state knew about them
     * goes with them (on or off, favourite, title, credit; the blacklist is copied, so a deleted one never comes
     * back). A file that cannot be moved stays where it is and is tried again next time. Cheap when there is
     * nothing to move; safe to call from any thread.
     */
    static void migrate(Context c) {
        synchronized (LOCK) {
            WallpaperState general = load(c, false);
            if (general.christmas.isEmpty()) return;
            File from = archive(c, false), to = archive(c, true);
            if (from == null || to == null || (!to.isDirectory() && !to.mkdirs())) return;
            List<String> stuck = new ArrayList<>();
            for (String id : general.christmas) {
                File src = new File(from, id + ".jpg"), dst = new File(to, id + ".jpg");
                if (src.isFile() && !dst.exists() && !src.renameTo(dst)) stuck.add(id);
            }
            WallpaperState xmas = load(c, true);
            int moved = WallpaperState.moveChristmas(general, xmas, stuck);
            if (save(c, true, xmas)) save(c, false, general);
            Log.i(TAG, "Christmas photos moved to their own archive: " + moved
                    + (stuck.isEmpty() ? "" : ", " + stuck.size() + " left for next time"));
        }
    }

    // ---- thumbnails --------------------------------------------------------------------------------------

    static File thumbFile(Context c, String id) {
        return new File(new File(c.getCacheDir(), "thumbs"), id + ".jpg");
    }

    /**
     * The photo's 320x180 thumbnail: read from the cache, or made once (a sampled decode, a centre crop) and saved.
     * Null when the photo cannot be read. Blocking: call it off the main thread.
     */
    static Bitmap thumbnail(Context c, File photo) {
        File thumb = thumbFile(c, id(photo));
        try {
            if (thumb.isFile() && thumb.lastModified() >= photo.lastModified()) {
                Bitmap b = BitmapFactory.decodeFile(thumb.getPath());
                if (b != null) return b;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(photo.getPath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= THUMB_W && o.outHeight / (sample * 2) >= THUMB_H) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap src = BitmapFactory.decodeFile(photo.getPath(), o);
            if (src == null) return null;
            Bitmap out = Bitmap.createBitmap(THUMB_W, THUMB_H, Bitmap.Config.RGB_565);
            float scale = Math.max(THUMB_W / (float) src.getWidth(), THUMB_H / (float) src.getHeight());
            int cw = Math.round(THUMB_W / scale), ch = Math.round(THUMB_H / scale);
            int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
            new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, THUMB_W, THUMB_H),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
            File dir = thumb.getParentFile();
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                try (OutputStream os = new FileOutputStream(thumb)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 85, os);
                }
            }
            return out;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no thumbnail for " + photo.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /** A readable name for a photo without a saved title: "OHR.LagoDiBraies_IT-IT123" gives "Lago Di Braies". */
    static String fallbackTitle(String id) {
        String s = id.startsWith("OHR.") ? id.substring(4) : id;
        int cut = s.indexOf('_');
        if (cut > 0) s = s.substring(0, cut);
        return s.replaceAll("([a-z])([A-Z0-9])", "$1 $2").trim();
    }
}
