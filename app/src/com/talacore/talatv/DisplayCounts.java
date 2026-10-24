package com.talacore.talatv;

import java.util.List;
import java.util.Map;

/**
 * The QC clause of the Display Honesty Law (v0.14.0, James:
 * "You say you got 2598 links — how many of those are bad?
 * How many do I have to sort through? Should already be done
 * for me."). Link verification is the app's job, completed
 * BEFORE display; the viewer never sorts good from bad.
 *
 * The invariant this class exists to hold: ANY COUNT SANDY
 * PRINTS BESIDE LINKS IS THE COUNT OF VERIFIED-LIVE LINKS ONLY
 * — links that passed the checker (SG-1 semantics: terminal
 * failures out on the spot, slow withheld as unproven, never
 * branded or counted). The checked set IS the shelf. A link
 * with no verdict yet is not counted and not promised; a link
 * that later dies leaves the count at the next check
 * (HealthStore is re-read at render, never cached into the
 * tiles). Renderers ask here; they never count raw lists.
 */
public final class DisplayCounts {

    private DisplayCounts() {}

    /** Is this health tier a verified-live link? */
    public static boolean isVerifiedLive(Integer tier) {
        return tier != null
                && (tier == LinkHealth.TIER_PLAYS
                        || tier == LinkHealth.TIER_CONNECTS);
    }

    /**
     * The only count a surface may print for a set of links:
     * how many of them stand verified-live in the health
     * record. urls and their tiers come from the same read, so
     * displayed count == verified set size, by construction.
     */
    public static int verifiedCount(List<String> urls,
                                    Map<String, Integer> tiersByUrl) {
        if (urls == null || tiersByUrl == null) return 0;
        int n = 0;
        for (String u : urls) {
            if (isVerifiedLive(tiersByUrl.get(u))) n++;
        }
        return n;
    }

    /**
     * The count text a tile may carry: the verified count, or
     * NOTHING (empty string) when no link in the set stands
     * verified yet — an unverified number is never printed,
     * and a printed zero would still be a claim.
     */
    public static String countText(int verified) {
        return verified > 0 ? String.valueOf(verified) : "";
    }
}
