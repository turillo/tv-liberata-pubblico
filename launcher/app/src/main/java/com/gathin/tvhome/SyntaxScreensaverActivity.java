package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Syntax TV's screensaver screen: Syntax scenes or Aerial Views as the TV's system screensaver (needs
 * WRITE_SECURE_SETTINGS, granted once over adb; without it a clear message instead), the scenes with a preview and
 * a tick each (ticked ones take turns; one always stays ticked), and "Try now".
 */
public final class SyntaxScreensaverActivity extends Activity {

    private TextView syntaxMode, aerialMode, amazonMode, notice, idleClock;
    private final TextView[] minuteChips = new TextView[ScreensaverMode.MINUTES.length];
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final View[] cards = new View[SyntaxSettings.SCENES.size()];
    private final TextView[] ticks = new TextView[SyntaxSettings.SCENES.size()];
    private final ImageView[] previews = new ImageView[SyntaxSettings.SCENES.size()];
    private final TextView[] secondChips = new TextView[FavoritesData.SECONDS.length];
    private TextView panelChip;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        // Scrolls: with the permission notice, a screen margin or a denser screen the second row of scenes no
        // longer fits, and its names were cut to dots.
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.syntax_screensaver), null);

        column.addView(SyntaxUi.label(this, getString(R.string.syntax_mode)));
        LinearLayout modes = SyntaxUi.chipRow(this);
        syntaxMode = SyntaxUi.chip(this, getString(R.string.syntax_mode_scenes));
        aerialMode = SyntaxUi.chip(this, getString(R.string.syntax_aerial));
        TextView test = SyntaxUi.chip(this, getString(R.string.syntax_try));
        syntaxMode.setOnClickListener(v -> setMode(false));
        // Aerial Views: the system screensaver when installed; otherwise its card in the App catalogue.
        aerialMode.setOnClickListener(v -> {
            if (ScreensaverMode.aerialInstalled(this)) setMode(true);
            else startActivity(new android.content.Intent(this, SyntaxCatalogActivity.class)
                    .putExtra(SyntaxCatalogActivity.EXTRA_FOCUS, "aerial-views"));
        });
        test.setOnClickListener(v -> {
            if (!ScreensaverMode.startNow(this)) {
                SyntaxDialog.message(this, getString(R.string.syntax_error), getString(R.string.syntax_try_failed));
            }
        });
        SyntaxUi.addChip(modes, syntaxMode);
        SyntaxUi.addChip(modes, aerialMode);
        // Fire OS: its own screensaver, Amazon Photos.
        amazonMode = SyntaxUi.chip(this, getString(R.string.ss_amazon_photos));
        amazonMode.setOnClickListener(v -> setAmazon());
        if (Device.isFireOs() && ScreensaverMode.amazonInstalled(this)) SyntaxUi.addChip(modes, amazonMode);
        View gap = new View(this);
        modes.addView(gap, new LinearLayout.LayoutParams(SyntaxUi.dp(this, 24), 1));
        SyntaxUi.addChip(modes, test);
        // Home's clock before the screensaver (home_idle_clock_minutes): one chip that switches it on and off.
        if (Config.load(this).homeIdleClockMinutes > 0) {
            idleClock = SyntaxUi.chip(this, "");
            idleClock.setOnClickListener(v -> {
                SyntaxSettings s = SyntaxPrefs.get(this);
                s.setHomeIdleClock(!s.homeIdleClock());
                refresh();
            });
            View gap2 = new View(this);
            modes.addView(gap2, new LinearLayout.LayoutParams(SyntaxUi.dp(this, 24), 1));
            SyntaxUi.addChip(modes, idleClock);
        }
        column.addView(modes);
        // "Avvia dopo N minuti": the system's screen_off_timeout.
        LinearLayout after = SyntaxUi.chipRow(this);
        after.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
        TextView afterLabel = SyntaxUi.text(this, 15, R.string.font_medium, SyntaxUi.TEXT);
        afterLabel.setText(R.string.ss_start_after);
        afterLabel.setPadding(0, 0, SyntaxUi.dp(this, 12), 0);
        after.addView(afterLabel);
        for (int i = 0; i < ScreensaverMode.MINUTES.length; i++) {
            int m = ScreensaverMode.MINUTES[i];
            minuteChips[i] = small(SyntaxUi.chip(this, getString(R.string.ss_minutes, m)));
            minuteChips[i].setOnClickListener(v -> worker.execute(() -> {
                boolean ok = ScreensaverMode.setMinutes(this, m);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (!ok) SyntaxDialog.message(this, getString(R.string.ss_start_after), getString(R.string.ss_minutes_failed));
                    refresh();
                });
            }));
            SyntaxUi.addChip(after, minuteChips[i]);
        }
        column.addView(after);
        notice = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.DANGER);
        notice.setPadding(0, SyntaxUi.dp(this, 8), 0, 0);
        column.addView(notice);

        // The scenes' heading, with the "Preferite" scene's settings on the same line (seconds, panel), compact so
        // the two rows of cards still fit.
        LinearLayout fav = SyntaxUi.chipRow(this);
        fav.addView(SyntaxUi.label(this, getString(R.string.syntax_scenes)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView favLabel = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.MUTED);
        favLabel.setText(R.string.favorites_settings);
        favLabel.setPadding(0, 0, SyntaxUi.dp(this, 10), 0);
        fav.addView(favLabel);
        for (int i = 0; i < FavoritesData.SECONDS.length; i++) {
            int sec = FavoritesData.SECONDS[i];
            secondChips[i] = small(SyntaxUi.chip(this, getString(R.string.favorites_seconds, sec)));
            secondChips[i].setOnClickListener(v -> {
                SyntaxPrefs.get(this).setFavoritesSeconds(sec);
                refresh();
            });
            SyntaxUi.addChip(fav, secondChips[i]);
        }
        fav.addView(new View(this), new LinearLayout.LayoutParams(SyntaxUi.dp(this, 14), 1));
        panelChip = small(SyntaxUi.chip(this, ""));
        panelChip.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setFavoritesPanel(!s.favoritesPanel());
            refresh();
        });
        SyntaxUi.addChip(fav, panelChip);
        column.addView(fav);
        String[] names = getResources().getStringArray(R.array.syntax_scene_names);
        LinearLayout row = null;
        for (int i = 0; i < SyntaxSettings.SCENES.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(0, 0, 0, SyntaxUi.dp(this, 10));
                column.addView(row);
            }
            row.addView(sceneCard(i, names[SyntaxSettings.ALL_SCENES.indexOf(SyntaxSettings.SCENES.get(i))]));
        }

        setContentView(outer);
        refresh();
        cards[0].requestFocus();
        loadFavoritePreview();
    }

    private View sceneCard(int index, String name) {
        String scene = SyntaxSettings.SCENES.get(index);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SyntaxUi.cardBackground(this, 16, SyntaxUi.CARD));
        int pad = SyntaxUi.dp(this, 8);
        card.setPadding(pad, pad, pad, SyntaxUi.dp(this, 12));
        SyntaxUi.focusable(card, 1.07f);
        FrameLayout frame = new FrameLayout(this);
        frame.setClipToOutline(true);
        frame.setBackground(SyntaxUi.rounded(this, 10, 0xFF000000, 0, 0));
        ImageView preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setImageResource(SyntaxActivity.scenePreview(scene));
        previews[index] = preview;
        frame.addView(preview, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        TextView tick = SyntaxUi.text(this, 18, R.string.font_medium, 0xFF06222A);
        tick.setText("✓");
        tick.setGravity(Gravity.CENTER);
        tick.setBackground(SyntaxUi.rounded(this, 14, SyntaxUi.ACCENT, 0, 0));
        int t = SyntaxUi.dp(this, 28);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(t, t, Gravity.TOP | Gravity.END);
        tl.setMargins(0, SyntaxUi.dp(this, 6), SyntaxUi.dp(this, 6), 0);
        frame.addView(tick, tl);
        ticks[index] = tick;
        int w = cardWidth();   // four in a row, inside the margins
        card.addView(frame, new LinearLayout.LayoutParams(w, w * 9 / 16));
        TextView label = SyntaxUi.text(this, 15, R.string.font_regular, SyntaxUi.TEXT);
        label.setText(name);
        label.setPadding(SyntaxUi.dp(this, 4), SyntaxUi.dp(this, 8), 0, 0);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setMaxWidth(w);
        card.addView(label);
        card.setOnClickListener(v -> toggle(scene));
        if (Config.SCENE_FAVORITES.equals(scene)) {
            // Hold OK: the slideshow full screen now, whatever the system screensaver is.
            card.setOnLongClickListener(v -> {
                startActivity(new android.content.Intent(this, FavoritesPreviewActivity.class));
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(SyntaxUi.dp(this, 16));
        card.setLayoutParams(lp);
        cards[index] = card;
        return card;
    }

    /** 140 dp, or less where four cards with their padding and gaps do not fit the width left by the margins. */
    private int cardWidth() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        int margin = ScreenMargin.x(this, ScreenMargin.percent(this));
        int room = m.widthPixels - 2 * (SyntaxUi.dp(this, 64) + margin);
        int perCard = room / 4 - SyntaxUi.dp(this, 16 + 16 + 4);   // card padding, gap, focus zoom
        return Math.max(SyntaxUi.dp(this, 96), Math.min(SyntaxUi.dp(this, 140), perCard));
    }

    private void toggle(String scene) {
        SyntaxSettings s = SyntaxPrefs.get(this);
        boolean on = !s.scenes().contains(scene);
        if (!s.setScene(scene, on)) {
            SyntaxDialog.message(this, getString(R.string.syntax_scenes), getString(R.string.syntax_one_scene));
            return;
        }
        refresh();
    }

    private void setMode(boolean aerial) {
        if (!ScreensaverMode.set(this, aerial)) {
            SyntaxDialog.message(this, getString(R.string.syntax_permission_title), getString(R.string.syntax_permission));
        }
        refresh();
    }

    /** Amazon Photos as the system screensaver; when Fire OS's screensaver app is disabled, how to turn it on. */
    private void setAmazon() {
        if (!ScreensaverMode.amazonAvailable(this)) {
            SyntaxDialog.message(this, getString(R.string.ss_amazon_photos), getString(R.string.ss_amazon_disabled));
            return;
        }
        if (!ScreensaverMode.setComponent(this, ScreensaverMode.AMAZON)) {
            SyntaxDialog.message(this, getString(R.string.syntax_permission_title), getString(R.string.syntax_permission));
        }
        refresh();
    }

    private void refresh() {
        boolean aerial = ScreensaverMode.isAerial(this);
        String current = ScreensaverMode.current(this);
        boolean syntax = current.equals(ScreensaverMode.syntax(this)) || current.startsWith(getPackageName() + "/");
        boolean amazon = ScreensaverMode.isAmazon(this);
        syntaxMode.setSelected(syntax);
        aerialMode.setSelected(aerial);
        aerialMode.setText(ScreensaverMode.aerialInstalled(this) ? getString(R.string.syntax_aerial)
                : getString(R.string.ss_aerial_install));
        amazonMode.setSelected(amazon);
        int minutes = ScreensaverMode.minutes(this);
        for (int i = 0; i < minuteChips.length; i++) minuteChips[i].setSelected(ScreensaverMode.MINUTES[i] == minutes);
        if (!ScreensaverMode.canWrite(this)) notice.setText(R.string.syntax_permission);
        else if (amazon && !ScreensaverMode.amazonAvailable(this)) notice.setText(R.string.ss_amazon_disabled);
        else if (!syntax && !aerial && !amazon) notice.setText(R.string.syntax_other_screensaver);
        else notice.setText("");
        notice.setVisibility(notice.getText().length() == 0 ? View.GONE : View.VISIBLE);
        if (idleClock != null) {
            boolean clock = SyntaxPrefs.get(this).homeIdleClock();
            idleClock.setText(getString(clock ? R.string.syntax_idle_clock_on : R.string.syntax_idle_clock_off));
            idleClock.setSelected(clock);
        }
        SyntaxSettings fs = SyntaxPrefs.get(this);
        for (int i = 0; i < secondChips.length; i++) secondChips[i].setSelected(FavoritesData.SECONDS[i] == fs.favoritesSeconds());
        boolean panelOn = fs.favoritesPanel();
        panelChip.setSelected(panelOn);
        panelChip.setText((panelOn ? "✓ " : "") + getString(R.string.favorites_panel));
        List<String> on = SyntaxPrefs.get(this).scenes();
        for (int i = 0; i < ticks.length; i++) {
            boolean ticked = on.contains(SyntaxSettings.SCENES.get(i));
            ticks[i].setVisibility(ticked ? View.VISIBLE : View.INVISIBLE);
            cards[i].setAlpha(ticked ? 1f : 0.55f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /** The "Preferite" card shows a favourite photo (or the newest), loaded off the main thread. */
    private void loadFavoritePreview() {
        int index = SyntaxSettings.SCENES.indexOf(Config.SCENE_FAVORITES);
        if (index < 0) return;
        new Thread(() -> {
            java.io.File[] photos = WallpaperLibrary.photos(this, false);
            WallpaperState state = WallpaperLibrary.load(this, false);
            java.io.File pick = null;
            for (java.io.File f : photos) {
                if (state.favorite(WallpaperLibrary.id(f))) {
                    pick = f;
                    break;
                }
            }
            if (pick == null && photos.length > 0) pick = photos[0];
            android.graphics.Bitmap b = pick == null ? null : WallpaperLibrary.thumbnail(this, pick);
            if (b != null) runOnUiThread(() -> {
                if (!isFinishing()) previews[index].setImageBitmap(b);
            });
        }, "favorites-preview").start();
    }

    private TextView small(TextView chip) {
        chip.setPadding(SyntaxUi.dp(this, 14), SyntaxUi.dp(this, 5), SyntaxUi.dp(this, 14), SyntaxUi.dp(this, 5));
        chip.setTextSize(13);
        return chip;
    }
}
