package com.gathin.tvhome;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java facts for the Sun screensaver scene (dream_scene "sun"), kept apart from Android so they can be
 * unit-tested: the four SDO channels and where their disk sits in the 1024 px "latest" images, the decode
 * sample size, and the last solar flare from NOAA SWPC's xray-flares-latest.json.
 */
final class SolarData {

    /** SDO file codes, in the order the scene shows them; the names are in the sun_channel_* string arrays. */
    static final String[] CODES = {"0193", "0304", "0171", "HMIIC"};

    /**
     * Solar disk radius as a fraction of half the image width, measured on the 1024 px images (AIA about 402 px,
     * HMI 479 px). The scene sizes each channel so the disk looks the same size.
     */
    static final float[] DISK = {0.785f, 0.785f, 0.785f, 0.936f};

    /**
     * Where the image starts fading to black (MASK_IN) and is fully black (MASK_OUT), as fractions of half the
     * width. The burnt-in caption sits in the bottom-left corner, at least 475 px from the centre on AIA images
     * and 521 px on HMI ones, so MASK_OUT stays inside that and the caption never shows.
     */
    static final float[] MASK_IN = {0.86f, 0.86f, 0.86f, 0.945f};
    static final float[] MASK_OUT = {0.90f, 0.90f, 0.90f, 0.97f};

    /** Nearest caption pixel to the centre on a 1024 px image, per channel, measured on 2026-11-04 images. */
    static final float[] CAPTION_PX = {475f, 475f, 475f, 521f};

    private static final Pattern MAX_CLASS = Pattern.compile("\"max_class\"\\s*:\\s*\"([ABCMX][0-9]+(?:\\.[0-9]+)?)\"");
    private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern MAX_TIME = Pattern.compile("\"max_time\"\\s*:\\s*\"([0-9T:Z-]+)\"");

    private SolarData() {
    }

    /** The largest power of two that still leaves the image at least {@code target} px wide (at least 1). */
    static int sampleSize(int width, int target) {
        int sample = 1;
        while (target > 0 && width / (sample * 2) >= target) sample *= 2;
        return sample;
    }

    /** The last flare: its class ("C1.2") and the time of its peak, in epoch millis. */
    static final class Flare {
        final String flareClass;
        final long peakMillis;

        Flare(String flareClass, long peakMillis) {
            this.flareClass = flareClass;
            this.peakMillis = peakMillis;
        }
    }

    /**
     * The last flare with a class and a peak time in xray-flares-latest.json or xray-flares-7-day.json (oldest
     * first), or null. A flare still in progress has "max_class": null and is skipped.
     */
    static Flare parseFlare(String json) {
        if (json == null) return null;
        Flare last = null;
        Matcher object = OBJECT.matcher(json);
        while (object.find()) {
            String item = object.group();
            Matcher c = MAX_CLASS.matcher(item);
            Matcher t = MAX_TIME.matcher(item);
            if (!c.find() || !t.find()) continue;
            long time = parseUtc(t.group(1));
            if (time > 0) last = new Flare(c.group(1), time);
        }
        return last;
    }

    /** "2026-11-04T06:54:00Z" (seconds optional) in epoch millis, or -1. */
    static long parseUtc(String text) {
        String pattern = text.length() == 17 ? "yyyy-MM-dd'T'HH:mm'Z'" : "yyyy-MM-dd'T'HH:mm:ss'Z'";
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        try {
            return format.parse(text).getTime();
        } catch (ParseException e) {
            return -1;
        }
    }
}
