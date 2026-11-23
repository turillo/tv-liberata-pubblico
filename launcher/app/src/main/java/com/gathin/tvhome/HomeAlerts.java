package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home's discreet system alert: a small pill in the bottom-right corner that fades in, stays a few seconds and goes
 * by itself; never focusable, never a notification. Checked once a minute on Home's own tick, off the main thread,
 * and only while Syntax TV's "Avvisi di sistema" is on. See {@link SystemAlerts} for when.
 */
final class HomeAlerts {

    private static final long SHOW_MS = 9000;
    private static final String PREFS = "system_alerts";

    private final Activity activity;
    private final FrameLayout root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SystemAlerts.RamWatch ram = new SystemAlerts.RamWatch();
    private TextView pill;
    private boolean checking, closed;

    HomeAlerts(Activity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
    }

    /** One check: Home's minute tick. Cheap (a StatFs, a memory reading, a few preferences). */
    void check() {
        if (closed || checking || pill != null) return;
        checking = true;
        Context c = activity.getApplicationContext();
        try {
            worker.execute(() -> {
                String message = null;
                try {
                    message = next(c);
                } catch (RuntimeException ignored) {
                    // no alert this minute
                }
                String text = message;
                root.post(() -> {
                    checking = false;
                    if (text != null && !closed && !activity.isFinishing()) show(text);
                });
            });
        } catch (RuntimeException e) {
            checking = false;   // the worker was shut down
        }
    }

    /** The alert due now, if any, and remembers that it was shown. */
    private String next(Context c) {
        long[] r = SystemProbe.ram(c);
        ram.add(r[1], r[0]);
        if (!SyntaxPrefs.get(c).systemAlerts()) return null;
        long now = System.currentTimeMillis();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long[] d = SystemProbe.disk();
        if (SystemAlerts.low(d[1], d[0]) && SystemAlerts.due(p.getLong("disk", 0), now)) {
            p.edit().putLong("disk", now).apply();
            return "Spazio quasi esaurito: liberi " + SystemStats.bytes(d[1]) + " · TV Liberata › Sistema › Pulizia";
        }
        if (ram.low() && SystemAlerts.due(p.getLong("ram", 0), now)) {
            p.edit().putLong("ram", now).apply();
            return "Memoria quasi piena da alcuni minuti (" + SystemStats.bytes(r[1]) + " libera)";
        }
        List<String> failing = ServiceStatus.failingLong(c, now);
        if (!failing.isEmpty() && SystemAlerts.due(p.getLong("service", 0), now)) {
            p.edit().putLong("service", now).apply();
            String names = failing.size() == 1 ? failing.get(0) : failing.get(0) + " e altre " + (failing.size() - 1);
            return names + " non risponde da oltre un giorno · TV Liberata › Sistema › Servizi";
        }
        return null;
    }

    private void show(String text) {
        Context c = activity;
        TextView t = SyntaxUi.text(c, 13, R.string.font_regular, SyntaxUi.TEXT);
        t.setText(text);
        t.setMaxWidth(SyntaxUi.dp(c, 420));
        t.setBackground(SyntaxUi.rounded(c, 18, 0xE6141821, 1, 0x55FFC14D));
        int h = SyntaxUi.dp(c, 16), v = SyntaxUi.dp(c, 10);
        t.setPadding(h, v, h, v);
        t.setFocusable(false);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, SyntaxUi.dp(c, 28), SyntaxUi.dp(c, 64));   // above the news band
        t.setAlpha(0f);
        root.addView(t, lp);
        pill = t;
        t.animate().alpha(1f).setDuration(400).start();
        t.postDelayed(this::hide, SHOW_MS);
    }

    void hide() {
        TextView t = pill;
        if (t == null) return;
        pill = null;
        t.animate().alpha(0f).setDuration(400).withEndAction(() -> root.removeView(t)).start();
    }

    void close() {
        closed = true;
        hide();
        worker.shutdownNow();
    }
}
