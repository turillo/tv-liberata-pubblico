package com.gathin.tvhome;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * TV Liberata keeps its Home: when Fire OS drops FireHomeGuard from the enabled accessibility services (a
 * system update, a force stop, a reset of the settings) without the user turning it off, it goes back on at the next
 * boot, after an update of this app, or when the app is opened from Amazon's home. Turning the service off in
 * Settings (or "settings delete secure enabled_accessibility_services" over adb) is respected: Android unbinds the
 * running service, which clears the wish. Needs WRITE_SECURE_SETTINGS (granted over adb at install); Fire OS only;
 * the owner's build does nothing here.
 */
public final class HomeRestore extends BroadcastReceiver {

    private static final String TAG = "TvHome.HomeRestore";
    private static final String PREFS = "home_restore", WANTED = "wanted";

    static boolean active() {
        return Device.isFireOs();
    }

    /** FireHomeGuard bound (true) or was turned off while running (false). */
    static void wanted(Context c, boolean on) {
        if (!active()) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WANTED, on).apply();
    }

    /** The enabled list with FireHomeGuard added, or null when it is already there. Plain Java, tested. */
    static String withGuard(String list, String guard) {
        String clean = list == null || "null".equals(list) ? "" : list.trim();
        for (String s : clean.split(":")) if (full(s).equals(full(guard))) return null;
        return clean.isEmpty() ? guard : clean + ":" + guard;
    }

    /** "pkg/.Cls" as "pkg/pkg.Cls", so both spellings compare equal. */
    private static String full(String component) {
        String t = component.trim();
        int slash = t.indexOf('/');
        if (slash > 0 && slash + 1 < t.length() && t.charAt(slash + 1) == '.') {
            t = t.substring(0, slash + 1) + t.substring(0, slash) + t.substring(slash + 1);
        }
        return t;
    }

    /** Puts FireHomeGuard back when it was wanted and is gone. Never throws. */
    static void restore(Context context) {
        Context c = context.getApplicationContext();
        if (!active() || !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WANTED, false)) return;
        if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return;
        try {
            ContentResolver cr = c.getContentResolver();
            String guard = new ComponentName(c, FireHomeGuard.class).flattenToString();
            String updated = withGuard(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), guard);
            if (updated == null) return;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "Home service back on");
        } catch (RuntimeException e) {
            Log.w(TAG, "Home service not restored: " + e.getMessage());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        restore(context);
        FilterSettings.resume(context);   // the DNS filter, both editions, if it was on
        Updater.schedule(context);        // the liberata's night update check
    }
}
