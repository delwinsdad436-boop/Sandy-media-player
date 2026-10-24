package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The receipts' home: a FILE on the memory hook
 * (files/memory/source_receipts.tsv), sibling to scan_links.tsv
 * and hydra_doors.tsv — file in, file out. Seeded at first load
 * from the shipped census record (assets/source_receipts_seed
 * .tsv) so a fresh install carries the proof with it; after
 * that, the memory-hook file is the standing record and only
 * a WORLD_CHANGED marking (a door the world broke later, found
 * by a re-check's terminal answer — {@link SourceReceipts
 * #noteDoorResult}) ever edits it. The bind proof is never
 * rewritten.
 */
public final class ReceiptStore {

    private ReceiptStore() {}

    public static final String ASSET_SEED = "source_receipts_seed.tsv";

    private static Map<String, SourceReceipts.Rec> cache;
    private static boolean dirty;

    public static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "memory");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "source_receipts.tsv");
    }

    public static synchronized Map<String, SourceReceipts.Rec> load(
            Context ctx) {
        if (cache != null) return cache;
        cache = new LinkedHashMap<>();
        try {
            File f = file(ctx);
            if (f.isFile()) {
                cache.putAll(SourceReceipts.parse(new String(
                        Files.readAllBytes(f.toPath()),
                        StandardCharsets.UTF_8)));
            }
            if (cache.isEmpty()) {
                // First run on this phone: take the census record
                // in from the shipped seed and keep it on the hook.
                String seed = null;
                try (InputStream in = ctx.getAssets().open(ASSET_SEED)) {
                    seed = new String(readAll(in), StandardCharsets.UTF_8);
                } catch (Exception ignored) {}
                if (seed == null || seed.trim().isEmpty()) {
                    seed = SourceReceipts.seedTsv();
                }
                cache.putAll(SourceReceipts.parse(seed));
                dirty = true;
                saveLocked(ctx);
            }
        } catch (Exception ignored) {}
        return cache;
    }

    /** One source's receipt, or null when Sandy never bound it. */
    public static synchronized SourceReceipts.Rec get(Context ctx,
                                                        String id) {
        return load(ctx).get(id);
    }

    /** The playable gate, asked the house way. */
    public static synchronized boolean backsPlayable(Context ctx,
                                                       String id) {
        return SourceReceipts.backsPlayable(get(ctx, id));
    }

    /**
     * A re-check spoke about a bound source's door. Only a
     * TERMINAL answer (gone / refused / no such address) marks
     * the receipt WORLD_CHANGED — the site's story, stamped with
     * today's date; slow and server weather change nothing.
     * Returns true when the record changed.
     */
    public static synchronized boolean noteDoorResult(Context ctx,
                                                      String id,
                                                      String kind,
                                                      String how) {
        SourceReceipts.Rec r = get(ctx, id);
        if (r == null) return false;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .format(new Date());
        SourceReceipts.Rec out = SourceReceipts.noteDoorResult(
                r, kind, today, how);
        if (out == r) return false;
        cache.put(id, out);
        dirty = true;
        saveLocked(ctx);
        return true;
    }

    public static synchronized void save(Context ctx) {
        if (!dirty && cache != null) return;
        saveLocked(ctx);
    }

    private static void saveLocked(Context ctx) {
        if (cache == null) return;
        try (FileWriter w = new FileWriter(file(ctx), false)) {
            w.write(SourceReceipts.toTsv(cache));
            dirty = false;
        } catch (Exception ignored) {}
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
