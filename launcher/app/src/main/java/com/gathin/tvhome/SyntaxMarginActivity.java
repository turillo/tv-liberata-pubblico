package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Syntax TV's "Margini schermo", for a TV that crops the edges of the picture: a frame drawn where the margin puts
 * the edges of Home, the kitchen panel, the news band and Syntax TV. Left and right change it (0-5 %) until the
 * whole frame shows; OK saves it for this TV, Back leaves it as it was.
 */
public final class SyntaxMarginActivity extends Activity {
    private int percent;
    private Frame frame;
    private TextView value;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        percent = ScreenMargin.percent(this);
        FrameLayout outer = new FrameLayout(this);
        outer.setBackgroundColor(SyntaxUi.BG);
        frame = new Frame(this);
        outer.addView(frame, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = SyntaxUi.text(this, 30, R.string.font_medium, SyntaxUi.TEXT);
        title.setText(R.string.margin_title);
        title.setGravity(Gravity.CENTER);
        middle.addView(title, centred());
        value = SyntaxUi.text(this, 72, R.string.font_light, SyntaxUi.ACCENT);
        value.setPadding(0, SyntaxUi.dp(this, 12), 0, SyntaxUi.dp(this, 12));
        value.setGravity(Gravity.CENTER);
        middle.addView(value, centred());
        TextView help = SyntaxUi.text(this, 16, R.string.font_light, SyntaxUi.MUTED);
        help.setText(R.string.margin_help);
        help.setGravity(Gravity.CENTER);
        help.setMaxWidth(SyntaxUi.dp(this, 520));
        middle.addView(help, centred());
        outer.addView(middle, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(outer);
        show();
    }

    private static LinearLayout.LayoutParams centred() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void show() {
        value.setText(getString(R.string.margin_value, percent));
        frame.set(percent);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (percent > 0) percent--;
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (percent < SyntaxSettings.MARGIN_MAX) percent++;
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                SyntaxPrefs.get(this).setScreenMargin(percent);
                setResult(RESULT_OK);
                finish();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /** The frame: a line where the margin puts the screen's edges, with marks in the corners. */
    private static final class Frame extends View {
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private int percent;

        Frame(Context c) {
            super(c);
            float dp = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * dp);
            line.setColor(SyntaxUi.ACCENT);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(6 * dp);
            corner.setColor(SyntaxUi.TEXT);
            shade.setColor(0xFF1A2230);
        }

        void set(int percent) {
            this.percent = percent;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float x = ScreenMargin.x(getContext(), percent), y = ScreenMargin.y(getContext(), percent);
            // What the margin keeps clear, a shade lighter than the page.
            canvas.drawRect(0, 0, w, y, shade);
            canvas.drawRect(0, h - y, w, h, shade);
            canvas.drawRect(0, y, x, h - y, shade);
            canvas.drawRect(w - x, y, w, h - y, shade);
            float half = line.getStrokeWidth() / 2;
            canvas.drawRect(x + half, y + half, w - x - half, h - y - half, line);
            float arm = Math.min(w, h) * 0.06f, c = corner.getStrokeWidth() / 2;
            float l = x + c, t = y + c, r = w - x - c, b = h - y - c;
            canvas.drawLine(l, t, l + arm, t, corner);
            canvas.drawLine(l, t, l, t + arm, corner);
            canvas.drawLine(r, t, r - arm, t, corner);
            canvas.drawLine(r, t, r, t + arm, corner);
            canvas.drawLine(l, b, l + arm, b, corner);
            canvas.drawLine(l, b, l, b - arm, corner);
            canvas.drawLine(r, b, r - arm, b, corner);
            canvas.drawLine(r, b, r, b - arm, corner);
        }
    }
}
