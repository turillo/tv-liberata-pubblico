package com.gathin.tvhome;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java facts for the Earth screensaver scene (dream_scene "earth"), apart from Android so they can be
 * unit-tested: NASA EPIC's lists of the latest day's photos (api/natural, true colour, and api/enhanced, colours
 * brought out), which collection to show, each photo's archive URL, DSCOVR's distance from the Earth, and a name for
 * the part of the Earth in the middle.
 */
final class EarthData {

    static final String NATURAL_URL = "https://epic.gsfc.nasa.gov/api/natural";
    static final String ENHANCED_URL = "https://epic.gsfc.nasa.gov/api/enhanced";
    private static final String IMAGE_URL = "https://epic.gsfc.nasa.gov/archive/%s/%04d/%02d/%02d/jpg/%s.jpg";
    /** Enhanced photos are named epic_RGB_..., natural ones epic_1b_... */
    private static final String ENHANCED_PREFIX = "epic_RGB_";

    /**
     * The Earth's disk diameter as a fraction of the 1080 px EPIC JPEG's width: 845 px measured on natural photos,
     * 816 px on enhanced ones.
     */
    static final float DISK = 0.783f;
    static final float DISK_ENHANCED = 0.756f;

    private static final String NUM = "(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)";
    private static final Pattern IMAGE = Pattern.compile("\"image\"\\s*:\\s*\"(epic_[A-Za-z0-9_]{1,60})\"");
    private static final Pattern DATE = Pattern.compile("\"date\"\\s*:\\s*\"([0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2})\"");
    private static final Pattern CENTRE = Pattern.compile(
            "\"centroid_coordinates\"\\s*:\\s*\\{\\s*\"lat\"\\s*:\\s*" + NUM + "\\s*,\\s*\"lon\"\\s*:\\s*" + NUM);
    private static final Pattern DSCOVR = Pattern.compile("\"dscovr_j2000_position\"\\s*:\\s*\\{\\s*\"x\"\\s*:\\s*" + NUM
            + "\\s*,\\s*\"y\"\\s*:\\s*" + NUM + "\\s*,\\s*\"z\"\\s*:\\s*" + NUM);

    /** Region names, in the order of the earth_regions string array. */
    static final int ATLANTIC = 0, PACIFIC = 1, INDIAN = 2, MEDITERRANEAN = 3, ARCTIC = 4, SOUTHERN = 5,
            EUROPE = 6, AFRICA = 7, ASIA = 8, OCEANIA = 9, NORTH_AMERICA = 10, SOUTH_AMERICA = 11, ANTARCTICA = 12;

    private EarthData() {
    }

    /** One EPIC photo. */
    static final class Shot {
        final String image;        // file name without .jpg, e.g. epic_1b_20261101115250
        final long timeMillis;     // when it was taken (EPIC "date", UTC)
        final double lat, lon;     // the point of the Earth in the middle of the photo
        final double distanceKm;   // DSCOVR from the Earth's centre; 0 when missing

        Shot(String image, long timeMillis, double lat, double lon, double distanceKm) {
            this.image = image;
            this.timeMillis = timeMillis;
            this.lat = lat;
            this.lon = lon;
            this.distanceKm = distanceKm;
        }

        /** From the enhanced collection (colours brought out) rather than the natural one. */
        boolean enhanced() {
            return image.startsWith(ENHANCED_PREFIX);
        }

        /** The Earth's diameter as a fraction of this photo's width. */
        float disk() {
            return enhanced() ? DISK_ENHANCED : DISK;
        }
    }

    /**
     * Whether to show the enhanced photos: only when that list's latest photo is from the same UTC day as the
     * natural list's latest (EPIC often publishes the enhanced ones days later).
     */
    static boolean useEnhanced(List<Shot> natural, List<Shot> enhanced) {
        if (natural.isEmpty() || enhanced.isEmpty()) return false;
        return utcDay(natural.get(natural.size() - 1).timeMillis) == utcDay(enhanced.get(enhanced.size() - 1).timeMillis);
    }

