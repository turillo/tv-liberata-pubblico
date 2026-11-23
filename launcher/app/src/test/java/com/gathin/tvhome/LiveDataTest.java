package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The live Earth scene: EUMETView's capabilities as served on 2026-11-04, the URLs, the two projections, Rome. */
public class LiveDataTest {

    private static final double ROME_LAT = 41.90, ROME_LON = 12.50;

    private static String fixture() throws Exception {
        try (InputStream in = LiveDataTest.class.getResourceAsStream("/eumetsat_caps.xml")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void latestTimeFromCapabilities() throws Exception {
        assertEquals(Instant.parse("2026-11-04T16:30:00Z").toEpochMilli(), LiveData.latestTime(fixture()));
        assertEquals(Instant.parse("2026-11-04T16:40:00Z").toEpochMilli(),
                LiveData.latestTime("<Dimension name=\"time\" default=\"2026-11-04T16:40:00.000Z\" units=\"ISO8601\">"));
        assertEquals(-1, LiveData.latestTime(null));
        assertEquals(-1, LiveData.latestTime("<html>503</html>"));
        assertEquals(-1, LiveData.latestTime("<Dimension name=\"time\" default=\"yesterday\">"));
    }

    @Test
    public void latestTimeFromWorkspaceCapabilities() throws Exception {
        // The mtg_fd workspace listing (trimmed to two layers; the first one's time set to 16:10 so they differ).
        String ws;
        try (InputStream in = LiveDataTest.class.getResourceAsStream("/eumetsat_ws_caps.xml")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            ws = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        assertEquals(Instant.parse("2026-11-04T17:00:00Z").toEpochMilli(), LiveData.latestTime(ws));
    }

    @Test
    public void capsUrlChangesEveryMinute() {
        long t = Instant.parse("2026-11-04T17:27:14Z").toEpochMilli();
        assertEquals(LiveData.CAPS_URLS[0] + "&_=" + (t / 60_000L), LiveData.capsUrl(0, t));
        assertEquals(LiveData.capsUrl(1, t), LiveData.capsUrl(1, t + 30_000L - (t % 60_000L) + 1));
    }

    @Test
    public void mapUrls() {
        long t = Instant.parse("2026-11-04T11:00:00Z").toEpochMilli();
        assertEquals("https://view.eumetsat.int/geoserver/ows?service=WMS&version=1.3.0&request=GetMap&styles=&format=image/jpeg"
                + "&layers=mtg_fd:rgb_geocolour&crs=EPSG:4326&bbox=-70,-70,70,70&width=1200&height=1200&time=2026-11-04T11:00:00Z",
                LiveData.mapUrl(LiveData.DISK, t));
        // The Europe box checked by hand with curl on 2026-11-04: Italy in the middle, Iberia to Turkey.
        assertEquals("https://view.eumetsat.int/geoserver/ows?service=WMS&version=1.3.0&request=GetMap&styles=&format=image/jpeg"
                + "&layers=mtg_fd:rgb_geocolour&crs=EPSG:3857&bbox=-1558473,3501623,4341460,6820336&width=1920&height=1080"
                + "&time=2026-11-04T11:00:00Z", LiveData.mapUrl(LiveData.EUROPE, t));
    }

    @Test
    public void romeInEurope() {
        float[] centre = LiveData.europePoint(42.0, 12.5, 1920, 1080);
        assertEquals(960, centre[0], 0.5);
        assertEquals(540, centre[1], 0.5);
        float[] rome = LiveData.europePoint(ROME_LAT, ROME_LON, 1920, 1080);
        assertEquals(960, rome[0], 2);
        assertTrue(rome[1] > 540 && rome[1] < 560);
    }

    @Test
    public void diskProjection() {
        double[] centre = LiveData.diskToGeo(0, 0);
        assertNotNull(centre);
        assertEquals(0, centre[0], 1e-9);
        assertEquals(0, centre[1], 1e-9);
        assertNull(LiveData.diskToGeo(0.8, 0.8));       // past the limb
        double[] north = LiveData.diskToGeo(0, 0.99);
        assertTrue(north[0] > 70 && north[0] < 90);
        double[] rome = LiveData.geoToDisk(ROME_LAT, ROME_LON);
        assertNotNull(rome);
        assertTrue(rome[0] > 0.1 && rome[0] < 0.3);      // east of the centre
        assertTrue(rome[1] > 0.6 && rome[1] < 0.8);      // well up towards the north
        double[] back = LiveData.diskToGeo(rome[0], rome[1]);
        assertEquals(ROME_LAT, back[0], 1e-6);
        assertEquals(ROME_LON, back[1], 1e-6);
        assertNull(LiveData.geoToDisk(0, 120));           // the far side
    }

    @Test
    public void diskTable() {
        int[] table = LiveData.diskTable(100, 1200);
        assertEquals(LiveData.SPACE, table[0]);                                // corner: space
        int centre = table[50 * 100 + 50];                                     // half a pixel from latitude 0, longitude 0
        assertEquals(600, centre % 1200, 6);
        assertEquals(600, centre / 1200, 6);
        assertEquals(LiveData.NO_DATA, table[0 * 100 + 50]);                   // top edge: north of 70 degrees
        int earth = 0;
        for (int i : table) if (i != LiveData.SPACE) earth++;
        assertEquals(Math.PI / 4, earth / 10000.0, 0.02);                      // a round disk
    }

    @Test
    public void dayAndNightInRome() {
        assertTrue(LiveData.daylight(ROME_LAT, ROME_LON, Instant.parse("2026-11-04T11:00:00Z").toEpochMilli()));
        assertFalse(LiveData.daylight(ROME_LAT, ROME_LON, Instant.parse("2026-11-04T19:00:00Z").toEpochMilli()));
    }
}
