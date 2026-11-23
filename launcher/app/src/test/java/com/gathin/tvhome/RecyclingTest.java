package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RecyclingTest {
    private static final List<List<String>> ROME = Recycling.parse(Recycling.DEFAULT);
    private static final int TUE = 2, WED = 3, THU = 4, SUN = 7, MON = 1;

    private static Recycling.Status at(int day, int hour, int minute) {
        return Recycling.at(ROME, day, hour * 60 + minute, Recycling.FROM, Recycling.TO, Recycling.BY);
    }

    @Test
    public void parsesTheRomeWeek() {
        assertEquals(Arrays.asList("carta", "organico"), ROME.get(TUE - 1));
        assertEquals(Arrays.asList("organico", "plastica"), ROME.get(THU - 1));
        assertEquals(Arrays.asList("organico", "indiff"), ROME.get(SUN - 1));
        assertTrue(ROME.get(MON - 1).isEmpty());
        assertEquals("2:carta,organico;4:organico,plastica;7:organico,indiff", Recycling.format(ROME));
    }

    @Test
    public void brokenPartsAreDropped() {
        List<List<String>> w = Recycling.parse("0:carta;2:carta,carta,sassi;x:vetro;5");
        assertEquals("2:carta", Recycling.format(w));
        assertEquals("", Recycling.format(Recycling.parse(null)));
    }

    @Test
    public void toggleAddsAndRemoves() {
        String w = Recycling.toggle(Recycling.DEFAULT, MON, Recycling.GLASS);
        assertEquals(Arrays.asList("vetro"), Recycling.parse(w).get(0));
        w = Recycling.toggle(w, TUE, Recycling.PAPER);
        assertEquals(Arrays.asList("organico"), Recycling.parse(w).get(1));
        assertEquals(w, Recycling.toggle(w, 8, Recycling.PAPER));
    }

    @Test
    public void tuesdayEvening() {
        assertEquals(Recycling.Kind.NEXT, at(TUE, 13, 59).kind);
        assertEquals(TUE, at(TUE, 13, 59).day);
        assertEquals(Recycling.Kind.TONIGHT, at(TUE, 14, 0).kind);
        assertEquals(Recycling.Kind.TONIGHT, at(TUE, 18, 59).kind);
        assertEquals(Recycling.Kind.OUT_NOW, at(TUE, 19, 0).kind);
        assertEquals(Arrays.asList("carta", "organico"), at(TUE, 20, 30).bins);
        assertEquals(Recycling.Kind.TONIGHT, at(TUE, 21, 0).kind);
    }

    @Test
    public void wednesdayMorningTakeIn() {
        assertEquals(Recycling.Kind.TAKE_IN, at(WED, 0, 10).kind);
        assertEquals(Recycling.Kind.TAKE_IN, at(WED, 8, 59).kind);
        assertEquals(Arrays.asList("carta", "organico"), at(WED, 7, 0).bins);
        Recycling.Status after = at(WED, 9, 0);
        assertEquals(Recycling.Kind.NEXT, after.kind);
        assertEquals(THU, after.day);
    }

    @Test
    public void sundayToMondayWraps() {
        assertEquals(Recycling.Kind.OUT_NOW, at(SUN, 19, 30).kind);
        assertEquals(Recycling.Kind.TAKE_IN, at(MON, 8, 0).kind);
        Recycling.Status next = at(MON, 10, 0);
        assertEquals(TUE, next.day);
        // Late on Sunday the reminder stays, no longer lit.
        assertEquals(Recycling.Kind.TONIGHT, at(SUN, 23, 0).kind);
    }

    @Test
    public void emptyWeekSaysNothing() {
        assertEquals(Recycling.Kind.NONE, Recycling.at(Recycling.parse(""), TUE, 20 * 60, 1140, 1260, 540).kind);
    }

    @Test
    public void glassGoesToTheBellsUnlessScheduled() {
        assertTrue(Recycling.glassToBells(ROME));
        assertFalse(Recycling.glassToBells(Recycling.parse("3:vetro")));
    }
}
