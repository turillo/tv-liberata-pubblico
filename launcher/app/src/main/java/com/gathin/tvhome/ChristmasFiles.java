package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;

/**
 * What is left of the fireplace scene (taken out on 11 November 2026): its 14 MB video in files/xmas/ and any
 * partial download. {@link #removeOnce} deletes them the first time the new version starts and logs the space freed.
 * Never throws.
 */
final class ChristmasFiles {

    private static final String TAG = "TvHome.Christmas";
    private static final String DONE = "fireplace_removed";

    private ChristmasFiles() {
    }

    /** files/xmas/, where the video and its .part were. */
    static File folder(Context c) {
        return new File(c.getFilesDir(), "xmas");
    }

    /** Once per install: deletes the fireplace's files and logs what that freed. Blocking: off the main thread. */
    static long removeOnce(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences("christmas_files", Context.MODE_PRIVATE);
            if (p.getBoolean(DONE, false)) return 0;
            File dir = folder(c);
            long before = SystemProbe.size(dir);
            delete(dir, 0);
            long freed = before - SystemProbe.size(dir);
            p.edit().putBoolean(DONE, true).apply();
            Log.i(TAG, "fireplace video removed: " + freed + " bytes freed (" + SystemStats.bytes(freed) + ")");
            return freed;
        } catch (RuntimeException e) {
            Log.w(TAG, "fireplace files not removed: " + e.getMessage());
            return 0;
        }
    }

    private static void delete(File f, int depth) {
        if (f == null || depth > 4 || !f.exists()) return;
        File[] list = f.isDirectory() ? f.listFiles() : null;
        if (list != null) for (File x : list) delete(x, depth + 1);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
