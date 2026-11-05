package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * CONTINUE WATCHING (run sandy-fixpass-0200, item 5) — the store
 * behind Home's first rail: what a person stopped mid-way, with
 * their place kept, one tap back into the player.
 *
 * House pattern, same as KeptPlaces/TimerModel: a file behind the
 * ONE memory hook (MemoryHook — memory/continue_watching.tsv),
 * TSV rows with free-text fields URL-encoded (TimerModel.enc/dec),
 * a static in-memory cache invalidated never and written through
 * on every change.
 *
 * The public writer API (the player side calls exactly this):
 *   upsert(Context, String key, String title, String image,
 *          String playUrl, String streamName,
 *          long positionMs, long durationMs)
 *   remove(Context, String key)
 *   list(Context) — what Home renders: newest first, finished
 *          and never-started entries excluded (see isVisible).
 *
 * Finished means the place reached 95% of a known duration;
 * never-started means no place at all (positionMs <= 0). Both are
 * the store's honest reading of the numbers it was handed — when
 * the duration is unknown (<= 0) nothing counts as finished.
 *
 * The ordering/filter/codec core is pure static (over Entry
 * lists and text), so the harness can pin it without Android;
 * only upsert/list/remove touch the phone.
 */
public final class ContinueWatching {

    private ContinueWatching() {}

    /** The file behind the memory hook. (MemoryHook's name
     *  constants live in MemoryHook, which this run does not edit;
     *  the name is carried here and passed through its door.) */
    public static final String FILE = "continue_watching.tsv";

    /** The rail never grows past this many kept places. */
    public static final int MAX_ENTRIES = 24;

    // ------------------------------------------------------------- entry

    /** One kept place: enough to re-enter the player honestly. */
    public static final class Entry {
        public String key = "";
        public String title = "";
        public String image = "";
        public String playUrl = "";
        public String streamName = "";
        public long positionMs = 0;
        public long durationMs = 0;
        public long updatedAtMs = 0;

        public Entry() {}
        public Entry(String key, String title, String image, String playUrl,
                     String streamName, long positionMs, long durationMs,
                     long updatedAtMs) {
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.image = image == null ? "" : image;
            this.playUrl = playUrl == null ? "" : playUrl;
            this.streamName = streamName == null ? "" : streamName;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.updatedAtMs = updatedAtMs;
        }
    }

    // ------------------------------------------------- pure core

    /** At or past 95% of a KNOWN duration. Integer math, no float:
     *  position/duration >= 19/20. Unknown duration never finishes. */
    public static boolean isFinished(Entry e) {
        return e != null && e.durationMs > 0
                && e.positionMs * 20 >= e.durationMs * 19;
    }

    /** What the rail shows: a real place, not yet finished. */
    public static boolean isVisible(Entry e) {
        return e != null && e.positionMs > 0 && !isFinished(e);
    }

    /**
     * The visible entries, newest (updatedAtMs) first. Ties keep
     * the incoming order. The input list is never touched.
     */
    public static List<Entry> visibleSorted(List<Entry> entries) {
        List<Entry> out = new ArrayList<>();
        if (entries == null) return out;
        for (Entry e : entries) if (isVisible(e)) out.add(e);
        out.sort((a, b) -> Long.compare(b.updatedAtMs, a.updatedAtMs));
        return out;
    }

    /**
     * Insert-or-replace by key, newest first, capped at
     * MAX_ENTRIES (the oldest fall off the end). Returns a NEW
     * list; the input is never touched.
     */
    public static List<Entry> upserted(List<Entry> entries, Entry row) {
        List<Entry> out = new ArrayList<>();
        if (entries != null) {
            for (Entry e : entries) {
                if (e != null && !e.key.equals(row.key)) out.add(e);
            }
        }
        out.add(row);
        out.sort((a, b) -> Long.compare(b.updatedAtMs, a.updatedAtMs));
        while (out.size() > MAX_ENTRIES) out.remove(out.size() - 1);
        return out;
    }

