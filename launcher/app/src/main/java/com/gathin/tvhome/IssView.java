package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.Log;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * The ISS screensaver scene's one view: an equirectangular world map from Natural Earth (drawn once into a
 * small RGB_565 bitmap), the night side as a 192x96 shade stretched over it, the ground track, the ISS with a
 * glow, a dot for home (no name), and a panel in a corner. IssScene feeds it every few seconds; nothing animates in
 * between except the panel's fade when it changes corner. No blur, no per-frame work.
 */
final class IssView extends View {

    private static final int OCEAN = 0xFF060A12;
    private static final int GRID = 0xFF0C1320;
    private static final int LAND = 0xFF1A2436;
    private static final int COAST = 0xFF2B3955;
    private static final int ACCENT = 0xFF5CE1E6;
    private static final int HOME = 0xFFE0661B;
    private static final int NIGHT_W = 192, NIGHT_H = 96;
    /** The base map bitmap is at most this wide; it is drawn stretched to the map's size on screen. */
    private static final int MAX_BASE_W = 1280;

    private final float dp;
    private final RectF map = new RectF();
    private Bitmap base;
    private final Bitmap night = Bitmap.createBitmap(NIGHT_W, NIGHT_H, Bitmap.Config.ARGB_8888);
    private final int[] nightPixels = new int[NIGHT_W * NIGHT_H];
    private boolean hasNight;
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint pastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint futurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint homePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint homeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float haloRadius;

    private final Path past = new Path();
    private final Path future = new Path();
    private float[] track;   // lon, lat pairs
    private int trackNow;    // index of the pair closest to now
    private boolean hasIss;
    private double issLat, issLon;
    private boolean hasHome;
    private double homeLat, homeLon;

    private String time = "";
    private String subtitle = "";
    private String[] labels = new String[0];
    private String[] values = new String[0];
    private String status = "";

    /** Whole-map drift against burn-in, in px. */
    private float driftX, driftY;
    private int corner;
    private float panelNudgeX, panelNudgeY;
    private float panelAlpha = 1f;
    private ValueAnimator fade;
    private final RectF panel = new RectF();
    private final RectF dst = new RectF();

    IssView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        setBackgroundColor(Color.BLACK);

        pastPaint.setStyle(Paint.Style.STROKE);
        pastPaint.setStrokeWidth(2 * dp);
        pastPaint.setStrokeCap(Paint.Cap.ROUND);
        pastPaint.setStrokeJoin(Paint.Join.ROUND);
        pastPaint.setColor(ACCENT);
        pastPaint.setAlpha(80);
        futurePaint.set(pastPaint);
        futurePaint.setAlpha(170);
        futurePaint.setPathEffect(new DashPathEffect(new float[]{6 * dp, 7 * dp}, 0));

