package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The voice book (v0.6.0) — Delwin's spec: a character's voice is
 * SAVED ("it's just a string of code") so the same character keeps
 * the same voice episode to episode, season to season.
 *
 * One book per show. Each discovered speaker becomes a cast entry:
 *   - speakerKey   the diarizer's cluster label (e.g. SPEAKER_00)
 *   - voiceprint   the centroid embedding of the ORIGINAL voice
 *   - ttsVoice     the Android TTS voice assigned to the character
 *                  ("" = not assigned yet; the renderer falls back
 *                  to a numbered voice slot with its own pitch)
 *   - pitch, rate  the casting's sound
 *   - name         a human name for the character, when known
 *   - nearMatch    set when an assignment was a near call — flagged
 *                  for a human, never silently swapped (Delwin's
 *                  stated limits, built in)
 *
 * Matching is cosine similarity against the saved voiceprints:
 *   >= 0.82  the same speaker — reuse their voice
 *   0.70–0.82 near — assign NOTHING automatically; the entry is
 *             flagged and a human decides
 *   <  0.70  a new character — a new entry, a new voice
 * A recast actor (a genuinely different voice for an old role) lands
 * in NEW, which is the honest outcome: the book forks, it does not
 * force the old voice onto a new person.
 *
 * Pure logic + org.json; the harness drives the matching math with
 * synthetic voiceprints and round-trips the persistence format.
 */
public final class VoiceBook {

    public static final double MATCH_MIN = 0.82;
    public static final double NEAR_MIN = 0.70;

    public static final class CastEntry {
        public String speakerKey = "";
        public String name = "";
        public String ttsVoice = "";
        public double pitch = 1.0;
        public double rate = 1.0;
        public float[] voiceprint = new float[0];
        public boolean nearMatch = false;
        /** Display label before a name is known: "Voice 1", "Voice 2"… */
        public String label = "";
        /** Season this casting belongs to; 0 = the whole series.
         *  Profiles are scoped per show AND per season (James's
         *  rule): the series-wide entry is the character's voice
         *  everywhere; a season entry exists when that season's
         *  casting genuinely forked (a recast), and it governs
         *  only its own season — history is never rewritten. */
        public int season = 0;
    }

    public static final class Book {
        public String showKey = "";
        public String showTitle = "";
        public final List<CastEntry> cast = new ArrayList<>();
        /** This book's match calibration. Cosine scales are a
         *  property of the embedding model that made the prints,
         *  not a universal constant: books fed by the diarization
         *  pack carry the pack's measured numbers (written by the
         *  dub cook); books without a model keep the classic
         *  conservative defaults. */
        public double matchMin = MATCH_MIN;
        public double nearMin = NEAR_MIN;
    }

    public enum MatchKind { MATCH, NEAR, NEW }

    public static final class Match {
        public MatchKind kind;
        public CastEntry entry;   // null when kind == NEW
        public double score;
    }

