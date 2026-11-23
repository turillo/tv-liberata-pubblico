package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the TV is, for the weather, the sun and the scenes' home dot. In order: config.xml latitude and longitude
 * (the owner's TVs), a city chosen in Syntax TV (Open-Meteo's geocoding), the place found from the internet
 * connection (ipwho.is: free, no key, HTTPS, commercial use allowed, 1,000 requests a day per address; asked at
 * most once a week), or nothing: no weather rather than a made-up place. Saved in SharedPreferences "place".
 */
final class Place {

    private static final String TAG = "TvHome.Place";
    static final String PREFS = "place";
    static final String IP_URL = "https://ipwho.is/?fields=success,city,latitude,longitude,country_code";
    static final long AUTO_EVERY = 7 * 24 * 3_600_000L, AUTO_RETRY = 6 * 3_600_000L;

    /** A place: coordinates and the name shown in Syntax TV. */
    static final class Spot {
        final double lat, lon;
        final String name;

        Spot(double lat, double lon, String name) {
            this.lat = lat;
            this.lon = lon;
            this.name = name;
        }

        boolean valid() {
            return Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0);
        }
    }

    private Place() {
    }

    /** {latitude, longitude} for this TV, or null when nothing is known yet. Cheap: no network. */
    static double[] home(Context c) {
        Spot s = spot(c);
        return s == null ? null : new double[]{s.lat, s.lon};
    }

    /** The place in use, or null. */
    static Spot spot(Context c) {
        SharedPreferences p = prefs(c);
        Spot manual = read(p, "manual");   // a city chosen by hand always wins: the place stays correctable
        if (manual != null) return manual;
        Spot device = DeviceConfig.place(c);   // then the device configuration's coordinates
        if (device != null) return device;
        Spot fixed = configured(c);
        if (fixed != null) return fixed;
        return read(p, "auto");
    }

    /** True when the city was chosen by hand (Syntax TV), false when automatic or from config.xml. */
    static boolean manual(Context c) {
        return read(prefs(c), "manual") != null;
    }

    static void setManual(Context c, Spot s) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (s == null) e.remove("manual_lat").remove("manual_lon").remove("manual_name");
        else e.putString("manual_lat", String.valueOf(s.lat)).putString("manual_lon", String.valueOf(s.lon))
                .putString("manual_name", s.name == null ? "" : s.name);
        e.putLong("auto_next", 0).apply();   // "Automatica" asks again at once
    }

    /**
     * The automatic place, from the internet connection, when there is no other and it is due (once a week; six
     * hours after a failure). Blocking: call it on a network thread. Never throws.
     */
    static void refreshAuto(Context c) {
        if (DeviceConfig.place(c) != null || configured(c) != null || read(prefs(c), "manual") != null) return;
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (now < p.getLong("auto_next", 0)) return;
        try {
            Spot s = parseIp(get(IP_URL));
            if (s == null) throw new IOException("risposta senza posizione");
            p.edit().putString("auto_lat", String.valueOf(s.lat)).putString("auto_lon", String.valueOf(s.lon))
                    .putString("auto_name", s.name == null ? "" : s.name).putLong("auto_next", now + AUTO_EVERY).apply();
            Log.i(TAG, "place from the connection: " + s.name);
            ServiceStatus.ok(c, ServiceStatus.GEO);
        } catch (IOException | RuntimeException e) {
            p.edit().putLong("auto_next", now + AUTO_RETRY).apply();
            Log.w(TAG, "place from the connection unavailable: " + e.getMessage());
            ServiceStatus.fail(c, ServiceStatus.GEO, e.getMessage() == null ? "errore" : e.getMessage());
        }
    }

    /** ipwho.is's reply: {"success":true,"city":"Rome","latitude":41.89,"longitude":12.51,...}; null otherwise. */
    static Spot parseIp(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) return null;
        Object ok = ((Map<?, ?>) root).get("success");
        if (!(ok instanceof Boolean) || !(Boolean) ok) return null;
        double lat = MiniJson.number(root, "latitude", Double.NaN), lon = MiniJson.number(root, "longitude", Double.NaN);
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        Spot s = new Spot(lat, lon, MiniJson.str(root, "city"));
        return s.valid() ? s : null;
    }

    /** Open-Meteo's city search, in Italian, up to 8 results. */
    static String searchUrl(String query) {
        try {
            return "https://geocoding-api.open-meteo.com/v1/search?count=8&language=it&format=json&name="
                    + URLEncoder.encode(query.trim(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The search reply as places named "Roma (Lazio, Italia)". */
    static List<Spot> parseSearch(String json) {
        List<Spot> out = new ArrayList<>();
        List<?> results = MiniJson.list(MiniJson.parse(json), "results");
        if (results == null) return out;
        for (Object o : results) {
            String name = MiniJson.str(o, "name");
            double lat = MiniJson.number(o, "latitude", Double.NaN), lon = MiniJson.number(o, "longitude", Double.NaN);
            if (name == null || Double.isNaN(lat) || Double.isNaN(lon)) continue;
            List<String> where = new ArrayList<>();
            String region = MiniJson.str(o, "admin1"), country = MiniJson.str(o, "country");
            if (region != null && !region.isEmpty() && !region.equals(name)) where.add(region);
            if (country != null && !country.isEmpty()) where.add(country);
            Spot s = new Spot(lat, lon, where.isEmpty() ? name : name + " (" + String.join(", ", where) + ")");
            if (s.valid()) out.add(s);
        }
        return out;
    }

    /** Blocking search; empty on any failure. */
    static List<Spot> search(String query) {
        if (query == null || query.trim().length() < 2) return new ArrayList<>();
        try {
            return parseSearch(get(searchUrl(query)));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "city search failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    /** config.xml latitude and longitude, or null when empty or out of range (TV Liberata has none). */
    static Spot configured(Context c) {
        try {
            Spot s = new Spot(Double.parseDouble(c.getString(R.string.latitude).trim()),
                    Double.parseDouble(c.getString(R.string.longitude).trim()), null);
            return s.valid() ? s : null;
        } catch (NumberFormatException | android.content.res.Resources.NotFoundException e) {
            return null;
        }
    }

    private static Spot read(SharedPreferences p, String kind) {
        try {
            String lat = p.getString(kind + "_lat", null), lon = p.getString(kind + "_lon", null);
            if (lat == null || lon == null) return null;
            Spot s = new Spot(Double.parseDouble(lat), Double.parseDouble(lon), p.getString(kind + "_name", ""));
            return s.valid() ? s : null;
        } catch (NumberFormatException | ClassCastException e) {
            return null;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME + " (Android TV launcher)");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() > 256_000) throw new IOException("risposta troppo grande");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** "41.892, 12.511" for the Sistema page and the log. */
    static String describe(Spot s) {
        return s == null ? "" : String.format(Locale.US, "%.3f, %.3f", s.lat, s.lon);
    }
}
