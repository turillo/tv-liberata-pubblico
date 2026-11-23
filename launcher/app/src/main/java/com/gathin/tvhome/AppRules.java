package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which apps Home must not lose: the app stores (Amazon Appstore on Fire OS, Google Play on Android TV) always have a
 * tile and can be neither hidden nor uninstalled nor disabled from here, and TV Liberata itself is never offered.
 */
final class AppRules {

    static final List<String> STORES = Collections.unmodifiableList(Arrays.asList("com.amazon.venezia", "com.android.vending"));

    private AppRules() {
    }

    static boolean store(String pkg) {
        return STORES.contains(pkg);
    }

    /** Offered "Disinstalla" in the tile menu: an app the user installed (not part of the system), not a store, not us. */
    static boolean uninstallable(Context c, String pkg) {
        if (pkg == null || store(pkg) || pkg.equals(c.getPackageName()) || pkg.startsWith("com.gathin.tvhome")) return false;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
