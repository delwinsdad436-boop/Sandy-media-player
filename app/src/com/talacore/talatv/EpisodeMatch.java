package com.talacore.talatv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Episode identity in the wild (v0.14.0 Addenda 9–11). The catalogue
 * knows the TRUE identity of an episode (season, number, title,
 * airdate, runtime from guide data); files on sources name
 * themselves chaotically. This is the tolerant matcher that weighs
 * the evidence instead of trusting the name:
 *
 *   - numbering schemes, parsed by {@link EpisodeKey} (S01E03,
 *     1x03, "Season 1 Episode 3", ep.7) plus the two it doesn't
 *     carry: AIRDATES (2024-03-05 / 05.03.2024) and the bare
 *     trailing number ("Dragnet 12");
 *   - title evidence: the episode's own title words found in the
 *     file name, after the show's name and the scheme tokens are
 *     taken out;
 *   - duration corroboration where a duration is actually known
 *     (a size in bytes is NOT a duration and is never used as one);
 *   - conflicts VETO: a name whose numbers clearly say a DIFFERENT
 *     episode is evidence AGAINST, weighted heavier than silence.
 *
 * The verdict is a TIER, never a silent rounding-up (the Lying
 * Tile family): RESOLVED (best choices — identity settled),
 * MOSTLY (middle ground — strong evidence, minor doubt), CLOSE
 * (the maybe pen — shown for the viewer's own eyes, NEVER
 * auto-played, NEVER counted as playable), UNMATCHED (with the
 * reason reachable). A wrong episode silently played under the
 * right title is a lie of the same species as a dead link under
 * a pretty card, so a candidate only BINDS to an episode through
 * matchAll — one candidate per episode, one episode per
 * candidate, best evidence first — and CLOSE never binds at all.
 */
public final class EpisodeMatch {

    private EpisodeMatch() {}

    public static final int TIER_RESOLVED = 0;
    public static final int TIER_MOSTLY = 1;
    public static final int TIER_CLOSE = 2;
    public static final int TIER_UNMATCHED = 3;

    /** UiStrings key naming a tier in the house voice. */
    public static String tierKey(int tier) {
        switch (tier) {
            case TIER_RESOLVED: return "tier_best";
            case TIER_MOSTLY: return "tier_middle";
            case TIER_CLOSE: return "tier_maybe";
            default: return "tier_maybe";
        }
    }

    // ------------------------------------------------------------- model

    /** The catalogue's truth about one episode. */
    public static final class EpisodeRef {
        public final int season, number;
        public final String name;        // may be ""
        public final String airdate;     // "YYYY-MM-DD" or ""
        public final int runtimeSec;     // 0 = unknown

        public EpisodeRef(int season, int number, String name,
                          String airdate, int runtimeSec) {
            this.season = season; this.number = number;
            this.name = name == null ? "" : name;
            this.airdate = airdate == null ? "" : airdate;
            this.runtimeSec = runtimeSec;
        }
    }

    /** One candidate file/item found out in the wild. */
    public static final class Candidate {
        public final String label;       // file name / item title
        public final int durationSec;    // 0 = unknown (sizes don't count)
        public boolean verified;         // link proven live (Hydra/health)

        public Candidate(String label, int durationSec) {
            this.label = label == null ? "" : label;
            this.durationSec = durationSec;
        }
    }

    /** What a wild name actually says, once parsed. */
    public static final class Parsed {
        public int season = -1;          // -1 = not stated
        public int episode = -1;
        public String airdate = "";      // "YYYY-MM-DD" or ""
        public int bareNumber = -1;      // trailing bare number, if any
        public String titleWords = "";   // normalized residue (title hunt)
    }

