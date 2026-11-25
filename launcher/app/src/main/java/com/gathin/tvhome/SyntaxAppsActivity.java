package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Impostazioni TV Liberata, "App": the Catalogo, App nascoste (Home's row) and Disinstalla (the TV's own
 * confirmation; system apps, stores and TV Liberata never listed). 1.0.9: no "Info app" any more. Home's row order
 * and visibility stay where they were: a long press on OK on Home.
 */
public final class SyntaxAppsActivity extends Activity {

    private static final int PER_ROW = 5;

    private TextView uninstallChip;
    private LinearLayout grid;
    private TextView hint;
    private boolean uninstalling;

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

        // In this order: Catalogo, App nascoste, Disinstalla (1.0.7: no "Tutte le app"; 1.0.9: no "Info app").
        LinearLayout chips = SyntaxUi.chipRow(this);
        TextView catalog = SyntaxUi.chip(this, getString(R.string.catalog_title));
        catalog.setOnClickListener(v -> startActivity(new Intent(this, SyntaxCatalogActivity.class)));
        SyntaxUi.addChip(chips, catalog);
        TextView hidden = SyntaxUi.chip(this, getString(R.string.hidden_title));
        hidden.setOnClickListener(v -> startActivity(new Intent(this, SyntaxHiddenAppsActivity.class)));
        SyntaxUi.addChip(chips, hidden);
        uninstallChip = SyntaxUi.chip(this, getString(R.string.tile_uninstall));
        uninstallChip.setOnClickListener(v -> {
            uninstalling = !uninstalling;
            show();
        });
        SyntaxUi.addChip(chips, uninstallChip);
        SyntaxUi.toggle(uninstallChip);
        column.addView(chips);

        hint = SyntaxUi.text(this, 16, R.string.font_regular, SyntaxUi.MUTED);
        hint.setPadding(0, SyntaxUi.dp(this, 16), 0, SyntaxUi.dp(this, 4));
        column.addView(hint);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        column.addView(grid);
        setContentView(outer);
        catalog.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        show();   // an app may have gone (Disinstalla) or come (Catalogo)
    }

    /** Disinstalla on: the apps that can go, OK on one asks first. Off: nothing under the buttons. */
    private void show() {
        uninstallChip.setSelected(uninstalling);
        grid.removeAllViews();
        hint.setVisibility(uninstalling ? View.VISIBLE : View.GONE);
        if (!uninstalling) return;
        hint.setText(R.string.apps_uninstall_hint);
        List<App> apps = new ArrayList<>();
        for (App a : launchable(this)) if (AppRules.uninstallable(this, a.pkg)) apps.add(a);
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
            // Same artwork as Home's row: the TV banner, or the icon tile (TileArt).
            art = TileArt.art(this, pm.getActivityInfo(a.component, 0));
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
        SyntaxDialog.show(this, getString(R.string.tile_uninstall), getString(R.string.apps_uninstall_question, a.label),
                new String[]{getString(R.string.tile_uninstall), getString(R.string.syntax_no)}, 1, which -> {
                    if (which == 0) startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                });
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
}
