package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ArchiveCleanupTest {

    private static WallpaperState state() {
        WallpaperState s = new WallpaperState();
        s.setActive("b", false);
        s.setActive("c", false);
        s.setFavorite("c", true);   // off but a favourite: kept
        s.setFavorite("e", true);
        return s;
    }

    @Test
    public void inactiveSkipsFavourites() {
        assertEquals(Collections.singletonList("b"), ArchiveCleanup.inactive(Arrays.asList("a", "b", "c", "d"), state()));
        assertTrue(ArchiveCleanup.inactive(Arrays.asList("a", "d"), state()).isEmpty());
    }

    @Test
    public void beyondNewestKeepsTheNewestAndEveryFavourite() {
        List<String> newestFirst = Arrays.asList("a", "b", "c", "d", "e", "f");
        assertEquals(Arrays.asList("d", "f"), ArchiveCleanup.beyondNewest(newestFirst, 2, state()));
        assertTrue(ArchiveCleanup.beyondNewest(newestFirst, 6, state()).isEmpty());
        assertTrue(ArchiveCleanup.beyondNewest(newestFirst, 100, state()).isEmpty());
        assertEquals(Arrays.asList("a", "b", "d", "f"), ArchiveCleanup.beyondNewest(newestFirst, 0, state()));
    }
}
