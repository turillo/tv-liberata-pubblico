package com.gathin.tvhome;

/**
 * What this build offers (Features) and its first-start theme. Since 1.0 there is one TV Liberata for every device.
 * The liberata keeps Home, wallpapers, app management, the screensaver scenes, Christmas, weather, the ANSA ticker,
 * screen margins, the power button and Sistema on every device; it leaves out the profiles, the kitchen panel,
 * the recycling calendar, Il Post and Unsplash, and has none of the owner's data (config.xml in src/liberata).
 */
final class Edition {


    private Edition() {
    }

    /**
     * TV Liberata's first start: its look (online wallpapers, weather header, glass dock) comes from a
     * theme file, written next to Home's other files when there is none yet. A theme pushed later replaces it.
     */
    static void seedTheme(android.content.Context c) {
        java.io.File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        java.io.File theme = new java.io.File(dir, Config.FILE_NAME);
        if (theme.exists()) return;
        try (java.io.InputStream in = c.getResources().openRawResource(R.raw.liberata_theme);
             java.io.OutputStream out = new java.io.FileOutputStream(theme)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.IOException | RuntimeException e) {
            android.util.Log.w("TvHome.Edition", "default theme not written: " + e.getMessage());
        }
    }

    /** Profiles, apps row position, kitchen panel and recycling: the owner's Fire TVs only. */
    static boolean profiles() {
        return Features.PROFILES && Device.isFireOs();
    }

    /** Il Post in the news ticker. */
    static boolean ilPost() {
        return Features.NEWS_TICKER;
    }

    /** Unsplash among the wallpaper sources. */
    static boolean unsplash() {
        return Features.UNSPLASH;
    }
}
