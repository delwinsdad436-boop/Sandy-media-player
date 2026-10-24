package com.talacore.talatv;

/**
 * Lead arithmetic for the cooker (v0.6.0). James's model, as measured:
 * not real-time — LEAD-time. The viewer waits a bit; cooking runs
 * ahead of the playhead; the GO SIGN lights when the cooked lead is
 * real. Every number this class produces is either measured on this
 * device (per-chunk wall times the cooker itself records) or plain
 * arithmetic on measured numbers — never a promise dressed as one.
 *
 * The bench numbers this must reproduce when fed bench inputs
 * (PHONE-BENCH-2026-10-17.md): base subtitle path cooks 600 s of
 * anime in 88.8 s of wall (6.76x); chunks of 240 s land every ~35.5 s
 * of wall; a 5-minute wait therefore starts the viewer with the whole
 * episode's subtitles already cooked.
 *
 * Pure logic; harness-driven against the bench's chunk times.
 */
public final class LeadMath {

    /** The cooker works in 240 s chunks — load-bearing (bench). */
    public static final double CHUNK_SEC = 240.0;

    private LeadMath() {}

    /**
     * How much finished translation must sit ahead of the playhead
     * before the go sign may light: one full chunk for a long title,
     * half the title for a short one (never under 30 s, never more
     * than the title holds).
     */
    public static double goThresholdSec(double titleDurSec) {
        if (titleDurSec <= 0) return CHUNK_SEC;
        if (titleDurSec <= CHUNK_SEC * 2) {
            return Math.min(titleDurSec, Math.max(30.0, titleDurSec / 2.0));
        }
        return CHUNK_SEC;
    }

    /** Lead = finished seconds minus watched seconds. Negative = starved. */
    public static double leadSec(double cookedSec, double playedSec) {
        return cookedSec - playedSec;
    }

    public static boolean isGo(double cookedSec, double playedSec, double titleDurSec) {
        return leadSec(cookedSec, playedSec) >= goThresholdSec(titleDurSec);
    }

    /** Playback has reached the cooking edge before the title is done. */
    public static boolean isStarved(double cookedSec, double playedSec, double titleDurSec) {
        return playedSec > cookedSec && cookedSec < titleDurSec;
    }

    /** After a starve, this much rebuilt lead resumes playback. */
    public static final double RESUME_LEAD_SEC = 30.0;

    /**
     * This device's measured cook rate: finished seconds per wall
     * second, accumulated over the chunks it has actually cooked.
     */
    public static final class RateTracker {
        private double cookedTotal = 0.0;
        private double wallTotal = 0.0;
        private int chunks = 0;

        public void recordChunk(double cookedSec, double wallSec) {
            if (cookedSec <= 0 || wallSec <= 0) return;
            cookedTotal += cookedSec;
            wallTotal += wallSec;
            chunks++;
        }

        /** Finished-seconds per wall-second; 0 until a chunk lands. */
        public double rate() {
            return wallTotal <= 0 ? 0.0 : cookedTotal / wallTotal;
        }

        public int chunks() { return chunks; }

        /**
         * Wall seconds until the lead reaches the go threshold at the
         * measured rate, assuming playback hasn't started. -1 when no
         * chunk has landed yet (the rate is honestly unknown).
         */
        public double etaToGoSec(double leadSec, double titleDurSec) {
            double r = rate();
            if (r <= 0) return -1;
            double need = goThresholdSec(titleDurSec) - leadSec;
            return need <= 0 ? 0 : need / r;
        }

        /**
         * Wall seconds until the cooking edge is reached while riding
         * the lead (rate > 1 means never, on current measurements).
         * -1 = will not starve at the measured rate.
         */
        public double etaToStarveSec(double leadSec) {
            double r = rate();
            if (r <= 0 || r >= 1.0) return -1;
            return leadSec / (1.0 - r);
        }
    }

    /** "2:05" for a lead readout. */
    public static String formatLead(double sec) {
        long s = Math.max(0, Math.round(sec));
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }
}
