package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The last success and the last error of each online source, written by the existing download code (one line
 * there, after it has already decided what to do: recording never changes a download) and read by Sistema, Servizi
 * and the Home alerts. In SharedPreferences "service_status". Never throws.
 */
final class ServiceStatus {

    private static final String TAG = "TvHome.Services";
    private static final String PREFS = "service_status";

    static final String BING = "bing", WIKIMEDIA = "wikimedia", UNSPLASH = "unsplash", ANSA = "ansa", ILPOST = "ilpost",
            METEO = "meteo", EUMETSAT = "eumetsat", SDO = "sdo", EPIC = "epic", MOON = "moon", ISS = "iss", ESA = "esa",
            GEO = "geo", GITHUB = "github", FILTER_LISTS = "filter_lists";

    /** A source as the list shows it. {@code lateAfter}: no success for longer is "in ritardo". */
    static final class Source {
        final String id, name, use;
        final long lateAfter;

        Source(String id, String name, String use, long lateAfter) {
            this.id = id;
            this.name = name;
            this.use = use;
            this.lateAfter = lateAfter;
        }
    }

    static final List<Source> SOURCES = Collections.unmodifiableList(edition(Arrays.asList(
            new Source(BING, "Bing", "sfondi", 2 * ServiceHealth.DAY),
            new Source(WIKIMEDIA, "Wikimedia Commons", "sfondi", 2 * ServiceHealth.DAY),
            new Source(UNSPLASH, "Unsplash", "sfondi", 2 * ServiceHealth.DAY),
            new Source(ANSA, "ANSA", "notizie", 12 * ServiceHealth.HOUR),
            new Source(ILPOST, "Il Post", "notizie", 12 * ServiceHealth.HOUR),
            new Source(METEO, "Open-Meteo", "meteo", 12 * ServiceHealth.HOUR),
            new Source(EUMETSAT, "Meteosat (EUMETSAT)", "Terra in diretta", 2 * ServiceHealth.DAY),
            new Source(SDO, "NASA SDO", "Sole adesso", 2 * ServiceHealth.DAY),
            new Source(EPIC, "NASA EPIC", "Terra adesso", 2 * ServiceHealth.DAY),
            new Source(MOON, "NASA Dial-a-Moon", "Luna adesso", 2 * ServiceHealth.DAY),
            new Source(ISS, "CelesTrak", "orbita ISS", 7 * ServiceHealth.DAY),
            new Source(ESA, "ESA Webb e Hubble", "Webb e Hubble", 7 * ServiceHealth.DAY),
            new Source(GEO, "ipwho.is", "posizione per il meteo", 30 * ServiceHealth.DAY),
            new Source(GITHUB, "GitHub", "aggiornamenti", 3 * ServiceHealth.DAY),
            new Source(FILTER_LISTS, "Liste del filtro (HaGeZi)", "filtro DNS", 14 * ServiceHealth.DAY))));

    /** TV Liberata has no Unsplash and no Il Post: their rows are left out. */
    private static List<Source> edition(List<Source> all) {
        List<Source> out = new java.util.ArrayList<>();
        for (Source s : all) {
            if (UNSPLASH.equals(s.id) && !Features.UNSPLASH) continue;
            if ((ANSA.equals(s.id) || ILPOST.equals(s.id)) && !Features.NEWS_TICKER) continue;
            boolean scene = EUMETSAT.equals(s.id) || SDO.equals(s.id) || EPIC.equals(s.id) || MOON.equals(s.id)
                    || ISS.equals(s.id) || ESA.equals(s.id);
            if (scene && !Features.SPACE_SCENES) continue;
            out.add(s);
        }
        return out;
    }

    /** What is saved for one source. */
    static final class Entry {
        long lastOk, lastErr, errSince;
        String message;

        int state(Source s, long now) {
            return ServiceHealth.state(lastOk, lastErr, now, s.lateAfter);
        }

        long failingFor(long now) {
            return ServiceHealth.failingFor(lastOk, lastErr, errSince, now);
        }
    }

    private ServiceStatus() {
    }

