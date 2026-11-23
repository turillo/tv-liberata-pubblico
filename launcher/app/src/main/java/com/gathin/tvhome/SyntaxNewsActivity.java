package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV's news screen: the ticker on or off, its speed and size, the ANSA sections with a tick each (Ultima ora
 * by default; one always stays), and a live preview of the band along the bottom, as the screensaver will show it.
 */
public final class SyntaxNewsActivity extends Activity {

    private static final String[] SPEEDS = {SyntaxSettings.SLOW, SyntaxSettings.MEDIUM, SyntaxSettings.FAST};
    private static final String[] SIZES = {SyntaxSettings.SMALL, SyntaxSettings.MEDIUM, SyntaxSettings.LARGE};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FrameLayout outer;
    private TextView on, off, homeOn, homeOff, status;
    private final TextView[] speeds = new TextView[3], sizes = new TextView[3];
    private final TextView[] sections = new TextView[NewsData.SECTIONS.size()];
    private NewsTickerView preview;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.syntax_news), null);

        column.addView(SyntaxUi.label(this, getString(R.string.syntax_ticker)));
        LinearLayout row = SyntaxUi.chipRow(this);
        on = SyntaxUi.chip(this, getString(R.string.syntax_on));
        off = SyntaxUi.chip(this, getString(R.string.syntax_off));
        on.setOnClickListener(v -> change(() -> SyntaxPrefs.get(this).setTicker(true)));
        off.setOnClickListener(v -> change(() -> SyntaxPrefs.get(this).setTicker(false)));
        SyntaxUi.addChip(row, on);
        SyntaxUi.addChip(row, off);
        spacer(row);
        addLabel(row, R.string.syntax_speed);
        int[] speedNames = {R.string.syntax_slow, R.string.syntax_medium, R.string.syntax_fast};
        for (int i = 0; i < 3; i++) {
            String value = SPEEDS[i];
            speeds[i] = SyntaxUi.chip(this, getString(speedNames[i]));
            speeds[i].setOnClickListener(v -> change(() -> SyntaxPrefs.get(this).setTickerSpeed(value)));
            SyntaxUi.addChip(row, speeds[i]);
        }
        column.addView(row);

        LinearLayout sizeRow = SyntaxUi.chipRow(this);
        sizeRow.setPadding(0, SyntaxUi.dp(this, 14), 0, 0);
        addLabel(sizeRow, R.string.syntax_size);
        int[] sizeNames = {R.string.syntax_small, R.string.syntax_medium_f, R.string.syntax_large};
        for (int i = 0; i < 3; i++) {
            String value = SIZES[i];
            sizes[i] = SyntaxUi.chip(this, getString(sizeNames[i]));
            sizes[i].setOnClickListener(v -> change(() -> SyntaxPrefs.get(this).setTickerSize(value)));
            SyntaxUi.addChip(sizeRow, sizes[i]);
        }
        column.addView(sizeRow);

        LinearLayout homeRow = SyntaxUi.chipRow(this);
        homeRow.setPadding(0, SyntaxUi.dp(this, 14), 0, 0);
        addLabel(homeRow, R.string.syntax_home_ticker);
        homeOn = SyntaxUi.chip(this, getString(R.string.syntax_on));
        homeOff = SyntaxUi.chip(this, getString(R.string.syntax_off));
        homeOn.setOnClickListener(v -> change(() -> SyntaxPrefs.get(this).setHomeTicker(true)));
        homeOff.setOnClickListener(v -> change(() -> SyntaxPrefs.get(this).setHomeTicker(false)));
        SyntaxUi.addChip(homeRow, homeOn);
        SyntaxUi.addChip(homeRow, homeOff);
        column.addView(homeRow);

        column.addView(SyntaxUi.label(this, getString(R.string.syntax_sections)));
        LinearLayout sectionRow = null;
        for (int i = 0; i < sections.length; i++) {
            if (i % 6 == 0) {
                sectionRow = SyntaxUi.chipRow(this);
                sectionRow.setPadding(0, 0, 0, SyntaxUi.dp(this, 12));
                column.addView(sectionRow);
            }
            NewsData.Section section = NewsData.SECTIONS.get(i);
            sections[i] = SyntaxUi.chip(this, section.label);
            sections[i].setOnClickListener(v -> toggleSection(section.key));
            SyntaxUi.addChip(sectionRow, sections[i]);
        }
        status = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        status.setPadding(0, SyntaxUi.dp(this, 4), 0, 0);
        column.addView(status);
        setContentView(outer);
        refresh();
        on.requestFocus();
    }

    private void spacer(LinearLayout row) {
        row.addView(new android.view.View(this), new LinearLayout.LayoutParams(SyntaxUi.dp(this, 18), 1));
    }

    private void addLabel(LinearLayout row, int text) {
        TextView t = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.MUTED);
        t.setText(text);
        t.setPadding(0, 0, SyntaxUi.dp(this, 14), 0);
        row.addView(t);
    }

    private void change(Runnable save) {
        save.run();
        refresh();
    }

    private void toggleSection(String key) {
        SyntaxSettings s = SyntaxPrefs.get(this);
        if (!s.setSection(key, !s.sections().contains(key))) {
            SyntaxDialog.message(this, getString(R.string.syntax_sections), getString(R.string.syntax_one_section));
            return;
        }
        refresh();
        fetch();
    }

    /** The ticked sections' headlines now, so the preview shows them (the screensaver refreshes them too). */
    private void fetch() {
        List<String> chosen = SyntaxPrefs.get(this).sections();
        status.setText(R.string.syntax_news_loading);
        worker.execute(() -> {
            List<NewsData.Headline> result = NewsTicker.fetch(this, chosen, NewsTicker.cached(this, chosen));
            handler.post(() -> {
                if (isFinishing()) return;
                if (result == null) status.setText(R.string.syntax_news_offline);
                else status.setText("");
                showPreview();
            });
        });
    }

    private void refresh() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        on.setSelected(s.ticker());
        off.setSelected(!s.ticker());
        homeOn.setSelected(s.homeTicker());
        homeOff.setSelected(!s.homeTicker());
        for (int i = 0; i < 3; i++) {
            speeds[i].setSelected(SPEEDS[i].equals(s.tickerSpeed()));
            sizes[i].setSelected(SIZES[i].equals(s.tickerSize()));
        }
        List<String> chosen = s.sections();
        for (int i = 0; i < sections.length; i++) {
            boolean ticked = chosen.contains(NewsData.SECTIONS.get(i).key);
            sections[i].setSelected(ticked);
            sections[i].setText((ticked ? "✓ " : "") + NewsData.SECTIONS.get(i).label);
        }
        showPreview();
    }

    /** The band as the screensaver will draw it, along the bottom; hidden while the ticker is off. */
    private void showPreview() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        if (preview != null) {
            preview.stopScrolling();
            outer.removeView(preview);
            preview = null;
        }
        if (!s.ticker() && !s.homeTicker()) return;   // the band is off everywhere
        preview = new NewsTickerView(this, NewsTicker.label(this, s.sections()), s.tickerSpeedDp(),
                SyntaxSettings.bandFraction(s.tickerSize()));
        List<String[]> items = NewsTicker.items(this, NewsTicker.cached(this, s.sections()), s.sections());
        if (items.isEmpty()) {
            items.add(new String[]{"", getString(R.string.syntax_news_empty)});
        }
        preview.setHeadlines(items);
        outer.addView(preview, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, preview.bandHeight(),
                Gravity.BOTTOM));
        preview.startScrolling();
    }

    @Override
    protected void onPause() {
        if (preview != null) preview.stopScrolling();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview != null) preview.startScrolling();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
