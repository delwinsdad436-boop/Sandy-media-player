package com.talacore.talatv;

/**
 * The Translator's cycle guard (v0.20.0, item 11) — a pure phase
 * machine for ONE conversation door, built so a self-sustaining
 * speak/listen loop is STRUCTURALLY impossible, whichever
 * micro-mechanism tried to start one in the field (Sandy's own
 * TTS output re-entering the recognizer, the recognition service
 * re-delivering one take's result, or the speaker re-speaking
 * one queue item).
 *
 * Phases and the ONLY transitions between them:
 *   IDLE     --tryBeginCapture()--> CAPTURING   (spoken turns)
 *   IDLE     --tryBeginWork()-----> WORKING     (typed turns)
 *   CAPTURING --captureFinished()-> WORKING
 *   WORKING  --turnRendered(true)-> SPEAKING
 *   WORKING  --turnRendered(false)> IDLE
 *   SPEAKING --speechFinished()---> IDLE
 *   ANY      --fail()-------------> IDLE
 *
 * A capture can therefore never start while the door is working
 * or speaking: the ear and the voice can never be live in the
 * same phase, so Sandy's own spoken output has no open ear to
 * re-enter through this door's machinery.
 *
 * Capture sequence: every ACCEPTED capture takes the next
 * monotonic sequence number (1, 2, 3, ...; never reused, not
 * even by reset()). A final result is accepted only for the
 * CURRENT capture's seq and only once (acceptFinal) — a
 * double-delivered onResults/onError for one take (EarCapture
 * delivers both through the same onFinal) can produce at most
 * one turn. fail() invalidates the live capture, so a final
 * that arrives late for a stopped take is refused too.
 *
 * Echo rejection: the guard remembers the last completed turn's
 * heard + rendered text (normalized with MtLoopGuard.normalize).
 * isEcho(candidate) is true only when the candidate's normalized
 * form EQUALS the last turn's normalized RENDERED text — the
 * "next speaker" saying, word for word, exactly what Sandy just
 * spoke aloud is Sandy's own voice coming back through the mic,
 * not a person. Equality with the last HEARD text alone is NOT
 * an echo (a person may legitimately repeat their own word).
 * An echo is never recorded as a turn and never passes the
 * floor; the door returns to IDLE (the activity calls fail()).
 *
 * No Android imports: the harness drives this class directly.
 * All methods are synchronized so a callback arriving off the
 * main thread can never tear the phase.
 */
public final class TranslatorCycleGuard {

    public enum Phase { IDLE, CAPTURING, WORKING, SPEAKING }

    private Phase phase = Phase.IDLE;

    /** Monotonic capture counter; the seq of the most recent
     *  accepted capture. 0 = no capture accepted yet. */
    private int captureSeq = 0;
    /** True while a capture with the current seq may still
     *  deliver its one accepted final. Cleared by acceptFinal,
     *  fail() and reset(). */
    private boolean finalPending = false;
    private boolean finalAccepted = false;

    private String lastHeardNorm = "";
    private String lastRenderedNorm = "";

    public synchronized Phase phase() { return phase; }

    public synchronized boolean isIdle() { return phase == Phase.IDLE; }

    /** The seq of the most recent accepted capture (0 if none). */
    public synchronized int currentCaptureSeq() { return captureSeq; }

    // ------------------------------------------------ capture

    /**
     * Begin one capture. Succeeds ONLY from IDLE; on success the
     * door is CAPTURING and the capture owns the next monotonic
     * seq (read it back with currentCaptureSeq()). Refused —
     * with no state change — while CAPTURING, WORKING or
     * SPEAKING.
     */
    public synchronized boolean tryBeginCapture() {
        if (phase != Phase.IDLE) return false;
        phase = Phase.CAPTURING;
        captureSeq++;
        finalPending = true;
        finalAccepted = false;
        return true;
    }

