package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the re-verification budget walks first (v0.14.0 Addendum
 * 10(5)). Background re-checks are finite — a phone, a pipe, a
 * battery — so they are spent where people actually walk, in
 * this order:
 *
 *   1. KEPT PLACES — what someone is in the middle of;
 *   2. FAVORITES — the household's chosen shelves;
 *   3. RECENTLY WATCHED — still warm from the last visit;
 *   4. THE WIDER SHELVES — everything else, stalest first.
 *
 * Inside a class, the stalest verdict goes first. REALISM CAP,
 * stated plainly: the whole ocean is NOT re-probed; the queue is
 * bounded per run, and a link nobody walks near may sit unchecked
 * until someone walks near it — at which point its card shows the
 * honest "still checking" face until the check lands.
 */
public final class VerifyPriority {

    private VerifyPriority() {}

    public static final int CAT_KEPT = 0;
    public static final int CAT_FAVORITE = 1;
    public static final int CAT_RECENT = 2;
    public static final int CAT_SHELF = 3;

    public static int category(boolean kept, boolean favorite, boolean recent) {
        if (kept) return CAT_KEPT;
        if (favorite) return CAT_FAVORITE;
        if (recent) return CAT_RECENT;
        return CAT_SHELF;
    }

    public static final class Entry {
        public final String url, userAgent;
        public final int category;
        /** When the standing verdict was taken; 0 = never checked. */
        public final long checkedAt;

        public Entry(String url, String userAgent, int category, long checkedAt) {
            this.url = url; this.userAgent = userAgent;
            this.category = category; this.checkedAt = checkedAt;
        }
    }

    /** Category first; inside a class, a standing verdict going
     *  stale outranks a first look (checkedAt 0 = never checked
     *  walks last in its class), then stalest first. Capped. */
    public static List<Entry> order(List<Entry> in, int cap) {
        List<Entry> out = new ArrayList<>(in);
        out.sort((a, b) -> {
            if (a.category != b.category) {
                return Integer.compare(a.category, b.category);
            }
            boolean aNew = a.checkedAt == 0, bNew = b.checkedAt == 0;
            if (aNew != bNew) return aNew ? 1 : -1;
            return Long.compare(a.checkedAt, b.checkedAt);
        });
        if (cap > 0 && out.size() > cap) {
            return new ArrayList<>(out.subList(0, cap));
        }
        return out;
    }
}
