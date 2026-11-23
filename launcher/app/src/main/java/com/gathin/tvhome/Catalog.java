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

    /** One app of the catalogue. */
    static final class App {
        String id, name, description, pkg, version, apk, sha256, signer, source;
    }

    private Catalog() {
    }

    /** The catalogue's apps; entries without a package or an https APK address are left out. */
    static List<App> parse(String json) {
        List<App> out = new ArrayList<>();
        List<?> apps = MiniJson.list(MiniJson.parse(json), "app");
        if (apps == null) return out;
        for (Object o : apps) {
            App a = new App();
            a.id = MiniJson.str(o, "id");
            a.name = MiniJson.str(o, "nome");
            a.description = MiniJson.str(o, "descrizione");
            a.pkg = MiniJson.str(o, "pacchetto");
            a.version = MiniJson.str(o, "versione");
            a.apk = MiniJson.str(o, "apk");
            a.sha256 = lower(MiniJson.str(o, "sha256"));
            a.signer = lower(MiniJson.str(o, "firma"));
            a.source = MiniJson.str(o, "fonte");
            if (a.pkg == null || a.name == null || a.apk == null || !a.apk.startsWith("https://")) continue;
            if (a.sha256 == null && a.signer == null) continue;   // nothing to check it against: never installed
            out.add(a);
        }
        return out;
    }

    private static String lower(String s) {
        return s == null || s.isEmpty() ? null : s.toLowerCase(Locale.ROOT).replace(":", "");
    }

    /** The catalogue from GitHub, or the bundled copy. Blocking. */
    static List<App> load(Context c) {
        try {
            List<App> online = parse(get(URL));
            if (!online.isEmpty()) return online;
        } catch (IOException | RuntimeException e) {
            Log.i(TAG, "online catalogue unavailable, bundled copy: " + e.getMessage());
        }
        return parse(DnsVpnService.raw(c, R.raw.catalogo));
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
        File apk = new File(c.getCacheDir(), "catalogo-" + a.pkg + ".apk");
        HttpURLConnection conn = (HttpURLConnection) new URL(a.apk).openConnection();
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
        if (a.sha256 != null && !a.sha256.equals(sha)) throw fail(apk, "SHA-256 diverso dal catalogo");
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
