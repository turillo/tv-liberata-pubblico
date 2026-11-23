package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceConfigTest {

    @Test
    public void readsTheDeviceFile() {
        DeviceConfig.Values v = DeviceConfig.parse("{\"nome\":\" Firestick-Cucina \",\"canale\":\"TEST\","
                + "\"latitudine\":41.9,\"longitudine\":12.5,\"citta\":\"Roma\"}");
        assertEquals("Firestick-Cucina", v.name);
        assertEquals(DeviceConfig.TEST, v.channel);
        assertEquals(41.9, v.lat, 1e-9);
        assertEquals("Roma", v.city);
        DeviceConfig.Values d = DeviceConfig.parse("{\"nome\":\"Chicco 1\"}");
        assertEquals(DeviceConfig.PRODUCTION, d.channel);   // the default channel
        assertTrue(Double.isNaN(d.lat));
        assertTrue(Double.isNaN(DeviceConfig.parse("{\"latitudine\":0,\"longitudine\":0}").lat));
        assertNull(DeviceConfig.parse("rotto"));
    }

    @Test
    public void updateChannels() {
        String list = "[{\"tag_name\":\"v1.1\",\"prerelease\":true,\"assets\":[{\"name\":\"tv-liberata.apk\","
                + "\"browser_download_url\":\"https://github.com/a/b/releases/download/v1.1/tv-liberata.apk\"}]},"
                + "{\"tag_name\":\"v1.0\",\"prerelease\":false,\"assets\":[{\"name\":\"tv-liberata.apk\","
                + "\"browser_download_url\":\"https://github.com/a/b/releases/download/v1.0/tv-liberata.apk\"}]},"
                + "{\"tag_name\":\"v2.0\",\"draft\":true,\"assets\":[{\"name\":\"x.apk\",\"browser_download_url\":\"https://x/x.apk\"}]}]";
        assertEquals("1.1", Updater.newest(list).version);   // test channel: pre-release included, drafts never
        assertNull(Updater.newest("[]"));
        assertEquals("1.0", Updater.productionVersion("{\"versione\":\"v1.0\"}"));
        assertNull(Updater.productionVersion("{}"));
    }
}
