package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The kitchen panel's weather from Open-Meteo, at the coordinates PersonalHeader uses: now, the next hours, and
 * rain in the next two hours from the 15-minute forecast ("pioggia tra 30 min"). The last answer is kept, so the
 * panel shows something offline; the logic that reads it is plain Java, tested in KitchenWeatherTest.
 */
final class KitchenWeather {
    /** Rain from this many mm in 15 minutes counts; less is a trace the panel does not mention. */
    static final double RAIN_MM = 0.1;
    static final int HOURS = 5;
    private static final String CACHE = "kitchen_weather";

    /** One hour of the forecast. */
    static final class Hour {
        final long time;
        final double temp;
        final int code;
        final int rainChance;

        Hour(long time, double temp, int code, int rainChance) {
            this.time = time;
            this.temp = temp;
            this.code = code;
            this.rainChance = rainChance;
        }
    }

    final double temp;
    final int code;
    final boolean day;
    /** 0: raining now; N: rain in N minutes; -1: no rain in the 15-minute forecast. */
    final int rainIn;
    final List<Hour> hours;
    /** When Open-Meteo answered (System.currentTimeMillis). */
    final long fetched;

    KitchenWeather(double temp, int code, boolean day, int rainIn, List<Hour> hours, long fetched) {
        this.temp = temp;
        this.code = code;
        this.day = day;
        this.rainIn = rainIn;
        this.hours = hours;
        this.fetched = fetched;
    }

    static String url(Context c) {
        double[] at = PersonalHeader.coordinates(c);
        return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                + "&current=temperature_2m,weather_code,is_day,precipitation"
                + "&minutely_15=precipitation&forecast_minutely_15=8"
                + "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=7&timezone=auto",
                at[0], at[1]);
    }

    /** Runs off the UI thread. A fresh reading, saved for later, or null when Open-Meteo could not be read. */
    static KitchenWeather fetch(Context c) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url(c)).openConnection();
            try {
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                if (connection.getResponseCode() != 200) return null;
                byte[] bytes = new byte[64_000];
                int count = 0;
                try (InputStream input = connection.getInputStream()) {
                    while (count < bytes.length) {
                        int read = input.read(bytes, count, bytes.length - count);
                        if (read < 0) break;
                        count += read;
                    }
                }
                String body = new String(bytes, 0, count, StandardCharsets.UTF_8);
                long now = System.currentTimeMillis();
                KitchenWeather w = parse(body, now, now);
                if (w != null) prefs(c).edit().putString("json", body).putLong("time", now).apply();
                return w;
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The last saved answer, read again for {@code now}; null when there is none. */
    static KitchenWeather saved(Context c, long now) {
        SharedPreferences p = prefs(c);
        String json = p.getString("json", null);
        return json == null ? null : parse(json, p.getLong("time", 0), now);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(CACHE, Context.MODE_PRIVATE);
    }

    /** Open-Meteo's answer, with times from its utc_offset_seconds; null when it is not one. */
    static KitchenWeather parse(String body, long fetched, long now) {
        try {
            JSONObject json = new JSONObject(body);
            int offset = json.optInt("utc_offset_seconds", 0);
            JSONObject current = json.getJSONObject("current");
            JSONObject quarter = json.optJSONObject("minutely_15");
            long[] qTimes = new long[0];
            double[] qRain = new double[0];
            if (quarter != null) {
                JSONArray t = quarter.getJSONArray("time"), r = quarter.getJSONArray("precipitation");
                int n = Math.min(t.length(), r.length());
                qTimes = new long[n];
                qRain = new double[n];
                for (int i = 0; i < n; i++) {
                    qTimes[i] = epoch(t.getString(i), offset);
                    qRain[i] = r.optDouble(i, 0);
                }
            }
            JSONObject hourly = json.getJSONObject("hourly");
            JSONArray ht = hourly.getJSONArray("time"), temp = hourly.getJSONArray("temperature_2m"),
                    code = hourly.getJSONArray("weather_code"), chance = hourly.getJSONArray("precipitation_probability");
            List<Hour> all = new ArrayList<>();
            for (int i = 0; i < ht.length(); i++) {
                all.add(new Hour(epoch(ht.getString(i), offset), temp.optDouble(i, 0), code.optInt(i, 3),
                        chance.optInt(i, 0)));
            }
            double rainNow = current.optDouble("precipitation", 0);
            return new KitchenWeather(current.getDouble("temperature_2m"), current.getInt("weather_code"),
                    current.optInt("is_day", 1) == 1, rainIn(qTimes, qRain, rainNow, now), next(all, now, HOURS),
                    fetched);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Minutes until the first 15-minute slot from now with rain (RAIN_MM or more): 0 when it is raining now or the
     * current slot is wet, -1 when no slot is. A slot that has already ended is skipped.
     */
    static int rainIn(long[] times, double[] mm, double rainNow, long now) {
        if (rainNow >= RAIN_MM) return 0;
        for (int i = 0; i < times.length && i < mm.length; i++) {
            if (times[i] + 15 * 60_000L <= now) continue;   // over already
            if (mm[i] >= RAIN_MM) return (int) Math.max(0, (times[i] - now + 59_999) / 60_000);
        }
        return -1;
    }

    /** The {@code count} whole hours after now. */
    static List<Hour> next(List<Hour> hours, long now, int count) {
        List<Hour> out = new ArrayList<>();
        for (Hour h : hours) {
            if (h.time > now && out.size() < count) out.add(h);
        }
        return out;
    }

    /** "2026-11-13T16:30" at {@code offsetSeconds} from UTC, in epoch milliseconds. */
    static long epoch(String localIso, int offsetSeconds) {
        return LocalDateTime.parse(localIso).toEpochSecond(ZoneOffset.ofTotalSeconds(offsetSeconds)) * 1000L;
    }
}
