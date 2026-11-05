package com.talacore.talatv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SEARCH TIGHTENING (run sandy-fixpass-0200, item 1) — the pure
 * re-ranker behind Home's title search.
 *
 * TVmaze's own relevance order is a good DISCOVERY order, but it
 * buries the obvious answer: a search for "house" should stand
 * "House" itself at the head, not somewhere down the spill. This
 * class re-orders a result list into tightness tiers against the
 * query, and — the half James asked for by name — keeps the loose
 * spill BELOW the tightened head, in its incoming order, instead
 * of throwing it away:
 *
 *   tier 0  the title IS the query (case-insensitive, trimmed,
 *           whitespace-collapsed)
 *   tier 1  the title starts with the query
 *   tier 2  a word inside the title starts with the query, or the
 *           title contains the query at a word boundary
 *   tier 3  the title contains the query anywhere
 *   tier 4  no containment — the spill, kept last
 *
 * Within a tier the incoming order is preserved exactly, EXCEPT
 * that for multi-word queries, within tiers 2–3, a title holding
 * MORE of the query's words stands ahead of one holding fewer
 * (ties stay in incoming order). No Android imports, no state:
 * the harness pins this class with plain fixtures, e.g.
 *   query "house" over [House Hunters, House, Greenhouse,
 *   House of Cards, Full House]
 *     -> [House, House Hunters, House of Cards, Full House,
 *         Greenhouse]
 */
public final class SearchRank {

    /** The name a rankable item answers to. */
    public interface NameOf<T> {
        String nameOf(T item);
    }

    private SearchRank() {}

    /**
     * A NEW list holding the same items, re-ordered by tier as
     * described above. The input list is never touched.
     */
    public static <T> List<T> rank(String query, List<T> items, NameOf<T> fn) {
        List<T> out = new ArrayList<>();
        if (items == null) return out;
        final String q = normalize(query);
        if (q.isEmpty() || fn == null) {
            out.addAll(items);
            return out;
        }
        final String[] qWords = q.split(" ");
        // Decorate with the incoming index so every tie — tier,
        // and word-count inside tiers 2–3 — resolves to the
        // incoming order explicitly, not by sort-algorithm luck.
        List<Scored<T>> scored = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            String n = normalize(fn.nameOf(item));
            int tier = tierOf(q, n);
            int words = (tier == 2 || tier == 3)
                    ? queryWordsIn(qWords, n) : 0;
            scored.add(new Scored<>(item, i, tier, words));
        }
        scored.sort((a, b) -> {
            if (a.tier != b.tier) return a.tier - b.tier;
            if (a.words != b.words) return b.words - a.words;
            return a.index - b.index;
        });
        for (Scored<T> s : scored) out.add(s.item);
        return out;
    }

    /** The tier one normalized title earns against the query. */
    static int tierOf(String q, String n) {
        if (n.equals(q)) return 0;
        if (n.startsWith(q)) return 1;
        if (containsAtWordBoundary(n, q) || aWordStartsWith(n, q)) return 2;
        if (n.contains(q)) return 3;
        return 4;
    }

    /** True when the title holds the query starting at a word
     *  boundary (start of the title, or right after a space). The
     *  start-of-title case is tier 1 by the time we are asked. */
    private static boolean containsAtWordBoundary(String n, String q) {
        int from = 0;
        while (true) {
            int at = n.indexOf(q, from);
            if (at < 0) return false;
            if (at == 0 || n.charAt(at - 1) == ' ') return true;
            from = at + 1;
        }
    }

    /** True when any single word of the title starts with the
     *  query. Matters when the query itself carries punctuation
     *  a boundary check would miss; identical to the boundary
     *  check for plain word queries. */
    private static boolean aWordStartsWith(String n, String q) {
        for (String w : n.split(" ")) {
            if (w.startsWith(q)) return true;
        }
        return false;
    }

    /** How many DISTINCT query words the title holds — a word
     *  counts when it stands in the title as a whole word or as
     *  the head of a title word ("cards" holds "card"). */
    private static int queryWordsIn(String[] qWords, String n) {
        if (qWords.length < 2) return 0; // single-word: all equal
        Set<String> counted = new HashSet<>();
        String[] words = n.split(" ");
        for (String qw : qWords) {
            if (qw.isEmpty() || counted.contains(qw)) continue;
            for (String w : words) {
                if (w.startsWith(qw)) { counted.add(qw); break; }
            }
        }
        return counted.size();
    }

    /** Trim, collapse every whitespace run to one space, fold case. */
    static String normalize(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }

    private static final class Scored<T> {
        final T item;
        final int index;
        final int tier;
        final int words;
        Scored(T item, int index, int tier, int words) {
            this.item = item; this.index = index;
            this.tier = tier; this.words = words;
        }
    }
}
