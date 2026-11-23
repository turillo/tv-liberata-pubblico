package com.gathin.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver. The scene comes from Syntax TV's settings (by default config.xml dream_scene, or a theme's
 * config.json; several ticked scenes take turns at random): the launcher's thin
 * clock and date over a slow ambient glow (ClockScene, no network), the ISS live over a world map (IssScene),
 * NASA SDO's latest pictures of the Sun (SolarScene), NASA EPIC's photos of the whole Earth (EarthScene), the
 * Moon tonight (MoonScene), ESA/Webb and ESA/Hubble images (SpaceScene), Meteosat live over Europe and Africa
 * (LiveScene), or the clock, the ISS, the Moon, Webb and Hubble or Meteosat at random each time. Over any of
 * them, the ANSA news ticker along the bottom (config.xml news_ticker). DreamStage builds all of it, the same
 * way ScenePreviewActivity does.
 */
public class ClockDream extends DreamService {

    private DreamStage stage;
    /** Set by Syntax TV to show one scene next time (Natale, hold OK on a scene). */
    static volatile String tryScene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        stage = new DreamStage(this);
        setContentView(stage.root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        stage.start();
    }

    @Override
    public void onDreamingStopped() {
        stage.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (stage != null) stage.stop();
        super.onDetachedFromWindow();
    }
}
