package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The TV Liberata catalogue: a JSON list kept in the public repository (catalogo.json), so apps can be added without
 * a new release; a copy is bundled for when GitHub cannot be reached. Each app comes from its official source and is
 * installed only when its SHA-256 and its signing certificate match what the catalogue says.
 */
final class Catalog {

    private static final String TAG = "TvHome.Catalog";
    static final String URL = "https://raw.githubusercontent.com/turillo/tv-liberata-pubblico/main/catalogo.json";

    static final String FREE = "libera", STORE = "store", EXTRA = "extra";

    /** One app of the catalogue. */
    static final class App {
        String id, name, category, description, pkg, type = FREE, version, apk, sha256, signer, source;
        long code;
        final List<String> platforms = new ArrayList<>();
        /** Per-ABI APKs: {abi, url, sha256}; "*" fits every device. Empty: {@link #apk} alone. */
        final List<String[]> variants = new ArrayList<>();

        boolean store() {
            return STORE.equals(type);
        }

        /** Shown on this kind of TV ("fireos" or "androidtv"); no list means both. */
        boolean on(String platform) {
            return platforms.isEmpty() || platforms.contains(platform);
        }
    }

    private Catalog() {
    }

    /**
     * The catalogue's apps. A "store" app needs only a package; an APK app ("libera", "extra") needs an https address
     * and something to check it against (SHA-256 or signer), or it is left out: never installed unchecked.
     */
    static List<App> parse(String json) {
        List<App> out = new ArrayList<>();
        List<?> apps = MiniJson.list(MiniJson.parse(json), "app");
        if (apps == null) return out;
        for (Object o : apps) {
            App a = new App();
            a.id = MiniJson.str(o, "id");
            a.name = MiniJson.str(o, "nome");
            a.category = MiniJson.str(o, "categoria");
            a.description = MiniJson.str(o, "descrizione");
            a.pkg = MiniJson.str(o, "pacchetto");
            String type = MiniJson.str(o, "tipo");
            if (type != null) a.type = type.trim().toLowerCase(Locale.ROOT);
            a.version = MiniJson.str(o, "versione");
            a.code = (long) MiniJson.number(o, "codice", 0);
            a.apk = MiniJson.str(o, "apk");
            a.sha256 = lower(MiniJson.str(o, "sha256"));
            a.signer = lower(MiniJson.str(o, "firma"));
            a.source = MiniJson.str(o, "fonte");
            List<?> on = MiniJson.list(o, "piattaforme");
            if (on != null) for (Object p : on) if (p instanceof String) a.platforms.add(((String) p).toLowerCase(Locale.ROOT));
            List<?> variants = MiniJson.list(o, "varianti");
            if (variants != null) for (Object v : variants) {
                String abi = MiniJson.str(v, "abi"), url = MiniJson.str(v, "apk"), sha = lower(MiniJson.str(v, "sha256"));
                if (abi != null && url != null && url.startsWith("https://") && sha != null) a.variants.add(new String[]{abi, url, sha});
            }
            if (a.pkg == null || a.name == null) continue;
            if (!a.store()) {
                if (!FREE.equals(a.type) && !EXTRA.equals(a.type)) continue;   // a kind this version does not know
                if (a.variants.isEmpty() && (a.apk == null || !a.apk.startsWith("https://"))) continue;
                if (a.sha256 == null && a.signer == null && a.variants.isEmpty()) continue;   // nothing to check against
            }
            out.add(a);
        }
        return out;
    }

    /**
     * The APK for this device: the first variant whose ABI the device supports, in the device's own order of
     * preference (Build.SUPPORTED_ABIS), then a "*" variant, then the entry's single APK. {url, sha256}; null: none.
     * Plain Java, tested.
     */
    static String[] pick(App a, String[] abis) {
        for (String abi : abis) for (String[] v : a.variants) if (v[0].equals(abi)) return new String[]{v[1], v[2]};
        for (String[] v : a.variants) if ("*".equals(v[0])) return new String[]{v[1], v[2]};
        if (a.apk != null && a.apk.startsWith("https://") && (a.sha256 != null || a.signer != null)) return new String[]{a.apk, a.sha256};
        return null;
    }

