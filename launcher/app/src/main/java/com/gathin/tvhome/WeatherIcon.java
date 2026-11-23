package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A thin line icon for the header's weather (WeatherCode icons): sun or moon, cloud, fog, rain, snow and storm,
 * in the text's colour. Drawn on a canvas, so no images and no icon font; redrawn only when the weather changes.
 */
final class WeatherIcon extends Drawable {

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path cloud = new Path();
    private final Path cut = new Path();
    private final Path bump = new Path();
    private int kind = WeatherCode.ICON_CLOUD;
    private boolean day = true;

    WeatherIcon(int color) {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setColor(color);
    }

    void set(int kind, boolean day) {
        this.kind = kind;
        this.day = day;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height());
        if (s <= 0) return;
        stroke.setStrokeWidth(Math.max(1.5f, s * 0.075f));
        canvas.save();
        canvas.translate(b.left + (b.width() - s) / 2f, b.top + (b.height() - s) / 2f);
        switch (kind) {
            case WeatherCode.ICON_CLEAR:
                sky(canvas, s * 0.5f, s * 0.5f, s * 0.22f, s);
                break;
            case WeatherCode.ICON_PARTLY:
                // The sun or moon behind the cloud: clipped where the cloud covers it.
                cloud(s * 0.08f, s * 0.38f, s * 0.84f);
                canvas.save();
                canvas.clipOutPath(cloud);
                sky(canvas, s * 0.36f, s * 0.36f, s * 0.15f, s * 0.75f);
                canvas.restore();
                canvas.drawPath(cloud, stroke);
                break;
            case WeatherCode.ICON_FOG:
                for (int i = 0; i < 3; i++) {
                    float y = s * (0.3f + 0.2f * i);
                    float inset = s * (i == 1 ? 0.08f : 0.16f);
                    canvas.drawLine(inset, y, s - inset, y, stroke);
                }
                break;
            case WeatherCode.ICON_RAIN:
                cloud(s * 0.06f, s * 0.12f, s * 0.88f);
                canvas.drawPath(cloud, stroke);
                for (int i = 0; i < 3; i++) {
                    float x = s * (0.3f + 0.2f * i);
                    canvas.drawLine(x, s * 0.72f, x - s * 0.07f, s * 0.92f, stroke);
                }
                break;
            case WeatherCode.ICON_SNOW:
                cloud(s * 0.06f, s * 0.12f, s * 0.88f);
                canvas.drawPath(cloud, stroke);
                for (int i = 0; i < 3; i++) {
                    canvas.drawCircle(s * (0.3f + 0.2f * i), s * (i == 1 ? 0.9f : 0.8f), s * 0.05f, fill);
                }
                break;
            case WeatherCode.ICON_STORM:
                cloud(s * 0.06f, s * 0.08f, s * 0.88f);
                canvas.drawPath(cloud, stroke);
                path.reset();
                path.moveTo(s * 0.54f, s * 0.6f);
                path.lineTo(s * 0.42f, s * 0.8f);
                path.lineTo(s * 0.56f, s * 0.8f);
                path.lineTo(s * 0.44f, s * 0.98f);
                canvas.drawPath(path, stroke);
                break;
            default:
                cloud(s * 0.06f, s * 0.22f, s * 0.88f);
                canvas.drawPath(cloud, stroke);
                break;
        }
        canvas.restore();
    }

    /** Sun with rays by day, a crescent moon at night. */
    private void sky(Canvas canvas, float cx, float cy, float r, float s) {
        if (day) {
            canvas.drawCircle(cx, cy, r, stroke);
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                float c = (float) Math.cos(a), sn = (float) Math.sin(a);
                canvas.drawLine(cx + c * r * 1.55f, cy + sn * r * 1.55f, cx + c * r * 2.05f, cy + sn * r * 2.05f, stroke);
            }
        } else {
            float mr = r * 1.6f;
            path.reset();
            path.addCircle(cx, cy, mr, Path.Direction.CW);
            cut.reset();
            cut.addCircle(cx + mr * 0.62f, cy - mr * 0.45f, mr * 0.9f, Path.Direction.CW);
            path.op(cut, Path.Op.DIFFERENCE);
            canvas.drawPath(path, stroke);
        }
    }

    /** A cloud outline, two bumps on a flat base, from (left, top) and w wide. */
    private void cloud(float left, float top, float w) {
        float h = w * 0.55f;
        float base = top + h;
        cloud.reset();
        cloud.addRoundRect(left, top + h * 0.45f, left + w, base, h * 0.28f, h * 0.28f, Path.Direction.CW);
        bump.reset();
        bump.addCircle(left + w * 0.36f, top + h * 0.45f, h * 0.36f, Path.Direction.CW);
        cloud.op(bump, Path.Op.UNION);
        bump.reset();
        bump.addCircle(left + w * 0.64f, top + h * 0.38f, h * 0.44f, Path.Direction.CW);
        cloud.op(bump, Path.Op.UNION);
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        stroke.setColorFilter(filter);
        fill.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
