package com.gathin.tvhome;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java facts for the screensaver's news ticker (config.xml news_ticker), apart from Android so they can be
 * unit-tested: ANSA's RSS feeds and Il Post's (titles and publication times), merging each fetch into the cached
 * list (newest first, no duplicates, at most MAX), and the cache file's lines.
 */
final class NewsData {

    static final String FEED_URL = "https://www.ansa.it/sito/notizie/topnews/topnews_rss.xml";
    static final int MAX = 30;
    static final String TOP = "ultimaora";

    /** An ANSA section: its key in the settings, the name shown on the ticker, and its RSS feed. */
    static final class Section {
        final String key, label, url;

        Section(String key, String label, String url) {
            this.key = key;
            this.label = label;
            this.url = url;
        }
    }

    /**
     * The ANSA sections, checked with curl on 2026-11-04 (all current). Left out: Spettacolo (no feed, 404) and
     * Ambiente (its feed stopped in May 2023).
     */
    static final List<Section> SECTIONS = Collections.unmodifiableList(java.util.Arrays.asList(
            new Section(TOP, "Ultima ora", FEED_URL),
            new Section("cronaca", "Cronaca", "https://www.ansa.it/sito/notizie/cronaca/cronaca_rss.xml"),
            new Section("politica", "Politica", "https://www.ansa.it/sito/notizie/politica/politica_rss.xml"),
            new Section("mondo", "Mondo", "https://www.ansa.it/sito/notizie/mondo/mondo_rss.xml"),
            new Section("economia", "Economia", "https://www.ansa.it/sito/notizie/economia/economia_rss.xml"),
            new Section("sport", "Sport", "https://www.ansa.it/sito/notizie/sport/sport_rss.xml"),
            new Section("calcio", "Calcio", "https://www.ansa.it/sito/notizie/sport/calcio/calcio_rss.xml"),
            new Section("cultura", "Cultura", "https://www.ansa.it/sito/notizie/cultura/cultura_rss.xml"),
            new Section("tecnologia", "Tecnologia", "https://www.ansa.it/canale_tecnologia/notizie/tecnologia_rss.xml"),
            new Section("scienza", "Scienza", "https://www.ansa.it/canale_scienza_tecnica/notizie/scienzaetecnica_rss.xml"),
            // 1.1.0: no "Lazio" any more (SyntaxSettings.sections drops it from the saved choice).
            // Il Post's main feed (RSS 2.0, checked with curl on 2026-11-11; the form with a final "/" gives 403).
            // Not in TV Liberata, which has ANSA only.
            new Section(NewsData.ILPOST, "Il Post", "https://www.ilpost.it/feed")).subList(0, Edition.ilPost() ? 11 : 10));

    /** Il Post: a source of its own, not an ANSA section; off until ticked. */
    static final String ILPOST = "ilpost";

    /** Whether a section key is ANSA's (all but Il Post). */
    static boolean ansa(String key) {
        return !ILPOST.equals(key);
    }

    static final int LABEL_ANSA = 0, LABEL_ILPOST = 1, LABEL_MIXED = 2;

    /** Which label the band shows: ANSA's with ANSA only, Il Post's with Il Post only, the neutral one with both. */
    static int labelKind(java.util.Collection<String> sections) {
        boolean il = sections.contains(ILPOST), an = false;
        for (String s : sections) if (ansa(s)) an = true;
        return il && an ? LABEL_MIXED : il ? LABEL_ILPOST : LABEL_ANSA;
    }

    static List<String> sectionKeys() {
        List<String> keys = new ArrayList<>();
        for (Section s : SECTIONS) keys.add(s.key);
        return keys;
    }

