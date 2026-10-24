package com.talacore.talatv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The names a title is looked up under (v0.10.0): its own name first,
 * then the alternate names TVmaze itself lists (the /akas record) —
 * never a translation we invent. A Spanish show filed as "La Casa de
 * Papel" is also findable as "Money Heist" because TVmaze says the
 * world calls it that; a Japanese show carries its Japanese AKA for
 * the same reason. Matching runs in the title's own language world,
 * with its own names.
 */
public final class TitleNames {

    /** Cap on the set — a show with 40 AKAs still only needs its best few. */
    public static final int MAX_NAMES = 8;

    public static final class Name {
        public final String name;
        public final String countryName;   // "" when TVmaze states none
        public Name(String name, String countryName) {
            this.name = name;
            this.countryName = countryName == null ? "" : countryName;
        }
    }

    private TitleNames() {}

    /** Original first, AKAs after, duplicates by normalized form dropped. */
    public static List<Name> buildSet(String original, List<TvMaze.Aka> akas) {
        List<Name> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (original != null && !original.trim().isEmpty()) {
            out.add(new Name(original, ""));
            seen.add(EpisodeKey.normalize(original));
        }
        if (akas != null) {
            for (TvMaze.Aka a : akas) {
                if (out.size() >= MAX_NAMES) break;
                if (a.name == null || a.name.trim().isEmpty()) continue;
                if (!seen.add(EpisodeKey.normalize(a.name))) continue;
                out.add(new Name(a.name, a.countryName));
            }
        }
        return out;
    }

    /** Just the name strings, original first. */
    public static List<String> namesOf(List<Name> set) {
        List<String> out = new ArrayList<>();
        for (Name n : set) out.add(n.name);
        return out;
    }

    /**
     * The name to retry the Archive under when the original name came
     * back weak: the AKA the English-speaking world uses (country US
     * or GB, or the country-less primary alternate — TVmaze lists
     * "Money Heist" with no country), else the first AKA. Null when
     * the set holds nothing beyond the original.
     */
    public static String archiveFallbackName(List<Name> set) {
        if (set == null || set.size() < 2) return null;
        for (int i = 1; i < set.size(); i++) {
            Name n = set.get(i);
            if (n.countryName.isEmpty()
                    || n.countryName.equalsIgnoreCase("United States")
                    || n.countryName.equalsIgnoreCase("United Kingdom")) {
                return n.name;
            }
        }
        return set.get(1).name;
    }
}
