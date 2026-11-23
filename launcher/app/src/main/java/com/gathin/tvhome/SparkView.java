package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * A small line chart for the Sistema page: values 0..1, oldest on the left, a slot for every sample the history
 * can hold (so it fills from the right as time goes on). One path, no allocation per frame beyond it.
 */
final class SparkView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint();
    private final Path path = new Path();
    private float[] values = new float[0];
    private int slots = 60;

    SparkView(Context context) {
        super(context);
        float dp = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * dp);
        line.setColor(SyntaxUi.ACCENT);
        fill.setColor(0x335CE1E6);
        grid.setColor(0x22FFFFFF);
        grid.setStrokeWidth(dp);
    }

    void set(float[] values, int slots) {
        this.values = values;
        this.slots = Math.max(2, slots);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        canvas.drawLine(0, h / 2f, w, h / 2f, grid);
        canvas.drawLine(0, h - 1, w, h - 1, grid);
        if (values.length < 2) return;
        float step = (float) w / (slots - 1);
        float x0 = w - (values.length - 1) * step;
        path.reset();
        for (int i = 0; i < values.length; i++) {
            float v = Math.max(0f, Math.min(1f, values[i]));
            float x = x0 + i * step, y = h - v * (h - 2) - 1;
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, line);
        path.lineTo(w, h);
        path.lineTo(x0, h);
        path.close();
        canvas.drawPath(path, fill);
    }
}
