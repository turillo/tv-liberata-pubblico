package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Liberata 1.1.0, the "cartolina": with the owner's yes, this TV tells Syntax that it uses TV Liberata and which
 * version, at the first set-up ("installata") and once after each update ("aggiornata"). Sent: a random code made on
 * this TV, the TV's name, the version, the channel, the model and the system; nothing else (no apps, use, times, IP or
 * accounts). Turned off in Sistema, it sends "cancella" (the row goes) and then nothing more.
 * <p>
 * The receiver's URL and secret come from launcher/keys.properties into the APK (never in the public repository);
 * without them the cartolina is off and never asks.
 */
final class Cartolina {

    private static final String TAG = "TvHome.Cartolina";
    static final String PREFS = "cartolina";
    static final String INSTALLED = "installata", UPDATED = "aggiornata", DELETE = "cancella";
    static final long RETRY_MS = 24L * 60 * 60 * 1000;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private Cartolina() {
    }

    static boolean enabled() {
        return !BuildConfig.CARTOLINA_URL.isEmpty() && !BuildConfig.CARTOLINA_SECRET.isEmpty();
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether the owner said yes (the switch in Sistema). */
    static boolean on(Context c) {
        return "si".equals(prefs(c).getString("consenso", ""));
    }

    static boolean answered(Context c) {
        return !prefs(c).getString("consenso", "").isEmpty();
    }

    /** This TV's random code, made once. */
    static synchronized String code(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("codice", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            p.edit().putString("codice", id).apply();
        }
        return id;
    }

    /**
     * The owner's answer. Yes: the first message goes ({@code first}: "installata" after a set-up, "aggiornata" for a
     * TV that already had TV Liberata). No after a yes: "cancella", then nothing more.
     */
    static void setConsent(Context c, boolean yes, String first) {
        SharedPreferences p = prefs(c);
        boolean was = on(c);
        SharedPreferences.Editor e = p.edit().putString("consenso", yes ? "si" : "no").putLong("tentato", 0);
        if (yes) {
            if (!was) e.putString("inviata", "").putString("evento", first);
        } else {
            e.putString("evento", was || !p.getString("inviata", "").isEmpty() ? DELETE : "");
        }
        e.apply();
        send(c);
    }

    /** Asks once (Home, for a TV that had TV Liberata before 1.1.0); the focus starts on "Sì". */
    static void askOnce(Activity a) {
        if (!enabled() || answered(a) || !Setup.done(a) || prefs(a).getBoolean("chiesto", false)) return;
        ask(a, UPDATED, null);
    }

    /** The question; {@code then} runs after the answer (the guided set-up goes on). Back counts as no answer yet. */
    static void ask(Activity a, String first, Runnable then) {
        if (!enabled()) {
            if (then != null) then.run();
            return;
        }
        prefs(a).edit().putBoolean("chiesto", true).apply();
        android.app.Dialog d = SyntaxDialog.show(a, a.getString(R.string.cartolina_question), a.getString(R.string.cartolina_text),
                new String[]{a.getString(R.string.syntax_yes), a.getString(R.string.syntax_no)}, 0, which -> {
                    setConsent(a, which == 0, first);
                    if (then != null) then.run();
                });
        if (then != null) d.setOnCancelListener(x -> then.run());
    }

    /** Home: after an update, one "aggiornata" for this version; a message not sent yet is tried again (once a day). */
    static void tick(Context c) {
        if (!enabled()) return;
        SharedPreferences p = prefs(c);
        if (on(c) && p.getString("evento", "").isEmpty() && !BuildConfig.VERSION_NAME.equals(p.getString("inviata", ""))) {
            p.edit().putString("evento", UPDATED).putLong("tentato", 0).apply();
        }
        if (p.getString("evento", "").isEmpty()) return;
        if (System.currentTimeMillis() - p.getLong("tentato", 0) < RETRY_MS) return;
        send(c);
    }

    /** Sends the waiting message, off the main thread. */
    static void send(Context context) {
        if (!enabled()) return;
        Context c = context.getApplicationContext();
        WORKER.execute(() -> {
            SharedPreferences p = prefs(c);
            String event = p.getString("evento", "");
            if (event.isEmpty()) return;
            if (!DELETE.equals(event) && !on(c)) return;
            p.edit().putLong("tentato", System.currentTimeMillis()).apply();
            String body = payload(BuildConfig.CARTOLINA_SECRET, code(c), DeviceConfig.name(c), BuildConfig.VERSION_NAME,
                    DeviceConfig.channel(c), model(), system(), event);
            String reply;
            try {
                reply = post(BuildConfig.CARTOLINA_URL, body);
            } catch (IOException | RuntimeException e) {
                Log.i(TAG, "not sent (" + event + "), again tomorrow: " + e.getMessage());
                return;
            }
            Log.i(TAG, event + ": " + reply);
            if (delivered(reply)) {
                SharedPreferences.Editor e = p.edit().putString("evento", "");
                if (!DELETE.equals(event)) e.putString("inviata", BuildConfig.VERSION_NAME);
                else e.putString("inviata", "");
                e.apply();
            }
        });
    }

    /** The receiver's answer: "ok", or "gia' ricevuto" (this event and version came already) also counts as delivered. Tested. */
    static boolean delivered(String reply) {
        if (reply == null) return false;
        String r = reply.trim().toLowerCase(Locale.ROOT);
        return r.startsWith("ok") || r.startsWith("gia");
    }

    /** "Amazon AFTSS", "Sony BRAVIA 4K GB ATV3". */
    static String model() {
        String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String mod = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (!man.isEmpty() && mod.toLowerCase(Locale.ROOT).startsWith(man.toLowerCase(Locale.ROOT))) return mod;
        return (man.isEmpty() ? mod : man.substring(0, 1).toUpperCase(Locale.ROOT) + man.substring(1) + " " + mod).trim();
    }

    /** "Fire OS 7.7" or "Android 9". */
    static String system() {
        boolean fire = "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
        return system(fire, fire ? property("ro.build.version.name") : "", Build.VERSION.RELEASE, Build.VERSION.SDK_INT);
    }

    /** Plain Java, tested: "Fire OS 7.7.1.7 (PS7717/...)" gives "Fire OS 7.7"; without it, from the API level. */
    static String system(boolean fire, String fireName, String release, int sdk) {
        if (!fire) return "Android " + release;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Fire OS (\\d+)(\\.\\d+)?").matcher(fireName == null ? "" : fireName);
        if (m.find()) return "Fire OS " + m.group(1) + (m.group(2) == null ? "" : m.group(2));
        int major = sdk >= 30 ? 8 : sdk >= 28 ? 7 : sdk >= 25 ? 6 : 5;
        return "Fire OS " + major;
    }

    private static String property(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** The JSON the receiver expects. Plain Java, tested. */
    static String payload(String secret, String code, String name, String version, String channel, String model,
                          String system, String event) {
        return "{" + pair("segreto", secret) + "," + pair("codice", code) + "," + pair("nome", name) + ","
                + pair("versione", version) + "," + pair("canale", channel) + "," + pair("modello", model) + ","
                + pair("sistema", system) + "," + pair("evento", event) + "}";
    }

    private static String pair(String k, String v) {
        return "\"" + k + "\":\"" + escape(v == null ? "" : v) + "\"";
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.toString();
    }

    /**
     * POST to the Apps Script web app. Google answers a POST with a redirect to the script's output: that is read with
     * a GET (the message is already stored by then).
     */
    private static String post(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        if (code / 100 == 3) {
            String next = c.getHeaderField("Location");
            c.disconnect();
            if (next == null) throw new IOException("redirect without Location");
            HttpURLConnection g = (HttpURLConnection) new URL(next).openConnection();
            g.setConnectTimeout(15_000);
            g.setReadTimeout(30_000);
            if (g.getResponseCode() != 200) throw new IOException("HTTP " + g.getResponseCode());
            return read(g);
        }
        if (code != 200) throw new IOException("HTTP " + code);
        return read(c);
    }

    private static String read(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 16_000) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
