package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Once, on a TV that used the Home Assistant archive (13 November 2026, removed since): the photos it copied from HA
 * carry HA's ids (bing-…, wmc-…, uns-…). They get back the names the TV's own downloads give them (OHR.…, WMC.…,
 * UNS.…), with their favourite, on/off, title, credit and blacklist, so Bing, Wikimedia and Unsplash do not download
 * them again; photos HA got elsewhere (up-…) keep their id. Then HA's files, token and settings are deleted and the
 * three sources are ticked. Nothing happens on a TV without files/ha_archive.json. Never throws.
 */
final class HaLeftovers {

    private static final String TAG = "TvHome.HaLeftovers";

    private HaLeftovers() {
    }

    /**
     * The TV's own name for a photo HA had: Bing's from its address, Unsplash's from the photo id, Commons' from the
     * title; null when the TV would not have named it (an upload, or not enough to go on).
     */
    static String localId(String id, String source, String originalUrl, String title) {
        return localId(id, source, originalUrl, title, null);
    }

    /** With HA's file name: photos moved from the TV to HA kept the TV's name there (OHR.…, WMC.…, UNS.….jpg). */
    static String localId(String id, String source, String originalUrl, String title, String fileName) {
        if (id == null) return null;
        if (fileName != null && fileName.matches("(OHR|WMC|UNS)\\.[A-Za-z0-9._-]+\\.jpg")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        if (id.startsWith("bing-") && originalUrl != null && originalUrl.contains("id=")) {
            // As WallpaperPlayer.bingId (which the JVM tests cannot load): OHR.Name_IT-IT123 from ?id=…_1920x1080.jpg
            int start = originalUrl.indexOf("id=");
            int end = originalUrl.indexOf('&', start);
            String n = originalUrl.substring(start + 3, end < 0 ? originalUrl.length() : end)
                    .replaceAll("\\.jpg$", "").replaceAll("_(UHD|\\d+x\\d+)$", "");
            return n.startsWith("OHR.") && n.matches("[A-Za-z0-9._-]+") ? n : null;
        }
        if (id.startsWith("uns-") && id.length() > 4) return "UNS." + WallpaperSources.safe(id.substring(4));
        if (id.startsWith("wmc-") && title != null && !title.isEmpty()) {
            String name = WallpaperSources.safe(title);
            return name.isEmpty() ? null : "WMC." + name;
        }
        return null;
    }

    /** HA id to the TV's own name, from the TV's copy of HA's index (files/ha_archive.json). */
    static Map<String, String> renames(String archiveJson) {
        Map<String, String> out = new LinkedHashMap<>();
        List<?> files = MiniJson.list(MiniJson.parse(archiveJson), "file");
        if (files == null) return out;
        for (Object o : files) {
            String id = MiniJson.str(o, "id");
            String to = localId(id, MiniJson.str(o, "sorgente"), MiniJson.str(o, "url_originale"), MiniJson.str(o, "titolo"),
                    MiniJson.str(o, "nome_file"));
            if (to != null && !to.equals(id)) out.put(id, to);
        }
        return out;
    }

    /** One state's ids renamed: favourites, off, blacklist, titles, credits. */
    static void rename(WallpaperState s, Map<String, String> renames) {
        for (Map.Entry<String, String> r : renames.entrySet()) {
            String from = r.getKey(), to = r.getValue();
            if (s.favorites.remove(from)) s.favorites.add(to);
            if (s.disabled.remove(from)) s.disabled.add(to);
            if (s.blacklist.remove(from)) s.blacklist.add(to);
            String t = s.titles.remove(from);
            if (t != null) s.titles.put(to, t);
            String c = s.credits.remove(from);
            if (c != null) s.credits.put(to, c);
        }
    }

    /** True when it cleaned up (the TV had used HA), false when there was nothing to do. */
    static boolean run(Context context) {
        Context c = context.getApplicationContext();
        File index = new File(c.getFilesDir(), "ha_archive.json");
        // Or HA's index pushed over adb next to the photos (getExternalFilesDir/ha_archive.json), for a second pass.
        File pushed = new File(c.getExternalFilesDir(null), "ha_archive.json");
        if (!index.isFile() && pushed.isFile()) index = pushed;
        try {
            if (!index.isFile()) return false;
            Map<String, String> renames = renames(read(index));
            int moved = 0;
            for (boolean xmas : new boolean[]{false, true}) {
                File dir = WallpaperLibrary.archive(c, xmas);
                if (dir == null) continue;
                for (Map.Entry<String, String> r : renames.entrySet()) {
                    File from = new File(dir, r.getKey() + ".jpg"), to = new File(dir, r.getValue() + ".jpg");
                    if (!from.isFile()) continue;
                    long when = from.lastModified();
                    if (to.exists()) {
                        // The same photo under the TV's own name already (downloaded again since): one copy is enough.
                        //noinspection ResultOfMethodCallIgnored
                        from.delete();
                        //noinspection ResultOfMethodCallIgnored
                        WallpaperLibrary.thumbFile(c, r.getKey()).delete();
                        moved++;
                    } else if (from.renameTo(to)) {
                        //noinspection ResultOfMethodCallIgnored
                        to.setLastModified(when);
                        //noinspection ResultOfMethodCallIgnored
                        WallpaperLibrary.thumbFile(c, r.getKey()).delete();
                        moved++;
                    }
                }
                WallpaperLibrary.update(c, xmas, s -> rename(s, renames));
            }
            SyntaxSettings s = SyntaxPrefs.get(c);
            for (String source : WallpaperSources.ALL) s.setSource(source, true);
            //noinspection ResultOfMethodCallIgnored
            pushed.delete();
            for (String name : new String[]{"ha_archive.json", "ha_queue.json", "ha_archive.json.tmp", "ha_queue.json.tmp"}) {
                //noinspection ResultOfMethodCallIgnored
                new File(c.getFilesDir(), name).delete();
            }
            c.deleteSharedPreferences("ha_archive");   // the token and HA's address
            c.getSharedPreferences(SyntaxPrefs.NAME, Context.MODE_PRIVATE).edit().remove("archive_mode").apply();
            c.getSharedPreferences("service_status", Context.MODE_PRIVATE).edit()
                    .remove("ha_ok").remove("ha_err").remove("ha_since").remove("ha_msg").apply();
            File backup = new File(c.getExternalFilesDir(null), "backup-stato");
            File[] old = backup.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
            Log.i(TAG, "Home Assistant left behind: " + moved + " photos renamed, token and files deleted");
            return true;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Home Assistant leftovers not cleaned: " + e.getMessage());
            return false;
        }
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
