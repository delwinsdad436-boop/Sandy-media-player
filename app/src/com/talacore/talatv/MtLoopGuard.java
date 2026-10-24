package com.talacore.talatv;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The loop guard for machine-translated reading (v0.8.0) — the
 * repetition principle of the cooker's hallucination guard,
 * adapted honestly to MT output.
 *
 * The cooker's guard leans on ASR scores (logprob / no-speech)
 * that a text->text engine never produces, so running it verbatim
 * over translations would be theater. What survives translation
 * is the repetition principle: a healthy translator given
 * DIFFERENT source sentences does not return the SAME sentence
 * again and again. When >= 3 distinct source chunks normalize to
 * one identical target, the target is looping; the first rendering
 * is kept (it may be right), the repeats are dropped, and the
 * drops are COUNTED — the reader hears the count, never a silent
 * skip. Same judgment as the cookbook: repetition is the smoke,
 * whatever the fire.
 */
public final class MtLoopGuard {

    /** Distinct sources mapping to one normalized target before it loops. */
    public static final int LOOP_AT = 3;

    private final Map<String, Set<String>> sourcesByTarget = new HashMap<>();
    private final Map<String, String> keptForTarget = new HashMap<>();
    private int dropped = 0;

    /** Verdict for one translated chunk. */
    public enum Verdict { KEEP, DROP_LOOP }

    /**
     * Feed one (source, translation) pair. KEEP the first rendering
     * of a target and any target with < LOOP_AT distinct sources;
     * DROP_LOOP once LOOP_AT distinct sources have produced it.
     */
    public Verdict feed(String source, String translated) {
        String t = normalize(translated);
        String s = normalize(source);
        if (t.isEmpty()) return Verdict.KEEP; // empties are the caller's state
        Set<String> sources = sourcesByTarget.computeIfAbsent(t, k -> new HashSet<>());
        boolean newSource = sources.add(s);
        if (sources.size() >= LOOP_AT && keptForTarget.containsKey(t)
                && !keptForTarget.get(t).equals(s) && newSource) {
            dropped++;
            return Verdict.DROP_LOOP;
        }
        if (!keptForTarget.containsKey(t)) keptForTarget.put(t, s);
        return Verdict.KEEP;
    }

    /** How many chunks this book's reading has dropped as loops. */
    public int dropped() { return dropped; }

    /** A new book (or a new sitting from the top) resets the guard. */
    public void reset() {
        sourcesByTarget.clear();
        keptForTarget.clear();
        dropped = 0;
    }

    /** Lowercase, letters/digits only, single-spaced — the compare shape. */
    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean lastSpace = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
                lastSpace = false;
            } else if (!lastSpace) {
                sb.append(' ');
                lastSpace = true;
            }
        }
        return sb.toString().trim();
    }
}
