package com.gathin.tvhome;

import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Christmas mode: the switches, the scenes' rotation, the header's line, the Advent calendar and Santa. */
public class ChristmasTest {

    private static SyntaxSettings settings(Map<String, String> map) {
        return new SyntaxSettings(new SyntaxSettings.Store() {
            @Override
            public String get(String key) {
                return map.get(key);
            }

            @Override
            public void put(String key, String value) {
                map.put(key, value);
            }
        }, new SyntaxSettings.Defaults("live", true, 70, 60));
    }

    @Test
    public void offByDefaultAndNothingChanges() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.CHRISTMAS && Features.SPACE_SCENES);
        SyntaxSettings s = settings(new HashMap<>());
        assertFalse(s.christmas());
        for (String key : new String[]{SyntaxSettings.XMAS_WALLPAPERS, SyntaxSettings.XMAS_SNOW_HOME,
                SyntaxSettings.XMAS_COUNTDOWN, SyntaxSettings.XMAS_DOCK, SyntaxSettings.XMAS_SNOW_DREAM}) {
            assertFalse(key, s.xmas(key));
            assertTrue(key, s.xmasSwitch(key));     // on, waiting for the master
        }
        assertEquals(Collections.singletonList("live"), s.dreamCandidates());
    }

    @Test
    public void masterAndOwnSwitches() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.CHRISTMAS);
        Map<String, String> map = new HashMap<>();
        SyntaxSettings s = settings(map);
        s.setChristmas(true);
        assertTrue(s.xmas(SyntaxSettings.XMAS_SNOW_HOME));
        s.setXmasSwitch(SyntaxSettings.XMAS_SNOW_HOME, false);
        assertFalse(settings(map).xmas(SyntaxSettings.XMAS_SNOW_HOME));
        assertTrue(settings(map).xmas(SyntaxSettings.XMAS_DOCK));
        s.setChristmas(false);
        assertFalse(settings(map).xmas(SyntaxSettings.XMAS_DOCK));
        assertFalse(settings(map).xmasSwitch(SyntaxSettings.XMAS_SNOW_HOME));   // kept for next time
    }

    @Test
    public void scenesJoinTheRotationOnlyWithTheMode() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.CHRISTMAS && Features.SPACE_SCENES);
        Map<String, String> map = new HashMap<>();
        SyntaxSettings s = settings(map);
        s.setScene("iss", true);
        assertEquals(Arrays.asList("iss", "live"), s.dreamCandidates());
        s.setChristmas(true);
        List<String> all = s.dreamCandidates();
        assertEquals(5, all.size());   // iss, live and the three Christmas scenes
        assertTrue(all.containsAll(Christmas.SCENES) && all.contains("iss"));
        s.setXmasOnly(true);
        assertEquals(Christmas.SCENES, s.dreamCandidates());
        s.setXmasScene(Christmas.SANTA, false);
        assertFalse(s.dreamCandidates().contains(Christmas.SANTA));
        for (String x : Christmas.SCENES) s.setXmasScene(x, false);
        assertEquals(Arrays.asList("iss", "live"), s.dreamCandidates());          // none ticked: as before
    }

    @Test
    public void headerLine() {
        assertArrayEquals(new int[]{Christmas.COUNTDOWN, 50}, Christmas.greeting(LocalDate.of(2026, 11, 5)));
        assertArrayEquals(new int[]{Christmas.COUNTDOWN, 2}, Christmas.greeting(LocalDate.of(2026, 12, 23)));
        assertEquals(Christmas.EVE, Christmas.greeting(LocalDate.of(2026, 12, 24))[0]);
        assertEquals(Christmas.DAY, Christmas.greeting(LocalDate.of(2026, 12, 25))[0]);
        assertEquals(Christmas.HOLIDAYS, Christmas.greeting(LocalDate.of(2026, 12, 31))[0]);
        assertEquals(Christmas.NEW_YEAR, Christmas.greeting(LocalDate.of(2027, 1, 6))[0]);
        assertArrayEquals(new int[]{Christmas.COUNTDOWN, 352}, Christmas.greeting(LocalDate.of(2027, 1, 7)));
    }

    @Test
    public void adventCalendar() {
        assertArrayEquals(new int[]{0, 0, 26}, Christmas.advent(LocalDate.of(2026, 11, 5)));
        assertArrayEquals(new int[]{0, 1, 0}, Christmas.advent(LocalDate.of(2026, 12, 1)));
        assertArrayEquals(new int[]{11, 12, 0}, Christmas.advent(LocalDate.of(2026, 12, 12)));
        assertArrayEquals(new int[]{23, 24, 0}, Christmas.advent(LocalDate.of(2026, 12, 24)));
        assertArrayEquals(new int[]{24, 0, 0}, Christmas.advent(LocalDate.of(2026, 12, 30)));
        assertArrayEquals(new int[]{24, 0, 0}, Christmas.advent(LocalDate.of(2027, 1, 6)));
        assertEquals(0, Christmas.advent(LocalDate.of(2027, 1, 7))[0]);
    }

    private static long utc(String s) {
        return Instant.parse(s).toEpochMilli();
    }

    @Test
    public void santaWaitsAtThePole() {
        double[] s = Christmas.santa(utc("2026-12-20T12:00:00Z"));
        assertEquals(Christmas.BEFORE, s[0], 0);
        assertEquals(4 * Christmas.DAY_MS, s[4], 0);
        assertEquals(Christmas.BEFORE, Christmas.santa(utc("2027-01-07T00:00:00Z"))[0], 0);   // next year's
    }

    @Test
    public void santaFollowsMidnightWest() {
        // Rome is at about 12.5 E: its local midnight is 23:10 UTC on the 24th.
        double[] rome = Christmas.santa(utc("2026-12-24T23:10:00Z"));
        assertEquals(Christmas.FLYING, rome[0], 0);
        assertEquals(12.5, rome[2], 0.1);
        assertTrue(rome[1] > 30 && rome[1] < 46);   // between Cape Town and Rome on the route
        assertTrue(rome[3] > 0.6 * Christmas.PRESENTS && rome[3] < 0.85 * Christmas.PRESENTS);
        double[] early = Christmas.santa(utc("2026-12-24T14:00:00Z"));
        double[] late = Christmas.santa(utc("2026-12-25T06:00:00Z"));
        assertTrue(early[2] > rome[2] && late[2] < rome[2]);   // westwards
        assertTrue(early[3] < rome[3] && rome[3] < late[3]);   // presents only grow
        double[] done = Christmas.santa(utc("2026-12-25T12:30:00Z"));
        assertEquals(Christmas.DONE, done[0], 0);
        assertEquals(Christmas.PRESENTS, done[3], 0);
    }

    @Test
    public void oneTraditionForEachDay() throws Exception {
        String xml = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/res/values/strings.xml")),
                java.nio.charset.StandardCharsets.UTF_8);
        int start = xml.indexOf("<string-array name=\"xmas_advent_facts\"");
        int end = xml.indexOf("</string-array>", start);
        String array = xml.substring(start, end);
        int items = array.split("<item>", -1).length - 1;
        assertEquals(24, items);
        assertEquals(java.util.Arrays.asList("santa", "advent", "xmas_favorites"), Christmas.SCENES);
    }

    @Test
    public void santaTrack() {
        float[] t = Christmas.track();
        assertEquals(2 * (24 * 6 + 1), t.length);
        for (int i = 2; i < t.length; i += 2) assertTrue(t[i] <= t[i - 2]);
        long start = Christmas.departure(2026);
        assertEquals(0, Christmas.trackIndex(start - 1000, start));
        assertEquals(6, Christmas.trackIndex(start + Christmas.HOUR, start));
        assertEquals(24 * 6, Christmas.trackIndex(start + 3 * Christmas.DAY_MS, start));
    }
}
