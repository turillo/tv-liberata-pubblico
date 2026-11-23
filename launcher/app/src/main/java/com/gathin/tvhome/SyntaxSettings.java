package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The settings Syntax TV changes on the TV, read at run time by the screensaver, its scenes, the news ticker and the
 * wallpaper rotation, so nothing needs a rebuild. Plain Java over a small key-value {@link Store} (SharedPreferences
 * on the TV, a map in the tests); a missing or broken value falls back to the build's default (config.xml or the
 * theme), passed in as {@link Defaults}.
 */
final class SyntaxSettings {

    /** Where the values live. */
    interface Store {
        String get(String key);

        void put(String key, String value);
    }

    /** The build's defaults: config.xml or the theme's config.json. */
    static final class Defaults {
        final String dreamScene;      // config dream_scene: a scene, or random
        final boolean ticker;         // config news_ticker
        final int tickerSpeedDp;      // config news_ticker_speed
        final int wallpaperMinutes;   // the launcher's online_wallpaper_minutes

        Defaults(String dreamScene, boolean ticker, int tickerSpeedDp, int wallpaperMinutes) {
            this.dreamScene = dreamScene;
            this.ticker = ticker;
            this.tickerSpeedDp = tickerSpeedDp;
            this.wallpaperMinutes = wallpaperMinutes;
        }
    }

    /** Every scene the screensaver can show, in the order Syntax TV lists them. */
    /** Every scene the code has, in the order of R.array.syntax_scene_names. */
    static final List<String> ALL_SCENES = Collections.unmodifiableList(Arrays.asList(Config.SCENE_CLOCK, Config.SCENE_ISS,
            Config.SCENE_SUN, Config.SCENE_EARTH, Config.SCENE_MOON, Config.SCENE_SPACE, Config.SCENE_LIVE,
            Config.SCENE_FAVORITES));
    static final List<String> SCENES = Collections.unmodifiableList(Features.SPACE_SCENES
            ? Arrays.asList(Config.SCENE_CLOCK, Config.SCENE_ISS, Config.SCENE_SUN, Config.SCENE_EARTH, Config.SCENE_MOON,
            Config.SCENE_SPACE, Config.SCENE_LIVE, Config.SCENE_FAVORITES)
            : Arrays.asList(Config.SCENE_CLOCK, Config.SCENE_FAVORITES));
    /** What dream_scene "random" meant before the scenes could be picked one by one. */
    static final List<String> RANDOM_DEFAULT = Collections.unmodifiableList(Arrays.asList(Config.SCENE_CLOCK,
            Config.SCENE_ISS, Config.SCENE_MOON, Config.SCENE_SPACE, Config.SCENE_LIVE));

    static final String SLOW = "slow", MEDIUM = "medium", FAST = "fast";
    static final String SMALL = "small", LARGE = "large";
    /** Each archived photo stays 15 minutes, 30 minutes or an hour (the default); a longer saved value is an hour. */
    static final int[] WALLPAPER_MINUTES = {15, 30, 60};
    static final int WALLPAPER_DEFAULT = 60;

    static final String KEY_HOME_TICKER = "home_ticker";
    static final String KEY_SCENES = "scenes", KEY_TICKER = "ticker", KEY_SPEED = "ticker_speed",
            KEY_SIZE = "ticker_size", KEY_SECTIONS = "news_sections", KEY_WALLPAPER = "wallpaper_minutes";

    private final Store store;
    private final Defaults defaults;

    SyntaxSettings(Store store, Defaults defaults) {
        this.store = store;
        this.defaults = defaults;
    }

    // ---- profile ---------------------------------------------------------------------------------------------

    /** Which room this TV is in: living room (the default), kitchen, hobby room or attic. Saved on the TV only. */
    static final List<String> PROFILES = Collections.unmodifiableList(Arrays.asList("living", "kitchen", "hobby",
            "attic"));
    static final String KEY_PROFILE = "profile";

    String profile() {
        String v = store.get(KEY_PROFILE);
        return PROFILES.contains(v) ? v : PROFILES.get(0);
    }

    /** False for a name that is not a profile; nothing is saved then. */
    boolean setProfile(String profile) {
        if (!PROFILES.contains(profile)) return false;
        store.put(KEY_PROFILE, profile);
        return true;
    }

