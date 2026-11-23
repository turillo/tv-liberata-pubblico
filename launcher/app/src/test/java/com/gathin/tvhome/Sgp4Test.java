package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** SGP4 against the published test case 00005 of Vallado et al., "Revisiting Spacetrack Report #3" (2006). */
public class Sgp4Test {

    private static final String TLE_00005 =
            "1 00005U 58002B   00179.78495062  .00000023  00000-0  28098-4 0  4753\n"
                    + "2 00005  34.2682 348.7242 1859667 331.7664  19.3264 10.82419157413667\n";

    /** tsince (min), then x, y, z (km) and vx, vy, vz (km/s), from the report's tcppver.out. */
    private static final double[][] EXPECTED = {
            {0, 7022.46529266, -1400.08296755, 0.03995155, 1.893841015, 6.405893759, 4.534807250},
            {360, -7154.03120202, -3783.17682504, -3536.19412294, 4.741887409, -4.151817765, -2.093935425},
            {720, -7134.59340119, 6531.68641334, 3260.27186483, -4.113793027, -2.911922039, -2.557327851},
            {1080, 5568.53901181, 4492.06992591, 3863.87641983, -4.209106476, 5.159719888, 2.744852980},
            {1440, -938.55923943, -6268.18748831, -4294.02924751, 7.536105209, -0.427127707, 0.989878080},
    };

    @Test
    public void matchesVallado00005() {
        Sgp4 sat = Sgp4.parse(TLE_00005);
        for (double[] e : EXPECTED) {
            Sgp4.State s = sat.propagate(e[0]);
            String at = "t=" + e[0];
            assertEquals(at, e[1], s.x, 1e-4);
            assertEquals(at, e[2], s.y, 1e-4);
            assertEquals(at, e[3], s.z, 1e-4);
            assertEquals(at, e[4], s.vx, 1e-7);
            assertEquals(at, e[5], s.vy, 1e-7);
            assertEquals(at, e[6], s.vz, 1e-7);
        }
    }

    @Test
    public void readsNameEpochAndCatalog() {
        Sgp4 iss = Sgp4.parse(OrbitTest.ISS_TLE);
        assertEquals("ISS (ZARYA)", iss.name);
        assertEquals(25544, iss.catalog);
        // 2026 day 307.53092409 = 3 November 2026, 12:44:31.8 UTC.
        assertEquals(Sgp4.julianDate(2026, 11, 3, 12, 44, 31.84), iss.epochJd, 1e-5);
    }

    @Test
    public void checksumCatchesATypo() {
        String line = "1 25544U 98067A   26307.53092409  .00005389  00000+0  10530-3 0  9996";
        assertTrue(Sgp4.checksumOk(line));
        assertFalse(Sgp4.checksumOk(line.replace("26307", "26308")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsABrokenTle() {
        Sgp4.parse(OrbitTest.ISS_TLE.replace("51.6330", "51.6331"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingLines() {
        Sgp4.parse("ISS (ZARYA)\n");
    }
}