    private static long utcDay(long millis) {
        return Math.floorDiv(millis, 86_400_000L);
    }

    /** The photos in an EPIC list, oldest first; empty when the text is not one. Never throws. */
    static List<Shot> parse(String json) {
        List<Shot> shots = new ArrayList<>();
        if (json == null) return shots;
        // Each photo's fields follow its "identifier"; nested objects make a regex over {...} unreliable.
        String[] items = json.split("\"identifier\"");
        for (int i = 1; i < items.length; i++) {
            String item = items[i];
            Matcher image = IMAGE.matcher(item);
            Matcher date = DATE.matcher(item);
            Matcher centre = CENTRE.matcher(item);
            if (!image.find() || !date.find() || !centre.find()) continue;
            long time = parseUtc(date.group(1));
            double lat, lon;
            try {
                lat = Double.parseDouble(centre.group(1));
                lon = Double.parseDouble(centre.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (time <= 0 || Math.abs(lat) > 90 || Math.abs(lon) > 180) continue;
            double km = 0;
            Matcher d = DSCOVR.matcher(item);
            if (d.find()) {
                try {
                    double x = Double.parseDouble(d.group(1)), y = Double.parseDouble(d.group(2)),
                            z = Double.parseDouble(d.group(3));
                    km = Math.sqrt(x * x + y * y + z * z);
                } catch (NumberFormatException ignored) {
                    km = 0;
                }
            }
            shots.add(new Shot(image.group(1), time, lat, lon, km));
        }
        Collections.sort(shots, (a, b) -> Long.compare(a.timeMillis, b.timeMillis));
        return shots;
    }

    /** "2026-11-01 11:48:01" (UTC) in epoch millis, or -1. */
    static long parseUtc(String text) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try {
            return format.parse(text).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }

    /** The photo's JPEG (1080 px) in EPIC's archive, under its collection and UTC date. */
    static String imageUrl(Shot shot) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(shot.timeMillis);
        return String.format(Locale.ROOT, IMAGE_URL, shot.enhanced() ? "enhanced" : "natural", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), shot.image);
    }

    /**
     * A rough name for the place at (lat, lon): an ocean when {@code land} is false, a continent when true.
     * Boxes, not borders: good enough to say what is in the middle of a whole-Earth photo.
     */
    static int region(double lat, double lon, boolean land) {
        if (land) {
            if (lat < -60) return ANTARCTICA;
            if (lon < -30) return lat > 12 || lon < -82 ? NORTH_AMERICA : SOUTH_AMERICA;
            if (lat > 35 && lon < 60) return EUROPE;
            if (lon >= 34 && lat > 12) return ASIA;
            if (lon < 52) return AFRICA;
            if (lon >= 110 && lat < -10) return OCEANIA;
            return ASIA;
        }
        if (lat > 66) return ARCTIC;
        if (lat < -60) return SOUTHERN;
        if (lat > 30 && lat < 46 && lon > -6 && lon < 36) return MEDITERRANEAN;
        if (lon >= -70 && lon <= 20) return ATLANTIC;
        if (lat > 18 && lon > -100 && lon < -70) return ATLANTIC;   // Gulf of Mexico and the Caribbean
        if (lon > 20 && (lon < 100 || (lon < 147 && lat < -8))) return INDIAN;
        return PACIFIC;
    }

    /** Whether (lon, lat) is inside the land outlines (lon, lat pairs), by the even-odd rule. */
    static boolean onLand(float[][] rings, double lat, double lon) {
        if (rings == null) return false;
        boolean inside = false;
        for (float[] ring : rings) {
            int n = ring.length / 2;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                double xi = ring[2 * i], yi = ring[2 * i + 1], xj = ring[2 * j], yj = ring[2 * j + 1];
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside;
            }
        }
        return inside;
    }
}
