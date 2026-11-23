package com.gathin.tvhome;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV Liberata's updates from the public repository's GitHub Releases (BuildConfig.UPDATE_REPO): the latest
 * release's tag ("v1.1") against this version, its .apk asset downloaded, its signature checked against this app's
 * own (an APK signed with another key is never installed), then Android's installer (PackageInstaller; the TV asks
 * to confirm). Home checks at most once a day and offers a new version once per version; Sistema's "Cerca
 * aggiornamenti" checks now. The owner's build has no UPDATE_REPO: nothing here runs.
 */
final class Updater {

    private static final String TAG = "TvHome.Updater";
    private static final String PREFS = "updater";
    static final long CHECK_EVERY = 24 * 3_600_000L;
    private static final long MAX_APK = 64L << 20;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    /** The latest release: its version and its APK's address. */
    static final class Release {
        final String version, apkUrl;
        /** The release's text (the changes, in Italian), shown after the update. */
        String notes = "";

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
        }
    }

    private Updater() {
    }

    static boolean enabled() {
        return !BuildConfig.UPDATE_REPO.isEmpty();
    }

    // ---- plain Java, tested -----------------------------------------------------------------------------------

    /** GitHub's "latest release" reply: tag_name and the first asset whose name ends in .apk; null otherwise. */
    static Release parse(String json) {
        return parse(MiniJson.parse(json), false);
    }

    /** One release object; pre-releases only when {@code pre} (the test channel). */
    static Release parse(Object root, boolean pre) {
        if (!(root instanceof Map)) return null;
        String tag = MiniJson.str(root, "tag_name");
        Object draft = ((Map<?, ?>) root).get("draft"), isPre = ((Map<?, ?>) root).get("prerelease");
        if (tag == null || Boolean.TRUE.equals(draft) || (!pre && Boolean.TRUE.equals(isPre))) return null;
        List<?> assets = MiniJson.list(root, "assets");
        if (assets == null) return null;
        for (Object a : assets) {
            String name = MiniJson.str(a, "name"), url = MiniJson.str(a, "browser_download_url");
            if (name != null && url != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")
                    && url.startsWith("https://")) {
                Release r = new Release(version(tag), url);
                String body = MiniJson.str(root, "body");
                r.notes = body == null ? "" : body.trim();
                return r;
            }
        }
        return null;
    }

    /** The test channel: the highest version among all releases, pre-releases included; null when there is none. */
    static Release newest(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof List)) return null;
        Release best = null;
        for (Object o : (List<?>) root) {
            Release r = parse(o, true);
            if (r != null && (best == null || newer(r.version, best.version))) best = r;
        }
        return best;
    }

    /** produzione.json: {"versione":"1.0"}; null when missing or unreadable. */
    static String productionVersion(String json) {
        String v = MiniJson.str(MiniJson.parse(json), "versione");
        return v == null || v.trim().isEmpty() ? null : version(v);
    }

    /** "v1.2.0" or "1.2" to "1.2.0" / "1.2". */
    static String version(String tag) {
        String t = tag.trim();
        return t.startsWith("v") || t.startsWith("V") ? t.substring(1) : t;
    }

    /** Numeric comparison, part by part: "1.10" is newer than "1.9"; missing parts count as 0. */
    static boolean newer(String candidate, String current) {
        int[] a = parts(candidate), b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String v) {
        String[] p = v.split("[.\\-+ ]");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i].replaceAll("\\D.*$", ""));
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Same signing certificates, whatever the order. */
    static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
        String[] x = new String[a.length], y = new String[b.length];
        for (int i = 0; i < a.length; i++) {
            x[i] = a[i].toCharsString();
            y[i] = b[i].toCharsString();
        }
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    // ---- on the TV --------------------------------------------------------------------------------------------

    // ---- the night: silent update through the TV's own adb -------------------------------------------------------

    /** Every night at about 4: Nightly runs a check (and the update, when the TV is not in use). */
    static void schedule(Context c) {
        if (!enabled()) return;
        try {
            android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
            java.util.Calendar at = java.util.Calendar.getInstance();
            at.set(java.util.Calendar.HOUR_OF_DAY, 4);
            at.set(java.util.Calendar.MINUTE, 0);
            at.set(java.util.Calendar.SECOND, 0);
            if (at.getTimeInMillis() <= System.currentTimeMillis()) at.add(java.util.Calendar.DAY_OF_MONTH, 1);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(c, 4, new Intent(c, Nightly.class), flags);
            am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP, at.getTimeInMillis(),
                    android.app.AlarmManager.INTERVAL_DAY, pi);
        } catch (RuntimeException e) {
            Log.w(TAG, "night check not scheduled: " + e.getMessage());
        }
    }

    /** The night's check. With the screen off and the TV's adb authorised, installs in silence; else Home offers it. */
    public static final class Nightly extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Context c = context.getApplicationContext();
            PendingResult pending = goAsync();
            WORKER.execute(() -> {
                try {
                    silentUpdate(c);
                } finally {
                    pending.finish();
                }
            });
        }
    }

    /** True when the update was handed to pm (the app then restarts as the new version). Never throws. */
    static boolean silentUpdate(Context c) {
        if (!enabled()) return false;
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            if (pm != null && pm.isInteractive()) return false;   // in use: Home will offer it instead
            Release r = latestQuiet(c);
            if (r == null || !newer(r.version, BuildConfig.VERSION_NAME)) return false;
            if (!AdbLocal.authorized(c)) return false;            // plan B: Home's "Aggiornamento disponibile"
            File dir = c.getExternalCacheDir();                   // readable by adb's shell, unlike the private cache
            if (dir == null) return false;
            File apk = new File(dir, "tv-liberata-update.apk");
            download(r.apkUrl, apk);
            if (!signedLikeUs(c, apk)) {
                Log.w(TAG, "night update refused: wrong signature");
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                return false;
            }
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("updated_to", r.version)
                    .putString("updated_notes", r.notes).putBoolean("updated_shown", false).apply();
            String out = AdbLocal.shell(c, "pm install -r " + AdbLocal.quote(apk.getPath()), 180_000);
            Log.i(TAG, "night update " + r.version + ": " + out.trim());   // not reached on success: the process restarts
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            return out.contains("Success");
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "night update failed: " + e.getMessage());
            return false;
        }
    }

    /** At the first Home after a silent update: "TV Liberata è stata aggiornata alla versione X" and the changes. */
    static void announce(Activity home) {
        SharedPreferences p = home.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String to = p.getString("updated_to", null);
        if (to == null || p.getBoolean("updated_shown", true) || !to.equals(BuildConfig.VERSION_NAME)) return;
        p.edit().putBoolean("updated_shown", true).apply();
        String notes = p.getString("updated_notes", "");
        SyntaxDialog.show(home, home.getString(R.string.update_done_title, to),
                notes == null || notes.isEmpty() ? home.getString(R.string.update_done_no_notes) : notes,
                new String[]{home.getString(R.string.update_ok)}, 0, which -> { });
    }

    /** Home's daily check: a new version is offered once (Home shows the dialog). Never throws. */
    static void maybeOffer(Activity home) {
        if (!enabled()) return;
        announce(home);
        SharedPreferences p = home.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("checked", 0) < CHECK_EVERY) return;
        p.edit().putLong("checked", now).apply();
        Handler ui = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            Release r = latestQuiet(home);
            if (r == null || !newer(r.version, BuildConfig.VERSION_NAME)) return;
            if (r.version.equals(p.getString("offered", ""))) return;
            p.edit().putString("offered", r.version).apply();
            ui.post(() -> {
                if (!home.isFinishing() && !home.isDestroyed()) offer(home, r);
            });
        });
    }

    /** Sistema's "Cerca aggiornamenti": the result on the chip, the offer as a dialog. */
    static void checkNow(Activity a, TextView chip) {
        CharSequence label = chip.getText();
        chip.setText(R.string.update_checking);
        Handler ui = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            Release found;
            boolean failed = false;
            try {
                found = latest(a);
            } catch (IOException e) {
                found = null;
                failed = true;
            }
            Release r = found;
            boolean error = failed;
            ui.post(() -> {
                chip.setText(label);
                if (a.isFinishing()) return;
                if (error) Toast.makeText(a, R.string.update_failed, Toast.LENGTH_LONG).show();
                else if (r == null || !newer(r.version, BuildConfig.VERSION_NAME)) {
                    Toast.makeText(a, a.getString(R.string.update_none, BuildConfig.VERSION_NAME), Toast.LENGTH_LONG).show();
                } else offer(a, r);
            });
        });
    }

    private static void offer(Activity a, Release r) {
        SyntaxDialog.show(a, a.getString(R.string.update_available_title, r.version),
                a.getString(R.string.update_available, r.version, BuildConfig.VERSION_NAME),
                new String[]{a.getString(R.string.update_install), a.getString(R.string.update_later)}, 0, which -> {
                    if (which == 0) install(a, r);
                });
    }

    /** The latest release, or null (no network, no release, GitHub's limit of 60 requests an hour reached). */
    /** No release yet, or no repository yet: not an error, just nothing to install. */
    static final class NotFound extends IOException {
        NotFound() {
            super("HTTP 404");
        }
    }

    /**
     * The release this device's channel wants: test, the newest of all (pre-releases too); produzione, the one named
     * in produzione.json. Null when there is none; throws when GitHub cannot be reached. Records the result in
     * Sistema, Servizi ("GitHub aggiornamenti").
     */
    static Release latest(Context c) throws IOException {
        String api = "https://api.github.com/repos/" + BuildConfig.UPDATE_REPO;
        try {
            Release r;
            if (DeviceConfig.TEST.equals(DeviceConfig.channel(c))) {
                r = newest(get(api + "/releases?per_page=30"));
            } else {
                String v = productionVersion(get("https://raw.githubusercontent.com/" + BuildConfig.UPDATE_REPO
                        + "/main/produzione.json"));
                r = v == null ? null : parse(MiniJson.parse(get(api + "/releases/tags/v" + v)), true);
            }
            ServiceStatus.ok(c, ServiceStatus.GITHUB);
            return r;
        } catch (NotFound e) {
            ServiceStatus.ok(c, ServiceStatus.GITHUB);   // reachable, nothing published yet
            return null;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "no release info: " + e.getMessage());
            ServiceStatus.fail(c, ServiceStatus.GITHUB, e instanceof IOException ? e.getMessage() : "errore");
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    /** Like latest, but null on any failure (the quiet checks). */
    static Release latestQuiet(Context c) {
        try {
            return latest(c);
        } catch (IOException e) {
            return null;
        }
    }

    private static void install(Activity a, Release r) {
        Context app = a.getApplicationContext();
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, R.string.update_permission, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(a, R.string.update_downloading, Toast.LENGTH_LONG).show();
        Handler ui = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            File apk = new File(app.getCacheDir(), "update.apk");
            try {
                download(r.apkUrl, apk);
                if (!signedLikeUs(app, apk)) {
                    ui.post(() -> Toast.makeText(app, R.string.update_bad_signature, Toast.LENGTH_LONG).show());
                    return;
                }
                session(app, apk);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "update not installed: " + e.getMessage());
                ui.post(() -> Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show());
            } finally {
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
            }
        });
    }

    @SuppressWarnings("deprecation")
    static boolean signedLikeUs(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo theirs = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (theirs == null || !c.getPackageName().equals(theirs.packageName)) return false;
        try {
            PackageInfo ours = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES);
            return sameSigners(ours.signatures, theirs.signatures);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Android's installer: the APK in a session; the TV shows its own "install?" screen. */
    /** Any checked APK (the catalogue's apps) through Android's installer; the TV asks to confirm. */
    static void installFile(Context c, File apk) throws IOException {
        session(c, apk, false);
    }

    private static void session(Context c, File apk) throws IOException {
        session(c, apk, true);
    }

    private static void session(Context c, File apk, boolean self) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (self) params.setAppPackageName(c.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session s = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("update.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(c, Result.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, id, done, flags).getIntentSender());
        }
    }

    /** The installer's answer: its confirmation screen when it needs one, a message when it failed. */
    public static final class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "install failed: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                Toast.makeText(c, R.string.update_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---- network ----------------------------------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);   // GitHub's assets redirect to its download host
        c.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() == 404) throw new NotFound();
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() > 2_000_000) throw new IOException("risposta troppo grande");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File target) throws IOException {
        HttpURLConnection c = open(url);
        c.setRequestProperty("Accept", "application/octet-stream");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_APK) throw new IOException("file troppo grande");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
