package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.util.Arrays;
import java.util.List;

/**
 * Fire OS's own "Le mie app" (Your Apps & Channels) and "Cerca app" (Amazon's home with its search), as two tiles of
 * TV Liberata's apps row on Fire OS. They live inside Amazon's home screen, which FireHomeGuard normally replaces at
 * once: opening them gives the guard a pass until HOME is pressed. Each tile has a pseudo package name, so it can be
 * hidden, shown again and moved like any app; nothing of Amazon's is ever disabled for them.
 */
final class FireShortcuts {

    static final String MY_APPS = "tvliberata.myapps", SEARCH = "tvliberata.search";
    static final List<String> ALL = Arrays.asList(MY_APPS, SEARCH);

    private FireShortcuts() {
    }

    static boolean is(String pkg) {
        return pkg != null && pkg.startsWith("tvliberata.");
    }

    static ComponentName component(String pkg) {
        return new ComponentName(pkg, pkg + ".Tile");
    }

    static String label(Context c, String pkg) {
        return c.getString(MY_APPS.equals(pkg) ? R.string.fire_my_apps : R.string.fire_search);
    }

    /** Opens the Amazon screen behind the tile; false when Fire OS has no such screen. */
    static boolean open(Context c, String pkg) {
        // Fire OS 7 keeps "Le tue app" and the search inside Amazon's home (its search deep link is for the system
        // only, and ACTION_ALL_APPS opens nothing): both tiles open Amazon's home, where they are the first rows.
        Intent i = new Intent(Intent.ACTION_MAIN).setClassName(FireHomeGuard.AMAZON, "com.amazon.tv.launcher.ui.HomeActivity_vNext");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (c.getPackageManager().resolveActivity(i, 0) == null) return false;   // not Fire OS
        FireHomeGuard.pass();
        try {
            c.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A plain banner: the tile's name on a dark card with a thin accent line. */
    static Drawable banner(Context c, String pkg) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF141821);
        cv.drawRoundRect(new RectF(0, 0, w, h), 18, 18, bg);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(SyntaxUi.ACCENT);
        cv.drawRect(28, h - 46, 76, h - 42, line);
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setColor(0xFFFFFFFF);
        t.setTextSize(34);
        try {
            t.setTypeface(UiFont.get(c, R.string.font_medium));
        } catch (RuntimeException e) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        cv.drawText(label(c, pkg), 28, h / 2f + 6, t);
        return new BitmapDrawable(c.getResources(), b);
    }
}
