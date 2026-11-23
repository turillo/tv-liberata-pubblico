package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.Random;

/**
 * The Earth screensaver scene's view: faint fixed stars, two photo layers clipped to the Earth's disk that
 * cross-fade, each slowly zooming, drifting and turning a little while it shows (render properties only, so the
 * photo is never redrawn), and a small panel in a corner in the style of the ISS and Sun scenes. With no photo it
 * shows one short centred line on black.
 */
final class EarthView extends FrameLayout {

    /** The Earth's diameter as a fraction of the screen height. */
    private static final float DISK_OF_HEIGHT = 0.80f;
    /** The disk clip is a little wider than the measured disk, so the thin atmosphere stays. */
    private static final float CLIP_MARGIN = 0.01f;
    private static final float ZOOM = 1.05f;
    private static final float TURN_DEGREES = 2f;
    private static final int STARS = 220;

    private final float dp;
    private final ImageView[] layers = new ImageView[2];
    private int front = -1;
    private final Random random = new Random();
    private final float[] stars = new float[STARS * 3];   // x, y as fractions, radius in px
    private final int[] starAlpha = new int[STARS];
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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

    private static final ViewOutlineProvider DISK_OUTLINE = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int w = view.getWidth(), h = view.getHeight();
            Object disk = view.getTag();
            float clip = (disk instanceof Float ? (Float) disk : EarthData.DISK) + CLIP_MARGIN;
            int inset = Math.round(w * (1 - clip) / 2);
            outline.setOval(inset, inset, w - inset, h - inset);
            outline.setAlpha(0f);   // a clip, not a shadow
        }
    };

    EarthView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        for (int i = 0; i < 2; i++) {
            ImageView layer = new ImageView(context);
            layer.setScaleType(ImageView.ScaleType.FIT_CENTER);
            layer.setOutlineProvider(DISK_OUTLINE);
            layer.setClipToOutline(true);
            layer.setAlpha(0f);
            addView(layer, new LayoutParams(0, 0, Gravity.CENTER));
            layers[i] = layer;
        }

        Random sky = new Random(1972);   // the same sky every time
        for (int i = 0; i < STARS; i++) {
            stars[3 * i] = sky.nextFloat();
            stars[3 * i + 1] = sky.nextFloat();
            stars[3 * i + 2] = (sky.nextFloat() < 0.85f ? 0.6f : 1.0f) * dp;
            starAlpha[i] = 30 + sky.nextInt(sky.nextFloat() < 0.9f ? 70 : 150);
        }
        starPaint.setColor(Color.WHITE);

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

    /** The photo's size in px on screen, so the Earth is the same size whatever the photo's disk fraction. */
    int imageSize(float disk) {
        int h = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        return Math.round(h * DISK_OF_HEIGHT / disk);
    }

    /** Fades {@code bitmap} in over the current photo and starts its slow drift, zoom and turn. */
    void showImage(Bitmap bitmap, float disk, long fadeMs, long showMs) {
        message = null;
        int next = front < 0 ? 0 : 1 - front;
        ImageView in = layers[next];
        in.animate().cancel();
        int size = imageSize(disk);
        in.setTag(disk);
        LayoutParams lp = (LayoutParams) in.getLayoutParams();
        if (lp.width != size) {
            lp.width = size;
            lp.height = size;
            in.setLayoutParams(lp);
        } else {
            in.invalidateOutline();
        }
        in.setImageBitmap(bitmap);
        in.setZ(1f);
        float range = getResources().getDisplayMetrics().heightPixels * 0.012f;
        double a = random.nextDouble() * 2 * Math.PI;
        float dx = (float) (range * Math.cos(a)), dy = (float) (range * Math.sin(a));
        float turn = random.nextBoolean() ? TURN_DEGREES : -TURN_DEGREES;
        in.setTranslationX(dx);
        in.setTranslationY(dy);
        in.setRotation(-turn / 2);
        in.setScaleX(1f);
        in.setScaleY(1f);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(fadeMs).setInterpolator(null).withEndAction(() -> {
            if (front == next) hideBack();
            in.animate().translationX(-dx).translationY(-dy).scaleX(ZOOM).scaleY(ZOOM).rotation(turn / 2)
                    .setDuration(showMs).setInterpolator(new LinearInterpolator()).start();
        }).start();
        if (front >= 0) layers[front].setZ(0f);
        front = next;
        invalidate();
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
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < STARS; i++) {
            starPaint.setAlpha(starAlpha[i]);
            canvas.drawCircle(stars[3 * i] * w, stars[3 * i + 1] * h, stars[3 * i + 2], starPaint);
        }
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

    private float panelWidth() {
        float widest = Math.max(headText.measureText(heading), smallText.measureText(detail));
        widest = Math.max(widest, titleText.measureText(title) + timeText.measureText(time) + 24 * dp);
        widest = Math.max(widest, smallText.measureText(subtitle));
        for (int i = 0; i < labels.length; i++) {
            float row = labelText.measureText(labels[i]) + 20 * dp + (i < values.length ? valueText.measureText(values[i]) : 0);
            widest = Math.max(widest, row);
        }
        return Math.max(230 * dp, widest + 36 * dp);
    }

    private float panelHeight() {
        return 18 * dp + 20 * dp + 18 * dp + 28 * dp + 18 * dp + labels.length * 26 * dp + 18 * dp;
    }

    private void drawPanel(Canvas canvas) {
        int alpha = Math.round(255 * panelAlpha);
        if (alpha <= 0) return;
        float w = panelWidth(), h = panelHeight();
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
