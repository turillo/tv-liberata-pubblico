package com.gathin.tvhome;

import android.app.Activity;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Impostazioni TV Liberata, "App": every app the TV can open, in one place. Tutte le app (OK opens it; it replaces
 * Amazon's "Le mie app"), App nascoste (Home's row), Disinstalla (the TV's own confirmation; system apps, stores and
 * TV Liberata never listed), Info app (version, space, "Svuota cache" through the app's own system page) and the
 * Catalogo. Home's row order and visibility stay where they were: a long press on OK on Home.
 */
public final class SyntaxAppsActivity extends Activity {

    static final int ALL = 0, UNINSTALL = 1, INFO = 2;
    private static final int PER_ROW = 5;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] modeChips = new TextView[3];
    private LinearLayout grid;
    private TextView hint;
    private int mode = ALL;

    /** One app the TV can open. */
    static final class App {
        final String pkg, label;
        final ComponentName component;

        App(String pkg, String label, ComponentName component) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.apps_title), getString(R.string.apps_intro));

        LinearLayout chips = SyntaxUi.chipRow(this);
        int[] names = {R.string.apps_all, R.string.tile_uninstall, R.string.apps_info};
        for (int i = 0; i < names.length; i++) {
            int m = i;
            TextView chip = SyntaxUi.chip(this, getString(names[i]));
            chip.setOnClickListener(v -> show(m));
            SyntaxUi.addChip(chips, chip);
            modeChips[i] = chip;
        }
        TextView hidden = SyntaxUi.chip(this, getString(R.string.hidden_title));
        hidden.setOnClickListener(v -> startActivity(new Intent(this, SyntaxHiddenAppsActivity.class)));
        SyntaxUi.addChip(chips, hidden);
        TextView catalog = SyntaxUi.chip(this, getString(R.string.catalog_title));
        catalog.setOnClickListener(v -> startActivity(new Intent(this, SyntaxCatalogActivity.class)));
        SyntaxUi.addChip(chips, catalog);
        column.addView(chips);

        hint = SyntaxUi.text(this, 16, R.string.font_regular, SyntaxUi.MUTED);
        hint.setPadding(0, SyntaxUi.dp(this, 16), 0, SyntaxUi.dp(this, 4));
        column.addView(hint);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        column.addView(grid);
        setContentView(outer);
        modeChips[0].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(mode);   // an app may have gone (Disinstalla) or come (Catalogo)
    }

    private void show(int m) {
        mode = m;
        for (int i = 0; i < modeChips.length; i++) modeChips[i].setSelected(i == m);
        hint.setText(m == ALL ? R.string.apps_all_hint : m == UNINSTALL ? R.string.apps_uninstall_hint : R.string.apps_info_hint);
        List<App> apps = new ArrayList<>();
        for (App a : launchable(this)) if (m != UNINSTALL || AppRules.uninstallable(this, a.pkg)) apps.add(a);
        grid.removeAllViews();
        if (apps.isEmpty()) {
            TextView none = SyntaxUi.text(this, 16, R.string.font_regular, SyntaxUi.MUTED);
            none.setText(R.string.apps_none);
            grid.addView(none);
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(0, SyntaxUi.dp(this, 12), 0, 0);
                grid.addView(row);
            }
            row.addView(tile(apps.get(i)));
        }
    }

    /** A tile: the app's banner (or its icon) and its name. */
    private LinearLayout tile(App a) {
        PackageManager pm = getPackageManager();
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setBackground(SyntaxUi.cardBackground(this, 14, SyntaxUi.CARD));
        int pad = SyntaxUi.dp(this, 8);
        t.setPadding(pad, pad, pad, pad);
        SyntaxUi.focusable(t, 1.06f);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Drawable art = null;
        try {
            art = pm.getActivityBanner(a.component);
            if (art == null) art = pm.getApplicationBanner(a.pkg);
            if (art == null) art = pm.getActivityIcon(a.component);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // no picture: the name alone
        }
        image.setImageDrawable(art);
        int w = SyntaxUi.dp(this, 118);   // five tiles and their margins fit the page
        t.addView(image, new LinearLayout.LayoutParams(w, w * 9 / 16));
        TextView name = SyntaxUi.text(this, 14, R.string.font_regular, SyntaxUi.TEXT);
        name.setText(a.label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMaxWidth(w);
        name.setPadding(0, SyntaxUi.dp(this, 6), 0, 0);
        t.addView(name);
        t.setOnClickListener(v -> pick(a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, SyntaxUi.dp(this, 14), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void pick(App a) {
        if (mode == ALL) {
            try {
                startActivity(new Intent(Intent.ACTION_MAIN).setComponent(a.component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (RuntimeException e) {
                SyntaxDialog.message(this, getString(R.string.syntax_error), getString(R.string.cant_open, a.label));
            }
        } else if (mode == UNINSTALL) {
            SyntaxDialog.show(this, getString(R.string.tile_uninstall), getString(R.string.apps_uninstall_question, a.label),
                    new String[]{getString(R.string.tile_uninstall), getString(R.string.syntax_no)}, 1, which -> {
                        if (which == 0) startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                    });
        } else {
            info(a);
        }
    }

    /** Version and space (StorageStatsManager: needs GET_USAGE_STATS, granted by the PC program). */
    private void info(App a) {
        worker.execute(() -> {
            String version = "?";
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(a.pkg, 0);
                version = pi.versionName == null ? String.valueOf(Catalog.versionCode(pi)) : pi.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // gone meanwhile
            }
            String space;
            try {
                StorageStatsManager ssm = getSystemService(StorageStatsManager.class);
                StorageStats st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.pkg, Process.myUserHandle());
                space = getString(R.string.apps_info_space, size(st.getAppBytes()), size(st.getDataBytes()), size(st.getCacheBytes()));
            } catch (Exception e) {   // SecurityException without the permission, IOException, NameNotFound
                space = getString(R.string.apps_info_no_space);
            }
            String text = getString(R.string.apps_info_text, a.pkg, version, space);
            handler.post(() -> {
                if (isFinishing()) return;
                SyntaxDialog.show(this, a.label, text,
                        new String[]{getString(R.string.apps_clear_cache), getString(R.string.apps_close)}, 1, which -> {
                            // Android lets only the system clear another app's cache: its page has the button.
                            if (which == 0) startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        });
            });
        });
    }

    private String size(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    /** Every app the TV can open (TV and phone entry points), one per package, by name; never TV Liberata itself. */
    static List<App> launchable(Context c) {
        PackageManager pm = c.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));
        List<App> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            apps.add(new App(pkg, label == null ? pkg : label.toString(), new ComponentName(pkg, ri.activityInfo.name)));
        }
        sort(apps);
        return apps;
    }

    /** By name, ignoring case. Plain Java, tested. */
    static void sort(List<App> apps) {
        apps.sort((x, y) -> x.label.toLowerCase(Locale.ROOT).compareTo(y.label.toLowerCase(Locale.ROOT)));
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
