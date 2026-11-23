package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pulizia avanzata: which system apps may be disabled from the remote (through AdbLocal), and the record of what TV
 * Liberata disabled, so "Ripristina tutto" can always turn it back on. Disabling is "pm disable-user", never an
 * uninstall: every step is undone by "pm enable".
 */
final class SystemApps {

    private static final String PREFS = "system_apps", KEY_DISABLED = "disabled_by_us";

    private SystemApps() {
    }

    /**
     * Never offered: the app stores, TV Liberata, Android's core (com.android.*, the package installer, permission
     * controller, Play services), the current keyboard, and dumbtv's never-disable list (what draws the screen,
     * switches inputs, keeps the remote paired).
     */
    static boolean protectedPkg(String pkg, Set<String> never, String keyboard) {
        if (pkg == null || pkg.isEmpty()) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (AppRules.store(pkg) || p.startsWith("com.gathin.tvhome") || p.equals("android")) return true;
        if (p.startsWith("com.android.") || p.startsWith("com.google.android.overlay.modules")) return true;
        if (p.equals("com.google.android.gms") || p.equals("com.google.android.gsf")
                || p.contains("packageinstaller") || p.contains("permissioncontroller") || p.contains("ext.services")
                || p.contains("ext.shared") || p.contains("modulemetadata") || p.contains("factory")) return true;
        if (keyboard != null && keyboard.equals(pkg)) return true;
        return never.contains(pkg);
    }

    /** The packages of a list file: the first word of each line, "#" comments skipped. */
    static Set<String> parseList(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String s = line.replaceAll("#.*$", "").trim();
            if (s.isEmpty()) continue;
            out.add(s.split("\\s+")[0]);
        }
        return out;
    }

    static Set<String> never(Context c) {
        return parseList(DnsVpnService.raw(c, R.raw.never_disable));
    }

    static Set<String> disabledByUs(Context c) {
        return new TreeSet<>(prefs(c).getStringSet(KEY_DISABLED, Collections.emptySet()));
    }

    static void record(Context c, String pkg, boolean disabled) {
        Set<String> s = disabledByUs(c);
        if (disabled) s.add(pkg);
        else s.remove(pkg);
        prefs(c).edit().putStringSet(KEY_DISABLED, s).apply();
    }

    /** The shell command for one change. */
    static String command(String pkg, boolean disable) {
        return disable ? "pm disable-user --user 0 " + AdbLocal.quote(pkg) : "pm enable " + AdbLocal.quote(pkg);
    }

    /** pm's answer says the change happened. */
    static boolean succeeded(String output, boolean disable) {
        String o = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return disable ? o.contains("new state: disabled") : o.contains("new state: enabled") || o.contains("new state: default");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
