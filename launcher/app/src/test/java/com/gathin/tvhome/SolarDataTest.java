package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Sun scene's flare parsing, decode size and the mask that hides SDO's caption. */
public class SolarDataTest {

    /** NOAA SWPC xray-flares-latest.json as served on 2026-11-04. */
    private static final String LATEST = "[{\"time_tag\": \"2026-11-04T13:43:00Z\", \"satellite\": 18, "
            + "\"current_class\": \"B5.6\", \"begin_time\": \"2026-11-04T06:43:00Z\", \"begin_class\": \"B5.6\", "
            + "\"max_time\": \"2026-11-04T06:54:00Z\", \"max_class\": \"C1.2\", \"max_xrlong\": 1.2e-06, "
            + "\"end_time\": \"2026-11-04T07:02:00Z\", \"end_class\": \"B8.6\"}]";

    @Test
    public void parsesLatestFlare() {
        SolarData.Flare flare = SolarData.parseFlare(LATEST);
        assertNotNull(flare);
        assertEquals("C1.2", flare.flareClass);
        assertEquals(1793775240000L, flare.peakMillis);   // 2026-11-04 06:54:00 UTC
    }

    @Test
    public void skipsFlareInProgressAndTakesLastOfWeek() {
        String inProgress = "[{\"begin_time\": \"2026-11-04T14:50:00Z\", \"max_time\": \"Unk\", \"max_class\": null}]";
        assertNull(SolarData.parseFlare(inProgress));
        String week = "[{\"max_time\": \"2026-11-03T22:39:00Z\", \"max_class\": \"C1.1\"}, "
                + "{\"max_time\": \"2026-11-04T06:54:00Z\", \"max_class\": \"C1.2\"}, "
                + "{\"max_time\": \"Unk\", \"max_class\": null}]";
        SolarData.Flare flare = SolarData.parseFlare(week);
        assertNotNull(flare);
        assertEquals("C1.2", flare.flareClass);
        assertEquals(1793775240000L, flare.peakMillis);
    }

    @Test
    public void rejectsMissingOrBrokenFlare() {
        assertNull(SolarData.parseFlare(null));
        assertNull(SolarData.parseFlare(""));
        assertNull(SolarData.parseFlare("[]"));
        assertNull(SolarData.parseFlare("[{\"max_class\": null, \"max_time\": null}]"));
        assertNull(SolarData.parseFlare("[{\"max_class\": \"C1.2\", \"max_time\": \"yesterday\"}]"));
        assertNull(SolarData.parseFlare("<html>503</html>"));
    }

    @Test
    public void parsesUtcWithAndWithoutSeconds() {
        assertEquals(1793775240000L, SolarData.parseUtc("2026-11-04T06:54:00Z"));
        assertEquals(1793775240000L, SolarData.parseUtc("2026-11-04T06:54Z"));
        assertEquals(-1, SolarData.parseUtc("2026-13-04T06:54:00Z"));
    }

    @Test
    public void sampleSizeKeepsAtLeastTarget() {
        assertEquals(1, SolarData.sampleSize(1024, 1046));
        assertEquals(1, SolarData.sampleSize(1024, 877));
        assertEquals(1, SolarData.sampleSize(2048, 1046));
        assertEquals(2, SolarData.sampleSize(2048, 1000));
        assertEquals(2, SolarData.sampleSize(1024, 512));
        assertEquals(4, SolarData.sampleSize(4096, 877));
        assertEquals(1, SolarData.sampleSize(1024, 0));
    }

    @Test
    public void maskHidesCaptionButKeepsDisk() {
        for (int i = 0; i < SolarData.CODES.length; i++) {
            float half = 512f;
            assertTrue(SolarData.CODES[i] + " caption", SolarData.MASK_OUT[i] * half < SolarData.CAPTION_PX[i] - 5);
            assertTrue(SolarData.CODES[i] + " disk", SolarData.DISK[i] < SolarData.MASK_IN[i]);
            assertTrue(SolarData.CODES[i] + " fade", SolarData.MASK_IN[i] < SolarData.MASK_OUT[i]);
        }
    }

    @Test
    public void tablesLineUp() {
        int n = SolarData.CODES.length;
        assertEquals(n, SolarData.DISK.length);
        assertEquals(n, SolarData.MASK_IN.length);
        assertEquals(n, SolarData.MASK_OUT.length);
        assertEquals(n, SolarData.CAPTION_PX.length);
    }
}