    static void ok(Context c, String id) {
        try {
            prefs(c).edit().putLong(id + "_ok", System.currentTimeMillis()).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "status not saved: " + e.getMessage());
        }
    }

    static void fail(Context c, String id, Throwable t) {
        fail(c, id, ServiceHealth.reason(t));
    }

    static void fail(Context c, String id, String message) {
        try {
            SharedPreferences p = prefs(c);
            long now = System.currentTimeMillis();
            long since = ServiceHealth.errorSince(p.getLong(id + "_ok", 0), p.getLong(id + "_since", 0), now);
            p.edit().putLong(id + "_err", now).putLong(id + "_since", since)
                    .putString(id + "_msg", ServiceHealth.reason(message)).apply();
        } catch (RuntimeException e) {
            Log.w(TAG, "status not saved: " + e.getMessage());
        }
    }

    static Entry get(Context c, String id) {
        Entry e = new Entry();
        try {
            SharedPreferences p = prefs(c);
            e.lastOk = p.getLong(id + "_ok", 0);
            e.lastErr = p.getLong(id + "_err", 0);
            e.errSince = p.getLong(id + "_since", 0);
            e.message = p.getString(id + "_msg", null);
        } catch (RuntimeException ignored) {
            // empty entry: "mai usata"
        }
        return e;
    }

    /** Sources failing for longer than {@link ServiceHealth#ALERT_AFTER}, by name. */
    static List<String> failingLong(Context c, long now) {
        List<String> out = new java.util.ArrayList<>();
        for (Source s : SOURCES) {
            if (get(c, s.id).failingFor(now) > ServiceHealth.ALERT_AFTER) out.add(s.name);
        }
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- "Riprova ora" -------------------------------------------------------------------------------------

    /**
     * Tries the source again now, through its own download code where it has one (which records the result), or
     * by asking its service once. Blocking: off the main thread. Never throws.
     */
    static void retry(Context c, String id) {
        Context app = c.getApplicationContext();
        try {
            switch (id) {
                case GEO:
                    Place.prefs(app).edit().putLong("auto_next", 0).apply();
                    Place.refreshAuto(app);
                    break;
                case GITHUB:
                    Updater.latestQuiet(app);
                    break;
                case FILTER_LISTS:
                    DnsVpnService.refreshNow(app);
                    break;
                case BING:
                    probe(app, id, WallpaperPlayer.BING_API);
                    // Home looks at Bing again at its next minute instead of in up to 6 hours.
                    app.getSharedPreferences(WallpaperPlayer.PREFS, Context.MODE_PRIVATE).edit().remove("bing_next").apply();
                    break;
                case WIKIMEDIA:
                case UNSPLASH:
                    new WallpaperFetcher(app, WallpaperLibrary.archive(app)).retry(id);
                    break;
                case ANSA:
                case ILPOST: {
                    // This source's ticked feeds (or its main one), so "Riprova" always tries it.
                    List<String> ticked = SyntaxPrefs.get(app).sections();
                    List<String> sections = new java.util.ArrayList<>();
                    for (String k : ticked) if (NewsData.ansa(k) == ANSA.equals(id)) sections.add(k);
                    if (sections.isEmpty()) sections.add(ANSA.equals(id) ? NewsData.TOP : NewsData.ILPOST);
                    NewsTicker.fetch(app, sections, NewsTicker.cached(app, ticked));
                    break;
                }
                case METEO:
                    probe(app, id, PersonalHeader.forecastUrl(app));
                    break;
                case EUMETSAT:
                    new LiveImages(app).refresh(null);
                    break;
                case SDO:
                    new SolarImages(app).refresh();
                    break;
                case EPIC:
                    new EarthImages(app).refresh();
                    break;
                case MOON:
                    new MoonImages(app).refresh(System.currentTimeMillis());
                    break;
                case ISS: {
                    IssTle tle = new IssTle(app);
                    tle.download(tle.load());
                    break;
                }
                case ESA:
                    new SpaceImages(app).refresh(null);
                    break;
                default:
                    break;
            }
        } catch (RuntimeException e) {
            fail(app, id, e);
        }
    }

    /** One GET; a 200 is a success. Used where the download itself belongs to a screen that is not open. */
    private static void probe(Context c, String id, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("User-Agent", "TvHome-launcher");
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                //noinspection ResultOfMethodCallIgnored
                in.read(new byte[4096]);   // the start of the reply is enough
            }
            ok(c, id);
        } catch (IOException | RuntimeException e) {
            fail(c, id, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** What this TV uses now, for the reasons in Sistema, Servizi. Never throws. */
    static ServiceHealth.Usage usage(Context c) {
        ServiceHealth.Usage u = new ServiceHealth.Usage();
        try {
            SyntaxSettings s = SyntaxPrefs.get(c);
            u.unsplashKey = WallpaperSources.usable(UNSPLASH, DeviceConfig.unsplashKey(c));
            u.wallpaperSources.addAll(s.sources());
            if (s.xmas(SyntaxSettings.XMAS_WALLPAPERS)) u.wallpaperSources.addAll(s.xmasSources());
            u.scenes.addAll(s.scenes());
            u.ticker = s.ticker() || s.homeTicker();
            for (String k : s.sections()) u.newsSources.add(NewsData.ansa(k) ? ANSA : ILPOST);
            u.otherDream = otherDream(c);
        } catch (RuntimeException e) {
            Log.w(TAG, "usage not read: " + e.getMessage());
        }
        return u;
    }

    /** The system screensaver's name when it is not ClockDream (or screensavers are off); null when it is ours. */
    private static String otherDream(Context c) {
        try {
            android.content.ContentResolver r = c.getContentResolver();
            if (android.provider.Settings.Secure.getInt(r, "screensaver_enabled", 1) == 0) return "spento";
            String comp = android.provider.Settings.Secure.getString(r, "screensaver_components");
            if (comp == null || comp.isEmpty()) return null;
            android.content.ComponentName cn = android.content.ComponentName.unflattenFromString(comp.split(",")[0]);
            if (cn == null || c.getPackageName().equals(cn.getPackageName())) return null;
            android.content.pm.PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(cn.getPackageName(), 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    static String describe(Entry e, Source s, long now, java.util.TimeZone zone) {
        int state = e.state(s, now);
        String text = "riuscito " + ServiceHealth.when(e.lastOk, now, zone);
        if (e.lastErr > 0) {
            text += " · errore " + ServiceHealth.when(e.lastErr, now, zone) + (e.message == null ? "" : ": " + e.message);
        }
        return state == ServiceHealth.UNKNOWN ? "nessun tentativo da quando c'è questa pagina" : text;
    }
}
