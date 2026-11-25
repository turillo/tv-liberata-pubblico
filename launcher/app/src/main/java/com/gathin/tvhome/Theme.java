package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * TV Liberata's theme for its settings pages (1.0.8): one style for every page (balloons, segmented, list, text,
 * tiles), one main colour (the selected option, switches, underlines, the focused tile), and the menus' background
 * (blurred photo, dark glow, sharp photo darkened on the left, logo watermark). The "TV Liberata" tile on Home and
 * the logo stay orange whatever the colour. Saved in the "theme" preferences; the Temi page can preview a style
 * (live, until confirmed) without saving it.
 */
final class Theme {

    // 1.0.9: the tiles style (4) is gone; a TV that had it goes back to balloons (clampStyle).
    static final int BALLOONS = 0, SEGMENTED = 1, LIST = 2, TEXT = 3;
    static final int STYLES = 4;
    static final int BG_BLUR = 0, BG_GLOW = 1, BG_PHOTO = 2, BG_LOGO = 3;
    static final int BACKDROPS = 4;

    /** The logo's orange: the default main colour, and always the TV Liberata tile's. */
    static final int ORANGE = 0xFFFF7A45;
    /** Arancio, Rosso, Azzurro (the colour before 1.0.8), Verde, Viola, Giallo, Bianco. */
    static final int[] PALETTE = {ORANGE, 0xFFFF4D5E, 0xFF5CE1E6, 0xFF4CD787, 0xFFA77BFF, 0xFFFFD23F, 0xFFF2F4F7};

    private static final String PREFS = "theme", STYLE = "style", COLOR = "color", BACKDROP = "backdrop";
    /** The Temi page's live preview of a style; -1: none. Never saved. */
    private static volatile int previewStyle = -1;

    private Theme() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int style(Context c) {
        int p = previewStyle;
        if (p >= 0) return p;
        return clampStyle(prefs(c).getInt(STYLE, BALLOONS));
    }

    static int savedStyle(Context c) {
        return clampStyle(prefs(c).getInt(STYLE, BALLOONS));
    }

    static int color(Context c) {
        return prefs(c).getInt(COLOR, ORANGE) | 0xFF000000;
    }

    static int backdrop(Context c) {
        int b = prefs(c).getInt(BACKDROP, BG_BLUR);
        return b >= 0 && b < BACKDROPS ? b : BG_BLUR;
    }

    static void setStyle(Context c, int style) {
        prefs(c).edit().putInt(STYLE, clampStyle(style)).apply();
        previewStyle = -1;
    }

    static void setColor(Context c, int color) {
        prefs(c).edit().putInt(COLOR, color | 0xFF000000).apply();
    }

    static void setBackdrop(Context c, int backdrop) {
        prefs(c).edit().putInt(BACKDROP, backdrop >= 0 && backdrop < BACKDROPS ? backdrop : BG_BLUR).apply();
    }

    /** "Ripristina tema predefinito": balloons, orange, blurred photo. */
    static void reset(Context c) {
        prefs(c).edit().clear().apply();
        previewStyle = -1;
    }

    static void preview(int style) {
        previewStyle = clampStyle(style);
    }

    static void endPreview() {
        previewStyle = -1;
    }

    static boolean previewing() {
        return previewStyle >= 0;
    }

    static int clampStyle(int s) {
        return s >= 0 && s < STYLES ? s : BALLOONS;
    }

    // ---- colour helpers (plain Java, tested) -------------------------------------------------------------------

    static double luminance(int c) {
        return (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
    }

    /** Text drawn on the colour: black on a light one, white on a dark one. */
    static int onColor(int c) {
        return luminance(c) > 0.6 ? 0xFF101418 : 0xFFFFFFFF;
    }

    /** Darker (amount < 0) or lighter (amount > 0). */
    static int shade(int c, float amount) {
        int target = amount >= 0 ? 255 : 0;
        float t = Math.abs(amount);
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return 0xFF000000 | (Math.round(r + (target - r) * t) << 16) | (Math.round(g + (target - g) * t) << 8)
                | Math.round(b + (target - b) * t);
    }

    static int withAlpha(int c, int alpha) {
        return (alpha << 24) | (c & 0xFFFFFF);
    }

    /** Hue 0..360 and value 0..1 (full saturation) to a colour: the custom colour picker. */
    static int fromHue(float hue, float value) {
        float h = ((hue % 360) + 360) % 360 / 60f, v = Math.max(0f, Math.min(1f, value));
        int i = (int) Math.floor(h);
        float f = h - i, p = 0, q = v * (1 - f), t = v * f;
        float r, g, b;
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    /**
     * From the PC (tests, screenshots): only the adb shell can send it (WRITE_SECURE_SETTINGS).
     * am broadcast -f 0x20 -n com.gathin.tvhome.liberata/com.gathin.tvhome.Theme$Receiver -a com.gathin.tvhome.THEME
     * --ei style 1 --es color FF7A45 --ei backdrop 0   (--ez reset true: the default theme)
     */
    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Context c = context.getApplicationContext();
            if (intent.getBooleanExtra("reset", false)) reset(c);
            if (intent.hasExtra("style")) setStyle(c, intent.getIntExtra("style", BALLOONS));
            if (intent.hasExtra("backdrop")) setBackdrop(c, intent.getIntExtra("backdrop", BG_BLUR));
            String color = intent.getStringExtra("color");
            if (color != null) {
                try {
                    setColor(c, (int) Long.parseLong(color.replace("#", ""), 16));
                } catch (NumberFormatException ignored) {
                    // left as it was
                }
            }
            setResultData("stile " + savedStyle(c) + ", colore " + Integer.toHexString(color(c)) + ", sfondo " + backdrop(c));
        }
    }
}
