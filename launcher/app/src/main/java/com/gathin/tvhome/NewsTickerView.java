package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.List;

/**
 * The screensaver's news ticker: a dark band across the bottom with a fixed "ANSA · ULTIM'ORA" label and the
 * headlines, each after its time, sliding right to left at a constant speed. The text is measured once per list;
 * each frame only works out the offset from the clock and draws the few headlines in view, and only this band
 * is redrawn. A new list replaces the old one at the end of a full pass, so nothing jumps.
 */
final class NewsTickerView extends View {

    private static final int BAND = 0xD9060A12;
    private static final int ACCENT = 0xFFFF5A3C;

    private final float dp;
    private final float speedPx;   // px per second
    private final float fraction;  // the band's height as a fraction of the screen height
    private final Paint band = new Paint();
    private final Paint line = new Paint();
    private final Paint labelBg = new Paint();
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String label;

    private String[] times = new String[0], titles = new String[0];
    private float[] timeW = new float[0], itemW = new float[0];
    private float total;
    private String[][] pending;
    private long start;
    /** When the band stopped, so it carries on from where it was instead of starting over. */
    private long pausedAt;
    private boolean running;
    private float labelW;
    /** ScreenMargin: the text keeps clear of the sides and the band grows by the bottom margin (overscan). */
    private int insetX, insetBottom;

    NewsTickerView(Context context, String label, int speedDp, float fraction) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        speedPx = speedDp * dp;
        this.fraction = fraction;
        this.label = label;
        band.setColor(BAND);
        line.setColor(0x26FFFFFF);
        labelBg.setColor(0xF0060A12);
        labelText.setColor(ACCENT);
        labelText.setTypeface(UiFont.get(context, R.string.font_medium));
        labelText.setLetterSpacing(0.1f);
        timeText.setColor(ACCENT);
        timeText.setTypeface(UiFont.get(context, R.string.font_medium));
        titleText.setColor(0xF2FFFFFF);
        titleText.setTypeface(UiFont.get(context, R.string.font_regular));
        dotPaint.setColor(0x80FFFFFF);
        for (Paint p : new Paint[]{labelText, timeText, titleText}) {
            p.setSubpixelText(true);
            p.setLinearText(true);
        }
    }

    /** The band's height in px: its fraction (5, 6 or 7.5%) of the screen height. */
    int bandHeight() {
        return Math.round(getResources().getDisplayMetrics().heightPixels * fraction) + insetBottom;
    }

    /** The screen margin: the band still fills the width and reaches the bottom, its text {@code x} px in from the
     * sides and {@code bottom} px up. Set before the band is laid out. */
    void setInsets(int x, int bottom) {
        insetX = Math.max(0, x);
        insetBottom = Math.max(0, bottom);
        requestLayout();
        invalidate();
    }

    /** The bottom margin bandHeight includes. */
    int insetBottom() {
        return insetBottom;
    }

    /** Shows {@code headlines} as {time, title} pairs; the first time at once, later at the end of a pass. */
    void setHeadlines(List<String[]> headlines) {
        String[][] next = headlines.toArray(new String[0][]);
        if (titles.length == 0) apply(next);
        else pending = next;
    }

    private void apply(String[][] items) {
        int n = items.length;
        times = new String[n];
        titles = new String[n];
        timeW = new float[n];
        itemW = new float[n];
        total = 0;
        float gap = gap();
        for (int i = 0; i < n; i++) {
            times[i] = items[i][0];
            titles[i] = items[i][1];
            timeW[i] = timeText.measureText(times[i]);
            itemW[i] = timeW[i] + gap * 0.6f + titleText.measureText(titles[i]) + gap * 2;
            total += itemW[i];
        }
        pending = null;
        start = SystemClock.uptimeMillis();
    }

    private float gap() {
        return getHeight() > 0 ? getHeight() * 0.5f : 24 * dp;
    }

    void startScrolling() {
        long now = SystemClock.uptimeMillis();
        if (pausedAt > 0) start += now - pausedAt;   // carry on from the same place
        else if (!running) start = now;
        pausedAt = 0;
        running = true;
        postInvalidateOnAnimation();
    }

    /** Restarts the frame loop after the first headlines arrive (it stops while there is nothing to scroll). */
    void startScrollingIfStopped() {
        if (running) postInvalidateOnAnimation();
    }

    void stopScrolling() {
        if (running && total > 0) pausedAt = SystemClock.uptimeMillis();
        running = false;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        h = Math.max(1, h - insetBottom);
        float size = h * 0.42f;
        labelText.setTextSize(size * 0.82f);
        timeText.setTextSize(size);
        titleText.setTextSize(size);
        labelW = labelText.measureText(label) + h * 0.9f;
        if (titles.length > 0) {
            String[][] items = new String[titles.length][];
            for (int i = 0; i < titles.length; i++) items[i] = new String[]{times[i], titles[i]};
            apply(items);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int full = getWidth();
        canvas.drawRect(0, 0, full, getHeight(), band);
        canvas.drawRect(0, 0, full, Math.max(1, dp), line);
        // The text's own box: in from the sides and above the bottom by the screen margin.
        int ix = Math.min(insetX, full / 4);
        int w = full - ix, h = Math.max(1, getHeight() - insetBottom);
        canvas.save();
        canvas.translate(ix, 0);
        w -= ix;
        float base = h / 2f - (titleText.descent() + titleText.ascent()) / 2;
        if (total > 0) {
            long now = SystemClock.uptimeMillis();
            float offset = (now - start) / 1000f * speedPx;
            if (offset >= total) {
                // A full pass: take a waiting list now, so it starts from its first headline.
                if (pending != null) {
                    apply(pending);
                    offset = 0;
                } else {
                    start += (long) (total / speedPx * 1000);
                    offset -= total;
                }
            }
            canvas.save();
            canvas.clipRect(labelW, 0, w, h);
            float x = w - offset;   // the first headline enters from the right edge
            float gap = gap();
            int i = 0;
            float limit = w;
            // Skip what has already left on the left, then draw until past the right edge.
            while (x + itemW[i] < labelW && total > 0) {
                x += itemW[i];
                i = (i + 1) % itemW.length;
            }
            while (x < limit) {
                canvas.drawText(times[i], x, base, timeText);
                canvas.drawText(titles[i], x + timeW[i] + gap * 0.6f, base, titleText);
                float end = x + itemW[i];
                canvas.drawCircle(end - gap, h / 2f, h * 0.05f, dotPaint);
                x = end;
                i = (i + 1) % itemW.length;
                // The list repeats seamlessly; when a new one waits, leave the space after the last headline empty.
                if (i == 0 && pending != null) break;
            }
            canvas.restore();
        }
        canvas.drawRect(-ix, 0, labelW, h + insetBottom, labelBg);
        canvas.drawRect(-ix, 0, labelW, Math.max(1, dp), line);
        float lb = h / 2f - (labelText.descent() + labelText.ascent()) / 2;
        canvas.drawText(label, h * 0.45f, lb, labelText);
        canvas.drawRect(labelW - Math.max(1, dp) * 2, h * 0.22f, labelW, h * 0.78f, labelText);
        canvas.restore();
        if (running && total > 0) postInvalidateOnAnimation();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }
}
