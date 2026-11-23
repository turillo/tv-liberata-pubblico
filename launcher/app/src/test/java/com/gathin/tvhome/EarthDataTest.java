package com.gathin.tvhome;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Earth scene: EPIC's list as served on 2026-11-04 (the 2026-11-01 photos), URLs, distance and regions. */
public class EarthDataTest {

    private static float[][] land;

    @BeforeClass
    public static void readMap() throws Exception {
        try (InputStream in = new FileInputStream("src/main/res/raw/world_land.bin")) {
            land = WorldMap.read(in);
        }
    }

    private static String fixture() throws Exception {
        try (InputStream in = EarthDataTest.class.getResourceAsStream("/epic_natural.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesTheDaysPhotos() throws Exception {
        List<EarthData.Shot> shots = EarthData.parse(fixture());
        assertEquals(13, shots.size());
        EarthData.Shot first = shots.get(0);
        assertEquals("epic_1b_20261101010436", first.image);
        assertEquals(EarthData.parseUtc("2026-11-01 00:59:48"), first.timeMillis);
        assertEquals(-5.632324, first.lat, 1e-6);
        assertEquals(150.812988, first.lon, 1e-6);
        assertEquals(1494367, first.distanceKm, 1);   // DSCOVR, about 1.49 million km
        for (int i = 1; i < shots.size(); i++) assertTrue(shots.get(i).timeMillis > shots.get(i - 1).timeMillis);
    }

    @Test
    public void utcAndUrl() throws Exception {
        assertEquals(1793533681000L, EarthData.parseUtc("2026-11-01 11:48:01"));
        assertEquals(-1, EarthData.parseUtc("2026-11-01T11:48:01Z"));
        EarthData.Shot noon = EarthData.parse(fixture()).get(6);
        assertEquals("https://epic.gsfc.nasa.gov/archive/natural/2026/11/01/jpg/epic_1b_20261101115250.jpg",
                EarthData.imageUrl(noon));
    }

    private static EarthData.Shot shot(String image, String utc) {
        return new EarthData.Shot(image, EarthData.parseUtc(utc), 0, 0, 1.5e6);
    }

    @Test
    public void sameDayEnhancedIsUsed() {
        List<EarthData.Shot> natural = Arrays.asList(
                shot("epic_1b_20261101010436", "2026-11-01 00:59:48"), shot("epic_1b_20261101224105", "2026-11-01 22:36:15"));
        List<EarthData.Shot> sameDay = Collections.singletonList(shot("epic_RGB_20261101000830", "2026-11-01 00:03:42"));
        List<EarthData.Shot> older = Collections.singletonList(shot("epic_RGB_20261029214459", "2026-10-29 21:40:10"));
        assertTrue(EarthData.useEnhanced(natural, sameDay));
        assertFalse(EarthData.useEnhanced(natural, older));
        assertFalse(EarthData.useEnhanced(natural, Collections.<EarthData.Shot>emptyList()));
        assertFalse(EarthData.useEnhanced(Collections.<EarthData.Shot>emptyList(), sameDay));
    }

    @Test
    public void enhancedPhotosHaveTheirOwnUrlAndDisk() {
        EarthData.Shot e = shot("epic_RGB_20261029105644", "2026-10-29 10:51:55");
        assertTrue(e.enhanced());
        assertEquals("https://epic.gsfc.nasa.gov/archive/enhanced/2026/10/29/jpg/epic_RGB_20261029105644.jpg",
                EarthData.imageUrl(e));
        assertEquals(EarthData.DISK_ENHANCED, e.disk(), 0);
        EarthData.Shot n = shot("epic_1b_20261101115250", "2026-11-01 11:48:01");
        assertFalse(n.enhanced());
        assertEquals(EarthData.DISK, n.disk(), 0);
    }

    @Test
    public void brokenListsAreEmpty() {
        assertTrue(EarthData.parse(null).isEmpty());
        assertTrue(EarthData.parse("").isEmpty());
        assertTrue(EarthData.parse("[]").isEmpty());
        assertTrue(EarthData.parse("<html>503</html>").isEmpty());
        // Unsafe file names are skipped rather than used in a path.
        assertTrue(EarthData.parse("[{\"identifier\":\"1\",\"image\":\"../x\",\"date\":\"2026-11-01 11:48:01\","
                + "\"centroid_coordinates\":{\"lat\":1,\"lon\":2}}]").isEmpty());
        // A photo without DSCOVR's position still shows, without a distance.
        List<EarthData.Shot> one = EarthData.parse("[{\"identifier\":\"1\",\"image\":\"epic_1b_1\",\"date\":"
                + "\"2026-11-01 11:48:01\",\"centroid_coordinates\":{\"lat\":1.5,\"lon\":-2}}]");
        assertEquals(1, one.size());
        assertEquals(0, one.get(0).distanceKm, 0);
    }

    private static int at(double lat, double lon) {
        return EarthData.region(lat, lon, EarthData.onLand(land, lat, lon));
    }

    @Test
    public void landAndSea() {
        assertTrue(EarthData.onLand(land, 41.9, 12.5));     // Rome
        assertTrue(EarthData.onLand(land, -25, 134));       // Australia
        assertFalse(EarthData.onLand(land, -5.87, -11.29)); // the noon photo's centre
        assertFalse(EarthData.onLand(land, 0, -150));
    }

    @Test
    public void regions() {
        assertEquals(EarthData.ATLANTIC, at(-5.87, -11.29));
        assertEquals(EarthData.PACIFIC, at(0, -150));
        assertEquals(EarthData.INDIAN, at(-20, 80));
        assertEquals(EarthData.MEDITERRANEAN, at(35, 18));
        assertEquals(EarthData.EUROPE, at(48, 10));
        assertEquals(EarthData.AFRICA, at(0, 20));
        assertEquals(EarthData.ASIA, at(30, 100));
        assertEquals(EarthData.OCEANIA, at(-25, 134));
        assertEquals(EarthData.NORTH_AMERICA, at(40, -100));
        assertEquals(EarthData.SOUTH_AMERICA, at(-10, -55));
        assertEquals(EarthData.ANTARCTICA, at(-80, 0));
    }
}