    /** The kitchen: Home turns into the information panel (KitchenPanelActivity) when left alone. */
    boolean kitchen() {
        return "kitchen".equals(profile());
    }

    // ---- apps row position -------------------------------------------------------------------------------

    /** Where Home's dock row sits: low as in tvOS (the default, as before), in the middle, or right under the header. */
    static final List<String> APPS_POSITIONS = Collections.unmodifiableList(Arrays.asList("bottom", "center", "top"));
    static final String KEY_APPS_POSITION = "apps_position";

    String appsPosition() {
        String v = store.get(KEY_APPS_POSITION);
        return APPS_POSITIONS.contains(v) ? v : APPS_POSITIONS.get(0);
    }

    boolean setAppsPosition(String position) {
        if (!APPS_POSITIONS.contains(position)) return false;
        store.put(KEY_APPS_POSITION, position);
        return true;
    }

    // ---- kitchen panel -----------------------------------------------------------------------------------

    static final String KEY_KITCHEN_SCREENSAVER = "kitchen_screensaver", KEY_KITCHEN_RECYCLING = "kitchen_recycling";

    /** Off by default: the kitchen panel keeps the screen on, so no screensaver starts over it. */
    boolean kitchenScreensaver() {
        return Boolean.parseBoolean(store.get(KEY_KITCHEN_SCREENSAVER));
    }

    void setKitchenScreensaver(boolean on) {
        store.put(KEY_KITCHEN_SCREENSAVER, String.valueOf(on));
    }

    // Night on the kitchen panel: the screen almost black, only a faint clock. Minutes after midnight; -1 is off.
    static final String KEY_KITCHEN_NIGHT_START = "kitchen_night_start", KEY_KITCHEN_NIGHT_END = "kitchen_night_end";
    static final int NIGHT_START = 23 * 60, NIGHT_END = 6 * 60 + 30;
    /** The choices Syntax TV cycles through; -1 (off) only for the start. */
    static final int[] NIGHT_STARTS = {21 * 60, 22 * 60, 22 * 60 + 30, 23 * 60, 23 * 60 + 30, 0, -1};
    static final int[] NIGHT_ENDS = {5 * 60 + 30, 6 * 60, 6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60};

    int kitchenNightStart() {
        return minutes(store.get(KEY_KITCHEN_NIGHT_START), NIGHT_START, true);
    }

    int kitchenNightEnd() {
        return minutes(store.get(KEY_KITCHEN_NIGHT_END), NIGHT_END, false);
    }

    void setKitchenNight(int start, int end) {
        store.put(KEY_KITCHEN_NIGHT_START, String.valueOf(minutes(String.valueOf(start), NIGHT_START, true)));
        store.put(KEY_KITCHEN_NIGHT_END, String.valueOf(minutes(String.valueOf(end), NIGHT_END, false)));
    }

