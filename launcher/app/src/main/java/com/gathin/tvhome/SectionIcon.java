package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The settings sections' icons, drawn as simple white lines (no images, no emoji). */
final class SectionIcon extends Drawable {

    static final int WALLPAPER = 0, SCREENSAVER = 1, APPS = 2, NEWS = 3, THEMES = 4, SYSTEM = 5, CHRISTMAS = 6;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int kind;

    SectionIcon(int kind, int color) {
        this.kind = kind;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()), x = b.left + (b.width() - s) / 2f, y = b.top + (b.height() - s) / 2f;
        line.setStrokeWidth(s * 0.075f);
        switch (kind) {
            case WALLPAPER: {   // a picture: frame, mountains, sun
                RectF f = new RectF(x + s * .12f, y + s * .2f, x + s * .88f, y + s * .8f);
                c.drawRoundRect(f, s * .08f, s * .08f, line);
                Path m = new Path();
                m.moveTo(x + s * .18f, y + s * .72f);
                m.lineTo(x + s * .4f, y + s * .46f);
                m.lineTo(x + s * .56f, y + s * .62f);
                m.lineTo(x + s * .66f, y + s * .52f);
                m.lineTo(x + s * .82f, y + s * .72f);
                c.drawPath(m, line);
                c.drawCircle(x + s * .67f, y + s * .34f, s * .06f, fill);
                break;
            }
            case SCREENSAVER: {   // a moon over a screen line
                Path moon = new Path();
                moon.addCircle(x + s * .5f, y + s * .46f, s * .26f, Path.Direction.CW);
                Path bite = new Path();
                bite.addCircle(x + s * .62f, y + s * .36f, s * .22f, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                c.drawPath(moon, fill);
                c.drawLine(x + s * .22f, y + s * .84f, x + s * .78f, y + s * .84f, line);
                break;
            }
            case APPS: {   // four rounded squares
                float g = s * .08f, w = (s * .64f - g) / 2f;
                for (int i = 0; i < 4; i++) {
                    float l = x + s * .18f + (i % 2) * (w + g), t = y + s * .18f + (i / 2) * (w + g);
                    c.drawRoundRect(new RectF(l, t, l + w, t + w), s * .06f, s * .06f, line);
                }
                break;
            }
            case NEWS: {   // a page with lines
                c.drawRoundRect(new RectF(x + s * .16f, y + s * .18f, x + s * .84f, y + s * .82f), s * .07f, s * .07f, line);
                c.drawRect(x + s * .28f, y + s * .3f, x + s * .46f, y + s * .46f, fill);
                c.drawLine(x + s * .54f, y + s * .33f, x + s * .72f, y + s * .33f, line);
                c.drawLine(x + s * .54f, y + s * .44f, x + s * .72f, y + s * .44f, line);
                c.drawLine(x + s * .28f, y + s * .58f, x + s * .72f, y + s * .58f, line);
                c.drawLine(x + s * .28f, y + s * .69f, x + s * .62f, y + s * .69f, line);
                break;
            }
            case THEMES: {   // a palette: an outline and three dots
                c.drawCircle(x + s * .5f, y + s * .5f, s * .32f, line);
                c.drawCircle(x + s * .38f, y + s * .4f, s * .055f, fill);
                c.drawCircle(x + s * .55f, y + s * .33f, s * .055f, fill);
                c.drawCircle(x + s * .66f, y + s * .48f, s * .055f, fill);
                c.drawCircle(x + s * .44f, y + s * .62f, s * .075f, line);
                break;
            }
            case SYSTEM: {   // three sliders
                for (int i = 0; i < 3; i++) {
                    float ly = y + s * (.28f + i * .22f);
                    c.drawLine(x + s * .18f, ly, x + s * .82f, ly, line);
                    float k = x + s * (i == 1 ? .64f : i == 0 ? .36f : .5f);
                    c.drawCircle(k, ly, s * .075f, fill);
                }
                break;
            }
            default: {   // a star
                Path p = new Path();
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI / 2 + i * Math.PI / 5;
                    float r = s * (i % 2 == 0 ? .34f : .15f);
                    float px = x + s * .5f + (float) (r * Math.cos(a)), py = y + s * .52f - (float) (r * Math.sin(a));
                    if (i == 0) p.moveTo(px, py);
                    else p.lineTo(px, py);
                }
                p.close();
                c.drawPath(p, line);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        line.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        line.setColorFilter(cf);
        fill.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
