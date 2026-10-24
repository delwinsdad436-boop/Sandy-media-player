package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * The Channel Scan's persisted record, kept as a FILE on the
 * memory hook (files/memory/scan_links.tsv) — file in, file out,
 * sibling to hydra_doors.tsv. Corpses stay convicted across
 * opens, so a dead link is not re-listed on the next launch,
 * and the last run's summary (when, tested, live, removed)
 * rides the file's first line so the scan surface can print
 * verified counts without re-walking anything.
 */
public final class ScanStore {

    private ScanStore() {}

    private static Map<String, ChannelScan.Rec> cache;
    private static ChannelScan.Summary summaryCache;
    private static boolean dirty;

    public static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "memory");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "scan_links.tsv");
    }

    public static synchronized Map<String, ChannelScan.Rec> load(Context ctx) {
        if (cache != null) return cache;
        cache = new HashMap<>();
        summaryCache = new ChannelScan.Summary(0, 0, 0, 0);
        try {
            File f = file(ctx);
            if (f.isFile()) {
                ChannelScan.Parsed p = ChannelScan.parse(new String(
                        Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                cache.putAll(p.recs);
                summaryCache = p.summary;
            }
        } catch (Exception ignored) {}
        return cache;
    }

    public static synchronized ChannelScan.Summary summary(Context ctx) {
        load(ctx);
        return summaryCache;
    }

    /** The recorded verdict for one url (V_UNTESTED when unheard-of). */
    public static synchronized int verdictOf(Context ctx, String url) {
        return ChannelScan.verdictOf(load(ctx), url);
    }

    /** Records one verdict (called from scan worker threads). */
    public static synchronized void record(Context ctx, ChannelScan.Rec r) {
        if (r == null || r.url.isEmpty()) return;
        load(ctx).put(r.url, r);
        dirty = true;
    }

    /** Closes a run: stamps the summary and writes through. */
    public static synchronized void finishRun(Context ctx,
                                              ChannelScan.Summary s) {
        load(ctx);
        summaryCache = s;
        dirty = true;
        saveLocked(ctx);
    }

    /** Writes pending verdicts; safe to call any time. */
    public static synchronized void save(Context ctx) {
        if (!dirty && cache != null) return;
        saveLocked(ctx);
    }

    private static void saveLocked(Context ctx) {
        if (cache == null) return;
        try (FileWriter w = new FileWriter(file(ctx), false)) {
            w.write(ChannelScan.toTsv(cache, summaryCache));
            dirty = false;
        } catch (Exception ignored) {}
    }
}
