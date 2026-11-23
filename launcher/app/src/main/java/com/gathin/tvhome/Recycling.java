package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The kitchen's door-to-door recycling calendar (Rome, AMA): which bins go out on which evening, the hours to put
 * them out and the hour to take them back in the next morning. Stored as one string in Syntax TV's settings,
 * "2:carta,organico;4:plastica,organico;7:indiff,organico" (ISO days, Monday 1). Plain Java, tested in
 * RecyclingTest; KitchenPanelActivity and Home only show what {@link #at} says.
 */
final class Recycling {
    static final String PAPER = "carta", ORGANIC = "organico", PLASTIC = "plastica", REST = "indiff", GLASS = "vetro";
    /** Every bin, in the order Syntax TV lists them. */
    static final List<String> BINS = Collections.unmodifiableList(Arrays.asList(PAPER, ORGANIC, PLASTIC, REST, GLASS));
    /** Tuesday paper and organic, Thursday plastic and metals and organic, Sunday the rest and organic. */
    static final String DEFAULT = "2:carta,organico;4:plastica,organico;7:indiff,organico";
    static final int FROM = 19 * 60, TO = 21 * 60, BY = 9 * 60;
    /** From this minute the evening's bins are on screen, before the hours to put them out. */
    static final int AFTERNOON = 14 * 60;
    static final int[] FROMS = {17 * 60, 18 * 60, 19 * 60, 20 * 60};
    static final int[] TOS = {20 * 60, 21 * 60, 22 * 60, 23 * 60};
    static final int[] BYS = {7 * 60, 8 * 60, 9 * 60, 10 * 60, 12 * 60};

    /** What the box says now. */
    enum Kind { NONE, TONIGHT, OUT_NOW, TAKE_IN, NEXT }

    static final class Status {
        final Kind kind;
        final List<String> bins;
        /** NEXT: the ISO day of the next evening with bins. */
        final int day;

        Status(Kind kind, List<String> bins, int day) {
            this.kind = kind;
            this.bins = bins;
            this.day = day;
        }
    }

    private Recycling() {
    }

    /** Seven lists, index 0 Monday; unknown bins and broken parts are dropped. */
    static List<List<String>> parse(String value) {
        List<List<String>> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) week.add(new ArrayList<>());
        if (value == null) return week;
        for (String part : value.split(";")) {
            int colon = part.indexOf(':');
            if (colon < 0) continue;
            int day;
            try {
                day = Integer.parseInt(part.substring(0, colon).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (day < 1 || day > 7) continue;
            List<String> bins = week.get(day - 1);
            for (String b : part.substring(colon + 1).split(",")) {
                String bin = b.trim();
                if (BINS.contains(bin) && !bins.contains(bin)) bins.add(bin);
            }
            bins.sort((a, b) -> BINS.indexOf(a) - BINS.indexOf(b));
        }
        return week;
    }

    static String format(List<List<String>> week) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < week.size() && i < 7; i++) {
            if (week.get(i).isEmpty()) continue;
            if (out.length() > 0) out.append(';');
            out.append(i + 1).append(':').append(String.join(",", week.get(i)));
        }
        return out.toString();
    }

    /** The week with {@code bin} switched on or off on ISO {@code day}. */
    static String toggle(String value, int day, String bin) {
        List<List<String>> week = parse(value);
        if (day < 1 || day > 7 || !BINS.contains(bin)) return format(week);
        List<String> bins = week.get(day - 1);
        if (!bins.remove(bin)) bins.add(bin);
        return format(parse(format(week)));   // sorted again
    }

    /**
     * The box at ISO {@code day}, {@code minute} after midnight: the morning after an evening with bins, until
     * {@code by}, TAKE_IN; on an evening with bins, from the afternoon, TONIGHT (OUT_NOW between {@code from} and
     * {@code to}); otherwise the next evening with bins, or NONE for an empty week.
     */
    static Status at(List<List<String>> week, int day, int minute, int from, int to, int by) {
        List<String> yesterday = week.get((day + 5) % 7);
        if (minute < by && !yesterday.isEmpty()) return new Status(Kind.TAKE_IN, yesterday, 0);
        List<String> today = week.get(day - 1);
        if (!today.isEmpty() && minute >= Math.min(AFTERNOON, from)) {
            boolean now = minute >= from && minute < to;
            return new Status(now ? Kind.OUT_NOW : Kind.TONIGHT, today, day);
        }
        for (int i = minute < Math.min(AFTERNOON, from) ? 0 : 1; i <= 7; i++) {
            int d = (day - 1 + i) % 7;
            if (!week.get(d).isEmpty()) return new Status(Kind.NEXT, week.get(d), d + 1);
        }
        return new Status(Kind.NONE, Collections.emptyList(), 0);
    }

    /** True when no evening has glass: the box then reminds that glass goes to the street bells. */
    static boolean glassToBells(List<List<String>> week) {
        for (List<String> d : week) if (d.contains(GLASS)) return false;
        return true;
    }
}
