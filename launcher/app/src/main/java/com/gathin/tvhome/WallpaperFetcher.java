package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Downloads the new photos of the wallpaper sources ticked in Syntax TV (besides Bing, which WallpaperPlayer fetches
 * itself) into the general archive, and with the Christmas mode on the Christmas sources' photos into the Christmas
 * archive, at most each source's photos a day; then trims each archive to its cap (the oldest non-favourites go). Runs on WallpaperPlayer's network thread with Bing's check (while Home is on screen, at most
 * every 6 hours). One source failing never stops the others; nothing here throws.
 */
final class WallpaperFetcher {

    private static final String TAG = "TvHome.WallpaperSrc";
    private static final String UA = "TvHome-launcher/1.0 (personal Android TV wallpaper)";
    private static final long MAX_IMAGE_BYTES = 8L << 20;
    private static final int MAX_JSON_BYTES = 1 << 20;
    private final Random random = new Random();

    private final Context context;
    private final File archive;
    private final SharedPreferences counts;

    WallpaperFetcher(Context context, File archive) {
        this.context = context;
        this.archive = archive;
        this.counts = context.getSharedPreferences("wallpaper_sources", Context.MODE_PRIVATE);
    }

    /** Photos that came down but were not kept (portrait, too small, over 8 MB, unreadable) in the last fetch. */
    private int discarded;

    /**
     * All ticked sources (Bing apart: WallpaperPlayer fetches it), then the cap. Returns what each source did.
     * Blocking: call it off the main thread.
     */
    List<WallpaperSources.Result> run() {
        List<WallpaperSources.Result> results = new ArrayList<>();
        try {
            if (archive == null || (!archive.isDirectory() && !archive.mkdirs())) return results;
            WallpaperLibrary.migrate(context);
            SyntaxSettings s = SyntaxPrefs.get(context);
            if (s.syncFrom() != null) {
                // Copying another TV's wallpapers (both archives): nothing comes from the internet here.
                results.add(ArchiveShare.mirror(context));
                return results;
            }
            Log.i(TAG, "sources ticked: " + s.sources());
            for (String source : s.sources()) {
                if (WallpaperSources.BING.equals(source) || Thread.currentThread().isInterrupted()) continue;
                if (!WallpaperSources.usable(source, DeviceConfig.unsplashKey(context))) {
                    Log.i(TAG, source + ": no API key");
                    results.add(new WallpaperSources.Result(source, 0, 0, s.perDay(source), true, false));
                    continue;
                }
                int perDay = s.perDay(source);
                int left = remaining(source, perDay);
                if (left <= 0) {
                    Log.i(TAG, source + ": today's " + perDay + " already downloaded");
                    results.add(new WallpaperSources.Result(source, 0, 0, perDay, false, true));
                    continue;
                }
                discarded = 0;
                try {
                    int added = fetch(source, left, s.themes());
                    if (added > 0) count(source, added);
                    ServiceStatus.ok(context, source);
                    Log.i(TAG, source + ": " + added + " added, " + discarded + " discarded");
                    results.add(new WallpaperSources.Result(source, added, discarded, perDay, false, false));
                } catch (Exception e) {
                    Log.w(TAG, source + " not refreshed: " + e.getMessage());
                    ServiceStatus.fail(context, source, e);
                    results.add(new WallpaperSources.Result(source, 0, discarded, perDay, true, false));
                }
            }
            trim(false, s.archiveCap());
            if (s.xmas(SyntaxSettings.XMAS_WALLPAPERS)) results.addAll(runChristmas());
        } catch (Exception e) {
            Log.w(TAG, "wallpaper sources skipped: " + e.getMessage());
        }
        return results;
    }

    /**
     * The "Riprova ora" of Sistema, Servizi for one wallpaper source: one photo now, whatever today's count, then
     * the cap; the result is recorded like a scheduled fetch. Blocking: call it off the main thread. Never throws.
     */
    void retry(String source) {
        try {
            if (archive == null || (!archive.isDirectory() && !archive.mkdirs())) return;
            if (!WallpaperSources.usable(source, DeviceConfig.unsplashKey(context))) {
                ServiceStatus.fail(context, source, "chiave mancante");
                return;
            }
            SyntaxSettings s = SyntaxPrefs.get(context);
            discarded = 0;
            int added = fetch(source, 1, s.themes());
            if (added > 0) count(source, added);
            ServiceStatus.ok(context, source);
            trim(false, s.archiveCap());
        } catch (Exception e) {
            Log.w(TAG, source + " not refreshed: " + e.getMessage());
            ServiceStatus.fail(context, source, e);
        }
    }

