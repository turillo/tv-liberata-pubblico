package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Impostazioni TV Liberata", the settings app on the TV's app row (LEANBACK_LAUNCHER): a grid of large tiles, one per
 * section (Wallpaper, Screensaver, App, News, Temi, Sistema; a section switched off in Features is not shown), each
 * with its icon, its name and a line on what is set now. The focused tile fills with the main colour. This page is
 * always a grid, whatever style Temi has chosen for the other pages. Every choice is saved in SyntaxSettings and read
 * at run time.
 */
public final class SyntaxActivity extends Activity {

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<TextView> statuses = new ArrayList<>();
    private final List<Integer> kinds = new ArrayList<>();
    private TextView[] profiles, positions;
    private TextView kitchenLabel, kitchenScreensaver, kitchenRecycling, nightFrom, nightTo;
    private LinearLayout kitchenRow;
    private int margin = -1;
    private int themeKey = 0;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SetupActivity.offer(this);   // first start (opened from the apps list after Downloader)
        long started = android.os.SystemClock.uptimeMillis();
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        themeKey = themeKey();
        String sub = getString(R.string.syntax_home_sub, BuildConfig.VERSION_NAME,
                DeviceConfig.TEST.equals(DeviceConfig.channel(this)) ? "test" : "produzione", DeviceConfig.name(this));
        SyntaxUi.header(this, column, getString(R.string.syntax_home_title), sub);

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, SyntaxUi.dp(this, 26), 0, 0);
        column.setClipChildren(false);
        column.addView(grid);
        List<int[]> sections = new ArrayList<>();   // {icon kind, title}
        sections.add(new int[]{SectionIcon.WALLPAPER, R.string.syntax_wallpaper});
        sections.add(new int[]{SectionIcon.SCREENSAVER, R.string.syntax_screensaver});
        sections.add(new int[]{SectionIcon.APPS, R.string.apps_title});
        if (Features.NEWS_TICKER) sections.add(new int[]{SectionIcon.NEWS, R.string.syntax_news});
        if (Features.CHRISTMAS) sections.add(new int[]{SectionIcon.CHRISTMAS, R.string.xmas_title});
        sections.add(new int[]{SectionIcon.THEMES, R.string.theme_title});
        sections.add(new int[]{SectionIcon.SYSTEM, R.string.syntax_system});
        int room = getResources().getDisplayMetrics().widthPixels
                - 2 * (SyntaxUi.dp(this, 64) + ScreenMargin.x(this, ScreenMargin.percent(this)));
        int tileGap = SyntaxUi.dp(this, 22);
        // Room around the grid for the focused tile's 5% zoom, so its edges are never cut.
        int zoomRoom = SyntaxUi.dp(this, 14);
        grid.setPadding(zoomRoom, grid.getPaddingTop(), zoomRoom, zoomRoom);
        room -= 2 * zoomRoom;
        int w = (room - 2 * tileGap) / 3, h = Math.round(w * 150f / 330f);
        List<View> tiles = new ArrayList<>();
        LinearLayout row = null;
        View first = null;
        for (int i = 0; i < sections.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rl.topMargin = tileGap;
                grid.addView(row, rl);
            }
            int kind = sections.get(i)[0];
            View tile = tile(kind, sections.get(i)[1]);
            // 330x150 on a 1280x720 screen, from the real room on any other (see fitTiles).
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i % 3 != 0) lp.setMarginStart(tileGap);
            row.addView(tile, lp);
            sizeTile(tile, w, h);
            tiles.add(tile);
            tile.setOnClickListener(v -> open(kind));
            if (first == null) first = tile;
        }

        // The room this TV is in, under the cards: one chip per profile, the current one selected.
        TextView profileLabel = SyntaxUi.label(this, getString(R.string.syntax_profile));
        column.addView(profileLabel);
        LinearLayout profileRow = SyntaxUi.chipRow(this);
        String[] profileNames = getResources().getStringArray(R.array.syntax_profile_names);
        profiles = new TextView[SyntaxSettings.PROFILES.size()];
        for (int i = 0; i < profiles.length; i++) {
            String profile = SyntaxSettings.PROFILES.get(i);
            TextView chip = SyntaxUi.chip(this, profileNames[i]);
            chip.setOnClickListener(v -> {
                SyntaxPrefs.get(this).setProfile(profile);
                refreshProfile();
            });
            SyntaxUi.addChip(profileRow, chip);
            profiles[i] = chip;
        }
        // Where Home's apps row sits, on the same line: low (as before), in the middle or at the top.
        View gap = new View(this);
        profileRow.addView(gap, new LinearLayout.LayoutParams(SyntaxUi.dp(this, 6), 1));
        TextView positionLabel = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.MUTED);
        positionLabel.setText(R.string.syntax_apps_position);
        positionLabel.setPadding(0, 0, SyntaxUi.dp(this, 14), 0);
        profileRow.addView(positionLabel);
        String[] positionNames = getResources().getStringArray(R.array.syntax_apps_position_names);
        positions = new TextView[SyntaxSettings.APPS_POSITIONS.size()];
        for (int i = 0; i < positions.length; i++) {
            String position = SyntaxSettings.APPS_POSITIONS.get(i);
            TextView chip = SyntaxUi.chip(this, positionNames[i]);
            // Narrower than the profile chips, so both groups fit one line at 1080p.
            chip.setPadding(SyntaxUi.dp(this, 14), chip.getPaddingTop(), SyntaxUi.dp(this, 14), chip.getPaddingBottom());
            chip.setOnClickListener(v -> {
                SyntaxPrefs.get(this).setAppsPosition(position);
                refreshProfile();
            });
            SyntaxUi.addChip(profileRow, chip);
            positions[i] = chip;
        }
        column.addView(profileRow);
        if (!Edition.profiles()) {
            // The TVs and TV Liberata: no profile, apps row position or kitchen panel (Fire TV extras).
            profileLabel.setVisibility(View.GONE);
            profileRow.setVisibility(View.GONE);
        }

        // Profile Cucina only: the panel, whether a screensaver may start over it, and the recycling box.
        kitchenLabel = SyntaxUi.label(this, getString(R.string.syntax_kitchen));
        column.addView(kitchenLabel);
        kitchenRow = SyntaxUi.chipRow(this);
        TextView open = SyntaxUi.chip(this, getString(R.string.kitchen_open));
        open.setOnClickListener(v -> startActivity(new Intent(this, KitchenPanelActivity.class)));
        SyntaxUi.addChip(kitchenRow, open);
        kitchenScreensaver = SyntaxUi.chip(this, getString(R.string.kitchen_screensaver));
        kitchenScreensaver.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setKitchenScreensaver(!s.kitchenScreensaver());
            refreshProfile();
        });
        SyntaxUi.addChip(kitchenRow, kitchenScreensaver);
        kitchenRecycling = SyntaxUi.chip(this, getString(R.string.kitchen_recycling));
        // The calendar's own screen, with the switch for the box at its top.
        kitchenRecycling.setOnClickListener(v -> startActivity(new Intent(this, SyntaxRecyclingActivity.class)));
        SyntaxUi.addChip(kitchenRow, kitchenRecycling);
        // Night hours: each OK moves to the next time; the start can also be off.
        nightFrom = SyntaxUi.chip(this, "");
        nightFrom.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setKitchenNight(SyntaxSettings.next(SyntaxSettings.NIGHT_STARTS, s.kitchenNightStart()), s.kitchenNightEnd());
            refreshProfile();
        });
        SyntaxUi.addChip(kitchenRow, nightFrom);
        nightTo = SyntaxUi.chip(this, "");
        nightTo.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setKitchenNight(s.kitchenNightStart(), SyntaxSettings.next(SyntaxSettings.NIGHT_ENDS, s.kitchenNightEnd()));
            refreshProfile();
        });
        SyntaxUi.addChip(kitchenRow, nightTo);
        // Five chips on one line at 1080p: narrower than the others.
        for (int i = 0; i < kitchenRow.getChildCount(); i++) {
            View c = kitchenRow.getChildAt(i);
            c.setPadding(SyntaxUi.dp(this, 12), c.getPaddingTop(), SyntaxUi.dp(this, 12), c.getPaddingBottom());
        }
        column.addView(kitchenRow);


        setContentView(outer);
        int rows = (sections.size() + 2) / 3, rowGap = tileGap, tileW = w;
        grid.post(() -> fitTiles(outer, grid, tiles, rows, tileW, rowGap));
        first.requestFocus();
        outer.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                outer.getViewTreeObserver().removeOnPreDrawListener(this);
                android.util.Log.i("TvHome.Settings", "settings page ready in "
                        + (android.os.SystemClock.uptimeMillis() - started) + " ms");
                return true;
            }
        });
    }

    /**
     * Makes the tiles shorter when the rows do not fit the screen under the header (a TV with less room in dp, or
     * screen margins), so the whole grid is always visible.
     */
    private void fitTiles(View outer, LinearLayout grid, List<View> tiles, int rows, int w, int gap) {
        if (tiles.isEmpty() || outer.getHeight() == 0) return;
        int[] o = new int[2], g = new int[2];
        outer.getLocationInWindow(o);
        grid.getLocationInWindow(g);
        int bottom = SyntaxUi.dp(this, 24) + ScreenMargin.y(this, ScreenMargin.percent(this));
        int avail = outer.getHeight() - (g[1] - o[1]) - grid.getPaddingTop() - grid.getPaddingBottom() - bottom;
        int h = tiles.get(0).getLayoutParams().height;
        int fit = tileHeight(avail, rows, gap, h);
        if (fit == h) return;
        for (View t : tiles) {
            t.getLayoutParams().height = fit;
            sizeTile(t, w, fit);
        }
        grid.requestLayout();
    }

    /** The tile height: the wanted one, or less when that many rows (with their gaps) would not fit. Tested. */
    static int tileHeight(int avail, int rows, int gap, int wanted) {
        if (rows <= 0 || avail <= 0) return wanted;
        int most = (avail - (rows - 1) * gap) / rows;
        return Math.max(1, Math.min(wanted, most));
    }

    /**
     * Sizes a tile's icon and texts from the tile's own height (in pixels, not fixed dp), so the name and the status
     * line always fit inside it: the icon shrinks first, then the texts.
     */
    private void sizeTile(View tile, int w, int h) {
        LinearLayout t = (LinearLayout) tile;
        View icon = t.getChildAt(0);
        TextView name = (TextView) t.getChildAt(1), status = (TextView) t.getChildAt(2);
        float[] m = tileMetrics(w, h, getResources().getDisplayMetrics().density);
        int padV = Math.round(m[0]), padH = Math.round(m[1]);
        t.setPadding(padH, padV, padH, padV);
        int box = Math.round(m[2]);
        icon.getLayoutParams().width = box;
        icon.getLayoutParams().height = box;
        icon.setForeground(new android.graphics.drawable.InsetDrawable(new SectionIcon(kinds.get(statuses.indexOf(status)),
                0xFFFFFFFF), Math.round(box * 0.2f)));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m[3]);
        name.setPadding(0, Math.round(m[5]), 0, 0);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, m[4]);
        status.setPadding(0, Math.round(m[6]), 0, 0);
        t.requestLayout();
    }

    /**
     * {vertical padding, horizontal padding, icon box, name size, status size, gap above the name, gap above the
     * status}, all in pixels, for a w x h tile. The parts add up to at most h (line height about 1.35 x text size).
     * Plain Java, tested.
     */
    static float[] tileMetrics(int w, int h, float density) {
        float padV = h * 0.10f, padH = Math.min(w * 0.055f, 18 * density);
        float icon = Math.min(h * 0.26f, 40 * density), name = Math.min(h * 0.14f, 22 * density);
        float status = Math.min(h * 0.09f, 14 * density), gap1 = h * 0.05f, gap2 = h * 0.015f;
        float need = 2 * padV + icon + gap1 + name * 1.35f + gap2 + status * 1.35f;
        if (need > h) {
            // The icon gives way first (down to 60%), then everything shrinks together.
            float spare = Math.min(need - h, icon * 0.4f);
            icon -= spare;
            need -= spare;
            if (need > h) {
                float k = h / need;
                padV *= k; icon *= k; name *= k; status *= k; gap1 *= k; gap2 *= k;
            }
        }
        return new float[]{padV, padH, icon, name, status, gap1, gap2};
    }

    /** A section's tile: the icon in a small square, the name, and a line on what is set now. */
    private View tile(int kind, int title) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        int pad = SyntaxUi.dp(this, 14);
        t.setPadding(SyntaxUi.dp(this, 18), pad, SyntaxUi.dp(this, 18), pad);
        t.setGravity(android.view.Gravity.BOTTOM);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, SyntaxUi.accentFill(this, 20));
        bg.addState(new int[0], SyntaxUi.rounded(this, 20, 0xB3101520, 1, SyntaxUi.CARD_EDGE));
        t.setBackground(bg);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(150).start();
            v.setElevation(has ? SyntaxUi.dp(this, 14) : 0);
        });
        View icon = new View(this);
        icon.setBackground(SyntaxUi.rounded(this, 12, 0x40000000, 0, 0));
        int box = SyntaxUi.dp(this, 36);
        int iconPad = SyntaxUi.dp(this, 7);
        icon.setForeground(new android.graphics.drawable.InsetDrawable(new SectionIcon(kind, 0xFFFFFFFF), iconPad));
        t.addView(icon, new LinearLayout.LayoutParams(box, box));
        int on = Theme.onColor(SyntaxUi.ACCENT);
        TextView name = SyntaxUi.text(this, 20, R.string.font_medium, SyntaxUi.TEXT);
        name.setText(title);
        name.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{on, SyntaxUi.TEXT}));
        name.setDuplicateParentStateEnabled(true);
        name.setPadding(0, SyntaxUi.dp(this, 8), 0, 0);
        name.setSingleLine(true);
        t.addView(name);
        TextView status = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        status.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{Theme.withAlpha(on, 0xD9), SyntaxUi.MUTED}));
        status.setDuplicateParentStateEnabled(true);
        status.setPadding(0, SyntaxUi.dp(this, 4), 0, 0);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(status);
        statuses.add(status);
        kinds.add(kind);
        return t;
    }

    private void open(int kind) {
        Class<?> page;
        switch (kind) {
            case SectionIcon.WALLPAPER: page = SyntaxWallpaperActivity.class; break;
            case SectionIcon.SCREENSAVER: page = SyntaxScreensaverActivity.class; break;
            case SectionIcon.APPS: page = SyntaxAppsActivity.class; break;
            case SectionIcon.NEWS: page = SyntaxNewsActivity.class; break;
            case SectionIcon.CHRISTMAS: page = SyntaxChristmasActivity.class; break;
            case SectionIcon.THEMES: page = SyntaxThemeActivity.class; break;
            default: page = SyntaxSystemActivity.class;
        }
        startActivity(new Intent(this, page));
    }

    /** Changes when the theme does: the page is built again (its tiles carry the colour). */
    private int themeKey() {
        return (Theme.savedStyle(this) * 31 + Theme.color(this)) * 31 + Theme.backdrop(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A new screen margin (Sistema) or a new theme (Temi) changes this page: build it again.
        if ((margin >= 0 && margin != ScreenMargin.percent(this)) || themeKey != themeKey()) {
            recreate();
            return;
        }
        margin = ScreenMargin.percent(this);
        refresh();
    }

    @Override
    protected void onDestroy() {
        loader.shutdownNow();
        super.onDestroy();
    }

    private void refreshProfile() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        String now = s.profile();
        for (int i = 0; i < profiles.length; i++) profiles[i].setSelected(SyntaxSettings.PROFILES.get(i).equals(now));
        String position = s.appsPosition();
        for (int i = 0; i < positions.length; i++) {
            positions[i].setSelected(SyntaxSettings.APPS_POSITIONS.get(i).equals(position));
        }
        int kitchen = Edition.profiles() && s.kitchen() ? View.VISIBLE : View.GONE;
        kitchenLabel.setVisibility(kitchen);
        kitchenRow.setVisibility(kitchen);
        mark(kitchenScreensaver, R.string.kitchen_screensaver, s.kitchenScreensaver());
        mark(kitchenRecycling, R.string.kitchen_recycling, s.kitchenRecycling());
        int start = s.kitchenNightStart();
        nightFrom.setText(start < 0 ? getString(R.string.kitchen_night_off) : getString(R.string.kitchen_night_from, hhmm(start)));
        nightTo.setText(getString(R.string.kitchen_night_to, hhmm(s.kitchenNightEnd())));
        nightTo.setVisibility(start < 0 ? View.GONE : View.VISIBLE);
    }

    private static String hhmm(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** A toggle chip: a tick in front and selected while on, as the other switches. */
    private void mark(TextView chip, int label, boolean on) {
        chip.setText(on ? "✓ " + getString(label) : getString(label));
        chip.setSelected(on);
    }

    /** The tiles' lines, from the settings as they are now (counts and sizes off the main thread). */
    private void refresh() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        refreshProfile();
        List<String> scenes = s.scenes();
        String mode = ScreensaverMode.isAerial(this) ? getString(R.string.syntax_aerial)
                : ScreensaverMode.isAmazon(this) ? getString(R.string.ss_amazon_photos)
                : scenes.size() == 1 ? sceneName(scenes.get(0))
                : getString(R.string.syntax_scenes_count, scenes.size());
        String screensaver = getString(R.string.tile_screensaver_status, mode, ScreensaverMode.minutes(this));
        String news = !s.ticker() && !s.homeTicker() ? getString(R.string.tile_news_off)
                : s.ticker() && s.homeTicker() ? getString(R.string.tile_news_both)
                : s.homeTicker() ? getString(R.string.tile_news_home) : getString(R.string.tile_news_dream);
        String[] styleNames = getResources().getStringArray(R.array.theme_styles);
        String theme = styleNames[Theme.savedStyle(this)] + " · " + SyntaxThemeActivity.colorName(this, Theme.color(this));
        String xmas = getString(s.christmas() ? R.string.xmas_on_summary : R.string.xmas_off_summary);
        String system = getString(FilterSettings.on(this) ? R.string.tile_filter_on : R.string.tile_filter_off) + " · "
                + getString(Updater.newerKnown(this) ? R.string.tile_update_available : R.string.tile_up_to_date);
        int minutes = s.wallpaperMinutes();
        for (int i = 0; i < kinds.size(); i++) {
            switch (kinds.get(i)) {
                case SectionIcon.SCREENSAVER: statuses.get(i).setText(screensaver); break;
                case SectionIcon.NEWS: statuses.get(i).setText(news); break;
                case SectionIcon.THEMES: statuses.get(i).setText(theme); break;
                case SectionIcon.CHRISTMAS: statuses.get(i).setText(xmas); break;
                case SectionIcon.SYSTEM: statuses.get(i).setText(system); break;
                default: break;   // below, off the main thread
            }
        }
        loader.execute(() -> {
            WallpaperLibrary.Counts counts = WallpaperLibrary.counts(this);
            int apps = SyntaxAppsActivity.launchable(this).size();
            handler.post(() -> {
                if (isFinishing()) return;
                for (int i = 0; i < kinds.size(); i++) {
                    if (kinds.get(i) == SectionIcon.WALLPAPER) {
                        statuses.get(i).setText(counts.total == 0 ? getString(R.string.syntax_wallpaper_none)
                                : getString(R.string.tile_wallpaper_status, counts.total, interval(minutes)));
                    } else if (kinds.get(i) == SectionIcon.APPS) {
                        statuses.get(i).setText(getString(R.string.tile_apps_status, apps));
                    }
                }
            });
        });
    }

    String interval(int minutes) {
        // Under an hour (15 or 30 minutes) in minutes: "ogni 0 h" before.
        return minutes >= 1440 ? getString(R.string.syntax_every_day)
                : minutes < 60 || minutes % 60 != 0 ? getString(R.string.syntax_every_minutes, minutes)
                : getString(R.string.syntax_every_hours, minutes / 60);
    }

    String sceneName(String scene) {
        int i = SyntaxSettings.ALL_SCENES.indexOf(scene);
        String[] names = getResources().getStringArray(R.array.syntax_scene_names);
        return i >= 0 && i < names.length ? names[i] : scene;
    }

    static int scenePreview(String scene) {
        switch (scene) {
            case Config.SCENE_ISS: return R.drawable.preview_iss;
            case Config.SCENE_SUN: return R.drawable.preview_sun;
            case Config.SCENE_EARTH: return R.drawable.preview_earth;
            case Config.SCENE_MOON: return R.drawable.preview_moon;
            case Config.SCENE_SPACE: return R.drawable.preview_space;
            case Config.SCENE_LIVE: return R.drawable.preview_live;
            default: return R.drawable.preview_clock;
        }
    }
}
