package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auto-play resolution (v0.12.1, James's auto-play doctrine): tap a
 * tile or an episode and Sandy plays it — she picks the source, in
 * the viewer's already-chosen language, under the viewer's quality
 * POSTURE. The manual source list stays one Back away; auto-pick is
 * the default road, not a wall.
 *
 * Postures (Settings, James's shape):
 *  LOW      — fastest start wins: the lowest adequate WORKING
 *             source first; it plays immediately and smoothly, and
 *             its quality is what it is (labeled, never dressed).
 *  BALANCED — the happy medium: the best quality the connection
 *             can sustain without buffer pain (a measured pipe cap
 *             from PipeMeter), over a 480p floor.
 *  ULTRA    — the highest quality available, period; it may cost
 *             buffer time, and the player says so instead of
 *             silently downgrading.
 *
 * Rules that hold in every posture: a link already filed DEAD
 * (HealthStore verdict) is never picked; language-matching sources
 * form the pool when any exist (the pick is labeled when none
 * matched); when no working source meets the posture's floor, the
 * result is an OFFER — the best working source below the floor,
 * presented to the person — never a silent below-floor serve and
 * never a dead end. Quality evidence is the entry's own tokens
 * (SourceFinder/EpisodeKey tiers: 2160=6, 1080=5, 720=4, HD=3,
 * 480=2, SD=1, unknown=0, CAM=-1); unknown stays unknown.
 *
 * Health tiers ride LinkHealth's scale: DEAD=0, CONNECTS=1,
 * PLAYS=2; an unprobed source is UNKNOWN (-1 here) and ranks
 * between proven-dead (excluded) and proven.
 */
public final class AutoPlay {

    private AutoPlay() {}

    public enum Posture { LOW, BALANCED, ULTRA }

    public static final int HEALTH_UNKNOWN = -1;
    /** The internal floor for BALANCED/ULTRA: 480p (tier 2). */
    public static final int FLOOR_TIER = 2;

    public static Posture parsePosture(String pref) {
        if ("low".equals(pref)) return Posture.LOW;
        if ("ultra".equals(pref)) return Posture.ULTRA;
        return Posture.BALANCED;
    }

    public static String posturePref(Posture p) {
        switch (p) {
            case LOW: return "low";
            case ULTRA: return "ultra";
            default: return "balanced";
        }
    }

    /** One candidate: a source plus its evidence. */
    /** The posture's name as the viewer hears it ("Low First"). */
    public static String postureLabel(Posture p) {
        switch (p) {
            case LOW: return "Low First";
            case ULTRA: return "Ultra";
            default: return "Balanced";
        }
    }

    public static final class Cand {
        public final SourceFinder.Source source;
        public final int health;      // LinkHealth tier, or HEALTH_UNKNOWN
        public final boolean langMatch;
        public Cand(SourceFinder.Source s, int health, boolean langMatch) {
            this.source = s; this.health = health; this.langMatch = langMatch;
        }
    }

    public static final class Pick {
        public SourceFinder.Source source;   // null: nothing workable at all
        public boolean offer;                // true: below the floor — ask first
        public boolean langMatched;          // the pool was language-matched
        public int capTier = -1;             // Balanced's pipe cap (for the label)
    }

    /**
     * Pick for a posture. {@code pipeCapTier} is PipeMeter's honest
     * cap for BALANCED (the best tier the measured pipe sustains);
     * pass 6 when no measurement exists (no cap claimed).
     */
    public static Pick pick(List<Cand> cands, Posture posture, int pipeCapTier) {
        Pick out = new Pick();
        if (cands == null) return out;
        List<Cand> alive = new ArrayList<>();
        for (Cand c : cands) {
            if (c.health != LinkHealth.TIER_DEAD) alive.add(c);
        }
        if (alive.isEmpty()) return out;
        List<Cand> pool = new ArrayList<>();
        for (Cand c : alive) if (c.langMatch) pool.add(c);
        out.langMatched = !pool.isEmpty();
        if (pool.isEmpty()) pool = alive;

        switch (posture) {
            case LOW: {
                pool.sort(AutoPlay::lowOrder);
                out.source = pool.get(0).source;
                return out;
            }
            case ULTRA: {
                pool.sort(AutoPlay::highOrder);
                Cand best = pool.get(0);
                out.source = best.source;
                out.offer = best.source.qualityTier < FLOOR_TIER;
                return out;
            }
            case BALANCED:
            default: {
                int cap = pipeCapTier < 0 ? 6 : Math.min(pipeCapTier, 6);
                out.capTier = cap;
                List<Cand> atFloor = new ArrayList<>();
                for (Cand c : pool) {
                    if (c.source.qualityTier >= FLOOR_TIER) atFloor.add(c);
                }
                if (atFloor.isEmpty()) {
                    pool.sort(AutoPlay::highOrder);
                    out.source = pool.get(0).source;
                    out.offer = true;
                    return out;
                }
                // The measured pipe is a real cap, not a sort hint:
                // the best source the pipe carries clean wins; a
                // source above the cap is taken only when nothing
                // at or under the cap meets the floor (the player
                // can still step down inside an HLS ladder, but a
                // fixed file above the pipe's tier is buffer pain
                // Sandy chose, and Balanced does not choose pain).
                List<Cand> within = new ArrayList<>();
                for (Cand c : atFloor) {
                    if (c.source.qualityTier <= cap) within.add(c);
                }
                List<Cand> ranked = within.isEmpty() ? atFloor : within;
                ranked.sort(AutoPlay::highOrder);
                out.source = ranked.get(0).source;
                return out;
            }
        }
    }

    /** Build candidates from sources + the health book + language wants. */
    public static List<Cand> candidates(List<SourceFinder.Source> sources,
                                        Map<String, Integer> healthByUrl,
                                        String wantFacet, String wantMarc) {
        List<Cand> out = new ArrayList<>();
        if (sources == null) return out;
        for (SourceFinder.Source s : sources) {
            Integer h = healthByUrl == null ? null : healthByUrl.get(s.url);
            int health = h == null ? HEALTH_UNKNOWN : h;
            boolean lm = (wantFacet != null && !wantFacet.isEmpty()
                            && wantFacet.equalsIgnoreCase(s.langFacet))
                    || (wantMarc != null && !wantMarc.isEmpty()
                            && wantMarc.equalsIgnoreCase(s.langMarc));
            out.add(new Cand(s, health, lm));
        }
        return out;
    }

    // ------------------------------------------------------- ordering

    /** Health first (proven plays > connects > unknown), then evidence. */
    private static int healthRank(int health) {
        if (health == LinkHealth.TIER_PLAYS) return 3;
        if (health == LinkHealth.TIER_CONNECTS) return 2;
        if (health == HEALTH_UNKNOWN) return 1;
        return 0;
    }

    /** Fastest start: lowest tier first — but a CAM (tier -1) is the
     *  last resort, never the "fastest" pick while anything else lives. */
    private static int lowOrder(Cand a, Cand b) {
        int ka = a.source.qualityTier < 0 ? 99 : a.source.qualityTier;
        int kb = b.source.qualityTier < 0 ? 99 : b.source.qualityTier;
        if (ka != kb) return ka - kb;
        int h = healthRank(b.health) - healthRank(a.health);
        if (h != 0) return h;
        if (a.source.size != b.source.size) {
            return a.source.size < b.source.size ? -1 : 1;
        }
        return a.source.label.compareToIgnoreCase(b.source.label);
    }

    /** Highest quality first, proven health breaking ties. */
    private static int highOrder(Cand a, Cand b) {
        if (a.source.qualityTier != b.source.qualityTier) {
            return b.source.qualityTier - a.source.qualityTier;
        }
        int h = healthRank(b.health) - healthRank(a.health);
        if (h != 0) return h;
        if (a.source.size != b.source.size) {
            return a.source.size > b.source.size ? -1 : 1;
        }
        return a.source.label.compareToIgnoreCase(b.source.label);
    }
}
