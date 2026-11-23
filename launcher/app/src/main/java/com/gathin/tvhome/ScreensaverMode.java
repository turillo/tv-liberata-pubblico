package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * The TV's system screensaver (Settings.Secure screensaver_components): the launcher's own scenes (ClockDream) or
 * Aerial Views. Writing it needs WRITE_SECURE_SETTINGS, granted once over adb; without it the change is refused and
 * Syntax TV says so.
 */
final class ScreensaverMode {

    static final String AERIAL_PACKAGE = "com.neilturner.aerialviews";
    static final String AERIAL = AERIAL_PACKAGE + "/.ui.screensaver.DreamActivity";
    private static final String KEY = "screensaver_components";

    private ScreensaverMode() {
    }

    static String syntax(Context c) {
        return new ComponentName(c, ClockDream.class).flattenToShortString();
    }

    static String current(Context c) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), KEY);
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Fire OS's own screensaver, which shows Amazon Photos. */
    static final String AMAZON_PACKAGE = "com.amazon.ftv.screensaver";
    static final String AMAZON = AMAZON_PACKAGE + "/.app.services.ScreensaverService";
    /** "Avvia dopo N minuti": the choices, written to the system's screen_off_timeout. */
    static final int[] MINUTES = {1, 3, 5, 10, 15, 30};

    static boolean isAmazon(Context c) {
        return current(c).startsWith(AMAZON_PACKAGE + "/");
    }

    /** Installed and enabled (it can be disabled by a clean-up: then it cannot run). */
    static boolean amazonAvailable(Context c) {
        try {
            return c.getPackageManager().getApplicationInfo(AMAZON_PACKAGE, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean amazonInstalled(Context c) {
        try {
            c.getPackageManager().getApplicationInfo(AMAZON_PACKAGE, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Sets a screensaver component; false without the permission. */
    static boolean setComponent(Context c, String component) {
        if (!canWrite(c)) return false;
        try {
            boolean ok = Settings.Secure.putString(c.getContentResolver(), KEY, component);
            Settings.Secure.putInt(c.getContentResolver(), "screensaver_enabled", 1);
            return ok;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Minutes before the screensaver starts (the system's screen_off_timeout), or -1 when unreadable. */
    static int minutes(Context c) {
        try {
            int ms = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
            return Math.round(ms / 60_000f);
        } catch (Settings.SettingNotFoundException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * Sets screen_off_timeout: directly when the app may write system settings (WRITE_SETTINGS, granted by the
     * set-up), else through the TV's own adb when it is authorised. Blocking (adb): off the main thread.
     */
    static boolean setMinutes(Context c, int minutes) {
        int ms = minutes * 60_000;
        try {
            if (Settings.System.canWrite(c)) return Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, ms);
        } catch (RuntimeException ignored) {
            // try adb below
        }
        try {
            AdbLocal.shell(c, "settings put system screen_off_timeout " + ms, 10_000);
            return minutes(c) == minutes;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    static boolean isAerial(Context c) {
        return current(c).startsWith(AERIAL_PACKAGE + "/");
    }

    static boolean aerialInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(AERIAL_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Sets the system screensaver; false when the permission is missing or the TV refuses. */
    static boolean set(Context c, boolean aerial) {
        if (!canWrite(c)) return false;
        try {
            String component = aerial ? AERIAL : syntax(c);
            boolean ok = Settings.Secure.putString(c.getContentResolver(), KEY, component);
            Settings.Secure.putInt(c.getContentResolver(), "screensaver_enabled", 1);
            return ok;
        } catch (RuntimeException e) {
            Log.w("TvHome.Screensaver", "screensaver not set: " + e.getMessage());
            return false;
        }
    }

    /**
     * Starts the system screensaver now, whichever is set. Fire OS, and any TV without Android's Somnambulator,
     * cannot be asked to: there the Syntax scene plays as a full-screen activity instead (ScenePreviewActivity),
     * the same scene the screensaver would show. False only when neither could start.
     */
    static boolean startNow(Context c) {
        Intent dream = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName("com.android.systemui", "com.android.systemui.Somnambulator"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!Device.isFireOs()) {
            try {
                c.startActivity(dream);
                return true;
            } catch (RuntimeException e) {
                Log.w("TvHome.Screensaver", "screensaver not started, playing the scene instead: " + e.getMessage());
            }
        }
        try {
            c.startActivity(new Intent(c, ScenePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException e) {
            Log.w("TvHome.Screensaver", "scene not started: " + e.getMessage());
            return false;
        }
    }
}
