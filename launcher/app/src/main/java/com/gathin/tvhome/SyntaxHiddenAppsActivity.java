package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syntax TV's "App nascoste": every app this TV can open, hidden ones first and ticked. OK hides an app from Home's
 * app row or shows it again; nothing is uninstalled. The choice is the same one the app library's "remove from Home"
 * makes (LibraryApps), so both stay in step; Home rebuilds its tiles when it comes back.
 */
public final class SyntaxHiddenAppsActivity extends Activity {
    private final Map<String, TextView> chips = new LinkedHashMap<>();
    private final Map<String, String> labels = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        SyntaxUi.header(this, column, getString(R.string.hidden_title), getString(R.string.hidden_intro));
        Set<String> hidden = LibraryApps.loadRemoved(this);
        List<String> order = new ArrayList<>();
        for (String pkg : apps().keySet()) if (hidden.contains(pkg)) order.add(pkg);
        for (String pkg : labels.keySet()) if (!hidden.contains(pkg)) order.add(pkg);
        LinearLayout row = null;
        for (int i = 0; i < order.size(); i++) {
            if (i % 4 == 0) {
                row = SyntaxUi.chipRow(this);
                row.setPadding(0, SyntaxUi.dp(this, i == 0 ? 22 : 10), 0, 0);
                column.addView(row);
            }
            String pkg = order.get(i);
            TextView chip = SyntaxUi.chip(this, "");
            chip.setTextSize(15);
            chip.setMaxWidth(SyntaxUi.dp(this, 200));
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (AppRules.store(pkg)) {   // the app store cannot be hidden
                chip.setEnabled(false);
                chip.setFocusable(false);
                chip.setAlpha(0.4f);
            }
            chip.setOnClickListener(v -> {
                boolean nowHidden = LibraryApps.loadRemoved(this).contains(pkg);
                if (!LibraryApps.save(this, pkg, nowHidden)) {
                    SyntaxDialog.message(this, getString(R.string.syntax_error), getString(R.string.hidden_failed));
                }
                refresh();
            });
            SyntaxUi.addChip(row, chip);
            chips.put(pkg, chip);
        }
        setContentView(outer);
        refresh();
        if (!order.isEmpty()) chips.get(order.get(0)).requestFocus();
    }

    /**
     * The apps Home lists or could list: TV apps, plus phone-style apps added from the library or hidden here, one
     * per package, by name; never this launcher.
     */
    private Map<String, String> apps() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(
                pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        java.util.Set<String> chosen = new java.util.HashSet<>(LibraryApps.load(this));
        chosen.addAll(LibraryApps.loadRemoved(this));
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            if (ri.activityInfo != null && chosen.contains(ri.activityInfo.packageName)) found.add(ri);
        }
        List<String[]> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            list.add(new String[]{pkg, label == null ? pkg : label.toString()});
        }
        list.sort((a, b) -> a[1].compareToIgnoreCase(b[1]));
        for (String[] a : list) labels.put(a[0], a[1]);
        return labels;
    }

    private void refresh() {
        Set<String> hidden = LibraryApps.loadRemoved(this);
        for (Map.Entry<String, TextView> e : chips.entrySet()) {
            boolean h = hidden.contains(e.getKey());
            String name = labels.get(e.getKey());
            e.getValue().setText(h ? getString(R.string.hidden_chip, name) : name);
            e.getValue().setSelected(h);
            e.getValue().setAlpha(h ? 1f : 0.75f);
        }
    }
}
