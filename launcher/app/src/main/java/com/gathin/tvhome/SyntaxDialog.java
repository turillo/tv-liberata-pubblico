package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Syntax TV's small dialogs in its own style: a dark glass card with a question and a row of choices, picked with
 * the D-pad and OK; Back closes it without choosing.
 */
final class SyntaxDialog {

    interface Choice {
        void picked(int index);
    }

    private SyntaxDialog() {
    }

    /**
     * Shows {@code title} (and {@code message}, may be null) with the choices; {@code focus} is the one focused
     * first, so a "No" can be the safe default.
     */
    static Dialog show(Activity a, String title, String message, String[] choices, int focus, Choice onPick) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(SyntaxUi.rounded(a, 24, 0xF2121722, 1, 0x33FFFFFF));
        int pad = SyntaxUi.dp(a, 32);
        card.setPadding(pad, pad, pad, SyntaxUi.dp(a, 28));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        TextView t = SyntaxUi.text(a, 24, R.string.font_medium, SyntaxUi.TEXT);
        t.setText(title);
        card.addView(t);
        if (message != null) {
            TextView m = SyntaxUi.text(a, 15, R.string.font_light, SyntaxUi.MUTED);
            m.setText(message);
            m.setPadding(0, SyntaxUi.dp(a, 10), 0, 0);
            m.setMaxWidth(SyntaxUi.dp(a, 520));
            card.addView(m);
        }
        LinearLayout row = SyntaxUi.chipRow(a);
        row.setPadding(0, SyntaxUi.dp(a, 24), 0, 0);
        TextView[] chips = new TextView[choices.length];
        for (int i = 0; i < choices.length; i++) {
            int index = i;
            chips[i] = SyntaxUi.chip(a, choices[i]);
            chips[i].setOnClickListener(v -> {
                d.dismiss();
                onPick.picked(index);
            });
            SyntaxUi.addChip(row, chips[i]);
        }
        card.addView(row);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
            w.setDimAmount(0.6f);
        }
        d.show();
        if (focus >= 0 && focus < chips.length) chips[focus].requestFocus();
        return d;
    }

    /** A short message with one OK. */
    static void message(Activity a, String title, String message) {
        show(a, title, message, new String[]{a.getString(R.string.syntax_ok)}, 0, i -> { });
    }
}
