package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The guided set-up's steps, run through the TV's own adb (AdbLocal) once the user has allowed it: permissions,
 * TV Liberata as Home (Fire OS: FireHomeGuard; Android TV: the Home activity), the base clean-up (disabled, never
 * uninstalled, protected list skipped), the DNS filter, the updates. Everything changed is remembered so
 * {@link #undo} puts the TV back as it was. Blocking: off the main thread.
 */
final class Setup {

    private static final String TAG = "TvHome.Setup";
    static final String PREFS = "setup";

    /** One step's outcome for the screen. */
    static final class Step {
        final String name;
        boolean ok;
        String detail;

        Step(String name) {
            this.name = name;
        }
    }

    private Setup() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean done(Context c) {
        return prefs(c).getBoolean("done", false);
    }

    /** Set from the PC (device configuration, migration): the guided set-up is not offered. */
    static void markDone(Context c) {
        prefs(c).edit().putBoolean("done", true).apply();
    }

    private static String sh(Context c, String cmd) throws IOException {
        return AdbLocal.shell(c, cmd, 30_000).trim();
    }

    /** Remembers a setting's value before the first change (null when unset), for undo. */
    private static void remember(Context c, String ns, String key) throws IOException {
        SharedPreferences p = prefs(c);
        String k = "before:" + ns + ":" + key;
        if (p.contains(k)) return;
        String v = sh(c, "settings get " + ns + " " + key);
        p.edit().putString(k, v).apply();
    }

    /** The steps after the name, in order; each reports its own result. Stops at the first that cannot go on. */
    static List<Step> run(Context c, String name, Progress progress) {
        List<Step> steps = new ArrayList<>();
        String pkg = c.getPackageName();
        boolean fire = Device.isFireOs();
        DeviceConfig.setName(c, name);
        try {
            Step perm = add(steps, progress, c.getString(R.string.setup_step_permissions));
            sh(c, "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            sh(c, "appops set " + pkg + " REQUEST_INSTALL_PACKAGES allow");
            sh(c, "appops set " + pkg + " ACTIVATE_VPN allow");
            sh(c, "appops set " + pkg + " WRITE_SETTINGS allow");
            perm.ok = true;
            progress.update(steps);

            Step home = add(steps, progress, c.getString(R.string.setup_step_home));
            if (fire) {
                remember(c, "secure", "enabled_accessibility_services");
                remember(c, "secure", "accessibility_enabled");
                remember(c, "global", "alarm_manager_constants");
                String alarm = sh(c, "settings get global alarm_manager_constants");
                if (!alarm.contains("min_futurity=0")) {
                    String rest = alarm.equals("null") ? "" : alarm.replaceAll("(^|,)min_futurity=[^,]*", "");
                    sh(c, "settings put global alarm_manager_constants " + (rest.isEmpty() ? "" : rest + ",") + "min_futurity=0");
                }
                sh(c, "dumpsys deviceidle whitelist +" + pkg);
                String guard = new ComponentName(c, FireHomeGuard.class).flattenToString();
                String list = sh(c, "settings get secure enabled_accessibility_services");
                StringBuilder kept = new StringBuilder();
                for (String s : list.split(":")) {
                    if (s.isEmpty() || s.equals("null") || s.contains("FireHomeGuard") || s.contains("tv.dumbtv.home")) continue;
                    kept.append(s).append(':');
                }
                sh(c, "settings put secure enabled_accessibility_services " + AdbLocal.quote(kept + guard));
                sh(c, "settings put secure accessibility_enabled 1");
            } else {
                String current = sh(c, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1");
                if (current.contains("/") && !current.startsWith(pkg + "/")) prefs(c).edit().putString("before:home", current).apply();
                sh(c, "cmd package set-home-activity --user 0 " + pkg + "/com.gathin.tvhome.MainActivity");
            }
            home.ok = true;
            progress.update(steps);

            Step clean = add(steps, progress, c.getString(R.string.setup_step_cleanup));
            Set<String> never = SystemApps.never(c);
            String ime = sh(c, "settings get secure default_input_method");
            String keyboard = ime.contains("/") ? ime.split("/")[0] : null;
            int disabled = 0;
            for (String p : SystemApps.parseList(DnsVpnService.raw(c, fire ? R.raw.base_fireos : R.raw.base_androidtv))) {
                if (SystemApps.protectedPkg(p, never, keyboard)) continue;
                if (sh(c, "pm path " + AdbLocal.quote(p)).isEmpty()) continue;   // not on this TV
                String out = sh(c, SystemApps.command(p, true));
                if (SystemApps.succeeded(out, true)) {
                    SystemApps.record(c, p, true);
                    disabled++;
                }
            }
            clean.ok = true;
            clean.detail = c.getString(R.string.setup_cleanup_done, disabled);
            progress.update(steps);

            Step filter = add(steps, progress, c.getString(R.string.setup_step_filter));
            remember(c, "secure", "always_on_vpn_app");
            FilterSettings.setOta(c, true);
            FilterSettings.set(c, true);
            sh(c, "settings put secure always_on_vpn_app " + pkg);
            filter.ok = true;
            progress.update(steps);

            Step upd = add(steps, progress, c.getString(R.string.setup_step_updates));
            Updater.schedule(c);
            upd.ok = true;
            markDone(c);
            progress.update(steps);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "set-up stopped: " + e.getMessage());
            Step last = steps.isEmpty() ? add(steps, progress, "?") : steps.get(steps.size() - 1);
            last.ok = false;
            last.detail = e.getMessage();
            progress.update(steps);
        }
        return steps;
    }

    /** "Annulla tutto e torna come prima": every change of the set-up undone. Returns what could not be. */
    static String undo(Context c) {
        StringBuilder failed = new StringBuilder();
        SharedPreferences p = prefs(c);
        String pkg = c.getPackageName();
        try {
            FilterSettings.set(c, false);
            for (String app : SystemApps.disabledByUs(c)) {
                if (SystemApps.succeeded(sh(c, SystemApps.command(app, false)), false)) SystemApps.record(c, app, false);
                else failed.append(app).append(' ');
            }
            for (String k : p.getAll().keySet()) {
                if (!k.startsWith("before:") || k.equals("before:home")) continue;
                String[] parts = k.split(":", 3);
                String v = p.getString(k, "null");
                if (v == null || v.equals("null") || v.isEmpty()) sh(c, "settings delete " + parts[1] + " " + parts[2]);
                else sh(c, "settings put " + parts[1] + " " + parts[2] + " " + AdbLocal.quote(v));
            }
            String home = p.getString("before:home", null);
            if (home != null) sh(c, "cmd package set-home-activity --user 0 " + home);
            sh(c, "appops set " + pkg + " ACTIVATE_VPN default");
            p.edit().clear().apply();
            sh(c, "input keyevent KEYCODE_HOME");
        } catch (IOException e) {
            failed.append(e.getMessage());
        }
        return failed.toString().trim();
    }

    interface Progress {
        void update(List<Step> steps);
    }

    private static Step add(List<Step> steps, Progress progress, String name) {
        Step s = new Step(name);
        steps.add(s);
        progress.update(steps);
        return s;
    }
}