        haloRadius = 46 * dp;
        haloPaint.setShader(new RadialGradient(0, 0, haloRadius,
                new int[]{0x885CE1E6, 0x335CE1E6, 0x0C5CE1E6, 0x005CE1E6},
                new float[]{0f, 0.25f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        dotPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(2 * dp);
        ringPaint.setColor(ACCENT);

        homePaint.setColor(HOME);
        homeRingPaint.setStyle(Paint.Style.STROKE);
        homeRingPaint.setStrokeWidth(1.5f * dp);
        homeRingPaint.setColor(HOME);
        homeRingPaint.setAlpha(110);

        panelPaint.setColor(0xD905080F);
        panelBorder.setStyle(Paint.Style.STROKE);
        panelBorder.setStrokeWidth(dp);
        panelBorder.setColor(0x1FFFFFFF);
        titleText.setColor(0xE6FFFFFF);
        titleText.setTextSize(26 * sp);
        titleText.setTypeface(UiFont.get(context, R.string.font_medium));
        titleText.setLetterSpacing(0.08f);
        timeText.setColor(0xE6FFFFFF);
        timeText.setTextSize(26 * sp);
        timeText.setTypeface(UiFont.get(context, R.string.font_extralight));
        timeText.setTextAlign(Paint.Align.RIGHT);
        smallText.setColor(0x8CFFFFFF);
        smallText.setTextSize(12 * sp);
        smallText.setTypeface(UiFont.get(context, R.string.font_regular));
        smallText.setLetterSpacing(0.12f);
        labelText.set(smallText);
        labelText.setTextSize(14 * sp);
        labelText.setLetterSpacing(0.02f);
        valueText.setColor(0xE6FFFFFF);
        valueText.setTextSize(20 * sp);
        valueText.setTypeface(UiFont.get(context, R.string.font_light));
        valueText.setTextAlign(Paint.Align.RIGHT);
    }

    /** The panel's title ("ISS"). */
    private String title = "ISS";

    /** Another scene on the same map (Santa's journey): its title, and its colour for the marker and the track. */
    void setLook(String title, int accent) {
        this.title = title;
        pastPaint.setColor(accent);
        pastPaint.setAlpha(80);
        futurePaint.setColor(accent);
        futurePaint.setAlpha(170);
        ringPaint.setColor(accent);
        int c = accent & 0x00FFFFFF;
        haloPaint.setShader(new RadialGradient(0, 0, haloRadius, new int[]{0x88000000 | c, 0x33000000 | c, 0x0C000000 | c, c},
                new float[]{0f, 0.25f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        invalidate();
    }

    // ---- data from IssScene -----------------------------------------------------------------------------

    void setHome(double lat, double lon) {
        hasHome = true;
        homeLat = lat;
        homeLon = lon;
    }

    void setIss(double lat, double lon) {
        hasIss = true;
        issLat = lat;
        issLon = lon;
    }

    /** The ground track as lon, lat pairs; {@code now} is the index of the pair at the present moment. */
    void setTrack(float[] lonLat, int now) {
        track = lonLat;
        trackNow = now;
        buildTrack();
    }

    void setPanel(String time, String subtitle, String[] labels, String[] values, String status) {
        this.time = time;
        this.subtitle = subtitle;
        this.labels = labels;
        this.values = values;
        this.status = status;
        invalidate();
    }

    /** Shades the night side for the sun overhead at (sunLat, sunLon): full night once the sun is 12 degrees down. */
    void setNight(double sunLat, double sunLon) {
        double sd = Math.toRadians(sunLat);
        double sinD = Math.sin(sd), cosD = Math.cos(sd);
        double[] cosH = new double[NIGHT_W];
        for (int x = 0; x < NIGHT_W; x++) {
            double lon = -180 + (x + 0.5) * 360.0 / NIGHT_W;
            cosH[x] = Math.cos(Math.toRadians(lon - sunLon));
        }
        double dark = Math.sin(Math.toRadians(12));
        for (int y = 0; y < NIGHT_H; y++) {
            double lat = Math.toRadians(90 - (y + 0.5) * 180.0 / NIGHT_H);
            double a = Math.sin(lat) * sinD, b = Math.cos(lat) * cosD;
            for (int x = 0; x < NIGHT_W; x++) {
                double s = a + b * cosH[x];   // sine of the sun's elevation
                double f = Math.max(0, Math.min(1, (0.015 - s) / (dark + 0.015)));
                nightPixels[y * NIGHT_W + x] = ((int) Math.round(f * 165) << 24) | 0x000105;
            }
        }
        night.setPixels(nightPixels, 0, NIGHT_W, 0, 0, NIGHT_W, NIGHT_H);
        hasNight = true;
    }

    /** Moves the whole map a few pixels and the panel a little within its corner; call once a minute. */
    void nudge(float mapX, float mapY, float panelX, float panelY) {
        driftX = mapX * dp;
        driftY = mapY * dp;
        panelNudgeX = panelX * dp;
        panelNudgeY = panelY * dp;
        buildTrack();
        invalidate();
    }

    /** Fades the panel out, moves it to the corner farthest from the ISS, and fades it back in. */
    void moveCorner() {
        if (getWidth() == 0) return;
        int best = corner;
        double bestDistance = -1;
        for (int c = 0; c < 4; c++) {
            if (c == corner) continue;
            RectF r = panelRect(c);
            double d = hasIss ? Math.hypot(r.centerX() - x(issLon), r.centerY() - y(issLat)) : c;
            if (d > bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        int target = best;
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(1f, 0f, 1f);
        fade.setDuration(2400);
        fade.addUpdateListener(a -> {
            panelAlpha = (float) a.getAnimatedValue();
            if (a.getAnimatedFraction() >= 0.5f) corner = target;
            invalidate();
        });
        fade.start();
    }

    void stopAnimations() {
        if (fade != null) fade.cancel();
    }

    // ---- layout and drawing -----------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w == 0 || h == 0) return;
        float mapW = w * 0.92f;
        float mapH = mapW / 2;
        if (mapH > h * 0.92f) {
            mapH = h * 0.92f;
            mapW = mapH * 2;
        }
        map.set((w - mapW) / 2, (h - mapH) / 2, (w + mapW) / 2, (h + mapH) / 2);
        buildBase();
        buildTrack();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimations();
        if (base != null) base.recycle();
        base = null;
        super.onDetachedFromWindow();
    }

    private void buildBase() {
        if (base != null) base.recycle();
        base = null;
        float scale = Math.min(1f, MAX_BASE_W / map.width());
        int bw = Math.max(1, Math.round(map.width() * scale));
        int bh = Math.max(1, Math.round(map.height() * scale));
        float[][] rings;
        try (InputStream in = getResources().openRawResource(R.raw.world_land)) {
            rings = WorldMap.read(in);
        } catch (IOException | RuntimeException e) {
            Log.w("TvHome.IssView", "world map unreadable", e);
            rings = new float[0][];
        }
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        c.drawColor(OCEAN);
        Paint grid = new Paint();
        grid.setColor(GRID);
        grid.setStrokeWidth(Math.max(1f, scale * dp));
        for (int lon = -150; lon <= 150; lon += 30) c.drawLine(bx(lon, bw), 0, bx(lon, bw), bh, grid);
        for (int lat = -60; lat <= 60; lat += 30) c.drawLine(0, by(lat, bh), bw, by(lat, bh), grid);
        Path land = new Path();
        land.setFillType(Path.FillType.EVEN_ODD);
        for (float[] ring : rings) {
            for (int i = 0; i < ring.length; i += 2) {
                float px = bx(ring[i], bw), py = by(ring[i + 1], bh);
                if (i == 0) land.moveTo(px, py);
                else land.lineTo(px, py);
            }
            land.close();
        }
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(LAND);
        c.drawPath(land, fill);
        Paint coast = new Paint(Paint.ANTI_ALIAS_FLAG);
        coast.setStyle(Paint.Style.STROKE);
        coast.setStrokeWidth(Math.max(1f, scale * dp));
        coast.setColor(COAST);
        c.drawPath(land, coast);
        base = b;
    }

    private static float bx(double lon, int w) {
        return (float) ((lon + 180) / 360 * w);
    }

    private static float by(double lat, int h) {
        return (float) ((90 - lat) / 180 * h);
    }

    private float x(double lon) {
        return (float) (map.left + driftX + (lon + 180) / 360 * map.width());
    }

    private float y(double lat) {
        return (float) (map.top + driftY + (90 - lat) / 180 * map.height());
    }

    private void buildTrack() {
        past.rewind();
        future.rewind();
        if (track == null || map.isEmpty()) return;
        addSegment(past, 0, trackNow);
        addSegment(future, trackNow, track.length / 2 - 1);
    }

    /** Pairs {@code from} to {@code to}, broken where the track crosses the date line. */
    private void addSegment(Path path, int from, int to) {
        float prevLon = 0;
        for (int i = from; i <= to; i++) {
            float lon = track[2 * i], lat = track[2 * i + 1];
            float px = x(lon), py = y(lat);
            if (i == from || Math.abs(lon - prevLon) > 180) path.moveTo(px, py);
            else path.lineTo(px, py);
            prevLon = lon;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (map.isEmpty()) return;
        dst.set(map);
        dst.offset(driftX, driftY);
        if (base != null) canvas.drawBitmap(base, null, dst, bitmapPaint);
        if (hasNight) canvas.drawBitmap(night, null, dst, bitmapPaint);

        canvas.drawPath(past, pastPaint);
        canvas.drawPath(future, futurePaint);

        if (hasHome) {
            float hx = x(homeLon), hy = y(homeLat);
            canvas.drawCircle(hx, hy, 9 * dp, homeRingPaint);
            canvas.drawCircle(hx, hy, 3.5f * dp, homePaint);
        }

        if (hasIss) {
            float ix = x(issLon), iy = y(issLat);
            canvas.save();
            canvas.translate(ix, iy);
            canvas.drawCircle(0, 0, haloRadius, haloPaint);
            canvas.drawCircle(0, 0, 8 * dp, ringPaint);
            canvas.drawCircle(0, 0, 4.5f * dp, dotPaint);
            canvas.restore();
        }

        drawPanel(canvas);
    }

    private float panelWidth() {
        return 330 * dp;
    }

    private float panelHeight() {
        float rows = labels.length * 34 * dp;
        return 22 * dp + 30 * dp + 22 * dp + 12 * dp + rows + (status.isEmpty() ? 0 : 30 * dp) + 16 * dp;
    }

    private RectF panelRect(int c) {
        float w = panelWidth(), h = panelHeight();
        float mx = getWidth() * 0.05f, my = getHeight() * 0.06f;
        float left = (c == 0 || c == 3) ? mx : getWidth() - mx - w;
        float top = (c == 0 || c == 1) ? my : getHeight() - DreamInset.bottom - my - h;   // above the news ticker
        return new RectF(left, top, left + w, top + h);
    }

    private void drawPanel(Canvas canvas) {
        if (labels.length == 0 && status.isEmpty()) return;
        panel.set(panelRect(corner));
        panel.offset(panelNudgeX, panelNudgeY);
        int alpha = Math.round(255 * panelAlpha);
        if (alpha <= 0) return;
        canvas.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, alpha);
        float r = 18 * dp;
        canvas.drawRoundRect(panel, r, r, panelPaint);
        canvas.drawRoundRect(panel, r, r, panelBorder);
        float pad = 22 * dp;
        float left = panel.left + pad, right = panel.right - pad;
        float line = panel.top + pad + 24 * dp;
        canvas.drawText(title, left, line, titleText);
        canvas.drawText(time, right, line, timeText);
        line += 24 * dp;
        canvas.drawText(subtitle, left, line, smallText);
        line += 14 * dp;
        for (int i = 0; i < labels.length; i++) {
            line += 34 * dp;
            canvas.drawText(labels[i], left, line, labelText);
            canvas.drawText(i < values.length ? values[i] : "", right, line, valueText);
        }
        if (!status.isEmpty()) {
            line += 30 * dp;
            canvas.drawText(status, left, line, smallText);
        }
        canvas.restore();
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
