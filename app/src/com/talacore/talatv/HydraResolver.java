package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hydra's stream hand (v0.14.0): proving a playable address by
 * walking the Connection Law's ladder (see Hydra) instead of
 * probing the one published manner and giving up. Each head is
 * proven with LinkHealth's patient probe (SG-1 semantics inside
 * the head: terminal fails fast, slow retries); a head that
 * fails ADVANCES THE LADDER — a different door, not the same
 * knock again — and the heads share the ~30 s budget in order.
 * The door that answered is learned (HydraStore) and recorded
 * in the health record under BOTH its own address and the
 * published one, so the display counts (DisplayCounts) bind to
 * what Hydra actually proved.
 *
 * v0.14.1: the outcome also carries the EVIDENCE — every head
 * tried, with its HTTP status and failure kind, plus the best
 * tier any head reached — and a second walk, proveExhaustive,
 * for the player's mid-play maintenance: there the question is
 * not "the first door that answers" but "does ANY door still
 * serve the video" — a connects-only answer (the playlist
 * answers, the video doesn't) must not stop the ladder,
 * because the continuity law classifies on the full evidence.
 *
 * Blocking: call from a worker thread.
 */
public final class HydraResolver {

    private HydraResolver() {}

    /** Fires when the ladder advances: the viewer is told
     *  "Trying another way…" — simple words, no jargon. */
    public interface AdvanceListener {
        void onAdvance(int headNumber, int headCount);
    }

    /** What a resolution came back with. */
    public static final class Outcome {
        /** The address that answered (the door to play), or the
         *  published address when none did. */
        public String url;
        public int manner = Hydra.M_DIRECT;
        /** The best result: a success when any head answered. */
        public LinkHealth.Result result;
        /** Every head tried, in order, with its answer (v0.14.1). */
        public List<Hydra.Tried> tried = new ArrayList<>();
        /** The best tier any head reached (v0.14.1). */
        public int bestTier = LinkHealth.TIER_DEAD;
        public boolean ok() {
            return result != null
                    && (result.tier == LinkHealth.TIER_PLAYS
                            || result.tier == LinkHealth.TIER_CONNECTS
                            || result.slow);
        }
    }

    public static Outcome prove(Context ctx, String url, String userAgent,
                                AdvanceListener listener) {
        return walk(ctx, url, userAgent, listener, false);
    }

    /**
     * The maintenance walk: only a head proven to the SEGMENT
     * tier (PLAYS) stops the ladder — the full evidence returns
     * either way, so the caller can classify the failure family
     * (PlaybackContinuity) from what every door actually said.
     */
    public static Outcome proveExhaustive(Context ctx, String url,
                                          String userAgent,
                                          AdvanceListener listener) {
        return walk(ctx, url, userAgent, listener, true);
    }

    private static Outcome walk(Context ctx, String url, String userAgent,
                                AdvanceListener listener,
                                boolean exhaustive) {
        Outcome out = new Outcome();
        out.url = url;
        if (!LinkHealth.isRemote(url)) {
            out.result = LinkHealth.probeOnce(url, userAgent, 1000);
            out.bestTier = out.result.tier;
            return out;
        }
        Map<String, Integer> doors = HydraStore.load(ctx);
        List<Hydra.Head> ladder = Hydra.orderFor(url, doors);
        long started = System.currentTimeMillis();
        LinkHealth.Result best = null;
        Hydra.Head slowHead = null;
        LinkHealth.Result slowResult = null;
        Hydra.Head connectsHead = null;
        LinkHealth.Result connectsResult = null;
        int headNo = 0;
        for (Hydra.Head head : ladder) {
            headNo++;
            if (headNo > 1) {
                if (!Hydra.mayAdvance(started,
                        System.currentTimeMillis(), 4000)) break;
                if (listener != null) listener.onAdvance(headNo, ladder.size());
            }
            LinkHealth.Result r = LinkHealth.probe(
                    head.url, userAgent, 6000);
            HealthStore.record(ctx, head.url, r);
            out.tried.add(new Hydra.Tried(head.manner, r.httpCode,
                    FailureWords.kindFor(r)));
            if (r.tier > out.bestTier) out.bestTier = r.tier;
            boolean success = r.tier == LinkHealth.TIER_PLAYS
                    || (!exhaustive && (r.tier == LinkHealth.TIER_CONNECTS
                            || r.slow));
            if (success) {
                out.url = head.url;
                out.manner = head.manner;
                out.result = r;
                if (!head.url.equals(url)) {
                    HealthStore.record(ctx, url, r);
                }
                Net.noteLearned(Hydra.hostOf(url), head.manner);
                return out;
            }
            if (exhaustive) {
                // a slow head is worth re-preparing on (the
                // tune-in law's judgment: slow is not dead); a
                // connects-only head is evidence, not a door
                if (r.slow && slowResult == null) {
                    slowHead = head;
                    slowResult = r;
                }
                if (r.tier == LinkHealth.TIER_CONNECTS
                        && connectsResult == null) {
                    connectsHead = head;
                    connectsResult = r;
                }
            }
            // a proven-dead head: keep the most informative word
            if (best == null
                    || (best.httpCode == 0 && r.httpCode > 0)) {
                best = r;
            }
            // slow-with-no-answer already returned as success
            // above (r.slow), so reaching here means terminal —
            // advance the ladder instead of ending the attempt.
        }
        if (exhaustive && slowResult != null) {
            out.url = slowHead.url;
            out.manner = slowHead.manner;
            out.result = slowResult;
            if (!slowHead.url.equals(url)) {
                HealthStore.record(ctx, url, slowResult);
            }
            Net.noteLearned(Hydra.hostOf(url), slowHead.manner);
            return out;
        }
        if (exhaustive && connectsResult != null) {
            out.result = connectsResult;
            return out;
        }
        out.result = best != null ? best
                : LinkHealth.probeOnce(url, userAgent, 1000);
        if (out.result != null && out.result.tier > out.bestTier) {
            out.bestTier = out.result.tier;
        }
        return out;
    }
}
