package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The wallpaper rotation's choice with favourites, switched-off photos and the blacklist, and its JSON file. */
public class WallpaperStateTest {

    private static final List<String> IDS = Arrays.asList("OHR.A_IT-IT1", "OHR.B_IT-IT2", "OHR.C_IT-IT3", "OHR.D_IT-IT4");

    private static Map<String, Integer> tally(WallpaperState s, List<String> ids, String current, int rounds) {
        Random random = new Random(42);
        Map<String, Integer> n = new HashMap<>();
        for (int i = 0; i < rounds; i++) n.merge(s.pick(ids, current, random), 1, Integer::sum);
        return n;
    }

    @Test
    public void newPhotosAreActive() {
        WallpaperState s = new WallpaperState();
        for (String id : IDS) assertTrue(s.active(id));
        assertEquals(4, s.activeCount(IDS));
    }

    @Test
    public void onlyActivePhotosAndNeverTheCurrentOne() {
        WallpaperState s = new WallpaperState();
        s.setActive("OHR.B_IT-IT2", false);
        Map<String, Integer> n = tally(s, IDS, "OHR.A_IT-IT1", 3000);
        assertNull(n.get("OHR.B_IT-IT2"));   // switched off
        assertNull(n.get("OHR.A_IT-IT1"));   // on screen
        assertTrue(n.get("OHR.C_IT-IT3") > 1300 && n.get("OHR.D_IT-IT4") > 1300);
    }

    @Test
    public void favouritesComeUpAboutTwiceAsOften() {
        WallpaperState s = new WallpaperState();
        s.setFavorite("OHR.A_IT-IT1", true);
        Map<String, Integer> n = tally(s, IDS, null, 10000);
        double ratio = n.get("OHR.A_IT-IT1") / (double) n.get("OHR.B_IT-IT2");
        assertEquals(2.0, ratio, 0.25);
    }

    @Test
    public void noneActiveUsesThemAll() {
        WallpaperState s = new WallpaperState();
        s.setAllActive(IDS, false);
        assertEquals(0, s.activeCount(IDS));
        Map<String, Integer> n = tally(s, IDS, null, 2000);
        assertEquals(4, n.size());
    }

    @Test
    public void blacklistedPhotosNeverShow() {
        WallpaperState s = new WallpaperState();
        s.setFavorite("OHR.C_IT-IT3", true);
        s.titles.put("OHR.C_IT-IT3", "Lago");
        s.delete("OHR.C_IT-IT3");
        assertTrue(s.blacklist.contains("OHR.C_IT-IT3"));
        assertFalse(s.favorite("OHR.C_IT-IT3"));
        assertNull(s.titles.get("OHR.C_IT-IT3"));
        // Even if the file were still listed, and even with every other photo switched off.
        s.setAllActive(Arrays.asList("OHR.A_IT-IT1", "OHR.B_IT-IT2", "OHR.D_IT-IT4"), false);
        assertNull(tally(s, IDS, null, 2000).get("OHR.C_IT-IT3"));
    }

    @Test
    public void christmasPhotosMoveToTheirOwnState() {
        // As saved before 6 November 2026: two Christmas photos in the general state, one off, one a favourite.
        WallpaperState general = new WallpaperState();
        general.christmas.add("WMC.Lights");
        general.christmas.add("UNS.Market");
        general.christmas.add("UNS.Stuck");
        general.setActive("WMC.Lights", false);
        general.setFavorite("UNS.Market", true);
        general.titles.put("UNS.Market", "Mercatino");
        general.credits.put("UNS.Market", "Photo by someone on Unsplash");
        general.setFavorite("OHR.A_IT-IT1", true);
        general.blacklist.add("UNS.DeletedTree");
        WallpaperState back = WallpaperState.fromJson(general.toJson());   // it survives a save
        WallpaperState xmas = new WallpaperState();

        assertEquals(2, WallpaperState.moveChristmas(back, xmas, Collections.singletonList("UNS.Stuck")));
        assertFalse(xmas.active("WMC.Lights"));
        assertTrue(xmas.favorite("UNS.Market"));
        assertEquals("Mercatino", xmas.titles.get("UNS.Market"));
        assertEquals("Photo by someone on Unsplash", xmas.credits.get("UNS.Market"));
        assertTrue(xmas.blacklist.contains("UNS.DeletedTree"));   // deleted stays deleted
        // The general state keeps only its own photos, and the one whose file did not move, for next time.
        assertTrue(back.active("WMC.Lights"));
        assertFalse(back.favorite("UNS.Market"));
        assertFalse(back.titles.containsKey("UNS.Market"));
        assertTrue(back.favorite("OHR.A_IT-IT1"));
        assertEquals(Collections.singleton("UNS.Stuck"), back.christmas);
        // Moving again does nothing more.
        assertEquals(1, WallpaperState.moveChristmas(back, xmas, Collections.<String>emptyList()));
        assertEquals(0, WallpaperState.moveChristmas(back, xmas, Collections.<String>emptyList()));
        assertTrue(back.christmas.isEmpty());
    }

