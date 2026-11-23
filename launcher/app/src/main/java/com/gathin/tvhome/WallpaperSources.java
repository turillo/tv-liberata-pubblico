package com.gathin.tvhome;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The wallpaper archive's sources besides Bing, in plain Java so each reply's parsing is unit-tested: Wikimedia
 * Commons' picture of the day, Unsplash (themed photos, API key needed). Every photo is
 * filed in the one archive under its source's prefix (OHR. for Bing, as Bing names them), so the grid, favourites,
 * deleting and the blacklist work the same for all. Also the themes, the daily quota and the archive's cap.
 * Google Earth View is left out: it has no official list of its photos.
 */
final class WallpaperSources {

    static final String BING = "bing", WIKIMEDIA = "wikimedia", UNSPLASH = "unsplash";
    /** NASA APOD was a source until 13 November 2026: its photos stay in the archive, under their own name. */
    static final String OLD_APOD = "apod";
    /** Pexels was a source until 11 November 2026: its photos stay in the archive, under their own name. */
    static final String OLD_PEXELS = "pexels";
    /** In Syntax TV's order. */
    /** The sources this edition offers: TV Liberata has no Unsplash (it would need a key). */
    static final List<String> ALL = Collections.unmodifiableList(Edition.unsplash() ? Arrays.asList(BING, WIKIMEDIA, UNSPLASH)
            : Arrays.asList(BING, WIKIMEDIA));
    static final List<String> THEMED = Collections.unmodifiableList(Collections.singletonList(UNSPLASH));

    static final String SEA = "sea", MOUNTAIN = "mountain", CITY = "city", NATURE = "nature", SPACE = "space",
            ITALY = "italy", SEASON = "season";
    static final List<String> THEMES = Collections.unmodifiableList(Arrays.asList(SEA, MOUNTAIN, CITY, NATURE, SPACE,
            ITALY, SEASON));

    /** Photos narrower than this, or less wide than 4:3, are not wallpapers for a 16:9 screen. */
    static final int MIN_WIDTH = 1280;
    static final double MIN_RATIO = 1.3;
    static final int DEFAULT_CAP = 500;
    static final int[] CAPS = {200, 500, 1000};

    private WallpaperSources() {
    }

    /** One photo a source offers. */
    static final class Photo {
        final String id;        // archive file name without .jpg, with the source's prefix
        final String title;
        final String credit;    // the attribution to show
        final String url;       // the picture to download, sized for 1920x1080 where the source allows it
        final int width, height;
        final String ping;      // Unsplash: the download endpoint to call when the photo is used; else null

        Photo(String id, String title, String credit, String url, int width, int height, String ping) {
            this.id = id;
            this.title = title;
            this.credit = credit;
            this.url = url;
            this.width = width;
            this.height = height;
            this.ping = ping;
        }
    }

    /** The source of an archived photo, from its id. */
    static String sourceOf(String id) {
        if (id.startsWith("WMC.")) return WIKIMEDIA;
        if (id.startsWith("APOD.")) return OLD_APOD;
        if (id.startsWith("UNS.")) return UNSPLASH;
        if (id.startsWith("PEX.")) return OLD_PEXELS;
        return BING;
    }

    /** A 16:9 screen's wallpaper: wide enough and landscape (or sizes unknown, checked again after the download). */
    static boolean landscape(int width, int height) {
        if (width <= 0 || height <= 0) return true;
        return width >= MIN_WIDTH && width >= height * MIN_RATIO;
    }

    /** Only letters, digits, dot, dash and underscore: safe as a file name. */
    static String safe(String s) {
        String t = s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+", "_");
        if (t.length() > 80) t = t.substring(0, 80);
        return t.replaceAll("^[._]+|[._]+$", "");
    }

    // ---- Wikimedia Commons, picture of the day ----------------------------------------------------------

    /** api.wikimedia.org/feed/v1/wikipedia/it/featured/YYYY/MM/DD: today's picture of the day. */
    static String wikimediaUrl(int year, int month, int day) {
        return String.format(Locale.ROOT, "https://api.wikimedia.org/feed/v1/wikipedia/it/featured/%04d/%02d/%02d",
                year, month, day);
    }

