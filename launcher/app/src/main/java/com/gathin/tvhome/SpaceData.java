package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-Java facts for the Webb and Hubble screensaver scene (dream_scene "space"), apart from Android so they can
 * be unit-tested: the ESA/Webb and ESA/Hubble image feeds (esawebb.org/images/feed/, esahubble.org/images/feed/),
 * each image's 1920x1200 wallpaper on their CDNs, the credit line on its page (CC BY 4.0 requires it, visibly),
 * and the random pick that does not repeat the last few.
 */
final class SpaceData {

    static final int WEBB = 0, HUBBLE = 1;
    static final String[] FEEDS = {"https://esawebb.org/images/feed/", "https://esahubble.org/images/feed/"};
    private static final String[] SITES = {"https://esawebb.org/images/", "https://esahubble.org/images/"};
    private static final String[] CDNS = {"https://cdn.esawebb.org/archives/images/", "https://cdn.esahubble.org/archives/images/"};
    /** The names shown in the panel; the attribution the licence asks for starts with them. */
    static final String[] NAMES = {"ESA/Webb", "ESA/Hubble"};

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>\\s*https://(?:www\\.)?esa(?:webb|hubble)\\.org/images/([a-z]+[0-9]+[a-z]?)/\\s*</link>");
    private static final Pattern CREDIT = Pattern.compile("class=\"credit\"[^>]*>(.*?)</div>", Pattern.DOTALL);
    private static final Pattern ID = Pattern.compile("[a-z]+[0-9]+[a-z]?");
    private static final Pattern NUMERIC = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});");

    private SpaceData() {
    }

    /** One image in a feed. */
    static final class Item {
        final String id;          // e.g. weic2622c, safe as a file name
        final int telescope;      // WEBB or HUBBLE
        final String title;

        Item(String id, int telescope, String title) {
            this.id = id;
            this.telescope = telescope;
            this.title = title;
        }
    }

    /** The images in a feed, newest first as the feed lists them; empty when the text is not one. Never throws. */
    static List<Item> parseFeed(String xml, int telescope) {
        List<Item> items = new ArrayList<>();
        if (xml == null) return items;
        Set<String> seen = new HashSet<>();
        Matcher item = ITEM.matcher(xml);
        while (item.find()) {
            String body = item.group(1);
            Matcher link = LINK.matcher(body);
            Matcher title = TITLE.matcher(body);
            if (!link.find() || !title.find()) continue;
            String id = link.group(1);
            String text = clean(title.group(1));
            if (text.isEmpty() || !seen.add(id)) continue;
            items.add(new Item(id, telescope, text.length() > 120 ? text.substring(0, 119) + "…" : text));
        }
        return items;
    }

    static boolean validId(String id) {
        return id != null && id.length() <= 24 && ID.matcher(id).matches();
    }

    /** The image page, where its credit line is. */
    static String pageUrl(Item item) {
        return SITES[item.telescope] + item.id + "/";
    }

    /** The 1920x1200 wallpaper (about 1 MB), or with {@code fallback} the 1280 px "screen" size. */
    static String imageUrl(Item item, boolean fallback) {
        return CDNS[item.telescope] + (fallback ? "screen/" : "wallpaper4/") + item.id + ".jpg";
    }

    /** The credit line on an image page ("NASA, ESA, CSA, STScI..."), or null when there is none. */
    static String parseCredit(String html) {
        if (html == null) return null;
        Matcher m = CREDIT.matcher(html);
        if (!m.find()) return null;
        String credit = clean(m.group(1));
        if (credit.isEmpty()) return null;
        return credit.length() > 200 ? credit.substring(0, 199) + "…" : credit;
    }

    /** Text without tags, CDATA or entities, with spaces collapsed. */
    static String clean(String s) {
        s = s.replace("<![CDATA[", "").replace("]]>", "");
        s = s.replaceAll("<[^>]*>", " ");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher n = NUMERIC.matcher(s);
        StringBuffer out = new StringBuffer();
        while (n.find()) {
            int code;
            try {
                code = Integer.parseInt(n.group(2), n.group(1).isEmpty() ? 10 : 16);
            } catch (NumberFormatException e) {
                code = ' ';
            }
            String c = Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : " ";
            n.appendReplacement(out, Matcher.quoteReplacement(c));
        }
        n.appendTail(out);
        s = out.toString().replace("&amp;", "&").replace(' ', ' ').replace('�', '\'');
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * A random index into {@code count} images that is not among the {@code recent} ones; when every image is
     * recent, any one but the last shown. -1 when there are none.
     */
    static int pick(int count, Collection<Integer> recent, int last, Random random) {
        if (count <= 0) return -1;
        if (count == 1) return 0;
        List<Integer> fresh = new ArrayList<>();
        for (int i = 0; i < count; i++) if (!recent.contains(i)) fresh.add(i);
        if (!fresh.isEmpty()) return fresh.get(random.nextInt(fresh.size()));
        int i = random.nextInt(count - 1);
        return i >= last && last >= 0 ? i + 1 : i;
    }

    /** How many recent picks to avoid repeating: up to 30, and never all the images. */
    static int memory(int count) {
        return Math.max(0, Math.min(30, count / 2));
    }
}
