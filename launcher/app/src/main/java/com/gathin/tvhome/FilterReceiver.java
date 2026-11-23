package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The DNS filter from the PC, for the set-up scripts (protected by WRITE_SECURE_SETTINGS, which only the adb shell
 * has; the VPN consent must be granted first with "appops set &lt;package&gt; ACTIVATE_VPN allow"):
 * <pre>
 * am broadcast -n &lt;package&gt;/com.gathin.tvhome.FilterReceiver -a com.gathin.tvhome.FILTER --ez on true [--ez ota true]
 * </pre>
 * The reply says whether it is on, and whether the consent is there.
 */
public final class FilterReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        if (intent.hasExtra("ota")) FilterSettings.setOta(c, intent.getBooleanExtra("ota", true));
        if (intent.hasExtra("on")) {
            boolean on = intent.getBooleanExtra("on", false);
            if (on && FilterSettings.consent(c) != null) {
                setResultData("consenso VPN mancante: appops set " + c.getPackageName() + " ACTIVATE_VPN allow");
                return;
            }
            FilterSettings.set(c, on);
        }
        setResultData("filtro " + (FilterSettings.on(c) ? "acceso" : "spento") + ", aggiornamenti Fire OS "
                + (FilterSettings.ota(c) ? "bloccati" : "permessi"));
    }
}
