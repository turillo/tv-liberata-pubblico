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

/**
 * The Moon scene: Dial-a-Moon's reply for 2026-11-04 16:00 UTC, and the TV's own Moon maths checked against
 * Dial-a-Moon (phase 0.19% at the computed new Moon, 99.81% at the full Moon, the same distance and position).
 */
public class MoonDataTest {

    private static final long NOV4_16H = Instant.parse("2026-11-04T16:00:00Z").toEpochMilli();
    private static final double LAT = 41.90, LON = 12.50;   // config.xml latitude and longitude

    private static String fixture() throws Exception {
        try (InputStream in = MoonDataTest.class.getResourceAsStream("/dialamoon.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesDialAMoon() throws Exception {
        MoonData.Reading r = MoonData.parse(fixture());
        assertNotNull(r);
        // The north-up picture ("image", not the south-up "su_image"), over http (the server is TLS 1.3 only).
        assertEquals("http://svs.gsfc.nasa.gov/vis/a000000/a005500/a005587/frames/730x730_1x1_30p/moon.7385.jpg", r.imageUrl);
        assertEquals(21.18, r.phase, 1e-9);
        assertEquals(25.007, r.ageDays, 1e-9);
        assertEquals(382372.0, r.distanceKm, 1e-9);
    }

    @Test
    public void rejectsBrokenReplies() {
        assertNull(MoonData.parse(null));
        assertNull(MoonData.parse("<html>503</html>"));
        assertNull(MoonData.parse("{\"image\":{\"url\":\"https://example.com/moon.jpg\"},\"phase\":21,\"age\":2,\"distance\":380000}"));
        assertNull(MoonData.parse("{\"image\":{\"url\":\"https://svs.gsfc.nasa.gov/../x.jpg\"},\"phase\":21,\"age\":2,\"distance\":380000}"));
        assertNull(MoonData.parse("{\"image\":{\"url\":\"https://svs.gsfc.nasa.gov/m.jpg\"},\"phase\":121,\"age\":2,\"distance\":380000}"));
    }

    @Test
    public void apiUrlIsTheUtcHour() {
        assertEquals("http://svs.gsfc.nasa.gov/api/dialamoon/2026-11-04T16:00",
                MoonData.apiUrl(Instant.parse("2026-11-04T16:47:12Z").toEpochMilli()));
    }

    @Test
    public void phaseNames() {
        assertEquals(MoonData.NEW, MoonData.phaseName(0.5, true));
        assertEquals(MoonData.WAXING_CRESCENT, MoonData.phaseName(20, true));
        assertEquals(MoonData.FIRST_QUARTER, MoonData.phaseName(50, true));
        assertEquals(MoonData.WAXING_GIBBOUS, MoonData.phaseName(80, true));
        assertEquals(MoonData.FULL, MoonData.phaseName(99.5, false));
        assertEquals(MoonData.WANING_GIBBOUS, MoonData.phaseName(80, false));
        assertEquals(MoonData.LAST_QUARTER, MoonData.phaseName(50, false));
        assertEquals(MoonData.WANING_CRESCENT, MoonData.phaseName(21.18, MoonData.waxing(25.007)));
        assertTrue(MoonData.waxing(3));
        assertFalse(MoonData.waxing(20));
    }

    @Test
    public void nextNewAndFullMoon() {
        long newMoon = MoonData.next(NOV4_16H, false);
        long fullMoon = MoonData.next(NOV4_16H, true);
        // Dial-a-Moon: 0.19% lit at 2026-11-09 07:00 UTC, 99.81% at 2026-11-24 15:00 UTC.
        assertEquals(Instant.parse("2026-11-09T07:02:00Z").toEpochMilli(), newMoon, 10 * 60_000L);
        assertEquals(Instant.parse("2026-11-24T14:54:00Z").toEpochMilli(), fullMoon, 10 * 60_000L);
        assertTrue(MoonData.next(newMoon, false) - newMoon > 29 * 86_400_000L);
    }

    @Test
    public void positionMatchesDialAMoon() {
        double[] p = MoonData.position(NOV4_16H);
        assertEquals(382372.0, p[2], 300);                          // Dial-a-Moon distance
        assertEquals(11.1695 * 15, Math.toDegrees(p[0]), 0.6);     // J2000 RA; ours is of date (precession)
        assertEquals(3.4056, Math.toDegrees(p[1]), 0.3);
        assertEquals(21.18, MoonData.illumination(NOV4_16H), 1.5);
        assertEquals(25.007, MoonData.ageDays(NOV4_16H), 0.1);
    }

    @Test
    public void riseAndSetAtHome() {
        long[] rs = MoonData.nextRiseSet(Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), LAT, LON);
        // At 01:00 UTC Dial-a-Moon's position puts the Moon 5.2 degrees up, at 14:00 UTC 4.5 degrees down.
        assertEquals(Instant.parse("2026-11-04T00:33:00Z").toEpochMilli(), rs[0], 5 * 60_000L);
        assertEquals(Instant.parse("2026-11-04T13:36:00Z").toEpochMilli(), rs[1], 5 * 60_000L);
        assertTrue(MoonData.aboveHorizon(rs[0] + 30 * 60_000L, LAT, LON) > 0);
        assertTrue(MoonData.aboveHorizon(rs[1] + 30 * 60_000L, LAT, LON) < 0);
    }
}
