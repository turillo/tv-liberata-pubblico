package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Impostazioni TV Liberata, Sistema, Filtro: the DNS filter on or off (Android asks for the VPN consent the first
 * time), Fire OS updates blocked or not, what was blocked lately (OK on one: let it through), the exceptions (OK on
 * one: block it again), a domain typed in as an exception, and the counts.
 */
public final class SyntaxFilterActivity extends Activity {

    private static final int CONSENT = 7;
    private TextView onChip, otaChip, stats;
    private LinearLayout recentBox, exceptionBox;
    private EditText input;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.filter_title), getString(R.string.filter_intro));

        LinearLayout row = SyntaxUi.chipRow(this);
        onChip = SyntaxUi.chip(this, "");
        onChip.setOnClickListener(v -> toggle());
        SyntaxUi.addChip(row, onChip);
        otaChip = SyntaxUi.chip(this, "");
        otaChip.setOnClickListener(v -> {
            FilterSettings.setOta(this, !FilterSettings.ota(this));
            refresh();
        });
        if (Device.isFireOs()) SyntaxUi.addChip(row, otaChip);   // Fire OS updates: Fire TV only
        TextView reset = SyntaxUi.chip(this, getString(R.string.filter_reset));
        reset.setOnClickListener(v -> {
            FilterSettings.resetStats(this);
            refresh();
        });
        SyntaxUi.addChip(row, reset);
        SyntaxUi.toggle(onChip);
        SyntaxUi.toggle(otaChip);
        column.addView(row);

        stats = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        stats.setPadding(0, SyntaxUi.dp(this, 10), 0, SyntaxUi.dp(this, 4));
        column.addView(stats);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        int pad = SyntaxUi.dp(this, 6);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);
        column.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        body.addView(SyntaxUi.label(this, getString(R.string.filter_recent)));
        recentBox = new LinearLayout(this);
        recentBox.setOrientation(LinearLayout.VERTICAL);
        recentBox.setClipChildren(false);
        body.addView(recentBox);

        body.addView(SyntaxUi.label(this, getString(R.string.filter_exceptions)));
        exceptionBox = new LinearLayout(this);
        exceptionBox.setOrientation(LinearLayout.VERTICAL);
        exceptionBox.setClipChildren(false);
        body.addView(exceptionBox);
        LinearLayout add = SyntaxUi.chipRow(this);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setHint(R.string.filter_add_hint);
        input.setTextColor(SyntaxUi.TEXT);
        input.setHintTextColor(SyntaxUi.MUTED);
        input.setMinWidth(SyntaxUi.dp(this, 320));
        input.setOnEditorActionListener((v, a, e) -> {
            addTyped();
            return true;
        });
        add.addView(input);
        TextView addChip = SyntaxUi.chip(this, getString(R.string.filter_add));
        addChip.setOnClickListener(v -> addTyped());
        SyntaxUi.addChip(add, addChip);
        body.addView(add);

        setContentView(outer);
        onChip.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void toggle() {
        if (FilterSettings.on(this)) {
            FilterSettings.set(this, false);
            refresh();
            return;
        }
        Intent consent = FilterSettings.consent(this);
        if (consent != null) {
            try {
                startActivityForResult(consent, CONSENT);
                return;
            } catch (RuntimeException e) {
                SyntaxDialog.message(this, getString(R.string.filter_title), getString(R.string.filter_no_consent));
                return;
            }
        }
        FilterSettings.set(this, true);
        onChip.postDelayed(this::refresh, 800);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONSENT) return;
        if (result == RESULT_OK) FilterSettings.set(this, true);
        else SyntaxDialog.message(this, getString(R.string.filter_title), getString(R.string.filter_no_consent));
        onChip.postDelayed(this::refresh, 800);
    }

    private void addTyped() {
        String d = DnsFilter.domain(input.getText().toString());
        if (d == null) return;
        FilterSettings.setException(this, d, true);
        input.setText("");
        refresh();
    }

    private void refresh() {
        boolean on = FilterSettings.on(this);
        onChip.setSelected(on);
        onChip.setText(getString(on ? R.string.filter_on : R.string.filter_off));
        boolean ota = FilterSettings.ota(this);
        otaChip.setSelected(ota);
        otaChip.setText((ota ? "✓ " : "") + getString(R.string.filter_ota));

        SharedPreferences p = FilterSettings.prefs(this);
        long blocked = p.getLong("blocked", 0), total = p.getLong("total", 0), since = p.getLong("since", 0);
        StringBuilder s = new StringBuilder();
        s.append(on && !DnsVpnService.isRunning() ? getString(R.string.filter_starting) + " · " : "");
        s.append(getString(R.string.filter_counts, blocked, total));
        if (since > 0) s.append(" · ").append(getString(R.string.filter_since, DateFormat.getDateInstance().format(new Date(since))));
        long listed = p.getLong("lists_updated", 0);
        s.append(" · ").append(listed > 0 ? getString(R.string.filter_lists_updated, DateFormat.getDateInstance().format(new Date(listed)))
                : getString(R.string.filter_lists_bundled));
        Map<String, Integer> top = FilterSettings.parseTop(p.getString("top", ""));
        if (!top.isEmpty()) {
            s.append('\n').append(getString(R.string.filter_top)).append(' ');
            int i = 0;
            for (Map.Entry<String, Integer> e : top.entrySet()) {
                if (i++ == 5) break;
                s.append(i > 1 ? ", " : "").append(e.getKey()).append(" (").append(e.getValue()).append(')');
            }
        }
        stats.setText(s);

        fill(recentBox, FilterSettings.recent(this), true);
        fill(exceptionBox, FilterSettings.exceptions(this), false);
    }

    /** Chips, three a row: blocked ones let through on OK, exceptions blocked again on OK (after a question). */
    private void fill(LinearLayout box, List<String> domains, boolean blocked) {
        box.removeAllViews();
        if (domains.isEmpty()) {
            TextView none = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
            none.setText(blocked ? R.string.filter_none_recent : R.string.filter_none_exceptions);
            box.addView(none);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < Math.min(domains.size(), 24); i++) {
            if (i % 3 == 0) {
                row = SyntaxUi.chipRow(this);
                row.setPadding(0, 0, 0, SyntaxUi.dp(this, 8));
                box.addView(row);
            }
            String d = domains.get(i);
            TextView chip = SyntaxUi.chip(this, d);
            chip.setTextSize(14);
            chip.setOnClickListener(v -> SyntaxDialog.show(this, d,
                    getString(blocked ? R.string.filter_allow_question : R.string.filter_block_question),
                    new String[]{getString(R.string.syntax_yes), getString(R.string.syntax_no)}, 1, which -> {
                        if (which != 0) return;
                        FilterSettings.setException(this, d, blocked);
                        refresh();
                    }));
            SyntaxUi.addChip(row, chip);
        }
    }
}
