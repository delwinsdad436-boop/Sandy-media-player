package com.talacore.talatv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * THE CHANNEL SCAN (v0.17.0, James, 2026-10-20): "It should
 * pretest every link in Live TV and in the catalog to make sure
 * they actually exist. Kind of like when you're scanning for
 * channels on the TV."
 *
 * This class is the scan's pure policy — no Android, no network:
 * the verdict vocabulary, the SG-1 classification, the cadence,
 * the persisted record format, and the presentation filter. The
 * engine ({@link ScanEngine}) does the walking through Hydra;
 * the record lives in {@link ScanStore} as a file on the memory
 * hook, sibling to hydra_doors.tsv.
 *
 * SG-1 semantics, exactly as laid down: a TERMINAL answer (the
 * address said gone / refused / no such host — Hydra's walk
 * ended DEAD and not slow) marks the link DEAD on the spot.
 * A SLOW or ambiguous outcome (timeouts, resets, 5xx, throttled)
 * is NEVER a death verdict: it files UNKNOWN, the link keeps
 * its shelf place, and a later scan decides fresh.
 *
 * v0.18.0 — THE PROOF TIER IS THE PLAYER'S TIER (James's field
 * defect, 2026-10-20: tiles "say they're available, but when
 * you go to play them, you can't"). Up to 0.17.0 the scan filed
 * LIVE when the walk reached the PLAYLIST tier (CONNECTS) —
 * but the player consumes the SEGMENT tier: a list can wave
 * you in while every video piece behind it refuses (token,
 * referer, or geo gates live at the segment CDN). So LIVE now
 * means what a tap means: the walk — the player's own manner
 * ladder, walked exhaustively (see ScanEngine) — proved the
 * playlist AND the first segment (LinkHealth.TIER_PLAYS). A
 * link whose list answers but whose picture is unproven files
 * LISTED: it stays on the shelf under its own honest state
 * (listed, not playable — never counted as live, never lent
 * to a tile as availability), wearing the plain-words reason
 * the walk ended with. The scan's printed "live" count is
 * therefore a tap-and-it-plays count, not listed-and-hoped.
 *
 * Display consequence (the Lying Tile law with a sweep behind
 * it): only a DEAD verdict removes a link from presentation —
 * gone, not badged, not greyed. LIVE, LISTED, UNKNOWN and
 * untested links present; LISTED presents AS LISTED. The
 * scan's printed counts are counts of these verdicts,
 * nothing else.
 */
public final class ChannelScan {

    private ChannelScan() {}

    /** The walk proved playlist AND first segment: a tap plays. */
    public static final int V_LIVE = 0;
    /** A terminal answer: the link is gone. Off the shelf. */
    public static final int V_DEAD = 1;
    /** Slow or ambiguous: no verdict on the link's life. */
    public static final int V_UNKNOWN = 2;
    /**
     * v0.18.0: the list answers, but the picture is unproven —
     * the walk reached the playlist tier on some door and no
     * door proved a segment. Presented AS LISTED (its own
     * honest state), never as working, never counted live.
     */
    public static final int V_LISTED = 3;
    /** No record: never tested. */
    public static final int V_UNTESTED = -1;

    public static final String KIND_LIVE = "live";
    public static final String KIND_VOD = "vod";

    /** Bounded concurrency for the sweep — politeness is real. */
    public static final int CONCURRENCY = 4;
    /** The shared per-link budget is Hydra's own cap (~30 s). */
    public static final long BUDGET_MS = LinkHealth.TOTAL_CAP_MS;
    /** Auto re-tests respect this cadence; a manual scan overrides. */
    public static final long CADENCE_MS = 6L * 3600L * 1000L;

    /** One link's persisted scan verdict. */
    public static final class Rec {
        public final String url;
        public final String kind;
        public final int verdict;
        public final long atMs;
        /** The plain-words reason Hydra's walk ended with. */
        public final String how;

        public Rec(String url, String kind, int verdict, long atMs, String how) {
            this.url = url == null ? "" : url;
            this.kind = kind == null ? "" : kind;
            this.verdict = verdict;
            this.atMs = atMs;
            this.how = how == null ? "" : how;
        }
    }

    /** The last run's shape, printed on the scan surface. */
    public static final class Summary {
        public final long lastScanMs;
        /** Links actually tested in the last run. */
        public final int tested;
        /** Links carrying a LIVE verdict in the record. */
        public final int live;
        /** Links carrying a DEAD verdict in the record (removed). */
        public final int removed;
        /** v0.18.0: links carrying a LISTED verdict (list answers,
         *  picture unproven) — presented as listed, never live. */
        public final int listed;

        public Summary(long lastScanMs, int tested, int live, int removed) {
            this(lastScanMs, tested, live, removed, 0);
        }

        public Summary(long lastScanMs, int tested, int live, int removed,
                       int listed) {
            this.lastScanMs = lastScanMs;
            this.tested = tested;
            this.live = live;
            this.removed = removed;
            this.listed = listed;
        }
    }

    /** A parsed record file: the verdicts plus the run summary. */
    public static final class Parsed {
        public final Map<String, Rec> recs;
        public final Summary summary;

        public Parsed(Map<String, Rec> recs, Summary summary) {
            this.recs = recs;
            this.summary = summary;
        }
    }

    // -------------------------------------------------- SG-1 verdict

