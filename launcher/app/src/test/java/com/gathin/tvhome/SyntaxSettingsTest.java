package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Syntax TV's settings: the build's defaults, saving and reading back, and the rules on the ticks. */
public class SyntaxSettingsTest {

    private static final class MapStore implements SyntaxSettings.Store {
        final Map<String, String> map = new HashMap<>();

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }
    }

    private static SyntaxSettings settings(MapStore store, String dreamScene) {
        return new SyntaxSettings(store, new SyntaxSettings.Defaults(dreamScene, true, 70, 60));
    }

    @Test
    public void defaultsComeFromTheBuild() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.SPACE_SCENES && Features.NEWS_TICKER);
        SyntaxSettings s = settings(new MapStore(), "live");
        assertEquals(Collections.singletonList("live"), s.scenes());
        assertTrue(s.ticker());
        assertEquals(SyntaxSettings.MEDIUM, s.tickerSpeed());
        assertEquals(70, s.tickerSpeedDp());
        assertEquals(SyntaxSettings.MEDIUM, s.tickerSize());
        assertEquals(Collections.singletonList(NewsData.TOP), s.sections());
        assertEquals(60, s.wallpaperMinutes());
        assertEquals(SyntaxSettings.RANDOM_DEFAULT, settings(new MapStore(), "random").scenes());
        assertEquals(Collections.singletonList("clock"), settings(new MapStore(), "nonsense").scenes());
    }

    @Test
    public void savedValuesReadBack() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.SPACE_SCENES && Features.NEWS_TICKER);
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "live");
        assertTrue(s.setScene("iss", true));
        assertTrue(s.setScene("clock", true));
        s.setTicker(false);
        s.setTickerSpeed(SyntaxSettings.FAST);
        s.setTickerSize(SyntaxSettings.LARGE);
        assertTrue(s.setSection("sport", true));
        s.setWallpaperMinutes(30);
        s.setXmasWallpaperMinutes(15);
        SyntaxSettings again = settings(store, "moon");   // a new read, as after a restart
        assertEquals(Arrays.asList("clock", "iss", "live"), again.scenes());   // in list order
        assertFalse(again.ticker());
        assertEquals(105, again.tickerSpeedDp());
        assertEquals(SyntaxSettings.LARGE, again.tickerSize());
        assertEquals(Arrays.asList(NewsData.TOP, "sport"), again.sections());
        assertEquals(30, again.wallpaperMinutes());
        assertEquals(15, again.xmasWallpaperMinutes());   // each archive its own interval
    }

    @Test
    public void wallpaperIntervalsAreFifteenThirtyOrSixty() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "live");
        assertEquals(60, s.wallpaperMinutes());
        assertEquals(60, s.xmasWallpaperMinutes());
        // Saved before, when 3 h, 6 h and 1 day were offered: an hour now.
        store.put(SyntaxSettings.KEY_WALLPAPER, "360");
        store.put(SyntaxSettings.KEY_XMAS_WALLPAPER, "1440");
        assertEquals(60, s.wallpaperMinutes());
        assertEquals(60, s.xmasWallpaperMinutes());
        s.setWallpaperMinutes(180);   // not offered: ignored
        assertEquals(60, s.wallpaperMinutes());
        s.setWallpaperMinutes(15);
        assertEquals(15, s.wallpaperMinutes());
        assertEquals(60, s.xmasWallpaperMinutes());
    }

    @Test
    public void christmasSourcesAndThemes() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.NEWS_TICKER && Features.UNSPLASH);
        SyntaxSettings s = settings(new MapStore(), "live");
        assertEquals(Arrays.asList(WallpaperSources.WIKIMEDIA, WallpaperSources.UNSPLASH), s.xmasSources());
        assertEquals(Christmas.THEMES, s.xmasThemes());
        s.setXmasSource(WallpaperSources.UNSPLASH, false);
        s.setXmasSource(WallpaperSources.BING, true);   // not a Christmas source
        assertEquals(Collections.singletonList(WallpaperSources.WIKIMEDIA), s.xmasSources());
        s.setXmasSource(WallpaperSources.WIKIMEDIA, false);
        assertTrue(s.xmasSources().isEmpty());
        assertTrue(s.setXmasTheme(Christmas.THEME_MARKET, false));
        assertFalse(s.xmasThemes().contains(Christmas.THEME_MARKET));
        s.setPerDay(WallpaperSources.WIKIMEDIA, 2);
        assertEquals(2, s.xmasPerDay(WallpaperSources.WIKIMEDIA));   // the general value until set
        s.setXmasPerDay(WallpaperSources.WIKIMEDIA, 3);
        assertEquals(3, s.xmasPerDay(WallpaperSources.WIKIMEDIA));
        assertEquals(2, s.perDay(WallpaperSources.WIKIMEDIA));
        assertEquals("christmas market", Christmas.query(Christmas.THEME_MARKET, false));
        assertEquals("snowy village winter", Christmas.query(Christmas.THEME_SNOW, true));
    }

    @Test
    public void wallpaperSharingIsOffUntilChosen() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "live");
        assertFalse(s.shareWallpapers());
        assertEquals(null, s.syncFrom());
        s.setShareWallpapers(true);
        s.setSyncFrom("  BRAVIA 4K GB ATV3  ");
        SyntaxSettings again = settings(store, "live");
        assertTrue(again.shareWallpapers());
        assertEquals("BRAVIA 4K GB ATV3", again.syncFrom());
        again.setSyncFrom(null);
        assertEquals(null, settings(store, "live").syncFrom());
        again.setSyncFrom("   ");
        assertEquals(null, settings(store, "live").syncFrom());
    }

    @Test
    public void theLastTickStays() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.SPACE_SCENES);
        SyntaxSettings s = settings(new MapStore(), "live");
        assertFalse(s.setScene("live", false));
        assertEquals(Collections.singletonList("live"), s.scenes());
        assertTrue(s.setScene("sun", true));
        assertTrue(s.setScene("live", false));
        assertEquals(Collections.singletonList("sun"), s.scenes());
        assertFalse(s.setScene("mars", true));
        assertFalse(s.setSection(NewsData.TOP, false));
        assertFalse(s.setSection("spettacolo", true));   // no such ANSA feed
    }

    @Test
    public void brokenValuesFallBack() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.SPACE_SCENES && Features.NEWS_TICKER);
        MapStore store = new MapStore();
        store.put(SyntaxSettings.KEY_SCENES, " , mars ,,");
        store.put(SyntaxSettings.KEY_SPEED, "warp");
        store.put(SyntaxSettings.KEY_SIZE, "huge");
        store.put(SyntaxSettings.KEY_WALLPAPER, "7");
        store.put(SyntaxSettings.KEY_SECTIONS, "SPORT,,sport,nope");
        SyntaxSettings s = settings(store, "iss");
        assertEquals(Collections.singletonList("iss"), s.scenes());
        assertEquals(SyntaxSettings.MEDIUM, s.tickerSpeed());
        assertEquals(SyntaxSettings.MEDIUM, s.tickerSize());
        assertEquals(60, s.wallpaperMinutes());
        assertEquals(Collections.singletonList("sport"), s.sections());
    }

    @Test
    public void homeTickerIsOffByDefaultAndSeparate() {
        org.junit.Assume.assumeTrue("off (Features)", Features.NEWS_TICKER);
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "live");
        assertFalse(s.homeTicker());              // 1.0.6: off until turned on
        s.setHomeTicker(true);
        assertTrue(settings(store, "live").homeTicker());
        s.setTicker(false);                       // the screensaver's switch does not touch Home's
        assertTrue(settings(store, "live").homeTicker());
        assertFalse(settings(store, "live").ticker());
        s.setHomeTicker(false);
        assertFalse(settings(store, "live").homeTicker());
    }

    @Test
    public void homeIdleClockIsOnByDefaultAndCanBeSwitchedOff() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "live");
        assertTrue(s.homeIdleClock());            // the theme's home_idle_clock_minutes decides
        s.setHomeIdleClock(false);
        assertFalse(settings(store, "live").homeIdleClock());
        s.setHomeIdleClock(true);
        assertTrue(settings(store, "live").homeIdleClock());
    }

    @Test
    public void systemAlertsAreOnByDefault() {
        MapStore store = new MapStore();
        assertTrue(settings(store, "live").systemAlerts());
        settings(store, "live").setSystemAlerts(false);
        assertFalse(settings(store, "live").systemAlerts());
    }

    @Test
    public void newWallpaperOnWakeIsOnByDefault() {
        MapStore store = new MapStore();
        assertTrue(settings(store, "live").wallpaperOnWake());
        settings(store, "live").setWallpaperOnWake(false);
        assertFalse(settings(store, "live").wallpaperOnWake());
    }

    @Test
    public void homeTickerKeyFollowsWhatTheBandShows() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.NEWS_TICKER);
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "live");
        assertEquals("", s.homeTickerKey());      // off by default: no band
        s.setHomeTicker(true);
        String first = s.homeTickerKey();
        assertFalse(first.isEmpty());
        s.setTickerSize(SyntaxSettings.LARGE);
        String bigger = s.homeTickerKey();
        assertFalse(first.equals(bigger));
        s.setSection("sport", true);
        assertFalse(bigger.equals(s.homeTickerKey()));
        s.setTickerSpeed(SyntaxSettings.SLOW);
        assertEquals(s.homeTickerKey(), settings(store, "moon").homeTickerKey());   // same settings, same band
        s.setHomeTicker(false);
        assertEquals("", s.homeTickerKey());
    }

    @Test
    public void speedsAndSizes() {
        assertEquals(45, SyntaxSettings.speedDp(SyntaxSettings.SLOW));
        assertEquals(70, SyntaxSettings.speedDp(SyntaxSettings.MEDIUM));
        assertEquals(105, SyntaxSettings.speedDp(SyntaxSettings.FAST));
        assertEquals(0.06f, SyntaxSettings.bandFraction(SyntaxSettings.MEDIUM), 0);
        assertTrue(SyntaxSettings.bandFraction(SyntaxSettings.SMALL) < SyntaxSettings.bandFraction(SyntaxSettings.LARGE));
        assertEquals(SyntaxSettings.SLOW, new SyntaxSettings(new MapStore(),
                new SyntaxSettings.Defaults("clock", true, 40, 60)).tickerSpeed());
    }

    @Test
    public void profileDefaultsToLivingRoomAndKeepsOnlyKnownNames() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "clock");
        assertEquals("living", s.profile());
        assertTrue(s.setProfile("kitchen"));
        assertEquals("kitchen", s.profile());
        assertFalse(s.setProfile("garage"));
        assertEquals("kitchen", s.profile());
        store.put(SyntaxSettings.KEY_PROFILE, "nonsense");
        assertEquals("living", s.profile());
    }

    @Test
    public void appsPositionDefaultsToBottomAsBefore() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "clock");
        assertEquals("bottom", s.appsPosition());
        assertTrue(s.setAppsPosition("center"));
        assertEquals("center", s.appsPosition());
        assertFalse(s.setAppsPosition("left"));
        assertEquals("center", s.appsPosition());
    }

    @Test
    public void kitchenPanelOptionsStartOff() {
        SyntaxSettings s = settings(new MapStore(), "clock");
        assertFalse(s.kitchen());
        assertFalse(s.kitchenScreensaver());
        assertFalse(s.kitchenRecycling());
        s.setProfile("kitchen");
        s.setKitchenScreensaver(true);
        assertTrue(s.kitchen());
        assertTrue(s.kitchenScreensaver());
        assertFalse(s.kitchenRecycling());
    }

    @Test
    public void kitchenNightDefaultsAndWrapsMidnight() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "clock");
        assertEquals(23 * 60, s.kitchenNightStart());
        assertEquals(6 * 60 + 30, s.kitchenNightEnd());
        assertTrue(SyntaxSettings.isNight(23 * 60, 23 * 60, 390));
        assertTrue(SyntaxSettings.isNight(3 * 60, 23 * 60, 390));
        assertFalse(SyntaxSettings.isNight(390, 23 * 60, 390));
        assertFalse(SyntaxSettings.isNight(12 * 60, 23 * 60, 390));
        assertTrue(SyntaxSettings.isNight(60, 0, 390));
        assertFalse(SyntaxSettings.isNight(3 * 60, -1, 390));
        s.setKitchenNight(-1, 7 * 60);
        assertEquals(-1, s.kitchenNightStart());
        assertEquals(7 * 60, s.kitchenNightEnd());
        store.put(SyntaxSettings.KEY_KITCHEN_NIGHT_END, "-1");
        assertEquals(6 * 60 + 30, s.kitchenNightEnd());
        assertEquals(0, SyntaxSettings.next(SyntaxSettings.NIGHT_STARTS, 23 * 60 + 30));
        assertEquals(21 * 60, SyntaxSettings.next(SyntaxSettings.NIGHT_STARTS, -1));
    }

    @Test
    public void screenMarginFallsBackAndClamps() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "clock");
        assertEquals(0, s.screenMargin(0));
        assertEquals(5, s.screenMargin(5));
        assertEquals(5, s.screenMargin(9));
        s.setScreenMargin(3);
        assertEquals(3, s.screenMargin(5));
        s.setScreenMargin(12);
        assertEquals(5, s.screenMargin(0));
        store.put(SyntaxSettings.KEY_SCREEN_MARGIN, "x");
        assertEquals(2, s.screenMargin(2));
    }

    @Test
    public void recyclingDefaultsToRome() {
        MapStore store = new MapStore();
        SyntaxSettings s = settings(store, "clock");
        assertEquals(Recycling.DEFAULT, s.recyclingWeek());
        assertEquals(19 * 60, s.recyclingFrom());
        assertEquals(21 * 60, s.recyclingTo());
        assertEquals(9 * 60, s.recyclingBy());
        s.setRecyclingHours(18 * 60, 22 * 60, 1234);
        assertEquals(18 * 60, s.recyclingFrom());
        assertEquals(22 * 60, s.recyclingTo());
        assertEquals(9 * 60, s.recyclingBy());
        s.setRecyclingWeek("1:vetro;9:carta");
        assertEquals("1:vetro", s.recyclingWeek());
        s.setRecyclingWeek("");
        assertEquals("", s.recyclingWeek());
    }

    @Test
    public void lazioLeavesTheSavedSections() {   // 1.1.0
        MapStore store = new MapStore();
        store.put("news_sections", "ultimaora,lazio,sport");
        SyntaxSettings s = settings(store, "clock");
        assertEquals(Arrays.asList(NewsData.TOP, "sport"), s.sections());
        assertEquals("ultimaora,sport", store.get("news_sections"));
        store.put("news_sections", "lazio");
        assertEquals(Collections.singletonList(NewsData.TOP), settings(store, "clock").sections());
        assertEquals("ultimaora", store.get("news_sections"));
    }
}