    /** Every entry but the keyed one, order kept. NEW list. */
    public static List<Entry> removed(List<Entry> entries, String key) {
        List<Entry> out = new ArrayList<>();
        if (entries == null) return out;
        for (Entry e : entries) {
            if (e != null && !e.key.equals(key)) out.add(e);
        }
        return out;
    }

    // ------------------------------------------------- TSV codec
    // continue_watching.tsv columns (free text URL-encoded):
    // key, positionMs, durationMs, updatedAtMs,
    // title, image, playUrl, streamName

    public static String entriesToText(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy continue watching - key, position, duration,\n");
        sb.append("# updated, title, image, play url, stream name.\n");
        sb.append("# Behind the memory hook (files/memory/continue_watching.tsv).\n");
        if (entries != null) {
            for (Entry e : entries) {
                if (e == null) continue;
                sb.append(TimerModel.enc(e.key)).append('\t')
                  .append(e.positionMs).append('\t')
                  .append(e.durationMs).append('\t')
                  .append(e.updatedAtMs).append('\t')
                  .append(TimerModel.enc(e.title)).append('\t')
                  .append(TimerModel.enc(e.image)).append('\t')
                  .append(TimerModel.enc(e.playUrl)).append('\t')
                  .append(TimerModel.enc(e.streamName)).append('\n');
            }
        }
        return sb.toString();
    }

    public static List<Entry> entriesFromText(String content) {
        List<Entry> out = new ArrayList<>();
        if (content == null) return out;
        for (String line : content.split("\n")) {
            // No trim before the split (house rule from
            // MemoryFiles): the last field may be an empty
            // encoded value whose separator must survive.
            String probe = line.trim();
            if (probe.isEmpty() || probe.startsWith("#")) continue;
            String s = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
            String[] c = s.split("\t", -1);
            if (c.length < 8) continue;
            try {
                Entry e = new Entry();
                e.key = TimerModel.dec(c[0]);
                e.positionMs = Long.parseLong(c[1]);
                e.durationMs = Long.parseLong(c[2]);
                e.updatedAtMs = Long.parseLong(c[3]);
                e.title = TimerModel.dec(c[4]);
                e.image = TimerModel.dec(c[5]);
                e.playUrl = TimerModel.dec(c[6]);
                e.streamName = TimerModel.dec(c[7]);
                if (!e.key.isEmpty()) out.add(e);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    // ------------------------------------------------- the store

    private static List<Entry> cache;

    private static synchronized List<Entry> load(Context ctx) {
        if (cache == null) {
            cache = entriesFromText(MemoryHook.read(ctx, FILE));
        }
        return cache;
    }

    private static synchronized void save(Context ctx) {
        MemoryHook.write(ctx, FILE, entriesToText(cache));
    }

    /** Test/maintenance escape: forget the in-memory copy. */
    public static synchronized void invalidate() { cache = null; }

    /**
     * Record (or move) a kept place. updatedAtMs is stamped here —
     * the caller hands over where the viewer is, not when.
     */
    public static synchronized void upsert(Context ctx, String key,
                                           String title, String image,
                                           String playUrl, String streamName,
                                           long positionMs, long durationMs) {
        if (ctx == null || key == null || key.isEmpty()) return;
        Entry e = new Entry(key, title, image, playUrl, streamName,
                positionMs, durationMs, System.currentTimeMillis());
        cache = upserted(load(ctx), e);
        save(ctx);
    }

    /** The rail's entries: visible only, newest first. */
    public static synchronized List<Entry> list(Context ctx) {
        if (ctx == null) return new ArrayList<>();
        return visibleSorted(load(ctx));
    }

    public static synchronized void remove(Context ctx, String key) {
        if (ctx == null || key == null) return;
        cache = removed(load(ctx), key);
        save(ctx);
    }
}
