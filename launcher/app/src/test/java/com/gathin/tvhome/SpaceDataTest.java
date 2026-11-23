package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Webb and Hubble scene: the ESA feeds and image pages as served on 2026-11-04 (trimmed), and the random pick. */
public class SpaceDataTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = SpaceDataTest.class.getResourceAsStream("/" + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesWebbFeed() throws Exception {
        List<SpaceData.Item> items = SpaceData.parseFeed(fixture("esawebb_feed.xml"), SpaceData.WEBB);
        assertEquals(3, items.size());
        SpaceData.Item first = items.get(0);
        assertEquals("weic2622c", first.id);
        assertEquals("Cat’s Head Nebula (Webb NIRCam image)", first.title);
        assertEquals("https://esawebb.org/images/weic2622c/", SpaceData.pageUrl(first));
        assertEquals("https://cdn.esawebb.org/archives/images/wallpaper4/weic2622c.jpg", SpaceData.imageUrl(first, false));
        assertEquals("https://cdn.esawebb.org/archives/images/screen/weic2622c.jpg", SpaceData.imageUrl(first, true));
    }

    @Test
    public void parsesHubbleFeedAndSkipsBanners() throws Exception {
        List<SpaceData.Item> items = SpaceData.parseFeed(fixture("esahubble_feed.xml"), SpaceData.HUBBLE);
        assertEquals(2, items.size());   // the "ST-ECF Newsletters" banner is not an image release
        assertEquals("heic2614a", items.get(0).id);
        assertEquals("https://cdn.esahubble.org/archives/images/wallpaper4/heic2614a.jpg", SpaceData.imageUrl(items.get(0), false));
    }

    @Test
    public void brokenFeedsAreEmpty() {
        assertTrue(SpaceData.parseFeed(null, SpaceData.WEBB).isEmpty());
        assertTrue(SpaceData.parseFeed("<html>503</html>", SpaceData.WEBB).isEmpty());
        assertTrue(SpaceData.parseFeed("<item><title>x</title><link>https://esawebb.org/images/../etc/</link></item>",
                SpaceData.WEBB).isEmpty());
        assertTrue(SpaceData.parseFeed("<item><title>x</title><link>https://evil.example/images/weic1/</link></item>",
                SpaceData.WEBB).isEmpty());
        assertFalse(SpaceData.validId("../x"));
        assertTrue(SpaceData.validId("potm2610a"));
    }

    @Test
    public void creditLines() throws Exception {
        assertEquals("NASA, ESA, CSA, STScI. Image Processing: J. Depasquale (STScI)",
                SpaceData.parseCredit(fixture("esawebb_page.html")));
        assertEquals("ESA/Hubble & NASA, R. Indebetouw", SpaceData.parseCredit(fixture("esahubble_page.html")));
        assertNull(SpaceData.parseCredit("<html>no credit</html>"));
        assertNull(SpaceData.parseCredit(null));
    }

    @Test
    public void cleansEntities() {
        assertEquals("Stars & dust — “pillars”", SpaceData.clean("<![CDATA[Stars &amp; dust &#8212; &#x201C;pillars&#x201D;]]>"));
        assertEquals("a b", SpaceData.clean(" a <em>\n</em> b "));
    }

    @Test
    public void pickNeverRepeatsRecentOnes() {
        Random random = new Random(7);
        int count = 40;
        Deque<Integer> recent = new ArrayDeque<>();
        int last = -1;
        for (int round = 0; round < 500; round++) {
            int i = SpaceData.pick(count, recent, last, random);
            assertTrue(i >= 0 && i < count);
            assertFalse("repeated " + i, recent.contains(i));
            recent.addLast(i);
            while (recent.size() > SpaceData.memory(count)) recent.removeFirst();
            last = i;
        }
    }

    @Test
    public void pickEdgeCases() {
        Random random = new Random(1);
        assertEquals(-1, SpaceData.pick(0, Collections.<Integer>emptyList(), -1, random));
        assertEquals(0, SpaceData.pick(1, Collections.singletonList(0), 0, random));
        // All recent: anything but the last shown.
        Set<Integer> seen = new HashSet<>();
        for (int k = 0; k < 50; k++) seen.add(SpaceData.pick(3, Arrays.asList(0, 1, 2), 1, random));
        assertFalse(seen.contains(1));
        assertEquals(2, seen.size());
        assertEquals(0, SpaceData.memory(1));
        assertEquals(30, SpaceData.memory(200));
    }
}
