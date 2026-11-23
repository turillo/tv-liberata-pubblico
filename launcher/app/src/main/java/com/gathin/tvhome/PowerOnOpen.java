package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * "Apri TV Liberata all'accensione": the fallback where FireHomeGuard cannot run (its accessibility service not
 * turned on from a PC). At boot (HomeRestore) and when the TV wakes from standby (screen on, user present), Home is
 * opened. The wake events only reach a running process: the DNS filter's VPN service keeps one and registers them.
 * On by default on Fire OS without the accessibility service; off elsewhere (Android TV's HOME is already ours).
 */
final class PowerOnOpen {

    private static final String TAG = "TvHome.PowerOn";
    private static final String KEY = "open_at_power_on";
    private static BroadcastReceiver wake;

    private PowerOnOpen() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("power_on", Context.MODE_PRIVATE);
    }

    /** The default when the user never chose. Plain Java, tested. */
    static boolean byDefault(boolean fireOs, boolean guardOn) {
        return fireOs && !guardOn;
    }

    static boolean chosen(Context c) {
        return prefs(c).contains(KEY);
    }

    static boolean enabled(Context c) {
        SharedPreferences p = prefs(c);
        return p.contains(KEY) ? p.getBoolean(KEY, false) : byDefault(Device.isFireOs(), guardEnabled(c));
    }

    static void set(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY, on).apply();
    }

    /** FireHomeGuard is in the enabled accessibility services (turned on from the PC). */
    static boolean guardEnabled(Context c) {
        try {
            String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String guard = new ComponentName(c, FireHomeGuard.class).flattenToString();
            return HomeRestore.withGuard(list, guard) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static void openHome(Context c) {
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClass(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (RuntimeException e) {
            Log.w(TAG, "Home not opened: " + e.getMessage());
        }
    }

    /** From the VPN service: listens for the TV waking up while that process lives. */
    static synchronized void listen(Context service) {
        if (wake != null) return;
        wake = new BroadcastReceiver() {
            private long last;

            @Override
            public void onReceive(Context c, Intent i) {
                if (FireHomeGuard.isRunning() || !enabled(c)) return;   // the guard does this better
                long now = System.currentTimeMillis();
                if (now - last < 5_000) return;   // screen on and user present arrive together
                last = now;
                openHome(c);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        try {
            service.getApplicationContext().registerReceiver(wake, f);
        } catch (RuntimeException e) {
            wake = null;
        }
    }

    static synchronized void stop(Context service) {
        if (wake == null) return;
        try {
            service.getApplicationContext().unregisterReceiver(wake);
        } catch (RuntimeException ignored) {
            // already gone
        }
        wake = null;
    }
}
