package com.talacore.talatv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * One tap to the next episode (v0.14.0, James: "if we are one
 * click, do you click into the next episode?") — the pure half:
 * given a show's episode list in TVmaze's shape, which episode
 * follows this one? Air order is (season, number); specials
 * (season 0) are a side shelf — the next episode after a regular
 * episode is the next REGULAR episode, and a special's next is
 * the next special. When the current episode isn't in the list
 * (a stale page), the next is the first episode that sorts
 * after it. The last episode has no next: null, said plainly.
 */
public final class NextEpisode {

    private NextEpisode() {}

    public static final Comparator<TvMaze.Episode> AIR_ORDER =
            Comparator.comparingInt((TvMaze.Episode e) -> e.season)
                    .thenComparingInt(e -> e.number);

    public static TvMaze.Episode next(List<TvMaze.Episode> episodes,
                                      int season, int number) {
        if (episodes == null || episodes.isEmpty()) return null;
        List<TvMaze.Episode> sorted = new ArrayList<>(episodes);
        sorted.sort(AIR_ORDER);
        // exact position: the entry right after, in the same shelf
        for (int i = 0; i < sorted.size(); i++) {
            TvMaze.Episode e = sorted.get(i);
            if (e.season == season && e.number == number) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    TvMaze.Episode c = sorted.get(j);
                    if ((c.season == 0) == (season == 0)) return c;
                }
                return null;
            }
        }
        // not listed: the first entry that sorts after, same shelf
        for (TvMaze.Episode c : sorted) {
            if ((c.season == 0) != (season == 0)) continue;
            if (c.season > season || (c.season == season && c.number > number)) {
                return c;
            }
        }
        return null;
    }

    /** "S02E03 · The One With the List" — how a next tile names it. */
    public static String label(TvMaze.Episode e) {
        if (e == null) return "";
        String base = String.format(Locale.US, "S%02dE%02d", e.season, e.number);
        return e.name == null || e.name.isEmpty() ? base : base + " · " + e.name;
    }
}
