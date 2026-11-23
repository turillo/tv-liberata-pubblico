package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Light snow for the Christmas mode: a few flakes falling slowly, each with its own size, speed and sway. Positions
 * come from the clock, so a dropped frame never makes the snow jump; it redraws about 20 times a second while
 * running and not at all when stopped. Used over Home (in its own small window), over the screensaver and inside the
 * Christmas scenes.
 */
class SnowView extends View {

    private static final long FRAME_MS = 50;   // 20 frames a second: plenty for slow snow, light on the TV

    private final float dp;
    private final int count;
    private final float[] x, y, r, speed, sway, freq, phase;
    private final int[] alpha;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long start = SystemClock.uptimeMillis();
    private boolean running;
    private boolean snow = true;

    /** {@code count} flakes; {@code scale} makes them larger and faster (1 is Home's light snow). */
    SnowView(Context context, int count, float scale) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        // A small TV (Device.lite) draws fewer flakes; the rest look the same.
        this.count = Device.lite(context) ? Math.max(1, count * 3 / 5) : count;
        x = new float[this.count];
        y = new float[this.count];
        r = new float[this.count];
        speed = new float[this.count];
        sway = new float[this.count];
        freq = new float[this.count];
        phase = new float[this.count];
        alpha = new int[this.count];
        Random random = new Random(1225);
        for (int i = 0; i < this.count; i++) {
            float depth = random.nextFloat();   // far flakes are small, slow and faint
            x[i] = random.nextFloat();
            y[i] = random.nextFloat();
            r[i] = (1.2f + 2.6f * depth) * dp * scale;
            speed[i] = (14f + 34f * depth) * dp * scale;
            sway[i] = (4f + 12f * random.nextFloat()) * dp;
            freq[i] = 0.15f + 0.35f * random.nextFloat();
            phase[i] = random.nextFloat() * 6.283f;
            alpha[i] = Math.round(90 + 140 * depth);
        }
        paint.setColor(0xFFFFFFFF);
        setWillNotDraw(false);
    }

    void setSnow(boolean on) {
        snow = on;
        invalidate();
    }

    void start() {
        if (running) return;
        running = true;
        invalidate();
    }

    void stop() {
        running = false;
    }

    boolean running() {
        return running;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        if (snow) drawSnow(canvas, t);
        drawExtra(canvas, t);
        if (running) postInvalidateDelayed(FRAME_MS);
    }

    /** For subclasses: what else to draw this frame (the dock's lights). */
    void drawExtra(Canvas canvas, float seconds) {
    }

    private void drawSnow(Canvas canvas, float t) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        for (int i = 0; i < this.count; i++) {
            float span = h + 4 * r[i];
            float fy = (y[i] * span + speed[i] * t) % span - 2 * r[i];
            float fx = x[i] * w + sway[i] * (float) Math.sin(freq[i] * t * 6.283f + phase[i]);
            paint.setAlpha(alpha[i]);
            canvas.drawCircle(fx, fy, r[i], paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
