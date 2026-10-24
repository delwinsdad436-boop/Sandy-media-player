package com.talacore.talatv;

/**
 * Playback continuity — the verdict law for a stream that is
 * already PLAYING (v0.14.1, James's fifteen-second window +
 * Addenda 1–4). Tune-in failures live under the old law
 * (probe → words → the viewer's auto-skip choice); this class
 * governs only what happens AFTER a channel reached READY and
 * played — where the old code had exactly one move: stop, probe
 * the playlist, and skip. That move conflated four different
 * realities, so this law separates them first (Addendum 1:
 * classify-and-respond, never "make sources behave"):
 *
 *   SEGMENT_REFUSED  the playlist answers but the video pieces
 *                    are refused/reset on every door tried —
 *                    token-rot, CDN segment gates, split doors.
 *   PLAYLIST_GONE    the playlist itself is terminally gone or
 *                    refused across the ladder: the source
 *                    ENDED this stream.
 *   PLAYLIST_STUCK   the playlist answers, politely and forever,
 *                    but its window never advances — the source
 *                    stopped sending new video.
 *   NO_ANSWER        nothing answered anywhere on the ladder.
 *
 * And the Reconnect Doctrine over all of them (Addenda 2 + 4):
 * a live connection is MAINTAINED, not assumed. A reconnect of
 * a playing stream is MAINTENANCE, not failure — it carries no
 * strike and no attempt cap while the source keeps answering
 * at any layer ("however many times"). A verdict — advancing
 * away from the channel — is earned only when BOTH the ladder
 * is exhausted AND the maintenance runway (RUNWAY_MS, one full
 * Hydra budget) has been spent trying. A stuck playlist is
 * never a verdict while its door still answers: the words say
 * "stalled", the channel stays, the maintenance continues.
 *
 * Pure policy: no Android, no sockets. PlayerActivity feeds it
 * facts; the harness pins the law.
 */
public final class PlaybackContinuity {

    // ------------------------------------------------ families

    public static final int SEGMENT_REFUSED = 1;
    public static final int PLAYLIST_GONE = 2;
    public static final int PLAYLIST_STUCK = 3;
    public static final int NO_ANSWER = 4;

    // ------------------------------------------------ decisions

    /** Maintenance: walk, re-prepare, resume — no strike. */
    public static final int RECONNECT = 1;
    /** Words ("stream stalled"), stay on the channel, keep maintaining. */
    public static final int HOLD_STALL = 2;
    /** Verdict: the stream is over; advancing is correct. */
    public static final int ADVANCE = 3;

    /**
     * The maintenance runway after a mid-play failure: a full
     * Hydra budget (~30 s) must be spent trying before any
     * verdict may be spoken. Before the failure, the same
     * figure is what the live buffer is sized to cover
     * (TimeshiftMath.RUNWAY_MIN_BUFFER_MS).
     */
    public static final long RUNWAY_MS = 30_000;

    /** A playing stream stuck in BUFFERING this long, with no
     *  error spoken, is a stall: maintenance starts. */
    public static final long STALL_TRIGGER_MS = 20_000;

    /** Maintenance re-walk cadence: patient, never a storm. */
    public static final long BACKOFF_START_MS = 2_000;
    public static final long BACKOFF_MAX_MS = 10_000;

    private PlaybackContinuity() {}

    public static String familyName(int family) {
        switch (family) {
            case SEGMENT_REFUSED: return "segments-refused";
            case PLAYLIST_GONE: return "source-ended";
            case PLAYLIST_STUCK: return "playlist-stuck";
            default: return "no-answer";
        }
    }

    /**
     * The law itself. {@code ladderExhausted} = one full Hydra
     * cycle in which no head served a segment; {@code elapsedMs}
     * = how long maintenance has been running since the failure.
     */
    public static int decide(int family, boolean ladderExhausted,
                             long elapsedMs) {
        // A door that still answers is never a verdict — the
        // source may resume, and restreamers do. Hold, worded.
        if (family == PLAYLIST_STUCK) return HOLD_STALL;
        if (!ladderExhausted) return RECONNECT;
        if (elapsedMs < RUNWAY_MS) return RECONNECT;
        return ADVANCE;
    }

    /**
     * Maintenance never strikes. Strikes (the auto-skip budget)
     * belong to tune-in failures only; a mid-play event — even
     * one that ends in a verdict — is not a strike against the
     * NEXT channel's tune-in.
     */
    public static boolean addsStrike(int decision) {
        return false;
    }

    /**
     * Classify what a completed Hydra walk proved, for a stream
     * that failed mid-play: {@code bestTier} is the best tier
     * any head reached (LinkHealth tiers), {@code bestHttp} the
     * most informative HTTP status (0 = silence).
     */
    public static int classifyWalk(int bestTier, int bestHttp) {
        if (bestTier == LinkHealth.TIER_CONNECTS) {
            // the list answers, the video doesn't — the pieces
            // were refused/reset on every door that was tried
            return SEGMENT_REFUSED;
        }
        if (bestHttp == 404 || bestHttp == 410
                || bestHttp == 401 || bestHttp == 403) {
            // the playlist door itself said no, finally
            return PLAYLIST_GONE;
        }
        return NO_ANSWER;
    }

    /**
     * Classify the raw player error before any walk: a stuck
     * playlist exception is its own family from the first
     * breath; everything else waits for the walk's evidence.
     * {@code stuckPlaylist} = the cause chain carries media3's
     * PlaylistStuckException.
     */
    public static int classifyError(boolean stuckPlaylist) {
        return stuckPlaylist ? PLAYLIST_STUCK : NO_ANSWER;
    }
}
