package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The ISS's orbit (a TLE) for the ISS screensaver scene. Downloaded from CelesTrak at most once a day, and
 * only while the screensaver runs; kept in a file so the last valid one is used offline. Before the first
 * download a TLE bundled with the build stands in, which drifts by minutes a week: the scene says how old it is.
 */
final class IssTle {

    static final int ISS = 25544;
    private static final String TAG = "TvHome.IssTle";
    private static final String URL_TLE = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE";
    private static final long REFRESH_MS = 24 * 3_600_000L;
    /** After a failed download, the next try waits this long. */
    private static final long RETRY_MS = 3_600_000L;
    /** Bundled fallback, CelesTrak 2026-11-03. */
    private static final String SEED = "ISS (ZARYA)\n"
            + "1 25544U 98067A   26307.53092409  .00005389  00000+0  10530-3 0  9996\n"
            + "2 25544  51.6330 330.5488 0007153 343.6919  16.3838 15.49164192593358\n";

    private final File file;
    private static long lastAttempt;

    /** For ServiceStatus: the result of each refresh, for Syntax TV, Sistema, Servizi. */
    private final Context app;

    IssTle(Context context) {
        app = context.getApplicationContext();
        file = new File(context.getFilesDir(), "iss_tle.txt");
    }

    /** The saved TLE, or the bundled one when there is none or it is unreadable. */
    Sgp4 load() {
        Sgp4 saved = parse(readFile());
        return saved != null ? saved : parse(SEED);
    }

    /** Whether a download is due: the saved TLE is a day old (or missing) and no try failed in the last hour. */
    boolean due(long now) {
        long saved = file.isFile() ? file.lastModified() : 0;
        return now - saved >= REFRESH_MS && now - lastAttempt >= RETRY_MS;
    }

    /**
     * Downloads a fresh TLE and saves it when it is valid and not older than {@code current}; returns it, or
     * null on any failure. Blocking: call it off the main thread.
     */
    Sgp4 download(Sgp4 current) {
        lastAttempt = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(URL_TLE).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "TvHome-launcher");
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            String text;
            try (InputStream in = connection.getInputStream()) {
                text = readAtMost(in, 4096);
            }
            Sgp4 fresh = parse(text);
            if (fresh == null) throw new IOException("not a valid ISS TLE");
            ServiceStatus.ok(app, ServiceStatus.ISS);   // CelesTrak answered; an older TLE is not a failure
            if (current != null && fresh.epochJd < current.epochJd) return null;
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            if (!tmp.renameTo(file)) throw new IOException("could not save " + file);
            return fresh;
        } catch (Exception e) {
            Log.w(TAG, "ISS orbit not refreshed: " + e.getMessage());
            ServiceStatus.fail(app, ServiceStatus.ISS, e);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** A valid TLE for the ISS, or null. */
    static Sgp4 parse(String text) {
        try {
            Sgp4 tle = Sgp4.parse(text);
            return tle.catalog == ISS ? tle : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String readFile() {
        if (!file.isFile() || file.length() > 4096) return null;
        try (InputStream in = new FileInputStream(file)) {
            return readAtMost(in, 4096);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
