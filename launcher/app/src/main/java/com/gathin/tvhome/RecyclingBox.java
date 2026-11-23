package com.gathin.tvhome;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The recycling box, on the kitchen panel and on Home: what Recycling says for now, from Syntax TV's calendar.
 * "Stasera esponi: Carta, Organico" from the afternoon, lit up while the bins should go out; "Ritira i contenitori
 * entro le 9" the morning after. The panel also says the next evening when there is nothing to do, and where glass
 * goes; Home shows the box only when there is something to do.
 */
final class RecyclingBox {
    private static final int PLAIN = 0x33FFFFFF, GLASS = 0x8C0B1018, LIT = 0xF2FFC14D, LIT_TEXT = 0xFF1C1300;

    final LinearLayout view;
    private final Context context;
    private final TextView title, body, note;
    private final boolean home;
    private final float scale;

    /** {@code home}: Home's smaller box over the wallpaper, shown only when there is something to do. */
    RecyclingBox(Context c, boolean home) {
        context = c;
        this.home = home;
        scale = home ? 0.62f : 0.88f;
        view = new LinearLayout(c);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setFocusable(false);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setPadding(dp(22), dp(14), dp(22), dp(16));
        title = SyntaxUi.text(c, 24 * scale, R.string.font_medium, SyntaxUi.TEXT);
        view.addView(title);
        body = SyntaxUi.text(c, 19 * scale, R.string.font_light, SyntaxUi.MUTED);
        body.setPadding(0, dp(6), 0, 0);
        view.addView(body);
        note = SyntaxUi.text(c, 16 * scale, R.string.font_light, SyntaxUi.MUTED);
        note.setPadding(0, dp(8), 0, 0);
        view.addView(note);
    }

    /** Fills the box for now; false (and the box gone) when Home has nothing to show. */
    boolean update(SyntaxSettings s) {
        Calendar c = Calendar.getInstance();
        int day = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1;   // ISO: Monday 1 ... Sunday 7
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        List<List<String>> week = Recycling.parse(s.recyclingWeek());
        int from = s.recyclingFrom(), to = s.recyclingTo(), by = s.recyclingBy();
        Recycling.Status st = Recycling.at(week, day, minute, from, to, by);
        boolean lit = st.kind == Recycling.Kind.OUT_NOW;
        boolean show = true;
        switch (st.kind) {
            case TAKE_IN:
                title.setText(context.getString(R.string.recycling_take_in, hhmm(by)));
                body.setText(names(st.bins));
                break;
            case TONIGHT:
            case OUT_NOW:
                title.setText(context.getString(R.string.recycling_tonight, names(st.bins)));
                body.setText(lit ? context.getString(R.string.recycling_now, hhmm(to))
                        : context.getString(R.string.recycling_hours, hhmm(from), hhmm(to)));
                break;
            case NEXT:
                show = !home;
                title.setText(R.string.kitchen_recycling);
                String when = st.day == day ? context.getString(R.string.recycling_this_evening)
                        : context.getResources().getStringArray(R.array.recycling_days)[st.day - 1];
                body.setText(context.getString(R.string.recycling_next, when, names(st.bins)));
                break;
            default:
                show = !home;
                title.setText(R.string.kitchen_recycling);
                body.setText(R.string.kitchen_recycling_empty);
                break;
        }
        boolean glass = !home && Recycling.glassToBells(week) && st.kind != Recycling.Kind.NONE;
        note.setText(glass ? context.getString(R.string.recycling_glass) : "");
        note.setVisibility(glass ? View.VISIBLE : View.GONE);
        view.setBackground(SyntaxUi.rounded(context, 18 * scale, lit ? LIT : home ? GLASS : PLAIN, 0, 0));
        title.setTextColor(lit ? LIT_TEXT : SyntaxUi.TEXT);
        body.setTextColor(lit ? LIT_TEXT : SyntaxUi.MUTED);
        note.setTextColor(lit ? LIT_TEXT : SyntaxUi.MUTED);
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        return show;
    }

    private String names(List<String> bins) {
        String[] labels = context.getResources().getStringArray(R.array.recycling_bins);
        List<String> out = new ArrayList<>();
        for (String b : bins) {
            int i = Recycling.BINS.indexOf(b);
            if (i >= 0 && i < labels.length) out.add(labels[i]);
        }
        return android.text.TextUtils.join(", ", out);
    }

    static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%d:%02d", minutes / 60, minutes % 60);
    }

    private int dp(float v) {
        return SyntaxUi.dp(context, v * scale);
    }
}
