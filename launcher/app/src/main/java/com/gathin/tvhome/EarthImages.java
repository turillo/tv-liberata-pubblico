package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Earth scene's photos: NASA EPIC's latest day of photos, checked at most once an hour and only while the
 * screensaver runs. The enhanced-colour ones when EPIC has them for the same day as the natural ones and all of
 * them download; otherwise the natural ones. The list and the photos are kept in files/earth/ (at most MAX_SHOTS photos, about
 * 220 KB each; older ones are deleted), so the scene works offline with the last day it got.
 */
final class EarthImages {

    private static final String TAG = "TvHome.EarthImages";
    private static final long REFRESH_MS = 60 * 60_000L;
    private static final long RETRY_MS = 10 * 60_000L;
    private static final int MAX_SHOTS = 24;
    private static final int MAX_LIST_BYTES = 256 * 1024;
    private static final int MAX_IMAGE_BYTES = 2 << 20;

    private static long lastAttempt;

    private final File dir;
    private final File index;

    /** For ServiceStatus: the result of each refresh, for Syntax TV, Sistema, Servizi. */
    private final Context app;

    EarthImages(Context context) {
        app = context.getApplicationContext();
        dir = new File(context.getFilesDir(), "earth");
        index = new File(dir, "index.json");
    }

    /** The cached day's photos that are on disk, oldest first. */
    List<EarthData.Shot> load() {
        List<EarthData.Shot> shots = new ArrayList<>();
        for (EarthData.Shot s : EarthData.parse(readFile(index))) if (file(s).isFile()) shots.add(s);
        return shots;
    }

    private File file(EarthData.Shot shot) {
        return new File(dir, shot.image + ".jpg");
    }

    /** Whether a check is due: the cached list is an hour old (or missing) and no check failed in 10 minutes. */
    boolean due(long now) {
        long saved = index.isFile() ? index.lastModified() : 0;
        return now - saved >= REFRESH_MS && now - lastAttempt >= RETRY_MS;
    }

    /**
     * Downloads the latest list and the photos missing from the cache. The new list replaces the old one only
     * once at least one of its photos is on disk; then photos no longer listed are deleted. Returns whether the
     * list changed. Blocking: call it off the main thread. Never throws.
     */
    boolean refresh() {
        lastAttempt = System.currentTimeMillis();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            String text = download(EarthData.NATURAL_URL);
            List<EarthData.Shot> shots = latest(EarthData.parse(text));
            if (shots.isEmpty()) throw new IOException("no photos in the EPIC list");
            String enhancedText = null;
            try {
                enhancedText = download(EarthData.ENHANCED_URL);
            } catch (IOException e) {
                Log.w(TAG, "EPIC enhanced list not read: " + e.getMessage());
            }
            List<EarthData.Shot> enhanced = latest(EarthData.parse(enhancedText));
            if (EarthData.useEnhanced(shots, enhanced) && fetchAll(enhanced) == enhanced.size()) {
                text = enhancedText;
                shots = enhanced;
            } else if (fetchAll(shots) == 0) {
                ServiceStatus.fail(app, ServiceStatus.EPIC, "foto non scaricate");
                return false;
            }
            boolean changed = !text.equals(readFile(index));
            File tmp = new File(dir, "index.tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(index)) throw new IOException("could not save the list");
            Set<String> keep = new HashSet<>();
            keep.add(index.getName());
            for (EarthData.Shot s : shots) keep.add(file(s).getName());
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    if (!keep.contains(f.getName())) f.delete();
                }
            }
            ServiceStatus.ok(app, ServiceStatus.EPIC);
            return changed;
        } catch (Exception e) {
            Log.w(TAG, "EPIC not refreshed: " + e.getMessage());
            ServiceStatus.fail(app, ServiceStatus.EPIC, e);
            return false;
        }
    }

    private static List<EarthData.Shot> latest(List<EarthData.Shot> shots) {
        return shots.size() > MAX_SHOTS ? shots.subList(shots.size() - MAX_SHOTS, shots.size()) : shots;
    }

    /** Downloads the photos missing from the cache; returns how many are on disk afterwards. */
    private int fetchAll(List<EarthData.Shot> shots) {
        int onDisk = 0;
        for (EarthData.Shot s : shots) {
            if (Thread.currentThread().isInterrupted()) break;
            if (file(s).isFile() || fetchImage(s)) onDisk++;
        }
        return onDisk;
    }

    private boolean fetchImage(EarthData.Shot shot) {
        HttpURLConnection connection = null;
        File tmp = new File(dir, shot.image + ".tmp");
        try {
            connection = open(EarthData.imageUrl(shot));
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) throw new IOException("image too large");
                    out.write(buf, 0, n);
                }
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), bounds);
            if (bounds.outWidth < 256 || bounds.outWidth != bounds.outHeight) throw new IOException("not an EPIC photo");
            if (!tmp.renameTo(file(shot))) throw new IOException("could not save");
            return true;
        } catch (Exception e) {
            Log.w(TAG, shot.image + " not downloaded: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String download(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + connection.getResponseCode());
            }
            try (InputStream in = connection.getInputStream()) {
                return readAtMost(in, MAX_LIST_BYTES);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
        connection.setRequestProperty("User-Agent", "TvHome-launcher");
        return connection;
    }

    private static String readFile(File f) {
        if (!f.isFile() || f.length() > MAX_LIST_BYTES) return null;
        try (InputStream in = new FileInputStream(f)) {
            return readAtMost(in, MAX_LIST_BYTES);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > max) throw new IOException("reply too large");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** The photo at least {@code target} px wide, as RGB_565 (2 bytes a pixel); null when missing or broken. */
    Bitmap decode(EarthData.Shot shot, int target) {
        File f = file(shot);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), opts);
            if (opts.outWidth <= 0) return null;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = SolarData.sampleSize(opts.outWidth, target);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), opts);
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, shot.image + " not decoded", e);
            return null;
        }
    }
}