    private static final Pattern P_DATE_ISO = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})[-._](\\d{1,2})[-._](\\d{1,2})(?!\\d)");
    private static final Pattern P_DATE_DMY = Pattern.compile(
            "(?<!\\d)(\\d{1,2})[-._](\\d{1,2})[-._]((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern P_BARE = Pattern.compile(
            "(?:^|[\\s._\\-])(\\d{1,3})(?:\\s*(?:of\\s*\\d{1,3})?)?"
                    + "(?:\\.[A-Za-z0-9]{2,4})?\\s*$");

    public static Parsed parse(String name, String showName) {
        Parsed p = new Parsed();
        if (name == null) return p;
        EpisodeKey k = EpisodeKey.parse(name);
        if (k != null) {
            // seasonKnown=false (episode-only markers) = unstated;
            // an explicit S00 is a stated season (specials).
            p.season = k.seasonKnown ? k.season : -1;
            p.episode = k.episode;
        }
        Matcher iso = P_DATE_ISO.matcher(name);
        if (iso.find()) {
            p.airdate = String.format("%04d-%02d-%02d",
                    Integer.parseInt(iso.group(1)),
                    Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
        } else {
            Matcher dmy = P_DATE_DMY.matcher(name);
            if (dmy.find()) {
                p.airdate = String.format("%04d-%02d-%02d",
                        Integer.parseInt(dmy.group(3)),
                        Integer.parseInt(dmy.group(2)),
                        Integer.parseInt(dmy.group(1)));
            }
        }
        if (p.episode < 0 && p.airdate.isEmpty()) {
            // (an airdate already explains the trailing digits —
            // the day of a date is not a bare episode number)
            Matcher b = P_BARE.matcher(name.trim());
            if (b.find()) p.bareNumber = Integer.parseInt(b.group(1));
        }
        // residue for the title hunt: drop the scheme span, the
        // airdate, the show's own words — what remains should be
        // the episode's title if the name carries one.
        String residue = name;
        int[] span = EpisodeKey.parseSpan(name);
        if (span != null) {
            // parseSpan: {season, episode, startIdx, endIdx, seasonKnown}
            residue = residue.substring(0, span[2])
                    + " " + residue.substring(span[3]);
        }
        residue = P_DATE_ISO.matcher(residue).replaceAll(" ");
        residue = P_DATE_DMY.matcher(residue).replaceAll(" ");
        String norm = EpisodeKey.normalize(residue);
        String showNorm = EpisodeKey.normalize(showName);
        if (!showNorm.isEmpty()) {
            Set<String> showWords = wordSet(showNorm);
            StringBuilder keep = new StringBuilder();
            for (String w : norm.split(" ")) {
                if (w.isEmpty() || showWords.contains(w)) continue;
                if (keep.length() > 0) keep.append(' ');
                keep.append(w);
            }
            norm = keep.toString();
        }
        p.titleWords = norm;
        return p;
    }

    // ------------------------------------------------------------- score

    public static final class Scored {
        public int score;
        public boolean conflict;         // the name argues AGAINST
        public String reason = "";       // plain words, reachable
    }

    /** Weigh one candidate against one episode. */
    public static Scored score(Parsed p, EpisodeRef ep) {
        Scored s = new Scored();
        StringBuilder why = new StringBuilder();
        // numbering
        if (p.episode > 0) {
            boolean seasonOk = p.season < 0 || p.season == ep.season;
            if (p.episode == ep.number && seasonOk) {
                s.score += 50;
                why.append("numbers match");
            } else if (p.season >= 0 && p.season != ep.season) {
                s.score -= 60; s.conflict = true;
                why.append("numbers say season ").append(p.season);
            } else {
                s.score -= 60; s.conflict = true;
                why.append("numbers say episode ").append(p.episode);
            }
        } else if (p.bareNumber > 0) {
            if (p.bareNumber == ep.number) {
                s.score += 20;
                addWhy(why, "bare number matches");
            } else {
                s.score -= 25; s.conflict = true;
                addWhy(why, "bare number says " + p.bareNumber);
            }
        }
        // airdate
        if (!p.airdate.isEmpty() && !ep.airdate.isEmpty()) {
            if (p.airdate.equals(ep.airdate)) {
                s.score += 45;
                addWhy(why, "airdate matches");
            } else {
                s.score -= 40; s.conflict = true;
                addWhy(why, "airdate differs");
            }
        }
        // title
        if (!ep.name.isEmpty() && !p.titleWords.isEmpty()) {
            double cov = coverage(p.titleWords,
                    EpisodeKey.normalize(ep.name));
            if (cov >= 0.99) { s.score += 30; addWhy(why, "title matches"); }
            else if (cov >= 0.6) { s.score += 18; addWhy(why, "title close"); }
            else if (cov > 0) { s.score += (int) Math.round(cov * 12); }
        }
        s.reason = why.toString();
        return s;
    }

    /** Duration corroboration, applied when BOTH durations are
     *  honestly known. Returns a delta for the score. */
    public static int durationDelta(int candidateSec, int episodeSec) {
        if (candidateSec <= 0 || episodeSec <= 0) return 0;
        double ratio = (double) candidateSec / episodeSec;
        if (ratio >= 0.9 && ratio <= 1.1) return 10;
        if (ratio < 0.5 || ratio > 2.0) return -15;
        return 0;
    }

    /** The tier a score earns. Margin = lead over the runner-up
     *  candidate FOR THE SAME EPISODE (or over this candidate's
     *  next-best episode, in matchAll); a conflict caps at CLOSE —
     *  doubt is never rounded up. */
    public static int tierFor(int score, int margin, boolean conflict) {
        if (conflict) return score > 0 ? TIER_CLOSE : TIER_UNMATCHED;
        if (score >= 60 && margin >= 15) return TIER_RESOLVED;
        if (score >= 35) return TIER_MOSTLY;
        if (score > 0) return TIER_CLOSE;
        return TIER_UNMATCHED;
    }

    // -------------------------------------------------------- assignment

    public static final class Binding {
        public int episodeIdx, candidateIdx, score, tier;
        public String reason = "";
    }

    public static final class Assignment {
        /** One binding per episode (or null): episodeIdx -> Binding. */
        public final java.util.Map<Integer, Binding> byEpisode =
                new java.util.HashMap<>();
        /** Candidates bound to nothing, with reasons (the maybe pen
         *  plus the plainly unmatched). */
        public final List<Binding> unbound = new ArrayList<>();
    }

    /**
     * Bind candidates to episodes: every (episode, candidate) pair
     * scored, best pairs claimed first, each side used once
     * (Addendum 10: the union dedupes BY EPISODE IDENTITY — five
     * uploads of S01E03 are one episode, not five). CLOSE pairs
     * never bind; they go to the pen with their reasons.
     */
    public static Assignment matchAll(List<EpisodeRef> episodes,
                                      List<Candidate> candidates,
                                      String showName) {
        Assignment out = new Assignment();
        int ne = episodes.size(), nc = candidates.size();
        Parsed[] parsed = new Parsed[nc];
        for (int j = 0; j < nc; j++) {
            parsed[j] = parse(candidates.get(j).label, showName);
        }
        int[][] scores = new int[ne][nc];
        boolean[][] conflicts = new boolean[ne][nc];
        String[][] reasons = new String[ne][nc];
        int[][] tiers = new int[ne][nc];
        for (int i = 0; i < ne; i++) {
            // margin per candidate vs its runner-up episode is
            // computed column-wise below; first pass: raw scores.
            for (int j = 0; j < nc; j++) {
                Scored s = score(parsed[j], episodes.get(i));
                s.score += durationDelta(candidates.get(j).durationSec,
                        episodes.get(i).runtimeSec);
                scores[i][j] = s.score;
                conflicts[i][j] = s.conflict;
                reasons[i][j] = s.reason;
            }
        }
        for (int j = 0; j < nc; j++) {
            // per candidate: best and second-best episode scores
            int best = Integer.MIN_VALUE, second = Integer.MIN_VALUE;
            for (int i = 0; i < ne; i++) {
                if (scores[i][j] > best) { second = best; best = scores[i][j]; }
                else if (scores[i][j] > second) { second = scores[i][j]; }
            }
            for (int i = 0; i < ne; i++) {
                int margin = scores[i][j]
                        - (scores[i][j] == best ? second : best);
                if (margin == Integer.MIN_VALUE / 2) margin = 999;
                tiers[i][j] = tierFor(scores[i][j], Math.max(margin, 0),
                        conflicts[i][j]);
            }
        }
        // claim pairs best-first; RESOLVED and MOSTLY may bind.
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < ne; i++) {
            for (int j = 0; j < nc; j++) {
                if (tiers[i][j] == TIER_RESOLVED || tiers[i][j] == TIER_MOSTLY) {
                    pairs.add(new int[]{scores[i][j], i, j});
                }
            }
        }
        pairs.sort((a, b) -> b[0] - a[0]);
        Set<Integer> usedEp = new HashSet<>(), usedCand = new HashSet<>();
        for (int[] pr : pairs) {
            int i = pr[1], j = pr[2];
            if (usedEp.contains(i) || usedCand.contains(j)) continue;
            usedEp.add(i); usedCand.add(j);
            Binding b = new Binding();
            b.episodeIdx = i; b.candidateIdx = j;
            b.score = scores[i][j]; b.tier = tiers[i][j];
            b.reason = reasons[i][j];
            out.byEpisode.put(i, b);
        }
        for (int j = 0; j < nc; j++) {
            if (usedCand.contains(j)) continue;
            // the pen entry: this candidate's best look, honestly told
            int bi = -1, bs = Integer.MIN_VALUE;
            for (int i = 0; i < ne; i++) {
                if (scores[i][j] > bs) { bs = scores[i][j]; bi = i; }
            }
            Binding b = new Binding();
            b.episodeIdx = bi; b.candidateIdx = j;
            b.score = Math.max(bs, 0);
            b.tier = bi < 0 ? TIER_UNMATCHED : tiers[bi][j];
            if (b.tier == TIER_RESOLVED || b.tier == TIER_MOSTLY) {
                b.tier = TIER_CLOSE;   // lost the claim — a maybe now
            }
            b.reason = bi < 0 ? "no episode evidence in the name"
                    : reasons[bi][j];
            if (b.reason == null || b.reason.isEmpty()) {
                b.reason = "no episode evidence in the name";
            }
            out.unbound.add(b);
        }
        return out;
    }

    /**
     * Addendum 10(2) + 11: the playable count a show card may print.
     * An episode counts ONLY when its bound candidate is RESOLVED
     * tier AND its link is verified live — middle ground and
     * maybes never inflate the number. Returns {playable, total};
     * total 0 means "print nothing" (Display Honesty).
     */
    public static int[] playableCount(Assignment a,
                                      List<Candidate> candidates,
                                      int totalEpisodes) {
        int playable = 0;
        for (Binding b : a.byEpisode.values()) {
            if (b.tier == TIER_RESOLVED
                    && candidates.get(b.candidateIdx).verified) {
                playable++;
            }
        }
        return new int[]{playable, totalEpisodes};
    }

    // ------------------------------------------------------------- words

    private static void addWhy(StringBuilder why, String s) {
        if (why.length() > 0) why.append("; ");
        why.append(s);
    }

    private static Set<String> wordSet(String norm) {
        Set<String> out = new HashSet<>();
        for (String w : norm.split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Fraction of the needle's words present in the haystack. */
    static double coverage(String haystackNorm, String needleNorm) {
        Set<String> hay = wordSet(haystackNorm);
        Set<String> needle = wordSet(needleNorm);
        if (needle.isEmpty()) return 0;
        int hit = 0;
        for (String w : needle) if (hay.contains(w)) hit++;
        return (double) hit / needle.size();
    }
}
