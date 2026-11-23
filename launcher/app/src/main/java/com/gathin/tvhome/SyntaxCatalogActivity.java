package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Impostazioni TV Liberata, Sistema, Catalogo: apps from their official sources, installed with one press after the
 * download is checked (SHA-256 and signer from the catalogue). The first time, Android asks to allow TV Liberata to
 * install apps: the page opens that setting.
 */
public final class SyntaxCatalogActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        worker.execute(() -> {
            List<Catalog.App> apps = Catalog.load(this);
            handler.post(() -> show(apps));
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list.getChildCount() > 0) worker.execute(() -> {
            List<Catalog.App> apps = Catalog.load(this);
            handler.post(() -> show(apps));
        });
    }

    private void show(List<Catalog.App> apps) {
        if (isFinishing()) return;
        list.removeAllViews();
        status.setText(apps.isEmpty() ? getString(R.string.catalog_empty) : "");
        for (Catalog.App a : apps) {
            LinearLayout row = SyntaxUi.chipRow(this);
            row.setPadding(0, 0, 0, SyntaxUi.dp(this, 12));
            String have = Catalog.installed(this, a.pkg);
            boolean current = have != null && have.equals(a.version);
            TextView chip = SyntaxUi.chip(this, current ? getString(R.string.catalog_installed, a.name)
                    : getString(have == null ? R.string.catalog_install : R.string.catalog_update, a.name, a.version));
            chip.setOnClickListener(v -> install(a));
            chip.setEnabled(!current);
            SyntaxUi.addChip(row, chip);
            TextView about = SyntaxUi.text(this, 13, R.string.font_light, SyntaxUi.MUTED);
            about.setText(a.description + (a.source == null ? "" : "\n" + a.source));
            about.setPadding(SyntaxUi.dp(this, 12), 0, 0, 0);
            row.addView(about);
            list.addView(row);
        }
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
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
