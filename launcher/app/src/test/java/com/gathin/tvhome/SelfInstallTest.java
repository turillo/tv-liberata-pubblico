package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TV Liberata 1.0.1: the installer's "Installa" is pressed only for our own update, only for a short while. */
public class SelfInstallTest {

    @Test
    public void onlyAndroidsInstaller() {
        assertTrue(SelfInstall.installer("com.android.packageinstaller"));
        assertTrue(SelfInstall.installer("com.google.android.packageinstaller"));
        assertFalse(SelfInstall.installer("com.amazon.venezia"));
        assertFalse(SelfInstall.installer("com.android.settings"));
        assertFalse(SelfInstall.installer(null));
    }

    @Test
    public void theWindowIsShortAndEnds() {
        long now = 1_000_000L;
        assertFalse("never armed", SelfInstall.within(now, 0));
        assertTrue(SelfInstall.within(now, now + 60_000));
        assertTrue(SelfInstall.within(now, now + SelfInstall.WINDOW_MS));
        assertFalse("over", SelfInstall.within(now, now - 1));
        assertFalse("too far ahead: a wrong clock never arms it for long", SelfInstall.within(now, now + SelfInstall.WINDOW_MS + 1));
    }
}
