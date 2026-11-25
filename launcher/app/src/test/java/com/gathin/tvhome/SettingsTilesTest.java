package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TV Liberata 1.0.9: the settings page's tiles always fit, whole, on any screen. */
public class SettingsTilesTest {

    private static float need(float[] m) {
        return 2 * m[0] + m[2] + m[5] + m[3] * 1.35f + m[6] + m[4] * 1.35f;
    }

    @Test
    public void contentFitsInsideTheTile() {
        // 1280x720 dp at density 1 and 1.5 (Sony), 960x540 dp at density 2 (Fire TV), and very short tiles.
        int[][] sizes = {{330, 150}, {495, 225}, {600, 273}, {300, 90}, {280, 60}, {200, 40}};
        for (float density : new float[]{1f, 1.5f, 2f}) {
            for (int[] s : sizes) {
                float[] m = SyntaxActivity.tileMetrics(s[0], s[1], density);
                assertTrue(s[0] + "x" + s[1] + " @" + density + ": " + need(m), need(m) <= s[1] + 0.5f);
                assertTrue(m[3] > m[4]);   // the name stays larger than the status line
            }
        }
    }

    @Test
    public void iconShrinksBeforeTheTexts() {
        float[] roomy = SyntaxActivity.tileMetrics(330, 150, 1f);
        float[] tight = SyntaxActivity.tileMetrics(330, 105, 1f);
        assertTrue(tight[2] < roomy[2] * 0.75f);
        assertEquals(Math.min(105 * 0.14f, 22f), tight[3], 0.01f);   // the name not shrunk yet
    }

    @Test
    public void rowsFitTheRoomLeft() {
        assertEquals(150, SyntaxActivity.tileHeight(1000, 2, 22, 150));   // room enough: as wanted
        assertEquals(139, SyntaxActivity.tileHeight(300, 2, 22, 150));    // (300 - 22) / 2
        assertEquals(150, SyntaxActivity.tileHeight(0, 2, 22, 150));      // not measured yet
    }
}