    private VoiceBook() {}

    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * (double) a[i];
            nb += b[i] * (double) b[i];
        }
        if (na <= 0 || nb <= 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Match a freshly heard speaker centroid against the book. */
    public static Match match(Book book, float[] centroid) {
        return match(book, centroid, 0);
    }

    /**
     * Season-aware match: the candidate pool is the series-wide
     * entries plus this season's own — a voice cast for season 2
     * never answers for season 1, and the series voice answers
     * everywhere it hasn't honestly forked.
     */
    public static Match match(Book book, float[] centroid, int season) {
        Match m = new Match();
        m.kind = MatchKind.NEW;
        m.score = 0;
        if (book == null || centroid == null || centroid.length == 0) return m;
        CastEntry best = null;
        double bestScore = -1;
        for (CastEntry e : book.cast) {
            if (e.season != 0 && e.season != season) continue;
            if (e.voiceprint == null || e.voiceprint.length != centroid.length) continue;
            double s = cosine(centroid, e.voiceprint);
            if (s > bestScore) { bestScore = s; best = e; }
        }
        if (best == null) return m;
        m.score = bestScore;
        if (bestScore >= book.matchMin) { m.kind = MatchKind.MATCH; m.entry = best; }
        else if (bestScore >= book.nearMin) { m.kind = MatchKind.NEAR; m.entry = best; }
        return m;
    }

    /** Enroll a speaker as a new cast entry (auto label "Voice N"). */
    public static CastEntry enroll(Book book, String speakerKey, float[] centroid) {
        return enroll(book, speakerKey, centroid, 0);
    }

    /** Enroll, scoped to a season (0 = the whole series). */
    public static CastEntry enroll(Book book, String speakerKey,
                                   float[] centroid, int season) {
        CastEntry e = new CastEntry();
        e.speakerKey = speakerKey == null ? "" : speakerKey;
        e.label = "Voice " + (book.cast.size() + 1);
        e.season = season;
        if (centroid != null) e.voiceprint = centroid.clone();
        book.cast.add(e);
        return e;
    }

    /**
     * Fold a confirmed re-hearing into a saved voiceprint (running
     * average) so the print tracks the actor, not one loud scene.
     * Only ever called on a MATCH — never on a NEAR.
     */
    public static void foldVoiceprint(CastEntry e, float[] centroid, double alpha) {
        if (e == null || centroid == null || e.voiceprint.length != centroid.length) return;
        for (int i = 0; i < centroid.length; i++) {
            e.voiceprint[i] = (float) (e.voiceprint[i] * (1 - alpha) + centroid[i] * alpha);
        }
    }

    // ------------------------------------------------------------ JSON

    public static String toJson(Book b) throws Exception {
        JSONObject o = new JSONObject();
        o.put("showKey", b.showKey);
        o.put("showTitle", b.showTitle);
        o.put("matchMin", b.matchMin);
        o.put("nearMin", b.nearMin);
        JSONArray arr = new JSONArray();
        for (CastEntry e : b.cast) {
            JSONObject eo = new JSONObject();
            eo.put("speakerKey", e.speakerKey);
            eo.put("name", e.name);
            eo.put("label", e.label);
            eo.put("ttsVoice", e.ttsVoice);
            eo.put("pitch", e.pitch);
            eo.put("rate", e.rate);
            eo.put("nearMatch", e.nearMatch);
            eo.put("season", e.season);
            JSONArray vp = new JSONArray();
            for (float f : e.voiceprint) vp.put((double) f);
            eo.put("voiceprint", vp);
            arr.put(eo);
        }
        o.put("cast", arr);
        return o.toString();
    }

    public static Book fromJson(String text) throws Exception {
        Book b = new Book();
        JSONObject o = new JSONObject(text);
        b.showKey = o.optString("showKey", "");
        b.showTitle = o.optString("showTitle", "");
        b.matchMin = o.optDouble("matchMin", MATCH_MIN);
        b.nearMin = o.optDouble("nearMin", NEAR_MIN);
        JSONArray arr = o.optJSONArray("cast");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject eo = arr.getJSONObject(i);
                CastEntry e = new CastEntry();
                e.speakerKey = eo.optString("speakerKey", "");
                e.name = eo.optString("name", "");
                e.label = eo.optString("label", "");
                e.ttsVoice = eo.optString("ttsVoice", "");
                e.pitch = eo.optDouble("pitch", 1.0);
                e.rate = eo.optDouble("rate", 1.0);
                e.nearMatch = eo.optBoolean("nearMatch", false);
                e.season = eo.optInt("season", 0);
                JSONArray vp = eo.optJSONArray("voiceprint");
                if (vp != null) {
                    e.voiceprint = new float[vp.length()];
                    for (int j = 0; j < vp.length(); j++) {
                        e.voiceprint[j] = (float) vp.optDouble(j, 0);
                    }
                }
                b.cast.add(e);
            }
        }
        return b;
    }
}
