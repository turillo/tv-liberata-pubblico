package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The Christmas scenes' small panel, in the style of the other scenes: a dark glass card in the bottom-left corner
 * (above the news ticker), a title, a line under it and a few short lines. Redrawn only when its text changes.
 */
final class XmasPanel extends View {

    private final float dp;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private String title = "", sub = "";
    private String[] lines = new String[0];

    XmasPanel(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        card.setColor(0xCC0B0608);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp);
        edge.setColor(0x44FFD27A);
        titlePaint.setColor(0xF2FFFFFF);
        titlePaint.setTextSize(20 * sp);
        titlePaint.setLetterSpacing(0.08f);
        titlePaint.setTypeface(UiFont.get(c, R.string.font_medium));
        subPaint.setColor(0xFFFFD27A);
        subPaint.setTextSize(11 * sp);
        subPaint.setLetterSpacing(0.12f);
        subPaint.setTypeface(UiFont.get(c, R.string.font_regular));
        linePaint.setColor(0xD9FFFFFF);
        linePaint.setTextSize(15 * sp);
        linePaint.setTypeface(UiFont.get(c, R.string.font_light));
    }

    void set(String title, String sub, String... lines) {
        this.title = title == null ? "" : title;
        this.sub = sub == null ? "" : sub;
        this.lines = lines == null ? new String[0] : lines;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (title.isEmpty()) return;
        float pad = 18 * dp, w = titlePaint.measureText(title);
        w = Math.max(w, subPaint.measureText(sub));
        for (String l : lines) w = Math.max(w, linePaint.measureText(l));
        w += 2 * pad;
        float h = pad + 20 * dp + 18 * dp + lines.length * 24 * dp + pad;
        float left = getWidth() * 0.04f, bottom = getHeight() - DreamInset.bottom - getHeight() * 0.05f;
        box.set(left, bottom - h, left + w, bottom);
        canvas.drawRoundRect(box, 16 * dp, 16 * dp, card);
        canvas.drawRoundRect(box, 16 * dp, 16 * dp, edge);
        float x = box.left + pad, y = box.top + pad + 17 * dp;
        canvas.drawText(title, x, y, titlePaint);
        y += 18 * dp;
        canvas.drawText(sub, x, y, subPaint);
        for (String l : lines) {
            y += 24 * dp;
            canvas.drawText(l, x, y, linePaint);
        }
    }
}
