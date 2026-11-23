package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Plain-Java facts for the Christmas mode (Syntax TV, Natale), apart from Android so they are unit-tested: the
 * scenes, the header's greeting for a date, the Advent calendar's state, and Santa's simulated night: he crosses each
 * longitude at its local midnight between 24 December 12:00 UTC (the date line) and 25 December 12:00 UTC, along a
 * route of real places, west and fuso by fuso. NORAD's tracker has no public API, so nothing here is downloaded.
 */
final class Christmas {

    static final String SANTA = "santa", ADVENT = "advent";
    /** The favourite Christmas wallpapers (FavoritesScene, which uses the Christmas archive while the mode is on). */
    static final String FAVORITES = "xmas_favorites";
    /**
     * The Christmas scenes, in Syntax TV's order. The fireplace, the snowy landscape and the tree were taken out on
     * 11 November 2026; their names, saved in old settings, are simply ignored.
     */
    static final List<String> SCENES = Collections.unmodifiableList(Arrays.asList(SANTA, ADVENT, FAVORITES));

    /** Unsplash searches for the Christmas wallpapers. */
    static final List<String> UNSPLASH_QUERIES = Collections.unmodifiableList(Arrays.asList(
            "christmas lights", "christmas market", "christmas tree", "snow village"));
    /** Wikimedia Commons searches, among its Quality images. */
    static final List<String> WIKIMEDIA_QUERIES = Collections.unmodifiableList(Arrays.asList(
            "christmas lights", "christmas market", "christmas tree", "snowy village winter"));

    /** The Christmas wallpapers' themes (Syntax TV, Natale, Sorgenti), in the order of the queries above. */
    static final String THEME_LIGHTS = "lights", THEME_MARKET = "market", THEME_TREE = "tree", THEME_SNOW = "snow";
    static final List<String> THEMES = Collections.unmodifiableList(Arrays.asList(THEME_LIGHTS, THEME_MARKET,
            THEME_TREE, THEME_SNOW));

    /** The search for a theme on a source (Wikimedia Commons, or Unsplash). */
    static String query(String theme, boolean wikimedia) {
        int i = Math.max(0, THEMES.indexOf(theme));
        return (wikimedia ? WIKIMEDIA_QUERIES : UNSPLASH_QUERIES).get(i);
    }

    private Christmas() {
    }

    // ---- the header's line -----------------------------------------------------------------------------------

    static final int COUNTDOWN = 0, EVE = 1, DAY = 2, HOLIDAYS = 3, NEW_YEAR = 4;

    /** What the header says on {@code d}: {kind, days to Christmas (for COUNTDOWN)}. */
    static int[] greeting(LocalDate d) {
        int m = d.getMonthValue(), day = d.getDayOfMonth();
        if (m == 12 && day == 24) return new int[]{EVE, 0};
        if (m == 12 && day == 25) return new int[]{DAY, 0};
        if (m == 12 && day >= 26) return new int[]{HOLIDAYS, 0};
        if (m == 1 && day <= 6) return new int[]{NEW_YEAR, 0};
        LocalDate xmas = LocalDate.of(d.getYear(), 12, 25);
        return new int[]{COUNTDOWN, (int) ChronoUnit.DAYS.between(d, xmas)};
    }

    // ---- the Advent calendar ---------------------------------------------------------------------------------

    /**
     * The calendar on {@code d}: {opened, today, daysToStart}. During 1-24 December the days before today are open
     * and today's opens now (today = its number); from Christmas to Epiphany all 24 are open (today = 0); the rest of
     * the year all are closed and daysToStart counts to 1 December.
     */
    static int[] advent(LocalDate d) {
        int m = d.getMonthValue(), day = d.getDayOfMonth();
        if (m == 12 && day <= 24) return new int[]{day - 1, day, 0};
        if ((m == 12) || (m == 1 && day <= 6)) return new int[]{24, 0, 0};
        LocalDate start = LocalDate.of(d.getYear(), 12, 1);
        return new int[]{0, 0, (int) ChronoUnit.DAYS.between(d, start)};
    }

    // ---- Santa ----------------------------------------------------------------------------------------------

    static final int BEFORE = 0, FLYING = 1, DONE = 2;
    static final long HOUR = 3_600_000L, DAY_MS = 24 * HOUR;
    /** Children's presents worldwide for the counter: a round, plausible figure. */
    static final double PRESENTS = 2_400_000_000.0;

