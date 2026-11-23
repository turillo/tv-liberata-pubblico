package com.gathin.tvhome;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java facts for the Moon screensaver scene (dream_scene "moon"), apart from Android so they can be
 * unit-tested: NASA SVS Dial-a-Moon's reply (the picture of the Moon for an hour, north up as seen from the
 * northern hemisphere, with its phase, age and distance), the phase's name, the next new and full Moon (Meeus,
 * Astronomical Algorithms ch. 49, to a few minutes) and moonrise and moonset at a place (the Moon's position from
 * the main terms of Meeus ch. 47, to a few minutes).
 */
final class MoonData {

    /** Plain HTTP: the server accepts only TLS 1.3, which Android 9 lacks (res/xml/network_security_config.xml). */
    private static final String API = "http://svs.gsfc.nasa.gov/api/dialamoon/%04d-%02d-%02dT%02d:00";
    private static final String IMAGE_HOST = "http://svs.gsfc.nasa.gov/";

    /** The Moon's disk (with its dark side) as a fraction of the 730 px picture's width, measured. */
    static final float DISK = 0.897f;

    static final double SYNODIC_DAYS = 29.530588861;
    private static final double DEG = Math.PI / 180;
    /** TT minus UTC around 2026, in days. */
    private static final double DELTA_T = 69.0 / 86400;

    /** Phase names, in the order of the moon_phases string array. */
    static final int NEW = 0, WAXING_CRESCENT = 1, FIRST_QUARTER = 2, WAXING_GIBBOUS = 3, FULL = 4,
            WANING_GIBBOUS = 5, LAST_QUARTER = 6, WANING_CRESCENT = 7;

