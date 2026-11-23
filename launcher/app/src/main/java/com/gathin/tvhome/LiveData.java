package com.gathin.tvhome;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java facts for the live Earth screensaver scene (dream_scene "live"), apart from Android so they can be
 * unit-tested: EUMETSAT's EUMETView WMS (view.eumetsat.int, public, no key) for Meteosat-12's GeoColour RGB (true
 * colour by day, infrared clouds and city lights by night), the time of its latest image, the two views (the whole
 * disk, which the WMS only serves as a latitude/longitude grid and the TV turns into the view from the satellite,
 * and Europe and the Mediterranean in Web Mercator with Italy in the middle), and where Rome falls in each.
 */
final class LiveData {

    static final String LAYER = "mtg_fd:rgb_geocolour";
    /** The layer's own capabilities (6.5 KB), then the whole mtg_fd workspace's (35 KB) when that fails. */
    static final String[] CAPS_URLS = {
            "https://view.eumetsat.int/geoserver/mtg_fd/rgb_geocolour/ows?service=WMS&version=1.3.0&request=GetCapabilities",
            "https://view.eumetsat.int/geoserver/mtg_fd/ows?service=WMS&version=1.3.0&request=GetCapabilities"};
    private static final String MAP_URL = "https://view.eumetsat.int/geoserver/ows?service=WMS&version=1.3.0&request=GetMap"
            + "&styles=&format=image/jpeg&layers=" + LAYER + "&crs=%s&bbox=%s&width=%d&height=%d&time=%s";

    static final int DISK = 0, EUROPE = 1;
    /** The disk view's source: the layer's whole extent, latitude and longitude -70..70, as a square grid. */
    static final int GRID_SIZE = 1200;
    static final double GRID_EDGE = 70;
    static final int EUROPE_W = 1920, EUROPE_H = 1080;

    /** Europe: longitude -14..39 with Italy in the middle, 16:9, in Web Mercator metres. */
    static final double[] EUROPE_BOX = europeBox(12.5, 42.0, -14, 39);

    /** Satellite distance from the Earth's centre and Earth radius, km (a sphere is plenty for a picture). */
    private static final double SAT_KM = 42164.0, EARTH_KM = 6378.137;
    private static final double MAX_SCAN = Math.asin(EARTH_KM / SAT_KM);
    private static final double MERC_R = 6378137.0;

    /** Disk pixels on the Earth but outside the WMS data (beyond 70 degrees): a dark grey so the disk stays round. */
    static final int NO_DATA = -2, SPACE = -1;

    private static final Pattern DEFAULT_TIME = Pattern.compile(
            "<Dimension\\s+name=\"time\"[^>]*default=\"([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2})?(?:\\.[0-9]+)?Z)\"");

    private LiveData() {
    }

    /**
     * A capabilities URL with a parameter that changes every minute: EUMETView's cache once kept serving a stale
     * error page for the plain URL to everyone.
     */
    static String capsUrl(int i, long now) {
        return CAPS_URLS[i] + "&_=" + now / 60_000L;
    }

