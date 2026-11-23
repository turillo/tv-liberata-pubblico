package com.gathin.tvhome;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional personal header for this owner's theme. The weather line under the date reads, for example,
 * "[icon] 19° Partly cloudy · min 12° · max 19°" (Open-Meteo, at config.xml latitude and longitude; no place
 * name). It is requested at most every 30 minutes and only while Home is visible; the last reading is kept,
 * so offline Home shows it with the time it was taken.
 */
final class PersonalHeader {
    private static final long REFRESH_MS = 30 * 60_000L;
    /** A reading older than this shows when it was taken. */
    private static final long STALE_MS = 45 * 60_000L;
    private static final String CACHE = "weather_cache";

    private final Context context;
    private final TextView weather;
    private final WeatherIcon icon;
    private final SharedPreferences cache;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean active;
    private boolean loading;
    private long lastAttempt;

    /** One Open-Meteo reading. */
    private static final class Reading {
        final double temp, min, max;
        final int code;
        final boolean day;
        final long time;

        Reading(double temp, int code, boolean day, double min, double max, long time) {
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.min = min;
            this.max = max;
            this.time = time;
        }
    }

    static boolean enabled(Context context) {
        try {
            File file = new File(context.getExternalFilesDir(null), Config.FILE_NAME);
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
                    .optBoolean("personal_header", false);
        } catch (Exception ignored) {
            return false;
        }
    }

    PersonalHeader(MainActivity activity, String name) {
        context = activity;
        SharedPreferences choices = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        cache = activity.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
        TextView profileName = activity.findViewById(R.id.profileName);
        profileName.setText(name);
        profileName.setVisibility(name.isEmpty() ? View.GONE : View.VISIBLE);
        activity.findViewById(R.id.date).setVisibility(choices.getBoolean("show_date", true) ? View.VISIBLE : View.GONE);
        weather = activity.findViewById(R.id.weather);
        weather.setContentDescription("Weather data: Open-Meteo");
        icon = new WeatherIcon(weather.getCurrentTextColor());
        int size = Math.round(weather.getTextSize() * 1.35f);
        icon.setBounds(0, 0, size, size);
        weather.setCompoundDrawablePadding(Math.round(8 * activity.getResources().getDisplayMetrics().density));
        Reading saved = load();
        if (saved != null) show(saved, false);
        else weather.setText(R.string.weather_loading);
        weather.setVisibility(choices.getBoolean("show_weather", true) ? View.VISIBLE : View.GONE);
    }

    void start() {
        active = true;
        update();
    }

    void stop() { active = false; }

    void close() {
        active = false;
        worker.shutdownNow();
    }

    void update() {
        long now = System.currentTimeMillis();
        if (!active || loading || now - lastAttempt < REFRESH_MS) return;
        lastAttempt = now;
        loading = true;
        worker.execute(() -> {
            Reading result = null;
            try {
                result = fetch();
                ServiceStatus.ok(context, ServiceStatus.METEO);
            } catch (Exception failure) {
                Log.w("TvHome.Weather", "Weather unavailable", failure);
                ServiceStatus.fail(context, ServiceStatus.METEO, failure);
            }
            Reading fresh = result;
            weather.post(() -> {
                loading = false;
                if (fresh != null) save(fresh);
                if (!active) return;
                if (fresh != null) {
                    show(fresh, true);
                } else {
                    Reading saved = load();
                    if (saved != null) show(saved, false);
                }
            });
        });
    }

    /**
     * {latitude, longitude}: config.xml, then the city chosen in Syntax TV or the one found from the connection
     * (Place), then the system's location, then (the owner's build only) the old default; null in TV Liberata
     * when nothing is known yet, and then there is no weather.
     */
    static double[] coordinates(Context context) {
        double[] place = Place.home(context);
        if (place != null) return place;
        Location location = location(context);
        if (location != null) return new double[]{location.getLatitude(), location.getLongitude()};
        return null;   // no made-up place: no weather until one is known
    }

    /** The Open-Meteo request for this TV; also used by Sistema, Servizi ("Riprova ora"). */
    static String forecastUrl(Context context) {
        double[] at = coordinates(context);
        if (at == null) return null;
        double lat = at[0], lon = at[1];
        // A weather model uses a nearby grid cell, not a thermometer at the TV's coordinates.
        return String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                        + "&current=temperature_2m,weather_code,is_day"
                        + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
                lat, lon);
    }

    private Reading fetch() throws Exception {
        Place.refreshAuto(context);   // no-op unless TV Liberata still needs its place
        String endpoint = forecastUrl(context);
        if (endpoint == null) throw new java.io.IOException("posizione sconosciuta");
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            if (connection.getResponseCode() != 200) throw new java.io.IOException("Weather HTTP error");
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = new byte[64_000];
                int count = 0;
                while (count < bytes.length) {
                    int read = input.read(bytes, count, bytes.length - count);
                    if (read < 0) break;
                    count += read;
                }
                JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
                JSONObject current = json.getJSONObject("current");
                JSONObject daily = json.getJSONObject("daily");
                return new Reading(current.getDouble("temperature_2m"), current.getInt("weather_code"),
                        current.optInt("is_day", 1) == 1,
                        daily.getJSONArray("temperature_2m_min").getDouble(0),
                        daily.getJSONArray("temperature_2m_max").getDouble(0),
                        System.currentTimeMillis());
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The line and icon; a reading not just fetched and older than STALE_MS says when it was taken. */
    private void show(Reading r, boolean fresh) {
        int index = WeatherCode.condition(r.code);
        String[] conditions = context.getResources().getStringArray(R.array.weather_conditions);
        String condition = index >= 0 && index < conditions.length ? conditions[index] : "";
        String text = context.getString(R.string.weather_line, Math.round(r.temp), condition,
                Math.round(r.min), Math.round(r.max));
        long age = System.currentTimeMillis() - r.time;
        if (!fresh && age > STALE_MS) {
            Date when = new Date(r.time);
            String time = DateFormat.getTimeFormat(context).format(when);
            if (!DateUtils.isToday(r.time)) time = DateFormat.getMediumDateFormat(context).format(when) + " " + time;
            text += " · " + context.getString(R.string.weather_updated, time);
        }
        icon.set(WeatherCode.icon(r.code), r.day);
        weather.setCompoundDrawablesRelative(icon, null, null, null);
        weather.setText(text);
    }

    private void save(Reading r) {
        cache.edit()
                .putFloat("temp", (float) r.temp)
                .putInt("code", r.code)
                .putBoolean("day", r.day)
                .putFloat("min", (float) r.min)
                .putFloat("max", (float) r.max)
                .putLong("time", r.time)
                .apply();
    }

    private Reading load() {
        if (!cache.contains("time")) return null;
        return new Reading(cache.getFloat("temp", 0), cache.getInt("code", 3), cache.getBoolean("day", true),
                cache.getFloat("min", 0), cache.getFloat("max", 0), cache.getLong("time", 0));
    }


    private static Location location(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return null;
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) return null;
        try {
            Location network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (network != null && System.currentTimeMillis() - network.getTime() < 6 * 60 * 60_000L)
                return network;
        } catch (SecurityException ignored) { }
        return null;
    }
}