    /**
     * Only the Christmas sources, into the Christmas archive, then its cap: the Natale menu's "Scarica ora", and
     * {@link #run()} with the mode on. Blocking: call it off the main thread.
     */
    List<WallpaperSources.Result> runChristmas() {
        List<WallpaperSources.Result> results = new ArrayList<>();
        if (SyntaxPrefs.get(context).syncFrom() != null) {
            results.add(ArchiveShare.mirror(context));   // the other TV's Christmas photos come with the rest
            return results;
        }
        try {
            WallpaperLibrary.migrate(context);
            File dir = WallpaperLibrary.archive(context, true);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return results;
            results.addAll(christmas(SyntaxPrefs.get(context), dir));
            trim(true, SyntaxPrefs.get(context).archiveCap());
        } catch (Exception e) {
            Log.w(TAG, "Christmas sources skipped: " + e.getMessage());
        }
        return results;
    }

    /**
     * The Christmas mode's wallpapers: searches for the ticked Christmas themes on the ticked Christmas sources
     * (Wikimedia Commons; Unsplash with its key), as many a day as set in the Natale menu, saved in the
     * Christmas archive.
     */
    private List<WallpaperSources.Result> christmas(SyntaxSettings s, File dir) {
        List<WallpaperSources.Result> results = new ArrayList<>();
        List<String> themes = s.xmasThemes();
        for (String source : s.xmasSources()) {
            if (Thread.currentThread().isInterrupted()) break;
            String key = "xmas_" + source;
            int perDay = s.xmasPerDay(source);
            if (!WallpaperSources.usable(source, DeviceConfig.unsplashKey(context))) {
                results.add(new WallpaperSources.Result(key, 0, 0, perDay, true, false));
                continue;
            }
            int left = remaining(key, perDay);
            if (left <= 0) {
                results.add(new WallpaperSources.Result(key, 0, 0, perDay, false, true));
                continue;
            }
            discarded = 0;
            try {
                WallpaperState state = WallpaperLibrary.load(context, true);
                Set<String> have = new HashSet<>(WallpaperLibrary.ids(WallpaperLibrary.photos(context, true)));
                String theme = themes.get(random.nextInt(themes.size()));
                List<WallpaperSources.Photo> found;
                if (WallpaperSources.WIKIMEDIA.equals(source)) {
                    found = WallpaperSources.parseWikimediaSearch(get(WallpaperSources.wikimediaSearchUrl(
                            Christmas.query(theme, true), 20 * random.nextInt(4)), null));
                } else {
                    found = WallpaperSources.parseUnsplash(get(WallpaperSources.unsplashUrl(Christmas.query(theme, false),
                            left + 2), "Client-ID " + DeviceConfig.unsplashKey(context)));
                }
                java.util.Collections.shuffle(found, random);
                int added = 0;
                for (WallpaperSources.Photo p : found) {
                    if (added >= left || Thread.currentThread().isInterrupted()) break;
                    if (have.contains(p.id) || state.blacklist.contains(p.id)) continue;
                    if (!save(p, dir)) {
                        discarded++;
                        continue;
                    }
                    added++;
                    WallpaperLibrary.remember(context, true, p.id, p.title, p.credit);
                    if (p.ping != null) {
                        try {
                            get(p.ping, "Client-ID " + DeviceConfig.unsplashKey(context));
                        } catch (IOException e) {
                            Log.w(TAG, "Unsplash download ping failed: " + e.getMessage());
                        }
                    }
                }
                if (added > 0) count(key, added);
                Log.i(TAG, key + ": " + added + " added, " + discarded + " discarded");
                results.add(new WallpaperSources.Result(key, added, discarded, perDay, false, false));
            } catch (Exception e) {
                Log.w(TAG, key + " not refreshed: " + e.getMessage());
                results.add(new WallpaperSources.Result(key, 0, discarded, perDay, true, false));
            }
        }
        return results;
    }

    private int remaining(String source, int perDay) {
        return WallpaperSources.remainingToday(perDay, counts.getString(source + "_day", ""),
                counts.getInt(source + "_count", 0), LocalDate.now().toString());
    }

    private void count(String source, int added) {
        String today = LocalDate.now().toString();
        int before = today.equals(counts.getString(source + "_day", "")) ? counts.getInt(source + "_count", 0) : 0;
        counts.edit().putString(source + "_day", today).putInt(source + "_count", before + added).apply();
    }

