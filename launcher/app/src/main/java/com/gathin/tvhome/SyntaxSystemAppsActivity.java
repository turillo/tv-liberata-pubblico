package com.gathin.tvhome;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Impostazioni TV Liberata, Sistema, Pulizia avanzata: system apps disabled or enabled again from the remote, one at
 * a time and after a question, through the TV's own adb (AdbLocal: authorised once on screen). Protected apps are
 * not listed; "Ripristina tutto" turns back on everything disabled here.
 */
public final class SyntaxSystemAppsActivity extends Activity {

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, authorize;
    private LinearLayout list;
    private boolean adbOk;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        SyntaxUi.header(this, column, getString(R.string.sysapps_title), getString(R.string.sysapps_intro));
        LinearLayout row = SyntaxUi.chipRow(this);
        authorize = SyntaxUi.chip(this, getString(R.string.sysapps_authorize));
        authorize.setOnClickListener(v -> authorize());
        SyntaxUi.addChip(row, authorize);
        TextView restore = SyntaxUi.chip(this, getString(R.string.sysapps_restore_all));
        restore.setOnClickListener(v -> restoreAll());
        SyntaxUi.addChip(row, restore);
        column.addView(row);
        status = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        status.setPadding(0, SyntaxUi.dp(this, 10), 0, SyntaxUi.dp(this, 6));
        column.addView(status);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        column.addView(list);
        setContentView(outer);
        authorize.requestFocus();
        checkAdb();
    }

    private void checkAdb() {
        status.setText(R.string.sysapps_checking);
        worker.execute(() -> {
            boolean ok = AdbLocal.authorized(this);
            handler.post(() -> {
                adbOk = ok;
                if (isFinishing()) return;
                status.setText(ok ? R.string.sysapps_ready : R.string.sysapps_not_authorized);
                authorize.setVisibility(ok ? android.view.View.GONE : android.view.View.VISIBLE);
                fill();
            });
        });
    }

    private void authorize() {
        status.setText(R.string.sysapps_confirm_on_tv);
        worker.execute(() -> {
            int r = AdbLocal.check(this, true);
            handler.post(() -> {
                if (isFinishing()) return;
                if (r == AdbLocal.NO_ADB) status.setText(R.string.sysapps_no_adb);
                else if (r == AdbLocal.BLOCKED) status.setText(R.string.adb_blocked);
                else checkAdb();
            });
        });
    }

    private void fill() {
        list.removeAllViews();
        if (!Features.ADVANCED_CLEANUP) {   // 1.0: only "Ripristina tutto"
            TextView only = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
            only.setText(getString(R.string.sysapps_only_restore, SystemApps.disabledByUs(this).size()));
            list.addView(only);
            return;
        }
        PackageManager pm = getPackageManager();
        Set<String> never = SystemApps.never(this);
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        String keyboard = ime == null ? null : ime.split("/")[0];
        List<ApplicationInfo> apps = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
            if (SystemApps.protectedPkg(ai.packageName, never, keyboard)) continue;
            apps.add(ai);
        }
        apps.sort((a, b) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % 3 == 0) {
                row = SyntaxUi.chipRow(this);
                row.setPadding(0, 0, 0, SyntaxUi.dp(this, 8));
                list.addView(row);
            }
            ApplicationInfo ai = apps.get(i);
            String label = String.valueOf(ai.loadLabel(pm));
            boolean enabled = ai.enabled;
            TextView chip = SyntaxUi.chip(this, (enabled ? "" : "✕ ") + label);
            chip.setTextSize(14);
            chip.setMaxWidth(SyntaxUi.dp(this, 300));
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setAlpha(enabled ? 1f : 0.6f);
            chip.setEnabled(adbOk);
            chip.setFocusable(adbOk);
            chip.setOnClickListener(v -> ask(ai.packageName, label, enabled));
            SyntaxUi.addChip(row, chip);
        }
    }

    private void ask(String pkg, String label, boolean enabled) {
        SyntaxDialog.show(this, label, getString(enabled ? R.string.sysapps_disable_q : R.string.sysapps_enable_q, pkg),
                new String[]{getString(R.string.syntax_yes), getString(R.string.syntax_no)}, 1, which -> {
                    if (which == 0) change(pkg, enabled);
                });
    }

    private void change(String pkg, boolean disable) {
        status.setText(R.string.sysapps_working);
        worker.execute(() -> {
            String out;
            try {
                out = AdbLocal.shell(this, SystemApps.command(pkg, disable), 20_000);
            } catch (java.io.IOException e) {
                out = e.getMessage();
            }
            boolean ok = SystemApps.succeeded(out, disable);
            if (ok) SystemApps.record(this, pkg, disable);
            String msg = out == null ? "" : out.trim();
            handler.post(() -> {
                if (isFinishing()) return;
                status.setText(ok ? getString(R.string.sysapps_done) : getString(R.string.sysapps_failed, msg));
                fill();
            });
        });
    }

    private void restoreAll() {
        Set<String> all = SystemApps.disabledByUs(this);
        if (all.isEmpty()) {
            status.setText(R.string.sysapps_nothing_to_restore);
            return;
        }
        SyntaxDialog.show(this, getString(R.string.sysapps_restore_all), getString(R.string.sysapps_restore_q, all.size()),
                new String[]{getString(R.string.syntax_yes), getString(R.string.syntax_no)}, 1, which -> {
                    if (which != 0) return;
                    status.setText(R.string.sysapps_working);
                    worker.execute(() -> {
                        int done = 0;
                        for (String pkg : all) {
                            try {
                                if (SystemApps.succeeded(AdbLocal.shell(this, SystemApps.command(pkg, false), 20_000), false)) {
                                    SystemApps.record(this, pkg, false);
                                    done++;
                                }
                            } catch (java.io.IOException ignored) {
                                // left on the list for the next try
                            }
                        }
                        int n = done;
                        handler.post(() -> {
                            if (isFinishing()) return;
                            status.setText(getString(R.string.sysapps_restored, n, all.size()));
                            fill();
                        });
                    });
                });
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
