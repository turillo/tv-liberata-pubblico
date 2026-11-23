package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Impostazioni TV Liberata, App, Catalogo: apps by category. An app from its official source ("libera") or from
 * TV Liberata's "extra" Release is downloaded, checked (SHA-256 and signer from the catalogue, the APK for this
 * device's ABI) and handed to Android's installer; a "store" app opens its page in the Appstore or Play Store.
 * Each button says Installa, Installata or Aggiorna. The first time, Android asks to allow TV Liberata to install
 * apps: the page opens that setting. Opened with EXTRA_FOCUS, the page starts on that app (Screensaver, Aerial Views).
 */
public final class SyntaxCatalogActivity extends Activity {

    static final String EXTRA_FOCUS = "focus";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private String focus;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        focus = getIntent().getStringExtra(EXTRA_FOCUS);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        SyntaxUi.header(this, column, getString(R.string.catalog_title), getString(R.string.catalog_intro));
        status = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        status.setPadding(0, SyntaxUi.dp(this, 8), 0, SyntaxUi.dp(this, 8));
        column.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        column.addView(list);
        setContentView(outer);
        status.setText(R.string.catalog_loading);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list.getChildCount() > 0) load();   // back from an install or the store: the buttons change
    }

    private void load() {
        worker.execute(() -> {
            List<Catalog.App> apps = Catalog.load(this);
            handler.post(() -> show(apps));
        });
    }

    private void show(List<Catalog.App> all) {
        if (isFinishing()) return;
        String platform = Device.isFireOs() ? "fireos" : "androidtv";
        Map<String, List<Catalog.App>> byCategory = new LinkedHashMap<>();
        for (Catalog.App a : all) {
            if (!a.on(platform)) continue;
            String cat = a.category == null ? getString(R.string.catalog_other) : a.category;
            byCategory.computeIfAbsent(cat, k -> new ArrayList<>()).add(a);
        }
        list.removeAllViews();
        status.setText(byCategory.isEmpty() ? getString(R.string.catalog_empty) : "");
        View first = null, wanted = null;
        for (Map.Entry<String, List<Catalog.App>> e : byCategory.entrySet()) {
            list.addView(SyntaxUi.label(this, e.getKey()));
            for (Catalog.App a : e.getValue()) {
                LinearLayout row = SyntaxUi.chipRow(this);
                row.setPadding(0, 0, 0, SyntaxUi.dp(this, 10));
                TextView chip = SyntaxUi.chip(this, label(a));
                chip.setOnClickListener(v -> press(a));
                SyntaxUi.addChip(row, chip);
                TextView about = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
                about.setText(a.description == null ? "" : a.description);
                about.setPadding(SyntaxUi.dp(this, 12), 0, 0, 0);
                row.addView(about);
                list.addView(row);
                if (first == null) first = chip;
                if (a.id != null && a.id.equals(focus)) wanted = chip;
            }
        }
        View target = wanted != null ? wanted : first;
        if (target != null) target.requestFocus();
        focus = null;   // only the first time
    }

    private String label(Catalog.App a) {
        long code = Catalog.installedCode(this, a.pkg);
        switch (Catalog.state(a, code, Catalog.installed(this, a.pkg))) {
            case Catalog.INSTALLED:
                return getString(R.string.catalog_installed, a.name);
            case Catalog.UPDATE:
                return getString(R.string.catalog_update, a.name, a.version);
            default:
                if (a.store()) return getString(R.string.catalog_from_store, a.name);
                return getString(R.string.catalog_install, a.name, a.version == null ? "" : a.version).trim();
        }
    }

    private void press(Catalog.App a) {
        long code = Catalog.installedCode(this, a.pkg);
        int state = Catalog.state(a, code, Catalog.installed(this, a.pkg));
        if (state == Catalog.INSTALLED) {   // already there: OK opens it
            Intent open = getPackageManager().getLeanbackLaunchIntentForPackage(a.pkg);
            if (open == null) open = getPackageManager().getLaunchIntentForPackage(a.pkg);
            if (open != null) startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (a.store()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Catalog.storeUri(a, Device.isFireOs())))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                status.setText(getString(R.string.catalog_no_store, a.name));
            }
            return;
        }
        install(a);
    }

    private void install(Catalog.App a) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            status.setText(R.string.catalog_allow);
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                SyntaxDialog.message(this, getString(R.string.catalog_title), getString(R.string.update_permission));
            }
            return;
        }
        status.setText(getString(R.string.catalog_downloading, a.name));
        worker.execute(() -> {
            try {
                File apk = Catalog.download(this, a);
                Updater.installFile(getApplicationContext(), apk);
                handler.post(() -> status.setText(getString(R.string.catalog_confirm, a.name)));
            } catch (java.io.IOException | RuntimeException e) {
                String why = e.getMessage();
                handler.post(() -> status.setText(getString(R.string.catalog_failed, a.name, why)));
            }
        });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