    /**
     * The classification, from the walk's own evidence: the best
     * tier any head reached, and the final result's tier + slow
     * flag. LIVE only when some head proved the SEGMENT tier
     * (TIER_PLAYS — the tier the player consumes; v0.18.0).
     * A walk that reached the playlist tier (TIER_CONNECTS) but
     * never a segment files LISTED — the list is there, the
     * picture is not proven; it is presented as listed, not as
     * working. An answering door outranks a terminal tail: when
     * some head demonstrably served the playlist, another
     * head's terminal ending demotes the link to LISTED, it
     * does not convict it (the 0.17.0 law — an early proof
     * outranks a later dead head — kept, one tier down). DEAD
     * is reserved for a walk where NO head ever answered: the
     * final result terminal (DEAD and NOT slow — the exact
     * guard HealthStore has kept since v0.11.0) and nothing
     * proven above it. Anything else is UNKNOWN, never a
     * death sentence.
     */
    public static int classify(int bestTier, int resultTier, boolean resultSlow) {
        if (bestTier >= LinkHealth.TIER_PLAYS) return V_LIVE;
        if (bestTier >= LinkHealth.TIER_CONNECTS) return V_LISTED;
        if (resultTier == LinkHealth.TIER_DEAD && !resultSlow) return V_DEAD;
        return V_UNKNOWN;
    }

    /**
     * May this verdict be PRESENTED AS WORKING — counted in the
     * scan's live numbers, lent to a tile as availability, badged
     * as a channel that plays? Only V_LIVE (v0.18.0): the proof
     * standard for presentation is the player's own tier.
     */
    public static boolean presentedAsWorking(int verdict) {
        return verdict == V_LIVE;
    }

    // ------------------------------------------------------ cadence

    /** Is this link due a (re)test? Manual scans test everything. */
    public static boolean isDue(Rec r, long nowMs, boolean manual) {
        if (manual) return true;
        if (r == null) return true;
        return nowMs - r.atMs >= CADENCE_MS;
    }

    // ------------------------------------------------- presentation

    /** The recorded verdict for a url, or V_UNTESTED. */
    public static int verdictOf(Map<String, Rec> recs, String url) {
        if (recs == null || url == null) return V_UNTESTED;
        Rec r = recs.get(url);
        return r == null ? V_UNTESTED : r.verdict;
    }

    /**
     * The presentation filter the shelves apply: every url except
     * the ones the scan convicted DEAD. Order preserved.
     */
    public static List<String> presentable(List<String> urls,
                                           Map<String, Rec> recs) {
        List<String> out = new ArrayList<>();
        if (urls == null) return out;
        for (String u : urls) {
            if (verdictOf(recs, u) != V_DEAD) out.add(u);
        }
        return out;
    }

    /** The run summary from the record itself: printed = verified. */
    public static Summary summarize(Map<String, Rec> recs,
                                    long lastScanMs, int testedThisRun) {
        int live = 0, removed = 0, listed = 0;
        if (recs != null) {
            for (Rec r : recs.values()) {
                if (r.verdict == V_LIVE) live++;
                else if (r.verdict == V_DEAD) removed++;
                else if (r.verdict == V_LISTED) listed++;
            }
        }
        return new Summary(lastScanMs, testedThisRun, live, removed, listed);
    }

    // --------------------------------------------------- the record
    // scan_links.tsv — first line is the summary:
    //   # <tab> lastScanMs <tab> tested <tab> live <tab> removed
    //        [<tab> listed]      (v0.18.0; files written before it
    //                              carry no sixth field and parse
    //                              with listed = 0)
    // then one line per link:
    //   enc(url) <tab> kind <tab> verdict <tab> atMs <tab> enc(how)

    public static Parsed parse(String tsv) {
        Map<String, Rec> recs = new LinkedHashMap<>();
        Summary summary = new Summary(0, 0, 0, 0);
        if (tsv == null) return new Parsed(recs, summary);
        for (String line : tsv.split("\n")) {
            if (line.isEmpty()) continue;
            try {
                String[] p = line.split("\t", -1);
                if (p[0].equals("#")) {
                    int listed = p.length >= 6
                            ? Integer.parseInt(p[5]) : 0;
                    summary = new Summary(Long.parseLong(p[1]),
                            Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                            Integer.parseInt(p[4]), listed);
                } else if (p.length >= 5) {
                    Rec r = new Rec(dec(p[0]), p[1],
                            Integer.parseInt(p[2]), Long.parseLong(p[3]),
                            dec(p[4]));
                    if (!r.url.isEmpty()) recs.put(r.url, r);
                }
            } catch (Exception ignored) {
                // a torn line is skipped, never guessed at
            }
        }
        return new Parsed(recs, summary);
    }

    public static String toTsv(Map<String, Rec> recs, Summary summary) {
        StringBuilder sb = new StringBuilder();
        Summary s = summary == null ? new Summary(0, 0, 0, 0) : summary;
        sb.append("#\t").append(s.lastScanMs).append('\t').append(s.tested)
                .append('\t').append(s.live).append('\t').append(s.removed)
                .append('\t').append(s.listed)
                .append('\n');
        if (recs != null) {
            // Sorted by url so the file is stable and diff-able.
            for (Rec r : new TreeMap<>(recs).values()) {
                sb.append(enc(r.url)).append('\t').append(r.kind)
                        .append('\t').append(r.verdict).append('\t')
                        .append(r.atMs).append('\t').append(enc(r.how))
                        .append('\n');
            }
        }
        return sb.toString();
    }

    /** Verdict counts over any record collection:
     *  {live, dead, unknown, listed} (listed added v0.18.0). */
    public static int[] counts(Collection<Rec> recs) {
        int[] out = new int[4];
        if (recs == null) return out;
        for (Rec r : recs) {
            if (r.verdict == V_LIVE) out[0]++;
            else if (r.verdict == V_DEAD) out[1]++;
            else if (r.verdict == V_UNKNOWN) out[2]++;
            else if (r.verdict == V_LISTED) out[3]++;
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
