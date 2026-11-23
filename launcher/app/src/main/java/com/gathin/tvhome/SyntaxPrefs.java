package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Syntax TV's settings on the TV: SharedPreferences "syntax_settings", with config.xml (or the theme) as the defaults.
 * The wallpaper interval is also written to the launcher's own online_wallpaper_minutes, so Launcher settings and
 * Syntax TV show the same value.
 */
final class SyntaxPrefs {

    static final String NAME = "syntax_settings";

    private SyntaxPrefs() {
    }

    static SyntaxSettings get(Context context) {
        Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        SharedPreferences prefs = app.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        SharedPreferences launcher = app.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SyntaxSettings.Defaults defaults = new SyntaxSettings.Defaults(Config.dreamScene(app), Config.newsTicker(app),
                Config.newsTickerSpeed(app), launcher.getInt("online_wallpaper_minutes", 60));
        return new SyntaxSettings(new SyntaxSettings.Store() {
            @Override
            public String get(String key) {
                return prefs.getString(key, null);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
                if (SyntaxSettings.KEY_WALLPAPER.equals(key)) {
                    try {
                        launcher.edit().putInt("online_wallpaper_minutes", Integer.parseInt(value)).apply();
                    } catch (NumberFormatException ignored) {
                        // SyntaxSettings only writes numbers here
                    }
                }
            }
        }, defaults);
    }

    /** Launcher settings chose its own interval: Syntax TV follows it from now on. */
    static void forgetWallpaperMinutes(Context context) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().remove(SyntaxSettings.KEY_WALLPAPER).apply();
    }
}
