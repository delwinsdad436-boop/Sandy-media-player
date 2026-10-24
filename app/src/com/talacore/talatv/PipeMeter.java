package com.talacore.talatv;

import android.content.Context;

import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

/**
 * The pipe, measured (v0.12.1, James's realism cap): Sandy works
 * with the connection it actually has and SAYS what that is —
 * "if your internet is shit, it'll tell you it's shit." No heroics:
 * no upscaling claims, no retry storms, no dressing a dead pipe up
 * as a settings problem.
 *
 * Evidence, in order: (1) media3's own bandwidth meter — the
 * player's measured estimate from real playback transfers;
 * (2) Sandy's own observed transfers (guide fetches are large,
 * timed, and counted — GuideStore feeds samples here, persisted so
 * the statement survives restarts). When neither has measured
 * anything, the honest answer is NO MEASUREMENT, and every consumer
 * (posture caps, quality statements) must behave as if the pipe is
 * unknown — never invent a speed.
 *
 * Pure tier math (capTierFor / statementFor) is separated from the
 * Android sampling so the harness pins the law.
 */
public final class PipeMeter {

    private PipeMeter() {}

    private static final String PREF_KEY = "pipe_sample_bps";
    private static volatile double lastFetchBps = -1;

    // ----------------------------------------------------- sampling

    /** Feed an observed transfer (bytes over millis) as evidence. */
    public static void sample(Context ctx, long bytes, long millis) {
        if (bytes <= 0 || millis < 200) return;
        double bps = bytes * 8.0 / (millis / 1000.0);
        if (bps <= 0) return;
        lastFetchBps = bps;
        try {
            ctx.getSharedPreferences("talatv", Context.MODE_PRIVATE)
                    .edit().putLong(PREF_KEY, (long) bps).apply();
        } catch (Exception ignored) { }
    }

    /** Best measured bits-per-second, or -1: no honest measurement. */
    public static double measuredBps(Context ctx) {
        try {
            long est = DefaultBandwidthMeter.getSingletonInstance(ctx)
                    .getBitrateEstimate();
            if (est > 0) return est;
        } catch (Exception ignored) { }
        if (lastFetchBps > 0) return lastFetchBps;
        try {
            long v = ctx.getSharedPreferences("talatv", Context.MODE_PRIVATE)
                    .getLong(PREF_KEY, -1);
            if (v > 0) return v;
        } catch (Exception ignored) { }
        return -1;
    }

    // ---------------------------------------------------- pure math

    /**
     * The best quality tier (EpisodeKey scale) this pipe sustains
     * without buffer pain, at a 1.5× headroom over each tier's
     * typical bitrate. -1 when unmeasured: no cap may be claimed.
     * Tiers: 480p≈1.5 Mbps · SD≈1 · 720p≈3 · HD≈4.5 · 1080p≈6 ·
     * 2160p≈16 (typical HLS ladders, headroom included below).
     */
    public static int capTierFor(double bps) {
        if (bps <= 0) return -1;
        double mbps = bps / 1_000_000.0;
        if (mbps >= 24) return 6;   // 2160p
        if (mbps >= 9) return 5;    // 1080p
        if (mbps >= 4.5) return 4;  // 720p
        if (mbps >= 3.2) return 3;  // HD-labelled
        if (mbps >= 2.2) return 2;  // 480p
        if (mbps >= 1.2) return 1;  // SD
        return 1;
    }

    /** Coarse class for statements: 0 unknown, 1 slow, 2 medium, 3 fast. */
    public static int pipeClass(double bps) {
        if (bps <= 0) return 0;
        double mbps = bps / 1_000_000.0;
        if (mbps < 2.2) return 1;
        if (mbps < 9) return 2;
        return 3;
    }

    /** Mbps with one decimal, for the plain statement. */
    public static String mbpsText(double bps) {
        if (bps <= 0) return "";
        return String.format(java.util.Locale.US, "%.1f", bps / 1_000_000.0);
    }

    /** The quality label a tier wears (mirrors EpisodeKey's labels). */
    public static String tierLabel(int tier) {
        switch (tier) {
            case 6: return "2160p";
            case 5: return "1080p";
            case 4: return "720p";
            case 3: return "HD";
            case 2: return "480p";
            case 1: return "SD";
            default: return "unknown quality";
        }
    }
}
