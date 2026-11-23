package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Syntax TV's "Prova ora" where the system screensaver cannot be started on request (Fire OS has no
 * Somnambulator): the same scene as the screensaver, full screen, until any key.
 */
public class ScenePreviewActivity extends Activity {
    private DreamStage stage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            stage = new DreamStage(this);
            setContentView(stage.root);
        } catch (RuntimeException e) {
            // A scene that cannot be built must not take Syntax TV down with it.
            Log.w("TvHome.Screensaver", "scene preview failed: " + e.getMessage());
            finish();
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stage != null) stage.start();
    }

    @Override
    protected void onPause() {
        if (stage != null) stage.stop();
        super.onPause();
        finish();   // like the screensaver, never left behind in the back stack
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any key ends it, as it ends the screensaver; the key is not passed on.
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }
}
