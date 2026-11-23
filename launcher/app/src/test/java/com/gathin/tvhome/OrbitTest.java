package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Earth geometry for the ISS scene, checked against api.wheretheiss.at for the same TLE and instant
 * (2026-11-03 20:57:55 UTC): it reported 46.6770 N 94.6537 E, 426.99 km, 27,574 km/h, eclipsed, and the sun
 * overhead at 15.2657 S 221.4078 E.
 */
public class OrbitTest {

    static final String ISS_TLE = "ISS (ZARYA)\n"
            + "1 25544U 98067A   26307.53092409  .00005389  00000+0  10530-3 0  9996\n"
            + "2 25544  51.6330 330.5488 0007153 343.6919  16.3838 15.49164192593358\n";
    private static final long WHEN = 1793739475_000L;

    @Test
    public void issPositionMatchesWhereTheIssAt() {
        Sgp4 iss = Sgp4.parse(ISS_TLE);
        Sgp4.State s = iss.at(WHEN);
        Orbit.Geo g = Orbit.geodetic(s, WHEN);
        assertEquals(46.6770, g.lat, 0.02);
        assertEquals(94.6537, g.lon, 0.02);
        assertEquals(426.99, g.altKm, 0.5);
        assertEquals(27574.3, s.speedKmS() * 3600, 5);
        assertFalse(Orbit.sunlit(s, WHEN));
    }

    @Test
    public void subsolarPointMatchesWhereTheIssAt() {
        double[] sun = Orbit.subsolar(WHEN);
        assertEquals(-15.2657, sun[0], 0.05);
        assertEquals(221.4078 - 360, sun[1], 0.1);
    }

    @Test
    public void sunElevationAtTheSubsolarPointIsNinety() {
        assertEquals(90, Orbit.sunElevation(-15, 40, -15, 40), 1e-6);
        assertEquals(-90, Orbit.sunElevation(15, -140, -15, 40), 1e-6);
    }

    @Test
    public void romeToMilanIsAbout480Km() {
        double km = Orbit.groundDistanceKm(41.9028, 12.4964, 45.4642, 9.1900);
        assertTrue("km " + km, km > 470 && km < 485);
    }

    @Test
    public void overheadIsNinetyDegreesUp() {
        double[] above = Orbit.ecef(41.90, 12.50, 420);
        Orbit.Look look = Orbit.look(41.90, 12.50, 0, above);
        assertEquals(90, look.elevation, 0.01);
        assertEquals(420, look.rangeKm, 0.01);
    }

    @Test
    public void northIsAzimuthZero() {
        double[] north = Orbit.ecef(43.0, 12.50, 420);
        Orbit.Look look = Orbit.look(41.90, 12.50, 0, north);
        assertEquals(0, look.azimuth > 180 ? look.azimuth - 360 : look.azimuth, 0.5);
        assertTrue(look.elevation > 0);
    }

    @Test
    public void geodeticRoundTrips() {
        Orbit.Geo g = Orbit.geodetic(Orbit.ecef(41.90, 12.50, 0.05));
        assertEquals(41.90, g.lat, 1e-6);
        assertEquals(12.50, g.lon, 1e-6);
        assertEquals(0.05, g.altKm, 1e-6);
    }

    @Test
    public void longitudesWrap() {
        assertEquals(-170, Orbit.normalizeLon(190), 1e-9);
        assertEquals(170, Orbit.normalizeLon(-190), 1e-9);
        assertEquals(0, Orbit.normalizeLon(360), 1e-9);
    }
}
