package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppManagementTest {

    @Test
    public void storesAreProtected() {
        assertTrue(AppRules.store("com.amazon.venezia"));
        assertTrue(AppRules.store("com.android.vending"));
        assertFalse(AppRules.store("com.netflix.ninja"));
    }

    @Test
    public void protectedSystemApps() {
        Set<String> never = SystemApps.parseList("# x\ncom.amazon.tv.launcher   # home\n\ncom.amazon.tv.routing\n");
        assertEquals(2, never.size());
        assertTrue(SystemApps.protectedPkg("com.amazon.tv.launcher", never, null));
        assertTrue(SystemApps.protectedPkg("com.amazon.venezia", never, null));
        assertTrue(SystemApps.protectedPkg("com.gathin.tvhome.liberata", never, null));
        assertTrue(SystemApps.protectedPkg("com.android.systemui", never, null));
        assertTrue(SystemApps.protectedPkg("com.google.android.gms", never, null));
        assertTrue(SystemApps.protectedPkg("com.amazon.keyboard", never, "com.amazon.keyboard"));
        assertTrue(SystemApps.protectedPkg("com.cvte.tv.hkcfac.factory", never, null));
        assertFalse(SystemApps.protectedPkg("com.amazon.cloud9", never, null));
        assertEquals("pm disable-user --user 0 'com.x'", SystemApps.command("com.x", true));
        assertEquals("pm enable 'com.x'", SystemApps.command("com.x", false));
        assertTrue(SystemApps.succeeded("Package com.x new state: disabled-user", true));
        assertTrue(SystemApps.succeeded("Package com.x new state: enabled", false));
        assertFalse(SystemApps.succeeded("Error: java.lang.SecurityException", true));
        assertEquals("'it'\\''s'", AdbLocal.quote("it's"));
    }

    @Test
    public void adbPacketsAndKeys() throws Exception {
        byte[] p = AdbLocal.packet(0x4e584e43, 0x01000000, 4096, "host::\0".getBytes("UTF-8"));
        ByteBuffer b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x4e584e43, b.getInt());
        assertEquals(0x01000000, b.getInt());
        assertEquals(4096, b.getInt());
        assertEquals(7, b.getInt());
        int sum = 0;
        for (byte x : "host::\0".getBytes("UTF-8")) sum += x & 0xFF;
        assertEquals(sum, b.getInt());
        assertEquals(~0x4e584e43, b.getInt());
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        String key = AdbLocal.publicKeyString((RSAPublicKey) g.generateKeyPair().getPublic(), "tvliberata@tv");
        assertTrue(key.endsWith(" tvliberata@tv\0"));
        byte[] raw = Base64.getDecoder().decode(key.substring(0, key.indexOf(' ')));
        assertEquals(4 + 4 + 256 + 256 + 4, raw.length);   // Android's RSAPublicKey struct
        assertEquals(64, ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    @Test
    public void catalogue() {
        List<Catalog.App> apps = Catalog.parse("{\"versione\":1,\"app\":[{\"id\":\"aerial-views\",\"nome\":\"Aerial Views\","
                + "\"pacchetto\":\"com.neilturner.aerialviews\",\"versione\":\"1.8.6\",\"apk\":\"https://github.com/x.apk\","
                + "\"sha256\":\"CD53\",\"firma\":\"38:3B\"},{\"nome\":\"No check\",\"pacchetto\":\"a.b\",\"apk\":\"https://x/y.apk\"},"
                + "{\"nome\":\"Plain http\",\"pacchetto\":\"a.c\",\"apk\":\"http://x/y.apk\",\"sha256\":\"ab\"}]}");
        assertEquals(1, apps.size());
        assertEquals("cd53", apps.get(0).sha256);
        assertEquals("383b", apps.get(0).signer);
        assertTrue(Catalog.parse("rotto").isEmpty());
        assertEquals("0aff", Catalog.hex(new byte[]{10, (byte) 255}));
    }
}
