package com.heracaresolutions.kiosk;

/**
 * When a downloaded update may install. Plain Java (no Android) so it can be tested off-device.
 * All times are SystemClock.elapsedRealtime() milliseconds, which keep counting while the screen is off.
 */
final class ChargeWait {
    /** The tablet must have been on charge without a break for this long. */
    static final long CHARGING_FOR_MS = 10 * 60_000L;
    /** …and nobody has touched the screen for this long, so nobody loses what they were typing. */
    static final long QUIET_FOR_MS = 2 * 60_000L;

    private ChargeWait() {
    }

    /**
     * The new "charging since" to store.
     *
     * @param stored what was stored (0 = not charging)
     * @return 0 when unplugged; otherwise the stored time, or now if there wasn't a sensible one
     */
    static long chargingSince(long stored, boolean pluggedIn, long now) {
        if (!pluggedIn) return 0;
        if (stored <= 0 || stored > now) return now;
        return stored;
    }

    /** True when an update can install now. */
    static boolean ready(long chargingSince, boolean pluggedIn, long lastTouch, long now) {
        return pluggedIn
                && chargingSince > 0
                && now - chargingSince >= CHARGING_FOR_MS
                && (lastTouch <= 0 || now - lastTouch >= QUIET_FOR_MS);
    }

    /** How long until {@link #ready} could next become true while still plugged in (0 = now). */
    static long waitMs(long chargingSince, long lastTouch, long now) {
        long chargeLeft = chargingSince + CHARGING_FOR_MS - now;
        long quietLeft = lastTouch <= 0 ? 0 : lastTouch + QUIET_FOR_MS - now;
        return Math.max(0, Math.max(chargeLeft, quietLeft));
    }
}
