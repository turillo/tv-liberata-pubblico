package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV, Natale: the Christmas mode's master switch (off by default, only ever on by hand) and a switch for each
 * part: Christmas wallpapers, snow and countdown and lights on Home, the Christmas scenes and snow on the
 * screensaver. Turning the master off brings everything back as it was; the parts' own choices are kept for next time.
 */
public final class SyntaxChristmasActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView on, off, only, status, wallpapers;
    private final Map<String, TextView> switches = new LinkedHashMap<>();
    private final Map<String, TextView> scenes = new LinkedHashMap<>();
    private LinearLayout parts;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.xmas_title), getString(R.string.xmas_intro));

        column.addView(SyntaxUi.label(this, getString(R.string.xmas_mode)));
        LinearLayout master = SyntaxUi.chipRow(this);
        on = SyntaxUi.chip(this, getString(R.string.syntax_on));
        off = SyntaxUi.chip(this, getString(R.string.syntax_off));
        on.setOnClickListener(v -> setMaster(true));
        off.setOnClickListener(v -> setMaster(false));
        SyntaxUi.addChip(master, on);
        SyntaxUi.addChip(master, off);
        column.addView(master);

        parts = new LinearLayout(this);
        parts.setOrientation(LinearLayout.VERTICAL);
        parts.setClipChildren(false);
        column.addView(parts);

        parts.addView(SyntaxUi.label(this, getString(R.string.xmas_home)));
        LinearLayout home = SyntaxUi.chipRow(this);
        // The Christmas wallpapers have their own grid (photos, interval, sources); its first button turns them on
        // or off for Home.
        wallpapers = small(SyntaxUi.chip(this, getString(R.string.xmas_wallpapers)));
        wallpapers.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxWallpaperActivity.class)
                .putExtra(SyntaxWallpaperActivity.EXTRA_XMAS, true)));
        SyntaxUi.addChip(home, wallpapers);
        toggle(home, SyntaxSettings.XMAS_SNOW_HOME, R.string.xmas_snow_home);
        toggle(home, SyntaxSettings.XMAS_COUNTDOWN, R.string.xmas_countdown);
        toggle(home, SyntaxSettings.XMAS_DOCK, R.string.xmas_dock);
        parts.addView(home);

        parts.addView(SyntaxUi.label(this, getString(R.string.xmas_scenes)));
        LinearLayout row = SyntaxUi.chipRow(this);
        String[] names = getResources().getStringArray(R.array.xmas_scene_names);
        for (int i = 0; i < Christmas.SCENES.size(); i++) {
            String scene = Christmas.SCENES.get(i);
            TextView chip = small(SyntaxUi.chip(this, names[i]));
            chip.setOnClickListener(v -> {
                SyntaxSettings s = SyntaxPrefs.get(this);
                s.setXmasScene(scene, !s.xmasScenes().contains(scene));
                refresh();
                prefetch();
            });
            // Hold OK: this scene now, once. The remote repeats OK while held; the scene starts on the release (a
            // release after the start would end the screensaver at once), and holding never ticks or unticks it.
            final boolean[] held = {false};
            chip.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        && keyCode != android.view.KeyEvent.KEYCODE_ENTER) return false;
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
                    held[0] = true;
                    v.setPressed(false);
                    return true;
                }
                if (event.getAction() == android.view.KeyEvent.ACTION_UP && held[0]) {
                    held[0] = false;
                    v.setPressed(false);
                    tryScene(scene);
                    return true;
                }
                return false;
            });
            scenes.put(scene, chip);
            SyntaxUi.addChip(row, chip);
        }
        parts.addView(row);
        LinearLayout more = SyntaxUi.chipRow(this);
        more.setPadding(0, SyntaxUi.dp(this, 12), 0, 0);
        only = small(SyntaxUi.chip(this, getString(R.string.xmas_only)));
        only.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setXmasOnly(!s.xmasOnly());
            refresh();
        });
        SyntaxUi.addChip(more, only);
        toggle(more, SyntaxSettings.XMAS_SNOW_DREAM, R.string.xmas_snow_dream);
        TextView test = small(SyntaxUi.chip(this, getString(R.string.syntax_try)));
        test.setOnClickListener(v -> {
            if (!ScreensaverMode.startNow(this)) {
                SyntaxDialog.message(this, getString(R.string.syntax_error), getString(R.string.syntax_try_failed));
            }
        });
        SyntaxUi.addChip(more, test);
        parts.addView(more);

        status = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        status.setPadding(0, SyntaxUi.dp(this, 18), 0, 0);
        column.addView(status);

        setContentView(outer);
        refresh();
        (SyntaxPrefs.get(this).christmas() ? on : off).requestFocus();
    }

    private TextView small(TextView chip) {
        chip.setTextSize(15);
        chip.setPadding(SyntaxUi.dp(this, 18), SyntaxUi.dp(this, 10), SyntaxUi.dp(this, 18), SyntaxUi.dp(this, 10));
        return chip;
    }

    private void toggle(LinearLayout row, String key, int label) {
        TextView chip = small(SyntaxUi.chip(this, getString(label)));
        chip.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setXmasSwitch(key, !s.xmasSwitch(key));
            refresh();
        });
        chip.setTag(label);
        switches.put(key, chip);
        SyntaxUi.addChip(row, chip);
    }

    private void tryScene(String scene) {
        if (Christmas.FAVORITES.equals(scene)) {
            // Shown in its own full-screen preview: the system screensaver may not be Syntax TV's.
            startActivity(new android.content.Intent(this, FavoritesPreviewActivity.class));
            return;
        }
        ClockDream.tryScene = scene;
        if (!ScreensaverMode.startNow(this)) {
            ClockDream.tryScene = null;
            SyntaxDialog.message(this, getString(R.string.syntax_error), getString(R.string.syntax_try_failed));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();   // back from the Christmas wallpapers' grid
    }

    private void setMaster(boolean value) {
        SyntaxPrefs.get(this).setChristmas(value);
        refresh();
        prefetch();
    }

    /** With the Christmas wallpapers on, the first photos come down now. */
    private void prefetch() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        if (s.xmas(SyntaxSettings.XMAS_WALLPAPERS)) {
            // The first Christmas photos now, not at Home's next check (up to 6 hours away).
            java.io.File archive = WallpaperLibrary.archive(this);
            worker.execute(() -> new WallpaperFetcher(this, archive).runChristmas());
        }
    }

    private void refresh() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        boolean master = s.christmas();
        on.setSelected(master);
        off.setSelected(!master);
        parts.setAlpha(master ? 1f : 0.5f);   // still editable, they wait for the master
        for (Map.Entry<String, TextView> e : switches.entrySet()) {
            boolean v = s.xmasSwitch(e.getKey());
            e.getValue().setSelected(v);
            e.getValue().setText((v ? "✓ " : "") + getString((Integer) e.getValue().getTag()));
        }
        List<String> ticked = s.xmasScenes();
        String[] names = getResources().getStringArray(R.array.xmas_scene_names);
        int i = 0;
        for (Map.Entry<String, TextView> e : scenes.entrySet()) {
            boolean v = ticked.contains(e.getKey());
            e.getValue().setSelected(v);
            e.getValue().setText((v ? "✓ " : "") + names[i++]);
        }
        boolean pics = s.xmasSwitch(SyntaxSettings.XMAS_WALLPAPERS);
        wallpapers.setSelected(pics);
        wallpapers.setText((pics ? "✓ " : "") + getString(R.string.xmas_wallpapers) + "  ›");
        only.setSelected(s.xmasOnly());
        only.setText((s.xmasOnly() ? "✓ " : "") + getString(R.string.xmas_only));
        if (status.getText().length() == 0 || !master) {
            status.setText(master ? R.string.xmas_status_on : R.string.xmas_status_off);
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
