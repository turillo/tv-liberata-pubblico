package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Webb and Hubble scene's view: two full-screen image layers (centre crop) that cross-fade, each slowly zooming
 * and drifting while it shows (render properties only, so the bitmap is never redrawn), and a small panel in a
 * corner with the title, the telescope and the credit line, in the style of the other scenes.
 */
final class SpaceView extends FrameLayout {

    private static final float ZOOM = 1.08f;
    private static final int TITLE_LINES = 2, CREDIT_LINES = 3;

    private final float dp;
    private final ImageView[] layers = new ImageView[2];
    private int front = -1;
    private final Random random = new Random();

    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint kickerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint headText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint creditText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messageText = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String kicker = "", time = "";
    private List<String> titleLines = new ArrayList<>(), creditLines = new ArrayList<>();
    private String title = "", credit = "";
    private String message;

    private int corner = 3;
    private float panelAlpha = 1f;
    private ValueAnimator fade;
    private final RectF panel = new RectF();

    SpaceView(Context context) {
        super(context);
        dp = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        setBackgroundColor(Color.BLACK);
        setWillNotDraw(false);
        for (int i = 0; i < 2; i++) {
            ImageView layer = new ImageView(context);
            layer.setScaleType(ImageView.ScaleType.CENTER_CROP);
            layer.setAlpha(0f);
            addView(layer, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            layers[i] = layer;
        }
        panelPaint.setColor(0xCC05080F);
        panelBorder.setStyle(Paint.Style.STROKE);
        panelBorder.setStrokeWidth(dp);
        panelBorder.setColor(0x1FFFFFFF);
        kickerText.setColor(0xE6FFFFFF);
        kickerText.setTextSize(15 * sp);
        kickerText.setTypeface(UiFont.get(context, R.string.font_medium));
        kickerText.setLetterSpacing(0.08f);
        timeText.setColor(0xE6FFFFFF);
        timeText.setTextSize(15 * sp);
        timeText.setTypeface(UiFont.get(context, R.string.font_extralight));
        timeText.setTextAlign(Paint.Align.RIGHT);
        headText.setColor(0xE6FFFFFF);
        headText.setTextSize(16 * sp);
        headText.setTypeface(UiFont.get(context, R.string.font_light));
        creditText.setColor(0x99FFFFFF);
        creditText.setTextSize(11 * sp);
        creditText.setTypeface(UiFont.get(context, R.string.font_regular));
        creditText.setLetterSpacing(0.03f);
        messageText.set(headText);
        messageText.setColor(0x99FFFFFF);
        messageText.setTextAlign(Paint.Align.CENTER);
    }

    /** Fades {@code bitmap} in over the current image and starts its slow zoom and drift. */
    void showImage(Bitmap bitmap, long fadeMs, long showMs) {
        message = null;
        int next = front < 0 ? 0 : 1 - front;
        ImageView in = layers[next];
        in.animate().cancel();
        in.setImageBitmap(bitmap);
        in.setZ(1f);
        float range = getResources().getDisplayMetrics().heightPixels * 0.025f;
        double a = random.nextDouble() * 2 * Math.PI;
        float dx = (float) (range * Math.cos(a)), dy = (float) (range * Math.sin(a));
        boolean zoomIn = random.nextBoolean();
        float from = zoomIn ? 1.02f : ZOOM, to = zoomIn ? ZOOM : 1.02f;
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

    void setPanel(String kicker, String time, String title, String credit) {
        this.kicker = kicker;
        this.time = time;
        if (!title.equals(this.title) || !credit.equals(this.credit)) {
            this.title = title;
            this.credit = credit;
            float width = textWidth();
            titleLines = wrap(title, headText, width, TITLE_LINES);
            creditLines = wrap(credit, creditText, width, CREDIT_LINES);
        }
        invalidate();
    }

    private float textWidth() {
        return 380 * dp;
    }

    /** Word-wraps {@code text} into at most {@code max} lines, the last one ellipsized. */
    private static List<String> wrap(String text, Paint paint, float width, int max) {
        List<String> lines = new ArrayList<>();
        String rest = text.trim();
        while (!rest.isEmpty() && lines.size() < max) {
            if (lines.size() == max - 1 || paint.measureText(rest) <= width) {
                lines.add(TextUtils.ellipsize(rest, new android.text.TextPaint(paint), width, TextUtils.TruncateAt.END).toString());
                break;
            }
            int n = paint.breakText(rest, true, width, null);
            int cut = rest.lastIndexOf(' ', n);
            if (cut <= 0) cut = n;
            lines.add(rest.substring(0, cut).trim());
            rest = rest.substring(cut).trim();
        }
        return lines;
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
        float pad = 16 * dp;
        float widest = kickerText.measureText(kicker) + timeText.measureText(time) + 24 * dp;
        for (String l : titleLines) widest = Math.max(widest, headText.measureText(l));
        for (String l : creditLines) widest = Math.max(widest, creditText.measureText(l));
        float w = Math.max(220 * dp, widest + 2 * pad);
        float h = pad + 16 * dp + 10 * dp + titleLines.size() * 22 * dp + 6 * dp + creditLines.size() * 16 * dp + pad;
        float mx = getWidth() * 0.04f, my = getHeight() * 0.05f;
        float left = (corner == 0 || corner == 3) ? mx : getWidth() - mx - w;
        float top = (corner == 0 || corner == 1) ? my : getHeight() - DreamInset.bottom - my - h;   // above the news ticker
        panel.set(left, top, left + w, top + h);
        canvas.saveLayerAlpha(panel.left - 2, panel.top - 2, panel.right + 2, panel.bottom + 2, alpha);
        float r = 14 * dp;
        canvas.drawRoundRect(panel, r, r, panelPaint);
        canvas.drawRoundRect(panel, r, r, panelBorder);
        float x = panel.left + pad, right = panel.right - pad;
        float line = panel.top + pad + 14 * dp;
        canvas.drawText(kicker, x, line, kickerText);
        canvas.drawText(time, right, line, timeText);
        line += 10 * dp;
        for (String l : titleLines) {
            line += 22 * dp;
            canvas.drawText(l, x, line, headText);
        }
        line += 6 * dp;
        for (String l : creditLines) {
            line += 16 * dp;
            canvas.drawText(l, x, line, creditText);
        }
        canvas.restore();
    }
}
