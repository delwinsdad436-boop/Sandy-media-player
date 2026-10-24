package com.talacore.talatv;

/**
 * The cooker state machine (v0.6.0) — the Translate option's honest
 * heart. It owns no engines and no UI; it is the deterministic set of
 * states the family designed, and every transition is driven by a
 * measured event:
 *
 *   press Translate
 *     -> a track or cooked sidecar already exists  => READY_EXISTING
 *     -> the engine pack is not installed          => NEEDS_PACK
 *     -> pack in, but no engine bound in this app  => ENGINE_UNAVAILABLE
 *     -> otherwise                                 => COOKING
 *   COOKING: chunks land (240 s each); when the cooked lead reaches
 *            the go threshold the state becomes GO — the go sign.
 *   GO --viewer presses play--> RIDING (playback rides the lead)
 *   RIDING: playhead passes the cooking edge before the title is
 *           done => STARVED (said out loud, never spun); when the
 *           lead rebuilds past the resume mark => RIDING again.
 *   cooked == title and played == cooked           => DONE
 *   any stage fails                                => FAILED (reason kept)
 *
 * Resource honesty is a state, not an error string: NEEDS_PACK and
 * ENGINE_UNAVAILABLE are where the machine stops and says exactly
 * what is missing (UTX Law 5). Nothing in here ever fabricates a
 * cooked second: cookedSec only moves when a chunk reports in.
 *
 * Pure logic; the harness drives it with the bench's measured chunk
 * times and with synthetic starve/go sequences.
 */
public final class Cooker {

    public enum State {
        IDLE, READY_EXISTING, NEEDS_PACK, ENGINE_UNAVAILABLE,
        COOKING, GO, RIDING, STARVED, DONE, FAILED
    }

    public enum PackStatus { NOT_INSTALLED, INSTALLED }

    /** The engine seam. 0.6.0 shipped it unbound; 0.6.1 binds the
     *  ONNX engine (OnnxCookerEngine). A null or unbound engine
     *  still answers ENGINE_UNAVAILABLE truthfully. */
    public interface Engine {
        boolean isBound();
    }

    private final double titleDurSec;
    private State state = State.IDLE;
    private String failReason = "";
    private double cookedSec = 0.0;
    private double playedSec = 0.0;
    private final LeadMath.RateTracker rate = new LeadMath.RateTracker();

    public Cooker(double titleDurSec) {
        this.titleDurSec = Math.max(0, titleDurSec);
    }

    public State state() { return state; }
    public String failReason() { return failReason; }
    public double cookedSec() { return cookedSec; }
    public double playedSec() { return playedSec; }
    public double titleDurSec() { return titleDurSec; }
    public double leadSec() { return LeadMath.leadSec(cookedSec, playedSec); }
    public double rate() { return rate.rate(); }
    public boolean goLit() { return state == State.GO || state == State.RIDING
            || state == State.DONE; }

    /** Press Translate. The three honest gates, in order. */
    public State begin(boolean hasExistingTrack, PackStatus pack, Engine engine) {
        if (state != State.IDLE) return state;
        if (hasExistingTrack) {
            state = State.READY_EXISTING;
        } else if (pack != PackStatus.INSTALLED) {
            state = State.NEEDS_PACK;
        } else if (engine == null || !engine.isBound()) {
            state = State.ENGINE_UNAVAILABLE;
        } else {
            state = State.COOKING;
        }
        return state;
    }

    /** A 240 s chunk (or the title's tail) finished cooking. */
    public void onChunkCooked(double chunkCookedSec, double chunkWallSec) {
        if (state != State.COOKING && state != State.GO
                && state != State.RIDING && state != State.STARVED) return;
        cookedSec = Math.min(titleDurSec, cookedSec + Math.max(0, chunkCookedSec));
        rate.recordChunk(chunkCookedSec, chunkWallSec);
        if (state == State.COOKING && LeadMath.isGo(cookedSec, playedSec, titleDurSec)) {
            state = State.GO;
        } else if (state == State.STARVED
                && LeadMath.leadSec(cookedSec, playedSec) >= LeadMath.RESUME_LEAD_SEC) {
            state = State.RIDING;
        }
        checkDone();
    }

    /** The viewer pressed play on the translated track. */
    public void onPlayTranslated() {
        if (state == State.GO) state = State.RIDING;
    }

    /** Playback position report (the player's own clock). */
    public void onTick(double played) {
        playedSec = Math.max(0, played);
        if (state == State.RIDING
                && LeadMath.isStarved(cookedSec, playedSec, titleDurSec)) {
            state = State.STARVED;
        }
        checkDone();
    }

    private void checkDone() {
        if ((state == State.RIDING || state == State.GO || state == State.STARVED)
                && titleDurSec > 0 && cookedSec >= titleDurSec
                && playedSec >= titleDurSec - 0.5) {
            state = State.DONE;
        }
    }

    public void onFail(String reason) {
        state = State.FAILED;
        failReason = reason == null ? "" : reason;
    }

    /** Wall seconds until the go sign at the measured rate; -1 = unknown yet. */
    public double etaToGoSec() {
        return rate.etaToGoSec(leadSec(), titleDurSec);
    }
}