    private static final Pattern IMAGE = Pattern.compile(
            "\"image\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"(https?://svs\\.gsfc\\.nasa\\.gov/[A-Za-z0-9_./-]+\\.jpg)\"");

    private MoonData() {
    }

    /** One Dial-a-Moon reply. */
    static final class Reading {
        final String imageUrl;     // http, on svs.gsfc.nasa.gov
        final double phase;        // percent illuminated
        final double ageDays;      // days since new Moon
        final double distanceKm;   // from the Earth's centre

        Reading(String imageUrl, double phase, double ageDays, double distanceKm) {
            this.imageUrl = imageUrl;
            this.phase = phase;
            this.ageDays = ageDays;
            this.distanceKm = distanceKm;
        }
    }

    /** The Dial-a-Moon URL for the hour (UTC) containing {@code millis}. */
    static String apiUrl(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(millis);
        return String.format(Locale.ROOT, API, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY));
    }

    /** A Dial-a-Moon reply, or null when it lacks the north-up picture or sane numbers. Never throws. */
    static Reading parse(String json) {
        if (json == null) return null;
        Matcher image = IMAGE.matcher(json);
        if (!image.find()) return null;
        String url = image.group(1).replaceFirst("^https://", "http://");
        if (!url.startsWith(IMAGE_HOST) || url.contains("..")) return null;
        double phase = number(json, "phase"), age = number(json, "age"), km = number(json, "distance");
        if (!(phase >= 0 && phase <= 100) || !(age >= 0 && age < 30) || !(km > 300_000 && km < 420_000)) return null;
        return new Reading(url, phase, age, km);
    }

    private static double number(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").matcher(json);
        if (!m.find()) return Double.NaN;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The phase's name for a percent illuminated and whether the Moon is waxing (age under half a month). */
    static int phaseName(double percent, boolean waxing) {
        if (percent < 2) return NEW;
        if (percent > 98) return FULL;
        if (percent >= 45 && percent <= 55) return waxing ? FIRST_QUARTER : LAST_QUARTER;
        if (percent < 45) return waxing ? WAXING_CRESCENT : WANING_CRESCENT;
        return waxing ? WAXING_GIBBOUS : WANING_GIBBOUS;
    }

    static boolean waxing(double ageDays) {
        return ageDays < SYNODIC_DAYS / 2;
    }

    // ---- new and full Moon (Meeus ch. 49) -------------------------------------------------------------------

    /** The first new Moon (full = false) or full Moon (full = true) after {@code millis}, in epoch millis. */
    static long next(long millis, boolean full) {
        double jd = julian(millis);
        double k = Math.floor((jd - 2451550.09766) / SYNODIC_DAYS) - 1 + (full ? 0.5 : 0);
        for (int i = 0; i < 4; i++, k += 1) {
            double t = phaseJde(k, full) - DELTA_T;
            if (t > jd + 1.0 / 1440) return millis(t);   // a minute on, so a phase's own time gives the next one
        }
        return -1;
    }

    private static double phaseJde(double k, boolean full) {
        double t = k / 1236.85, t2 = t * t, t3 = t2 * t, t4 = t3 * t;
        double jde = 2451550.09766 + SYNODIC_DAYS * k + 0.00015437 * t2 - 0.000000150 * t3 + 0.00000000073 * t4;
        double e = 1 - 0.002516 * t - 0.0000074 * t2;
        double m = (2.5534 + 29.10535670 * k - 0.0000014 * t2 - 0.00000011 * t3) * DEG;
        double mp = (201.5643 + 385.81693528 * k + 0.0107582 * t2 + 0.00001238 * t3 - 0.000000058 * t4) * DEG;
        double f = (160.7108 + 390.67050284 * k - 0.0016118 * t2 - 0.00000227 * t3 + 0.000000011 * t4) * DEG;
        double om = (124.7746 - 1.56375588 * k + 0.0020672 * t2 + 0.00000215 * t3) * DEG;
        double c = full
                ? -0.40614 * Math.sin(mp) + 0.17302 * e * Math.sin(m) + 0.01614 * Math.sin(2 * mp)
                + 0.01043 * Math.sin(2 * f) + 0.00734 * e * Math.sin(mp - m) - 0.00515 * e * Math.sin(mp + m)
                + 0.00209 * e * e * Math.sin(2 * m)
                : -0.40720 * Math.sin(mp) + 0.17241 * e * Math.sin(m) + 0.01608 * Math.sin(2 * mp)
                + 0.01039 * Math.sin(2 * f) + 0.00739 * e * Math.sin(mp - m) - 0.00514 * e * Math.sin(mp + m)
                + 0.00208 * e * e * Math.sin(2 * m);
        c += -0.00111 * Math.sin(mp - 2 * f) - 0.00057 * Math.sin(mp + 2 * f) + 0.00056 * e * Math.sin(2 * mp + m)
                - 0.00042 * Math.sin(3 * mp) + 0.00042 * e * Math.sin(m + 2 * f) + 0.00038 * e * Math.sin(m - 2 * f)
                - 0.00024 * e * Math.sin(2 * mp - m) - 0.00017 * Math.sin(om);
        return jde + c;
    }

    // ---- the Moon's position, rise and set ------------------------------------------------------------------

    /** Geocentric {right ascension (rad), declination (rad), distance (km)} at {@code millis}. */
    static double[] position(long millis) {
        double t = (julian(millis) + DELTA_T - 2451545.0) / 36525;
        double lp = 218.3164477 + 481267.88123421 * t;
        double d = (297.8501921 + 445267.1114034 * t) * DEG;
        double m = (357.5291092 + 35999.0502909 * t) * DEG;
        double mp = (134.9633964 + 477198.8675055 * t) * DEG;
        double f = (93.2720950 + 483202.0175233 * t) * DEG;
        double lon = lp + 6.288774 * Math.sin(mp) + 1.274027 * Math.sin(2 * d - mp) + 0.658314 * Math.sin(2 * d)
                + 0.213618 * Math.sin(2 * mp) - 0.185116 * Math.sin(m) - 0.114332 * Math.sin(2 * f)
                + 0.058793 * Math.sin(2 * d - 2 * mp) + 0.057066 * Math.sin(2 * d - m - mp)
                + 0.053322 * Math.sin(2 * d + mp) + 0.045758 * Math.sin(2 * d - m) - 0.040923 * Math.sin(m - mp)
                - 0.034720 * Math.sin(d) - 0.030383 * Math.sin(m + mp);
        double lat = 5.128122 * Math.sin(f) + 0.280602 * Math.sin(mp + f) + 0.277693 * Math.sin(mp - f)
                + 0.173237 * Math.sin(2 * d - f) + 0.055413 * Math.sin(2 * d - f + mp)
                + 0.046271 * Math.sin(2 * d - f - mp) + 0.032573 * Math.sin(2 * d + f);
        double km = 385000.56 - 20905.355 * Math.cos(mp) - 3699.111 * Math.cos(2 * d - mp)
                - 2955.968 * Math.cos(2 * d) - 569.925 * Math.cos(2 * mp) + 48.888 * Math.cos(m)
                - 3.149 * Math.cos(2 * f) + 246.158 * Math.cos(2 * d - 2 * mp) - 152.138 * Math.cos(2 * d - m - mp)
                - 170.733 * Math.cos(2 * d + mp) - 204.586 * Math.cos(2 * d - m) - 129.620 * Math.cos(m - mp)
                + 108.743 * Math.cos(d) + 104.755 * Math.cos(m + mp);
        double eps = (23.439291 - 0.0130042 * t) * DEG;
        double l = lon * DEG, b = lat * DEG;
        double ra = Math.atan2(Math.sin(l) * Math.cos(eps) - Math.tan(b) * Math.sin(eps), Math.cos(l));
        double dec = Math.asin(Math.sin(b) * Math.cos(eps) + Math.cos(b) * Math.sin(eps) * Math.sin(l));
        return new double[]{ra < 0 ? ra + 2 * Math.PI : ra, dec, km};
    }

    /**
     * How far the Moon's upper edge is above the horizon at (latitude, longitude), in degrees, allowing for
     * refraction and parallax (Meeus: h0 = 0.7275 parallax - 34'): positive while the Moon is up.
     */
    static double aboveHorizon(long millis, double latitude, double longitude) {
        double[] p = position(millis);
        double lst = Orbit.gmst(julian(millis)) + longitude * DEG;
        double phi = latitude * DEG;
        double h = Math.asin(Math.sin(phi) * Math.sin(p[1]) + Math.cos(phi) * Math.cos(p[1]) * Math.cos(lst - p[0]));
        double h0 = 0.7275 * Math.asin(6378.14 / p[2]) / DEG - 34.0 / 60;
        return h / DEG - h0;
    }

    /**
     * The next moonrise and moonset after {@code millis} within 30 hours, as {rise, set} in epoch millis, -1 for
     * one that does not happen in that time. Steps of 10 minutes, then bisection to under a minute.
     */
    static long[] nextRiseSet(long millis, double latitude, double longitude) {
        long rise = -1, set = -1;
        long step = 10 * 60_000L, end = millis + 30 * 3_600_000L;
        double prev = aboveHorizon(millis, latitude, longitude);
        for (long t = millis + step; t <= end && (rise < 0 || set < 0); t += step) {
            double now = aboveHorizon(t, latitude, longitude);
            if ((prev < 0) != (now < 0)) {
                long a = t - step, b = t;
                boolean rising = now >= 0;
                while (b - a > 30_000L) {
                    long mid = (a + b) / 2;
                    if ((aboveHorizon(mid, latitude, longitude) >= 0) == rising) b = mid;
                    else a = mid;
                }
                if (rising && rise < 0) rise = b;
                if (!rising && set < 0) set = b;
            }
            prev = now;
        }
        return new long[]{rise, set};
    }

    /** Percent of the Moon's disk lit at {@code millis}, from the Sun-Moon elongation (to about a percent). */
    static double illumination(long millis) {
        double[] moon = position(millis);
        double[] sun = Orbit.sunDecRa(julian(millis));
        double sDec = sun[0] * DEG, sRa = sun[1] * DEG;
        double cosPsi = Math.sin(sDec) * Math.sin(moon[1]) + Math.cos(sDec) * Math.cos(moon[1]) * Math.cos(sRa - moon[0]);
        double psi = Math.acos(Math.max(-1, Math.min(1, cosPsi)));
        double sunKm = 149_597_870.7;
        double i = Math.atan2(sunKm * Math.sin(psi), moon[2] - sunKm * Math.cos(psi));
        return 50 * (1 + Math.cos(i));
    }

    /** Days since the last new Moon before {@code millis}. */
    static double ageDays(long millis) {
        long last = next(millis - Math.round(SYNODIC_DAYS * 1.2 * 86_400_000L), false);
        long after = next(last, false);
        for (int i = 0; i < 3 && after > last && after <= millis; i++) {
            last = after;
            after = next(last, false);
        }
        return (millis - last) / 86_400_000.0;
    }

    static double julian(long millis) {
        return millis / 86_400_000.0 + 2440587.5;
    }

    private static long millis(double jd) {
        return Math.round((jd - 2440587.5) * 86_400_000.0);
    }
}
