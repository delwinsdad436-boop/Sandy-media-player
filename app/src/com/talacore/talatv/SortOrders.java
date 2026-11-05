package com.talacore.talatv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * SORTING (run sandy-fixpass-0200, item 12) — the pure ordering
 * half behind James's order: "Need to have a way to sort by
 * number. And also alphabetical ties the languages."
 *
 * Three standing modes for a channel list:
 *   DEFAULT  the list is left exactly as it stands — the
 *            comparator says every pair is equal, so a stable
 *            sort changes nothing;
 *   NUMBER   channels that carry a number IN THEIR NAME first,
 *            ascending by that number; unnumbered channels after
 *            them; ties (same number, or both unnumbered) keep
 *            their incoming order;
 *   AZ       display name A→Z, case-insensitive; ties keep their
 *            incoming order.
 *
 * There is NO channel-number field to sort by — M3uParser parses
 * no tvg-chno — so the number is read out of the display name
 * itself by {@link #channelNumber(String)}. The shapes it
 * accepts, and no others, are a run of ASCII digits at the very
 * start of the trimmed name, followed by:
 *   - the end of the name            ("12")
 *   - whitespace                     ("12 News", "12 - News")
 *   - a full stop                    ("12.", "12. News")
 *   - a hyphen                       ("12-News")
 * Leading zeros are fine in any of them ("012 News" is 12).
 * A digit run fused to a letter is part of a name, not a number
 * ("3sat" and "5USA" carry none), and a name with no leading
 * digits carries none. -1 is the "carries none" answer.
 *
 * azCompare mirrors CatalogIndex.sortBy's SortMode.AZ name rule
 * EXACTLY — plain String.compareToIgnoreCase, no stripping of a
 * leading "The " — because consistency with the catalogue's
 * standing A–Z beats a novel rule here. (CatalogIndex breaks its
 * remaining ties by show id; this class is generic over a name
 * extractor and has no id, so its ties stay stable instead.)
 * Language names use the same compare and likewise strip no
 * articles.
 *
 * No Android imports, no state: the harness pins this class with
 * plain fixtures, e.g.
 *   NUMBER over ["BBC One", "12 News", "3sat", "2 World", "012 Extra"]
 *     -> ["2 World", "12 News", "012 Extra", "BBC One", "3sat"]
 */
public final class SortOrders {

    /** The standing sort modes, in the Live TV cycle's order. */
    public enum Mode { DEFAULT, NUMBER, AZ }

    /** The name an orderable item answers to. */
    public interface NameOf<T> {
        String nameOf(T item);
    }

    private SortOrders() {}

    // ---------------------------------------------------- the number

    /**
     * The number a channel carries in its name — see the class
     * header for the exact shapes accepted — or -1 when the name
     * carries none. The value is the digit run's own value, so
     * leading zeros collapse ("012" is 12, "0" is 0).
     */
    public static int channelNumber(String displayName) {
        if (displayName == null) return -1;
        String s = displayName.trim();
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == 0) return -1;
        if (i < s.length()) {
            char next = s.charAt(i);
            if (next != '.' && next != '-'
                    && !Character.isWhitespace(next)) return -1;
        }
        long v = 0;
        for (int j = 0; j < i; j++) {
            v = v * 10 + (s.charAt(j) - '0');
            if (v > Integer.MAX_VALUE) return -1;
        }
        return (int) v;
    }

    // ---------------------------------------------------- the compare

    /**
     * A→Z for titles and language names: case-insensitive, null
     * reads as "", and NO leading article is stripped — the exact
     * name rule CatalogIndex's SortMode.AZ already stands on.
     */
    public static int azCompare(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        return x.compareToIgnoreCase(y);
    }

    /** The pair comparison for one mode, over extracted names. */
    public static int compareNames(Mode mode, String a, String b) {
        if (mode == null || mode == Mode.DEFAULT) return 0;
        if (mode == Mode.AZ) return azCompare(a, b);
        // NUMBER
        int na = channelNumber(a);
        int nb = channelNumber(b);
        if (na >= 0 && nb >= 0) return Integer.compare(na, nb);
        if (na >= 0) return -1;   // numbered stands first
        if (nb >= 0) return 1;
        return 0;                  // both unnumbered: incoming order
    }

    /** A comparator over items, through their name extractor. */
    public static <T> Comparator<T> comparator(Mode mode, NameOf<T> fn) {
        final Mode m = mode == null ? Mode.DEFAULT : mode;
        final NameOf<T> f = fn;
        return (a, b) -> compareNames(m,
                f == null || a == null ? null : f.nameOf(a),
                f == null || b == null ? null : f.nameOf(b));
    }

    /**
     * A NEW list holding the same items in the mode's order; the
     * input list is never touched. Ties resolve to the incoming
     * index explicitly (decorated, the SearchRank way), so the
     * stability the modes promise does not rest on the sort
     * algorithm. DEFAULT returns the incoming order untouched.
     */
    public static <T> List<T> order(List<T> items, Mode mode, NameOf<T> fn) {
        List<T> out = new ArrayList<>();
        if (items == null) return out;
        if (mode == null || mode == Mode.DEFAULT || fn == null) {
            out.addAll(items);
            return out;
        }
        List<Scored<T>> scored = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            scored.add(new Scored<>(item, i,
                    item == null ? null : fn.nameOf(item)));
        }
        scored.sort((a, b) -> {
            int c = compareNames(mode, a.name, b.name);
            return c != 0 ? c : a.index - b.index;
        });
        for (Scored<T> s : scored) out.add(s.item);
        return out;
    }

    private static final class Scored<T> {
        final T item;
        final int index;
        final String name;
        Scored(T item, int index, String name) {
            this.item = item; this.index = index; this.name = name;
        }
    }
}
