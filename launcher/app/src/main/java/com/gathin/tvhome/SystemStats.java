package com.gathin.tvhome;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plain logic behind Syntax TV's "Sistema" page, apart from Android so the JVM tests can check it: reading
 * the text the kernel exposes (temperatures, core frequencies, /proc/stat, ping), judging a Wi-Fi signal, and the
 * Italian number formats. Nothing here throws on odd input: an unreadable value is NaN or -1, never a made-up one.
 */
final class SystemStats {

    private SystemStats() {
    }

    /**
     * A thermal zone's "temp" file in degrees Celsius. Kernels write millidegrees (45000), some plain degrees (45),
     * a few tenths (450); anything outside 0..150 °C after scaling is not a temperature and gives NaN.
     */
    static float parseTemp(String text) {
        long raw = parseLong(text);
        if (raw == Long.MIN_VALUE || raw <= 0) return Float.NaN;
        float c = raw >= 1000 ? raw / 1000f : raw > 150 ? raw / 10f : raw;
        return c > 0 && c <= 150 ? c : Float.NaN;
    }

    /** A cpufreq file (kHz) in MHz; -1 when unreadable. */
    static int parseFreqMhz(String text) {
        long khz = parseLong(text);
        if (khz == Long.MIN_VALUE || khz <= 0 || khz > 10_000_000L) return -1;
        return (int) (khz / 1000);
    }

    /** The first "cpu" line of /proc/stat as {idle, total} jiffies, or null. idle counts iowait too. */
    static long[] parseCpuLine(String line) {
        if (line == null) return null;
        String[] f = line.trim().split("\\s+");
        if (f.length < 5 || !f[0].equals("cpu")) return null;
        long total = 0, idle = 0;
        for (int i = 1; i < f.length && i <= 8; i++) {
            long v = parseLong(f[i]);
            if (v == Long.MIN_VALUE || v < 0) return null;
            total += v;
            if (i == 4 || i == 5) idle += v;
        }
        return total > 0 ? new long[]{idle, total} : null;
    }

    /** CPU load 0..100 between two /proc/stat samples, or -1 when they cannot be compared. */
    static int cpuLoad(long[] before, long[] after) {
        if (before == null || after == null) return -1;
        long total = after[1] - before[1], idle = after[0] - before[0];
        if (total <= 0 || idle < 0 || idle > total) return -1;
        return (int) Math.round(100.0 * (total - idle) / total);
    }

    private static final Pattern PING_TIME = Pattern.compile("time[=<]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*ms");

    /** The round trip of one ping's output, in ms, or -1. */
    static float parsePingMs(String output) {
        if (output == null) return -1;
        Matcher m = PING_TIME.matcher(output);
        if (!m.find()) return -1;
        try {
            return Float.parseFloat(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Ottimo / buono / discreto / debole for a Wi-Fi RSSI in dBm; null when Android gives no real value. */
    static String signalLabel(int dbm) {
        if (dbm >= 0 || dbm < -120) return null;
        if (dbm >= -55) return "ottimo";
        if (dbm >= -67) return "buono";
        if (dbm >= -75) return "discreto";
        return "debole";
    }

    /** 1234567 B as "1,2 MB": one decimal under 100, none above; 1024 steps like Android's own storage page. */
    static String bytes(long b) {
        if (b < 0) return "–";
        if (b < 1024) return b + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        String n = v < 100 ? String.format(Locale.ITALY, "%.1f", v) : String.format(Locale.ITALY, "%.0f", v);
        return n + " " + units[u];
    }

    /** Whole percent of {@code part} in {@code whole}, 0..100; -1 when whole is not positive. */
    static int percent(long part, long whole) {
        if (whole <= 0) return -1;
        long p = Math.round(100.0 * part / whole);
        return (int) Math.max(0, Math.min(100, p));
    }

    /** Uptime as "3 g 4 h", "4 h 12 min" or "12 min". */
    static String duration(long ms) {
        long min = Math.max(0, ms) / 60_000;
        long d = min / 1440, h = (min % 1440) / 60, m = min % 60;
        if (d > 0) return d + " g " + h + " h";
        if (h > 0) return h + " h " + m + " min";
        return m + " min";
    }

    /** Mbit/s for {@code bytes} in {@code ms}, or -1. */
    static double mbps(long bytes, long ms) {
        if (bytes <= 0 || ms <= 0) return -1;
        return bytes * 8.0 / ms / 1000.0;
    }

    /** The newest {@code size} samples, oldest first; add() drops the oldest once full. */
    static final class History {
        private final float[] values;
        private int count, next;

        History(int size) {
            values = new float[Math.max(1, size)];
        }

        synchronized void add(float v) {
            values[next] = v;
            next = (next + 1) % values.length;
            if (count < values.length) count++;
        }

        synchronized float[] snapshot() {
            float[] out = new float[count];
            int start = count < values.length ? 0 : next;
            for (int i = 0; i < count; i++) out[i] = values[(start + i) % values.length];
            return out;
        }

        int capacity() {
            return values.length;
        }
    }

    private static long parseLong(String text) {
        if (text == null) return Long.MIN_VALUE;
        String t = text.trim();
        if (t.isEmpty()) return Long.MIN_VALUE;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }
}
