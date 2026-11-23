package com.gathin.tvhome;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The DNS filter's logic in plain Java, so it is unit-tested on the JVM: the block lists (a listed domain blocks all
 * its subdomains, the way dumbtv's lists are meant), the exceptions, and the packets: an IPv4/UDP DNS query read
 * from the VPN's interface, the "no such domain" answer for a blocked name, and the IPv4/UDP packet that carries an
 * upstream answer back. Nothing here throws on a bad packet: it reads as null.
 */
final class DnsFilter {

    private final Set<String> blocked = new HashSet<>();
    private final Set<String> allowed = new HashSet<>();

    // ---- lists -------------------------------------------------------------------------------------------------

    /** Adds the domains of a list file: one per line, "#" comments, hosts-file lines ("0.0.0.0 x.com") and "*." too. */
    void addList(String text) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            String d = domain(line);
            if (d != null) blocked.add(d);
        }
    }

    void setAllowed(Collection<String> domains) {
        allowed.clear();
        for (String d : domains) {
            String n = domain(d);
            if (n != null) allowed.add(n);
        }
    }

    int size() {
        return blocked.size();
    }

    /** One list line as a lower-case domain, or null for a comment, a blank or something that is not a domain. */
    static String domain(String line) {
        if (line == null) return null;
        String s = line;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\s+");
        s = parts[parts.length - 1];   // "0.0.0.0 x.com" -> x.com
        if (s.startsWith("*.")) s = s.substring(2);
        if (s.startsWith("||")) s = s.substring(2);
        if (s.endsWith("^")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        if (!s.matches("[a-z0-9_-]+(\\.[a-z0-9_-]+)+") || s.matches("[0-9.]+")) return null;
        return s;
    }

    /** Blocked: the name or one of its parent domains is listed, and none of them is an exception. */
    boolean blocks(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".")) n = n.substring(0, n.length() - 1);
        if (matches(allowed, n)) return false;
        return matches(blocked, n);
    }

    private static boolean matches(Set<String> set, String name) {
        String n = name;
        while (true) {
            if (set.contains(n)) return true;
            int dot = n.indexOf('.');
            if (dot < 0 || dot == n.length() - 1) return false;
            n = n.substring(dot + 1);
        }
    }

    // ---- packets -----------------------------------------------------------------------------------------------

    /** An IPv4/UDP DNS query as read from the interface. */
    static final class Query {
        final byte[] srcIp = new byte[4], dstIp = new byte[4];
        int srcPort, dstPort;
        byte[] dns;
        String name;
        int type;
    }

    /** The query in an IPv4 packet to port 53, or null for anything else. */
    static Query parse(byte[] p, int len) {
        if (len < 28 || (p[0] >> 4 & 0xF) != 4) return null;
        int ihl = (p[0] & 0xF) * 4;
        if (ihl < 20 || len < ihl + 8 || (p[9] & 0xFF) != 17) return null;   // UDP only
        int total = u16(p, 2);
        if (total > len || total < ihl + 8) total = len;
        Query q = new Query();
        System.arraycopy(p, 12, q.srcIp, 0, 4);
        System.arraycopy(p, 16, q.dstIp, 0, 4);
        q.srcPort = u16(p, ihl);
        q.dstPort = u16(p, ihl + 2);
        if (q.dstPort != 53) return null;
        int dnsLen = total - ihl - 8;
        if (dnsLen < 12) return null;
        q.dns = new byte[dnsLen];
        System.arraycopy(p, ihl + 8, q.dns, 0, dnsLen);
        if (u16(q.dns, 4) < 1) return null;   // no question
        int[] end = new int[1];
        q.name = name(q.dns, 12, end);
        if (q.name == null || end[0] + 4 > dnsLen) return null;
        q.type = u16(q.dns, end[0]);
        return q;
    }

    /** The question's name (labels, no compression), its end offset in {@code end[0]}; null if malformed. */
    static String name(byte[] d, int off, int[] end) {
        StringBuilder b = new StringBuilder();
        int i = off;
        while (i < d.length) {
            int l = d[i] & 0xFF;
            if (l == 0) {
                end[0] = i + 1;
                return b.toString();
            }
            if (l > 63 || i + 1 + l > d.length) return null;
            if (b.length() > 0) b.append('.');
            b.append(new String(d, i + 1, l, StandardCharsets.US_ASCII));
            i += 1 + l;
            if (b.length() > 253) return null;
        }
        return null;
    }

    /** "No such domain" for a blocked query: the question echoed, no answer. */
    static byte[] nxdomain(byte[] query) {
        int[] end = new int[1];
        if (name(query, 12, end) == null) return null;
        int qlen = end[0] + 4;
        byte[] r = new byte[qlen];
        System.arraycopy(query, 0, r, 0, qlen);
        r[2] = (byte) (0x80 | (query[2] & 0x79));   // QR=1, same opcode and RD
        r[3] = (byte) 0x83;                         // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0;
        r[5] = 1;                                   // one question
        for (int i = 6; i < 12; i++) r[i] = 0;      // no answer, authority or additional records
        return r;
    }

    /** An IPv4/UDP packet from the query's destination back to its source, carrying {@code dns}. */
    static byte[] reply(Query q, byte[] dns, int dnsLen) {
        int total = 20 + 8 + dnsLen;
        byte[] p = new byte[total];
        p[0] = 0x45;
        p[2] = (byte) (total >> 8);
        p[3] = (byte) total;
        p[6] = 0x40;   // don't fragment
        p[8] = 64;     // TTL
        p[9] = 17;     // UDP
        System.arraycopy(q.dstIp, 0, p, 12, 4);
        System.arraycopy(q.srcIp, 0, p, 16, 4);
        int sum = checksum(p, 0, 20);
        p[10] = (byte) (sum >> 8);
        p[11] = (byte) sum;
        p[20] = (byte) (q.dstPort >> 8);
        p[21] = (byte) q.dstPort;
        p[22] = (byte) (q.srcPort >> 8);
        p[23] = (byte) q.srcPort;
        p[24] = (byte) ((8 + dnsLen) >> 8);
        p[25] = (byte) (8 + dnsLen);
        // UDP checksum 0: allowed over IPv4
        System.arraycopy(dns, 0, p, 28, dnsLen);
        return p;
    }

    static int checksum(byte[] b, int off, int len) {
        long sum = 0;
        for (int i = off; i < off + len - 1; i += 2) sum += u16(b, i);
        if ((len & 1) != 0) sum += (b[off + len - 1] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    static int u16(byte[] b, int i) {
        return (b[i] & 0xFF) << 8 | (b[i + 1] & 0xFF);
    }
}
