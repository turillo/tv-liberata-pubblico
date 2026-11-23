package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/**
 * What kind of TV this is, for the few places that do less on a small one. A Fire TV Stick has about 0.9 GB of
 * RAM, the Sony BRAVIA about 1.9 GB: under {@link #LITE_RAM_MB} the launcher keeps fewer time-lapse frames,
 * fewer snowflakes and a smaller thumbnail cache. Anything bigger runs exactly as before.
 */
final class Device {
    static final long LITE_RAM_MB = 1200;
    private static long ramMb = -1;

    private Device() {
    }

    /** Amazon's Fire OS: no Somnambulator, and Home is Amazon's unless an accessibility helper steps in. */
    static boolean isFireOs() {
        return "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * The Fire TV extras (13 November 2026, for the Fire TV sticks): profile and apps row position, kitchen panel,
     * recycling, screen margins, hidden apps, power button. The TVs (Sony and the like, Android TV) do without them,
     * whatever was saved, and look as they did before.
     */
    static boolean fireExtras() {
        return true;   // TV Liberata 1.0: margins, hidden apps and power on every device
    }

    /** Total RAM in MB, read once; 0 when Android will not say. */
    static synchronized long ramMb(Context c) {
        if (ramMb < 0) {
            ramMb = 0;
            try {
                ActivityManager am = c.getSystemService(ActivityManager.class);
                ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
                if (am != null) {
                    am.getMemoryInfo(info);
                    ramMb = info.totalMem >> 20;
                }
            } catch (RuntimeException ignored) {
                // unknown: treated as a full-size TV
            }
        }
        return ramMb;
    }

    /** A small TV: under LITE_RAM_MB of RAM, or one Android itself calls low on memory. */
    static boolean lite(Context c) {
        long mb = ramMb(c);
        if (mb > 0 && mb < LITE_RAM_MB) return true;
        try {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            return am != null && am.isLowRamDevice();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
