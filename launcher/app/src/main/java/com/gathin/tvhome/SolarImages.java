package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Shader;
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

/**
 * The Sun scene's pictures: NASA SDO's latest 1024 px image for each channel and NOAA SWPC's last flare,
 * downloaded at most every 15 minutes and only while the screensaver runs. Each image is kept in
 * files/sun/ with its publication time, so the scene works offline with the last ones it got.
 */
final class SolarImages {

    private static final String TAG = "TvHome.SolarImages";
    private static final String IMAGE_URL = "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_%s.jpg";
    private static final String FLARE_URL = "https://services.swpc.noaa.gov/json/goes/primary/xray-flares-latest.json";
    private static final String FLARE_WEEK_URL = "https://services.swpc.noaa.gov/json/goes/primary/xray-flares-7-day.json";
    private static final long REFRESH_MS = 15 * 60_000L;
    /** After a failed round, the next try waits this long. */
    private static final long RETRY_MS = 5 * 60_000L;
    private static final int MAX_IMAGE_BYTES = 3 << 20;
    private static final int GLOW = 0xFF3A1E08;

    private static long lastAttempt;

    private final File dir;
    private final SharedPreferences prefs;

    /** For ServiceStatus: the result of each refresh, for Syntax TV, Sistema, Servizi. */
    private final Context app;

    /** The last failure of this refresh, for the status. */
    private String lastError;

    SolarImages(Context context) {
        app = context.getApplicationContext();
        dir = new File(context.getFilesDir(), "sun");
        prefs = context.getSharedPreferences("sun_cache", Context.MODE_PRIVATE);
    }

    private File file(int channel) {
        return new File(dir, SolarData.CODES[channel] + ".jpg");
    }

    boolean has(int channel) {
        return file(channel).isFile();
    }

    /** When SDO published the cached image (its Last-Modified), in epoch millis; 0 when unknown. */
    long published(int channel) {
        return prefs.getLong("t_" + SolarData.CODES[channel], 0);
    }

    /** The last flare from the cache, or null. */
    SolarData.Flare flare() {
        String cls = prefs.getString("flare_class", null);
        long time = prefs.getLong("flare_time", 0);
        return cls != null && time > 0 ? new SolarData.Flare(cls, time) : null;
    }

    /** Whether a download round is due: the last good one is 15 minutes old and no round failed in 5. */
    boolean due(long now) {
        return now - prefs.getLong("checked", 0) >= REFRESH_MS && now - lastAttempt >= RETRY_MS;
    }

    /**
     * Downloads what changed (If-Modified-Since, so an unchanged image costs one request) and the last flare.
     * Returns whether any image changed. Blocking: call it off the main thread. Never throws.
     */
    boolean refresh() {
        lastAttempt = System.currentTimeMillis();
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        boolean changed = false, reached = false;
        for (int i = 0; i < SolarData.CODES.length; i++) {
            if (Thread.currentThread().isInterrupted()) return changed;
            int result = fetchImage(i);
            if (result >= 0) reached = true;
            if (result > 0) changed = true;
        }
        if (fetchFlare()) reached = true;
        if (reached) prefs.edit().putLong("checked", System.currentTimeMillis()).apply();
        if (reached) ServiceStatus.ok(app, ServiceStatus.SDO);
        else ServiceStatus.fail(app, ServiceStatus.SDO, lastError != null ? lastError : "nessuna risposta");
        return changed;
    }

    /** 1 when a new image was saved, 0 when it had not changed, -1 on any failure. */
    private int fetchImage(int channel) {
        String code = SolarData.CODES[channel];
        HttpURLConnection connection = null;
        File tmp = new File(dir, code + ".tmp");
        try {
            connection = open(String.format(IMAGE_URL, code));
            long since = published(channel);
            if (since > 0 && has(channel)) connection.setIfModifiedSince(since);
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return 0;
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + status);
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
            if (bounds.outWidth < 256 || bounds.outWidth != bounds.outHeight) throw new IOException("not an SDO image");
            if (!tmp.renameTo(file(channel))) throw new IOException("could not save " + code);
            long modified = connection.getLastModified();
            prefs.edit().putLong("t_" + code, modified > 0 ? modified : System.currentTimeMillis()).apply();
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "SDO " + code + " not refreshed: " + e.getMessage());
            lastError = ServiceHealth.reason(e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** The last finished flare: from the latest one, or the 7-day list while the latest is still in progress. */
    private boolean fetchFlare() {
        int latest = fetchFlare(FLARE_URL);
        if (latest == 0) return fetchFlare(FLARE_WEEK_URL) >= 0;
        return latest > 0;
    }

    /** 1 when a flare was saved, 0 when the reply had none finished, -1 on any failure. */
    private int fetchFlare(String url) {
        HttpURLConnection connection = null;
        try {
            connection = open(url);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return -1;
            String text;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 32 * 1024) out.write(buf, 0, n);
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            SolarData.Flare flare = SolarData.parseFlare(text);
            if (flare == null) return 0;
            prefs.edit().putString("flare_class", flare.flareClass).putLong("flare_time", flare.peakMillis).apply();
            return 1;
        } catch (Exception e) {
            Log.w(TAG, "flare not refreshed: " + e.getMessage());
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "TvHome-launcher");
        return connection;
    }

    /**
     * The cached image for {@code channel}, at least {@code target} px wide, as RGB_565 (2 bytes a pixel) with
     * everything past the corona faded to black (which also hides SDO's caption) and a faint warm glow around
     * the disk. Null when there is none or it cannot be decoded. Blocking: call it off the main thread.
     */
    Bitmap decode(int channel, int target) {
        File f = file(channel);
        if (!f.isFile()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), opts);
            if (opts.outWidth <= 0) return null;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = SolarData.sampleSize(opts.outWidth, target);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inMutable = true;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) return null;
            float cx = b.getWidth() / 2f, cy = b.getHeight() / 2f, half = Math.min(cx, cy);
            float in = SolarData.MASK_IN[channel], out = SolarData.MASK_OUT[channel];
            float disk = SolarData.DISK[channel];
            Canvas canvas = new Canvas(b);
            Paint mask = new Paint();
            mask.setShader(new RadialGradient(cx, cy, half * out, new int[]{0, 0, 0xFF000000},
                    new float[]{0f, in / out, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, b.getWidth(), b.getHeight(), mask);
            Paint glow = new Paint();
            glow.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            float edge = 0.99f;
            glow.setShader(new RadialGradient(cx, cy, half * edge, new int[]{0, 0, GLOW, 0},
                    new float[]{0f, disk / edge, (disk + (edge - disk) * 0.25f) / edge, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, half * edge, glow);
            return b;
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, "SDO " + SolarData.CODES[channel] + " not decoded", e);
            return null;
        }
    }
}
