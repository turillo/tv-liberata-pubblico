package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TV Liberata 1.0: one app; what Features switches off is not offered. */
public class EditionTest {

    @Test
    public void featuresOffInOnePointZero() {
        assertEquals(Features.UNSPLASH, WallpaperSources.ALL.contains(WallpaperSources.UNSPLASH));
        assertTrue(WallpaperSources.ALL.contains(WallpaperSources.BING));
        assertTrue(WallpaperSources.ALL.contains(WallpaperSources.WIKIMEDIA));
        assertEquals(Edition.ilPost(), NewsData.sectionKeys().contains(NewsData.ILPOST));   // 1.0.6: ANSA only
        assertEquals(Features.UNSPLASH, SyntaxSettings.XMAS_SOURCES.contains(WallpaperSources.UNSPLASH));
        if (!Features.SPACE_SCENES) {
            assertEquals(java.util.Arrays.asList(Config.SCENE_CLOCK, Config.SCENE_FAVORITES), SyntaxSettings.SCENES);
        }
        boolean news = false, scene = false, geo = false, github = false, lists = false;
        for (ServiceStatus.Source s : ServiceStatus.SOURCES) {
            news |= ServiceStatus.ANSA.equals(s.id) || ServiceStatus.ILPOST.equals(s.id);
            scene |= ServiceStatus.ISS.equals(s.id) || ServiceStatus.EUMETSAT.equals(s.id);
            geo |= ServiceStatus.GEO.equals(s.id);
            github |= ServiceStatus.GITHUB.equals(s.id);
            lists |= ServiceStatus.FILTER_LISTS.equals(s.id);
        }
        assertEquals(Features.NEWS_TICKER, news);
        assertEquals(Features.SPACE_SCENES, scene);
        assertTrue(geo && github && lists);
        assertEquals("com.gathin.tvhome.liberata", BuildConfig.APPLICATION_ID);
        assertTrue(BuildConfig.VERSION_NAME.startsWith("1.0.") || BuildConfig.VERSION_NAME.startsWith("1.1."));   // 1.0.x and 1.1.x: the same features
        assertFalse(BuildConfig.UPDATE_REPO.isEmpty());
    }
}
