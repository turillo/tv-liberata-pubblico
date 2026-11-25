package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Impostazioni TV Liberata, Temi: the style of every settings page (moving the focus over a style redraws this page
 * in it at once; OK keeps it; leaving without OK goes back to the style before), the main colour (a palette, or a
 * custom one: hue with left/right, brightness with up/down), the menus' background, and "Ripristina tema predefinito".
 */
public final class SyntaxThemeActivity extends Activity {

    /** The style the focus is on after a live redraw; -1: the saved one. */
    private static int focusStyle = -1;
    private boolean redrawing;
    private boolean ready;
    private final TextView[] styleChips = new TextView[Theme.STYLES];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout outer = new FrameLayout(this);
        LinearLayout column = SyntaxUi.scrollPage(this, outer);
        column.setClipChildren(false);
        SyntaxUi.header(this, column, getString(R.string.theme_title), getString(R.string.theme_intro));

        // Style: a plain row (not one of the style's own lists), so the focus can move over the five and preview them.
        column.addView(SyntaxUi.label(this, getString(R.string.theme_style)));
        LinearLayout styles = new LinearLayout(this);
        styles.setOrientation(LinearLayout.HORIZONTAL);
        styles.setClipChildren(false);
        styles.setClipToPadding(false);
        String[] names = getResources().getStringArray(R.array.theme_styles);
        int shown = Theme.style(this);
        for (int i = 0; i < Theme.STYLES; i++) {
            int style = i;
            TextView chip = SyntaxUi.chip(this, names[i]);
            chip.setSelected(i == Theme.savedStyle(this));
            View.OnFocusChangeListener zoom = chip.getOnFocusChangeListener();
            chip.setOnFocusChangeListener((v, has) -> {
                if (zoom != null) zoom.onFocusChange(v, has);
                if (has && ready && style != Theme.style(this)) preview(style);
            });
            chip.setOnClickListener(v -> {
                Theme.setStyle(this, style);
                focusStyle = style;
                redraw();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(SyntaxUi.dp(this, 14));
            styles.addView(chip, lp);
            styleChips[i] = chip;
        }
        column.addView(styles);
        TextView hint = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        hint.setText(Theme.previewing() ? R.string.theme_preview_hint : R.string.theme_style_hint);
        hint.setPadding(0, SyntaxUi.dp(this, 10), 0, 0);
        column.addView(hint);

        // Main colour.
        column.addView(SyntaxUi.label(this, getString(R.string.theme_color)));
        LinearLayout colors = SyntaxUi.chipRow(this);
        String[] colorNames = getResources().getStringArray(R.array.theme_colors);
        int current = Theme.color(this);
        TextView[] colorChips = new TextView[Theme.PALETTE.length + 1];
        boolean custom = true;
        for (int i = 0; i < Theme.PALETTE.length; i++) {
            int color = Theme.PALETTE[i];
            TextView chip = swatch(SyntaxUi.chip(this, colorNames[i]), color);
            chip.setSelected(color == current);
            custom &= color != current;
            chip.setOnClickListener(v -> {
                Theme.setColor(this, color);
                redraw();
            });
            SyntaxUi.addChip(colors, chip);
            colorChips[i] = chip;
        }
        TextView customChip = swatch(SyntaxUi.chip(this, getString(R.string.theme_custom)), custom ? current : 0x00000000);
        customChip.setSelected(custom);
        customChip.setOnClickListener(v -> pickCustom());
        SyntaxUi.addChip(colors, customChip);
        colorChips[Theme.PALETTE.length] = customChip;
        SyntaxUi.group(getString(R.string.theme_color), colorChips);
        column.addView(colors);

        // Menus' background.
        column.addView(SyntaxUi.label(this, getString(R.string.theme_backdrop)));
        LinearLayout backs = SyntaxUi.chipRow(this);
        String[] backNames = getResources().getStringArray(R.array.theme_backdrops);
        TextView[] backChips = new TextView[Theme.BACKDROPS];
        for (int i = 0; i < Theme.BACKDROPS; i++) {
            int b = i;
            backChips[i] = SyntaxUi.chip(this, backNames[i]);
            backChips[i].setSelected(i == Theme.backdrop(this));
            backChips[i].setOnClickListener(v -> {
                Theme.setBackdrop(this, b);
                if (b == Theme.BG_BLUR || b == Theme.BG_PHOTO) MenuBackdrop.prepareAsync(this);
                redraw();
            });
            SyntaxUi.addChip(backs, backChips[i]);
        }
        SyntaxUi.group(getString(R.string.theme_backdrop), backChips);
        column.addView(backs);

        LinearLayout resetRow = SyntaxUi.chipRow(this);
        resetRow.setPadding(0, SyntaxUi.dp(this, 26), 0, 0);
        TextView reset = SyntaxUi.chip(this, getString(R.string.theme_reset));
        reset.setOnClickListener(v -> SyntaxDialog.show(this, getString(R.string.theme_reset),
                getString(R.string.theme_reset_question), new String[]{getString(R.string.syntax_yes), getString(R.string.syntax_no)},
                1, which -> {
                    if (which != 0) return;
                    Theme.reset(this);
                    focusStyle = -1;
                    redraw();
                }));
        SyntaxUi.addChip(resetRow, reset);
        column.addView(resetRow);

        setContentView(outer);
        int focus = focusStyle >= 0 ? focusStyle : shown;
        styleChips[Math.max(0, Math.min(Theme.STYLES - 1, focus))].requestFocus();
        // Focus changes from here on are the user's: only now does a new style redraw the page.
        outer.post(() -> ready = true);
    }

    /** A colour chip: a dot of the colour before the name (none for an empty custom colour). */
    private TextView swatch(TextView chip, int color) {
        if ((color >>> 24) == 0) return chip;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setStroke(SyntaxUi.dp(this, 1), 0x66FFFFFF);
        int s = SyntaxUi.dp(this, 16);
        dot.setBounds(0, 0, s, s);
        chip.setCompoundDrawablesRelative(dot, null, null, null);
        chip.setCompoundDrawablePadding(SyntaxUi.dp(this, 10));
        return chip;
    }

    /** The live preview: the page drawn again in that style, the focus back on it; not saved until OK. */
    private void preview(int style) {
        Theme.preview(style);
        focusStyle = style;
        redraw();
    }

    private void redraw() {
        redrawing = true;
        recreate();
    }

    @Override
    protected void onDestroy() {
        // Leaving the page without OK: the style before comes back.
        if (!redrawing) {
            Theme.endPreview();
            focusStyle = -1;
        }
        super.onDestroy();
    }

    /** "Personalizzato": hue with left/right, brightness with up/down, OK keeps it, Indietro leaves it. */
    private void pickCustom() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SyntaxUi.rounded(this, 24, 0xF2121722, 1, 0x33FFFFFF));
        int pad = SyntaxUi.dp(this, 28);
        card.setPadding(pad, pad, pad, pad);
        TextView title = SyntaxUi.text(this, 22, R.string.font_medium, SyntaxUi.TEXT);
        title.setText(R.string.theme_custom);
        card.addView(title);
        TextView help = SyntaxUi.text(this, 14, R.string.font_light, SyntaxUi.MUTED);
        help.setText(R.string.theme_custom_hint);
        help.setPadding(0, SyntaxUi.dp(this, 8), 0, SyntaxUi.dp(this, 18));
        card.addView(help);
        HuePicker picker = new HuePicker(this, Theme.color(this), color -> {
            Theme.setColor(this, color);
            d.dismiss();
            redraw();
        });
        card.addView(picker, new LinearLayout.LayoutParams(SyntaxUi.dp(this, 520), SyntaxUi.dp(this, 120)));
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.6f);
        }
        d.show();
        picker.requestFocus();
    }

    /** The colour's name in the palette, or "personalizzato". */
    static String colorName(Context c, int color) {
        String[] names = c.getResources().getStringArray(R.array.theme_colors);
        for (int i = 0; i < Theme.PALETTE.length && i < names.length; i++) {
            if (Theme.PALETTE[i] == color) return names[i].toLowerCase(java.util.Locale.ITALIAN);
        }
        return c.getString(R.string.theme_custom).toLowerCase(java.util.Locale.ITALIAN);
    }

    /** The custom colour: a bar of hues with a marker, and the chosen colour's swatch at its brightness. */
    static final class HuePicker extends View {
        interface Pick {
            void picked(int color);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Pick onPick;
        private float hue, value;

        HuePicker(Context c, int start, Pick onPick) {
            super(c);
            this.onPick = onPick;
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(start, hsv);
            hue = hsv[0];
            value = Math.max(0.35f, hsv[2]);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        int color() {
            return Theme.fromHue(hue, value);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT: hue = (hue + 350) % 360; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: hue = (hue + 10) % 360; invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_UP: value = Math.min(1f, value + 0.1f); invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: value = Math.max(0.35f, value - 0.1f); invalidate(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    onPick.picked(color());
                    return true;
                default:
                    return super.onKeyDown(keyCode, event);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float barH = h * 0.32f, r = barH / 2;
            int[] hues = new int[13];
            for (int i = 0; i < hues.length; i++) hues[i] = Theme.fromHue(i * 30f, value);
            paint.setShader(new LinearGradient(0, 0, w, 0, hues, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(0, 0, w, barH), r, r, paint);
            paint.setShader(null);
            float x = hue / 360f * w;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(SyntaxUi.dp(getContext(), 3));
            paint.setColor(0xFFFFFFFF);
            canvas.drawRoundRect(new RectF(x - r * 0.5f, -2, x + r * 0.5f, barH + 2), r * 0.4f, r * 0.4f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color());
            float top = barH + h * 0.16f;
            canvas.drawRoundRect(new RectF(0, top, w, h), SyntaxUi.dp(getContext(), 14), SyntaxUi.dp(getContext(), 14), paint);
            paint.setColor(Theme.onColor(color()));
            paint.setTextSize(SyntaxUi.dp(getContext(), 16));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.format("#%06X", color() & 0xFFFFFF), w / 2, top + (h - top) / 2 + SyntaxUi.dp(getContext(), 6), paint);
        }
    }
}
