package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class FavoritesDataTest {

    private static final List<String> IDS = Arrays.asList("a", "b", "c", "d", "gone");

    @Test
    public void favouritesFirstNeverDeleted() {
        WallpaperState s = new WallpaperState();
        s.setFavorite("b", true);
        s.setFavorite("d", true);
        s.delete("gone");
        FavoritesData.Pool p = FavoritesData.pool(IDS, s);
        assertEquals(Arrays.asList("b", "d"), p.ids);
        assertFalse(p.fallback);
    }

    @Test
    public void withoutFavouritesTheActiveOnesWithANote() {
        WallpaperState s = new WallpaperState();
        s.setActive("a", false);
        s.blacklist.add("gone");
        FavoritesData.Pool p = FavoritesData.pool(IDS, s);
        assertEquals(Arrays.asList("b", "c", "d"), p.ids);
        assertTrue(p.fallback);
        // none active either: every photo that is not deleted
        s.setAllActive(IDS, false);
        assertEquals(Arrays.asList("a", "b", "c", "d"), FavoritesData.pool(IDS, s).ids);
        assertTrue(FavoritesData.pool(new ArrayList<>(), s).ids.isEmpty());
    }

    @Test
    public void christmasModeUsesTheChristmasArchiveWhenItHasPhotos() {
        assertTrue(FavoritesData.useXmas(true, 3));
        assertFalse(FavoritesData.useXmas(true, 0));
        assertFalse(FavoritesData.useXmas(false, 3));
    }

    @Test
    public void randomWithoutTheLastFew() {
        FavoritesData.Picker picker = new FavoritesData.Picker(new Random(7));
        List<String> pool = Arrays.asList("1", "2", "3", "4", "5", "6");
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < 60; i++) shown.add(picker.next(pool));
        int keep = FavoritesData.Picker.memory(pool.size());
        assertEquals(3, keep);
        for (int i = 0; i < shown.size(); i++) {
            Set<String> window = new HashSet<>(shown.subList(Math.max(0, i - keep), i + 1));
            assertEquals("repeat at " + i, Math.min(i, keep) + 1, window.size());
        }
        assertEquals(6, new HashSet<>(shown).size());
    }

    @Test
    public void smallPools() {
        FavoritesData.Picker picker = new FavoritesData.Picker(new Random(1));
        assertNull(picker.next(new ArrayList<>()));
        assertEquals("x", picker.next(Arrays.asList("x")));
        assertEquals("x", picker.next(Arrays.asList("x")));
        List<String> two = Arrays.asList("x", "y");
        String first = picker.next(two);
        for (int i = 0; i < 10; i++) {
            String n = picker.next(two);
            assertNotEquals(first, n);
            first = n;
        }
    }

    @Test
    public void sourcesIntervalsAndCredit() {
        assertEquals("Unsplash", FavoritesData.source("uns-abc"));
        assertEquals("Unsplash", FavoritesData.source("UNS.abc"));
        assertEquals("Wikimedia Commons", FavoritesData.source("wmc-1"));
        assertEquals("Bing", FavoritesData.source("OHR.Lago_IT-IT1"));
        assertNull(FavoritesData.source("up-123"));
        assertTrue(FavoritesData.needsCredit("Unsplash"));
        assertFalse(FavoritesData.needsCredit("Bing"));
        assertEquals(20, FavoritesData.seconds(15));
        assertEquals(60, FavoritesData.seconds(60));
    }
}
