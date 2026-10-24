package com.talacore.talatv;

/**
 * The live position hook's arithmetic (v0.5.0, item 8 as amended).
 *
 * Live TV has no fixed timeline: a saved "position" only means
 * something as a distance behind the live edge. While Sandy is closed
 * the edge keeps moving, so the place you left falls further behind
 * by exactly the time you were gone — and if that distance grows past
 * the window the channel still offers, the moment is gone. This class
 * is that arithmetic, kept pure so the JVM harness can pin it:
 *
 *   behindAtResume = behindWhenSaved + timeAway
 *   targetPosition = windowDuration - behindAtResume   (clamped >= 0)
 *   rolledOff      = behindAtResume > windowDuration
 */
public final class LiveResumeMath {

    public static final class Result {
        public long targetPositionMs;
        public boolean rolledOff;
        public long behindMs;
    }

    private LiveResumeMath() {}

    /**
     * @param savedBehindMs  distance behind the live edge when saved
     * @param savedAtMs      wall clock when saved
     * @param nowMs          wall clock now
     * @param windowMs       the live window the player reports now
     *                       (<= 0 or TIME_UNSET when unknown)
     */
    public static Result resolve(long savedBehindMs, long savedAtMs,
                                 long nowMs, long windowMs) {
        Result r = new Result();
        long away = Math.max(0, nowMs - savedAtMs);
        r.behindMs = Math.max(0, savedBehindMs) + away;
        if (windowMs <= 0) {
            // No window to measure against: no honest target exists.
            r.rolledOff = true;
            r.targetPositionMs = 0;
            return r;
        }
        r.rolledOff = r.behindMs > windowMs;
        r.targetPositionMs = r.rolledOff ? 0 : windowMs - r.behindMs;
        return r;
    }

    /** Distance behind live for a playing position inside a window. */
    public static long behindMs(long windowDurationMs, long positionMs) {
        if (windowDurationMs <= 0 || positionMs < 0) return 0;
        return Math.max(0, windowDurationMs - positionMs);
    }
}