    /**
     * The newest GeoColour image time in a GetCapabilities reply (the layer's own, or a workspace listing several
     * layers), in epoch millis, or -1.
     */
    static long latestTime(String caps) {
        if (caps == null) return -1;
        int at = caps.indexOf("<Name>rgb_geocolour</Name>");
        if (at < 0) at = caps.indexOf("<Name>" + LAYER + "</Name>");
        Matcher m = DEFAULT_TIME.matcher(caps);
        if (!m.find(Math.max(0, at))) return -1;
        String t = m.group(1).replaceFirst("\\.[0-9]+Z$", "Z");
        if (t.length() == 17) t = t.substring(0, 16) + ":00Z";
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        f.setLenient(false);
        try {
            return f.parse(t).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    static String isoUtc(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(millis));
    }

    /** The GetMap URL for a view at a time. */
    static String mapUrl(int view, long millis) {
        if (view == DISK) {
            String e = fmt(GRID_EDGE);
            return String.format(Locale.ROOT, MAP_URL, "EPSG:4326", "-" + e + ",-" + e + "," + e + "," + e,
                    GRID_SIZE, GRID_SIZE, isoUtc(millis));
        }
        double[] b = EUROPE_BOX;
        return String.format(Locale.ROOT, MAP_URL, "EPSG:3857",
                fmt(b[0]) + "," + fmt(b[1]) + "," + fmt(b[2]) + "," + fmt(b[3]), EUROPE_W, EUROPE_H, isoUtc(millis));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    /** A 16:9 Web Mercator box {minX, minY, maxX, maxY} spanning lonWest..lonEast, centred on (lat, lon). */
    static double[] europeBox(double lon, double lat, double lonWest, double lonEast) {
        double cx = mercX(lon), cy = mercY(lat);
        double w = mercX(lonEast) - mercX(lonWest), h = w * 9 / 16;
        return new double[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2};
    }

    static double mercX(double lon) {
        return MERC_R * Math.toRadians(lon);
    }

    static double mercY(double lat) {
        return MERC_R * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
    }

    /** Where (lat, lon) falls in the Europe image, {x, y} in pixels of a w x h image. */
    static float[] europePoint(double lat, double lon, int w, int h) {
        double[] b = EUROPE_BOX;
        return new float[]{(float) ((mercX(lon) - b[0]) / (b[2] - b[0]) * w),
                (float) ((b[3] - mercY(lat)) / (b[3] - b[1]) * h)};
    }

    /**
     * The point of the Earth seen at disk coordinates (u, v), each -1..1 with the Earth's limb at radius 1, from a
     * satellite over latitude 0 and longitude 0: {lat, lon} in degrees, or null for space.
     */
    static double[] diskToGeo(double u, double v) {
        double ax = u * MAX_SCAN, ay = v * MAX_SCAN;
        double dx = -Math.cos(ay) * Math.cos(ax), dy = Math.cos(ay) * Math.sin(ax), dz = Math.sin(ay);
        double b = SAT_KM * dx;   // S . d, with S = (SAT_KM, 0, 0)
        double disc = b * b - (SAT_KM * SAT_KM - EARTH_KM * EARTH_KM);
        if (disc < 0) return null;
        double t = -b - Math.sqrt(disc);
        double px = SAT_KM + t * dx, py = t * dy, pz = t * dz;
        return new double[]{Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, pz / EARTH_KM)))),
                Math.toDegrees(Math.atan2(py, px))};
    }

    /** Disk coordinates {u, v} (-1..1) of (lat, lon) seen from the satellite, or null when on the far side. */
    static double[] geoToDisk(double lat, double lon) {
        double la = Math.toRadians(lat), lo = Math.toRadians(lon);
        double px = EARTH_KM * Math.cos(la) * Math.cos(lo), py = EARTH_KM * Math.cos(la) * Math.sin(lo),
                pz = EARTH_KM * Math.sin(la);
        double dx = px - SAT_KM, dy = py, dz = pz;
        if (px * (SAT_KM - px) - py * py - pz * pz < 0) return null;   // facing away from the satellite
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ay = Math.asin(dz / n), ax = Math.atan2(dy / n, -dx / n);
        return new double[]{ax / MAX_SCAN, ay / MAX_SCAN};
    }

    /**
     * For each pixel of an out x out disk image (y down, north up), the index into a src x src latitude/longitude
     * grid (-70..70, row 0 at the top) to copy, SPACE for space or NO_DATA for Earth outside the grid. Computed once
     * per size, so turning each new image into the disk is only copying pixels.
     */
    static int[] diskTable(int out, int src) {
        int[] table = new int[out * out];
        double half = out / 2.0;
        for (int y = 0; y < out; y++) {
            double v = (half - (y + 0.5)) / half;
            for (int x = 0; x < out; x++) {
                double u = ((x + 0.5) - half) / half;
                int i = y * out + x;
                if (u * u + v * v > 1) {
                    table[i] = SPACE;
                    continue;
                }
                double[] g = diskToGeo(u, v);
                if (g == null) {
                    table[i] = SPACE;
                    continue;
                }
                double fx = (g[1] + GRID_EDGE) / (2 * GRID_EDGE) * src, fy = (GRID_EDGE - g[0]) / (2 * GRID_EDGE) * src;
                int sx = (int) fx, sy = (int) fy;
                table[i] = sx >= 0 && sx < src && sy >= 0 && sy < src ? sy * src + sx : NO_DATA;
            }
        }
        return table;
    }

    /** Whether the Sun is up (above -6 degrees, civil twilight) at (lat, lon) at {@code millis}. */
    static boolean daylight(double lat, double lon, long millis) {
        double[] sun = Orbit.subsolar(millis);
        return Orbit.sunElevation(lat, lon, sun[0], sun[1]) > -6;
    }
}