    /**
     * Begin one typed turn's work. Succeeds ONLY from IDLE;
     * typed turns take no seq (no ear is involved).
     */
    public synchronized boolean tryBeginWork() {
        if (phase != Phase.IDLE) return false;
        phase = Phase.WORKING;
        return true;
    }

    /**
     * Accept a final result for a capture: true exactly once,
     * only for the CURRENT seq, only while that capture is still
     * live (CAPTURING, final pending, none accepted yet, and not
     * invalidated by fail()). Every later, stale, stopped-take or
     * wrong-seq delivery returns false and changes nothing.
     */
    public synchronized boolean acceptFinal(int seq) {
        if (phase != Phase.CAPTURING) return false;
        if (!finalPending || finalAccepted) return false;
        if (seq <= 0 || seq != captureSeq) return false;
        finalAccepted = true;
        finalPending = false;
        return true;
    }

    /** CAPTURING -> WORKING: the accepted take is over and its
     *  words are being rendered. No-op (false) from any other
     *  phase. */
    public synchronized boolean captureFinished() {
        if (phase != Phase.CAPTURING) return false;
        phase = Phase.WORKING;
        return true;
    }

    // ------------------------------------------------ the turn

    /**
     * WORKING -> SPEAKING when the rendering will be spoken,
     * WORKING -> IDLE when it will not (text mode, or no voice
     * was resolved for it). No-op (false) from any other phase.
     */
    public synchronized boolean turnRendered(boolean willSpeak) {
        if (phase != Phase.WORKING) return false;
        phase = willSpeak ? Phase.SPEAKING : Phase.IDLE;
        return true;
    }

    /**
     * As turnRendered(boolean), and also records the completed
     * turn's heard + rendered text as the echo signature for the
     * next capture.
     */
    public synchronized boolean turnRendered(boolean willSpeak,
                                             String heard, String rendered) {
        rememberTurn(heard, rendered);
        return turnRendered(willSpeak);
    }

    /** Record a completed turn's texts (normalized) for the
     *  echo check. The activity calls this through the
     *  turnRendered overload; it is separate so the harness can
     *  pin the echo rule without driving the phases. */
    public synchronized void rememberTurn(String heard, String rendered) {
        lastHeardNorm = MtLoopGuard.normalize(heard);
        lastRenderedNorm = MtLoopGuard.normalize(rendered);
    }

    /**
     * True when the candidate is Sandy's own voice returning:
     * its normalized form is non-empty and equals the last
     * completed turn's normalized RENDERED text exactly.
     * Equality with the last HEARD text alone is NOT an echo.
     */
    public synchronized boolean isEcho(String candidateHeard) {
        String c = MtLoopGuard.normalize(candidateHeard);
        if (c.isEmpty() || lastRenderedNorm.isEmpty()) return false;
        return c.equals(lastRenderedNorm);
    }

    /** SPEAKING -> IDLE: the voice has drained (or failed out
     *  and drained). Idempotent — a no-op (false) from any other
     *  phase, so a late or duplicate drain signal is harmless. */
    public synchronized boolean speechFinished() {
        if (phase != Phase.SPEAKING) return false;
        phase = Phase.IDLE;
        return true;
    }

    // ------------------------------------------------ escape

    /** Any phase -> IDLE. Every failure path returns here: the
     *  machine can never wedge. Also invalidates the live
     *  capture, so a stopped take's late final is refused by
     *  acceptFinal. The seq counter and the echo memory are
     *  kept (seqs stay monotonic; the last thing Sandy spoke
     *  stays the echo signature). */
    public synchronized void fail() {
        phase = Phase.IDLE;
        finalPending = false;
        finalAccepted = false;
    }

    /** Full reset for re-entry: IDLE, no live capture, and the
     *  echo memory cleared. The seq counter is NOT reset —
     *  sequence numbers are monotonic for the life of the guard. */
    public synchronized void reset() {
        fail();
        lastHeardNorm = "";
        lastRenderedNorm = "";
    }
}
