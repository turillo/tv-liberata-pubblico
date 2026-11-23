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
import java.util.Arrays;
import java.util.List;

/**
 * The Webb and Hubble scene's archive: ESA/Webb and ESA/Hubble images (their 1920x1200 wallpapers) with title and
 * credit, in files/space/ as id.jpg plus id.txt. New images are looked for at most once a day, only while the
 * screensaver runs, a few per day; the archive keeps the newest MAX_IMAGES and deletes older ones, so it works
 * offline for as long as needed.
 */
final class SpaceImages {

    private static final String TAG = "TvHome.SpaceImages";
    private static final long REFRESH_MS = 24 * 3_600_000L;
    private static final long RETRY_MS = 3_600_000L;
    static final int MAX_IMAGES = 200;
    /** New images per daily check, so a first run does not pull a hundred megabytes. */
    private static final int NEW_PER_CHECK = 12;
    private static final int MAX_IMAGE_BYTES = 8 << 20;
    private static final int MAX_FEED_BYTES = 512 * 1024;
    private static final int MAX_PAGE_BYTES = 512 * 1024;
    /** Decoded images stay under this many pixels (RGB_565: about 12 MB). */
    private static final int MAX_PIXELS = 6_000_000;

    private static long lastAttempt;

    /** One archived image. */
    static final class Entry {
        final String id;
        final int telescope;
        final String title, credit;

        Entry(String id, int telescope, String title, String credit) {
            this.id = id;
            this.telescope = telescope;
            this.title = title;
            this.credit = credit;
        }
    }

    interface Listener {
        /** Called on the download thread after each new image is saved. */
        void saved();
    }

    private final File dir;
    private final File checked;

    /** For ServiceStatus: the result of each refresh, for Syntax TV, Sistema, Servizi. */
    private final Context app;

    SpaceImages(Context context) {
        app = context.getApplicationContext();
        dir = new File(context.getFilesDir(), "space");
        checked = new File(dir, "checked");
    }

