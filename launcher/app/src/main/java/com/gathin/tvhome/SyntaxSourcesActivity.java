package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV, Wallpaper, Sorgenti: which sources fill the archive (Bing only by default), how many new photos a day
 * each brings (1 to 3; Bing always its one photo of the day), the Unsplash themes, the archive's cap, and
 * "Scarica ora". Unsplash needs an API key in launcher/keys.properties; without one they are greyed out
 * with "chiave mancante". From the Christmas wallpapers ({@link SyntaxWallpaperActivity#EXTRA_XMAS}) it sets the
 * Christmas sources and themes instead, and "Scarica ora" fills the Christmas archive.
 */
public final class SyntaxSourcesActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, TextView> sourceChips = new HashMap<>();
    private final Map<String, TextView[]> perDayChips = new HashMap<>();
    private final Map<String, TextView> themeChips = new HashMap<>();
    private final TextView[] capChips = new TextView[WallpaperSources.CAPS.length];
    private TextView status, shareChip, fromChip;
    private boolean busy;
    private boolean xmas;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        xmas = getIntent().getBooleanExtra(SyntaxWallpaperActivity.EXTRA_XMAS, false);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(xmas ? R.string.xmas_sources_title : R.string.syntax_sources), null);

        for (String source : xmas ? SyntaxSettings.XMAS_SOURCES : WallpaperSources.ALL) {
            LinearLayout row = SyntaxUi.chipRow(this);
            row.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
            boolean usable = WallpaperSources.usable(source, DeviceConfig.unsplashKey(this));
            TextView chip = compact(SyntaxUi.chip(this, SyntaxWallpaperActivity.sourceName(source)));
            chip.setMinWidth(SyntaxUi.dp(this, 190));
            if (usable) {
                chip.setOnClickListener(v -> {
                    SyntaxSettings s = SyntaxPrefs.get(this);
                    if (xmas) s.setXmasSource(source, !s.xmasSources().contains(source));
                    else s.setSource(source, !s.sources().contains(source));
                    refresh();
                });
            } else {
                chip.setText(getString(R.string.syntax_key_missing, SyntaxWallpaperActivity.sourceName(source)));
                chip.setFocusable(false);
                chip.setEnabled(false);
                chip.setAlpha(0.4f);
            }
            sourceChips.put(source, chip);
            SyntaxUi.addChip(row, chip);
            TextView note = SyntaxUi.text(this, 13, R.string.font_regular, SyntaxUi.MUTED);
            note.setPadding(SyntaxUi.dp(this, 6), 0, SyntaxUi.dp(this, 12), 0);
            row.addView(note);
            if (WallpaperSources.BING.equals(source)) {
                note.setText(R.string.syntax_bing_daily);
            } else if (usable) {
                note.setText(R.string.syntax_per_day);
                TextView[] chips = new TextView[3];
                for (int n = 1; n <= 3; n++) {
                    int value = n;
                    chips[n - 1] = compact(SyntaxUi.chip(this, String.valueOf(n)));
                    chips[n - 1].setOnClickListener(v -> {
                        SyntaxSettings s = SyntaxPrefs.get(this);
                        if (xmas) {
                            s.setXmasPerDay(source, value);
                            s.setXmasSource(source, true);
                        } else {
                            s.setPerDay(source, value);
                            s.setSource(source, true);   // choosing how many means using it
                        }
                        refresh();
                    });
                    SyntaxUi.addChip(row, chips[n - 1]);
                }
                perDayChips.put(source, chips);
                TextView about = SyntaxUi.text(this, 12, R.string.font_light, SyntaxUi.MUTED);
                about.setText(xmas ? R.string.xmas_source_note : WallpaperSources.WIKIMEDIA.equals(source) ? R.string.syntax_wikimedia_note : R.string.syntax_themed_note);
                about.setPadding(SyntaxUi.dp(this, 10), 0, 0, 0);
                row.addView(about);
            } else {
                note.setText(R.string.syntax_key_where);
            }
            column.addView(row);
        }

        column.addView(SyntaxUi.label(this, getString(R.string.syntax_themes)));
        LinearLayout themes = SyntaxUi.chipRow(this);
        int[] names = xmas ? new int[]{R.string.xmas_theme_lights, R.string.xmas_theme_market, R.string.xmas_theme_tree,
                R.string.xmas_theme_snow}
                : new int[]{R.string.syntax_theme_sea, R.string.syntax_theme_mountain, R.string.syntax_theme_city,
                R.string.syntax_theme_nature, R.string.syntax_theme_space, R.string.syntax_theme_italy, R.string.syntax_theme_season};
        List<String> allThemes = xmas ? Christmas.THEMES : WallpaperSources.THEMES;
        for (int i = 0; i < allThemes.size(); i++) {
            String theme = allThemes.get(i);
            TextView chip = compact(SyntaxUi.chip(this, getString(names[i])));
            chip.setOnClickListener(v -> {
                SyntaxSettings s = SyntaxPrefs.get(this);
                boolean ok = xmas ? s.setXmasTheme(theme, !s.xmasThemes().contains(theme))
                        : s.setTheme(theme, !s.themes().contains(theme));
                if (!ok) {
                    SyntaxDialog.message(this, getString(R.string.syntax_themes), getString(R.string.syntax_one_theme));
                }
                refresh();
            });
            themeChips.put(theme, chip);
            SyntaxUi.addChip(themes, chip);
        }
        column.addView(themes);

        column.addView(SyntaxUi.label(this, getString(R.string.syntax_archive)));
        LinearLayout archive = SyntaxUi.chipRow(this);
        for (int i = 0; i < WallpaperSources.CAPS.length; i++) {
            int cap = WallpaperSources.CAPS[i];
            capChips[i] = compact(SyntaxUi.chip(this, getString(R.string.syntax_cap, cap)));
            capChips[i].setOnClickListener(v -> {
                SyntaxPrefs.get(this).setArchiveCap(cap);
                refresh();
            });
            SyntaxUi.addChip(archive, capChips[i]);
        }
        archive.addView(new android.view.View(this), new LinearLayout.LayoutParams(SyntaxUi.dp(this, 18), 1));
        TextView now = compact(SyntaxUi.chip(this, getString(R.string.syntax_fetch_now)));
        now.setOnClickListener(v -> fetchNow());
        SyntaxUi.addChip(archive, now);
        status = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        status.setPadding(SyntaxUi.dp(this, 6), 0, 0, 0);
        archive.addView(status);
        column.addView(archive);

        if (!xmas) {
            // Wallpapers shared between the TVs of the house (ArchiveShare).
            column.addView(SyntaxUi.label(this, getString(R.string.share_title)));
            LinearLayout share = SyntaxUi.chipRow(this);
            shareChip = compact(SyntaxUi.chip(this, ""));
            shareChip.setOnClickListener(v -> {
                SyntaxSettings s = SyntaxPrefs.get(this);
                s.setShareWallpapers(!s.shareWallpapers());
                ArchiveShare.apply(this);
                refresh();
            });
            SyntaxUi.addChip(share, shareChip);
            fromChip = compact(SyntaxUi.chip(this, ""));
            fromChip.setOnClickListener(v -> chooseSource());
            SyntaxUi.addChip(share, fromChip);
            column.addView(share);
        }

        setContentView(outer);
        refresh();
        TextView first = sourceChips.get(xmas ? WallpaperSources.WIKIMEDIA : WallpaperSources.BING);
        if (first != null) first.requestFocus();
    }

    /** A chip a little less tall, so the whole screen fits without scrolling. */
    private TextView compact(TextView chip) {
        chip.setPadding(SyntaxUi.dp(this, 18), SyntaxUi.dp(this, 8), SyntaxUi.dp(this, 18), SyntaxUi.dp(this, 8));
        chip.setTextSize(15);
        return chip;
    }

    private void refresh() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        List<String> on = xmas ? s.xmasSources() : s.sources();
        for (Map.Entry<String, TextView> e : sourceChips.entrySet()) {
            if (!e.getValue().isEnabled()) continue;
            boolean ticked = on.contains(e.getKey());
            e.getValue().setSelected(ticked);
            e.getValue().setText((ticked ? "✓ " : "") + SyntaxWallpaperActivity.sourceName(e.getKey()));
        }
        for (Map.Entry<String, TextView[]> e : perDayChips.entrySet()) {
            int n = xmas ? s.xmasPerDay(e.getKey()) : s.perDay(e.getKey());
            for (int i = 0; i < 3; i++) e.getValue()[i].setSelected(i + 1 == n);
        }
        List<String> themes = xmas ? s.xmasThemes() : s.themes();
        for (Map.Entry<String, TextView> e : themeChips.entrySet()) e.getValue().setSelected(themes.contains(e.getKey()));
        for (int i = 0; i < capChips.length; i++) capChips[i].setSelected(WallpaperSources.CAPS[i] == s.archiveCap());
        if (shareChip != null) {
            boolean sharing = s.shareWallpapers();
            shareChip.setSelected(sharing);
            shareChip.setText((sharing ? "✓ " : "") + getString(R.string.share_mine));
            String from = s.syncFrom();
            fromChip.setSelected(from != null);
            fromChip.setText(getString(R.string.share_from, from == null ? getString(R.string.share_from_none) : from));
        }
    }

    /** Looks for the TVs sharing their wallpapers, then lets the viewer pick one (or none). */
    private void chooseSource() {
        if (busy) return;
        busy = true;
        status.setText(R.string.share_searching);
        worker.execute(() -> {
            List<ArchiveShare.Found> found = ArchiveShare.discover(this, 4_000);
            handler.post(() -> {
                busy = false;
                if (isFinishing()) return;
                String[] names = new String[found.size() + 1];
                names[0] = getString(R.string.share_from_none);
                for (int i = 0; i < found.size(); i++) names[i + 1] = found.get(i).name;
                status.setText(found.isEmpty() ? getString(R.string.share_none_found) : "");
                SyntaxDialog.show(this, getString(R.string.share_pick), getString(R.string.share_pick_note), names, 0, which -> {
                    SyntaxPrefs.get(this).setSyncFrom(which <= 0 ? null : names[which]);
                    refresh();
                    if (which > 0) fetchNow();   // the first copy now
                });
            });
        });
    }

    /** Downloads the ticked sources' photos for today now, instead of at Home's next check. */
    private void fetchNow() {
        if (busy) return;
        busy = true;
        status.setText(R.string.syntax_fetching);
        worker.execute(() -> {
            java.io.File archive = WallpaperLibrary.archive(this);
            WallpaperFetcher fetcher = new WallpaperFetcher(this, archive);
            List<WallpaperSources.Result> results = xmas ? fetcher.runChristmas() : fetcher.run();
            handler.post(() -> {
                busy = false;
                if (isFinishing()) return;
                status.setText(report(results));
            });
        });
    }

    /** One short line per source: "Unsplash: 3 foto nuove · Wikimedia: limite di oggi raggiunto (3)". */
    private String report(List<WallpaperSources.Result> results) {
        if (results.isEmpty()) return getString(R.string.syntax_fetch_none_ticked);
        StringBuilder b = new StringBuilder();
        for (WallpaperSources.Result r : results) {
            if (b.length() > 0) b.append('\n');
            if (r.source.startsWith("sync:")) {
                String from = r.source.substring(5);
                b.append(r.failed ? getString(R.string.share_failed, from) : getString(R.string.share_copied, from, r.added));
                continue;
            }
            String name = r.source.startsWith("xmas_") ? getString(R.string.xmas_title) + " · "
                    + SyntaxWallpaperActivity.sourceName(r.source.substring(5)) : SyntaxWallpaperActivity.sourceName(r.source);
            switch (r.outcome()) {
                case WallpaperSources.Result.ADDED:
                    b.append(getString(R.string.syntax_fetch_added, name, r.added));
                    break;
                case WallpaperSources.Result.QUOTA:
                    b.append(getString(R.string.syntax_fetch_quota, name, r.perDay));
                    break;
                case WallpaperSources.Result.DISCARDED:
                    b.append(getString(R.string.syntax_fetch_discarded, name, r.discarded));
                    break;
                case WallpaperSources.Result.FAILED:
                    b.append(getString(R.string.syntax_fetch_failed, name));
                    break;
                default:
                    b.append(getString(R.string.syntax_fetch_nothing, name));
            }
        }
        return b.toString();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
