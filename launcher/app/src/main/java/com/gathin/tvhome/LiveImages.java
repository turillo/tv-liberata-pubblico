package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The live Earth scene's frames: Meteosat-12 GeoColour images from EUMETView for the two views, the newest checked
 * every 15 minutes and only while the screensaver runs, plus one every 30 minutes back to six hours for the
 * time-lapse. Kept in files/live/ as d_ or e_ plus the UTC time; anything older than six hours is deleted.
 */
final class LiveImages {

    private static final String TAG = "TvHome.LiveImages";
    private static final long REFRESH_MS = 15 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    static final long KEEP_MS = 6 * 3_600_000L;
    static final long STEP_MS = 30 * 60_000L;
    private static final int MAX_IMAGE_BYTES = 8 << 20;
    private static final String[] PREFIX = {"d_", "e_"};
    private static final int ROME_COLOR = 0xFFE0661B;

    private static long lastAttempt, lastCheck;
    /** Disk lookup tables by size (the full disk and the time-lapse's half size). */
    private static final java.util.Map<Long, int[]> TABLES = new java.util.HashMap<>();

    /** One cached frame. */
    static final class Frame {
        final int view;
        final long time;
        final File file;

        Frame(int view, long time, File file) {
            this.view = view;
            this.time = time;
            this.file = file;
        }
    }

    interface Listener {
        /** Called on the download thread after each new frame is saved. */
        void saved(int view);
    }

    private final File dir;

    /** For ServiceStatus: the result of each refresh, for Syntax TV, Sistema, Servizi. */
    private final Context app;

    LiveImages(Context context) {
        app = context.getApplicationContext();
        dir = new File(context.getFilesDir(), "live");
    }

