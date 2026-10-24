package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk home of the gathered catalogue index (v0.10.0): one JSON
 * bucket per language + the walk state, under files/catalog/. The
 * wall reads from here, so a language wall works fully offline from
 * whatever the walk has honestly gathered so far.
 */
public final class CatalogStore {

    private CatalogStore() {}

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "catalog");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File stateFile(Context ctx) {
        return new File(dir(ctx), "index_state.json");
    }

    public static File bucketFile(Context ctx, String languageName) {
        return new File(dir(ctx), CatalogIndex.bucketFileName(languageName));
    }

    public static CatalogIndex.State loadState(Context ctx) {
        return CatalogIndex.State.fromJson(readFile(stateFile(ctx)));
    }

    public static void saveState(Context ctx, CatalogIndex.State st) {
        try {
            st.updatedAtMs = System.currentTimeMillis();
            writeFile(stateFile(ctx), st.toJson().toString());
        } catch (Exception ignored) { }
    }

    /** Every gathered record on this phone, keyed by TVmaze id. */
    public static Map<Integer, CatalogIndex.Record> loadGathered(Context ctx) {
        Map<Integer, CatalogIndex.Record> out = new LinkedHashMap<>();
        File[] files = dir(ctx).listFiles(
                (d, name) -> name.startsWith("lang_") && name.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            for (CatalogIndex.Record r : CatalogIndex.bucketFromJson(readFile(f))) {
                if (r.id > 0) out.put(r.id, r);
            }
        }
        return out;
    }

    /** Rewrite one language's bucket file from the gathered set. */
    public static void saveBucket(Context ctx, Map<Integer, CatalogIndex.Record> gathered,
                                  String languageName) {
        try {
            List<CatalogIndex.Record> bucket =
                    CatalogIndex.bucketOf(new ArrayList<>(gathered.values()), languageName);
            CatalogIndex.sortShows(bucket);
            writeFile(bucketFile(ctx, languageName), CatalogIndex.bucketToJson(bucket));
        } catch (Exception ignored) { }
    }

    /**
     * The availability shelf for this phone right now: candidate keys
     * from every loaded playlist entry + every indexed library file.
     * Built fresh by callers on a background thread (the channel cache
     * parse is the expensive part, and only happens there).
     */
    public static Availability.Shelf shelf(Context ctx) {
        List<String> names = new ArrayList<>();
        List<M3uParser.Channel> pl = ChannelStore.loadCache(ctx);
        if (pl != null) {
            Map<String, ChannelScan.Rec> scan = ScanStore.load(ctx);
            for (M3uParser.Channel c : pl) {
                // v0.17.0 THE CHANNEL SCAN: an entry the scan
                // convicted terminally dead lends its name to no
                // tile — the promise is gone with the link (the
                // Lying Tile law, with a sweep behind it).
                // v0.18.0: an entry the scan filed LISTED (its
                // list answers, its picture unproven) lends its
                // name to no tile either — a tile is a playable
                // promise, and listed is not playable. The entry
                // itself still presents, as listed, in Live TV.
                int v = ChannelScan.verdictOf(scan, c.url);
                if (v == ChannelScan.V_DEAD
                        || v == ChannelScan.V_LISTED) continue;
                names.add(c.name);
            }
        }
        for (M3uParser.Channel c : LibraryStore.loadChannels(ctx)) {
            names.add(c.name);
        }
        return new Availability.Shelf(Availability.buildKeySet(names));
    }

    /** Availability verdict for one record: shelf evidence or a proven Archive find. */
    public static boolean isAvailable(Context ctx, Availability.Shelf shelf,
                                      CatalogIndex.Record r) {
        if (r == null) return false;
        if (shelf != null && shelf.carries(r.name)) return true;
        return ChannelStore.isArchiveProven(ctx, r.id);
    }

    public static String readFile(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public static void writeFile(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
}
