package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.IBinder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.time.LocalDate;

/**
 * The Christmas mode on Home: light snow and slowly twinkling lights along the dock's top edge, in a full-screen
 * window of their own over Home that takes no keys and no touches (so drawing them never redraws Home's wallpaper
 * and tiles, and the dock's focus moves as before), and the countdown line under the date and weather. Everything
 * follows Syntax TV's switches at each return to Home; with the mode off nothing is added.
 */
final class ChristmasHome {

    private static final String TAG = "TvHome.ChristmasHome";

    private final Activity activity;
    private final RecyclerView grid;
    private final int dockSize;
    private Overlay overlay;
    private boolean attached;
    private TextView countdown;

    ChristmasHome(Activity activity, RecyclerView grid, int dockSize) {
        this.activity = activity;
        this.grid = grid;
        this.dockSize = dockSize;
    }

    /** Home is in front: show what the switches ask for. */
    void resume() {
        try {
            SyntaxSettings s = SyntaxPrefs.get(activity);
            boolean snow = s.xmas(SyntaxSettings.XMAS_SNOW_HOME), lights = s.xmas(SyntaxSettings.XMAS_DOCK);
            if (snow || lights) {
                if (overlay == null) overlay = new Overlay();
                overlay.setSnow(snow);
                overlay.lights = lights;
                resumed = true;
                attach();
                overlay.setVisibility(View.VISIBLE);
                overlay.setAlpha(1f);
                overlay.start();
            } else {
                hide();
            }
            showCountdown(s.xmas(SyntaxSettings.XMAS_COUNTDOWN));
        } catch (RuntimeException e) {
            Log.w(TAG, "Christmas decorations skipped: " + e.getMessage());
            hide();
        }
    }

    /** Idle this long after a key, the snow and lights come back. */
    private static final long IDLE_MS = 2_500L;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean resumed;
    private final Runnable comeBack = () -> {
        if (overlay == null || !resumed) return;   // Home left in the meantime
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.start();
        overlay.animate().alpha(1f).setDuration(700).start();
    };

    /**
     * A key was pressed on Home: the snow and lights hide at once and come back when the remote has been still for a
     * moment. Measured on the Sony while moving the focus: about 60 ms a frame with them showing, about 16 hidden,
     * 10 without the mode (removing the window instead of hiding it measured worse, about 30).
     */
    void userActive() {
        if (overlay == null || !resumed || !attached) return;
        handler.removeCallbacks(comeBack);
        if (overlay.getVisibility() != View.GONE) {
            overlay.animate().cancel();
            overlay.stop();
            overlay.setVisibility(View.GONE);
        }
        handler.postDelayed(comeBack, IDLE_MS);
    }

    /** Home is covered: the snow and lights stop moving. */
    void pause() {
        resumed = false;
        handler.removeCallbacks(comeBack);
        if (overlay != null) overlay.stop();
    }

    /** Home is gone: the window goes too. */
    void hide() {
        pause();
        detach();
    }

    private void detach() {
        if (attached && overlay != null) {
            try {
                activity.getWindowManager().removeViewImmediate(overlay);
            } catch (RuntimeException ignored) {
                // gone with Home's window
            }
        }
        attached = false;
    }

    private void attach() {
        if (attached) return;
        IBinder token = activity.getWindow().getDecorView().getWindowToken();
        if (token == null) {
            grid.post(() -> {
                if (!attached && overlay != null && !activity.isFinishing() && SyntaxPrefs.get(activity).christmas()) {
                    attach();
                    overlay.start();
                }
            });
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.token = token;
        lp.windowAnimations = 0;
        lp.setTitle("TvHome Christmas");
        try {
            activity.getWindowManager().addView(overlay, lp);
            attached = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Christmas window refused: " + e.getMessage());
        }
    }

    /** The line under the date and weather; gone with the switch off. */
    private void showCountdown(boolean on) {
        View weather = activity.findViewById(R.id.weather);
        if (countdown == null && on && weather != null && weather.getParent() instanceof LinearLayout) {
            countdown = new TextView(activity);
            countdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            countdown.setTextColor(0xFFFFD9A0);
            countdown.setSingleLine(true);
            try {
                countdown.setTypeface(UiFont.get(activity, R.string.font_regular));
            } catch (RuntimeException ignored) {
                // the system font is fine
            }
            countdown.setPadding(0, Math.round(6 * activity.getResources().getDisplayMetrics().density), 0, 0);
            LinearLayout parent = (LinearLayout) weather.getParent();
            parent.addView(countdown, parent.indexOfChild(weather) + 1);
        }
        if (countdown == null) return;
        countdown.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) countdown.setText(lineFor(activity, LocalDate.now()));
    }

