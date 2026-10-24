package com.talacore.talatv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The hallucination guard (v0.6.0) — a hard requirement from the
 * phone-class bench (work/dub-lab/PHONE-BENCH-2026-10-17.md), not a
 * nicety. On real anime audio, EVERY recognizer tested fabricated
 * lines over dialogue-free music/effects, and the pipeline happily
 * translated the inventions into finished subtitles:
 *
 *   base:  「JR東日本E233系電車」 — "JRE233 train." — three times
 *          (a 2010s Tokyo commuter train, hallucinated into a
 *          1945 film)
 *   tiny:  「王様はワンクにの知ちたりです」 — "The king knows the
 *          mink." — five times
 *   base also turned music-passage filler (「ん」) into "Mm-hmm."
 *          subtitles, four in a row.
 *
 * The fabrications are mechanically detectable: they repeat verbatim
 * and they sit in windows the recognizer itself scores as
 * non-speech / low-confidence. This guard is the mechanical check,
 * run on the SOURCE segments before anything is translated or
 * rendered. A dropped segment is never translated, never voiced,
 * never shown. A flagged segment is kept but marked — uncertainty is
 * shown, never silently polished (UTX Law 3).
 *
 * Pure logic (no Android imports); the harness feeds it the bench's
 * real segment files, fabrications included.
 */
public final class HallucinationGuard {

    /** One recognizer segment, with the scores the recognizer gave it. */
    public static final class Segment {
        public double start, end;
        public String text = "";
        public double avgLogprob = 0.0;
        public double noSpeechProb = 0.0;
        public Segment() {}
        public Segment(double start, double end, String text,
                       double avgLogprob, double noSpeechProb) {
            this.start = start; this.end = end; this.text = text;
            this.avgLogprob = avgLogprob; this.noSpeechProb = noSpeechProb;
        }
        public double duration() { return Math.max(0.0, end - start); }
    }

    public enum Verdict { KEEP, FLAG, DROP }

    public static final class Decision {
        public Verdict verdict = Verdict.KEEP;
        public String reason = "";
    }

    public static final class Result {
        public final List<Decision> decisions = new ArrayList<>();
        public int kept, flagged, dropped;
        /** The segments that survive (KEEP + FLAG), in order. */
        public List<Segment> surviving(List<Segment> in) {
            List<Segment> out = new ArrayList<>();
            for (int i = 0; i < in.size(); i++) {
                if (decisions.get(i).verdict != Verdict.DROP) out.add(in.get(i));
            }
            return out;
        }
        /** True when segment i survived but was marked uncertain. */
        public boolean isFlagged(int i) {
            return decisions.get(i).verdict == Verdict.FLAG;
        }
    }

    // Thresholds — named, and justified by the bench numbers:
    /** The recognizer itself says this window is probably not speech. */
    public static final double NO_SPEECH_DROP = 0.60;
    /** Below this average logprob the text is not trustworthy at all. */
    public static final double LOGPROB_DROP = -1.05;
    /** Below this, keep the line but mark it uncertain. */
    public static final double LOGPROB_FLAG = -0.80;
    /** An occurrence counts toward a repetition pattern when suspect. */
    public static final double SUSPECT_NO_SPEECH = 0.35;
    public static final double SUSPECT_DURATION = 1.2;
    /** Unbroken runs longer than this get a second look. */
    public static final double LONG_BLOB_SEC = 25.0;

    private HallucinationGuard() {}

    /** Text identity for repetition detection: letters/digits only. */
    public static String normalize(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static boolean suspect(Segment s) {
        return s.noSpeechProb >= SUSPECT_NO_SPEECH
                || s.avgLogprob <= LOGPROB_FLAG
                || s.duration() <= SUSPECT_DURATION;
    }

    public static Result apply(List<Segment> in) {
        Result r = new Result();
        int n = in.size();
        for (int i = 0; i < n; i++) r.decisions.add(new Decision());

        // Repetition census over the whole cook: a line invented over
        // music comes back verbatim, again and again. Real repeated
        // dialogue ("Run! Run!") is short, loud and confidently heard
        // — the suspect test is what separates the two.
        Map<String, List<Integer>> byText = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String key = normalize(in.get(i).text);
            if (key.isEmpty()) continue;
            byText.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        boolean[] repetitionDrop = new boolean[n];
        boolean[] fillerDrop = new boolean[n];
        for (Map.Entry<String, List<Integer>> e : byText.entrySet()) {
            List<Integer> idxs = e.getValue();
            if (idxs.size() < 3) continue;
            int suspects = 0;
            double noSpeechSum = 0;
            for (int i : idxs) {
                if (suspect(in.get(i))) suspects++;
                noSpeechSum += in.get(i).noSpeechProb;
            }
            if (suspects * 2 >= idxs.size()) {
                for (int i : idxs) repetitionDrop[i] = true;
            } else if (e.getKey().length() <= 2 && idxs.size() >= 4) {
                // A one-or-two-character filler (「ん」) looped over
                // a music passage — the bench's "Mm-hmm." x4 case:
                // base emitted the hum 13 times; the looped copies
                // are the ones the recognizer was lukewarm about
                // (logprob <= -0.50 or noSpeech >= 0.30). A real
                // backchannel said once or twice, confidently,
                // survives untouched.
                for (int i : idxs) {
                    Segment s = in.get(i);
                    if (s.avgLogprob <= -0.50 || s.noSpeechProb >= 0.30) {
                        fillerDrop[i] = true;
                    }
                }
            }
        }

        for (int i = 0; i < n; i++) {
            Segment s = in.get(i);
            Decision d = r.decisions.get(i);
            if (repetitionDrop[i]) {
                d.verdict = Verdict.DROP;
                d.reason = "the same line came back again and again over "
                        + "windows the recognizer scored as not-speech — "
                        + "an invention, not dialogue";
            } else if (fillerDrop[i]) {
                d.verdict = Verdict.DROP;
                d.reason = "a filler sound looped over a passage with "
                        + "no speech in it";
            } else if (s.noSpeechProb >= NO_SPEECH_DROP) {
                d.verdict = Verdict.DROP;
                d.reason = "the recognizer scored this window as "
                        + "probably not speech";
            } else if (s.avgLogprob <= LOGPROB_DROP) {
                d.verdict = Verdict.DROP;
                d.reason = "the recognizer was too unsure of these "
                        + "words to pass them on";
            } else if (s.duration() > LONG_BLOB_SEC && s.avgLogprob <= LOGPROB_FLAG) {
                d.verdict = Verdict.DROP;
                d.reason = "one long unbroken run the recognizer was "
                        + "unsure of — the run-on blob case";
            } else if (s.duration() > LONG_BLOB_SEC) {
                d.verdict = Verdict.FLAG;
                d.reason = "one long unbroken run — kept, but marked";
            } else if (s.avgLogprob <= LOGPROB_FLAG) {
                d.verdict = Verdict.FLAG;
                d.reason = "the recognizer was unsure of these words — "
                        + "kept, but marked";
            }
            switch (d.verdict) {
                case DROP: r.dropped++; break;
                case FLAG: r.flagged++; break;
                default: r.kept++;
            }
        }
        return r;
    }
}
