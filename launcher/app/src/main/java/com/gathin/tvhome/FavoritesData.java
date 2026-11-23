package com.gathin.tvhome;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The "Preferite" screensaver scene's choices, in plain Java so they are unit-tested: which archive (the Christmas
 * one while the Christmas mode is on and has photos), which photos (the favourites; without any, the active ones,
 * and the scene says so), the next one at random without the last few, and the photo's source for its credit line.
 */
final class FavoritesData {

    /** Seconds between photos, as Syntax TV offers them; 20 by default. */
    static final int[] SECONDS = {10, 20, 30, 60};
    static final int DEFAULT_SECONDS = 20;

    private FavoritesData() {
    }

    /** The Christmas archive while the mode is on and it has photos; the general one otherwise. */
    static boolean useXmas(boolean christmasOn, int xmasPhotos) {
        return christmasOn && xmasPhotos > 0;
    }

    /** The photos to show and whether they are a fallback (no favourite: the active ones instead). */
    static final class Pool {
        final List<String> ids;
        final boolean fallback;

        Pool(List<String> ids, boolean fallback) {
            this.ids = ids;
            this.fallback = fallback;
        }
    }

    /**
     * The favourites among {@code ids} (never a deleted one); without any, the active photos; with none active
     * either, every photo. Empty only when the archive is.
     */
    static Pool pool(Collection<String> ids, WallpaperState s) {
        List<String> favourites = new ArrayList<>(), active = new ArrayList<>(), all = new ArrayList<>();
        for (String id : ids) {
            if (s.blacklist.contains(id)) continue;
            all.add(id);
            if (s.active(id)) active.add(id);
            if (s.favorite(id)) favourites.add(id);
        }
        if (!favourites.isEmpty()) return new Pool(favourites, false);
        return new Pool(active.isEmpty() ? all : active, true);
    }

    /** Remembers the last photos shown, so the next pick skips them while there are others. */
    static final class Picker {
        private final Deque<String> recent = new ArrayDeque<>();
        private final Random random;

        Picker(Random random) {
            this.random = random;
        }

        /** How many recent photos are kept out: half the pool, at most 10 (so never the whole pool). */
        static int memory(int poolSize) {
            return Math.max(0, Math.min(10, poolSize / 2));
        }

        /** The next photo from {@code pool}, or null when it is empty. */
        String next(List<String> pool) {
            if (pool.isEmpty()) return null;
            int keep = memory(pool.size());
            while (recent.size() > keep) recent.removeLast();
            List<String> fresh = new ArrayList<>();
            for (String id : pool) if (!recent.contains(id)) fresh.add(id);
            if (fresh.isEmpty()) fresh = pool;
            String id = fresh.get(random.nextInt(fresh.size()));
            recent.remove(id);
            recent.addFirst(id);
            while (recent.size() > keep) recent.removeLast();
            return id;
        }
    }

    /** The source's name from the photo's id: the TV's own (OHR., WMC., UNS.) or Home Assistant's prefixes. */
    static String source(String id) {
        if (id.startsWith("OHR.") || id.startsWith("bing-")) return "Bing";
        if (id.startsWith("WMC.") || id.startsWith("wmc-")) return "Wikimedia Commons";
        if (id.startsWith("UNS.") || id.startsWith("uns-")) return "Unsplash";
        if (id.startsWith("APOD.")) return "NASA APOD";
        return null;
    }

    /** Unsplash and Wikimedia Commons ask for credit: their line stays even with the panel switched off. */
    static boolean needsCredit(String source) {
        return "Unsplash".equals(source) || "Wikimedia Commons".equals(source);
    }

    /** Syntax TV's interval, kept to the offered values. */
    static int seconds(int saved) {
        for (int s : SECONDS) if (s == saved) return s;
        return DEFAULT_SECONDS;
    }
}
