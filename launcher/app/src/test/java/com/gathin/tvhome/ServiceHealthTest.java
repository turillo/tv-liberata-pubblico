package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.TimeZone;

import org.junit.Test;

public class ServiceHealthTest {

    private static final long H = ServiceHealth.HOUR, NOW = 1_000 * H;

    @Test
    public void states() {
        assertEquals(ServiceHealth.UNKNOWN, ServiceHealth.state(0, 0, NOW, 48 * H));
        assertEquals(ServiceHealth.OK, ServiceHealth.state(NOW - H, 0, NOW, 48 * H));
        assertEquals(ServiceHealth.OK, ServiceHealth.state(NOW - H, NOW - 2 * H, NOW, 48 * H));   // error, then fixed
        assertEquals(ServiceHealth.ERROR, ServiceHealth.state(NOW - 2 * H, NOW - H, NOW, 48 * H));
        assertEquals(ServiceHealth.ERROR, ServiceHealth.state(0, NOW - H, NOW, 48 * H));
        assertEquals(ServiceHealth.LATE, ServiceHealth.state(NOW - 49 * H, 0, NOW, 48 * H));
    }

    @Test
    public void errorRunsStartAtTheFirstErrorAfterASuccess() {
        long ok = NOW - 30 * H;
        long since = ServiceHealth.errorSince(ok, 0, NOW - 26 * H);          // first error
        assertEquals(NOW - 26 * H, since);
        assertEquals(since, ServiceHealth.errorSince(ok, since, NOW - H));   // still failing: kept
        assertEquals(26 * H, ServiceHealth.failingFor(ok, NOW - H, since, NOW));
        long later = ServiceHealth.errorSince(NOW - 10 * H, since, NOW);     // new run after a success
        assertEquals(NOW, later);
        assertEquals(0, ServiceHealth.failingFor(NOW - H, NOW - 2 * H, since, NOW));
        assertEquals(0, ServiceHealth.failingFor(0, 0, 0, NOW));
    }

    @Test
    public void alertOnlyAfterADayOfErrors() {
        long ok = NOW - 40 * H;
        assertEquals(true, ServiceHealth.failingFor(ok, NOW - H, NOW - 25 * H, NOW) > ServiceHealth.ALERT_AFTER);
        assertEquals(false, ServiceHealth.failingFor(ok, NOW - H, NOW - 23 * H, NOW) > ServiceHealth.ALERT_AFTER);
    }

    @Test
    public void reasons() {
        assertEquals("server non trovato (rete o DNS)", ServiceHealth.reason(new UnknownHostException("x")));
        assertEquals("tempo scaduto", ServiceHealth.reason(new SocketTimeoutException()));
        assertEquals("indirizzo non trovato (404)", ServiceHealth.reason(new FileNotFoundException()));
        assertEquals("HTTP 503", ServiceHealth.reason(new IOException("HTTP 503")));
        assertEquals("errore", ServiceHealth.reason((String) null));
        assertEquals(70, ServiceHealth.reason(new String(new char[200]).replace('\0', 'a')).length());
    }

    @Test
    public void when() {
        TimeZone rome = TimeZone.getTimeZone("Europe/Rome");
        long now = 1_794_160_000_000L;   // 8 Nov 2026, afternoon in Rome
        assertEquals("mai", ServiceHealth.when(0, now, rome));
        assertEquals("oggi", ServiceHealth.when(now - 60_000, now, rome).substring(0, 4));
        assertEquals("ieri", ServiceHealth.when(now - 24 * H, now, rome).substring(0, 4));
        assertEquals("6 nov", ServiceHealth.when(now - 48 * H, now, rome).substring(0, 5));
    }

    @Test
    public void idleSourcesSayWhy() {
        ServiceHealth.Usage u = new ServiceHealth.Usage();
        u.ticker = true;
        u.newsSources.add(ServiceStatus.ANSA);
        u.scenes.add(Config.SCENE_ISS);
        u.otherDream = "Aerial Views";
        org.junit.Assert.assertNull(ServiceHealth.idleReason(ServiceStatus.ANSA, u));
        org.junit.Assert.assertEquals("Non scelta nelle notizie", ServiceHealth.idleReason(ServiceStatus.ILPOST, u));
        org.junit.Assert.assertEquals("In pausa: screensaver Aerial Views", ServiceHealth.idleReason(ServiceStatus.ISS, u));
        u.otherDream = null;
        org.junit.Assert.assertNull(ServiceHealth.idleReason(ServiceStatus.ISS, u));
        org.junit.Assert.assertEquals("Scena non attiva", ServiceHealth.idleReason(ServiceStatus.SDO, u));
        u.wallpaperSources.add(ServiceStatus.BING);
        org.junit.Assert.assertNull(ServiceHealth.idleReason(ServiceStatus.BING, u));
        org.junit.Assert.assertEquals("Sorgente non scelta", ServiceHealth.idleReason(ServiceStatus.WIKIMEDIA, u));
        org.junit.Assert.assertEquals("Chiave mancante", ServiceHealth.idleReason(ServiceStatus.UNSPLASH, u));
        u.ticker = false;
        org.junit.Assert.assertEquals("Striscia notizie spenta", ServiceHealth.idleReason(ServiceStatus.ANSA, u));
        org.junit.Assert.assertNull(ServiceHealth.idleReason(ServiceStatus.METEO, u));
    }
}
