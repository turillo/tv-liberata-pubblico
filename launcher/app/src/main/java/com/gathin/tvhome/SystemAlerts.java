package com.gathin.tvhome;

/**
 * When Home shows a small system alert: storage under 10% free, RAM available under 10% for several checks in a
 * row (one check a minute, so a moment of pressure while an app starts does not count), or a source failing for more
 * than a day. The same alert waits {@link #REPEAT_MS} before it shows again. Plain logic, for the JVM tests.
 */
final class SystemAlerts {

    static final int LOW_PERCENT = 10;
    /** Minute checks in a row with RAM under the threshold before it counts as "stable". */
    static final int RAM_STREAK = 5;
    static final long REPEAT_MS = 6 * ServiceHealth.HOUR;

    private SystemAlerts() {
    }

    /** Under 10% free; false when the sizes are unknown. */
    static boolean low(long free, long total) {
        return total > 0 && free >= 0 && free * 100 < total * LOW_PERCENT;
    }

    /** Counts the minute checks in a row with little RAM; {@link #low()} once there are enough. */
    static final class RamWatch {
        private int streak;

        void add(long available, long total) {
            if (SystemAlerts.low(available, total)) streak++;
            else streak = 0;
        }

        boolean low() {
            return streak >= RAM_STREAK;
        }
    }

    /** An alert last shown at {@code lastShown} may show again. */
    static boolean due(long lastShown, long now) {
        return lastShown <= 0 || now - lastShown >= REPEAT_MS || now < lastShown;
    }
}
