package com.gathin.tvhome;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.os.storage.StorageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The Sistema page's cleanup: Syntax TV's own regenerable files, the wallpaper archive, and the other apps by the
 * space they take. Blocking; call off the main thread. Never throws.
 */
final class SystemCleanup {

    private SystemCleanup() {
    }

    /**
     * Deletes what Syntax TV can download or make again: the cache folder (wallpaper thumbnails), the scenes' image
     * caches and the news cache. Never the wallpaper archives, favourites, settings or the ISS orbit. Returns the
     * bytes freed.
     */
    static long clearCache(Context c) {
        List<File> targets = new ArrayList<>();
        File[] cache = c.getCacheDir().listFiles();
        if (cache != null) for (File f : cache) targets.add(f);
        for (String d : SystemProbe.SCENE_DIRS) targets.add(new File(c.getFilesDir(), d));
        targets.add(new File(c.getFilesDir(), "news.txt"));
        long before = 0;
        for (File f : targets) before += SystemProbe.size(f);
        for (File f : targets) delete(f, 0);
        long after = 0;
        for (File f : targets) after += SystemProbe.size(f);
        return Math.max(0, before - after);
    }

    private static void delete(File f, int depth) {
        if (f == null || depth > 8 || !f.exists()) return;
        try {
            File[] list = f.isDirectory() ? f.listFiles() : null;
            if (list != null) for (File x : list) delete(x, depth + 1);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (SecurityException ignored) {
            // left as it is
        }
    }

    /** Deletes these photos of the general archive: blacklisted ({@code forever}) or just removed. Returns bytes freed. */
    static long deletePhotos(Context c, List<String> ids, boolean forever) {
        File dir = WallpaperLibrary.archive(c, false);
        long freed = 0;
        for (String id : ids) {
            File photo = dir == null ? null : new File(dir, id + ".jpg");
            long size = photo == null ? 0 : photo.length();
            try {
                if (forever) WallpaperLibrary.delete(c, false, id);
                else WallpaperLibrary.remove(c, false, id);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (photo != null && !photo.exists()) freed += size;
        }
        return freed;
    }

    /** An installed app and the space it takes. */
    static final class App {
        final String pkg, label;
        final long total, cache;

        App(String pkg, String label, long total, long cache) {
            this.pkg = pkg;
            this.label = label;
            this.total = total;
            this.cache = cache;
        }
    }

    /** Thrown inside {@link #apps}: Android refused the sizes of other apps (usage access not granted). */
    static final class NoAccess extends Exception {
        NoAccess() {
            super("usage access");
        }
    }

    /**
     * Installed apps with a launcher entry or that are not system apps, biggest first (code + data + cache).
     * Needs usage access (adb: appops set com.gathin.tvhome GET_USAGE_STATS allow) for every app but this one.
     */
    static List<App> apps(Context c) throws NoAccess {
        List<App> out = new ArrayList<>();
        StorageStatsManager ssm = c.getSystemService(StorageStatsManager.class);
        PackageManager pm = c.getPackageManager();
        if (ssm == null) return out;
        boolean refused = false;
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean user = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0
                    || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            boolean launchable = pm.getLeanbackLaunchIntentForPackage(ai.packageName) != null
                    || pm.getLaunchIntentForPackage(ai.packageName) != null;
            if (!user && !launchable) continue;
            try {
                StorageStats s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle());
                out.add(new App(ai.packageName, String.valueOf(ai.loadLabel(pm)),
                        s.getAppBytes() + s.getDataBytes() + s.getCacheBytes(), s.getCacheBytes()));
            } catch (SecurityException e) {
                refused = true;
            } catch (Exception ignored) {
                // an app that went away meanwhile, or no stats for it
            }
        }
        if (refused && out.size() <= 1) throw new NoAccess();
        out.sort((a, b) -> Long.compare(b.total, a.total));
        return out;
    }
}
