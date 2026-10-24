package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Kept places, filed (v0.11.0): the file-backed store behind
 * memory/kept_places.tsv. ChannelStore (positions, the kept live
 * place) and ReaderStore (book bookmarks) keep their public faces —
 * this class is where the bytes actually live now.
 *
 * Migration is per-kind and marker-guarded: the first time a kind is
 * needed, if no "#seeded" marker for it exists, the caller hands
 * over the legacy rows (read from the old preferences cluster) and
 * they are written into the file with the marker. After that the
 * file is the only truth; the frozen legacy copies are never read
 * again (recorded as a known deviation in the 0.11.0 NOTES).
 * Deleting every row of a kind on purpose can therefore never
 * resurrect the stale preferences — the marker remembers.
 */
public final class KeptPlaces {

    private KeptPlaces() {}

    private static List<MemoryFiles.KeptPlace> cache;

    private static synchronized List<MemoryFiles.KeptPlace> load(Context ctx) {
        if (cache == null) {
            cache = MemoryFiles.keptPlacesFromText(
                    MemoryHook.read(ctx, MemoryHook.KEPT_PLACES));
        }
        return cache;
    }

    private static synchronized void save(Context ctx) {
        MemoryHook.write(ctx, MemoryHook.KEPT_PLACES,
                MemoryFiles.keptPlacesToText(cache));
    }

    /** Test/maintenance escape: forget the in-memory copy. */
    public static synchronized void invalidate() { cache = null; }

    public static synchronized boolean seeded(Context ctx, String kind) {
        return MemoryFiles.findKept(load(ctx), "#seeded", kind) != null;
    }

    /** Write legacy rows for {@code kind} once, with the marker. */
    public static synchronized void seed(Context ctx, String kind,
                                         List<MemoryFiles.KeptPlace> rows) {
        List<MemoryFiles.KeptPlace> all = new ArrayList<>(load(ctx));
        if (MemoryFiles.findKept(all, "#seeded", kind) != null) return;
        for (MemoryFiles.KeptPlace r : rows) {
            if (MemoryFiles.findKept(all, r.kind, r.key) == null) all.add(r);
        }
        all.add(new MemoryFiles.KeptPlace("#seeded", kind, 0,
                System.currentTimeMillis(), ""));
        cache = all;
        save(ctx);
    }

    public static synchronized long getPosition(Context ctx, String contentKey) {
        MemoryFiles.KeptPlace r = MemoryFiles.findKept(load(ctx), "pos", contentKey);
        return r == null ? 0 : r.position;
    }

    public static synchronized void putPosition(Context ctx, String contentKey,
                                                long positionMs) {
        cache = MemoryFiles.upsertKept(load(ctx),
                new MemoryFiles.KeptPlace("pos", contentKey, positionMs,
                        System.currentTimeMillis(), ""));
        save(ctx);
    }

    public static synchronized String getExtra(Context ctx, String kind, String key) {
        MemoryFiles.KeptPlace r = MemoryFiles.findKept(load(ctx), kind, key);
        return r == null ? null : r.extra;
    }

    public static synchronized long getPlacePosition(Context ctx, String kind,
                                                     String key) {
        MemoryFiles.KeptPlace r = MemoryFiles.findKept(load(ctx), kind, key);
        return r == null ? 0 : r.position;
    }

    public static synchronized void putPlace(Context ctx, String kind, String key,
                                             long position, String extra) {
        cache = MemoryFiles.upsertKept(load(ctx),
                new MemoryFiles.KeptPlace(kind, key, position,
                        System.currentTimeMillis(), extra == null ? "" : extra));
        save(ctx);
    }

    public static synchronized void removePlace(Context ctx, String kind, String key) {
        List<MemoryFiles.KeptPlace> out = new ArrayList<>();
        for (MemoryFiles.KeptPlace r : load(ctx)) {
            if (!(r.kind.equals(kind) && r.key.equals(key))) out.add(r);
        }
        cache = out;
        save(ctx);
    }
}
