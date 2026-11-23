package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Syntax TV's recycling calendar: the switch for the box (kitchen panel and Home), the hours to put the bins out and
 * to take them back in, and one line per evening with a chip per bin. Every OK is saved at once.
 */
public final class SyntaxRecyclingActivity extends Activity {
    private TextView show, from, to, by;
    private final TextView[][] bins = new TextView[7][Recycling.BINS.size()];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        SyntaxUi.header(this, column, getString(R.string.recycling_title), getString(R.string.recycling_intro));

        LinearLayout top = SyntaxUi.chipRow(this);
        top.setPadding(0, SyntaxUi.dp(this, 20), 0, SyntaxUi.dp(this, 8));
        show = SyntaxUi.chip(this, "");
        show.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setKitchenRecycling(!s.kitchenRecycling());
            refresh();
        });
        SyntaxUi.addChip(top, show);
        from = SyntaxUi.chip(this, "");
        from.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setRecyclingHours(SyntaxSettings.next(Recycling.FROMS, s.recyclingFrom()), s.recyclingTo(), s.recyclingBy());
            refresh();
        });
        SyntaxUi.addChip(top, from);
        to = SyntaxUi.chip(this, "");
        to.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setRecyclingHours(s.recyclingFrom(), SyntaxSettings.next(Recycling.TOS, s.recyclingTo()), s.recyclingBy());
            refresh();
        });
        SyntaxUi.addChip(top, to);
        by = SyntaxUi.chip(this, "");
        by.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setRecyclingHours(s.recyclingFrom(), s.recyclingTo(), SyntaxSettings.next(Recycling.BYS, s.recyclingBy()));
            refresh();
        });
        SyntaxUi.addChip(top, by);
        column.addView(top);

        String[] days = getResources().getStringArray(R.array.recycling_day_names);
        String[] names = getResources().getStringArray(R.array.recycling_bins);
        for (int d = 0; d < 7; d++) {
            LinearLayout row = SyntaxUi.chipRow(this);
            row.setPadding(0, SyntaxUi.dp(this, 8), 0, 0);
            TextView day = SyntaxUi.text(this, 16, R.string.font_regular, SyntaxUi.MUTED);
            day.setText(days[d]);
            row.addView(day, new LinearLayout.LayoutParams(SyntaxUi.dp(this, 110), LinearLayout.LayoutParams.WRAP_CONTENT));
            for (int b = 0; b < Recycling.BINS.size(); b++) {
                int isoDay = d + 1;
                String bin = Recycling.BINS.get(b);
                TextView chip = SyntaxUi.chip(this, names[b]);
                chip.setTextSize(14);
                chip.setPadding(SyntaxUi.dp(this, 16), SyntaxUi.dp(this, 7), SyntaxUi.dp(this, 16), SyntaxUi.dp(this, 7));
                chip.setOnClickListener(v -> {
                    SyntaxSettings s = SyntaxPrefs.get(this);
                    s.setRecyclingWeek(Recycling.toggle(s.recyclingWeek(), isoDay, bin));
                    refresh();
                });
                SyntaxUi.addChip(row, chip);
                bins[d][b] = chip;
            }
            column.addView(row);
        }
        setContentView(outer);
        refresh();
        show.requestFocus();
    }

    private void refresh() {
        SyntaxSettings s = SyntaxPrefs.get(this);
        boolean on = s.kitchenRecycling();
        show.setText(on ? "✓ " + getString(R.string.recycling_show) : getString(R.string.recycling_show));
        show.setSelected(on);
        from.setText(getString(R.string.recycling_from, RecyclingBox.hhmm(s.recyclingFrom())));
        to.setText(getString(R.string.recycling_to, RecyclingBox.hhmm(s.recyclingTo())));
        by.setText(getString(R.string.recycling_by, RecyclingBox.hhmm(s.recyclingBy())));
        List<List<String>> week = Recycling.parse(s.recyclingWeek());
        String[] names = getResources().getStringArray(R.array.recycling_bins);
        for (int d = 0; d < 7; d++) {
            for (int b = 0; b < Recycling.BINS.size(); b++) {
                boolean set = week.get(d).contains(Recycling.BINS.get(b));
                bins[d][b].setText(set ? "✓ " + names[b] : names[b]);
                bins[d][b].setSelected(set);
                bins[d][b].setAlpha(set ? 1f : 0.7f);
            }
        }
    }
}
