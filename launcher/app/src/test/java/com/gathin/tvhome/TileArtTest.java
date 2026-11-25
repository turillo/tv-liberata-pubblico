package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TV Liberata 1.0.7: tiles made from an icon (Amazon's Appstore and every app without a TV banner). */
public class TileArtTest {

    @Test
    public void theIconIsCentredAndNeverStretched() {
        // A square icon on the 400x225 tile: 58% of the height, square, in the middle.
        int[] box = TileArt.fit(512, 512, 400, 225, 0.58f);
        assertArrayEquals(new int[]{134, 47, 131, 131}, box);
        // A wide icon keeps its proportions and stays inside 80% of the width.
        int[] wide = TileArt.fit(1000, 200, 400, 225, 0.58f);
        assertEquals(320, wide[2]);
        assertEquals(64, wide[3]);
        assertEquals(1000 / 200f, wide[2] / (float) wide[3], 0.01f);
        // No size (a vector without one): treated as square.
        int[] none = TileArt.fit(-1, -1, 400, 225, 0.58f);
        assertEquals(none[2], none[3]);
    }

    @Test
    public void anIconWithItsOwnBackgroundGivesTheTileThatColour() {
        int white = 0xFFFFFFFF;
        int[] corners = {white, white, 0xFFFEFEFE, white};
        int[] pixels = {white, 0xFF000000, 0xFFF0901E};
        assertEquals(white, TileArt.choose(pixels, pixels.length, corners));   // Amazon's Appstore: white
    }

    @Test
    public void aTransparentIconGetsADarkerShadeOfItsColour() {
        int clear = 0x00000000, red = 0xFFE50914;
        int[] corners = {clear, clear, clear, clear};
        int[] pixels = {clear, red, red, clear};
        int bg = TileArt.choose(pixels, pixels.length, corners);
        assertTrue(TileArt.luminance(bg) < TileArt.luminance(red));   // darker than the icon
        assertTrue(((bg >> 16) & 0xFF) > ((bg >> 8) & 0xFF));         // still red
        assertEquals(0xFF1B2330, TileArt.choose(new int[]{clear}, 1, corners));   // nothing opaque
    }

    @Test
    public void shades() {
        assertEquals(0xFF000000, TileArt.shade(0xFF808080, -1f));
        assertEquals(0xFFFFFFFF, TileArt.shade(0xFF808080, 1f));
        assertEquals(0xFF808080, TileArt.shade(0xFF808080, 0f));
    }

    @Test
    public void cacheNameChangesWithTheApp() {
        String a = TileArt.key("com.amazon.venezia~com.amazon.venezia.Main", 1, 100);
        assertFalse(a.equals(TileArt.key("com.amazon.venezia~com.amazon.venezia.Main", 2, 100)));
        assertFalse(a.equals(TileArt.key("com.amazon.venezia~com.amazon.venezia.Main", 1, 200)));
        assertTrue(a.startsWith("com.amazon.venezia~"));
        assertFalse(TileArt.key("a/b c", 1, 1).contains("/"));
    }

    @Test
    public void bannerShape() {
        assertTrue(TileArt.bannerShaped(320, 180));
        assertFalse(TileArt.bannerShaped(512, 512));
    }

    @Test
    public void ownBackgroundIsTrimmed() {
        assertTrue(TileArt.sameOpaque(new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0xFFFEFEFE, 0xFFFFFFFF}));
        assertFalse(TileArt.sameOpaque(new int[]{0x00000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF}));
        assertFalse(TileArt.sameOpaque(new int[]{0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF}));
        // The Appstore's drawing sits in the middle of a 512 px white square: cut to it, with 8% room.
        int[] box = TileArt.trimBox(60, 140, 450, 420, 512, 512);
        assertArrayEquals(new int[]{29, 109, 453, 343}, box);
        assertEquals(null, TileArt.trimBox(0, 0, 511, 511, 512, 512));   // nothing to cut
        assertEquals(null, TileArt.trimBox(10, 10, 5, 5, 512, 512));     // nothing found
    }
}
