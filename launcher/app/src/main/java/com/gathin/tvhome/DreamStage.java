package com.gathin.tvhome;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.List;
import java.util.Random;

/**
 * What the screensaver shows: the scene from Syntax TV's settings, the ANSA band and, in Christmas mode, snow.
 * Built the same way for the system screensaver (ClockDream) and for ScenePreviewActivity, which plays it as a
 * full-screen activity where the TV cannot start the system screensaver on request (Fire OS has no Somnambulator).
 */
final class DreamStage {
    final FrameLayout root;
    private final Context context;
    private final DreamScene scene;
    private NewsTicker ticker;
    private SnowView snow;
    private boolean running;

    DreamStage(Context context) {
        this.context = context;
        // The ticker goes first, so the space it takes is known before the scene places its panels.
        root = new FrameLayout(context);
        NewsTickerView band = null;
        DreamInset.bottom = 0;
        // Syntax TV's settings, read now: a change on the TV applies the next time the screensaver starts.
        SyntaxSettings settings = SyntaxPrefs.get(context);
        if (settings.ticker()) {
            band = new NewsTickerView(context, NewsTicker.label(context, settings.sections()), settings.tickerSpeedDp(),
                    SyntaxSettings.bandFraction(settings.tickerSize()));
            ticker = new NewsTicker(context, band, settings.sections(),
                    (visible, height) -> DreamInset.bottom = height);   // panels stay above the band
        }
        // One ticked scene always shows; several take turns at random.
        List<String> scenes = settings.dreamCandidates();   // the Christmas scenes too, with the mode on
        String name = scenes.get(new Random().nextInt(scenes.size()));
        // Syntax TV's "try this scene": once, then back to the rotation.
        String asked = ClockDream.tryScene;
        ClockDream.tryScene = null;
        if (asked != null) name = asked;
        View content;
        FrameLayout xmasRoot = Christmas.SCENES.contains(name) ? new FrameLayout(context) : null;
        DreamScene xmas = xmasRoot == null ? null : ChristmasScenes.create(context, name, xmasRoot);
        if (xmas != null) {
            content = xmasRoot;
            scene = xmas;
        } else if (Config.SCENE_ISS.equals(name)) {
            IssView view = new IssView(context);
            content = view;
            scene = new IssScene(context, view);
        } else if (Config.SCENE_SUN.equals(name)) {
            SolarView view = new SolarView(context);
            content = view;
            scene = new SolarScene(context, view);
        } else if (Config.SCENE_EARTH.equals(name)) {
            EarthView view = new EarthView(context);
            content = view;
            scene = new EarthScene(context, view);
        } else if (Config.SCENE_MOON.equals(name)) {
            EarthView view = new EarthView(context);   // the same round body over stars
            content = view;
            scene = new MoonScene(context, view);
        } else if (Config.SCENE_SPACE.equals(name)) {
            SpaceView view = new SpaceView(context);
            content = view;
            scene = new SpaceScene(context, view);
        } else if (Config.SCENE_FAVORITES.equals(name)) {
            FrameLayout frame = new FrameLayout(context);
            content = frame;
            scene = new FavoritesScene(context, frame);
        } else if (Config.SCENE_LIVE.equals(name)) {
            LiveView view = new LiveView(context);
            content = view;
            scene = new LiveScene(context, view);
        } else {
            content = LayoutInflater.from(context).inflate(R.layout.dream_clock, root, false);
            scene = new ClockScene(content);
        }
        root.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Christmas mode: snow over the scene too (the snowy Christmas scenes have their own).
        if (settings.xmas(SyntaxSettings.XMAS_SNOW_DREAM) && !ChristmasScenes.hasSnow(name)) {
            snow = new SnowView(context, 60, 1.2f);
            root.addView(snow, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (band != null) {
            root.addView(band, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, band.bandHeight(),
                    Gravity.BOTTOM));
        }
    }

    void start() {
        if (running) return;
        running = true;
        scene.start();
        if (ticker != null) ticker.start();
        if (snow != null) snow.start();
    }

    void stop() {
        running = false;
        scene.stop();
        if (ticker != null) ticker.stop();
        if (snow != null) snow.stop();
    }
}
