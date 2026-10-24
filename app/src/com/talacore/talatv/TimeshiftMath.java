package com.talacore.talatv;

/**
 * The arithmetic of the live timeshift buffer (v0.4.0), kept pure so
 * the JVM harness can prove it. The honest framing, stated in the
 * notes and in Settings: a "minutes" setting cannot be a promise —
 * streams arrive at different bitrates — so the setting converts to a
 * byte cap at a NOMINAL rate, and the player displays the MEASURED
 * lead (buffered position minus play position), never the nominal
 * number. The other hard bound is the source itself: a live playlist
 * only ever holds its own recent window, and no cache can fetch video
 * the source has already dropped.
 */
public final class TimeshiftMath {

    /** Nominal stream rate the minutes setting is converted at: 3 Mbps. */
    public static final long NOMINAL_BITS_PER_SEC = 3_000_000L;

    // ------------------------------------ the runway (v0.14.1)
    // The Reconnect Doctrine's numbers: the live buffer is the
    // runway recovery happens under. The loader defends a
    // minimum buffer of one full Hydra walk (30 s) so a dead
    // head is found and re-walked while the picture still has
    // seconds in hand; after a rebuffer, playback resumes with
    // 10 s in hand instead of 4. The ahead cap stays RAM-sane;
    // on a live source the real ahead bound is the source's own
    // playlist window, which no setting can widen.

    /** Minimum buffer the loader defends: one Hydra budget. */
    public static final int RUNWAY_MIN_BUFFER_MS = 30_000;
    /** Buffer required before playback resumes after a rebuffer. */
    public static final int REBUFFER_RESUME_MS = 10_000;
    /** Buffer required before playback first starts. */
    public static final int START_BUFFER_MS = 1_500;
    /** Furthest ahead the player may buffer, whatever the setting. */
    public static final int MAX_AHEAD_MS = 600_000;

    public static final int[] MINUTE_CHOICES = {10, 30, 60};
    public static final int DEFAULT_MINUTES = 30;

    private TimeshiftMath() {}

    /** Disk-capacity cap for a minutes setting, at the nominal rate. */
    public static long byteCapForMinutes(int minutes) {
        if (minutes <= 0) minutes = DEFAULT_MINUTES;
        return minutes * 60L * NOMINAL_BITS_PER_SEC / 8L;
    }

    /** Clamp a stored minutes value to one of the offered choices. */
    public static int clampMinutes(int minutes) {
        int best = DEFAULT_MINUTES;
        for (int c : MINUTE_CHOICES) if (c == minutes) return c;
        return best;
    }

    /**
     * Measured lead: how much not-yet-watched video the player is
     * holding ahead of the playhead. Negative/unknown readings clamp
     * to zero — the readout never invents buffer.
     */
    public static long leadMs(long bufferedPositionMs, long currentPositionMs) {
        if (bufferedPositionMs < 0 || currentPositionMs < 0) return 0;
        return Math.max(0, bufferedPositionMs - currentPositionMs);
    }

    /**
     * How far behind the live edge the playhead sits, when the window
     * has a known end (duration). The window's end is the live edge as
     * of the last playlist refresh — close, and labeled approximate in
     * the UI copy where it matters. TIME_UNSET durations (<=0 here)
     * yield -1 = "unknown, don't show".
     */
    public static long behindLiveMs(long durationMs, long currentPositionMs) {
        if (durationMs <= 0 || currentPositionMs < 0) return -1;
        return Math.max(0, durationMs - currentPositionMs);
    }

    /** "04:12" / "1:02:03" for the lead and behind-live readouts. */
    public static String formatClock(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return h + ":" + pad(m) + ":" + pad(s);
        return pad(m) + ":" + pad(s);
    }

    private static String pad(long v) {
        return v < 10 ? "0" + v : Long.toString(v);
    }
}
