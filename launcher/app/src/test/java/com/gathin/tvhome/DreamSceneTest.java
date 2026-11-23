package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** dream_scene: known names pass through, anything else is the clock. */
public class DreamSceneTest {

    @Test
    public void knownScenes() {
        assertEquals("iss", Config.dreamScene(" ISS "));
        assertEquals("sun", Config.dreamScene("Sun"));
        assertEquals("earth", Config.dreamScene("earth"));
        assertEquals("moon", Config.dreamScene("MOON"));
        assertEquals("space", Config.dreamScene("space"));
        assertEquals("live", Config.dreamScene("Live"));
        assertEquals("random", Config.dreamScene("random"));
        assertEquals("clock", Config.dreamScene("clock"));
    }

    @Test
    public void unknownIsClock() {
        assertEquals("clock", Config.dreamScene("mars"));
        assertEquals("clock", Config.dreamScene(""));
        assertEquals("clock", Config.dreamScene((String) null));
    }
}
