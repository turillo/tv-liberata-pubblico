package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * The "Preferite" scene full screen, whatever the system screensaver is (Syntax TV's "try" starts the system one,
 * which may be Aerial Views): hold OK on the scene in Syntax TV, Screensaver or Natale. With the Christmas mode and
 * its screensaver snow on, it snows as in the screensaver. Any key closes it.
 */
public final class FavoritesPreviewActivity extends Activity {

    private FavoritesScene scene;
    private SnowView snow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout root = new FrameLayout(this);
        FrameLayout frame = new FrameLayout(this);
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        DreamInset.bottom = 0;
        scene = new FavoritesScene(this, frame);
        if (SyntaxPrefs.get(this).xmas(SyntaxSettings.XMAS_SNOW_DREAM)) {
            snow = new SnowView(this, 60, 1.2f);
            root.addView(snow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        scene.start();
        if (snow != null) snow.start();
    }

    @Override
    protected void onStop() {
        scene.stop();
        if (snow != null) snow.stop();
        super.onStop();
        finish();   // a stopped slideshow is not resumed: the next try starts a fresh one
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }
}
