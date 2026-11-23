package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The wallpaper sources: Wikimedia's picture of the day as served on 2026-11-05, Unsplash replies built from their API documentation (no key to fetch real ones); the JSON
 * reader, the cap, the quota and the settings.
 */
public class WallpaperSourcesTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = WallpaperSourcesTest.class.getResourceAsStream("/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void miniJsonReadsWhatTheSourcesSend() {
        Object o = MiniJson.parse("{\"a\":[1,2.5,-3e2,true,false,null,\"x\\u00e8\\n\"],\"b\":{\"c\":\"d\"}}");
        assertEquals(Arrays.asList(1.0, 2.5, -300.0, true, false, null, "xè\n"), MiniJson.list(o, "a"));
        assertEquals("d", MiniJson.str(MiniJson.obj(o, "b"), "c"));
        assertEquals(7, (int) MiniJson.number(o, "missing", 7));
        assertNull(MiniJson.parse("{\"a\":"));
        assertNull(MiniJson.parse("<html>"));
        assertNull(MiniJson.parse(null));
    }

    @Test
    public void wikimediaPictureOfTheDay() throws Exception {
        WallpaperSources.Photo p = WallpaperSources.parseWikimedia(fixture("wikimedia_potd.json"));
        assertNotNull(p);
        assertTrue(p.id, p.id.startsWith("WMC.Norderney"));
        assertTrue(p.url, p.url.startsWith("https://") && p.url.contains("/1920px-") && !p.url.contains("?"));
        assertTrue(p.credit, p.credit.contains("Dietmar Rabich") && p.credit.contains("CC BY-SA 4.0")
                && p.credit.contains("Wikimedia Commons"));
        assertFalse(p.title.contains("<"));
        assertEquals(WallpaperSources.WIKIMEDIA, WallpaperSources.sourceOf(p.id));
        // A portrait picture of the day, or a video, is no wallpaper.
        assertNull(WallpaperSources.parseWikimedia(fixture("wikimedia_potd.json")
                .replace("\"width\": 6720, \"height\": 4480", "\"width\": 3000, \"height\": 4500")));
        assertNull(WallpaperSources.parseWikimedia("{\"image\":{\"title\":\"File:Clip.webm\",\"image\":{\"width\":1920,"
                + "\"height\":1080},\"thumbnail\":{\"source\":\"https://upload.wikimedia.org/a/960px-Clip.webm.jpg\"}}}"));
        assertNull(WallpaperSources.parseWikimedia("{}"));
    }

    @Test
    public void unsplashPhotosWithAttributionAndDownloadPing() throws Exception {
        List<WallpaperSources.Photo> photos = WallpaperSources.parseUnsplash(fixture("unsplash_random.json"));
        assertEquals(1, photos.size());   // the portrait one is skipped
        WallpaperSources.Photo p = photos.get(0);
        assertEquals("UNS.Dwu85P9SOIk", p.id);
        assertEquals("Foto di Jane Smith su Unsplash", p.credit);
        assertTrue(p.url, p.url.startsWith("https://images.unsplash.com/") && p.url.contains("w=1920") && p.url.contains("h=1080"));
        assertEquals("https://api.unsplash.com/photos/Dwu85P9SOIk/download?ixid=abc", p.ping);
        assertTrue(WallpaperSources.unsplashUrl("sea coast", 2).contains("orientation=landscape&content_filter=high&count=2&query=sea+coast"));
        assertTrue(WallpaperSources.parseUnsplash("{\"errors\":[\"OAuth error\"]}").isEmpty());
    }

    @Test
    public void landscapeOnly() {
        assertTrue(WallpaperSources.landscape(1920, 1080));
        assertTrue(WallpaperSources.landscape(4000, 3000));     // 4:3, cropped to fill
        assertFalse(WallpaperSources.landscape(1080, 1920));
        assertFalse(WallpaperSources.landscape(1024, 576));     // too small
        assertFalse(WallpaperSources.landscape(2000, 1800));    // nearly square
    }

    @Test
    public void capDropsTheOldestNonFavourites() {
        List<WallpaperSources.Archived> a = new ArrayList<>();
        a.add(new WallpaperSources.Archived("old-fav", 1, true));
        a.add(new WallpaperSources.Archived("old", 2, false));
        a.add(new WallpaperSources.Archived("mid", 3, false));
        a.add(new WallpaperSources.Archived("new", 4, false));
        assertEquals(Collections.emptyList(), WallpaperSources.overCap(a, 4));
        assertEquals(Arrays.asList("old", "mid"), WallpaperSources.overCap(a, 2));
        // Favourites over the cap all stay.
        List<WallpaperSources.Archived> favs = Arrays.asList(new WallpaperSources.Archived("f1", 1, true),
                new WallpaperSources.Archived("f2", 2, true));
        assertTrue(WallpaperSources.overCap(favs, 1).isEmpty());
    }

    @Test
    public void dailyQuotaAndThemes() {
        assertEquals(2, WallpaperSources.remainingToday(2, "2026-11-04", 2, "2026-11-05"));   // a new day
        assertEquals(1, WallpaperSources.remainingToday(2, "2026-11-05", 1, "2026-11-05"));
        assertEquals(0, WallpaperSources.remainingToday(1, "2026-11-05", 3, "2026-11-05"));
        assertEquals("winter landscape", WallpaperSources.query(WallpaperSources.SEASON, 1));
        assertEquals("autumn landscape", WallpaperSources.query(WallpaperSources.SEASON, 11));
        assertEquals("sea coast", WallpaperSources.query(WallpaperSources.SEA, 6));
        List<String> ids = Arrays.asList("OHR.A_IT-IT1", "WMC.B", "UNS.c", "PEX.1", "APOD.2026-10-12");
        assertEquals(Collections.singletonList("UNS.c"), WallpaperSources.filter(ids, WallpaperSources.UNSPLASH));
        assertEquals(ids, WallpaperSources.filter(ids, "all"));
        assertEquals("sea_coast_x", WallpaperSources.safe("sea coast/x"));
        assertFalse(WallpaperSources.usable(WallpaperSources.UNSPLASH, ""));
        assertFalse(WallpaperSources.usable("pexels", "u"));   // no longer a source
        assertFalse(WallpaperSources.usable("apod", "u"));     // no longer a source
        assertEquals("apod", WallpaperSources.sourceOf("APOD.2026-10-12"));   // kept photos keep their name
        assertTrue(WallpaperSources.usable(WallpaperSources.WIKIMEDIA, ""));
    }

    @Test
    public void wikimediaChristmasSearch() throws Exception {
        // Commons' Quality images for "christmas lights", as served on 2026-11-05.
        List<WallpaperSources.Photo> photos = WallpaperSources.parseWikimediaSearch(fixture("wikimedia_christmas.json"));
        assertEquals(5, photos.size());   // all five are landscape
        for (WallpaperSources.Photo p : photos) {
            assertTrue(p.id, p.id.startsWith("WMC."));
            assertTrue(p.url, p.url.startsWith("https://") && p.url.contains("1920px"));
            assertTrue(p.credit, p.credit.contains("Wikimedia Commons") && p.credit.contains("CC BY-SA"));
        }
        assertTrue(WallpaperSources.wikimediaSearchUrl("christmas lights", 20).contains("gsroffset=20"));
        assertTrue(WallpaperSources.parseWikimediaSearch("{}").isEmpty());
    }

    @Test
    public void roundOutcomes() {
        assertEquals(WallpaperSources.Result.ADDED, new WallpaperSources.Result("unsplash", 2, 1, 3, false, false).outcome());
        assertEquals(WallpaperSources.Result.QUOTA, new WallpaperSources.Result("wikimedia", 0, 0, 3, false, true).outcome());
        assertEquals(WallpaperSources.Result.DISCARDED, new WallpaperSources.Result("pexels", 0, 2, 1, false, false).outcome());
        assertEquals(WallpaperSources.Result.FAILED, new WallpaperSources.Result("wikimedia", 0, 0, 1, true, false).outcome());
        assertEquals(WallpaperSources.Result.NOTHING_NEW, new WallpaperSources.Result("wikimedia", 0, 0, 1, false, false).outcome());
    }

    @Test
    public void sourceSettings() {
        Map<String, String> map = new HashMap<>();
        SyntaxSettings.Store store = new SyntaxSettings.Store() {
            @Override
            public String get(String key) {
                return map.get(key);
            }

            @Override
            public void put(String key, String value) {
                map.put(key, value);
            }
        };
        SyntaxSettings s = new SyntaxSettings(store, new SyntaxSettings.Defaults("live", true, 70, 60));
        assertEquals(Collections.singletonList(WallpaperSources.BING), s.sources());   // Bing only by default
        assertEquals(1, s.perDay(WallpaperSources.WIKIMEDIA));
        assertEquals(Collections.singletonList(WallpaperSources.NATURE), s.themes());
        assertEquals(500, s.archiveCap());
        s.setSource(WallpaperSources.WIKIMEDIA, true);
        s.setPerDay(WallpaperSources.WIKIMEDIA, 3);
        s.setPerDay(WallpaperSources.BING, 3);              // Bing stays at one a day
        s.setTheme(WallpaperSources.SEA, true);
        s.setArchiveCap(1000);
        assertEquals(Arrays.asList(WallpaperSources.BING, WallpaperSources.WIKIMEDIA), s.sources());
        assertEquals(3, s.perDay(WallpaperSources.WIKIMEDIA));
        assertEquals(1, s.perDay(WallpaperSources.BING));
        assertEquals(Arrays.asList(WallpaperSources.SEA, WallpaperSources.NATURE), s.themes());
        assertEquals(1000, s.archiveCap());
        s.setSource(WallpaperSources.BING, false);
        s.setSource(WallpaperSources.WIKIMEDIA, false);
        assertTrue(s.sources().isEmpty());                  // all off stays off, not back to Bing
    }
}
