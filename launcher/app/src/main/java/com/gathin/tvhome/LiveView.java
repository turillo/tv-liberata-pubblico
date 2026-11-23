package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Random;

/**
 * The live Earth scene's view: two layers that cross-fade between the whole disk (fitted, on black) and Europe (full
 * screen), each slowly zooming and drifting while it shows (render properties only), frames swapped in place for
 * the time-lapse, and a small panel in a corner in the style of the other scenes.
 */
final class LiveView extends FrameLayout {

    private static final float ZOOM = 1.06f;

    private final float dp;
    private final ImageView[] layers = new ImageView[2];
    private int front = -1;
    private final Random random = new Random();

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valueText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messageText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String title = "", time = "", subtitle = "", heading = "", detail = "";
    private String[] labels = new String[0];
    private String[] values = new String[0];
    private String message;

    private int corner = 3;
    private float panelAlpha = 1f;
    private ValueAnimator fade;
    private final RectF panel = new RectF();

    LiveView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        for (int i = 0; i < 2; i++) {
            ImageView layer = new ImageView(context);
            layer.setAlpha(0f);
            addView(layer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layers[i] = layer;
        }
        panelPaint.setColor(0xD905080F);
        panelBorder.setStyle(Paint.Style.STROKE);
        panelBorder.setStrokeWidth(dp);
        panelBorder.setColor(0x1FFFFFFF);
        titleText.setColor(0xE6FFFFFF);
        titleText.setTextSize(20 * sp);
        titleText.setTypeface(UiFont.get(context, R.string.font_medium));
        titleText.setLetterSpacing(0.08f);
        timeText.setColor(0xE6FFFFFF);
        timeText.setTextSize(20 * sp);
        timeText.setTypeface(UiFont.get(context, R.string.font_extralight));
        timeText.setTextAlign(Paint.Align.RIGHT);
        smallText.setColor(0x8CFFFFFF);
        smallText.setTextSize(11 * sp);
        smallText.setTypeface(UiFont.get(context, R.string.font_regular));
        smallText.setLetterSpacing(0.12f);
        headText.setColor(0xE6FFFFFF);
        headText.setTextSize(17 * sp);
        headText.setTypeface(UiFont.get(context, R.string.font_light));
        labelText.set(smallText);
        labelText.setTextSize(12 * sp);
        labelText.setLetterSpacing(0.02f);
        valueText.setColor(0xE6FFFFFF);
        valueText.setTextSize(15 * sp);
        valueText.setTypeface(UiFont.get(context, R.string.font_light));
        valueText.setTextAlign(Paint.Align.RIGHT);
        messageText.set(headText);
        messageText.setColor(0x99FFFFFF);
        messageText.setTextAlign(Paint.Align.CENTER);
    }

    /** The disk's size in px: most of the screen height. */
    int diskSize() {
        int h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        return Math.round(h * 0.94f);
    }

