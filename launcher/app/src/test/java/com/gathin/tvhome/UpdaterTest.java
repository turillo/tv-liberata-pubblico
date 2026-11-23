package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterTest {

    private static final String RELEASE = "{\"tag_name\":\"v1.2\",\"draft\":false,\"prerelease\":false,\"assets\":["
            + "{\"name\":\"note.txt\",\"browser_download_url\":\"https://github.com/x/y/releases/download/v1.2/note.txt\"},"
            + "{\"name\":\"firestick-liberata-1.2.apk\",\"browser_download_url\":"
            + "\"https://github.com/x/y/releases/download/v1.2/firestick-liberata-1.2.apk\"}]}";

    @Test
    public void readsTheLatestRelease() {
        Updater.Release r = Updater.parse(RELEASE);
        assertEquals("1.2", r.version);
        assertTrue(r.apkUrl.endsWith("firestick-liberata-1.2.apk"));
        assertNull(Updater.parse(RELEASE.replace("\"draft\":false", "\"draft\":true")));
        assertNull(Updater.parse(RELEASE.replace("\"prerelease\":false", "\"prerelease\":true")));
        assertNull(Updater.parse("{\"tag_name\":\"v1.3\",\"assets\":[]}"));
        assertNull(Updater.parse("{\"message\":\"Not Found\"}"));
        assertNull(Updater.parse(RELEASE.replace("https://github.com/x/y/releases/download/v1.2/firestick", "http://evil/f")));
    }

    @Test
    public void comparesVersionsByNumber() {
        assertTrue(Updater.newer("1.1", "1.0"));
        assertTrue(Updater.newer("1.10", "1.9"));
        assertTrue(Updater.newer("2", "1.9.9"));
        assertFalse(Updater.newer("1.0", "1.0"));
        assertFalse(Updater.newer("1.0.0", "1.0"));
        assertFalse(Updater.newer("0.9", "1.0"));
        assertTrue(Updater.newer("1.1-beta", "1.0"));
        assertEquals("1.2.0", Updater.version("V1.2.0"));
    }
}
