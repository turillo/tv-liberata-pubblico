package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HomeRestoreTest {

    private static final String GUARD = "com.gathin.tvhome.liberata/com.gathin.tvhome.FireHomeGuard";

    @Test
    public void addsTheGuardOnlyWhenMissing() {
        assertEquals(GUARD, HomeRestore.withGuard(null, GUARD));
        assertEquals(GUARD, HomeRestore.withGuard("null", GUARD));
        assertEquals("a/.B:" + GUARD, HomeRestore.withGuard("a/.B", GUARD));
        assertNull(HomeRestore.withGuard("a/.B:" + GUARD, GUARD));
        assertNull(HomeRestore.withGuard("x/x.Y:com.gathin.tvhome.liberata/com.gathin.tvhome.FireHomeGuard", GUARD));
        // the owner's build's Guard is another app's: not the same component
        assertEquals("com.gathin.tvhome/.FireHomeGuard:" + GUARD,
                HomeRestore.withGuard("com.gathin.tvhome/.FireHomeGuard", GUARD));
        assertNull(HomeRestore.withGuard("p/.C", "p/p.C"));
    }
}
