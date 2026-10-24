package com.talacore.talatv;

/**
 * Estimates integrity (v0.12.1, James's law): any number Sandy
 * prints about time must be a number somebody can trust.
 *
 *  - MEASURED BASIS ONLY: an estimate is derived from rates this
 *    device actually observed (its own throughput/fetch history).
 *    No basis → NO number: callers print "still working — no honest
 *    estimate yet" instead of a comforting invention.
 *  - ONE MODEST PAD: the stated figure carries a single ~15% pad,
 *    so the time it names means FINISHED — a person would rather
 *    come back early than come back to another 30 minutes. Never
 *    padded twice, never inflated past the buffer.
 *  - LABELED: callers always present the number as an estimate.
 *  - PRECISION EXCEPTION: moments that need an exact time (a timer
 *    fires at 8:00 PM) never pass through here — exact stays exact.
 *
 * Pure math so the harness pins the law, not just the intent.
 */
public final class EstimateMath {

    private EstimateMath() {}

    /** The one pad: +1/6 (≈16.7%), applied exactly once. */
    public static long pad(long measuredMs) {
        if (measuredMs <= 0) return measuredMs;
        return measuredMs + measuredMs / 6;
    }

    /**
     * Estimated milliseconds for {@code bytesRemaining} at a
     * MEASURED {@code bytesPerSecond}, padded once. -1 when there
     * is no measured basis (rate unknown) — the caller must then
     * print no number at all.
     */
    public static long estimateMs(long bytesRemaining, double bytesPerSecond) {
        if (bytesPerSecond <= 0 || bytesRemaining < 0) return -1;
        long raw = (long) Math.ceil(bytesRemaining / bytesPerSecond * 1000.0);
        return pad(raw);
    }

    /** "about 45 sec" / "about 12 min" / "about 1 h 5 min". */
    public static String formatDuration(long ms) {
        if (ms < 0) return "";
        long sec = (ms + 999) / 1000;
        if (sec < 90) return "about " + Math.max(1, sec) + " sec";
        long min = (sec + 59) / 60;
        if (min < 90) return "about " + min + " min";
        long h = min / 60;
        long m = min % 60;
        return m == 0 ? "about " + h + " h"
                : "about " + h + " h " + m + " min";
    }
}
