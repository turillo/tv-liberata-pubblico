package com.gathin.tvhome;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads res/raw/world_land.bin, the land outlines of Natural Earth's 110m map (public domain), simplified by
 * tools/natural_earth_map.py. Pure Java, so WorldMapTest can read the file on the JVM.
 *
 * Format, big-endian: int16 ring count, then per ring an int16 point count and the points as int16
 * (longitude, latitude) in units of 1/UNITS degree, the first absolute and the rest as differences.
 */
final class WorldMap {

    static final int UNITS = 50;

    private WorldMap() {
    }

    /** The rings, each as {lon0, lat0, lon1, lat1, ...} in degrees. */
    static float[][] read(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int count = data.readShort();
        if (count <= 0 || count > 10_000) throw new IOException("bad ring count " + count);
        float[][] rings = new float[count][];
        for (int r = 0; r < count; r++) {
            int n = data.readShort();
            if (n <= 0 || n > 20_000) throw new IOException("bad point count " + n);
            float[] ring = new float[n * 2];
            int x = 0, y = 0;
            for (int i = 0; i < n; i++) {
                x += data.readShort();
                y += data.readShort();
                ring[2 * i] = x / (float) UNITS;
                ring[2 * i + 1] = y / (float) UNITS;
            }
            rings[r] = ring;
        }
        return rings;
    }
}
