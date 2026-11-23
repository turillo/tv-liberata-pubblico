package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdaterTest {

    private static final String RELEASE = "{\"tag_name\":\"v1.2\",\"draft\":false,\"prerelease\":false,\"assets\":["
            + "{\"name\":\"note.txt\",\"browser_download_url\":\"https://github.com/x/y/releases/download/v1.2/note.txt\"},"
            + "{\"name\":\"tv-liberata.apk\",\"browser_download_url\":"
            + "\"https://github.com/x/y/releases/download/v1.2/tv-liberata.apk\"}]}";

    @Test
    public void readsTheLatestRelease() {
        Updater.Release r = Updater.parse(RELEASE);
        assertEquals("1.2", r.version);
        assertTrue(r.apkUrl.endsWith("tv-liberata.apk"));
        assertNull(Updater.parse(RELEASE.replace("\"draft\":false", "\"draft\":true")));
        assertNull(Updater.parse(RELEASE.replace("\"prerelease\":false", "\"prerelease\":true")));
        assertNull(Updater.parse("{\"tag_name\":\"v1.3\",\"assets\":[]}"));
        assertNull(Updater.parse("{\"message\":\"Not Found\"}"));
        assertNull(Updater.parse(RELEASE.replace("https://github.com/x/y/releases/download/v1.2/tv-liberata", "http://evil/t")));
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

    /** The repository's "extra" release (other apps) is never taken for an update of TV Liberata. */
    @Test
    public void extraReleaseIsNotAnUpdate() {
        String list = "[{\"tag_name\":\"extra\",\"prerelease\":false,\"assets\":[{\"name\":\"darkmate.apk\","
                + "\"browser_download_url\":\"https://github.com/o/r/releases/download/extra/darkmate.apk\"}]},"
                + "{\"tag_name\":\"v1.0.5\",\"prerelease\":true,\"assets\":[{\"name\":\"tv-liberata.apk\","
                + "\"browser_download_url\":\"https://github.com/o/r/releases/download/v1.0.5/tv-liberata.apk\"}]}]";
        assertEquals("1.0.5", Updater.newest(list).version);
        assertNull(Updater.parse("{\"tag_name\":\"extra\",\"assets\":[{\"name\":\"darkmate.apk\","
                + "\"browser_download_url\":\"https://x/darkmate.apk\"}]}"));
    }
}
