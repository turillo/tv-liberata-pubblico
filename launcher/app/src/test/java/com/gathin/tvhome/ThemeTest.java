package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TV Liberata 1.0.8: themes (styles, colours) and the menus' background copies. */
public class ThemeTest {

    @Test
    public void defaultsAreBalloonsAndOrange() {
        assertEquals(0xFFFF7A45, Theme.ORANGE);
        assertEquals(Theme.ORANGE, Theme.PALETTE[0]);
        assertEquals(0xFF5CE1E6, Theme.PALETTE[2]);   // the cyan of the pages before 1.0.8
        assertEquals(Theme.BALLOONS, Theme.clampStyle(-1));
        assertEquals(Theme.BALLOONS, Theme.clampStyle(9));
        assertEquals(Theme.TEXT, Theme.clampStyle(3));
        assertEquals(Theme.BALLOONS, Theme.clampStyle(4));   // the tiles style (1.0.8) is gone
    }

    @Test
    public void textOnTheColourIsReadable() {
        assertEquals(0xFF101418, Theme.onColor(0xFFF2F4F7));   // white: dark text
        assertEquals(0xFF101418, Theme.onColor(0xFFFFD23F));   // yellow: dark text
        assertEquals(0xFFFFFFFF, Theme.onColor(0xFF1B2330));   // dark: white text
        assertEquals(0xFFFFFFFF, Theme.onColor(Theme.ORANGE));
    }

    @Test
    public void hues() {
        assertEquals(0xFFFF0000, Theme.fromHue(0, 1f));
        assertEquals(0xFF00FF00, Theme.fromHue(120, 1f));
        assertEquals(0xFF0000FF, Theme.fromHue(240, 1f));
        assertEquals(0xFFFF0000, Theme.fromHue(360, 1f));
        assertEquals(0xFF800000, Theme.fromHue(0, 0.5f));
        assertTrue(Theme.luminance(Theme.shade(Theme.ORANGE, -0.45f)) < Theme.luminance(Theme.ORANGE));
        assertEquals(0x80FF7A45, Theme.withAlpha(Theme.ORANGE, 0x80));
    }

    @Test
    public void copiesAreNamedByPhoto() {
        assertEquals("OHR.Lago_IT", MenuBackdrop.idOf("OHR.Lago_IT-blur.jpg"));
        assertEquals("wm-123", MenuBackdrop.idOf("wm-123-sharp.jpg"));
        assertNull(MenuBackdrop.idOf("other.png"));
    }

    @Test
    public void boxBlurAveragesNeighbours() {
        int w = 5, h = 1;
        int[] in = {0xFF000000, 0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFF000000};
        int[] out = MenuBackdrop.boxBlur(in, w, h, 1);
        // The white pixel spreads to its neighbours: each of the three middle ones becomes a third white.
        assertEquals(0x55, out[1] & 0xFF);
        assertEquals(0x55, out[2] & 0xFF);
        assertEquals(0x55, out[3] & 0xFF);
        assertEquals(0, out[0] & 0xFF);
        assertArrayEquals(new int[]{0xFF, 0xFF}, new int[]{out[0] >>> 24, out[4] >>> 24});
    }
}
