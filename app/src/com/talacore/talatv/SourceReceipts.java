package com.talacore.talatv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VERIFICATION RECEIPTS (v0.18.0, James, 2026-10-20): Sandy
 * should KNOW her sources are true the house way — because
 * someone verified them, and the evidence is on file.
 *
 * A receipt certifies VERIFIED-AT-BIND: the census proof, its
 * round and date, and the tiers that proof reached —
 * catalogue, page, stream, bytes — plus, where it applies, the
 * EYES tier: Pearl herself opened the source's own pages in
 * her browser and saw them real. "Eyes-seen" is never a crew
 * claim and never a status code; it is recorded only where it
 * actually happened (James's QA law, 2026-10-20).
 *
 * A receipt does NOT promise the door stands forever. Runtime
 * re-checks (the Channel Scan, the background checker) exist
 * to catch LATER breakage by the world: a source whose door
 * dies after binding is recorded WORLD_CHANGED — the door
 * died on a date, for a stated reason — never written up as a
 * binding error, and never on a slow answer (SG-1: a timeout
 * is UNKNOWN, not a death certificate; only a terminal answer
 * — gone, refused, no such address — may mark a door changed).
 *
 * The gate: a receipt whose tiers lack STREAM and BYTES
 * cannot back a "playable" claim (see backsPlayable). The
 * catalogue services (TVmaze, the iptv-org directory) hold
 * catalogue-tier receipts — they back catalogue presence,
 * never playability, and the record says so.
 *
 * This class is the pure format + policy; the file lives on
 * the memory hook via {@link ReceiptStore}
 * (files/memory/source_receipts.tsv), seeded at first run from
 * the shipped census record (assets/source_receipts_seed.tsv,
 * which SEED_TSV below mirrors — the harness pins them equal).
 */
public final class SourceReceipts {

    private SourceReceipts() {}

    public static final String STATE_BOUND = "BOUND";
    public static final String STATE_WORLD_CHANGED = "WORLD_CHANGED";

    /** One bound source's verification receipt. */
    public static final class Rec {
        public final String id;
        public final String name;
        /** The census round that proved it ("Round 1", "Round 2",
         *  or "House verification" for pre-census house doors). */
        public final String round;
        /** The date the proof was made, yyyy-MM-dd. */
        public final String provedDate;
        public final boolean catalogue;
        public final boolean page;
        public final boolean stream;
        public final boolean bytes;
        /** The eyes tier: "" when nobody's eyes are on record, else
         *  who saw it and when (e.g. "seen by Pearl, 2026-10-20"). */
        public final String eyes;
        /** The connection dialects proven at the door. */
        public final String dialects;
        public final String state;         // BOUND / WORLD_CHANGED
        /** When state is WORLD_CHANGED: the date the re-check
         *  found the door dead, yyyy-MM-dd; else "". */
        public final String changedDate;
        /** When WORLD_CHANGED: the plain words of the refusal. */
        public final String changedHow;
        /** Free provenance note (the census's own words, kept short). */
        public final String note;

        public Rec(String id, String name, String round, String provedDate,
                   boolean catalogue, boolean page, boolean stream,
                   boolean bytes, String eyes, String dialects, String state,
                   String changedDate, String changedHow, String note) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.round = round == null ? "" : round;
            this.provedDate = provedDate == null ? "" : provedDate;
            this.catalogue = catalogue;
            this.page = page;
            this.stream = stream;
            this.bytes = bytes;
            this.eyes = eyes == null ? "" : eyes;
            this.dialects = dialects == null ? "" : dialects;
            this.state = state == null ? STATE_BOUND : state;
            this.changedDate = changedDate == null ? "" : changedDate;
            this.changedHow = changedHow == null ? "" : changedHow;
            this.note = note == null ? "" : note;
        }

        Rec withChange(String newState, String date, String how) {
            return new Rec(id, name, round, provedDate, catalogue, page,
                    stream, bytes, eyes, dialects, newState, date, how, note);
        }
    }

    // ------------------------------------------------------- the gate

    /**
     * May this receipt back a "playable" claim? Only a BOUND
     * receipt whose census proof reached the STREAM and BYTES
     * tiers — the same tiers the player consumes (v0.18.0).
     */
    public static boolean backsPlayable(Rec r) {
        return r != null && STATE_BOUND.equals(r.state)
                && r.stream && r.bytes;
    }

    /** May this receipt back a "readable" claim (book sources)?
     *  Catalogue + the book's own bytes, bound and standing. */
    public static boolean backsReadable(Rec r) {
        return r != null && STATE_BOUND.equals(r.state)
                && r.catalogue && r.bytes;
    }

    // --------------------------------------------- world-changed

    /**
     * The terminal kinds that may mark a door WORLD_CHANGED:
     * the server SAID gone / refused / no such address. Slow,
     * server-trouble, and no-answer outcomes may NOT — they are
     * the weather, not a death (SG-1).
     */
    public static boolean isTerminalKind(String kind) {
        return FailureWords.GONE.equals(kind)
                || FailureWords.REFUSED.equals(kind)
                || FailureWords.ADDRESS.equals(kind);
    }

    /**
     * Record a re-check's verdict against a bound source's door.
     * Flips the receipt to WORLD_CHANGED (stamping the date and
     * the plain words) ONLY on a terminal answer; every other
     * outcome leaves the receipt exactly as it stands. The bind
     * proof itself is never edited — the receipt keeps what was
     * true when Sandy took the source in.
     */
    public static Rec noteDoorResult(Rec r, String kind, String date,
                                     String how) {
        if (r == null || !STATE_BOUND.equals(r.state)) return r;
        if (!isTerminalKind(kind)) return r;
        return r.withChange(STATE_WORLD_CHANGED,
                date == null ? "" : date, how == null ? "" : how);
    }

    // --------------------------------------------------------- seed
    // The census record, as shipped. Census Round 1:
    // ~/workspace/sandy-source-audit-2026-10-20/RESOLUTION-AUDIT.md
    // Census Round 2: .../CENSUS-ROUND-2.md. The eyes tier is
    // entered ONLY for the two sources Pearl opened and read in
    // the main chat on 2026-10-20 ~23:09 CDT (James's QA law).

    public static List<Rec> seed() {
        List<Rec> out = new ArrayList<>();
        out.add(new Rec("internet_archive", "Internet Archive",
                "Round 1", "2026-10-20", true, true, true, true, "",
                "HTTPS · REST JSON · progressive MP4 · HLS", STATE_BOUND,
                "", "",
                "Chain-walk to the byte: Duck and Cover — 206, verified"
                + " MP4 bytes via the Archive's CDN. Re-walked in"
                + " Round 2: still resolves to the byte."));
        out.add(new Rec("wikimedia_commons", "Wikimedia Commons",
                "Round 2", "2026-10-20", true, true, true, true,
                "seen by Pearl, 2026-10-20",
                "HTTPS · MediaWiki API (keyless) · progressive WebM"
                + " (VP9/AV1) + MPEG-4 renditions", STATE_BOUND, "", "",
                "Night of the Living Dead (1968) — 206, verified WebM"
                + " bytes. Per-file rights labels ride in the API and"
                + " onto the tile; captions ride where the file"
                + " offers them."));
        out.add(new Rec("nfaj_janim",
                "National Film Archive of Japan — Japanese Animated"
                + " Film Classics",
                "Round 2", "2026-10-20", true, true, true, true,
                "seen by Pearl, 2026-10-20",
                "HTTPS · HLS (TS segments) · progressive MP4",
                STATE_BOUND, "", "",
                "The Quack Infantry Troop (1940): master → variant →"
                + " segment, 206 verified MPEG-TS; a direct MP4 door"
                + " stands on the site's own pages too. The archive"
                + " streams its heritage — Sandy binds the stream;"
                + " copying is the archive's prohibition, named on"
                + " the shelf."));
        out.add(new Rec("project_gutenberg", "Project Gutenberg",
                "Round 1", "2026-10-20", true, false, false, true, "",
                "HTTPS · Gutendex REST JSON · direct files at"
                + " gutenberg.org", STATE_BOUND, "", "",
                "Landed in v0.16.0. Round 2 re-walk: the Gutendex"
                + " catalogue door timed out twice from the census"
                + " vantage while book files still resolved at"
                + " gutenberg.org — a catalogue hop to re-check,"
                + " recorded, not a death: slow is not dead."));
        out.add(new Rec("open_library", "Open Library",
                "Round 1", "2026-10-20", true, false, false, false, "",
                "HTTPS · REST JSON", STATE_BOUND, "", "",
                "The catalogue that helps find editions. Lending-"
                + " walled items are named, never bypassed; reading"
                + " resolves through the Archive's leg. Backs no"
                + " playable claim and never did."));
        out.add(new Rec("librivox", "LibriVox",
                "Round 1", "2026-10-20", true, false, true, true, "",
                "HTTPS · feed/API · progressive MP3", STATE_BOUND,
                "", "",
                "Feed + sections + on-book verified match (v0.16.0);"
                + " re-walked in Round 2 to the byte."));
        out.add(new Rec("tvmaze", "TVmaze",
                "House verification", "2026-10-17",
                true, false, false, false, "",
                "HTTPS · REST JSON", STATE_BOUND, "", "",
                "The catalogue index: it knows shows EXIST. No stream"
                + " tier — it backs catalogue presence only, never a"
                + " playable claim."));
        out.add(new Rec("iptvorg", "iptv-org directory",
                "House verification", "2026-10-17",
                true, false, false, false, "",
                "HTTPS · M3U collections", STATE_BOUND, "", "",
                "The channel collections. Per-link truth is the"
                + " Channel Scan's job, link by link — this receipt"
                + " covers the directory door only."));
        return out;
    }

    // --------------------------------------------------- the record
    // source_receipts.tsv — comment header, then one line per
    // source:
    //   enc(id) TAB enc(name) TAB enc(round) TAB provedDate TAB
    //   tiers(4 chars, 1/0: catalogue,page,stream,bytes) TAB
    //   enc(eyes) TAB enc(dialects) TAB state TAB changedDate TAB
    //   enc(changedHow) TAB enc(note)

    public static String seedTsv() {
        Map<String, Rec> m = new LinkedHashMap<>();
        for (Rec r : seed()) m.put(r.id, r);
        return toTsv(m);
    }

    public static Map<String, Rec> parse(String tsv) {
        Map<String, Rec> out = new LinkedHashMap<>();
        if (tsv == null) return out;
        for (String line : tsv.split("\n")) {
            String probe = line.trim();
            if (probe.isEmpty() || probe.startsWith("#")) continue;
            String s = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
            String[] p = s.split("\t", -1);
            if (p.length < 11) continue;
            try {
                String tiers = p[4];
                Rec r = new Rec(dec(p[0]), dec(p[1]), dec(p[2]), p[3],
                        tiers.length() > 0 && tiers.charAt(0) == '1',
                        tiers.length() > 1 && tiers.charAt(1) == '1',
                        tiers.length() > 2 && tiers.charAt(2) == '1',
                        tiers.length() > 3 && tiers.charAt(3) == '1',
                        dec(p[5]), dec(p[6]), p[7], p[8], dec(p[9]),
                        dec(p[10]));
                if (!r.id.isEmpty()) out.put(r.id, r);
            } catch (Exception ignored) {
                // a torn line is skipped, never guessed at
            }
        }
        return out;
    }

    public static String toTsv(Map<String, Rec> recs) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy source receipts - verified at bind, by the"
                + " census. A receipt proves the door was true when"
                + " Sandy took the source in; WORLD_CHANGED records"
                + " a door the world broke later.\n");
        sb.append("# id, name, round, proved date, tiers"
                + " (catalogue,page,stream,bytes), eyes, dialects,"
                + " state, changed date, changed how, note.\n");
        if (recs != null) {
            for (Rec r : recs.values()) {
                sb.append(enc(r.id)).append('\t').append(enc(r.name))
                        .append('\t').append(enc(r.round)).append('\t')
                        .append(r.provedDate).append('\t')
                        .append(r.catalogue ? '1' : '0')
                        .append(r.page ? '1' : '0')
                        .append(r.stream ? '1' : '0')
                        .append(r.bytes ? '1' : '0')
                        .append('\t').append(enc(r.eyes)).append('\t')
                        .append(enc(r.dialects)).append('\t').append(r.state)
                        .append('\t').append(r.changedDate).append('\t')
                        .append(enc(r.changedHow)).append('\t')
                        .append(enc(r.note)).append('\n');
            }
        }
        return sb.toString();
    }

    /** The tiers as plain words, for surfaces and the NOTES:
     *  e.g. "catalogue, page, stream, bytes". */
    public static String tiersWords(Rec r) {
        if (r == null) return "";
        List<String> w = new ArrayList<>();
        if (r.catalogue) w.add("catalogue");
        if (r.page) w.add("page");
        if (r.stream) w.add("stream");
        if (r.bytes) w.add("bytes");
        return String.join(", ", w);
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