    /** The cached frames of a view, oldest first. */
    List<Frame> frames(int view) {
        List<Frame> frames = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.startsWith(PREFIX[view]) && name.endsWith(".jpg"));
        if (files == null) return frames;
        for (File f : files) {
            long t = parseName(f.getName().substring(2, f.getName().length() - 4));
            if (t > 0) frames.add(new Frame(view, t, f));
        }
        frames.sort((a, b) -> Long.compare(a.time, b.time));
        return frames;
    }

    boolean due(long now) {
        return now - lastCheck >= REFRESH_MS && now - lastAttempt >= RETRY_MS;
    }

    /**
     * Saves the newest frame of both views, then fills the time-lapse every 30 minutes back to six hours, newest
     * first, and deletes what is older. Blocking: call it off the main thread. Never throws.
     */
    void refresh(Listener listener) {
        lastAttempt = System.currentTimeMillis();
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            long latest = -1;
            for (int i = 0; i < LiveData.CAPS_URLS.length && latest <= 0; i++) {
                try {
                    latest = LiveData.latestTime(download(LiveData.capsUrl(i, System.currentTimeMillis())));
                } catch (IOException e) {
                    Log.w(TAG, "capabilities " + i + ": " + e.getMessage());
                }
            }
            if (latest <= 0) throw new IOException("no time in the capabilities");
            lastCheck = System.currentTimeMillis();
            List<Long> times = new ArrayList<>();
            times.add(latest);
            for (long t = latest - latest % STEP_MS; t > latest - KEEP_MS; t -= STEP_MS) if (t != latest) times.add(t);
            for (long t : times) {
                for (int view = 0; view < 2; view++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    File f = new File(dir, PREFIX[view] + name(t) + ".jpg");
                    if (f.isFile()) continue;
                    if (fetch(LiveData.mapUrl(view, t), f) && listener != null) listener.saved(view);
                }
            }
            prune(latest);
            ServiceStatus.ok(app, ServiceStatus.EUMETSAT);
        } catch (Exception e) {
            Log.w(TAG, "EUMETView not refreshed: " + e.getMessage());
            ServiceStatus.fail(app, ServiceStatus.EUMETSAT, e);
        }
    }

    /** Deletes frames older than six hours, and frames between the 30-minute steps except the newest. */
    private void prune(long latest) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            boolean frame = (n.startsWith(PREFIX[0]) || n.startsWith(PREFIX[1])) && n.endsWith(".jpg");
            long t = frame ? parseName(n.substring(2, n.length() - 4)) : -1;
            boolean keep = frame && t > latest - KEEP_MS && (t == latest || t % STEP_MS == 0);
            //noinspection ResultOfMethodCallIgnored
            if (!keep) f.delete();
        }
    }

    private boolean fetch(String url, File to) {
        File tmp = new File(to.getPath() + ".tmp");
        HttpURLConnection c = null;
        try {
            c = open(url);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            String type = c.getContentType();
            if (type == null || !type.startsWith("image/")) throw new IOException("not an image: " + type);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32 * 1024];
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
            if (bounds.outWidth < 256) throw new IOException("not a picture");
            if (!tmp.renameTo(to)) throw new IOException("could not save");
            return true;
        } catch (Exception e) {
            Log.w(TAG, to.getName() + " not saved: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * A frame ready to show, RGB_565: the Europe image at {@code size} px wide (1920, or 960 for the time-lapse), or
     * the disk turned into the view from the satellite, {@code size} px square. With a small dot on Rome. Null when
     * broken. Blocking: call it off the main thread.
     */
    Bitmap decode(Frame frame, int size, double romeLat, double romeLon) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(frame.file.getPath(), opts);
            if (opts.outWidth <= 0) return null;
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap out;
            float dotX, dotY;
            if (frame.view == LiveData.EUROPE) {
                opts.inSampleSize = SolarData.sampleSize(opts.outWidth, size);
                opts.inMutable = true;
                out = BitmapFactory.decodeFile(frame.file.getPath(), opts);
                if (out == null) return null;
                float[] p = LiveData.europePoint(romeLat, romeLon, out.getWidth(), out.getHeight());
                dotX = p[0];
                dotY = p[1];
            } else {
                opts.inSampleSize = SolarData.sampleSize(opts.outWidth, Math.min(opts.outWidth, size * 6 / 5));
                Bitmap grid = BitmapFactory.decodeFile(frame.file.getPath(), opts);
                if (grid == null) return null;
                int src = grid.getWidth();
                if (grid.getHeight() != src) return null;
                int[] in = new int[src * src];
                grid.getPixels(in, 0, src, 0, 0, src, src);
                grid.recycle();
                int[] t = table(size, src);
                int[] px = new int[size * size];
                for (int i = 0; i < px.length; i++) {
                    int k = t[i];
                    px[i] = k >= 0 ? in[k] : k == LiveData.NO_DATA ? 0xFF0B0F16 : 0xFF000000;
                }
                out = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                out.setPixels(px, 0, size, 0, 0, size, size);
                double[] d = LiveData.geoToDisk(romeLat, romeLon);
                dotX = d == null ? -1 : (float) ((d[0] + 1) / 2 * size);
                dotY = d == null ? -1 : (float) ((1 - d[1]) / 2 * size);
            }
            if (dotX >= 0 && !Double.isNaN(romeLat)) {
                Canvas c = new Canvas(out);
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                float r = Math.max(2f, out.getWidth() / 480f);
                dot.setColor(ROME_COLOR);
                dot.setAlpha(110);
                dot.setStyle(Paint.Style.STROKE);
                dot.setStrokeWidth(r * 0.6f);
                c.drawCircle(dotX, dotY, r * 2.4f, dot);
                dot.setStyle(Paint.Style.FILL);
                dot.setAlpha(230);
                c.drawCircle(dotX, dotY, r, dot);
            }
            return out;
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, frame.file.getName() + " not decoded", e);
            return null;
        }
    }

    private static synchronized int[] table(int out, int src) {
        long key = ((long) out << 32) | src;
        int[] t = TABLES.get(key);
        if (t == null) {
            if (TABLES.size() >= 2) TABLES.clear();
            t = LiveData.diskTable(out, src);
            TABLES.put(key, t);
        }
        return t;
    }

    private static String name(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(millis);
    }

    private static long parseName(String s) {
        if (s.length() != 12) return -1;
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        try {
            return f.parse(s).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > 256 * 1024) throw new IOException("reply too large");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "TvHome-launcher");
        return connection;
    }

    static List<Frame> lastSix(List<Frame> frames, long latest) {
        List<Frame> out = new ArrayList<>();
        for (Frame f : frames) if (f.time > latest - KEEP_MS) out.add(f);
        return out;
    }

}