    static final int INSTALL = 0, INSTALLED = 1, UPDATE = 2;

    /**
     * What the catalogue's button says. installedCode -1: not installed. With a version code in the catalogue it
     * decides; otherwise the version names are compared (Updater.newer). A store app is never "Aggiorna": the store
     * updates it. Plain Java, tested.
     */
    static int state(App a, long installedCode, String installedName) {
        if (installedCode < 0) return INSTALL;
        if (a.store()) return INSTALLED;
        if (a.code > 0) return a.code > installedCode ? UPDATE : INSTALLED;
        if (a.version != null && installedName != null && Updater.newer(a.version, installedName)) return UPDATE;
        return INSTALLED;
    }

    /** The installed version code, or -1. */
    static long installedCode(Context c, String pkg) {
        try {
            return versionCode(c.getPackageManager().getPackageInfo(pkg, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** The version code on every Android this app runs on (getLongVersionCode is Android 9+). */
    @SuppressWarnings("deprecation")
    static long versionCode(PackageInfo pi) {
        return android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    /** The store page of a "store" app: Amazon's Appstore on Fire OS, Google Play elsewhere. */
    static String storeUri(App a, boolean fireOs) {
        return fireOs ? "amzn://apps/android?p=" + a.pkg : "market://details?id=" + a.pkg;
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(Locale.ROOT).replace(":", "");
    }

    /** The catalogue from GitHub, or the bundled copy. Blocking. */
    static List<App> load(Context c) {
        String bundled = DnsVpnService.raw(c, R.raw.catalogo);
        try {
            String json = get(URL);
            List<App> online = parse(json);
            // The online list wins, unless this build ships a newer format than the one published (before a release).
            if (!online.isEmpty() && format(json) >= format(bundled)) return online;
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "online catalogue unavailable, bundled copy: " + e.getMessage());
        }
        return parse(bundled);
    }

    /** The catalogue's "versione" (its format), 1 when missing. Plain Java, tested. */
    static int format(String json) {
        try {
            Object root = MiniJson.parse(json);
            return root instanceof java.util.Map ? (int) MiniJson.number(root, "versione", 1) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The installed version of a package, or null. */
    static String installed(Context c, String pkg) {
        try {
            return c.getPackageManager().getPackageInfo(pkg, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Downloads the app's APK into the cache and checks it: package name, SHA-256 (when listed) and signing
     * certificate (when listed). Returns the checked file; throws with a short reason otherwise.
     */
    @SuppressWarnings("deprecation")
    static File download(Context c, App a) throws IOException {
        String[] chosen = pick(a, android.os.Build.SUPPORTED_ABIS);
        if (chosen == null) throw new IOException("nessun APK per questo dispositivo");
        String url = chosen[0], expected = chosen[1];
        File apk = new File(c.getCacheDir(), "catalogo-" + a.pkg + ".apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 non disponibile");
        }
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            long total = 0;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 200L << 20) throw new IOException("file troppo grande");
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
        } finally {
            conn.disconnect();
        }
        String sha = hex(md.digest());
        if (expected != null && !expected.equals(sha)) throw fail(apk, "SHA-256 diverso dal catalogo");
        PackageInfo info = c.getPackageManager().getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
        if (info == null || !a.pkg.equals(info.packageName)) throw fail(apk, "non è il pacchetto atteso");
        if (a.signer != null && !a.signer.equals(signer(info.signatures))) throw fail(apk, "firma diversa dal catalogo");
        return apk;
    }

    private static IOException fail(File f, String why) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return new IOException(why);
    }

    /** SHA-256 of the first signing certificate, lower-case hex (as apksigner prints it). */
    static String signer(Signature[] s) {
        if (s == null || s.length == 0) return null;
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(s[0].toByteArray()));
        } catch (java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "TVLiberata/" + BuildConfig.VERSION_NAME);
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() > 1_000_000) throw new IOException("catalogo troppo grande");
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
