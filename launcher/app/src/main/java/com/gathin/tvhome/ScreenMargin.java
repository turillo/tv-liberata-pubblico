package com.gathin.tvhome;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * Syntax TV's "Margini schermo": a TV that crops its picture (overscan, as the kitchen's Thomson) hides what sits
 * near the edges, so Home, the kitchen panel, the news band and Syntax TV keep this share of the screen clear on
 * each side. A Fire TV starts at {@link #FIRE_TV} %, any other TV at 0, so the Sony looks as before.
 */
final class ScreenMargin {
    static final int FIRE_TV = 5;

    private ScreenMargin() {
    }

    static int percent(Context c) {
        if (!Device.fireExtras()) return 0;   // the TVs show their whole picture
        try {
            return SyntaxPrefs.get(c).screenMargin(Device.isFireOs() ? FIRE_TV : 0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The side margin in px for {@code percent}. */
    static int x(Context c, int percent) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.round(Math.max(m.widthPixels, m.heightPixels) * percent / 100f);
    }

    /** The top and bottom margin in px for {@code percent}. */
    static int y(Context c, int percent) {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        return Math.round(Math.min(m.widthPixels, m.heightPixels) * percent / 100f);
    }

    /**
     * Pads {@code v} with the margin on top of its own padding. The first call remembers that padding, so calling
     * again with another percent replaces the margin instead of adding to it.
     */
    static void pad(View v, int percent) {
        Object tag = v.getTag(R.id.screen_margin_base);
        int[] base;
        if (tag instanceof int[]) {
            base = (int[]) tag;
        } else {
            base = new int[]{v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom()};
            v.setTag(R.id.screen_margin_base, base);
        }
        int x = x(v.getContext(), percent), y = y(v.getContext(), percent);
        v.setPadding(base[0] + x, base[1] + y, base[2] + x, base[3] + y);
    }
}
