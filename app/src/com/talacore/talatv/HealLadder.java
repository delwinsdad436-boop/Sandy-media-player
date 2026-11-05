package com.talacore.talatv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The heal ladder (v0.20.0, item 7 — quiet self-healing): when a
 * live channel's own doors are spent, the SAME station often
 * stands in the loaded channel list more than once — same
 * tvg-id or same name, different address. This class orders
 * those sibling entries so the player can walk them (each one
 * still has to prove itself at the segment tier before it is
 * played; this class only ORDERS, it never vouches).
 *
 * The rule set, kept small and deterministic — candidates are
 * taken tier by tier, in list order inside a tier:
 *
 *   tier 1  same station id: ChannelNames.keyOf(tvgId) equal
 *           and non-empty (keyOf is the house identity join:
 *           the tvg-id before any @feed suffix — the suffix
 *           names a feed of the station, not another station).
 *   tier 2  same normalized name: ChannelNames.cleanName of
 *           the display name, lower-cased, whitespace folded —
 *           equal and non-empty.
 *   tier 3  same name modulo dressing: the tier-2 name with
 *           trailing bracketed/parenthesised segments and
 *           trailing quality tokens (HD, 1080p, HEVC, …)
 *           stripped, repeatedly — equal and non-empty, where
 *           the full tier-2 names actually differ (otherwise
 *           the candidate was already tier 2).
 *
 * Excluded always: the failed address itself, any address
 * already on the ladder (first occurrence wins), entries with
 * no address or a non-http(s) address. The ladder is capped at
 * MAX_CANDIDATES — each rung costs a full Hydra budget to
 * prove, so a sane cap is a courtesy to the viewer's evening.
 *
 * Pure logic only (no Android imports) so the JVM harness can
 * pin the ordering. The sleep-timer step cycle also lives here
 * (item 9b) as a pure helper for the same reason.
 */
public final class HealLadder {

    /** Most siblings one failure may walk. */
    public static final int MAX_CANDIDATES = 4;

    /** Sleep-timer steps in minutes; 0 = off. The cycle wraps. */
    public static final int[] SLEEP_STEPS_MIN = {0, 15, 30, 45, 60, 90};

    private HealLadder() {}

    // ---------------------------------------------------- sleep cycle

    /** The step after {@code currentMin} in the sleep cycle,
     *  wrapping from the last step back to Off (0). A value that
     *  is not one of the steps reads as Off's predecessor: the
     *  answer is 0 (Off) — a standing choice is never invented. */
    public static int nextSleep(int currentMin) {
        for (int i = 0; i < SLEEP_STEPS_MIN.length; i++) {
            if (SLEEP_STEPS_MIN[i] == currentMin) {
                return SLEEP_STEPS_MIN[(i + 1) % SLEEP_STEPS_MIN.length];
            }
        }
        return 0;
    }

    // ---------------------------------------------------- the ladder

    /**
     * The ordered sibling candidates for {@code failed} out of
     * {@code all} (the loaded channel list). Never null; empty
     * when the station stands alone in the list.
     */
    public static List<M3uParser.Channel> ladder(
            List<M3uParser.Channel> all, M3uParser.Channel failed) {
        List<M3uParser.Channel> out = new ArrayList<>();
        if (all == null || failed == null) return out;
        Set<String> seen = new HashSet<>();
        if (failed.url != null) seen.add(failed.url);
        for (int tier = 1; tier <= 3 && out.size() < MAX_CANDIDATES; tier++) {
            for (M3uParser.Channel c : all) {
                if (out.size() >= MAX_CANDIDATES) break;
                if (c == null || c.url == null || c.url.isEmpty()) continue;
                if (!isHttp(c.url)) continue;
                if (seen.contains(c.url)) continue;
                if (tierOf(c, failed) != tier) continue;
                seen.add(c.url);
                out.add(c);
            }
        }
        return out;
    }

    /** Which tier {@code c} heals {@code failed} at: 1, 2, 3 —
     *  or 0 when it is not a sibling at all. */
    public static int tierOf(M3uParser.Channel c, M3uParser.Channel failed) {
        if (c == null || failed == null) return 0;
        String ft = ChannelNames.keyOf(failed.tvgId);
        String ct = ChannelNames.keyOf(c.tvgId);
        if (!ft.isEmpty() && ft.equals(ct)) return 1;
        String fn = normName(failed);
        String cn = normName(c);
        if (!fn.isEmpty() && fn.equals(cn)) return 2;
        String fc = coreName(fn);
        if (!fc.isEmpty() && fc.equals(coreName(cn))) return 3;
        return 0;
    }

    // ---------------------------------------------------- name shapes

    /** Tier-2 shape: the display name, feed junk cleaned,
     *  lower-cased, whitespace folded. */
    static String normName(M3uParser.Channel c) {
        if (c == null) return "";
        String s = ChannelNames.cleanName(c.displayName());
        return s.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
    }

    private static final Set<String> QUALITY_TOKENS = new HashSet<>();
    static {
        String[] q = {"hd", "sd", "fhd", "uhd", "4k", "8k", "hevc",
                "h264", "h265", "x264", "x265",
                "2160p", "1080p", "720p", "576p", "480p", "360p",
                "1080i", "720i"};
        for (String t : q) QUALITY_TOKENS.add(t);
    }

    /** Tier-3 shape: a normalized name with its dressing off —
     *  trailing (…) / […] segments and trailing quality tokens,
     *  stripped until none are left. */
    static String coreName(String norm) {
        if (norm == null) return "";
        String s = norm.trim();
        boolean changed = true;
        while (changed && !s.isEmpty()) {
            changed = false;
            String t = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            if (!t.equals(s)) { s = t; changed = true; }
            t = s.replaceAll("\\s*\\[[^\\]]*\\]\\s*$", "").trim();
            if (!t.equals(s)) { s = t; changed = true; }
            int cut = s.lastIndexOf(' ');
            String last = cut >= 0 ? s.substring(cut + 1) : s;
            if (QUALITY_TOKENS.contains(last)) {
                s = cut >= 0 ? s.substring(0, cut).trim() : "";
                changed = true;
            }
        }
        return s;
    }

    private static boolean isHttp(String url) {
        String u = url.toLowerCase(Locale.US);
        return u.startsWith("http://") || u.startsWith("https://");
    }
}
