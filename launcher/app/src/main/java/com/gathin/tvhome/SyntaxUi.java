package com.gathin.tvhome;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Syntax TV's look, shared by its screens: the Ambient theme's near-black background, dark glass cards, Outfit
 * type, the scenes' cyan accent, and a focus that is obvious from the sofa (a white border and a small zoom).
 */
final class SyntaxUi {

    static final int BG = 0xFF07080D;
    static final int CARD = 0xFF141821;
    static final int CARD_EDGE = 0x22FFFFFF;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0x99FFFFFF;
    static final int ACCENT = 0xFF5CE1E6;
    static final int STAR = 0xFFFFC14D;
    static final int DANGER = 0xFFFF6B5C;

    private SyntaxUi() {
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A full-screen page: the dark background with a faint cyan glow, and a vertical column for the content. */
    static LinearLayout page(Context c, FrameLayout outer) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1018, BG, 0xFF060A10});
        outer.setBackground(bg);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(c, 64), dp(c, 34), dp(c, 64), dp(c, 24));
        ScreenMargin.pad(column, ScreenMargin.percent(c));   // inside what an overscanning TV crops
        outer.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return column;
    }

    /**
     * As {@link #page}, for a screen taller than the TV can show (a smaller screen, a wide margin): the column
     * scrolls, following the focus, so nothing at the bottom is cut. No weights in it: it is as tall as its rows.
     */
    static LinearLayout scrollPage(Context c, FrameLayout outer) {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1018, BG, 0xFF060A10});
        outer.setBackground(bg);
        android.widget.ScrollView scroll = new android.widget.ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(c, 64), dp(c, 34), dp(c, 64), dp(c, 24));
        ScreenMargin.pad(column, ScreenMargin.percent(c));
        column.setClipChildren(false);
        column.setClipToPadding(false);
        scroll.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        outer.addView(scroll, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return column;
    }

    static TextView text(Context c, float sp, int font, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        try {
            t.setTypeface(UiFont.get(c, font));
        } catch (RuntimeException ignored) {
            // the system font is fine
        }
        t.setIncludeFontPadding(false);
        return t;
    }

    /** The page title and the line under it. */
    static void header(Context c, LinearLayout column, String title, String subtitle) {
        TextView t = text(c, 34, R.string.font_medium, TEXT);
        t.setText(title);
        column.addView(t);
        if (subtitle != null) {
            TextView s = text(c, 15, R.string.font_light, MUTED);
            s.setText(subtitle);
            s.setPadding(0, dp(c, 8), 0, 0);
            column.addView(s);
        }
    }

    /** A card background: dark glass, and a white border when focused. */
    static StateListDrawable cardBackground(Context c, float radiusDp, int fill) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(c, radiusDp, fill, 3, 0xFFFFFFFF));
        states.addState(new int[]{android.R.attr.state_selected}, rounded(c, radiusDp, fill, 2, ACCENT));
        states.addState(new int[0], rounded(c, radiusDp, fill, 1, CARD_EDGE));
        return states;
    }

    static GradientDrawable rounded(Context c, float radiusDp, int fill, float strokeDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /** Makes {@code v} a remote target: focusable, with the card border and a small zoom while focused. */
    static void focusable(View v, float zoom) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? zoom : 1f).scaleY(has ? zoom : 1f).setDuration(150).start();
            view.setZ(has ? 4f : 0f);
        });
    }

    /** A pill button; "selected" shows a chosen option with the accent. */
    static TextView chip(Context c, String label) {
        TextView t = text(c, 16, R.string.font_regular, TEXT);
        t.setText(label);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
        t.setBackground(cardBackground(c, 24, CARD));
        focusable(t, 1.05f);
        return t;
    }

    /** A row for chips, with their spacing. */
    static LinearLayout chipRow(Context c) {
        LinearLayout row = new FitRow(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        return row;
    }

    static void addChip(LinearLayout row, TextView chip) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(row.getContext(), 14));
        row.addView(chip, lp);
    }

    /**
     * A row of chips that never runs off the screen: when its chips need more than the width it has (a denser
     * screen, a screen margin, a longer translation), their text, padding and gaps shrink together, down to 60 %.
     */
    static final class FitRow extends LinearLayout {
        private float applied = 1f;

        FitRow(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(widthSpec), avail = MeasureSpec.getSize(widthSpec);
            if (mode == MeasureSpec.UNSPECIFIED || avail <= 0) {
                super.onMeasure(widthSpec, heightSpec);
                return;
            }
            for (int pass = 0; pass < 3; pass++) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec);
                int need = getMeasuredWidth();
                if (need <= avail || applied <= 0.6f) break;
                float next = Math.max(0.6f, applied * avail / (float) need * 0.98f);
                scaleBy(next / applied);
                applied = next;
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        private void scaleBy(float f) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v instanceof TextView) {
                    TextView t = (TextView) v;
                    t.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.getTextSize() * f);
                }
                v.setPadding(Math.round(v.getPaddingLeft() * f), v.getPaddingTop(), Math.round(v.getPaddingRight() * f),
                        v.getPaddingBottom());
                if (v.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                    lp.setMarginStart(Math.round(lp.getMarginStart() * f));
                    lp.setMarginEnd(Math.round(lp.getMarginEnd() * f));
                    lp.leftMargin = Math.round(lp.leftMargin * f);
                    lp.rightMargin = Math.round(lp.rightMargin * f);
                    if (lp.width > 0) lp.width = Math.max(1, Math.round(lp.width * f));
                }
            }
        }
    }

    /** A small label above a group of options. */
    static TextView label(Context c, String text) {
        TextView t = text(c, 13, R.string.font_regular, MUTED);
        t.setText(text.toUpperCase(java.util.Locale.ITALIAN));
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(c, 20), 0, dp(c, 10));
        return t;
    }
}
