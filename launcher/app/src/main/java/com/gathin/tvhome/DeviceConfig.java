package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * This device's own configuration: what used to be built into the owner's APK (place, Unsplash key, ...) and what
 * differs per device (name, update channel). Set once from the PC, or by the guided set-up for the name:
 * <pre>
 * adb push dispositivo.json /sdcard/Android/data/com.gathin.tvhome.liberata/files/dispositivo.json
 * adb shell am broadcast -n com.gathin.tvhome.liberata/com.gathin.tvhome.DeviceConfig -a com.gathin.tvhome.DEVICE_CONFIG
 * </pre>
 * {"nome":"Firestick-Cucina","canale":"test","latitudine":41.7,"longitudine":12.3,"citta":"...","unsplash_key":"..."}
 * Only the adb shell can send it (WRITE_SECURE_SETTINGS). The file is read, stored privately, then deleted.
 */
public final class DeviceConfig extends BroadcastReceiver {

    private static final String TAG = "TvHome.DeviceConfig";
    static final String PREFS = "device_config", FILE = "dispositivo.json";
    static final String TEST = "test", PRODUCTION = "produzione";

    /** The parsed fields, in plain Java so they are tested. */
    static final class Values {
        String name, channel = PRODUCTION, city, unsplashKey;
        double lat = Double.NaN, lon = Double.NaN;
    }

    static Values parse(String json) {
        Values v = new Values();
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) return null;
        v.name = clean(MiniJson.str(root, "nome"));
        String ch = MiniJson.str(root, "canale");
        if (ch != null && TEST.equals(ch.trim().toLowerCase(Locale.ROOT))) v.channel = TEST;
        v.city = clean(MiniJson.str(root, "citta"));
        v.unsplashKey = clean(MiniJson.str(root, "unsplash_key"));
        double lat = MiniJson.number(root, "latitudine", Double.NaN), lon = MiniJson.number(root, "longitudine", Double.NaN);
        if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0)) {
            v.lat = lat;
            v.lon = lon;
        }
        return v;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : (t.length() > 60 ? t.substring(0, 60) : t);
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void save(Context c, Values v) {
        SharedPreferences.Editor e = prefs(c).edit().clear();
        if (v.name != null) e.putString("nome", v.name);
        e.putString("canale", v.channel);
        if (v.city != null) e.putString("citta", v.city);
        if (v.unsplashKey != null) e.putString("unsplash_key", v.unsplashKey);
        if (!Double.isNaN(v.lat)) e.putString("lat", String.valueOf(v.lat)).putString("lon", String.valueOf(v.lon));
        e.apply();
    }

    static String name(Context c) {
        String n = prefs(c).getString("nome", null);
        return n != null ? n : android.os.Build.MODEL;
    }

    static void setName(Context c, String name) {
        String n = clean(name);
        if (n != null) prefs(c).edit().putString("nome", n).apply();
    }

    static String channel(Context c) {
        return TEST.equals(prefs(c).getString("canale", PRODUCTION)) ? TEST : PRODUCTION;
    }

    static String unsplashKey(Context c) {
        return prefs(c).getString("unsplash_key", "");
    }

    /** The coordinates the configuration fixes, or null. */
    static Place.Spot place(Context c) {
        SharedPreferences p = prefs(c);
        try {
            String lat = p.getString("lat", null), lon = p.getString("lon", null);
            if (lat == null || lon == null) return null;
            Place.Spot s = new Place.Spot(Double.parseDouble(lat), Double.parseDouble(lon), p.getString("citta", null));
            return s.valid() ? s : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        File f = new File(c.getExternalFilesDir(null), FILE);
        String json = intent.getStringExtra("json");
        if (json == null && f.isFile()) json = read(f);
        Values v = json == null ? null : parse(json);
        if (v == null) {
            setResultData("configurazione non valida o mancante (" + f.getPath() + ")");
            return;
        }
        save(c, v);
        Setup.markDone(c);   // configured from the PC: no guided set-up on screen
        //noinspection ResultOfMethodCallIgnored
        f.delete();   // it may hold a key: not left on shared storage
        Log.i(TAG, "device configuration loaded: " + v.name + ", channel " + v.channel);
        setResultData("caricata: " + v.name + ", canale " + v.channel + (Double.isNaN(v.lat) ? "" : ", coordinate fissate"));
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 64_000)];
            int n = 0, r;
            while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
            return new String(b, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
