package com.talacore.talatv;

/**
 * Reachability (v0.20.1, fixpass-0201; James's law 2026-11-05:
 * "Don't leave shit off" — a verdict is a LABEL and an ORDER,
 * never a removal).
 *
 * The one decision Live TV's display filter makes per channel:
 * where does this channel stand in this display? Before 0.20.1
 * the filter answered that question with a removal — a channel
 * the scan had proven dead was dropped from search results, the
 * language view, country and genre facets, tile lists and the
 * favorites view, and survived only in the default display's
 * Set aside section. Every Iranian channel scans dead from
 * James's network, so a whole country read absolute 0. The
 * mechanism was country-blind; any all-dead cut of the catalogue
 * totaled the same.
 *
 * Now the scan verdict decides MARKING and PLACEMENT only:
 * a scan-dead channel passes through the same facet, query,
 * favorites and tile filters as any other channel, and where it
 * lands it is INCLUDED wearing its mark (the scan's reason +
 * checked date, rendered by MainActivity.setAsideWhy). Two
 * exceptions, both standing rules, both unchanged from 0.20.0:
 * in the DEFAULT display a scan-dead channel stands in the
 * labeled Set aside section at the bottom; and the user's own
 * "Working links only" toggle remains the only hider — it hides
 * what the health probe has proven dead, exactly as it always
 * has, for dead and live-scan channels alike.
 *
 * Pure logic, no Android: the JVM harness executes this decision
 * over fixtures for every view the brief names (search, the
 * language view's country sections, the country facet, the
 * favorites view), and MainActivity's collection loop is a thin
 * adapter that computes the plain booleans and calls decide().
 */
public final class Reachability {

    private Reachability() {}

    /** Not in this display (a facet, query, tile or favorites
     *  filter the channel does not pass — the same answer any
     *  channel gets). */
    public static final int EXCLUDE = 0;
    /** In this display, unmarked. */
    public static final int INCLUDE = 1;
    /** In this display, wearing its scan mark (reason + date). */
    public static final int INCLUDE_MARKED = 2;
    /** Default display only: stands in the Set aside section. */
    public static final int SET_ASIDE = 3;
    /** Out of this display because the USER's "Working links
     *  only" toggle is on and the health probe proved this link
     *  dead — the one user-controlled hider, unchanged. */
    public static final int EXCLUDE_WORKING_DEAD = 4;

    /** Is this scan verdict the proven-dead one? */
    public static boolean isScanDead(int scanVerdict) {
        return scanVerdict == ChannelScan.V_DEAD;
    }

    /**
     * The per-channel display decision. The facet/query/tile
     * booleans are the caller's own filter answers for this
     * channel (true = the channel passes that filter; a filter
     * that is not active passes everything). workingOnlyHealthDead
     * is true only when the user's toggle is on AND the health
     * probe has proven this link dead (LinkHealth.TIER_DEAD).
     */
    public static int decide(int scanVerdict, boolean defaultDisplay,
                             boolean favMode, boolean isFavorite,
                             boolean genreOk, boolean langOk,
                             boolean countryOk, boolean queryOk,
                             boolean workingOnlyHealthDead,
                             boolean serviceOk, boolean sectionOk) {
        boolean dead = isScanDead(scanVerdict);
        // The default display keeps its Set aside section exactly
        // as 0.20.0 has it: no facet, query or tile filter is
        // active there, and the aside stands ahead of the
        // working-only check just as it did — the wheat is in the
        // sections above, the chaff labeled below, both present.
        if (dead && defaultDisplay) return SET_ASIDE;
        if (favMode && !isFavorite) return EXCLUDE;
        if (!genreOk || !langOk || !countryOk || !queryOk) {
            return EXCLUDE;
        }
        if (workingOnlyHealthDead) return EXCLUDE_WORKING_DEAD;
        if (!serviceOk || !sectionOk) return EXCLUDE;
        return dead ? INCLUDE_MARKED : INCLUDE;
    }

    /**
     * Search ordering (item 1's tier rank, extended by the law):
     * a scan-dead match ranks BELOW a live match of the SAME
     * tier, and never above a live match of a better tier — it
     * stays findable; it does not outrank the living.
     */
    public static int searchOrder(int tier, boolean scanDead) {
        return tier * 2 + (scanDead ? 1 : 0);
    }
}
