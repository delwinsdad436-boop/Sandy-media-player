package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * THE MEMORY HOOK (v0.11.0) — the one section of hook that goes to
 * memory, per James's architecture rule: file in, file out.
 *
 * Every file Sandy keeps for a person lives under ONE root —
 * filesDir/memory/ — and every feature reaches its files through
 * this class and no other door:
 *
 *   memory/guide_map.tsv     tvg-id → hosted guide file bridge
 *   memory/guide/<src>.tsv   parsed programme cache, per source
 *   memory/guide_state.tsv   per-source freshness (fetched-at, etag)
 *   memory/timers.tsv        the VCR timer queue (TimerModel)
 *   memory/recordings.tsv    recordings metadata (MemoryFiles)
 *   memory/kept_places.tsv   every kept place (MemoryFiles)
 *
 * Writes are atomic (temp file + rename) so a dead battery mid-save
 * never halves a file. Reads of a missing file return "" — a fresh
 * install is an empty memory, not an error.
 *
 * What is NOT here, deliberately: media3's download index and
 * simple cache (machinery — the machine's own bookkeeping, not the
 * family's records), the catalogue buckets (CatalogStore — files
 * already, under files/catalog/, one store with one job), and the
 * SharedPreferences settings cluster (kept settings like playlists,
 * favorites, the UI language — convergence of those is recorded as
 * a known deviation in the 0.11.0 NOTES, not sprawled silently).
 */
public final class MemoryHook {

    private MemoryHook() {}

    public static final String TIMERS = "timers.tsv";
    public static final String RECORDINGS = "recordings.tsv";
    public static final String KEPT_PLACES = "kept_places.tsv";
    public static final String GUIDE_MAP = "guide_map.tsv";
    public static final String GUIDE_STATE = "guide_state.tsv";

    public static File root(Context ctx) {
        File d = new File(ctx.getFilesDir(), "memory");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context ctx, String name) {
        return new File(root(ctx), name);
    }

    public static File guideDir(Context ctx) {
        File d = new File(root(ctx), "guide");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File guideFile(Context ctx, String sourceKey) {
        // sourceKey is ours (mjh/iptvx + scope), never raw input.
        String safe = sourceKey.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(guideDir(ctx), safe + ".tsv");
    }

    public static boolean exists(Context ctx, String name) {
        return file(ctx, name).isFile();
    }

    public static String read(Context ctx, String name) {
        return readFile(file(ctx, name));
    }

    public static String readFile(File f) {
        try {
            if (!f.isFile()) return "";
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static void write(Context ctx, String name, String content) {
        writeFile(file(ctx, name), content);
    }

    /** Atomic write: temp beside the target, then rename over it. */
    public static void writeFile(File target, String content) {
        try {
            File tmp = new File(target.getParentFile(),
                    target.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(target)) {
                // rename can fail across states on some filesystems;
                // fall back to a plain copy over the target.
                Files.copy(tmp.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
        } catch (Exception ignored) { }
    }
}
