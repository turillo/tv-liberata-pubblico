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

/**
 * The Moon scene's picture: NASA SVS Dial-a-Moon's reply and picture for the current hour, fetched at most once an
 * hour and only while the screensaver runs, kept in files/moon/ so the scene works offline with the last one.
 */
final class MoonImages {

    private static final String TAG = "TvHome.MoonImages";
    private static final long REFRESH_MS = 60 * 60_000L;
    private static final long RETRY_MS = 10 * 60_000L;
    private static final int MAX_JSON_BYTES = 32 * 1024;
    private static final int MAX_IMAGE_BYTES = 2 << 20;

    private static long lastAttempt;

    private final File dir, json, image;

    /** For ServiceStatus: the result of each refresh, for Syntax TV, Sistema, Servizi. */
    private final Context app;

    MoonImages(Context context) {
        app = context.getApplicationContext();
        dir = new File(context.getFilesDir(), "moon");
        json = new File(dir, "moon.json");
        image = new File(dir, "moon.jpg");
    }

    /** The cached reply, or null when there is no picture. */
    MoonData.Reading load() {
        if (!image.isFile()) return null;
        try (InputStream in = new FileInputStream(json)) {
            return MoonData.parse(readAtMost(in, MAX_JSON_BYTES));
        } catch (IOException e) {
            return null;
        }
    }

    /** When the cached picture was fetched, in epoch millis; 0 when there is none. */
    long fetched() {
        return image.isFile() && json.isFile() ? json.lastModified() : 0;
    }

    boolean due(long now) {
        return now - fetched() >= REFRESH_MS && now - lastAttempt >= RETRY_MS;
    }

    /** Fetches this hour's reply and picture; true when both were saved. Blocking, off the main thread; never throws. */
    boolean refresh(long now) {
        lastAttempt = System.currentTimeMillis();
        File jsonTmp = new File(dir, "moon.json.tmp"), imageTmp = new File(dir, "moon.jpg.tmp");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            String text;
            HttpURLConnection c = open(MoonData.apiUrl(now));
            try {
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream()) {
                    text = readAtMost(in, MAX_JSON_BYTES);
                }
            } finally {
                c.disconnect();
            }
            MoonData.Reading reading = MoonData.parse(text);
            if (reading == null) throw new IOException("not a Dial-a-Moon reply");
            c = open(reading.imageUrl);
            try {
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(imageTmp)) {
                    byte[] buf = new byte[16 * 1024];
                    int n, total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_IMAGE_BYTES) throw new IOException("image too large");
                        out.write(buf, 0, n);
                    }
                }
            } finally {
                c.disconnect();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageTmp.getPath(), bounds);
            if (bounds.outWidth < 256 || bounds.outWidth != bounds.outHeight) throw new IOException("not a Moon picture");
            try (OutputStream out = new FileOutputStream(jsonTmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!imageTmp.renameTo(image) || !jsonTmp.renameTo(json)) throw new IOException("could not save");
            ServiceStatus.ok(app, ServiceStatus.MOON);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Dial-a-Moon not refreshed: " + e.getMessage());
            ServiceStatus.fail(app, ServiceStatus.MOON, e);
            //noinspection ResultOfMethodCallIgnored
            imageTmp.delete();
            //noinspection ResultOfMethodCallIgnored
            jsonTmp.delete();
            return false;
        }
    }

    /** The cached picture at least {@code target} px wide, as RGB_565; null when missing or broken. */
    Bitmap decode(int target) {
        if (!image.isFile()) return null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getPath(), opts);
            if (opts.outWidth <= 0) return null;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = SolarData.sampleSize(opts.outWidth, target);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(image.getPath(), opts);
        } catch (OutOfMemoryError | RuntimeException e) {
            Log.w(TAG, "Moon picture not decoded", e);
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(20_000);
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
