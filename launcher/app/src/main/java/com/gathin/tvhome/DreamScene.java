package com.gathin.tvhome;

/** One scene of the screensaver (config.xml dream_scene): ClockScene or IssScene. */
interface DreamScene {
    /** The screensaver is showing: start ticking. */
    void start();

    /** The screensaver is gone: stop every timer and any work in flight. */
    void stop();
}