    /** The feed's "image", with a 1920 px wide version; null when missing, not a photo or not landscape. */
    static Photo parseWikimedia(String json) {
        Map<?, ?> image = MiniJson.obj(MiniJson.parse(json), "image");
        if (image == null) return null;
        String title = MiniJson.str(image, "title");
        Map<?, ?> original = MiniJson.obj(image, "image");
        Map<?, ?> thumb = MiniJson.obj(image, "thumbnail");
        if (title == null || original == null || thumb == null) return null;
        int w = (int) MiniJson.number(original, "width", 0), h = (int) MiniJson.number(original, "height", 0);
        String thumbUrl = MiniJson.str(thumb, "source");
        if (thumbUrl == null || !landscape(w, h)) return null;
        String name = title.startsWith("File:") ? title.substring(5) : title;
        if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|tiff?|webp)$")) return null;   // no videos or SVGs
        // The 960 px thumbnail's URL names its width: Commons serves the 1920 px one at the same path.
        String url = thumbUrl.replaceFirst("/\\d+px-", "/1920px-").replaceFirst("\\?.*$", "");
        if (!url.startsWith("https://")) return null;
        String shown = name.replaceFirst("\\.[A-Za-z]+$", "").replace('_', ' ');
        Map<?, ?> desc = MiniJson.obj(image, "description");
        String description = desc == null ? null : MiniJson.str(desc, "text");
        Map<?, ?> artist = MiniJson.obj(image, "artist");
        Map<?, ?> license = MiniJson.obj(image, "license");
        String who = artist == null ? null : clean(MiniJson.str(artist, "text"));
        String lic = license == null ? null : MiniJson.str(license, "type");
        String credit = (who == null || who.isEmpty() ? "Wikimedia Commons" : who + " · Wikimedia Commons")
                + (lic == null ? "" : " · " + lic);
        String t = description != null && !clean(description).isEmpty() ? cut(clean(description), 140) : shown;
        return new Photo("WMC." + safe(shown), t, credit, url, w, h, null);
    }

    /**
     * A Commons search among its Quality images, for the Christmas wallpapers: each result's 1920 px version with
     * its author and licence.
     */
    static String wikimediaSearchUrl(String query, int offset) {
        return "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6"
                + "&gsrlimit=20&gsroffset=" + Math.max(0, offset) + "&gsrsearch="
                + encode(query + " incategory:\"Quality images\" filetype:bitmap")
                + "&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=1920"
                + "&iiextmetadatafilter=LicenseShortName%7CArtist%7CImageDescription";
    }

    /** The landscape photos in a Commons search reply. */
    static List<Photo> parseWikimediaSearch(String json) {
        List<Photo> out = new ArrayList<>();
        Map<?, ?> pages = MiniJson.obj(MiniJson.obj(MiniJson.parse(json), "query"), "pages");
        if (pages == null) return out;
        for (Object page : pages.values()) {
            String title = MiniJson.str(page, "title");
            List<?> info = MiniJson.list(page, "imageinfo");
            if (title == null || info == null || info.isEmpty()) continue;
            Object ii = info.get(0);
            int w = (int) MiniJson.number(ii, "width", 0), h = (int) MiniJson.number(ii, "height", 0);
            String url = MiniJson.str(ii, "thumburl");
            if (url == null || !url.startsWith("https://") || !landscape(w, h)) continue;
            Map<?, ?> meta = MiniJson.obj(ii, "extmetadata");
            String artist = clean(MiniJson.str(MiniJson.obj(meta, "Artist"), "value"));
            String lic = MiniJson.str(MiniJson.obj(meta, "LicenseShortName"), "value");
            String desc = clean(MiniJson.str(MiniJson.obj(meta, "ImageDescription"), "value"));
            String name = (title.startsWith("File:") ? title.substring(5) : title).replaceFirst("\\.[A-Za-z]+$", "");
            String credit = (artist.isEmpty() ? "Wikimedia Commons" : artist + " · Wikimedia Commons")
                    + (lic == null ? "" : " · " + lic);
            out.add(new Photo("WMC." + safe(name), desc.isEmpty() ? name : cut(desc, 140), credit, url, w, h, null));
        }
        return out;
    }

    // ---- Unsplash -----------------------------------------------------------------------------------------

    /** photos/random: {@code count} landscape photos for a theme. Needs the Authorization: Client-ID header. */
    static String unsplashUrl(String query, int count) {
        return "https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&count="
                + Math.max(1, Math.min(30, count)) + "&query=" + encode(query);
    }

    /**
     * The photos in a photos/random reply (an array, or one object): the hotlinked raw URL sized to 1920x1080, the
     * attribution "Foto di NAME su Unsplash", and the download endpoint Unsplash asks to be called.
     */
    static List<Photo> parseUnsplash(String json) {
        List<Photo> out = new ArrayList<>();
        Object root = MiniJson.parse(json);
        List<?> items = root instanceof List ? (List<?>) root : root instanceof Map ? Collections.singletonList(root) : null;
        if (items == null) return out;
        for (Object o : items) {
            String id = MiniJson.str(o, "id");
            Map<?, ?> urls = MiniJson.obj(o, "urls"), links = MiniJson.obj(o, "links"), user = MiniJson.obj(o, "user");
            if (id == null || urls == null) continue;
            String raw = MiniJson.str(urls, "raw");
            if (raw == null || !raw.startsWith("https://")) continue;
            int w = (int) MiniJson.number(o, "width", 0), h = (int) MiniJson.number(o, "height", 0);
            if (!landscape(w, h)) continue;
            String url = raw + (raw.contains("?") ? "&" : "?") + "w=1920&h=1080&fit=crop&crop=entropy&fm=jpg&q=82";
            String name = user == null ? null : MiniJson.str(user, "name");
            String title = MiniJson.str(o, "description");
            if (title == null || clean(title).isEmpty()) title = MiniJson.str(o, "alt_description");
            String ping = links == null ? null : MiniJson.str(links, "download_location");
            out.add(new Photo("UNS." + safe(id), title == null ? "Unsplash" : cut(clean(title), 140),
                    "Foto di " + (name == null ? "Unsplash" : clean(name)) + " su Unsplash", url, w, h,
                    ping != null && ping.startsWith("https://api.unsplash.com/") ? ping : null));
        }
        return out;
    }

    // ---- themes, quota, cap -------------------------------------------------------------------------------

    /** The search words for a theme; "season" follows the month (Northern Hemisphere). */
    static String query(String theme, int month) {
        switch (theme) {
            case SEA: return "sea coast";
            case MOUNTAIN: return "mountains";
            case CITY: return "city skyline";
            case SPACE: return "night sky stars";
            case ITALY: return "italy landscape";
            case SEASON: return seasonQuery(month);
            default: return "nature landscape";
        }
    }

    static String seasonQuery(int month) {
        if (month == 12 || month <= 2) return "winter landscape";
        if (month <= 5) return "spring landscape";
        if (month <= 8) return "summer landscape";
        return "autumn landscape";
    }

    /** How many more photos a source may add today: {@code perDay} minus those already added on {@code today}. */
    static int remainingToday(int perDay, String lastDay, int countThatDay, String today) {
        int done = today.equals(lastDay) ? countThatDay : 0;
        return Math.max(0, perDay - done);
    }

    /** What one source did in a download round, so Syntax TV can say it instead of staying silent. */
    static final class Result {
        static final int ADDED = 0, QUOTA = 1, NOTHING_NEW = 2, DISCARDED = 3, FAILED = 4;
        final String source;
        final int added, discarded, perDay;
        final boolean failed, quotaReached;

        Result(String source, int added, int discarded, int perDay, boolean failed, boolean quotaReached) {
            this.source = source;
            this.added = added;
            this.discarded = discarded;
            this.perDay = perDay;
            this.failed = failed;
            this.quotaReached = quotaReached;
        }

        /** The one thing worth saying about this source. */
        int outcome() {
            if (added > 0) return ADDED;
            if (quotaReached) return QUOTA;
            if (failed) return FAILED;
            if (discarded > 0) return DISCARDED;
            return NOTHING_NEW;
        }
    }

    /** An archived photo, for the cap. */
    static final class Archived {
        final String id;
        final long time;
        final boolean favorite;

        Archived(String id, long time, boolean favorite) {
            this.id = id;
            this.time = time;
            this.favorite = favorite;
        }
    }

    /**
     * The photos to delete so the archive keeps at most {@code cap}: the oldest ones that are not favourites. When
     * favourites alone exceed the cap, they all stay.
     */
    static List<String> overCap(Collection<Archived> photos, int cap) {
        List<String> out = new ArrayList<>();
        int extra = photos.size() - Math.max(1, cap);
        if (extra <= 0) return out;
        List<Archived> old = new ArrayList<>();
        for (Archived a : photos) if (!a.favorite) old.add(a);
        old.sort(Comparator.comparingLong(a -> a.time));
        for (int i = 0; i < old.size() && out.size() < extra; i++) out.add(old.get(i).id);
        return out;
    }

    /** The photos of one source (null or "all" keeps them all), for the grid's filter. */
    static List<String> filter(List<String> ids, String source) {
        if (source == null || "all".equals(source)) return ids;
        List<String> out = new ArrayList<>();
        for (String id : ids) if (sourceOf(id).equals(source)) out.add(id);
        return out;
    }

    /** Whether a source can run: a themed one needs its API key. */
    static boolean usable(String source, String unsplashKey) {
        if (UNSPLASH.equals(source)) return unsplashKey != null && !unsplashKey.isEmpty();
        return ALL.contains(source);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }
}
