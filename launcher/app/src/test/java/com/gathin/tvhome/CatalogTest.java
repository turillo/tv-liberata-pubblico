package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** TV Liberata 1.0.5: the catalogue's second format (categories, platforms, store apps, per-ABI APKs). */
public class CatalogTest {

    private static final String JSON = "{\"versione\":2,\"app\":["
            + "{\"id\":\"vlc\",\"nome\":\"VLC\",\"categoria\":\"Video\",\"pacchetto\":\"org.videolan.vlc\",\"tipo\":\"libera\","
            + "\"piattaforme\":[\"fireos\",\"androidtv\"],\"versione\":\"3.7.1\",\"codice\":13070104,"
            + "\"apk\":\"https://x/v7.apk\",\"sha256\":\"AA\",\"firma\":\"cc\","
            + "\"varianti\":[{\"abi\":\"armeabi-v7a\",\"apk\":\"https://x/v7.apk\",\"sha256\":\"aa\"},"
            + "{\"abi\":\"arm64-v8a\",\"apk\":\"https://x/v8.apk\",\"sha256\":\"bb\"}]},"
            + "{\"id\":\"spotify\",\"nome\":\"Spotify\",\"categoria\":\"Musica\",\"pacchetto\":\"com.spotify.tv.android\",\"tipo\":\"store\"},"
            + "{\"id\":\"only-tv\",\"nome\":\"Solo TV\",\"pacchetto\":\"a.b\",\"tipo\":\"store\",\"piattaforme\":[\"androidtv\"]},"
            + "{\"id\":\"future\",\"nome\":\"Nuovo tipo\",\"pacchetto\":\"a.c\",\"tipo\":\"sconosciuto\",\"apk\":\"https://x\",\"sha256\":\"ab\"},"
            + "{\"id\":\"darkmate\",\"nome\":\"DarkMate\",\"pacchetto\":\"ar.tvplayer.tv\",\"tipo\":\"extra\","
            + "\"apk\":\"https://github.com/o/r/releases/download/extra/darkmate.apk\",\"sha256\":\"dd\"}]}";

    @Test
    public void readsTheSecondFormat() {
        List<Catalog.App> apps = Catalog.parse(JSON);
        assertEquals(4, apps.size());   // the unknown kind is left out
        Catalog.App vlc = apps.get(0);
        assertEquals("Video", vlc.category);
        assertEquals(13070104L, vlc.code);
        assertEquals(2, vlc.variants.size());
        assertTrue(apps.get(1).store());
        assertTrue(apps.get(2).on("androidtv"));
        assertFalse(apps.get(2).on("fireos"));
        assertTrue(apps.get(1).on("fireos"));   // no list: both
        assertEquals("extra", apps.get(3).type);
    }

    @Test
    public void picksTheApkForTheDevice() {
        Catalog.App vlc = Catalog.parse(JSON).get(0);
        assertArrayEquals(new String[]{"https://x/v8.apk", "bb"}, Catalog.pick(vlc, new String[]{"arm64-v8a", "armeabi-v7a"}));
        assertArrayEquals(new String[]{"https://x/v7.apk", "aa"}, Catalog.pick(vlc, new String[]{"armeabi-v7a", "armeabi"}));
        // No variant for this device: the single APK (the 32-bit one, which 1.0.2 also reads).
        assertArrayEquals(new String[]{"https://x/v7.apk", "aa"}, Catalog.pick(vlc, new String[]{"x86"}));
        assertNull(Catalog.pick(Catalog.parse(JSON).get(1), new String[]{"arm64-v8a"}));   // a store app: no APK
    }

    @Test
    public void saysInstallInstalledOrUpdate() {
        List<Catalog.App> apps = Catalog.parse(JSON);
        Catalog.App vlc = apps.get(0), spotify = apps.get(1);
        assertEquals(Catalog.INSTALL, Catalog.state(vlc, -1, null));
        assertEquals(Catalog.UPDATE, Catalog.state(vlc, 13070000L, "3.7.0"));
        assertEquals(Catalog.INSTALLED, Catalog.state(vlc, 13070104L, "3.7.1"));
        assertEquals(Catalog.INSTALLED, Catalog.state(spotify, 5, "1.0"));   // the store updates it
        Catalog.App noCode = apps.get(3);   // version name only
        noCode.version = "5.1.0";
        assertEquals(Catalog.UPDATE, Catalog.state(noCode, 1, "5.0.9"));
        assertEquals(Catalog.INSTALLED, Catalog.state(noCode, 1, "5.1.0"));
    }

    @Test
    public void theNewerFormatWins() {
        assertEquals(2, Catalog.format(JSON));
        assertEquals(1, Catalog.format("{\"app\":[]}"));
        assertEquals(0, Catalog.format("rotto"));
    }

    @Test
    public void theStorePage() {
        Catalog.App spotify = Catalog.parse(JSON).get(1);
        assertEquals("amzn://apps/android?p=com.spotify.tv.android", Catalog.storeUri(spotify, true));
        assertEquals("market://details?id=com.spotify.tv.android", Catalog.storeUri(spotify, false));
    }

    /** The catalogue the app ships with reads, and every APK app in it can be checked. */
    @Test
    public void theBundledCatalogueReads() throws Exception {
        File f = new File("src/main/res/raw/catalogo.json");
        List<Catalog.App> apps = Catalog.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        assertTrue(apps.size() >= 10);
        for (Catalog.App a : apps) {
            if (a.store()) continue;
            assertTrue(a.id + " has an APK for a 32-bit Fire TV", Catalog.pick(a, new String[]{"armeabi-v7a"}) != null);
        }
    }

    @Test
    public void releaseNotesWithoutMarkdown() {
        String notes = "- **Aggiornamenti** su Fire TV: TV Liberata scarica\n  la nuova versione.\n\n- `App`: nuova sezione.";
        assertEquals("• Aggiornamenti su Fire TV: TV Liberata scarica la nuova versione.\n• App: nuova sezione.",
                Updater.readable(notes));
        assertEquals("", Updater.readable(null));
    }

    @Test
    public void appsByName() {
        List<SyntaxAppsActivity.App> apps = new ArrayList<>(Arrays.asList(
                new SyntaxAppsActivity.App("b", "netflix", null), new SyntaxAppsActivity.App("a", "Aerial Views", null),
                new SyntaxAppsActivity.App("c", "YouTube", null)));
        SyntaxAppsActivity.sort(apps);
        assertEquals("Aerial Views", apps.get(0).label);
        assertEquals("netflix", apps.get(1).label);
    }
}
