package com.gathin.tvhome;

/**
 * TV Liberata 1.0: what is in this version. The parts below stay in the code, switched off, ready to come back in a
 * later version by turning their flag on (and shipping a new release). One app for every device: nothing here
 * depends on whose TV it is; the owner's data is in the device configuration (DeviceConfig).
 */
final class Features {

    /** Rooms (living room, kitchen, hobby room, attic) and the apps row position. */
    static final boolean PROFILES = false;
    /** The kitchen information panel. */
    static final boolean KITCHEN_PANEL = false;
    /** The recycling calendar (AMA). */
    static final boolean RECYCLING = false;
    /** Unsplash among the wallpaper sources (it needs a key, from the device configuration). */
    static final boolean UNSPLASH = false;
    /** Screensaver scenes ISS, Sole adesso, Terra adesso, Luna adesso, Webb e Hubble, Terra in diretta. */
    static final boolean SPACE_SCENES = false;
    /** The ANSA news ticker (Home and screensaver), and Il Post with it. */
    static final boolean NEWS_TICKER = false;
    /** Christmas mode (wallpapers, snow, scenes, countdown). */
    static final boolean CHRISTMAS = false;
    /** Notice board on Home. */
    static final boolean BOARD = false;
    /** Devices' status written to a Google Sheet. */
    static final boolean DEVICE_SHEET = false;
    /** Pulizia avanzata's full list (in 1.0 only "Ripristina tutto"). */
    static final boolean ADVANCED_CLEANUP = false;

    private Features() {
    }
}
