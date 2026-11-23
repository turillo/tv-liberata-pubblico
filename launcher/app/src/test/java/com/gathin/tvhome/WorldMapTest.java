package com.gathin.tvhome;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.InputStream;

import static org.junit.Assert.assertTrue;

/** res/raw/world_land.bin (tools/natural_earth_map.py) reads back as sane land outlines. */
public class WorldMapTest {

    @Test
    public void mapReadsBackInsideTheWorld() throws Exception {
        float[][] rings;
        try (InputStream in = new FileInputStream("src/main/res/raw/world_land.bin")) {
            rings = WorldMap.read(in);
            assertTrue("trailing bytes", in.read() < 0);
        }
        assertTrue("rings " + rings.length, rings.length > 100);
        int points = 0;
        boolean italy = false;
        for (float[] ring : rings) {
            points += ring.length / 2;
            for (int i = 0; i < ring.length; i += 2) {
                assertTrue(ring[i] >= -180 && ring[i] <= 180);
                assertTrue(ring[i + 1] >= -90 && ring[i + 1] <= 90);
                if (Math.abs(ring[i] - 12.3) < 0.6 && Math.abs(ring[i + 1] - 41.7) < 0.6) italy = true;
            }
        }
        assertTrue("points " + points, points > 2000 && points < 6000);
        assertTrue("a coast point near Rome", italy);
    }
}