    /** The archive, newest first. */
    List<Entry> load() {
        List<Entry> entries = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return entries;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            if (!SpaceData.validId(id)) continue;
            String[] meta = readMeta(id);
            if (meta == null) continue;
            int telescope = "1".equals(meta[0]) ? SpaceData.HUBBLE : SpaceData.WEBB;
            entries.add(new Entry(id, telescope, meta[1], meta[2]));
        }
        return entries;
    }

    private String[] readMeta(String id) {
        File f = new File(dir, id + ".txt");
        if (!f.isFile() || f.length() > 4096) return null;
        try (InputStream in = new FileInputStream(f)) {
            String[] lines = readAtMost(in, 4096).split("\n", -1);
            return lines.length >= 3 && !lines[1].isEmpty() && !lines[2].isEmpty() ? lines : null;
        } catch (IOException e) {
            return null;
        }
    }

    boolean due(long now) {
        long last = checked.isFile() ? checked.lastModified() : 0;
        return now - last >= REFRESH_MS && now - lastAttempt >= RETRY_MS;
    }

    /**
     * Reads both feeds and saves up to NEW_PER_CHECK images not yet in the archive, newest first, alternating
     * telescopes; then trims the archive. Blocking: call it off the main thread. Never throws.
     */
    void refresh(Listener listener) {
        lastAttempt = System.currentTimeMillis();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            List<List<SpaceData.Item>> feeds = new ArrayList<>();
            boolean reached = false;
            for (int t = 0; t < SpaceData.FEEDS.length; t++) {
                List<SpaceData.Item> items = new ArrayList<>();
                try {
                    items = SpaceData.parseFeed(download(SpaceData.FEEDS[t], MAX_FEED_BYTES), t);
                    reached = true;
                } catch (IOException e) {
                    Log.w(TAG, SpaceData.NAMES[t] + " feed not read: " + e.getMessage());
                }
                feeds.add(items);
            }
            int saved = 0;
            int[] next = new int[feeds.size()];
            boolean more = true;
            while (saved < NEW_PER_CHECK && more) {
                more = false;
                for (int t = 0; t < feeds.size() && saved < NEW_PER_CHECK; t++) {
                    List<SpaceData.Item> items = feeds.get(t);
                    while (next[t] < items.size()) {
                        SpaceData.Item item = items.get(next[t]++);
                        if (new File(dir, item.id + ".jpg").isFile()) continue;
                        if (Thread.currentThread().isInterrupted()) return;
                        if (save(item)) {
                            saved++;
                            if (listener != null) listener.saved();
                        }
                        break;
                    }
                    if (next[t] < items.size()) more = true;
                }
            }
            trim();
            if (reached) ServiceStatus.ok(app, ServiceStatus.ESA);
            else ServiceStatus.fail(app, ServiceStatus.ESA, "feed non letti");
            if (reached) {
                try (OutputStream out = new FileOutputStream(checked)) {
                    out.write('1');
                }
                //noinspection ResultOfMethodCallIgnored
                checked.setLastModified(System.currentTimeMillis());
            }
        } catch (Exception e) {
            Log.w(TAG, "ESA images not refreshed: " + e.getMessage());
            ServiceStatus.fail(app, ServiceStatus.ESA, e);
        }
    }

    /** Credit first (the licence needs it), then the wallpaper or the screen size, then the text file. */
    private boolean save(SpaceData.Item item) {
        File tmp = new File(dir, item.id + ".tmp");
        try {
            String credit = SpaceData.parseCredit(download(SpaceData.pageUrl(item), MAX_PAGE_BYTES));
            if (credit == null) throw new IOException("no credit line");
            if (!fetch(SpaceData.imageUrl(item, false), tmp) && !fetch(SpaceData.imageUrl(item, true), tmp)) {
                throw new IOException("no image");
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(tmp.getPath(), bounds);
            if (bounds.outWidth < 640 || bounds.outHeight < 360) throw new IOException("not an image");
            String meta = item.telescope + "\n" + item.title.replace('\n', ' ') + "\n" + credit.replace('\n', ' ') + "\n";
            try (OutputStream out = new FileOutputStream(new File(dir, item.id + ".txt"))) {
                out.write(meta.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(new File(dir, item.id + ".jpg"))) throw new IOException("could not save");
            return true;
        } catch (Exception e) {
            Log.w(TAG, item.id + " not saved: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    /** Downloads an image into {@code to}; false on HTTP errors or one over 8 MB. */
    private static boolean fetch(String url, File to) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return false;
            if (c.getContentLength() > MAX_IMAGE_BYTES) return false;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[32 * 1024];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_IMAGE_BYTES) return false;
                    out.write(buf, 0, n);
                }
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    /** Keeps the newest MAX_IMAGES; deletes the rest and any stray files. */
    private void trim() {
        File[] images = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (images != null && images.length > MAX_IMAGES) {
            Arrays.sort(images, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = MAX_IMAGES; i < images.length; i++) {
                String id = images[i].getName().substring(0, images[i].getName().length() - 4);
                //noinspection ResultOfMethodCallIgnored
                images[i].delete();
                //noinspection ResultOfMethodCallIgnored
                new File(dir, id + ".txt").delete();
            }
        }
        File[] tmps = dir.listFiles((d, name) -> name.endsWith(".tmp"));
        if (tmps != null) {
            //noinspection ResultOfMethodCallIgnored
            for (File f : tmps) f.delete();
        }
    }

    /** The image at least screen-sized for a centre crop, as RGB_565, under MAX_PIXELS; null when broken. */
    Bitmap decode(Entry entry, int screenW, int screenH) {
        File f = new File(dir, entry.id + ".jpg");
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), opts);
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
            int sample = Math.min(SolarData.sampleSize(opts.outWidth, screenW), SolarData.sampleSize(opts.outHeight, screenH));
            while ((long) (opts.outWidth / sample) * (opts.outHeight / sample) > MAX_PIXELS) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getPath(), opts);
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, entry.id + " not decoded", e);
            return null;
        }
    }

    private static String download(String url, int max) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return readAtMost(in, max);
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(90_000);   // esahubble.org's feed can take most of a minute
        connection.setRequestProperty("User-Agent", "TvHome-launcher");
        return connection;
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
}
