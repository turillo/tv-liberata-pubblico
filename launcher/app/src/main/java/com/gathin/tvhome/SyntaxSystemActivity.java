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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Syntax TV's "Sistema" page: memory with a two-minute chart, CPU (load, or core frequencies and temperatures when
 * the load is hidden), storage with what Syntax TV's own data takes, the network with latency and an on-demand
 * speed test, and the device. Readings every 2 s only while the page is in front; nothing runs once it is closed.
 * Only what the device exposes is shown.
 */
public final class SyntaxSystemActivity extends Activity {

    private static final long TICK_MS = 2000, NET_MS = 10_000, USAGE_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** RAM and CPU: quick reads. */
    private final ExecutorService fast = Executors.newSingleThreadExecutor();
    /** Network, latency, folder sizes, speed test: slow ones, so they never hold up the 2-s readings. */
    private final ExecutorService slow = Executors.newSingleThreadExecutor();
    private final SystemStats.History ramHistory = new SystemStats.History(60);   // 2 minutes at 2 s

    private boolean running, slowBusy, speedBusy;
    private long lastNet, lastUsage;
    private long[] cpuBefore;
    private String speedText = "–";

    private LinearLayout ramBox, cpuBox, diskBox, netBox, infoBox;
    private SparkView ramChart;
    private TextView speedChip;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            readFast();
            long now = System.currentTimeMillis();
            if (!slowBusy && (now - lastNet >= NET_MS || now - lastUsage >= USAGE_MS)) readSlow(now);
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.page(this, outer);
        column.setClipChildren(false);

