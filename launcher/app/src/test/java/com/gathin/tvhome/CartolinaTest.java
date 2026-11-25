package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** TV Liberata 1.1.0: the cartolina's message and the device configuration's answer. */
public class CartolinaTest {

    @Test
    public void payloadHasOnlyTheAgreedFields() {
        String p = Cartolina.payload("s3", "0f8fad5b-d9cb-469f-a165-70867728950e", "Cucina \"1\"", "1.1.0", "test",
                "Amazon AFTSS", "Fire OS 7.7", "aggiornata");
        assertEquals("{\"segreto\":\"s3\",\"codice\":\"0f8fad5b-d9cb-469f-a165-70867728950e\",\"nome\":\"Cucina \\\"1\\\"\","
                + "\"versione\":\"1.1.0\",\"canale\":\"test\",\"modello\":\"Amazon AFTSS\",\"sistema\":\"Fire OS 7.7\","
                + "\"evento\":\"aggiornata\"}", p);
        assertEquals("a\\u000ab", Cartolina.escape("a" + (char) 10 + "b"));
    }

    @Test
    public void systemNames() {
        assertEquals("Fire OS 7.7", Cartolina.system(true, "Fire OS 7.7.1.7 (PS7717/4945)", "9", 28));
        assertEquals("Fire OS 7", Cartolina.system(true, "", "9", 28));
        assertEquals("Fire OS 8", Cartolina.system(true, null, "11", 30));
        assertEquals("Android 9", Cartolina.system(false, "", "9", 28));
    }

    @Test
    public void repliesThatCountAsDelivered() {
        assertTrue(Cartolina.delivered("ok"));
        assertTrue(Cartolina.delivered("gia' ricevuto"));   // this event and version came already
        assertFalse(Cartolina.delivered("errore: troppi messaggi oggi"));   // tried again tomorrow
        assertFalse(Cartolina.delivered("errore: non autorizzato"));
        assertFalse(Cartolina.delivered(null));
    }

    @Test
    public void offWithoutKeys() {
        // The test build has no keys.properties values for it: nothing is ever asked or sent.
        assertEquals(!BuildConfig.CARTOLINA_URL.isEmpty() && !BuildConfig.CARTOLINA_SECRET.isEmpty(), Cartolina.enabled());
    }

    @Test
    public void deviceConfigCarriesTheAnswer() {
        assertEquals(Boolean.TRUE, DeviceConfig.parse("{\"nome\":\"Cucina\",\"cartolina\":true}").cartolina);
        assertEquals(Boolean.FALSE, DeviceConfig.parse("{\"nome\":\"Cucina\",\"cartolina\":false}").cartolina);
        assertNull(DeviceConfig.parse("{\"nome\":\"Cucina\"}").cartolina);
        assertNull(DeviceConfig.parse("{\"cartolina\":\"si\"}").cartolina);   // only true or false
        assertTrue(DeviceConfig.parse("{\"cartolina\":true}").onlyCartolina);   // the rest stays as it is
        assertFalse(DeviceConfig.parse("{\"nome\":\"Cucina\",\"cartolina\":true}").onlyCartolina);
    }
}