    /** New photos added from one source, at most {@code want}. */
    private int fetch(String source, int want, List<String> themes) throws IOException {
        WallpaperState state = WallpaperLibrary.load(context);
        Set<String> have = new HashSet<>(WallpaperLibrary.ids(WallpaperLibrary.photos(context)));
        List<WallpaperSources.Photo> offer = new ArrayList<>();
        LocalDate day = LocalDate.now();
        switch (source) {
            case WallpaperSources.WIKIMEDIA:
                // Today's picture of the day, then the days before, until "want" new ones.
                for (int back = 0; back < want + 4 && offer.size() < want; back++) {
                    LocalDate d = day.minusDays(back);
                    WallpaperSources.Photo p = WallpaperSources.parseWikimedia(get(
                            WallpaperSources.wikimediaUrl(d.getYear(), d.getMonthValue(), d.getDayOfMonth()), null));
                    if (p != null && !have.contains(p.id) && !state.blacklist.contains(p.id)) offer.add(p);
                }
                break;
            case WallpaperSources.UNSPLASH: {
                String q = WallpaperSources.query(themes.get(random.nextInt(themes.size())), day.getMonthValue());
                for (WallpaperSources.Photo p : WallpaperSources.parseUnsplash(get(WallpaperSources.unsplashUrl(q, want + 2),
                        "Client-ID " + DeviceConfig.unsplashKey(context)))) {
                    if (offer.size() < want && !have.contains(p.id) && !state.blacklist.contains(p.id)) offer.add(p);
                }
                break;
            }
            default:
                return 0;
        }
        Log.i(TAG, source + ": " + offer.size() + " new photos offered");
        int added = 0;
        for (WallpaperSources.Photo p : offer) {
            if (Thread.currentThread().isInterrupted()) break;
            if (!save(p, archive)) {
                discarded++;
                continue;
            }
            added++;
            WallpaperLibrary.remember(context, p.id, p.title, p.credit);
            if (p.ping != null) {
                try {
                    get(p.ping, "Client-ID " + DeviceConfig.unsplashKey(context));   // Unsplash counts the download
                } catch (IOException e) {
                    Log.w(TAG, "Unsplash download ping failed: " + e.getMessage());
                }
            }
        }
        return added;
    }

    /** Saves a photo only when it downloads whole (8 MB at most), decodes, and is a landscape wallpaper. */
    private boolean save(WallpaperSources.Photo p, File dir) {
        File target = new File(dir, p.id + ".jpg"), part = new File(dir, p.id + ".part");
        HttpURLConnection c = null;
        try {
            c = open(p.url, null);
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            if (c.getContentLengthLong() > MAX_IMAGE_BYTES) throw new IOException("over 8 MB");
            String type = c.getContentType();
            if (type != null && !type.startsWith("image/")) throw new IOException("not an image: " + type);
            long size = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    size += n;
                    if (size > MAX_IMAGE_BYTES) throw new IOException("over 8 MB");
                    out.write(buf, 0, n);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(part.getPath(), o);
            // Portrait or small pictures are not wallpapers for a 16:9 screen: skipped, not cropped to a sliver.
            if (o.outWidth <= 0 || !WallpaperSources.landscape(o.outWidth, o.outHeight)) throw new IOException("not landscape");
            if (!part.renameTo(target)) throw new IOException("could not save");
            return true;
        } catch (Exception e) {
            Log.w(TAG, p.id + " not saved: " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Keeps an archive at {@code cap} photos: the oldest non-favourites go (not blacklisted: just old). */
    private void trim(boolean xmas, int cap) {
        File[] photos = WallpaperLibrary.photos(context, xmas);
        if (photos.length <= cap) return;
        WallpaperState state = WallpaperLibrary.load(context, xmas);
        List<WallpaperSources.Archived> list = new ArrayList<>();
        for (File f : photos) {
            String id = WallpaperLibrary.id(f);
            list.add(new WallpaperSources.Archived(id, f.lastModified(), state.favorite(id)));
        }
        for (String id : WallpaperSources.overCap(list, cap)) WallpaperLibrary.remove(context, xmas, id);
    }

    private static String get(String url, String auth) throws IOException {
        HttpURLConnection c = open(url, auth);
        try {
            int code = c.getResponseCode();
            Log.i(TAG, "GET " + url.replaceFirst("\\?.*$", "?…") + " -> " + code);
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > MAX_JSON_BYTES) throw new IOException("reply too large");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String auth) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        if (url.startsWith("https://api.unsplash.com/")) c.setRequestProperty("Accept-Version", "v1");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        return c;
    }
}
