package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Which archived wallpapers the Sistema page's cleanup may delete. Favourites are never on either list. Plain
 * logic, for the JVM tests.
 */
final class ArchiveCleanup {

    /** The "keep only the newest" choices. */
    static final int[] KEEP = {100, 200, 500};

    private ArchiveCleanup() {
    }

    /** Photos switched off in Syntax TV and not favourites. */
    static List<String> inactive(Collection<String> ids, WallpaperState state) {
        List<String> out = new ArrayList<>();
        for (String id : ids) if (!state.active(id) && !state.favorite(id)) out.add(id);
        return out;
    }

    /**
     * Photos beyond the newest {@code keep} ({@code newestFirst} in that order), favourites excepted: they stay
     * whatever their age, on top of the {@code keep} newest.
     */
    static List<String> beyondNewest(List<String> newestFirst, int keep, WallpaperState state) {
        List<String> out = new ArrayList<>();
        if (keep < 0) return out;
        for (int i = keep; i < newestFirst.size(); i++) {
            String id = newestFirst.get(i);
            if (!state.favorite(id)) out.add(id);
        }
        return out;
    }
}