    /**
     * The route, east to west: {longitude, latitude, weight}. The weight is roughly how many homes are near there,
     * so the counter grows fast over Asia and slowly over the oceans.
     */
    private static final double[][] ROUTE = {
            {180, -17, 0.2}, {174, -37, 0.4}, {151, -34, 1.2}, {145, -38, 0.8}, {139, 36, 6}, {127, 37, 3},
            {121, 31, 9}, {116, 40, 8}, {106, -6, 7}, {100, 14, 5}, {90, 24, 9}, {77, 29, 14}, {67, 25, 6},
            {55, 25, 2}, {44, 34, 3}, {37, 56, 3}, {31, 30, 5}, {28, -26, 3}, {18, -34, 2}, {12, 42, 4},
            {2, 49, 4}, {-4, 40, 3}, {-9, 39, 1}, {-17, 15, 2}, {-23, 64, 0.3}, {-38, -12, 2}, {-43, -23, 4},
            {-47, -24, 3}, {-58, -34, 2}, {-66, 18, 1}, {-74, 41, 4}, {-79, 44, 2}, {-87, 42, 3}, {-99, 19, 4},
            {-104, 40, 2}, {-118, 34, 3}, {-123, 49, 1}, {-150, 61, 0.2}, {-157, 21, 0.2}, {-171, -14, 0.1},
            {-180, -14, 0.1}};
    private static final double[] CUMULATIVE = new double[ROUTE.length];

    static {
        double sum = 0;
        for (int i = 0; i < ROUTE.length; i++) {
            sum += ROUTE[i][2];
            CUMULATIVE[i] = sum;
        }
        for (int i = 0; i < ROUTE.length; i++) CUMULATIVE[i] /= sum;
    }

    /** When Santa sets off from the date line: 24 December 12:00 UTC of {@code year}. */
    static long departure(int year) {
        return LocalDate.of(year, 12, 24).toEpochDay() * DAY_MS + 12 * HOUR;
    }

    /** Where Santa is: {phase, latitude, longitude, presents delivered, millis to departure}. */
    static double[] santa(long millis) {
        LocalDate today = LocalDate.ofEpochDay(Math.floorDiv(millis, DAY_MS));
        // After the night, "done" until Epiphany; then the countdown to next year's.
        int year = today.getMonthValue() == 1 ? today.getYear() - 1 : today.getYear();
        long start = departure(year);
        long end = start + DAY_MS;
        if (millis >= end) {
            if (today.getMonthValue() == 12 || today.getDayOfMonth() <= 6) return new double[]{DONE, 85, 0, PRESENTS, 0};
            start = departure(today.getYear());
        }
        if (millis < start) return new double[]{BEFORE, 85, 0, 0, start - millis};
        double hours = (millis - start) / (double) HOUR;
        double lon = 180 - 15 * hours;
        double[] here = at(lon);
        return new double[]{FLYING, here[0], lon, Math.round(PRESENTS * here[1]), 0};
    }

    /** {latitude, fraction of presents delivered} at a longitude along the route. */
    static double[] at(double lon) {
        lon = Math.max(-180, Math.min(180, lon));
        for (int i = 1; i < ROUTE.length; i++) {
            if (lon >= ROUTE[i][0]) {
                double f = (ROUTE[i - 1][0] - lon) / (ROUTE[i - 1][0] - ROUTE[i][0]);
                double lat = ROUTE[i - 1][1] + f * (ROUTE[i][1] - ROUTE[i - 1][1]);
                double done = CUMULATIVE[i - 1] + f * (CUMULATIVE[i] - CUMULATIVE[i - 1]);
                return new double[]{lat, done};
            }
        }
        return new double[]{ROUTE[ROUTE.length - 1][1], 1};
    }

    /** The whole route as lon, lat pairs every 10 minutes, for the map; {@code nowIndex} is where Santa is. */
    static float[] track() {
        int n = 24 * 6 + 1;
        float[] p = new float[2 * n];
        for (int i = 0; i < n; i++) {
            double lon = 180 - 15 * (i / 6.0);
            p[2 * i] = (float) Math.max(-179.9, Math.min(179.9, lon));
            p[2 * i + 1] = (float) at(lon)[0];
        }
        return p;
    }

    /** The track index for a time: 0 before departure, the last one after. */
    static int trackIndex(long millis, long start) {
        if (millis <= start) return 0;
        return (int) Math.min(24 * 6, (millis - start) / (10 * 60_000L));
    }
}
