package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The look of TV Liberata's settings pages, in one place (1.0.8): the menus' background (Temi), dark glass cards,
 * Outfit type, a focus that is obvious from the sofa, and the controls in the chosen style (Theme): balloons (pills
 * with a border), segmented (a dark bar, the chosen option light grey, the focused one white), list (one row per
 * setting with its value and ">", OK opens the choice) or text only (words, the chosen one underlined; 1.0.9: no
 * tiles style any more). Pages build plain chips and say which belong together ({@link #group}) and which are yes/no
 * ({@link #toggle}); in the list style a row shows one item per setting, the chips staying underneath as
 * the model that the page updates and the item mirrors.
 */
final class SyntaxUi {

    static final int BG = 0xFF07080D;
    static final int CARD = 0xFF141821;
    static final int CARD_EDGE = 0x22FFFFFF;
    static final int TEXT = 0xFFFFFFFF;
    static final int MUTED = 0x99FFFFFF;
    /** The main colour (Temi), set when a page is built; orange by default. */
    static int ACCENT = Theme.ORANGE;
    static final int STAR = 0xFFFFC14D;
    static final int DANGER = 0xFFFF6B5C;
    /** The style (Theme), set when a page is built. */
    static int STYLE = Theme.BALLOONS;
    private static final int DARK_TEXT = 0xFF101418;
    private static final Object GROUP_LABEL = new Object(), HEADING = new Object();

    private SyntaxUi() {
    }

    /** Reads the theme: every page does at its start, so a change in Temi shows on the next page opened. */
    static void applyTheme(Context c) {
        ACCENT = Theme.color(c);
        STYLE = Theme.style(c);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** A full-screen page: the menus' background (Temi), and a vertical column for the content. */
    static LinearLayout page(Context c, FrameLayout outer) {
        applyTheme(c);
        MenuBackdrop.apply(outer);
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
        applyTheme(c);
        MenuBackdrop.apply(outer);
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
        style(t, sp, font, color);
        return t;
    }

    private static void style(TextView t, float sp, int font, int color) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        try {
            t.setTypeface(UiFont.get(t.getContext(), font));
        } catch (RuntimeException ignored) {
            // the system font is fine
        }
        t.setIncludeFontPadding(false);
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

    /** A card background: dark glass, a border in the main colour (thicker) when focused, thinner when selected. */
    static StateListDrawable cardBackground(Context c, float radiusDp, int fill) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(c, radiusDp, fill, 3, ACCENT));
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

    /** The focused tile's fill: the main colour fading to a darker shade. */
    static GradientDrawable accentFill(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{ACCENT, Theme.shade(ACCENT, -0.45f)});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** Makes {@code v} a remote target: focusable, with a small zoom (and a lift) while focused. */
    static void focusable(View v, float zoom) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? zoom : 1f).scaleY(has ? zoom : 1f).setDuration(150).start();
            view.setZ(has ? dp(view.getContext(), 8) : 0f);
        });
        // The zoomed card and its border must never be cut by the rows and lists around it.
        v.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                unclip(view);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
            }
        });
    }

    /** Lets {@code v} draw outside its parents (up to the page): for the focus zoom, its border and its shadow. */
    static void unclip(View v) {
        android.view.ViewParent p = v.getParent();
        while (p instanceof ViewGroup && ((ViewGroup) p).getId() != android.R.id.content) {
            ViewGroup g = (ViewGroup) p;
            // A scrolling list keeps its own edges (its rows must not slide over the page's header); its cells have
            // margins enough for the zoom.
            if (g instanceof android.widget.ScrollView || g instanceof androidx.recyclerview.widget.RecyclerView) return;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            p = g.getParent();
        }
    }

    // ---- chips --------------------------------------------------------------------------------------------

    /** A button or an option ("selected" marks the chosen one), drawn in the theme's style. */
    static TextView chip(Context c, String label) {
        Chip t = new Chip(c);
        style(t, 16, R.string.font_regular, TEXT);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.paint(STYLE);
        t.setText(label);
        return t;
    }

    /** These chips are one setting's options (one of them selected): one item in the list style. */
    static void group(TextView label, TextView... chips) {
        Object token = new Object();
        String name = label == null ? "" : label.getText().toString();
        if (label != null) label.setTag(GROUP_LABEL);
        for (TextView t : chips) {
            if (t instanceof Chip) {
                ((Chip) t).group = token;
                ((Chip) t).groupLabel = name;
            }
        }
    }

    /** As {@link #group(TextView, TextView...)}, named by a text (the setting's heading is elsewhere on the page). */
    static void group(String name, TextView... chips) {
        Object token = new Object();
        for (TextView t : chips) {
            if (t instanceof Chip) {
                ((Chip) t).group = token;
                ((Chip) t).groupLabel = name == null ? "" : name;
            }
        }
    }

    /** This chip is a yes/no switch ("selected" = on): a switch in the segmented style, an on/off switch in the list. */
    static void toggle(TextView chip) {
        if (!(chip instanceof Chip)) return;
        Chip t = (Chip) chip;
        t.toggle = true;
        t.paint(STYLE);
        t.setText(t.getText());   // drops the tick in the styles that show a switch
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
        lp.setMarginEnd(dp(row.getContext(), STYLE == Theme.SEGMENTED ? 3 : STYLE == Theme.TEXT ? 22 : 14));
        row.addView(chip, lp);
    }

    /**
     * Keeps a row of buttons as buttons in every style (no bar, no list): for a dashboard page whose panels must stay
     * on screen under its buttons (Sistema).
     */
    static void keepAsChips(LinearLayout row) {
        if (row instanceof FitRow) ((FitRow) row).keep = true;
    }

    /** A small label above a group of options. */
    static TextView label(Context c, String text) {
        TextView t = text(c, 13, R.string.font_regular, MUTED);
        t.setText(text.toUpperCase(java.util.Locale.ITALIAN));
        t.setTag(HEADING);
        t.setLetterSpacing(0.12f);
        t.setPadding(0, dp(c, 20), 0, dp(c, 10));
        return t;
    }

    /**
     * A chip: a TextView that knows its style, whether it is a switch, and the setting it belongs to. In the list and
     * list style its row may draw an item for it instead (its proxy): the chip then stays hidden, a focus request
     * goes to the item, and the page's visibility changes are passed on.
     */
    static final class Chip extends TextView {
        boolean toggle;
        Object group;
        String groupLabel = "";
        View proxy;
        boolean proxied;
        int wanted = VISIBLE;

        Chip(Context c) {
            super(c);
        }

        void paint(int style) {
            Context c = getContext();
            switch (style) {
                case Theme.SEGMENTED: {
                    StateListDrawable bg = new StateListDrawable();
                    bg.addState(new int[]{android.R.attr.state_focused}, rounded(c, 22, 0xFFFFFFFF, 0, 0));
                    bg.addState(new int[]{android.R.attr.state_selected}, rounded(c, 22, 0x4DFFFFFF, 0, 0));
                    bg.addState(new int[0], new ColorDrawable(0));
                    setBackground(bg);
                    setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                            new int[]{DARK_TEXT, TEXT}));
                    setPadding(dp(c, 20), dp(c, 11), dp(c, 20), dp(c, 11));
                    focusable(this, 1.06f);
                    if (toggle) {
                        StateListDrawable sw = new StateListDrawable();
                        sw.addState(new int[]{android.R.attr.state_selected}, new SwitchPill(c, true));
                        sw.addState(new int[0], new SwitchPill(c, false));
                        sw.setBounds(0, 0, dp(c, 36), dp(c, 20));
                        setCompoundDrawablesRelative(null, null, sw, null);
                        setCompoundDrawablePadding(dp(c, 12));
                    }
                    break;
                }
                case Theme.TEXT: {
                    StateListDrawable bg = new StateListDrawable();
                    bg.addState(new int[]{android.R.attr.state_focused}, underline(c, 0xFFFFFFFF));
                    bg.addState(new int[]{android.R.attr.state_selected}, underline(c, ACCENT));
                    bg.addState(new int[0], new ColorDrawable(0));
                    setBackground(bg);
                    setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused},
                            {android.R.attr.state_selected}, {}}, new int[]{TEXT, TEXT, 0xB3FFFFFF}));
                    setPadding(dp(c, 2), dp(c, 8), dp(c, 2), dp(c, 8));
                    focusable(this, 1.14f);
                    break;
                }
                default:   // balloons, and the chips under the list items
                    setBackground(cardBackground(c, 24, CARD));
                    setTextColor(TEXT);
                    setPadding(dp(c, 22), dp(c, 12), dp(c, 22), dp(c, 12));
                    focusable(this, 1.05f);
            }
        }

        /** The tick in front of a switch's name shows only in the balloons and text styles. */
        @Override
        public void setText(CharSequence text, BufferType type) {
            if (text != null && toggle && (STYLE == Theme.SEGMENTED || STYLE == Theme.LIST)
                    && text.toString().startsWith("✓ ")) {
                text = text.toString().substring(2);
            }
            super.setText(text, type);
        }

        @Override
        public void setVisibility(int visibility) {
            wanted = visibility;
            super.setVisibility(proxied ? GONE : visibility);
        }

        @Override
        public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
            if (proxied && proxy != null) return proxy.requestFocus();
            return super.requestFocus(direction, previouslyFocusedRect);
        }
    }

    /** A line under the text: the text style's chosen (main colour) and focused (white) options. */
    private static Drawable underline(Context c, int color) {
        GradientDrawable line = new GradientDrawable();
        line.setColor(color);
        LayerDrawable d = new LayerDrawable(new Drawable[]{line});
        d.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        d.setLayerHeight(0, dp(c, 3));
        return d;
    }

    /** A switch, on (the main colour, the knob on the right) or off (grey, the knob on the left). */
    static final class SwitchPill extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean on;
        private final int w, h;

        SwitchPill(Context c, boolean on) {
            this.on = on;
            w = dp(c, 36);
            h = dp(c, 20);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            RectF track = new RectF(b.left, b.top, b.left + w, b.top + h);
            paint.setColor(on ? ACCENT : 0x66FFFFFF);
            canvas.drawRoundRect(track, h / 2f, h / 2f, paint);
            paint.setColor(on ? Theme.onColor(ACCENT) : 0xFFFFFFFF);
            float r = h / 2f - h * 0.14f;
            canvas.drawCircle(on ? track.right - h / 2f : track.left + h / 2f, track.centerY(), r, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * A row of chips that never runs off the screen: when its chips need more than the width it has (a denser
     * screen, a screen margin, a longer translation), their text, padding and gaps shrink together, down to 60 %.
     * It also draws the style: the segmented bar, or the list items.
     */
    static final class FitRow extends LinearLayout {
        private float applied = 1f;
        private boolean styled, keep;
        private final List<Item> items = new ArrayList<>();
        private final ViewTreeObserver.OnPreDrawListener sync = () -> {
            syncItems();
            return true;
        };

        FitRow(Context c) {
            super(c);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!styled) {
                styled = true;
                if (keep) return;
                if (STYLE == Theme.LIST) buildItems();
                else if (STYLE == Theme.SEGMENTED) segmentBar();
            }
            if (!items.isEmpty()) getViewTreeObserver().addOnPreDrawListener(sync);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(sync);
            super.onDetachedFromWindow();
        }

        /** Segmented: the row's chips in one dark rounded bar, its labels in small capitals. */
        private void segmentBar() {
            boolean chips = false;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v instanceof Chip) chips = true;
                else if (v instanceof TextView && !(v instanceof android.widget.EditText)) {
                    TextView t = (TextView) v;
                    t.setAllCaps(true);
                    t.setLetterSpacing(0.1f);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                } else return;   // an input or a picture: left as the page made it
            }
            if (!chips) return;
            GradientDrawable bar = rounded(getContext(), 26, 0xD9141821, 1, CARD_EDGE);
            setBackground(bar);
            int p = dp(getContext(), 4);
            setPadding(p + dp(getContext(), 8), p, p, p);
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                setLayoutParams(lp);
            }
        }

        /** List: one item per setting, action or switch; a row with anything else is left as it is. */
        private void buildItems() {
            Map<Object, Item> groups = new LinkedHashMap<>();
            List<Item> found = new ArrayList<>();
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v instanceof Chip) {
                    Chip chip = (Chip) v;
                    if (chip.group != null) {
                        Item g = groups.get(chip.group);
                        if (g == null) {
                            g = new Item(Item.GROUP, chip.groupLabel);
                            groups.put(chip.group, g);
                            found.add(g);
                        }
                        g.chips.add(chip);
                    } else {
                        Item it = new Item(chip.toggle ? Item.SWITCH : Item.ACTION, "");
                        it.chips.add(chip);
                        found.add(it);
                    }
                } else if (v.getTag() == HEADING && STYLE == Theme.LIST) {
                    v.setVisibility(GONE);   // a heading inside a row would be an empty item in the list
                } else if (v.getTag() == GROUP_LABEL || v.getTag() == HEADING || v.getVisibility() == GONE) {
                    continue;   // a group's label (the item carries it), a heading, or nothing shown
                } else if (!(v instanceof TextView) && !(v instanceof ViewGroup) && !v.isFocusable()) {
                    v.setVisibility(GONE);   // a spacer: the list has its own rhythm
                    continue;
                } else {
                    return;   // a description, an input field or a picture: this row stays as the page made it
                }
            }
            if (found.isEmpty()) return;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v.getTag() == GROUP_LABEL) v.setVisibility(GONE);
            }
            setOrientation(VERTICAL);
            setBackground(rounded(getContext(), 18, 0xCC10141C, 1, CARD_EDGE));
            int p = dp(getContext(), 6);
            setPadding(p, p, p, p);
            for (Item it : found) {
                View view = it.listView(this);
                for (Chip c : it.chips) {
                    c.proxy = view;
                    c.proxied = true;
                    c.setVisibility(c.wanted);
                }
                items.add(it);
            }
            syncItems();
        }

        private void syncItems() {
            for (Item it : items) it.sync();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int mode = MeasureSpec.getMode(widthSpec), avail = MeasureSpec.getSize(widthSpec);
            if (getOrientation() == VERTICAL || mode == MeasureSpec.UNSPECIFIED || avail <= 0) {
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

    /** One setting drawn by a list row: a group of options, a switch, or a button. */
    static final class Item {
        static final int GROUP = 0, SWITCH = 1, ACTION = 2;
        final int kind;
        final String label;
        final List<Chip> chips = new ArrayList<>();
        View view;
        TextView name, value;

        Item(int kind, String label) {
            this.kind = kind;
            this.label = label;
        }

        /** List: the name on the left, the value and ">" on the right; the focused row white with dark text. */
        View listView(FitRow row) {
            Context c = row.getContext();
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
            StateListDrawable bg = new StateListDrawable();
            bg.addState(new int[]{android.R.attr.state_focused}, rounded(c, 12, 0xFFFFFFFF, 0, 0));
            bg.addState(new int[0], new ColorDrawable(0));
            r.setBackground(bg);
            r.setFocusable(true);
            r.setClickable(true);
            ColorStateList ink = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{DARK_TEXT, TEXT});
            ColorStateList soft = new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFF3A4150, 0xB3FFFFFF});
            name = text(c, 16, R.string.font_regular, TEXT);
            name.setTextColor(ink);
            name.setDuplicateParentStateEnabled(true);
            r.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            value = text(c, 15, R.string.font_regular, TEXT);
            value.setTextColor(kind == GROUP ? new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{DARK_TEXT, ACCENT}) : soft);
            value.setDuplicateParentStateEnabled(true);
            r.addView(value);
            if (kind == SWITCH) {
                View sw = new View(c);
                sw.setDuplicateParentStateEnabled(true);
                r.addView(sw, new LinearLayout.LayoutParams(dp(c, 36), dp(c, 20)));
                switchView = sw;
            }
            r.setOnClickListener(v -> press(row));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            row.addView(r, lp);
            view = r;
            return r;
        }

        View switchView;

        /** Mirrors the chips: the chosen option, on or off, visible or not. */
        void sync() {
            Chip first = chips.get(0);
            boolean shown = false, enabled = false;
            for (Chip c : chips) {
                shown |= c.wanted == View.VISIBLE;
                enabled |= c.isEnabled();
            }
            if (view.getVisibility() != (shown ? View.VISIBLE : View.GONE)) view.setVisibility(shown ? View.VISIBLE : View.GONE);
            view.setAlpha(enabled ? 1f : 0.4f);
            view.setFocusable(enabled);
            String n, v;
            Context c = view.getContext();
            switch (kind) {
                case GROUP: {
                    n = label;
                    Chip chosen = null;
                    for (Chip x : chips) if (x.isSelected()) chosen = x;
                    v = (chosen == null ? "" : chosen.getText().toString()) + (STYLE == Theme.LIST ? "  ›" : "");
                    break;
                }
                case SWITCH:
                    n = first.getText().toString();
                    v = STYLE == Theme.LIST ? "" : c.getString(first.isSelected() ? R.string.syntax_yes : R.string.syntax_no);
                    if (switchView != null) {
                        Drawable want = new SwitchPill(c, first.isSelected());
                        Object tag = switchView.getTag();
                        if (!Boolean.valueOf(first.isSelected()).equals(tag)) {
                            switchView.setBackground(want);
                            switchView.setTag(first.isSelected());
                        }
                    }
                    break;
                default:
                    n = STYLE == Theme.LIST ? first.getText().toString() : "";
                    v = STYLE == Theme.LIST ? "›" : first.getText().toString();
            }
            if (!n.contentEquals(name.getText())) name.setText(n);
            if (!v.contentEquals(value.getText())) value.setText(v);
            if (name.getVisibility() != (n.isEmpty() ? View.GONE : View.VISIBLE)) name.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        }

        /** OK: a button or a switch does what its chip does; a group opens the list of its options. */
        void press(FitRow row) {
            if (kind != GROUP) {
                chips.get(0).performClick();
                return;
            }
            Activity a = activity(row.getContext());
            List<Chip> shown = new ArrayList<>();
            for (Chip x : chips) if (x.wanted == View.VISIBLE && x.isEnabled()) shown.add(x);
            if (a == null || shown.isEmpty()) return;
            String[] names = new String[shown.size()];
            int focus = 0;
            for (int i = 0; i < names.length; i++) {
                names[i] = shown.get(i).getText().toString();
                if (shown.get(i).isSelected()) focus = i;
            }
            SyntaxDialog.show(a, label.isEmpty() ? names[focus] : label, null, names, focus, which -> shown.get(which).performClick());
        }
    }

    static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