    private static int minutes(String v, int fallback, boolean offAllowed) {
        try {
            int m = Integer.parseInt(v);
            if (m == -1 && offAllowed) return -1;
            return m >= 0 && m < 24 * 60 ? m : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** True at {@code minute} (after midnight) inside [start, end), across midnight too; never with start -1. */
    static boolean isNight(int minute, int start, int end) {
        if (start < 0 || start == end) return false;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    /** The next value of {@code options} after {@code current}, wrapping; the first when current is not there. */
    static int next(int[] options, int current) {
        for (int i = 0; i < options.length; i++) if (options[i] == current) return options[(i + 1) % options.length];
        return options[0];
    }

    /** The recycling box on the kitchen panel and on Home: off until Syntax TV turns it on. */
    boolean kitchenRecycling() {
        return Boolean.parseBoolean(store.get(KEY_KITCHEN_RECYCLING));
    }

    void setKitchenRecycling(boolean on) {
        store.put(KEY_KITCHEN_RECYCLING, String.valueOf(on));
    }

    static final String KEY_RECYCLING_WEEK = "recycling_week", KEY_RECYCLING_FROM = "recycling_from",
            KEY_RECYCLING_TO = "recycling_to", KEY_RECYCLING_BY = "recycling_by";

    /** The recycling week (Recycling's format); Rome's AMA door-to-door calendar until Syntax TV changes it. */
    String recyclingWeek() {
        String v = store.get(KEY_RECYCLING_WEEK);
        return v == null ? Recycling.DEFAULT : Recycling.format(Recycling.parse(v));
    }

    void setRecyclingWeek(String week) {
        store.put(KEY_RECYCLING_WEEK, Recycling.format(Recycling.parse(week)));
    }

    int recyclingFrom() {
        return choice(store.get(KEY_RECYCLING_FROM), Recycling.FROMS, Recycling.FROM);
    }

    int recyclingTo() {
        return choice(store.get(KEY_RECYCLING_TO), Recycling.TOS, Recycling.TO);
    }

    int recyclingBy() {
        return choice(store.get(KEY_RECYCLING_BY), Recycling.BYS, Recycling.BY);
    }

    void setRecyclingHours(int from, int to, int by) {
        store.put(KEY_RECYCLING_FROM, String.valueOf(choice(String.valueOf(from), Recycling.FROMS, Recycling.FROM)));
        store.put(KEY_RECYCLING_TO, String.valueOf(choice(String.valueOf(to), Recycling.TOS, Recycling.TO)));
        store.put(KEY_RECYCLING_BY, String.valueOf(choice(String.valueOf(by), Recycling.BYS, Recycling.BY)));
    }

    private static int choice(String v, int[] options, int fallback) {
        try {
            int m = Integer.parseInt(v);
            for (int o : options) if (o == m) return m;
        } catch (NumberFormatException ignored) {
            // fallback below
        }
        return fallback;
    }

    // ---- screen margins ----------------------------------------------------------------------------------

    static final String KEY_SCREEN_MARGIN = "screen_margin";
    static final int MARGIN_MAX = 5;

    /**
     * The share of the screen (0-5 %) kept clear on each side, for a TV that crops the edges (overscan). Saved on
     * this TV only; {@code fallback} until Syntax TV sets it (ScreenMargin: 5 on a Fire TV, 0 elsewhere).
     */
    int screenMargin(int fallback) {
        try {
            int m = Integer.parseInt(store.get(KEY_SCREEN_MARGIN));
            if (m >= 0 && m <= MARGIN_MAX) return m;
        } catch (NumberFormatException ignored) {
            // not set yet
        }
        return Math.max(0, Math.min(MARGIN_MAX, fallback));
    }

    void setScreenMargin(int percent) {
        store.put(KEY_SCREEN_MARGIN, String.valueOf(Math.max(0, Math.min(MARGIN_MAX, percent))));
    }

    // ---- screensaver scenes ------------------------------------------------------------------------------

    /** The scenes ticked for the screensaver, in list order; never empty. */
    List<String> scenes() {
        List<String> chosen = known(store.get(KEY_SCENES), SCENES);
        if (!chosen.isEmpty()) return chosen;
        String d = Config.dreamScene(defaults.dreamScene);
        if (!Features.SPACE_SCENES) return Collections.singletonList(SCENES.contains(d) ? d : Config.SCENE_CLOCK);
        return Config.SCENE_RANDOM.equals(d) ? RANDOM_DEFAULT : Collections.singletonList(d);
    }

    /**
     * Ticks or unticks a scene. The last ticked scene cannot be unticked: returns false then (and on an unknown
     * scene), true when the change was saved.
     */
    boolean setScene(String scene, boolean on) {
        if (!SCENES.contains(scene)) return false;
        Set<String> set = new LinkedHashSet<>(scenes());
        if (on) set.add(scene);
        else if (set.size() <= 1 && set.contains(scene)) return false;
        else set.remove(scene);
        List<String> ordered = new ArrayList<>();
        for (String s : SCENES) if (set.contains(s)) ordered.add(s);
        store.put(KEY_SCENES, String.join(",", ordered));
        return true;
    }

    // ---- news ticker -------------------------------------------------------------------------------------

    boolean ticker() {
        if (!Features.NEWS_TICKER) return false;
        String v = store.get(KEY_TICKER);
        return v == null ? defaults.ticker : Boolean.parseBoolean(v);
    }

    void setTicker(boolean on) {
        store.put(KEY_TICKER, String.valueOf(on));
    }

    /** The ticker along the bottom of Home, under the dock: on by default, separate from the screensaver's. */
    boolean homeTicker() {
        if (!Features.NEWS_TICKER) return false;
        String v = store.get(KEY_HOME_TICKER);
        return v == null || Boolean.parseBoolean(v);
    }

    void setHomeTicker(boolean on) {
        store.put(KEY_HOME_TICKER, String.valueOf(on));
    }

    static final String KEY_HOME_IDLE_CLOCK = "home_idle_clock";

    /**
     * Home's own clock after home_idle_clock_minutes without keys (theme or config.xml): on by default, so the
     * theme decides; off here keeps it from ever showing, without touching the theme.
     */
    boolean homeIdleClock() {
        String v = store.get(KEY_HOME_IDLE_CLOCK);
        return v == null || Boolean.parseBoolean(v);
    }

    void setHomeIdleClock(boolean on) {
        store.put(KEY_HOME_IDLE_CLOCK, String.valueOf(on));
    }

    static final String KEY_SYSTEM_ALERTS = "system_alerts";

    /** Sistema's "Avvisi di sistema": the small alert on Home; on by default. */
    boolean systemAlerts() {
        String v = store.get(KEY_SYSTEM_ALERTS);
        return v == null || Boolean.parseBoolean(v);
    }

    void setSystemAlerts(boolean on) {
        store.put(KEY_SYSTEM_ALERTS, String.valueOf(on));
    }

    /**
     * What Home's band depends on: on/off, speed, size and sections. Home rebuilds the band only when this changes,
     * and "" means no band.
     */
    String homeTickerKey() {
        if (!homeTicker()) return "";
        return tickerSpeedDp() + "|" + tickerSize() + "|" + String.join(",", sections());
    }

    /** slow, medium or fast; the default is the one nearest config news_ticker_speed. */
    String tickerSpeed() {
        String v = store.get(KEY_SPEED);
        if (SLOW.equals(v) || MEDIUM.equals(v) || FAST.equals(v)) return v;
        int dp = defaults.tickerSpeedDp;
        return dp < 58 ? SLOW : dp > 88 ? FAST : MEDIUM;
    }

    void setTickerSpeed(String speed) {
        if (SLOW.equals(speed) || MEDIUM.equals(speed) || FAST.equals(speed)) store.put(KEY_SPEED, speed);
    }

    /** The ticker's speed in dp per second: config news_ticker_speed until a choice is made on the TV. */
    int tickerSpeedDp() {
        String v = store.get(KEY_SPEED);
        if (v == null) return defaults.tickerSpeedDp;
        return speedDp(tickerSpeed());
    }

    static int speedDp(String speed) {
        return SLOW.equals(speed) ? 45 : FAST.equals(speed) ? 105 : 70;
    }

    /** small, medium or large; medium is the 6% band from before. */
    String tickerSize() {
        String v = store.get(KEY_SIZE);
        return SMALL.equals(v) || LARGE.equals(v) ? v : MEDIUM;
    }

    void setTickerSize(String size) {
        if (SMALL.equals(size) || MEDIUM.equals(size) || LARGE.equals(size)) store.put(KEY_SIZE, size);
    }

    /** The band's height as a fraction of the screen height. */
    static float bandFraction(String size) {
        return SMALL.equals(size) ? 0.05f : LARGE.equals(size) ? 0.075f : 0.06f;
    }

    /** The ticked ANSA sections, as keys of {@link NewsData#SECTIONS}; Ultima ora only by default. */
    List<String> sections() {
        List<String> chosen = known(store.get(KEY_SECTIONS), NewsData.sectionKeys());
        return chosen.isEmpty() ? Collections.singletonList(NewsData.TOP) : chosen;
    }

    /** Ticks or unticks a section; the last one cannot be unticked (false then). */
    boolean setSection(String section, boolean on) {
        List<String> keys = NewsData.sectionKeys();
        if (!keys.contains(section)) return false;
        Set<String> set = new LinkedHashSet<>(sections());
        if (on) set.add(section);
        else if (set.size() <= 1 && set.contains(section)) return false;
        else set.remove(section);
        List<String> ordered = new ArrayList<>();
        for (String s : keys) if (set.contains(s)) ordered.add(s);
        store.put(KEY_SECTIONS, String.join(",", ordered));
        return true;
    }

    // ---- wallpaper ---------------------------------------------------------------------------------------

    /** How long each general photo stays, in minutes: one of WALLPAPER_MINUTES (1 hour by default). */
    int wallpaperMinutes() {
        return minutes(store.get(KEY_WALLPAPER));
    }

    static final String KEY_WALLPAPER_ON_WAKE = "wallpaper_on_wake";

    /** "Nuovo sfondo a ogni accensione" (general and Christmas photos): on by default. */
    boolean wallpaperOnWake() {
        String v = store.get(KEY_WALLPAPER_ON_WAKE);
        return v == null || Boolean.parseBoolean(v);
    }

    void setWallpaperOnWake(boolean on) {
        store.put(KEY_WALLPAPER_ON_WAKE, String.valueOf(on));
    }

    void setWallpaperMinutes(int minutes) {
        for (int ok : WALLPAPER_MINUTES) if (minutes == ok) store.put(KEY_WALLPAPER, String.valueOf(minutes));
    }

    /** How long each Christmas photo stays, in minutes, set apart in the Natale menu (1 hour by default). */
    int xmasWallpaperMinutes() {
        return minutes(store.get(KEY_XMAS_WALLPAPER));
    }

    void setXmasWallpaperMinutes(int minutes) {
        for (int ok : WALLPAPER_MINUTES) if (minutes == ok) store.put(KEY_XMAS_WALLPAPER, String.valueOf(minutes));
    }

    /** A saved interval: one of WALLPAPER_MINUTES as it is; longer ones (3 h, 6 h, 1 day before) an hour. */
    private static int minutes(String saved) {
        int m = number(saved, -1);
        for (int ok : WALLPAPER_MINUTES) if (m == ok) return m;
        return WALLPAPER_DEFAULT;
    }

    // ---- wallpaper sources ---------------------------------------------------------------------------------

    static final String KEY_SOURCES = "wallpaper_sources", KEY_THEMES = "wallpaper_themes", KEY_CAP = "wallpaper_cap";

    /** The ticked wallpaper sources, in list order; Bing only by default. Bing can be unticked like the others. */
    List<String> sources() {
        List<String> chosen = known(store.get(KEY_SOURCES), WallpaperSources.ALL);
        return chosen.isEmpty() && store.get(KEY_SOURCES) == null ? Collections.singletonList(WallpaperSources.BING) : chosen;
    }

    void setSource(String source, boolean on) {
        if (!WallpaperSources.ALL.contains(source)) return;
        Set<String> set = new LinkedHashSet<>(sources());
        if (on) set.add(source);
        else set.remove(source);
        List<String> ordered = new ArrayList<>();
        for (String x : WallpaperSources.ALL) if (set.contains(x)) ordered.add(x);
        store.put(KEY_SOURCES, ordered.isEmpty() ? "none" : String.join(",", ordered));
    }

    /** New photos a day from a source, 1 to 3 (1 by default); Bing always 1, its own photo of the day. */
    int perDay(String source) {
        if (WallpaperSources.BING.equals(source)) return 1;
        int n = number(store.get("per_day_" + source), 1);
        return n < 1 || n > 3 ? 1 : n;
    }

    void setPerDay(String source, int n) {
        if (WallpaperSources.ALL.contains(source) && !WallpaperSources.BING.equals(source) && n >= 1 && n <= 3) {
            store.put("per_day_" + source, String.valueOf(n));
        }
    }

    /** Unsplash themes, ticked; nature by default; one always stays. */
    List<String> themes() {
        List<String> chosen = known(store.get(KEY_THEMES), WallpaperSources.THEMES);
        return chosen.isEmpty() ? Collections.singletonList(WallpaperSources.NATURE) : chosen;
    }

    boolean setTheme(String theme, boolean on) {
        if (!WallpaperSources.THEMES.contains(theme)) return false;
        Set<String> set = new LinkedHashSet<>(themes());
        if (on) set.add(theme);
        else if (set.size() <= 1 && set.contains(theme)) return false;
        else set.remove(theme);
        List<String> ordered = new ArrayList<>();
        for (String x : WallpaperSources.THEMES) if (set.contains(x)) ordered.add(x);
        store.put(KEY_THEMES, String.join(",", ordered));
        return true;
    }

    /** The archive's cap: 200, 500 (default) or 1000 photos. */
    int archiveCap() {
        int n = number(store.get(KEY_CAP), WallpaperSources.DEFAULT_CAP);
        for (int ok : WallpaperSources.CAPS) if (n == ok) return n;
        return WallpaperSources.DEFAULT_CAP;
    }

    void setArchiveCap(int n) {
        for (int ok : WallpaperSources.CAPS) if (n == ok) store.put(KEY_CAP, String.valueOf(n));
    }

    // ---- the "Preferite" scene ------------------------------------------------------------------------------

    static final String KEY_FAV_SECONDS = "favorites_seconds", KEY_FAV_PANEL = "favorites_panel";

    /** Seconds between photos: 10, 20 (default), 30 or 60. */
    int favoritesSeconds() {
        return FavoritesData.seconds(number(store.get(KEY_FAV_SECONDS), FavoritesData.DEFAULT_SECONDS));
    }

    void setFavoritesSeconds(int seconds) {
        if (FavoritesData.seconds(seconds) == seconds) store.put(KEY_FAV_SECONDS, String.valueOf(seconds));
    }

    /** The panel with title, credit and source: on by default. */
    boolean favoritesPanel() {
        String v = store.get(KEY_FAV_PANEL);
        return v == null || Boolean.parseBoolean(v);
    }

    void setFavoritesPanel(boolean on) {
        store.put(KEY_FAV_PANEL, String.valueOf(on));
    }

    // ---- wallpapers shared between the TVs (ArchiveShare) ----------------------------------------------------

    static final String KEY_SHARE = "wallpaper_share", KEY_SYNC_FROM = "wallpaper_sync_from";

    /** This TV serves its wallpapers to the others on the home network: off by default. */
    boolean shareWallpapers() {
        return Boolean.parseBoolean(store.get(KEY_SHARE));
    }

    void setShareWallpapers(boolean on) {
        store.put(KEY_SHARE, String.valueOf(on));
    }

    /** The TV this one copies its wallpapers from (its network name), or null: download them itself. */
    String syncFrom() {
        String v = store.get(KEY_SYNC_FROM);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    void setSyncFrom(String name) {
        store.put(KEY_SYNC_FROM, name == null ? "" : name.trim());
    }

    // ---- Christmas mode ------------------------------------------------------------------------------------

    static final String KEY_XMAS = "xmas", XMAS_WALLPAPERS = "xmas_wallpapers", XMAS_SNOW_HOME = "xmas_snow_home",
            XMAS_COUNTDOWN = "xmas_countdown", XMAS_DOCK = "xmas_dock", XMAS_SNOW_DREAM = "xmas_snow_dream",
            KEY_XMAS_SCENES = "xmas_scenes", KEY_XMAS_ONLY = "xmas_only", KEY_XMAS_WALLPAPER = "xmas_wallpaper_minutes",
            KEY_XMAS_SOURCES = "xmas_sources", KEY_XMAS_THEMES = "xmas_themes";

    /** The Christmas wallpapers' sources: Wikimedia and Unsplash. */
    static final List<String> XMAS_SOURCES = Collections.unmodifiableList(Edition.unsplash()
            ? Arrays.asList(WallpaperSources.WIKIMEDIA, WallpaperSources.UNSPLASH)
            : Collections.singletonList(WallpaperSources.WIKIMEDIA));

    List<String> xmasSources() {
        String v = store.get(KEY_XMAS_SOURCES);
        if (v == null) return XMAS_SOURCES;
        return known(v, XMAS_SOURCES);
    }

    void setXmasSource(String source, boolean on) {
        if (!XMAS_SOURCES.contains(source)) return;
        Set<String> set = new LinkedHashSet<>(xmasSources());
        if (on) set.add(source);
        else set.remove(source);
        List<String> ordered = new ArrayList<>();
        for (String x : XMAS_SOURCES) if (set.contains(x)) ordered.add(x);
        store.put(KEY_XMAS_SOURCES, ordered.isEmpty() ? "none" : String.join(",", ordered));
    }

    /** New Christmas photos a day from a source, 1 to 3; until set, the general setting for that source. */
    int xmasPerDay(String source) {
        int n = number(store.get("xmas_per_day_" + source), -1);
        return n >= 1 && n <= 3 ? n : perDay(source);
    }

    void setXmasPerDay(String source, int n) {
        if (XMAS_SOURCES.contains(source) && n >= 1 && n <= 3) store.put("xmas_per_day_" + source, String.valueOf(n));
    }

    /** The Christmas themes ticked: all by default; one always stays. */
    List<String> xmasThemes() {
        List<String> chosen = known(store.get(KEY_XMAS_THEMES), Christmas.THEMES);
        return chosen.isEmpty() ? Christmas.THEMES : chosen;
    }

    boolean setXmasTheme(String theme, boolean on) {
        if (!Christmas.THEMES.contains(theme)) return false;
        Set<String> set = new LinkedHashSet<>(xmasThemes());
        if (on) set.add(theme);
        else if (set.size() <= 1 && set.contains(theme)) return false;
        else set.remove(theme);
        List<String> ordered = new ArrayList<>();
        for (String x : Christmas.THEMES) if (set.contains(x)) ordered.add(x);
        store.put(KEY_XMAS_THEMES, String.join(",", ordered));
        return true;
    }

    /** The master switch: off by default, only ever turned on by hand. */
    boolean christmas() {
        return Features.CHRISTMAS && Boolean.parseBoolean(store.get(KEY_XMAS));
    }

    void setChristmas(boolean on) {
        store.put(KEY_XMAS, String.valueOf(on));
    }

    /** An element's own switch (on by default, so the master turns everything on). */
    boolean xmasSwitch(String key) {
        String v = store.get(key);
        return v == null || Boolean.parseBoolean(v);
    }

    void setXmasSwitch(String key, boolean on) {
        store.put(key, String.valueOf(on));
    }

    /** Whether an element is in effect: the master and its own switch. With the master off, nothing changes. */
    boolean xmas(String key) {
        return christmas() && xmasSwitch(key);
    }

    /** The Christmas scenes ticked (all five by default); may be empty, which leaves the rotation as it was. */
    List<String> xmasScenes() {
        String v = store.get(KEY_XMAS_SCENES);
        if (v == null) return Christmas.SCENES;
        return known(v, Christmas.SCENES);
    }

    void setXmasScene(String scene, boolean on) {
        if (!Christmas.SCENES.contains(scene)) return;
        Set<String> set = new LinkedHashSet<>(xmasScenes());
        if (on) set.add(scene);
        else set.remove(scene);
        List<String> ordered = new ArrayList<>();
        for (String s : Christmas.SCENES) if (set.contains(s)) ordered.add(s);
        store.put(KEY_XMAS_SCENES, ordered.isEmpty() ? "none" : String.join(",", ordered));
    }

    boolean xmasOnly() {
        return Boolean.parseBoolean(store.get(KEY_XMAS_ONLY));
    }

    void setXmasOnly(boolean on) {
        store.put(KEY_XMAS_ONLY, String.valueOf(on));
    }

    /**
     * The screensaver's candidates: the ticked scenes, plus the Christmas ones while the mode is on (only those with
     * "solo scene natalizie"). Never empty.
     */
    List<String> dreamCandidates() {
        List<String> base = scenes();
        if (!christmas() || xmasScenes().isEmpty()) return base;
        List<String> out = new ArrayList<>(xmasOnly() ? Collections.<String>emptyList() : base);
        out.addAll(xmasScenes());
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** The known names in a comma list, without duplicates, in {@code order}'s order. */
    private static List<String> known(String list, List<String> order) {
        List<String> out = new ArrayList<>();
        if (list == null || list.trim().isEmpty()) return out;
        Set<String> given = new LinkedHashSet<>();
        for (String s : list.split(",")) given.add(s.trim().toLowerCase(Locale.ROOT));
        for (String s : order) if (given.contains(s)) out.add(s);
        return out;
    }

    private static int number(String v, int fallback) {
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