    /** The section with this key, or null. */
    static Section section(String key) {
        for (Section s : SECTIONS) if (s.key.equals(key)) return s;
        return null;
    }

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);

    private NewsData() {
    }

    /** One headline. */
    static final class Headline {
        final String title;
        final long time;       // epoch millis
        final String section;  // a section key

        Headline(String title, long time) {
            this(title, time, TOP);
        }

        Headline(String title, long time, String section) {
            this.title = title;
            this.time = time;
            this.section = section == null ? TOP : section;
        }
    }

    /** The headlines in a feed, as listed; empty when the text is not one. Never throws. */
    static List<Headline> parse(String xml) {
        return parse(xml, TOP);
    }

    /** The headlines in a section's feed, as listed, marked with that section. */
    static List<Headline> parse(String xml, String section) {
        List<Headline> out = new ArrayList<>();
        if (xml == null) return out;
        Matcher item = ITEM.matcher(xml);
        while (item.find()) {
            String body = item.group(1);
            Matcher t = TITLE.matcher(body), d = DATE.matcher(body);
            if (!t.find() || !d.find()) continue;
            String title = SpaceData.clean(t.group(1));
            long time = parseDate(d.group(1).trim());
            if (title.isEmpty() || time <= 0) continue;
            out.add(new Headline(title.length() > 200 ? title.substring(0, 199) + "…" : title, time, section));
        }
        return out;
    }

    /** An RSS (RFC 822) date such as "Wed, 4 Nov 2026 18:21:46 +0100", in epoch millis, or -1. */
    static long parseDate(String text) {
        String[] patterns = {"EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss Z"};
        for (String p : patterns) {
            SimpleDateFormat f = new SimpleDateFormat(p, Locale.ENGLISH);
            f.setLenient(false);
            try {
                return f.parse(text).getTime();
            } catch (ParseException ignored) {
                // try the next form
            }
        }
        return -1;
    }

    /**
     * The fresh headlines merged into the cached ones: newest first, a title only once (the newest copy), at most
     * MAX. Neither list is changed.
     */
    static List<Headline> merge(List<Headline> cached, List<Headline> fresh) {
        return merge(cached, fresh, null);
    }

    /**
     * As {@link #merge(List, List)}, keeping only headlines from {@code sections} (null keeps all), so unticking a
     * section clears its headlines from the list.
     */
    static List<Headline> merge(List<Headline> cached, List<Headline> fresh, java.util.Collection<String> sections) {
        List<Headline> all = new ArrayList<>(fresh);
        all.addAll(cached);
        Collections.sort(all, (a, b) -> Long.compare(b.time, a.time));
        List<Headline> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Headline h : all) {
            if (out.size() >= MAX) break;
            if (sections != null && !sections.contains(h.section)) continue;
            if (seen.add(key(h.title))) out.add(h);
        }
        return out;
    }

    private static String key(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The cache file's text: one "millis TAB section TAB title" line per headline. */
    static String serialize(List<Headline> headlines) {
        StringBuilder b = new StringBuilder();
        for (Headline h : headlines) {
            b.append(h.time).append('\t').append(h.section).append('\t')
                    .append(h.title.replace('\n', ' ').replace('\t', ' ')).append('\n');
        }
        return b.toString();
    }

    /** What the ticker shows before the title: "18:05", or "SPORT · 18:05" when several sections are ticked. */
    static String prefix(Headline h, String time, boolean showSection) {
        if (!showSection) return time;
        Section s = section(h.section);
        return (s == null ? h.section : s.label).toUpperCase(Locale.ITALIAN) + " · " + time;
    }

    /** The cache file's headlines; broken lines are skipped. */
    static List<Headline> deserialize(String text) {
        List<Headline> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] parts = line.split("\t", 3);
            try {
                if (parts.length == 3 && !parts[2].isEmpty()) {
                    out.add(new Headline(parts[2], Long.parseLong(parts[0]), parts[1]));
                } else if (parts.length == 2 && !parts[1].isEmpty()) {
                    out.add(new Headline(parts[1], Long.parseLong(parts[0])));   // the cache from before sections
                }
            } catch (NumberFormatException ignored) {
                // skip it
            }
        }
        return merge(out, new ArrayList<>());
    }
}
