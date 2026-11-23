package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sistema, Servizi: every online source with its last success, its last error (time and short reason) and its
 * state, and "Riprova" to try one again now. The list only reads what the download code recorded.
 */
public final class SyntaxServicesActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> retrying = new HashSet<>();
    private final TextView[] lines = new TextView[ServiceStatus.SOURCES.size()];
    private final View[] dots = new View[ServiceStatus.SOURCES.size()];
    private final TextView[] states = new TextView[ServiceStatus.SOURCES.size()];
    private final TextView[] buttons = new TextView[ServiceStatus.SOURCES.size()];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        TextView title = SyntaxUi.text(this, 30, R.string.font_medium, SyntaxUi.TEXT);
        title.setText("Servizi");
        column.addView(title);
        TextView intro = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        intro.setText("Ogni sorgente si aggiorna quando serve (Home, screensaver, scena scelta): "
                + "\"in ritardo\" vuol dire solo che non è stata usata di recente.");
        intro.setPadding(0, SyntaxUi.dp(this, 6), 0, SyntaxUi.dp(this, 10));
        column.addView(intro);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        int sp = SyntaxUi.dp(this, 6);
        scroll.setPadding(sp, sp, sp, sp);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        for (int i = 0; i < ServiceStatus.SOURCES.size(); i++) list.addView(row(i));
        setContentView(outer);
        buttons[0].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View row(int i) {
        ServiceStatus.Source s = ServiceStatus.SOURCES.get(i);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setClipChildren(false);
        r.setBackground(SyntaxUi.rounded(this, 14, SyntaxUi.CARD, 1, SyntaxUi.CARD_EDGE));
        int pad = SyntaxUi.dp(this, 12);
        r.setPadding(SyntaxUi.dp(this, 16), SyntaxUi.dp(this, 8), pad, SyntaxUi.dp(this, 8));

        View dot = new View(this);
        int d = SyntaxUi.dp(this, 10);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(d, d);
        dl.setMarginEnd(SyntaxUi.dp(this, 14));
        r.addView(dot, dl);
        dots[i] = dot;

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = SyntaxUi.text(this, 15, R.string.font_medium, SyntaxUi.TEXT);
        name.setText(s.name);
        head.addView(name);
        TextView use = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        use.setText("  ·  " + s.use + "  ·  ");
        head.addView(use);
        TextView st = SyntaxUi.text(this, 13, R.string.font_regular, SyntaxUi.MUTED);
        head.addView(st);
        states[i] = st;
        texts.addView(head);
        TextView line = SyntaxUi.text(this, 12, R.string.font_light, SyntaxUi.MUTED);
        line.setPadding(0, SyntaxUi.dp(this, 4), 0, 0);
        line.setSingleLine(true);
        line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(line);
        lines[i] = line;
        r.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView retry = SyntaxUi.chip(this, "Riprova");
        retry.setTextSize(14);
        retry.setPadding(SyntaxUi.dp(this, 18), SyntaxUi.dp(this, 8), SyntaxUi.dp(this, 18), SyntaxUi.dp(this, 8));
        retry.setOnClickListener(v -> retry(s.id));
        r.addView(retry);
        buttons[i] = retry;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = SyntaxUi.dp(this, 8);
        r.setLayoutParams(lp);
        return r;
    }

    private void retry(String id) {
        if (!retrying.add(id)) return;
        refresh();
        worker.execute(() -> {
            ServiceStatus.retry(this, id);
            handler.post(() -> {
                retrying.remove(id);
                if (!isFinishing()) refresh();
            });
        });
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        ServiceHealth.Usage usage = ServiceStatus.usage(this);
        for (int i = 0; i < ServiceStatus.SOURCES.size(); i++) {
            ServiceStatus.Source s = ServiceStatus.SOURCES.get(i);
            ServiceStatus.Entry e = ServiceStatus.get(this, s.id);
            int state = e.state(s, now);
            boolean busy = retrying.contains(s.id);
            // A source the TV does not use now says why, in grey; a real error stays red.
            String idle = state == ServiceHealth.ERROR ? null : ServiceHealth.idleReason(s.id, usage);
            int shown = idle != null ? ServiceHealth.UNKNOWN : state;
            states[i].setText(busy ? "nuovo tentativo…" : idle != null ? idle : ServiceHealth.label(state));
            states[i].setTextColor(color(shown));
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(color(shown));
            dots[i].setBackground(dot);
            String line = ServiceStatus.describe(e, s, now, zone);
            if (idle != null && e.lastOk <= 0 && e.lastErr <= 0) line = "nessun uso finora";
            lines[i].setText(line);
            buttons[i].setText(busy ? "…" : "Riprova");
        }
    }

    private static int color(int state) {
        switch (state) {
            case ServiceHealth.OK: return 0xFF4CD787;
            case ServiceHealth.LATE: return SyntaxUi.STAR;
            case ServiceHealth.ERROR: return SyntaxUi.DANGER;
            default: return 0x66FFFFFF;
        }
    }
}
