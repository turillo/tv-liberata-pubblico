package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The DNS filter's settings and statistics (SharedPreferences "dns_filter"): on or off, Fire OS updates blocked
 * (on by default), the exceptions, and the counts the Filtro screen shows. Starting needs Android's VPN consent once:
 * the TV's own dialog, or over adb "appops set &lt;package&gt; ACTIVATE_VPN allow".
 */
final class FilterSettings {

    static final String PREFS = "dns_filter", KEY_ON = "on", KEY_OTA = "block_ota", KEY_EXCEPTIONS = "exceptions";

    private FilterSettings() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean on(Context c) {
        return prefs(c).getBoolean(KEY_ON, false);
    }

    static boolean ota(Context c) {
        return prefs(c).getBoolean(KEY_OTA, true);
    }

    static List<String> exceptions(Context c) {
        return new ArrayList<>(new TreeSet<>(prefs(c).getStringSet(KEY_EXCEPTIONS, Collections.emptySet())));
    }

    static void setException(Context c, String domain, boolean allowed) {
        String d = DnsFilter.domain(domain);
        if (d == null) return;
        TreeSet<String> set = new TreeSet<>(prefs(c).getStringSet(KEY_EXCEPTIONS, Collections.emptySet()));
        if (allowed) set.add(d);
        else set.remove(d);
        prefs(c).edit().putStringSet(KEY_EXCEPTIONS, set).apply();
        DnsVpnService.reload(c);
    }

    static void setOta(Context c, boolean block) {
        prefs(c).edit().putBoolean(KEY_OTA, block).apply();
        DnsVpnService.reload(c);
    }

    /** Android's consent screen, or null when the consent is already there. */
    static Intent consent(Context c) {
        try {
            return VpnService.prepare(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Turns the filter on (the consent must be there) or off, and remembers it. Never throws. */
    static void set(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
        try {
            Intent i = new Intent(c, DnsVpnService.class);
            if (!on) i.setAction(DnsVpnService.ACTION_STOP);
            c.startService(i);
        } catch (RuntimeException e) {
            Log.w("TvHome.DnsFilter", "filter service not reached: " + e.getMessage());
        }
    }

    /** At boot or Home's start: the filter back on if it was on and the consent is there. */
    static void resume(Context c) {
        if (on(c) && !DnsVpnService.isRunning() && consent(c) == null) set(c, true);
    }

    // ---- statistics --------------------------------------------------------------------------------------------

    static void addStats(Context c, long blocked, long total, Map<String, Integer> top, List<String> recent) {
        SharedPreferences p = prefs(c);
        Map<String, Integer> merged = parseTop(p.getString("top", ""));
        for (Map.Entry<String, Integer> e : top.entrySet()) {
            Integer v = merged.get(e.getKey());
            merged.put(e.getKey(), (v == null ? 0 : v) + e.getValue());
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(merged.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < Math.min(50, sorted.size()); i++) {
            if (t.length() > 0) t.append(';');
            t.append(sorted.get(i).getKey()).append('=').append(sorted.get(i).getValue());
        }
        SharedPreferences.Editor e = p.edit().putLong("blocked", p.getLong("blocked", 0) + blocked)
                .putLong("total", p.getLong("total", 0) + total).putString("top", t.toString());
        if (!recent.isEmpty()) e.putString("recent", String.join(";", recent));
        if (!p.contains("since")) e.putLong("since", System.currentTimeMillis());
        e.apply();
    }

    static Map<String, Integer> parseTop(String s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // skipped
            }
        }
        return out;
    }

    static List<String> recent(Context c) {
        String s = prefs(c).getString("recent", "");
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) Collections.addAll(out, s.split(";"));
        return out;
    }

    static void resetStats(Context c) {
        prefs(c).edit().remove("blocked").remove("total").remove("top").remove("recent").remove("since").apply();
    }
}
