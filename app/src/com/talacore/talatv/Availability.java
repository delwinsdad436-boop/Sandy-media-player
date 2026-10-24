package com.talacore.talatv;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tile-level availability, honestly bounded (v0.10.0).
 *
 * A catalogue tile may call itself "on your shelves" only on evidence
 * this phone already holds: a loaded playlist entry or an indexed
 * library file whose name carries the show. This class turns entry
 * names into candidate show keys — the part of an episode file's name
 * before its SxxExx marker, a movie file's cleaned title (with and
 * without its year) — and answers whether a show's name is carried.
 *
 * What this is NOT: a promise that a specific episode plays. Episode
 * proof still happens in the Sources screen, per episode, as always.
 * And the Archive is not consulted here — a show counts as
 * Archive-proven only after a Sources visit actually found it there
 * (ChannelStore's record), never by guessing.
 */
public final class Availability {

    private Availability() {}

    /** Candidate show keys one playlist/library entry name offers. */
    public static Set<String> candidateKeys(String entryName) {
        Set<String> out = new HashSet<>();
        if (entryName == null || entryName.isEmpty()) return out;
        int[] span = EpisodeKey.parseSpan(entryName);
        if (span != null) {
            // "Bonanza S01E01 - ..." -> the show lives before the marker
            String head = entryName.substring(0, Math.max(0, span[2]));
            String headNorm = EpisodeKey.normalize(head);
            if (!headNorm.isEmpty()) out.add(headNorm);
        }
        String cleaned = EpisodeKey.normalize(
                SourceFinder.cleanTitleForSearch(entryName));
        if (!cleaned.isEmpty()) {
            if (span == null) out.add(cleaned);
            // "night of the living dead 1968" also answers to the bare title
            String noYear = cleaned.replaceAll("\\s(19|20)\\d{2}$", "");
            if (!noYear.equals(cleaned) && !noYear.isEmpty()) out.add(noYear);
        }
        return out;
    }

    /** The key set for a whole shelf of entry names. */
    public static Set<String> buildKeySet(List<String> entryNames) {
        Set<String> out = new HashSet<>();
        if (entryNames == null) return out;
        for (String n : entryNames) out.addAll(candidateKeys(n));
        return out;
    }

    /**
     * True when any candidate key IS this show's name or contains it
     * as a whole phrase ("bonanza the lost episodes" carries "Bonanza";
     * "office space" does not carry "The Office").
     */
    public static boolean carries(Set<String> keys, String showName) {
        return new Shelf(keys).carries(showName);
    }

    /** Any of the show's names (original + alternates) carried. */
    public static boolean carriesAny(Set<String> keys, List<String> showNames) {
        return new Shelf(keys).carriesAny(showNames);
    }

    /**
     * A built shelf of candidate keys with a token index, so asking
     * "carried?" per tile stays cheap even with ten thousand entries:
     * exact key hits answer directly; the phrase fallback only scans
     * keys that contain the show name's first token as a whole token
     * (a necessary condition for a phrase match).
     */
    public static final class Shelf {
        private final Set<String> keys;
        private final java.util.Map<String, List<String>> byFirstToken =
                new java.util.HashMap<>();

        public Shelf(Set<String> keys) {
            this.keys = keys == null ? new HashSet<>() : keys;
            for (String k : this.keys) {
                java.util.Set<String> seen = new HashSet<>();
                for (String tok : k.split(" ")) {
                    if (!tok.isEmpty() && seen.add(tok)) {
                        byFirstToken.computeIfAbsent(tok,
                                x -> new java.util.ArrayList<>()).add(k);
                    }
                }
            }
        }

        public boolean carries(String showName) {
            if (keys.isEmpty() || showName == null) return false;
            String want = EpisodeKey.normalize(showName);
            if (want.isEmpty()) return false;
            if (keys.contains(want)) return true;
            String first = want.split(" ")[0];
            List<String> candidates = byFirstToken.get(first);
            if (candidates == null) return false;
            for (String k : candidates) {
                if (EpisodeKey.containsPhrase(k, want)) return true;
            }
            return false;
        }

        public boolean carriesAny(List<String> showNames) {
            if (showNames == null) return false;
            for (String n : showNames) if (carries(n)) return true;
            return false;
        }

        public int size() { return keys.size(); }
    }
}
