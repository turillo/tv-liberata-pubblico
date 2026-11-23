package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * The news ticker along the bottom of Home, under the dock: the screensaver's band (NewsTickerView), its sections,
 * size and speed, and its headline cache (NewsTicker, which shares its download times with the screensaver, so the
 * two never download twice). It lives in a small window of its own over Home, so redrawing it every frame never
 * redraws Home's wallpaper and tiles, and moving the focus stays as smooth as without it. While it shows, the grid's
 * box ends above it, so the dock rises by the band's height; with no headline (offline, empty cache) or the switch
 * off, the band is gone and the dock is back in its usual place. Stops in onPause, goes away in onStop.
 */
final class HomeTicker {

    private static final String TAG = "TvHome.HomeTicker";

    private final Activity activity;
    private final View gridBox;
    private NewsTickerView band;
    private NewsTicker ticker;
    private String key = "";
    private boolean attached;
    private int lifted;

    HomeTicker(Activity activity, View gridBox) {
        this.activity = activity;
        this.gridBox = gridBox;
    }

    /** Home is in front again: rebuild the band if Syntax TV changed it, then scroll and refresh. */
    void resume() {
        try {
            SyntaxSettings s = SyntaxPrefs.get(activity);
            int margin = ScreenMargin.percent(activity);
            String now = s.homeTickerKey();
            if (!now.isEmpty()) now += "|" + margin;   // a new screen margin rebuilds the band too
            if (!now.equals(key)) {
                hide();
                // The old band goes for good: kept, it came straight back below even with the ticker turned off.
                band = null;
                ticker = null;
                key = now;
                if (!now.isEmpty()) {
                    band = new NewsTickerView(activity, NewsTicker.label(activity, s.sections()), s.tickerSpeedDp(),
                            SyntaxSettings.bandFraction(s.tickerSize()));
                    band.setInsets(ScreenMargin.x(activity, margin), ScreenMargin.y(activity, margin));
                NewsTickerView made = band;
                // Home's own padding already keeps the dock above the bottom margin the band includes.
                ticker = new NewsTicker(activity, band, s.sections(),
                        (visible, height) -> lift(visible ? height - made.insetBottom() : 0));
                }
            }
            if (band == null) return;
            attach();
            ticker.start();
        } catch (RuntimeException e) {
            // A band that cannot show must never take Home down with it.
            Log.w(TAG, "no news ticker on Home: " + e.getMessage());
            hide();
        }
    }

    /** Home is covered or left: the band stops moving and refreshing. */
    void pause() {
        if (ticker != null) ticker.stop();
    }

    /** Home is gone (onStop, onDestroy): the band's window goes too, and the dock goes back down. */
    void hide() {
        pause();
        if (attached && band != null) {
            try {
                activity.getWindowManager().removeViewImmediate(band);
            } catch (RuntimeException ignored) {
                // already gone with the activity's window
            }
        }
        attached = false;
        if (band == null || ticker == null) key = "";   // nothing left to reuse
        lift(0);
    }

    private void attach() {
        if (attached) return;
        IBinder token = activity.getWindow().getDecorView().getWindowToken();
        if (token == null) {
            // Home's own window is not up yet (a cold start): try once it is.
            gridBox.post(() -> {
                if (!attached && band != null && ticker != null && !activity.isFinishing()) {
                    attach();
                    ticker.start();
                }
            });
            return;
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                band.bandHeight(), WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM;
        lp.token = token;
        lp.setTitle("TvHome news ticker");
        lp.windowAnimations = 0;
        try {
            activity.getWindowManager().addView(band, lp);
            attached = true;
            // NewsTicker hid or showed the band before it had a window; apply that now.
            lift(band.getVisibility() == View.VISIBLE ? band.bandHeight() - band.insetBottom() : 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "news ticker window refused: " + e.getMessage());
        }
    }

    /** The grid's box ends {@code px} above the bottom, so the dock sits above the band. */
    private void lift(int px) {
        int want = attached ? px : 0;
        if (want == lifted) return;
        lifted = want;
        ViewGroup.LayoutParams p = gridBox.getLayoutParams();
        if (p instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) p).bottomMargin = want;
            gridBox.setLayoutParams(p);
        }
    }
}
