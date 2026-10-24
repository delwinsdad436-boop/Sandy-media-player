package com.talacore.talatv;

import android.content.Context;

import java.util.List;

/**
 * Recordings metadata, filed (v0.11.0): the store behind
 * memory/recordings.tsv. What a recording IS — its title, its
 * channel, when it aired, where it came from, how it ended — lives
 * in the file; the download bytes and media3's index stay machinery.
 *
 * ChannelStore's record-meta face (putRecordMeta / getRecordName /
 * removeRecordMeta) delegates here now; the legacy record_meta
 * preferences are seeded in once, on first use, by ChannelStore.
 */
public final class RecordingsMeta {

    private RecordingsMeta() {}

    private static List<MemoryFiles.RecordingMeta> cache;

    private static synchronized List<MemoryFiles.RecordingMeta> load(Context ctx) {
        if (cache == null) {
            cache = MemoryFiles.recordingsFromText(
                    MemoryHook.read(ctx, MemoryHook.RECORDINGS));
        }
        return cache;
    }

    private static synchronized void save(Context ctx) {
        MemoryHook.write(ctx, MemoryHook.RECORDINGS,
                MemoryFiles.recordingsToText(cache));
    }

    public static synchronized void invalidate() { cache = null; }

    public static synchronized boolean fileExists(Context ctx) {
        return MemoryHook.exists(ctx, MemoryHook.RECORDINGS);
    }

    /** One-time seed from the legacy store (ChannelStore supplies rows). */
    public static synchronized void seed(Context ctx,
                                         List<MemoryFiles.RecordingMeta> rows) {
        if (fileExists(ctx)) return;
        cache = rows;
        save(ctx);
    }

    public static synchronized void upsert(Context ctx,
                                           MemoryFiles.RecordingMeta row) {
        cache = MemoryFiles.upsertRecording(load(ctx), row);
        save(ctx);
    }

    public static synchronized MemoryFiles.RecordingMeta get(Context ctx, String id) {
        return MemoryFiles.findRecording(load(ctx), id);
    }

    public static synchronized String title(Context ctx, String id) {
        MemoryFiles.RecordingMeta r = get(ctx, id);
        return r == null ? "" : r.title;
    }

    public static synchronized void remove(Context ctx, String id) {
        cache = MemoryFiles.removeRecording(load(ctx), id);
        save(ctx);
    }

    public static synchronized List<MemoryFiles.RecordingMeta> all(Context ctx) {
        return load(ctx);
    }
}