    /** Fades {@code bitmap} in over the current view and starts its slow zoom and drift. */
    void showImage(Bitmap bitmap, boolean disk, long fadeMs, long showMs) {
        message = null;
        int next = front < 0 ? 0 : 1 - front;
        ImageView in = layers[next];
        in.animate().cancel();
        // The disk fills diskSize() whatever the bitmap's size (time-lapse frames are half size); Europe fills the screen.
        int pad = disk ? Math.max(0, (getHeight() - diskSize()) / 2) : 0;
        in.setPadding(0, pad, 0, pad);
        in.setScaleType(disk ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        in.setImageBitmap(bitmap);
        in.setZ(1f);
        float range = getResources().getDisplayMetrics().heightPixels * (disk ? 0.012f : 0.02f);
        double a = random.nextDouble() * 2 * Math.PI;
        float dx = (float) (range * Math.cos(a)), dy = (float) (range * Math.sin(a));
        float from = disk ? 1f : 1.01f, to = disk ? 1.04f : ZOOM;
        in.setTranslationX(dx);
        in.setTranslationY(dy);
        in.setScaleX(from);
        in.setScaleY(from);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(fadeMs).setInterpolator(null).withEndAction(() -> {
            if (front == next) hideBack();
            in.animate().translationX(-dx).translationY(-dy).scaleX(to).scaleY(to)
                    .setDuration(showMs).setInterpolator(new LinearInterpolator()).start();
        }).start();
        if (front >= 0) layers[front].setZ(0f);
        front = next;
        invalidate();
    }

    /** Swaps the shown picture in place (a time-lapse frame), keeping its zoom and drift. */
    void showFrame(Bitmap bitmap) {
        if (front >= 0) layers[front].setImageBitmap(bitmap);
    }

    private void hideBack() {
        ImageView back = layers[1 - front];
        back.animate().cancel();
        back.setAlpha(0f);
        back.setImageDrawable(null);
    }

    void showMessage(String text) {
        message = text;
        for (ImageView layer : layers) {
            layer.animate().cancel();
            layer.setAlpha(0f);
            layer.setImageDrawable(null);
        }
        front = -1;
        invalidate();
    }

    boolean hasImage() {
        return front >= 0;
    }

    void setPanel(String title, String time, String subtitle, String heading, String detail,
                  String[] labels, String[] values) {
        this.title = title;
        this.time = time;
        this.subtitle = subtitle;
        this.heading = heading;
        this.detail = detail;
        this.labels = labels;
        this.values = values;
        invalidate();
    }

    /** Fades the panel out, moves it to the next corner clockwise, and fades it back in. */
    void moveCorner() {
        if (fade != null) fade.cancel();
        int target = (corner + 1) % 4;
        fade = ValueAnimator.ofFloat(1f, 0f, 1f);
        fade.setDuration(2400);
        fade.addUpdateListener(an -> {
            panelAlpha = (float) an.getAnimatedValue();
            if (an.getAnimatedFraction() >= 0.5f) corner = target;
            invalidate();
        });
        fade.start();
    }

    void stopAnimations() {
        if (fade != null) fade.cancel();
        for (ImageView layer : layers) layer.animate().cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimations();
        for (ImageView layer : layers) layer.setImageDrawable(null);
        super.onDetachedFromWindow();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (message != null) {
            canvas.drawText(message, getWidth() / 2f, getHeight() / 2f, messageText);
            return;
        }
        if (front >= 0) drawPanel(canvas);
    }

    private void drawPanel(Canvas canvas) {
        int alpha = Math.round(255 * panelAlpha);
        if (alpha <= 0) return;
        float widest = Math.max(headText.measureText(heading), smallText.measureText(detail));
        widest = Math.max(widest, titleText.measureText(title) + timeText.measureText(time) + 24 * dp);
        widest = Math.max(widest, smallText.measureText(subtitle));
        for (int i = 0; i < labels.length; i++) {
            widest = Math.max(widest, labelText.measureText(labels[i]) + 20 * dp
                    + (i < values.length ? valueText.measureText(values[i]) : 0));
        }
        float w = Math.max(230 * dp, widest + 36 * dp);
        float h = 18 * dp + 20 * dp + 18 * dp + 28 * dp + 18 * dp + labels.length * 26 * dp + 18 * dp;
        float mx = getWidth() * 0.04f, my = getHeight() * 0.05f;
        float left = (corner == 0 || corner == 3) ? mx : getWidth() - mx - w;
        float top = (corner == 0 || corner == 1) ? my : getHeight() - DreamInset.bottom - my - h;   // above the news ticker
        panel.set(left, top, left + w, top + h);
        canvas.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, alpha);
        float r = 16 * dp;
        canvas.drawRoundRect(panel, r, r, panelPaint);
        canvas.drawRoundRect(panel, r, r, panelBorder);
        float pad = 18 * dp;
        float x = panel.left + pad, right = panel.right - pad;
        float line = panel.top + pad + 18 * dp;
        canvas.drawText(title, x, line, titleText);
        canvas.drawText(time, right, line, timeText);
        line += 18 * dp;
        canvas.drawText(subtitle, x, line, smallText);
        line += 28 * dp;
        canvas.drawText(heading, x, line, headText);
        line += 18 * dp;
        canvas.drawText(detail, x, line, smallText);
        for (int i = 0; i < labels.length; i++) {
            line += 26 * dp;
            canvas.drawText(labels[i], x, line, labelText);
            canvas.drawText(i < values.length ? values[i] : "", right, line, valueText);
        }
        canvas.restore();
    }
}
