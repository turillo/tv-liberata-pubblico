package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PlaceTest {

    @Test
    public void readsIpwhoisReplies() {
        Place.Spot s = Place.parseIp("{\"success\":true,\"city\":\"Rome\",\"latitude\":41.8919326,\"longitude\":12.5113342,"
                + "\"country_code\":\"IT\"}");
        assertEquals("Rome", s.name);
        assertEquals(41.892, s.lat, 0.001);
        assertNull(Place.parseIp("{\"success\":false,\"message\":\"Reserved range\"}"));
        assertNull(Place.parseIp("{\"success\":true,\"city\":\"X\"}"));
        assertNull(Place.parseIp("{\"success\":true,\"latitude\":0,\"longitude\":0}"));   // not a place
        assertNull(Place.parseIp("Too Many Requests"));
    }

    @Test
    public void readsTheCitySearch() {
        List<Place.Spot> r = Place.parseSearch("{\"results\":[{\"name\":\"Roma\",\"latitude\":41.89,\"longitude\":12.51,"
                + "\"admin1\":\"Lazio\",\"country\":\"Italia\"},{\"name\":\"Rome\",\"latitude\":34.25,\"longitude\":-85.16,"
                + "\"country\":\"Stati Uniti\"},{\"name\":\"Bad\"}]}");
        assertEquals(2, r.size());
        assertEquals("Roma (Lazio, Italia)", r.get(0).name);
        assertEquals("Rome (Stati Uniti)", r.get(1).name);
        assertTrue(Place.parseSearch("{\"generationtime_ms\":0.1}").isEmpty());
        assertTrue(Place.searchUrl("San Donà di Piave").contains("name=San+Don%C3%A0+di+Piave"));
        assertTrue(Place.searchUrl("x").startsWith("https://geocoding-api.open-meteo.com/"));
    }

    @Test
    public void spotsOutOfRangeAreNotPlaces() {
        assertTrue(new Place.Spot(45, 9, "Milano").valid());
        assertFalse(new Place.Spot(95, 9, null).valid());
        assertFalse(new Place.Spot(45, 190, null).valid());
    }
}
