package com.gathin.tvhome;

import java.io.FileNotFoundException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * The plain logic of the Sistema page's "Servizi": a source's state from its last success and last error, how long
 * it has been failing, and a short Italian reason for an error. Apart from Android, for the JVM tests.
 */
final class ServiceHealth {

    static final int UNKNOWN = 0, OK = 1, LATE = 2, ERROR = 3;
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;
    /** A source failing longer than this shows an alert on Home (Fase 4). */
    static final long ALERT_AFTER = DAY;

    private ServiceHealth() {
    }

    /** Never used, fine, late (no success for longer than {@code lateAfter}) or failing (the last try failed). */
    static int state(long lastOk, long lastErr, long now, long lateAfter) {
        if (lastOk <= 0 && lastErr <= 0) return UNKNOWN;
        if (lastErr > lastOk) return ERROR;
        if (now - lastOk > lateAfter) return LATE;
        return OK;
    }

    /** When the current run of errors began: kept while the errors go on, reset after a success. */
    static long errorSince(long lastOk, long errSince, long now) {
        return errSince > lastOk ? errSince : now;
    }

    /** How long the source has been failing, 0 when its last try worked. */
    static long failingFor(long lastOk, long lastErr, long errSince, long now) {
        if (lastErr <= 0 || lastErr <= lastOk) return 0;
        long since = errSince > 0 ? errSince : lastErr;
        return Math.max(0, now - since);
    }

    static String label(int state) {
        switch (state) {
            case OK: return "ok";
            case LATE: return "in ritardo";
            case ERROR: return "errore";
            default: return "mai usata";
        }
    }

    /** A short reason for the list: the common network failures in words, others as their message, cut to 70. */
    static String reason(Throwable t) {
        if (t == null) return "errore";
        if (t instanceof UnknownHostException) return "server non trovato (rete o DNS)";
        if (t instanceof SocketTimeoutException) return "tempo scaduto";
        if (t instanceof FileNotFoundException) return "indirizzo non trovato (404)";
        if (t instanceof javax.net.ssl.SSLException) return "errore di connessione sicura";
        if (t instanceof java.net.ConnectException) return "connessione rifiutata";
        return reason(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
    }

    static String reason(String message) {
        String m = message == null || message.trim().isEmpty() ? "errore" : message.trim().replace('\n', ' ');
        return m.length() > 70 ? m.substring(0, 69) + "…" : m;
    }

    /** "oggi 14:05", "ieri 09:12" or "3 nov 18:40"; "mai" for 0. */
    static String when(long t, long now, java.util.TimeZone zone) {
        if (t <= 0) return "mai";
        java.util.Calendar a = java.util.Calendar.getInstance(zone, Locale.ITALY);
        a.setTimeInMillis(t);
        String hm = String.format(Locale.ITALY, "%02d:%02d", a.get(java.util.Calendar.HOUR_OF_DAY), a.get(java.util.Calendar.MINUTE));
        long days = dayIndex(now, zone) - dayIndex(t, zone);
        if (days == 0) return "oggi " + hm;
        if (days == 1) return "ieri " + hm;
        String[] months = {"gen", "feb", "mar", "apr", "mag", "giu", "lug", "ago", "set", "ott", "nov", "dic"};
        return a.get(java.util.Calendar.DAY_OF_MONTH) + " " + months[a.get(java.util.Calendar.MONTH)] + " " + hm;
    }

    /** Local days since 1970, so "ieri" works across months and years. */
    private static long dayIndex(long t, java.util.TimeZone zone) {
        return Math.floorDiv(t + zone.getOffset(t), DAY);
    }

    /** What the TV uses now, for {@link #idleReason}: read by ServiceStatus.usage on the TV. */
    static final class Usage {
        boolean ticker, unsplashKey;
        /** The system screensaver's name when it is not the launcher's own (ClockDream), else null. */
        String otherDream;
        final java.util.Set<String> wallpaperSources = new java.util.HashSet<>(), scenes = new java.util.HashSet<>(),
                newsSources = new java.util.HashSet<>();
    }

    /**
     * Why a source is not being used right now, as Sistema, Servizi shows it instead of "mai usata"; null when it
     * should be working (then its own state, errors included, is what counts).
     */
    static String idleReason(String id, Usage u) {
        switch (id) {
            case ServiceStatus.BING:
            case ServiceStatus.WIKIMEDIA:
            case ServiceStatus.UNSPLASH:
                if (ServiceStatus.UNSPLASH.equals(id) && !u.unsplashKey) return "Chiave mancante";
                return u.wallpaperSources.contains(id) ? null : "Sorgente non scelta";
            case ServiceStatus.ANSA:
            case ServiceStatus.ILPOST:
                if (!u.ticker) return "Striscia notizie spenta";
                return u.newsSources.contains(id) ? null : "Non scelta nelle notizie";
            case ServiceStatus.EUMETSAT:
            case ServiceStatus.SDO:
            case ServiceStatus.EPIC:
            case ServiceStatus.MOON:
            case ServiceStatus.ISS:
            case ServiceStatus.ESA:
                if (u.otherDream != null) return "In pausa: screensaver " + u.otherDream;
                return u.scenes.contains(scene(id)) ? null : "Scena non attiva";
            default:
                return null;
        }
    }

    /** The screensaver scene a source feeds. */
    static String scene(String id) {
        switch (id) {
            case ServiceStatus.EUMETSAT: return Config.SCENE_LIVE;
            case ServiceStatus.SDO: return Config.SCENE_SUN;
            case ServiceStatus.EPIC: return Config.SCENE_EARTH;
            case ServiceStatus.MOON: return Config.SCENE_MOON;
            case ServiceStatus.ISS: return Config.SCENE_ISS;
            case ServiceStatus.ESA: return Config.SCENE_SPACE;
            default: return null;
        }
    }
}
