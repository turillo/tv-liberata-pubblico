package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Migration from the pre-1.0 app (com.gathin.tvhome) to TV Liberata 1.0, from the PC over adb: the old app's
 * private files and settings, exported by its bridge build and pushed to this app's import-migrazione/ folder
 * (files/ and shared_prefs/ as Android keeps them), are taken over: the wallpaper states (favourites, blacklist,
 * titles), the hidden apps and their order, and the compatible settings. The photos themselves are copied by the PC
 * into this app's folders. Only the adb shell can call it (WRITE_SECURE_SETTINGS); the folder is deleted afterwards.
 * <pre>am broadcast -n com.gathin.tvhome.liberata/com.gathin.tvhome.ImportReceiver -a com.gathin.tvhome.IMPORT</pre>
 */
public final class ImportReceiver extends BroadcastReceiver {

    private static final String TAG = "TvHome.Import";
    static final List<String> FILES = Arrays.asList("wallpaper_state.json", "xmas_wallpaper_state.json",
            "library_home_removed.txt", "library_apps.txt", Order.FILE_NAME);
    /** Settings files taken over; caches and per-device status are left behind. */
    static final List<String> PREFS = Arrays.asList(SyntaxPrefs.NAME, Config.SETTINGS_NAME, WallpaperPlayer.PREFS,
            "wallpaper_sources");

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        File in = new File(c.getExternalFilesDir(null), "import-migrazione");
        if (!in.isDirectory()) {
            setResultData("cartella import-migrazione mancante");
            return;
        }
        int files = 0, keys = 0;
        for (String name : FILES) {
            File f = new File(new File(in, "files"), name);
            if (f.isFile() && copy(f, new File(c.getFilesDir(), name))) files++;
        }
        for (String name : PREFS) {
            File f = new File(new File(in, "shared_prefs"), name + ".xml");
            if (f.isFile()) keys += importPrefs(f, c.getSharedPreferences(name, Context.MODE_PRIVATE));
        }
        delete(in);
        Setup.markDone(c);   // migrated from the old app: the TV is already set up
        Log.i(TAG, "migration: " + files + " files, " + keys + " settings");
        setResultData("importati " + files + " file e " + keys + " impostazioni");
    }

    /** One SharedPreferences XML file into {@code into}: strings, booleans, ints, longs, floats, string sets. */
    static int importPrefs(File xml, SharedPreferences into) {
        SharedPreferences.Editor e = into.edit();
        int n = 0;
        try (InputStream s = new FileInputStream(xml)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(s, "UTF-8");
            String setName = null;
            Set<String> set = null;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String tag = p.getName(), name = p.getAttributeValue(null, "name"), value = p.getAttributeValue(null, "value");
                    if ("set".equals(tag)) {
                        setName = name;
                        set = new HashSet<>();
                    } else if ("string".equals(tag)) {
                        String text = p.nextText();
                        if (set != null) set.add(text);
                        else if (name != null) {
                            e.putString(name, text);
                            n++;
                        }
                    } else if (name != null && value != null) {
                        switch (tag) {
                            case "boolean": e.putBoolean(name, Boolean.parseBoolean(value)); n++; break;
                            case "int": e.putInt(name, Integer.parseInt(value)); n++; break;
                            case "long": e.putLong(name, Long.parseLong(value)); n++; break;
                            case "float": e.putFloat(name, Float.parseFloat(value)); n++; break;
                            default: break;
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && "set".equals(p.getName()) && setName != null) {
                    e.putStringSet(setName, set);
                    n++;
                    setName = null;
                    set = null;
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, xml.getName() + " not imported: " + ex.getMessage());
            return 0;
        }
        e.apply();
        return n;
    }

    private static boolean copy(File from, File to) {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void delete(File f) {
        File[] list = f.listFiles();
        if (list != null) for (File x : list) delete(x);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
