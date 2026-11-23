package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Map;

public class DnsFilterTest {

    @Test
    public void listLinesAndSubdomains() {
        DnsFilter f = new DnsFilter();
        f.addList("# comment\narcus.amazon.com\n0.0.0.0 ads.example.com  # hosts line\n*.minerva.amazon.com\n"
                + "||tracker.io^\nnot a domain\n127.0.0.1\n");
        assertEquals(4, f.size());
        assertTrue(f.blocks("arcus.amazon.com"));
        assertTrue(f.blocks("eu.arcus.amazon.com."));
        assertTrue(f.blocks("x.minerva.amazon.com"));
        assertTrue(f.blocks("ADS.Example.com"));
        assertTrue(f.blocks("a.tracker.io"));
        assertFalse(f.blocks("amazon.com"));
        assertFalse(f.blocks("myarcus.amazon.com"));
        f.setAllowed(Arrays.asList("eu.arcus.amazon.com"));
        assertFalse(f.blocks("eu.arcus.amazon.com"));
        assertFalse(f.blocks("x.eu.arcus.amazon.com"));
        assertTrue(f.blocks("arcus.amazon.com"));
    }

    @Test
    public void bundledListsKeepWhatTheTvNeeds() {
        DnsFilter f = new DnsFilter();
        f.addList(String.join("\n", "softwareupdates.amazon.com", "amazon-adsystem.com"));
        f.setAllowed(DnsVpnService.KEEP);
        assertTrue(f.blocks("softwareupdates.amazon.com"));
        assertFalse(f.blocks("m.media-amazon.com"));
        assertFalse(f.blocks("api.github.com"));
    }

    /** An IPv4/UDP query for "a.example.com" A, from 10.111.222.1:40000 to 10.111.222.2:53. */
    static byte[] query() {
        byte[] dns = {0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
                1, 'a', 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1};
        byte[] p = new byte[28 + dns.length];
        p[0] = 0x45;
        p[2] = (byte) (p.length >> 8);
        p[3] = (byte) p.length;
        p[8] = 64;
        p[9] = 17;
        byte[] src = {10, 111, (byte) 222, 1}, dst = {10, 111, (byte) 222, 2};
        System.arraycopy(src, 0, p, 12, 4);
        System.arraycopy(dst, 0, p, 16, 4);
        p[20] = (byte) (40000 >> 8);
        p[21] = (byte) 40000;
        p[23] = 53;
        p[25] = (byte) (8 + dns.length);
        System.arraycopy(dns, 0, p, 28, dns.length);
        return p;
    }

    @Test
    public void readsAQueryAndAnswersNoSuchDomain() {
        byte[] p = query();
        DnsFilter.Query q = DnsFilter.parse(p, p.length);
        assertNotNull(q);
        assertEquals("a.example.com", q.name);
        assertEquals(1, q.type);
        assertEquals(40000, q.srcPort);
        byte[] nx = DnsFilter.nxdomain(q.dns);
        assertEquals(q.dns.length, nx.length);
        assertEquals(0x12, nx[0]);
        assertEquals((byte) 0x81, nx[2]);   // response, recursion desired
        assertEquals(3, nx[3] & 0x0F);      // NXDOMAIN
        assertEquals(0, DnsFilter.u16(nx, 6));
        byte[] r = DnsFilter.reply(q, nx, nx.length);
        assertArrayEquals(new byte[]{10, 111, (byte) 222, 2}, Arrays.copyOfRange(r, 12, 16));
        assertArrayEquals(new byte[]{10, 111, (byte) 222, 1}, Arrays.copyOfRange(r, 16, 20));
        assertEquals(53, DnsFilter.u16(r, 20));
        assertEquals(40000, DnsFilter.u16(r, 22));
        assertEquals(0, DnsFilter.checksum(r, 0, 20));   // a correct header sums to zero
        DnsFilter.Query back = DnsFilter.parse(swap(r), r.length);
        assertNull(back);   // a reply is not a query to port 53
    }

    private static byte[] swap(byte[] r) {
        return r.clone();
    }

    @Test
    public void ignoresOtherPackets() {
        byte[] p = query();
        p[9] = 6;   // TCP
        assertNull(DnsFilter.parse(p, p.length));
        byte[] q = query();
        q[23] = 80;
        assertNull(DnsFilter.parse(q, q.length));
        assertNull(DnsFilter.parse(new byte[10], 10));
    }

    @Test
    public void topCountsRoundTrip() {
        Map<String, Integer> m = FilterSettings.parseTop("a.com=3;b.org=10;bad;c=x");
        assertEquals(2, m.size());
        assertEquals(Integer.valueOf(10), m.get("b.org"));
    }
}