    static String lineFor(android.content.Context a, LocalDate d) {
        int[] g = Christmas.greeting(d);
        switch (g[0]) {
            case Christmas.EVE: return a.getString(R.string.xmas_eve);
            case Christmas.DAY: return a.getString(R.string.xmas_day);
            case Christmas.HOLIDAYS: return a.getString(R.string.xmas_holidays);
            case Christmas.NEW_YEAR: return a.getString(R.string.xmas_new_year);
            default: return a.getResources().getQuantityString(R.plurals.xmas_days, g[1], g[1]);
        }
    }

    /** The window's view: SnowView's snow, plus the dock's lights. */
    private final class Overlay extends SnowView {
        private static final int BULBS = 22;
        private final int[] colors = {0xFFFFD27A, 0xFFFFF1D6, 0xFFFFB36B, 0xFFFFE6A8};
        private final Paint bulb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint wire = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF band = new RectF();
        private final int[] at = new int[2];
        private final float density;
        boolean lights;

        Overlay() {
            super(activity, 57, 1f);   // 42 until 11 November 2026: about a third more
            density = getResources().getDisplayMetrics().density;
            wire.setStyle(Paint.Style.STROKE);
            wire.setStrokeWidth(density);
            wire.setColor(0x33FFE6B0);
            glow.setShader(new RadialGradient(0, 0, 9 * density, 0x66FFD27A, 0x00FFD27A, Shader.TileMode.CLAMP));
        }

        @Override
        void drawExtra(Canvas canvas, float t) {
            if (!lights || !dockBand(band)) return;
            float left = band.left + 30 * density, right = band.right - 30 * density, y = band.top;
            // A gently sagging wire, with bulbs that brighten and fade slowly, out of step.
            for (int i = 0; i < BULBS; i++) {
                float f = i / (float) (BULBS - 1);
                float bx = left + f * (right - left);
                float by = y + 3 * density * (float) Math.sin(f * Math.PI * 6);
                float lit = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t * 0.9f + i * 1.7f));
                canvas.save();
                canvas.translate(bx, by);
                glow.setAlpha(Math.round(255 * lit));
                canvas.drawCircle(0, 0, 9 * density, glow);
                bulb.setColor(colors[i % colors.length]);
                bulb.setAlpha(Math.round(110 + 145 * lit));
                canvas.drawCircle(0, 0, 2.6f * density, bulb);
                canvas.restore();
            }
        }

        /** The dock band on screen, like DockDecoration draws it; false when the dock is not in view. */
        private boolean dockBand(RectF out) {
            boolean any = false;
            for (int i = 0; i < grid.getChildCount(); i++) {
                View child = grid.getChildAt(i);
                int pos = grid.getChildAdapterPosition(child);
                if (pos < 0 || pos >= dockSize) continue;
                View card = child.findViewById(R.id.card);
                if (card == null) card = child;
                card.getLocationOnScreen(at);
                float l = at[0], tp = at[1];
                if (!any) {
                    out.set(l, tp, l + card.getWidth(), tp + card.getHeight());
                    any = true;
                } else {
                    out.union(l, tp, l + card.getWidth(), tp + card.getHeight());
                }
            }
            if (!any) return false;
            out.inset(-22 * density, -14 * density);
            return out.top > 0;
        }
    }
}
