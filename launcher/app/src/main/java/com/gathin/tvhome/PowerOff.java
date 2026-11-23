package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.util.Log;

import java.lang.ref.WeakReference;

/**
 * Home's power button: puts this device to sleep as its own remote's power key would. A third-party app cannot call
 * PowerManager.goToSleep, but one of this launcher's accessibility services can ask for GLOBAL_ACTION_LOCK_SCREEN
 * (Android 9 and later), which sends the device to sleep. On a Fire TV the stick goes into standby and, with HDMI-CEC
 * on, tells the TV to switch off; on a TV such as the Sony the TV itself goes into standby. No key is injected
 * (no KEYCODE_SLEEP, no KEYCODE_POWER).
 */
final class PowerOff {
    private static volatile WeakReference<AccessibilityService> live = new WeakReference<>(null);

    private PowerOff() {
    }

    /** One of the launcher's accessibility services is up (FireHomeGuard, HomeKeyService, InputKeyService). */
    static void connected(AccessibilityService s) {
        live = new WeakReference<>(s);
    }

    static void gone(AccessibilityService s) {
        if (live.get() == s) live = new WeakReference<>(null);
    }

    /** True when the button can work here: Android 9 or later and one of the services connected. */
    static boolean available() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && live.get() != null;
    }

    /** Sends the device to sleep; false when it cannot (no service, an older Android, or Android refused). */
    static boolean sleep() {
        AccessibilityService s = live.get();
        if (s == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            return s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        } catch (RuntimeException e) {
            Log.w("TvHome.Power", "sleep refused: " + e.getMessage());
            return false;
        }
    }
}