    @Test
    public void edgeCases() {
        WallpaperState s = new WallpaperState();
        Random r = new Random(1);
        assertNull(s.pick(Collections.<String>emptyList(), null, r));
        assertEquals("OHR.A_IT-IT1", s.pick(Collections.singletonList("OHR.A_IT-IT1"), "OHR.A_IT-IT1", r));
        s.delete("OHR.A_IT-IT1");
        assertNull(s.pick(Collections.singletonList("OHR.A_IT-IT1"), null, r));
    }

    @Test
    public void jsonRoundTrip() {
        WallpaperState s = new WallpaperState();
        s.setActive("OHR.B_IT-IT2", false);
        s.setFavorite("OHR.A_IT-IT1", true);
        s.delete("OHR.Z_IT-IT9");
        s.titles.put("OHR.A_IT-IT1", "Il \"lago\" di Braies\nAlto Adige \\ Italia");
        s.credits.put("OHR.A_IT-IT1", "© Bing, tab\there");
        WallpaperState back = WallpaperState.fromJson(s.toJson());
        assertEquals(s.disabled, back.disabled);
        assertEquals(s.favorites, back.favorites);
        assertEquals(s.blacklist, back.blacklist);
        assertEquals(s.titles, back.titles);
        assertEquals(s.credits, back.credits);
        assertEquals(s.toJson(), back.toJson());
    }

    @Test
    public void brokenJsonIsAnEmptyState() {
        for (String broken : new String[]{null, "", "{", "[1,2]", "{\"disabled\":[\"a\",", "nonsense",
                "{\"disabled\":5,\"favorites\":{\"x\":1},\"titles\":[\"y\"]}"}) {
            WallpaperState s = WallpaperState.fromJson(broken);
            assertTrue(broken, s.disabled.isEmpty() && s.favorites.isEmpty() && s.titles.isEmpty());
        }
        // Unknown keys and odd values are skipped, the rest kept.
        WallpaperState s = WallpaperState.fromJson("{\"version\":2,\"ok\":true,\"blacklist\":[\"x\",3,null,\"y\"],"
                + "\"titles\":{\"x\":\"T\\u00e8\",\"n\":1}}");
        assertEquals(Arrays.asList("x", "y"), new java.util.ArrayList<>(s.blacklist));
        assertEquals("Tè", s.titles.get("x"));
        assertEquals(1, s.titles.size());
    }

    @Test
    public void forgetMissingKeepsTheBlacklist() {
        WallpaperState s = new WallpaperState();
        s.setActive("gone", false);
        s.setFavorite("gone", true);
        s.blacklist.add("deleted");
        s.forgetMissing(IDS);
        assertTrue(s.disabled.isEmpty());
        assertTrue(s.favorites.isEmpty());
        assertTrue(s.blacklist.contains("deleted"));
    }

    @Test
    public void readableNames() {
        assertEquals("Lago Di Braies", WallpaperLibrary.fallbackTitle("OHR.LagoDiBraies_IT-IT1234567890"));
        assertNotEquals("", WallpaperLibrary.fallbackTitle("photo"));
    }
}
