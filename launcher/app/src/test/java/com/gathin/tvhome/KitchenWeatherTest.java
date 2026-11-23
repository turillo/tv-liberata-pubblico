package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class KitchenWeatherTest {
    private static final int ROME_WINTER = 3600;
    private static final long T1630 = KitchenWeather.epoch("2026-11-13T16:30", ROME_WINTER);

    private static long[] quarters(String... local) {
        long[] out = new long[local.length];
        for (int i = 0; i < local.length; i++) out[i] = KitchenWeather.epoch(local[i], ROME_WINTER);
        return out;
    }

    private static final long[] SLOTS = quarters("2026-11-13T16:30", "2026-11-13T16:45", "2026-11-13T17:00",
            "2026-11-13T17:15");

    @Test
    public void epochUsesTheOffset() {
        // 16:30 in Rome in winter is 15:30 UTC.
        assertEquals(1794583800000L, T1630);
    }

    @Test
    public void dryForecastSaysNoRain() {
        assertEquals(-1, KitchenWeather.rainIn(SLOTS, new double[]{0, 0, 0.05, 0}, 0, T1630));
    }

    @Test
    public void rainingNowIsZero() {
        assertEquals(0, KitchenWeather.rainIn(SLOTS, new double[]{0, 0, 0, 0}, 0.4, T1630));
        assertEquals(0, KitchenWeather.rainIn(SLOTS, new double[]{0.3, 0, 0, 0}, 0, T1630 + 5 * 60_000L));
    }

    @Test
    public void minutesUntilTheFirstWetSlot() {
        double[] rain = {0, 0, 0.6, 1.2};
        assertEquals(30, KitchenWeather.rainIn(SLOTS, rain, 0, T1630));
        // Ten minutes later the slot is 20 minutes away; partial minutes round up.
        assertEquals(20, KitchenWeather.rainIn(SLOTS, rain, 0, T1630 + 10 * 60_000L));
        assertEquals(1, KitchenWeather.rainIn(SLOTS, rain, 0, T1630 + 29 * 60_000L + 30_000L));
    }

    @Test
    public void finishedSlotsAreSkipped() {
        // The 16:30 slot was wet but ended at 16:45; at 16:50 the next rain is at 17:15.
        assertEquals(25, KitchenWeather.rainIn(SLOTS, new double[]{0.5, 0, 0, 0.5}, 0, T1630 + 20 * 60_000L));
    }

    @Test
    public void nextHoursStartAfterNow() {
        List<KitchenWeather.Hour> hours = Arrays.asList(
                new KitchenWeather.Hour(KitchenWeather.epoch("2026-11-13T16:00", ROME_WINTER), 19, 2, 0),
                new KitchenWeather.Hour(KitchenWeather.epoch("2026-11-13T17:00", ROME_WINTER), 18, 0, 3),
                new KitchenWeather.Hour(KitchenWeather.epoch("2026-11-13T18:00", ROME_WINTER), 17, 61, 80),
                new KitchenWeather.Hour(KitchenWeather.epoch("2026-11-13T19:00", ROME_WINTER), 16, 1, 0));
        List<KitchenWeather.Hour> next = KitchenWeather.next(hours, T1630, 2);
        assertEquals(2, next.size());
        assertEquals(18, next.get(0).temp, 0);
        assertEquals(80, next.get(1).rainChance);
    }
}
