package com.gathin.tvhome;

/**
 * Earth geometry for the ISS screensaver scene: sidereal time, TEME to latitude, longitude and altitude, the
 * sun's position, distances, and the look angles and lighting a visible-pass search needs (Phase 2).
 * Pure maths, no Android types, so OrbitTest runs it on the JVM.
 */
final class Orbit {

    private static final double DEG = Math.PI / 180;
    // WGS-84 for latitude and altitude.
    private static final double A_KM = 6378.137;
    private static final double F = 1 / 298.257223563;
    private static final double E2 = F * (2 - F);
    /** Mean Earth radius for great-circle distances. */
    static final double MEAN_RADIUS_KM = 6371.0;

    private Orbit() {
    }

    /** Latitude and longitude in degrees (longitude -180 to 180), altitude in km. */
    static final class Geo {
        final double lat, lon, altKm;

        Geo(double lat, double lon, double altKm) {
            this.lat = lat;
            this.lon = lon;
            this.altKm = altKm;
        }
    }

    /** Elevation and azimuth in degrees (azimuth from north, clockwise) and range in km. */
    static final class Look {
        final double elevation, azimuth, rangeKm;

        Look(double elevation, double azimuth, double rangeKm) {
            this.elevation = elevation;
            this.azimuth = azimuth;
            this.rangeKm = rangeKm;
        }
    }

    /** Greenwich mean sidereal time in radians (IAU-82), taking UTC for UT1. */
    static double gmst(double jd) {
        double t = (jd - 2451545.0) / 36525.0;
        double seconds = -6.2e-6 * t * t * t + 0.093104 * t * t
                + (876600.0 * 3600 + 8640184.812866) * t + 67310.54841;
        double g = (seconds * DEG / 240.0) % (2 * Math.PI);
        return g < 0 ? g + 2 * Math.PI : g;
    }

    /** TEME position to Earth-fixed (km), ignoring polar motion. */
    static double[] ecef(Sgp4.State s, long unixMillis) {
        double g = gmst(Sgp4.julianDate(unixMillis));
        double c = Math.cos(g), sn = Math.sin(g);
        return new double[]{c * s.x + sn * s.y, -sn * s.x + c * s.y, s.z};
    }

    static Geo geodetic(Sgp4.State s, long unixMillis) {
        return geodetic(ecef(s, unixMillis));
    }

    static Geo geodetic(double[] r) {
        double p = Math.sqrt(r[0] * r[0] + r[1] * r[1]);
        double lon = Math.atan2(r[1], r[0]);
        double lat = Math.atan2(r[2], p * (1 - E2));
        double h = 0;
        for (int i = 0; i < 6; i++) {
            double sinLat = Math.sin(lat);
            double n = A_KM / Math.sqrt(1 - E2 * sinLat * sinLat);
            h = p / Math.cos(lat) - n;
            lat = Math.atan2(r[2], p * (1 - E2 * n / (n + h)));
        }
        return new Geo(lat / DEG, lon / DEG, h);
    }

    /** Earth-fixed position (km) of a place on the WGS-84 ellipsoid. */
    static double[] ecef(double latDeg, double lonDeg, double altKm) {
        double lat = latDeg * DEG, lon = lonDeg * DEG;
        double sinLat = Math.sin(lat);
        double n = A_KM / Math.sqrt(1 - E2 * sinLat * sinLat);
        return new double[]{(n + altKm) * Math.cos(lat) * Math.cos(lon),
                (n + altKm) * Math.cos(lat) * Math.sin(lon),
                (n * (1 - E2) + altKm) * sinLat};
    }

    /**
     * The sun's apparent declination and right ascension in degrees, from the Astronomical Almanac's
     * low-precision formulae (about 0.01 degrees, plenty for a terminator).
     */
    static double[] sunDecRa(double jd) {
        double n = jd - 2451545.0;
        double l = (280.460 + 0.9856474 * n) % 360;
        double g = ((357.528 + 0.9856003 * n) % 360) * DEG;
        double lambda = (l + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g)) * DEG;
        double eps = (23.439 - 0.0000004 * n) * DEG;
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(eps) * Math.sin(lambda));
        return new double[]{dec / DEG, ra / DEG};
    }

    /** The point where the sun is overhead, {latitude, longitude} in degrees. */
    static double[] subsolar(long unixMillis) {
        double jd = Sgp4.julianDate(unixMillis);
        double[] decRa = sunDecRa(jd);
        return new double[]{decRa[0], normalizeLon(decRa[1] - gmst(jd) / DEG)};
    }

    /** The sun's elevation in degrees at a place, given the subsolar point. */
    static double sunElevation(double lat, double lon, double sunLat, double sunLon) {
        double s = Math.sin(lat * DEG) * Math.sin(sunLat * DEG)
                + Math.cos(lat * DEG) * Math.cos(sunLat * DEG) * Math.cos((lon - sunLon) * DEG);
        return Math.asin(Math.max(-1, Math.min(1, s))) / DEG;
    }

    /** Great-circle distance in km over a sphere of the mean Earth radius (haversine). */
    static double groundDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = (lat2 - lat1) * DEG, dLon = (lon2 - lon1) * DEG;
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1 * DEG) * Math.cos(lat2 * DEG) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * MEAN_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    static double normalizeLon(double lon) {
        double l = (lon + 180) % 360;
        if (l < 0) l += 360;
        return l - 180;
    }

    /** Where a satellite (Earth-fixed km) stands in an observer's sky. */
    static Look look(double obsLat, double obsLon, double obsAltKm, double[] sat) {
        double[] o = ecef(obsLat, obsLon, obsAltKm);
        double dx = sat[0] - o[0], dy = sat[1] - o[1], dz = sat[2] - o[2];
        double lat = obsLat * DEG, lon = obsLon * DEG;
        double sinLat = Math.sin(lat), cosLat = Math.cos(lat), sinLon = Math.sin(lon), cosLon = Math.cos(lon);
        double south = sinLat * cosLon * dx + sinLat * sinLon * dy - cosLat * dz;
        double east = -sinLon * dx + cosLon * dy;
        double up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz;
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double az = Math.atan2(east, -south) / DEG;
        return new Look(Math.asin(up / range) / DEG, az < 0 ? az + 360 : az, range);
    }

    /**
     * Whether a satellite (TEME km) is in sunlight, with the Earth's shadow taken as a cylinder: enough to tell
     * a pass the eye can see from one that crosses in the dark.
     */
    static boolean sunlit(Sgp4.State s, long unixMillis) {
        double[] decRa = sunDecRa(Sgp4.julianDate(unixMillis));
        double dec = decRa[0] * DEG, ra = decRa[1] * DEG;
        double ux = Math.cos(dec) * Math.cos(ra), uy = Math.cos(dec) * Math.sin(ra), uz = Math.sin(dec);
        double along = s.x * ux + s.y * uy + s.z * uz;
        if (along >= 0) return true;
        double px = s.x - along * ux, py = s.y - along * uy, pz = s.z - along * uz;
        return Math.sqrt(px * px + py * py + pz * pz) > A_KM;
    }
}
