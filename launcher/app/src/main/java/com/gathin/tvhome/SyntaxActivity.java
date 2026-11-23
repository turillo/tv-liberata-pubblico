package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV, the launcher's own settings app on the TV's app row (LEANBACK_LAUNCHER): large cards for the
 * wallpaper, the screensaver, the news ticker, Christmas and the system page. Every choice is saved in SyntaxSettings and read at run time.
 */
public final class SyntaxActivity extends Activity {

    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView[] summaries;
    private ImageView wallpaperImage, sceneImage;
    private TextView systemFigure;
    private NewsTickerView ticker;
    private TextView[] profiles, positions;
    private TextView kitchenLabel, kitchenScreensaver, kitchenRecycling, nightFrom, nightTo;
    private LinearLayout kitchenRow;
    private int margin = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        SetupActivity.offer(this);   // first start (opened from the apps list after Downloader)
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        SyntaxUi.header(this, column, getString(R.string.syntax_name), getString(R.string.syntax_intro));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        column.addView(row, rowLp);
        column.setClipChildren(false);

        summaries = new TextView[5];
        wallpaperImage = new ImageView(this);
        sceneImage = new ImageView(this);
        View wallpaper = card(row, wallpaperImage, R.string.syntax_wallpaper, 0);
        View screensaver = card(row, sceneImage, R.string.syntax_screensaver, 1);
        FrameLayout tickerHolder = new FrameLayout(this);
        tickerHolder.setBackgroundColor(0xFF0B1018);
        ticker = new NewsTickerView(this, NewsTicker.label(this, SyntaxPrefs.get(this).sections()), 45, 0.06f);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                SyntaxUi.dp(this, 26), Gravity.CENTER);
        tickerHolder.addView(ticker, tlp);
        View news = card(row, tickerHolder, R.string.syntax_news, 2);
        ImageView xmasImage = new ImageView(this);
        xmasImage.setImageResource(R.drawable.preview_xmas);
        View xmas = card(row, xmasImage, R.string.xmas_title, 3);
        // A festive card: deep red glass with a warm gold edge when focused.
        android.graphics.drawable.StateListDrawable red = new android.graphics.drawable.StateListDrawable();
        red.addState(new int[]{android.R.attr.state_focused}, SyntaxUi.rounded(this, 22, 0xFF3A0E16, 3, 0xFFFFD27A));
        red.addState(new int[0], SyntaxUi.rounded(this, 22, 0xFF2A0B12, 1, 0x55FFD27A));
        xmas.setBackground(red);
        xmas.setOnClickListener(v -> startActivity(new Intent(this, SyntaxChristmasActivity.class)));
        // Sistema: the free space as a big figure on a dark card; the line under it says the free RAM.
        FrameLayout systemHolder = new FrameLayout(this);
        systemHolder.setBackgroundColor(0xFF0B1018);
        systemFigure = SyntaxUi.text(this, 26, R.string.font_medium, SyntaxUi.ACCENT);
        systemFigure.setGravity(Gravity.CENTER);
        systemHolder.addView(systemFigure, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        View system = card(row, systemHolder, R.string.syntax_system, 4);
        system.setOnClickListener(v -> startActivity(new Intent(this, SyntaxSystemActivity.class)));

        wallpaper.setOnClickListener(v -> startActivity(new Intent(this, SyntaxWallpaperActivity.class)));
        screensaver.setOnClickListener(v -> startActivity(new Intent(this, SyntaxScreensaverActivity.class)));
        news.setOnClickListener(v -> startActivity(new Intent(this, SyntaxNewsActivity.class)));
        // TV Liberata 1.0: no news ticker and no Christmas mode (Features): their cards are not shown.
        if (!Features.NEWS_TICKER) news.setVisibility(View.GONE);
        if (!Features.CHRISTMAS) xmas.setVisibility(View.GONE);

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
        wallpaper.requestFocus();
    }

    /** A Netflix-style card: a 16:9 picture, a title and one line on what is set now. */
    private View card(LinearLayout row, View picture, int title, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SyntaxUi.cardBackground(this, 22, SyntaxUi.CARD));
        int pad = SyntaxUi.dp(this, 12);
        card.setPadding(pad, pad, pad, SyntaxUi.dp(this, 18));
        SyntaxUi.focusable(card, 1.06f);

        FrameLayout frame = new FrameLayout(this);
        frame.setClipToOutline(true);
        frame.setBackground(SyntaxUi.rounded(this, 14, 0xFF0B1018, 0, 0));
        if (picture instanceof ImageView) ((ImageView) picture).setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(picture, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // Five cards, their zoom and margins fit 960 dp; narrower inside a screen margin.
        int room = getResources().getDisplayMetrics().widthPixels
                - 2 * (SyntaxUi.dp(this, 64) + ScreenMargin.x(this, ScreenMargin.percent(this)));
        int w = Math.max(SyntaxUi.dp(this, 84), Math.min(SyntaxUi.dp(this, 120), room / 5 - SyntaxUi.dp(this, 24 + 20 + 2)));
        // Narrower cards, smaller type, so "Screensaver" still fits on its line.
        float type = Math.min(1f, w / (float) SyntaxUi.dp(this, 120));
        card.addView(frame, new LinearLayout.LayoutParams(w, w * 9 / 16));

        TextView name = SyntaxUi.text(this, 19 * type, R.string.font_medium, SyntaxUi.TEXT);
        name.setSingleLine(true);
        name.setText(title);
        name.setPadding(SyntaxUi.dp(this, 6), SyntaxUi.dp(this, 16), 0, 0);
        card.addView(name);
        TextView summary = SyntaxUi.text(this, 14 * type, R.string.font_light, SyntaxUi.MUTED);
        summary.setPadding(SyntaxUi.dp(this, 6), SyntaxUi.dp(this, 8), SyntaxUi.dp(this, 6), 0);
        summary.setMaxLines(2);
        summary.setMaxWidth(w);
        card.addView(summary);
        summaries[index] = summary;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(SyntaxUi.dp(this, 10), 0, SyntaxUi.dp(this, 10), 0);
        row.addView(card, lp);
        return card;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A new screen margin (Sistema) moves this page's edges and card sizes: build it again.
        if (margin >= 0 && margin != ScreenMargin.percent(this)) {
            recreate();
            return;
        }
        margin = ScreenMargin.percent(this);
        refresh();
        ticker.startScrolling();
    }

    @Override
    protected void onPause() {
        ticker.stopScrolling();
        super.onPause();
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

    /** A toggle chip: a tick in front and selected while on, as the other Syntax TV switches. */
    private void mark(TextView chip, int label, boolean on) {
        chip.setText(on ? "✓ " + getString(label) : getString(label));
        chip.setSelected(on);
    }

    /** The cards' lines and pictures, from the settings as they are now. */
    private void refresh() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        refreshProfile();
        List<String> scenes = s.scenes();
        String mode = ScreensaverMode.isAerial(this) ? getString(R.string.syntax_aerial)
                : ScreensaverMode.isAmazon(this) ? getString(R.string.ss_amazon_photos)
                : scenes.size() == 1 ? sceneName(scenes.get(0))
                : getString(R.string.syntax_scenes_count, scenes.size());
        summaries[1].setText(mode);
        sceneImage.setImageResource(scenePreview(scenes.get(0)));
        summaries[3].setText(s.christmas() ? R.string.xmas_on_summary : R.string.xmas_off_summary);
        summaries[2].setText(s.ticker() ? getString(R.string.syntax_news_on, sectionNames(s.sections()))
                : getString(R.string.syntax_news_off));
        List<String[]> items = NewsTicker.items(this, NewsTicker.cached(this, s.sections()), s.sections());
        if (!items.isEmpty()) ticker.setHeadlines(items);
        int minutes = s.wallpaperMinutes();
        loader.execute(() -> {
            WallpaperLibrary.Counts counts = WallpaperLibrary.counts(this);
            Bitmap picture = latestPhoto();
            long[] disk = SystemProbe.disk(), ram = SystemProbe.ram(this);
            handler.post(() -> {
                if (isFinishing()) return;
                int free = SystemStats.percent(disk[1], disk[0]);
                systemFigure.setText(free < 0 ? "–" : free + "%");
                summaries[4].setText(getString(R.string.syntax_system_summary, free < 0 ? "–" : free + "%",
                        SystemStats.bytes(ram[1])));
                summaries[0].setText(counts.total == 0 ? getString(R.string.syntax_wallpaper_none)
                        : getString(R.string.syntax_wallpaper_summary, counts.active, counts.total, interval(minutes)));
                if (picture != null) wallpaperImage.setImageBitmap(picture);
            });
        });
    }

    /** The newest archived Bing photo at card size, or null. */
    private Bitmap latestPhoto() {
        File[] photos = WallpaperLibrary.photos(this);
        if (photos.length == 0) return null;
        File newest = photos[0];
        for (File f : photos) if (f.lastModified() > newest.lastModified()) newest = f;
        return WallpaperLibrary.thumbnail(this, newest);
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
            case Config.SCENE_FAVORITES: return R.drawable.preview_clock;   // the screensaver page shows a favourite photo
            default: return R.drawable.preview_clock;
        }
    }

    private String sectionNames(List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String k : keys) {
            NewsData.Section s = NewsData.section(k);
            if (s != null) names.add(s.label);
        }
        return android.text.TextUtils.join(", ", names);
    }
}