        LinearLayout top = SyntaxUi.chipRow(this);
        TextView title = SyntaxUi.text(this, 30, R.string.font_medium, SyntaxUi.TEXT);
        title.setText(R.string.syntax_system);
        top.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        speedChip = SyntaxUi.chip(this, "Test velocità");
        speedChip.setOnClickListener(v -> speedTest());
        addChips(top);
        SyntaxUi.addChip(top, speedChip);
        column.addView(top);
        // TV Liberata: the weather's place and the updates, on a second line.
        LinearLayout more = SyntaxUi.chipRow(this);
        more.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
        TextView filter = SyntaxUi.chip(this, getString(R.string.filter_title));
        filter.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxFilterActivity.class)));
        SyntaxUi.addChip(more, filter);
        TextView catalog = SyntaxUi.chip(this, getString(R.string.catalog_title));
        catalog.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxCatalogActivity.class)));
        SyntaxUi.addChip(more, catalog);
        TextView sysApps = SyntaxUi.chip(this, getString(R.string.sysapps_title));
        sysApps.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxSystemAppsActivity.class)));
        SyntaxUi.addChip(more, sysApps);
        if (Place.configured(this) == null) {
            TextView place = SyntaxUi.chip(this, getString(R.string.place_title));
            place.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxPlaceActivity.class)));
            SyntaxUi.addChip(more, place);
        }
        addUpdateChip(more);
        TextView setup = SyntaxUi.chip(this, getString(R.string.setup_title));
        setup.setOnClickListener(v -> startActivity(new android.content.Intent(this, SetupActivity.class)));
        SyntaxUi.addChip(more, setup);
        if (more.getChildCount() > 0) column.addView(more);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setPadding(0, SyntaxUi.dp(this, 16), 0, 0);
        column.addView(cols, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout c1 = col(cols), c2 = col(cols), c3 = col(cols);
        ramBox = box(c1, "Memoria");
        ramChart = new SparkView(this);
        infoBox = box(c1, "Info");   // under Memoria: with Dispositivo and Canale it no longer fits under Rete
        diskBox = box(c2, "Spazio");
        netBox = box(c3, "Rete");
        cpuBox = box(c3, "Processore");
        setContentView(outer);
        if (top.getChildCount() > 1) top.getChildAt(1).requestFocus();
        fillInfo();
    }

    /** "Cerca aggiornamenti" (TV Liberata, which updates from its GitHub Releases). */
    private void addUpdateChip(LinearLayout row) {
        if (!Updater.enabled()) return;
        TextView check = SyntaxUi.chip(this, getString(R.string.update_check));
        check.setOnClickListener(v -> Updater.checkNow(this, check));
        SyntaxUi.addChip(row, check);
    }

    /** The page's other buttons, before "Test velocità" (Pulizia, Servizi, Avvisi). */
    private void addChips(LinearLayout row) {
        TextView cleanup = SyntaxUi.chip(this, "Pulizia");
        cleanup.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxCleanupActivity.class)));
        SyntaxUi.addChip(row, cleanup);
        TextView services = SyntaxUi.chip(this, "Servizi");
        services.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxServicesActivity.class)));
        SyntaxUi.addChip(row, services);
        alerts = SyntaxUi.chip(this, "");
        alerts.setOnClickListener(v -> {
            SyntaxSettings s = SyntaxPrefs.get(this);
            s.setSystemAlerts(!s.systemAlerts());
            showAlertsChip();
        });
        SyntaxUi.addChip(row, alerts);
        showAlertsChip();
        // Margini schermo: this TV's overscan margin, set on its own screen with a frame to see it.
        marginChip = SyntaxUi.chip(this, getString(R.string.margin_chip, ScreenMargin.percent(this)));
        marginChip.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxMarginActivity.class)));
        if (Device.fireExtras()) SyntaxUi.addChip(row, marginChip);   // Fire TV only
        TextView hiddenApps = SyntaxUi.chip(this, getString(R.string.hidden_title));
        hiddenApps.setOnClickListener(v -> startActivity(new android.content.Intent(this, SyntaxHiddenAppsActivity.class)));
        if (Device.fireExtras()) SyntaxUi.addChip(row, hiddenApps);   // Fire TV only
    }

    private TextView marginChip;
    private int shownMargin = -1;

    private TextView alerts;

    private void showAlertsChip() {
        boolean on = SyntaxPrefs.get(this).systemAlerts();
        alerts.setText(on ? "Avvisi di sistema: accesi" : "Avvisi di sistema: spenti");
        alerts.setSelected(on);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from Margini schermo with a new margin: this page's own edges move too.
        int margin = ScreenMargin.percent(this);
        if (shownMargin >= 0 && shownMargin != margin) {
            recreate();
            return;
        }
        shownMargin = margin;
        running = true;
        lastNet = lastUsage = 0;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        running = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        fast.shutdownNow();
        slow.shutdownNow();
        super.onDestroy();
    }

    // ---- readings ------------------------------------------------------------------------------------------

    private void readFast() {
        fast.execute(() -> {
            long[] ram = SystemProbe.ram(this);
            long[] cpu = SystemProbe.cpuSample();
            int load = SystemStats.cpuLoad(cpuBefore, cpu);
            if (cpu != null) cpuBefore = cpu;
            int[] freqs = load >= 0 ? new int[0] : SystemProbe.coreFreqs();
            int max = load >= 0 ? -1 : SystemProbe.maxFreq();
            List<SystemProbe.Temp> temps = SystemProbe.temps();
            if (ram[0] > 0) ramHistory.add((float) (ram[0] - ram[1]) / ram[0]);
            float[] chart = ramHistory.snapshot();
            boolean first = cpu != null && load < 0;   // /proc/stat readable: the load shows from the next tick
            handler.post(() -> {
                if (isFinishing()) return;
                showRam(ram, chart);
                showCpu(load, first, freqs, max, temps);
            });
        });
    }

    private void readSlow(long now) {
        boolean net = now - lastNet >= NET_MS, usage = now - lastUsage >= USAGE_MS;
        if (net) lastNet = now;
        if (usage) lastUsage = now;
        slowBusy = true;
        slow.execute(() -> {
            SystemProbe.Net n = net ? SystemProbe.net(this) : null;
            float router = n == null ? -2 : SystemProbe.latency(n.gateway, 53);
            float internet = n == null ? -2 : n.type == null ? -1 : SystemProbe.latency("1.1.1.1", 443);
            long[] disk = usage ? SystemProbe.disk() : null;
            SystemProbe.Usage u = usage ? SystemProbe.usage(this) : null;
            handler.post(() -> {
                slowBusy = false;
                if (isFinishing()) return;
                if (n != null) showNet(n, router, internet);
                if (disk != null) showDisk(disk, u);
            });
        });
    }

    private void speedTest() {
        if (speedBusy) return;
        speedBusy = true;
        speedText = "in corso…";
        speedChip.setText("Test in corso…");
        refreshSpeedRow();
        slow.execute(() -> {
            double mbps = SystemProbe.speedTest();
            handler.post(() -> {
                speedBusy = false;
                if (isFinishing()) return;
                speedText = mbps < 0 ? "Test velocità non disponibile" : String.format(Locale.ITALY, "%.1f Mbit/s", mbps);
                speedChip.setText("Test velocità");
                refreshSpeedRow();
            });
        });
    }

    // ---- boxes ---------------------------------------------------------------------------------------------

    private void showRam(long[] ram, float[] chart) {
        ramBox.removeViews(1, ramBox.getChildCount() - 1);
        if (ram[0] <= 0) {
            row(ramBox, "Stato", "non disponibile");
            return;
        }
        long used = ram[0] - ram[1];
        row(ramBox, "Usata", SystemStats.bytes(used) + " (" + SystemStats.percent(used, ram[0]) + "%)");
        row(ramBox, "Libera", SystemStats.bytes(ram[1]));
        row(ramBox, "Totale", SystemStats.bytes(ram[0]));
        ramChart.set(chart, ramHistory.capacity());
        if (ramChart.getParent() == null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    SyntaxUi.dp(this, 40));
            lp.topMargin = SyntaxUi.dp(this, 6);
            ramBox.addView(ramChart, lp);
        } else {
            ramBox.addView(ramChart);
        }
        TextView note = small("RAM usata, ultimi 2 minuti");
        ramBox.addView(note);
    }

    private void showCpu(int load, boolean first, int[] freqs, int max, List<SystemProbe.Temp> temps) {
        cpuBox.removeViews(1, cpuBox.getChildCount() - 1);
        int cores = Runtime.getRuntime().availableProcessors();
        row(cpuBox, "Core", String.valueOf(cores));
        boolean any = false;
        if (load >= 0) {
            row(cpuBox, "Carico", load + "%");
            any = true;
        } else if (first) {
            row(cpuBox, "Carico", "…");
            any = true;
        }
        if (freqs.length > 0) {
            int lo = Integer.MAX_VALUE, hi = 0;
            for (int f : freqs) {
                if (f <= 0) continue;
                lo = Math.min(lo, f);
                hi = Math.max(hi, f);
            }
            String range = lo == hi ? lo + " MHz" : lo + "–" + hi + " MHz";
            row(cpuBox, "Frequenza", max > 0 ? range + " (max " + max + ")" : range);
            any = true;
        }
        int shown = 0;
        for (SystemProbe.Temp t : temps) {
            if (shown++ >= 3) break;
            row(cpuBox, shortName(t.name), String.format(Locale.ITALY, "%.0f °C", t.celsius));
            any = true;
        }
        if (!any) cpuBox.addView(small("Il dispositivo non espone carico, frequenze né temperature."));
    }

    private void showDisk(long[] disk, SystemProbe.Usage u) {
        diskBox.removeViews(1, diskBox.getChildCount() - 1);
        if (disk[0] > 0) {
            row(diskBox, "Totale", SystemStats.bytes(disk[0]));
            row(diskBox, "Libero", SystemStats.bytes(disk[1]) + " (" + SystemStats.percent(disk[1], disk[0]) + "%)");
        } else {
            row(diskBox, "Stato", "non disponibile");
        }
        TextView sub = SyntaxUi.label(this, "Dati di TV Liberata");
        sub.setPadding(0, SyntaxUi.dp(this, 12), 0, SyntaxUi.dp(this, 6));
        diskBox.addView(sub);
        row(diskBox, "Archivio sfondi", SystemStats.bytes(u.archive));
        row(diskBox, "Sfondi natalizi", SystemStats.bytes(u.xmasArchive));
        row(diskBox, "Immagini scene", SystemStats.bytes(u.scenes));
        row(diskBox, "Notizie", SystemStats.bytes(u.news));
        row(diskBox, "Miniature", SystemStats.bytes(u.thumbs));
        row(diskBox, "Totale", SystemStats.bytes(u.total()));
    }

    private String netRouter = "–", netInternet = "–";
    private SystemProbe.Net lastNetInfo;

    private void showNet(SystemProbe.Net n, float router, float internet) {
        lastNetInfo = n;
        netRouter = ms(router);
        netInternet = ms(internet);
        drawNet();
    }

    private void drawNet() {
        netBox.removeViews(1, netBox.getChildCount() - 1);
        SystemProbe.Net n = lastNetInfo;
        if (n == null || n.type == null) {
            row(netBox, "Stato", "non connesso");
            row(netBox, "Velocità", speedText);
            return;
        }
        row(netBox, "Tipo", n.vpn ? n.type + " · VPN attiva" : n.type);
        if (n.ssid != null) row(netBox, "Rete", n.ssid);
        String label = SystemStats.signalLabel(n.rssi);
        if (label != null) row(netBox, "Segnale", n.rssi + " dBm · " + label);
        if (n.ip != null) row(netBox, "IP", n.ip);
        row(netBox, "Router", netRouter);
        row(netBox, "Internet", netInternet);
        row(netBox, "Velocità", speedText);
    }

    private void refreshSpeedRow() {
        drawNet();
    }

    private void fillInfo() {
        slow.execute(() -> {
            List<String[]> rows = SystemProbe.info(this);
            handler.post(() -> {
                if (isFinishing()) return;
                for (String[] r : rows) row(infoBox, r[0], r[1]);
            });
        });
    }

    private static String ms(float v) {
        if (v == -2) return "–";
        return v < 0 ? "non risponde" : String.format(Locale.ITALY, v < 10 ? "%.1f ms" : "%.0f ms", v);
    }

    /** "cpu-thermal" as "cpu", "soc_thermal" as "soc": zone names are long and mostly the same word. */
    private static String shortName(String zone) {
        String n = zone.replace("-thermal", "").replace("_thermal", "").replace("thermal", "").trim();
        if (n.isEmpty()) n = zone;
        return "Temp. " + (n.length() > 10 ? n.substring(0, 10) : n);
    }

    // ---- layout helpers ------------------------------------------------------------------------------------

    private LinearLayout col(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (parent.getChildCount() > 0) lp.setMarginStart(SyntaxUi.dp(this, 14));
        parent.addView(c, lp);
        return c;
    }

    LinearLayout box(LinearLayout parent, String title) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(SyntaxUi.rounded(this, 16, SyntaxUi.CARD, 1, SyntaxUi.CARD_EDGE));
        int pad = SyntaxUi.dp(this, 14);
        b.setPadding(pad, SyntaxUi.dp(this, 2), pad, pad);
        TextView t = SyntaxUi.label(this, title);
        t.setPadding(0, SyntaxUi.dp(this, 10), 0, SyntaxUi.dp(this, 6));
        b.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        if (parent.getChildCount() > 0) lp.topMargin = SyntaxUi.dp(this, 12);
        parent.addView(b, lp);
        return b;
    }

    void row(LinearLayout box, String key, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, SyntaxUi.dp(this, 2), 0, SyntaxUi.dp(this, 2));
        TextView k = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
        k.setText(key);
        k.setSingleLine(true);
        TextView v = SyntaxUi.text(this, 13, R.string.font_regular, SyntaxUi.TEXT);
        v.setText(value);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setGravity(Gravity.END);
        r.addView(k, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        r.addView(v, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(r);
    }

    TextView small(String text) {
        TextView t = SyntaxUi.text(this, 12, R.string.font_light, SyntaxUi.MUTED);
        t.setText(text);
        t.setPadding(0, SyntaxUi.dp(this, 4), 0, 0);
        return t;
    }
}
