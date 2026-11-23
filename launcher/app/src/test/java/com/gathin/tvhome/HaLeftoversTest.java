package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

public class HaLeftoversTest {

    @Test
    public void haIdsGetTheTvsOwnNamesBack() {
        assertEquals("OHR.DakotaBison_IT-IT5607960276", HaLeftovers.localId("bing-41358e2c47e1", "bing",
                "https://www.bing.com/th?id=OHR.DakotaBison_IT-IT5607960276_1920x1080.jpg", "Il ritorno del gigante"));
        assertEquals("UNS.JaRkADTYw1U", HaLeftovers.localId("uns-JaRkADTYw1U", "unsplash", null, null));
        assertEquals("WMC.D_lmen_Marktstra_e_Eisbahn_--_2021_--_9235", HaLeftovers.localId("wmc-113140675",
                "wikimedia", null, "Dülmen, Marktstraße, Eisbahn -- 2021 -- 9235"));
        assertNull(HaLeftovers.localId("up-fb9ec5e111271cb4", "migrazione-sony", null, "Trees"));
        assertNull(HaLeftovers.localId("bing-AlpineBraies", "bing", null, null));
        assertEquals("WMC.Panorama_of_Himalayas", HaLeftovers.localId("up-8d29bbccbb747032", "migrazione-sony", null,
                "Panorama", "WMC.Panorama_of_Himalayas.jpg"));
        assertNull(HaLeftovers.localId("up-1", "caricamento", null, "x", "foto.jpg"));
    }

    @Test
    public void renamesTheStateToo() {
        Map<String, String> r = HaLeftovers.renames("{\"versione\":120,\"file\":["
                + "{\"id\":\"uns-abc\",\"sorgente\":\"unsplash\"},{\"id\":\"up-1\",\"sorgente\":\"caricamento\"},"
                + "{\"id\":\"bing-x\",\"url_originale\":\"https://www.bing.com/th?id=OHR.Lago_IT-IT1_1920x1080.jpg\"}]}");
        assertEquals(2, r.size());
        assertEquals("OHR.Lago_IT-IT1", r.get("bing-x"));
        WallpaperState s = new WallpaperState();
        s.setFavorite("uns-abc", true);
        s.blacklist.add("bing-x");
        s.titles.put("uns-abc", "Mare");
        s.setFavorite("up-1", true);
        HaLeftovers.rename(s, r);
        assertTrue(s.favorite("UNS.abc") && s.favorite("up-1"));
        assertTrue(s.blacklist.contains("OHR.Lago_IT-IT1"));
        assertEquals("Mare", s.titles.get("UNS.abc"));
        assertTrue(HaLeftovers.renames("rotto").isEmpty());
    }
}
