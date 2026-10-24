package com.talacore.talatv;

/**
 * The reading session as a state machine (v0.8.0) — pure Java, so
 * every transition the recliner depends on is harness-pinned.
 *
 *   IDLE     --start-->            SPEAKING
 *   SPEAKING --pause-->            PAUSED
 *   PAUSED   --resume-->           SPEAKING
 *   SPEAKING --starve-->           WAITING   (TTS/translation not ready)
 *   WAITING  --feed-->             SPEAKING
 *   WAITING  --pause-->            PAUSED    (pause while waiting is real)
 *   PAUSED   --starveStay-->       WAITING   (resume into an empty pipe)
 *   SPEAKING --finish-->           FINISHED  (last chunk done)
 *   any      --stop-->             STOPPED   (incl. the sleep stop)
 *   STOPPED/FINISHED --start-->    SPEAKING  (a new sitting)
 *
 * Illegal transitions are refused (return false), never forced —
 * the same fail-closed shape as every gate in Sandy. The session
 * tracks the current chunk and the furthest chunk heard, which is
 * what the bookmark is written from.
 */
public final class ReadingSession {

    public enum State { IDLE, SPEAKING, PAUSED, WAITING, FINISHED, STOPPED }

    private State state = State.IDLE;
    private int currentChunk = 0;
    private int furthestChunk = 0;
    private int totalChunks = 0;

    public State state() { return state; }
    public int currentChunk() { return currentChunk; }
    public int furthestChunk() { return furthestChunk; }
    public int totalChunks() { return totalChunks; }
    public boolean isSpeaking() { return state == State.SPEAKING; }
    public boolean isActive() {
        return state == State.SPEAKING || state == State.PAUSED
                || state == State.WAITING;
    }

    /** Begin (or begin again) at a chunk. Legal from IDLE/STOPPED/FINISHED. */
    public boolean start(int total, int atChunk) {
        if (state != State.IDLE && state != State.STOPPED
                && state != State.FINISHED) return false;
        if (total <= 0) return false;
        totalChunks = total;
        currentChunk = clamp(atChunk);
        furthestChunk = currentChunk;
        state = State.SPEAKING;
        return true;
    }

    /** The speaker started chunk i (utterance onStart). */
    public boolean chunkStarted(int i) {
        if (state != State.SPEAKING && state != State.WAITING) return false;
        currentChunk = clamp(i);
        if (currentChunk > furthestChunk) furthestChunk = currentChunk;
        if (state == State.WAITING) state = State.SPEAKING;
        return true;
    }

    public boolean pause() {
        if (state != State.SPEAKING && state != State.WAITING) return false;
        state = State.PAUSED;
        return true;
    }

    public boolean resume() {
        if (state != State.PAUSED) return false;
        state = State.SPEAKING;
        return true;
    }

    /** The pipe ran dry (TTS not ready / translation not cooked yet). */
    public boolean starve() {
        if (state == State.SPEAKING) { state = State.WAITING; return true; }
        if (state == State.PAUSED) return true; // stays paused; feed on resume
        return false;
    }

    /** The pipe has words again while waiting. */
    public boolean feed() {
        if (state != State.WAITING) return false;
        state = State.SPEAKING;
        return true;
    }

    /** Step to a chunk while active (or paused): position moves, state stays. */
    public boolean seek(int i) {
        if (!isActive()) return false;
        currentChunk = clamp(i);
        return true;
    }

    /** The last chunk finished speaking. */
    public boolean finish() {
        if (state != State.SPEAKING && state != State.WAITING) return false;
        state = State.FINISHED;
        return true;
    }

    /** Stop — the viewer stopped it, or sleep did. Always legal. */
    public boolean stop() {
        if (state == State.IDLE) return false;
        state = State.STOPPED;
        return true;
    }

    private int clamp(int i) {
        if (totalChunks <= 0) return 0;
        return Math.max(0, Math.min(i, totalChunks - 1));
    }
}
