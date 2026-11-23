package com.gathin.tvhome;

/**
 * Space the screensaver keeps free at the bottom of the screen, in px: the news ticker's height while it shows,
 * otherwise 0. Scenes place their bottom-corner panels (and the drifting clock) above it.
 */
final class DreamInset {

    static volatile int bottom;

    private DreamInset() {
    }
}
