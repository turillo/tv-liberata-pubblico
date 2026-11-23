package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Which archived Bing photos the wallpaper rotation uses, in plain Java so it can be unit-tested: photos are active
 * unless switched off (so new downloads are active), favourites come up about twice as often, and blacklisted photos
 * were deleted and are never downloaded again. Also keeps each photo's Bing title and description. Saved as a small
 * JSON object; a broken file reads as an empty state rather than failing.
 */
final class WallpaperState {

    final Set<String> disabled = new LinkedHashSet<>();
    final Set<String> favorites = new LinkedHashSet<>();
    final Set<String> blacklist = new LinkedHashSet<>();
    /**
     * Before 6 November 2026, the Christmas photos kept in the general archive. Only read now, so that
     * WallpaperLibrary.migrate can move them to the Christmas archive; empty afterwards.
     */
    final Set<String> christmas = new LinkedHashSet<>();
    final Map<String, String> titles = new LinkedHashMap<>();
    final Map<String, String> credits = new LinkedHashMap<>();

    boolean active(String id) {
        return !disabled.contains(id);
    }

    void setActive(String id, boolean on) {
        if (on) disabled.remove(id);
        else disabled.add(id);
    }

    void setAllActive(Collection<String> ids, boolean on) {
        for (String id : ids) setActive(id, on);
    }

    boolean favorite(String id) {
        return favorites.contains(id);
    }

    void setFavorite(String id, boolean on) {
        if (on) favorites.add(id);
        else favorites.remove(id);
    }

    /** A deleted photo: forgotten everywhere, and never downloaded again. */
    void delete(String id) {
        disabled.remove(id);
        favorites.remove(id);
        christmas.remove(id);
        titles.remove(id);
        credits.remove(id);
        blacklist.add(id);
    }

    int activeCount(Collection<String> ids) {
        int n = 0;
        for (String id : ids) if (active(id)) n++;
        return n;
    }

    /**
     * The next photo for the rotation from {@code ids} (the archive, blacklisted ones already gone or skipped here):
     * only the active ones, or all of them when none is active; never {@code current} when there is another choice;
     * favourites weigh double. Null when there is nothing to pick.
     */
    String pick(List<String> ids, String current, Random random) {
        List<String> pool = new ArrayList<>();
        for (String id : ids) if (!blacklist.contains(id) && active(id)) pool.add(id);
        if (pool.isEmpty()) for (String id : ids) if (!blacklist.contains(id)) pool.add(id);
        if (pool.size() > 1) pool.remove(current);
        if (pool.isEmpty()) return null;
        int total = 0;
        for (String id : pool) total += weight(id);
        int r = random.nextInt(total);
        for (String id : pool) {
            r -= weight(id);
            if (r < 0) return id;
        }
        return pool.get(pool.size() - 1);
    }

    private int weight(String id) {
        return favorites.contains(id) ? 2 : 1;
    }

    /**
     * The move to a separate Christmas archive: every photo in the general state's old Christmas list (except the
     * {@code stuck} ones, whose file could not be moved) leaves {@code general} for {@code xmas}, with its on or
     * off, favourite, title and credit; {@code xmas} also gets the general blacklist, so a deleted Christmas photo
     * stays deleted. Returns how many moved.
     */
    static int moveChristmas(WallpaperState general, WallpaperState xmas, Collection<String> stuck) {
        int moved = 0;
        for (String id : new ArrayList<>(general.christmas)) {
            if (stuck.contains(id)) continue;
            if (general.disabled.remove(id)) xmas.disabled.add(id);
            if (general.favorites.remove(id)) xmas.favorites.add(id);
            String t = general.titles.remove(id);
            if (t != null) xmas.titles.put(id, t);
            String cr = general.credits.remove(id);
            if (cr != null) xmas.credits.put(id, cr);
            general.christmas.remove(id);
            moved++;
        }
        xmas.blacklist.addAll(general.blacklist);
        return moved;
    }

    /** Drops what refers to photos no longer in the archive (except the blacklist, which must outlive them). */
    void forgetMissing(Collection<String> ids) {
        Set<String> keep = new LinkedHashSet<>(ids);
        disabled.retainAll(keep);
        favorites.retainAll(keep);
        christmas.retainAll(keep);
        titles.keySet().retainAll(keep);
        credits.keySet().retainAll(keep);
    }

    // ---- JSON --------------------------------------------------------------------------------------------

    String toJson() {
        StringBuilder b = new StringBuilder("{");
        list(b, "disabled", disabled).append(',');
        list(b, "favorites", favorites).append(',');
        list(b, "blacklist", blacklist).append(',');
        list(b, "christmas", christmas).append(',');
        map(b, "titles", titles).append(',');
        map(b, "credits", credits);
        return b.append('}').toString();
    }

    /** The state in {@code json}; anything unreadable is left out, never thrown. */
    static WallpaperState fromJson(String json) {
        WallpaperState s = new WallpaperState();
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) return s;
        Map<?, ?> m = (Map<?, ?>) root;
        strings(m.get("disabled"), s.disabled);
        strings(m.get("favorites"), s.favorites);
        strings(m.get("blacklist"), s.blacklist);
        strings(m.get("christmas"), s.christmas);
        pairs(m.get("titles"), s.titles);
        pairs(m.get("credits"), s.credits);
        return s;
    }

    private static void strings(Object v, Set<String> into) {
        if (!(v instanceof List)) return;
        for (Object o : (List<?>) v) if (o instanceof String) into.add((String) o);
    }

    private static void pairs(Object v, Map<String, String> into) {
        if (!(v instanceof Map)) return;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
            if (e.getKey() instanceof String && e.getValue() instanceof String) into.put((String) e.getKey(), (String) e.getValue());
        }
    }

    private static StringBuilder list(StringBuilder b, String key, Collection<String> values) {
        quote(b, key).append(":[");
        boolean first = true;
        for (String v : values) {
            if (!first) b.append(',');
            quote(b, v);
            first = false;
        }
        return b.append(']');
    }

    private static StringBuilder map(StringBuilder b, String key, Map<String, String> values) {
        quote(b, key).append(":{");
        boolean first = true;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!first) b.append(',');
            quote(b, e.getKey()).append(':');
            quote(b, e.getValue());
            first = false;
        }
        return b.append('}');
    }

    private static StringBuilder quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"');
    }
}
