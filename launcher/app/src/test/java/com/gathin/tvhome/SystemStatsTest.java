package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SystemStatsTest {

    @Test
    public void temperaturesInMilliDegreesDegreesAndTenths() {
        assertEquals(45.0f, SystemStats.parseTemp("45000"), 0.01f);
        assertEquals(45.5f, SystemStats.parseTemp("45500\n"), 0.01f);
        assertEquals(52.0f, SystemStats.parseTemp("52"), 0.01f);
        assertEquals(45.0f, SystemStats.parseTemp("450"), 0.01f);
    }

    @Test
    public void temperaturesThatAreNotOneAreNaN() {
        assertTrue(Float.isNaN(SystemStats.parseTemp(null)));
        assertTrue(Float.isNaN(SystemStats.parseTemp("")));
        assertTrue(Float.isNaN(SystemStats.parseTemp("abc")));
        assertTrue(Float.isNaN(SystemStats.parseTemp("-40000")));
        assertTrue(Float.isNaN(SystemStats.parseTemp("0")));
        assertTrue(Float.isNaN(SystemStats.parseTemp("999999")));   // 999 °C: a broken sensor
    }

    @Test
    public void frequencies() {
        assertEquals(1512, SystemStats.parseFreqMhz("1512000"));
        assertEquals(-1, SystemStats.parseFreqMhz("0"));
        assertEquals(-1, SystemStats.parseFreqMhz("n/a"));
        assertEquals(-1, SystemStats.parseFreqMhz(null));
    }

    @Test
    public void cpuLoadFromTwoProcStatLines() {
        long[] a = SystemStats.parseCpuLine("cpu  100 0 100 700 100 0 0 0 0 0");
        long[] b = SystemStats.parseCpuLine("cpu  150 0 150 750 100 0 0 0 0 0");
        assertArrayEquals(new long[]{800, 1000}, a);
        assertEquals(67, SystemStats.cpuLoad(a, b));   // 100 busy of 150
        assertNull(SystemStats.parseCpuLine("cpu0 1 2 3 4"));
        assertNull(SystemStats.parseCpuLine("garbage"));
        assertEquals(-1, SystemStats.cpuLoad(null, b));
        assertEquals(-1, SystemStats.cpuLoad(b, a));
    }

    @Test
    public void pingOutput() {
        assertEquals(3.21f, SystemStats.parsePingMs("64 bytes from 192.168.1.1: icmp_seq=1 ttl=64 time=3.21 ms"), 0.001f);
        assertEquals(18f, SystemStats.parsePingMs("time=18 ms"), 0.001f);
        assertEquals(-1f, SystemStats.parsePingMs("1 packets transmitted, 0 received"), 0.001f);
        assertEquals(-1f, SystemStats.parsePingMs(null), 0.001f);
    }

    @Test
    public void signal() {
        assertEquals("ottimo", SystemStats.signalLabel(-50));
        assertEquals("buono", SystemStats.signalLabel(-61));
        assertEquals("discreto", SystemStats.signalLabel(-72));
        assertEquals("debole", SystemStats.signalLabel(-85));
        assertNull(SystemStats.signalLabel(-127));   // Android's "no value"
        assertNull(SystemStats.signalLabel(0));
    }

    @Test
    public void formats() {
        assertEquals("512 B", SystemStats.bytes(512));
        assertEquals("1,5 KB", SystemStats.bytes(1536));
        assertEquals("1,8 GB", SystemStats.bytes(1934336L * 1024));   // 1,84 GiB
        assertEquals("120 MB", SystemStats.bytes(120L << 20));
        assertEquals(25, SystemStats.percent(1, 4));
        assertEquals(-1, SystemStats.percent(1, 0));
        assertEquals("12 min", SystemStats.duration(12 * 60_000L));
        assertEquals("4 h 5 min", SystemStats.duration((4 * 60 + 5) * 60_000L));
        assertEquals("2 g 3 h", SystemStats.duration((51 * 60) * 60_000L));
        assertEquals(80.0, SystemStats.mbps(10_000_000, 1000), 0.001);
    }

    @Test
    public void historyKeepsTheNewestInOrder() {
        SystemStats.History h = new SystemStats.History(3);
        h.add(1);
        h.add(2);
        assertArrayEquals(new float[]{1, 2}, h.snapshot(), 0f);
        h.add(3);
        h.add(4);
        assertArrayEquals(new float[]{2, 3, 4}, h.snapshot(), 0f);
    }
}
