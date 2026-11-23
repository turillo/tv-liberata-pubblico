package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The news ticker: ANSA's "Ultima ora" feed as served on 2026-11-04, the merge into the cache, the cache file. */
public class NewsDataTest {

    private static String fixture() throws Exception {
        return fixture("/ansa_topnews.xml");
    }

    private static String fixture(String name) throws Exception {
        try (InputStream in = NewsDataTest.class.getResourceAsStream(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesAnsaFeed() throws Exception {
        List<NewsData.Headline> h = NewsData.parse(fixture());
        assertEquals(10, h.size());
        assertEquals("I futures su gas naturale crollano a 85,32 euro", h.get(0).title);
        assertEquals(Instant.parse("2026-11-04T17:21:46Z").toEpochMilli(), h.get(0).time);
        // Apostrophes and accents come through as text.
        assertEquals("Centro nazionale sangue, 'alzare livello d'attenzione dopo la Francia'", h.get(3).title);
        for (NewsData.Headline x : h) assertTrue(x.title, !x.title.contains("CDATA") && !x.title.contains("&"));
    }

    @Test
    public void datesInBothForms() {
        assertEquals(Instant.parse("2026-11-04T17:21:46Z").toEpochMilli(), NewsData.parseDate("Wed, 4 Nov 2026 18:21:46 +0100"));
        assertEquals(Instant.parse("2026-11-04T17:21:46Z").toEpochMilli(), NewsData.parseDate("Wed, 04 Nov 2026 17:21:46 GMT"));
        assertEquals(-1, NewsData.parseDate("yesterday"));
    }

    @Test
    public void brokenFeedsAreEmpty() {
        assertTrue(NewsData.parse(null).isEmpty());
        assertTrue(NewsData.parse("<html>503</html>").isEmpty());
        assertTrue(NewsData.parse("<item><title>No date</title></item>").isEmpty());
    }

    private static NewsData.Headline h(String title, int minute) {
        return new NewsData.Headline(title, Instant.parse("2026-11-04T17:00:00Z").toEpochMilli() + minute * 60_000L);
    }

    @Test
    public void mergeKeepsNewestFirstWithoutDuplicates() {
        List<NewsData.Headline> cached = Arrays.asList(h("Spread a 104", 10), h("Gas in calo", 5));
        List<NewsData.Headline> fresh = Arrays.asList(h("Nuovo governo", 20), h("Spread a 104!", 12));
        List<NewsData.Headline> merged = NewsData.merge(cached, fresh);
        assertEquals(3, merged.size());
        assertEquals("Nuovo governo", merged.get(0).title);
        assertEquals("Spread a 104!", merged.get(1).title);   // the newer copy of the same headline
        assertEquals("Gas in calo", merged.get(2).title);
    }

    @Test
    public void mergeKeepsAtMostThirty() {
        List<NewsData.Headline> many = new ArrayList<>();
        for (int i = 0; i < 45; i++) many.add(h("Titolo " + i, i));
        List<NewsData.Headline> merged = NewsData.merge(Collections.<NewsData.Headline>emptyList(), many);
        assertEquals(NewsData.MAX, merged.size());
        assertEquals("Titolo 44", merged.get(0).title);
        assertEquals("Titolo 15", merged.get(29).title);
    }

    @Test
    public void sectionsMixByTimeWithoutDuplicates() {
        List<NewsData.Headline> top = Arrays.asList(new NewsData.Headline("Spread a 104", t(18, 9), NewsData.TOP),
                new NewsData.Headline("Gas in calo", t(17, 50), NewsData.TOP));
        List<NewsData.Headline> sport = Arrays.asList(new NewsData.Headline("Roma, vittoria al 90'", t(18, 5), "sport"),
                new NewsData.Headline("Gas in calo!", t(17, 55), "economia"));   // the same story in another section
        List<NewsData.Headline> mixed = NewsData.merge(Collections.<NewsData.Headline>emptyList(),
                concat(top, sport), Arrays.asList(NewsData.TOP, "sport", "economia"));
        assertEquals(3, mixed.size());
        assertEquals("Spread a 104", mixed.get(0).title);
        assertEquals("sport", mixed.get(1).section);
        assertEquals("economia", mixed.get(2).section);   // the newer copy wins
        // Unticking a section drops its headlines from the list.
        List<NewsData.Headline> topOnly = NewsData.merge(mixed, Collections.<NewsData.Headline>emptyList(),
                Collections.singletonList(NewsData.TOP));
        assertEquals(1, topOnly.size());
        assertEquals("Spread a 104", topOnly.get(0).title);
    }

    @Test
    public void prefixShowsTheSectionOnlyWithSeveral() {
        NewsData.Headline h = new NewsData.Headline("Titolo", 0, "sport");
        assertEquals("18:05", NewsData.prefix(h, "18:05", false));
        assertEquals("SPORT · 18:05", NewsData.prefix(h, "18:05", true));
        assertEquals("ULTIMA ORA · 18:05", NewsData.prefix(new NewsData.Headline("x", 0), "18:05", true));
    }

    @Test
    public void sectionsTable() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.NEWS_TICKER && Features.UNSPLASH);
        assertEquals(NewsData.TOP, NewsData.SECTIONS.get(0).key);
        assertEquals(NewsData.FEED_URL, NewsData.section(NewsData.TOP).url);
        for (NewsData.Section s : NewsData.SECTIONS) {
            assertTrue(s.url, s.url.startsWith(NewsData.ansa(s.key) ? "https://www.ansa.it/" : "https://www.ilpost.it/"));
        }
        assertTrue(NewsData.section("spettacolo") == null && NewsData.section("ambiente") == null);
        assertEquals(12, NewsData.sectionKeys().size());   // 11 ANSA sections and Il Post
    }

    @Test
    public void oldCacheLinesStillRead() {
        List<NewsData.Headline> back = NewsData.deserialize("1793775240000\tTitolo vecchio\n1793775300000\tsport\tTitolo nuovo\n");
        assertEquals(2, back.size());
        assertEquals("sport", back.get(0).section);
        assertEquals(NewsData.TOP, back.get(1).section);
    }

    private static long t(int hour, int minute) {
        return Instant.parse(String.format("2026-11-04T%02d:%02d:00Z", hour - 1, minute)).toEpochMilli();
    }

    private static List<NewsData.Headline> concat(List<NewsData.Headline> a, List<NewsData.Headline> b) {
        List<NewsData.Headline> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    @Test
    public void cacheFileRoundTrip() throws Exception {
        List<NewsData.Headline> h = NewsData.merge(Collections.<NewsData.Headline>emptyList(), NewsData.parse(fixture()));
        List<NewsData.Headline> back = NewsData.deserialize(NewsData.serialize(h) + "garbage\n\t\n12x\tbad\n");
        assertEquals(h.size(), back.size());
        for (int i = 0; i < h.size(); i++) {
            assertEquals(h.get(i).title, back.get(i).title);
            assertEquals(h.get(i).time, back.get(i).time);
        }
        assertTrue(NewsData.deserialize(null).isEmpty());
    }

    @Test
    public void ilPostFeedMixesWithAnsaByTimeWithItsLabel() throws Exception {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.NEWS_TICKER && Features.UNSPLASH);
        List<NewsData.Headline> il = NewsData.parse(fixture("/ilpost_feed.xml"), NewsData.ILPOST);
        assertEquals(3, il.size());
        assertEquals("Mamma, pappa, nanna e Stokke", il.get(1).title);
        assertEquals(Instant.parse("2026-11-11T19:15:32Z").toEpochMilli(), il.get(0).time);
        assertEquals("IL POST · 20:15", NewsData.prefix(il.get(0), "20:15", true));
        List<NewsData.Headline> ansa = NewsData.parse(fixture(), NewsData.TOP);
        List<String> both = Arrays.asList(NewsData.TOP, NewsData.ILPOST);
        List<NewsData.Headline> merged = NewsData.merge(ansa, il, both);
        for (int i = 1; i < merged.size(); i++) assertTrue(merged.get(i - 1).time >= merged.get(i).time);
        boolean sawIl = false;
        for (NewsData.Headline h : merged) if (NewsData.ILPOST.equals(h.section)) sawIl = true;
        assertTrue(sawIl || merged.size() == NewsData.MAX);
        // The same story twice (another feed, other punctuation) shows once.
        List<NewsData.Headline> dup = NewsData.merge(il, Arrays.asList(new NewsData.Headline("Mamma pappa nanna e Stokke!",
                il.get(1).time + 1000, NewsData.TOP)), both);
        int count = 0;
        for (NewsData.Headline h : dup) if (h.title.toLowerCase().contains("stokke")) count++;
        assertEquals(1, count);
        // Unticking Il Post drops its headlines.
        for (NewsData.Headline h : NewsData.merge(merged, new ArrayList<>(), Collections.singletonList(NewsData.TOP))) {
            assertTrue(NewsData.ansa(h.section));
        }
    }

    @Test
    public void ilPostIsOffByDefaultAndLabelsTheBand() {
        org.junit.Assume.assumeTrue("off in 1.0 (Features)", Features.NEWS_TICKER && Features.UNSPLASH);
        assertTrue(NewsData.sectionKeys().contains(NewsData.ILPOST));
        assertFalse(NewsData.ansa(NewsData.ILPOST));
        assertEquals(NewsData.LABEL_ANSA, NewsData.labelKind(Arrays.asList(NewsData.TOP, "sport")));
        assertEquals(NewsData.LABEL_ILPOST, NewsData.labelKind(Collections.singletonList(NewsData.ILPOST)));
        assertEquals(NewsData.LABEL_MIXED, NewsData.labelKind(Arrays.asList(NewsData.TOP, NewsData.ILPOST)));
    }
}
