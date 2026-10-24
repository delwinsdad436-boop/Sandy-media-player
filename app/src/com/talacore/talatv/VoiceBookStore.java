package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Voice-book persistence (v0.6.0; v0.12.0: moved under the memory
 * hook): one JSON file per show under files/memory/voicebook/, so
 * a show's cast survives episodes, seasons and app updates — the
 * cast catalog lives where the dub work happens, as files, behind
 * the one hook (James's file rule; the 0.11.0 convergence).
 *
 * Books written before 0.12.0 live in filesDir/voicebooks/; they
 * migrate forward the first time the store is touched (copied,
 * never moved — the old file stays until it is read from its new
 * home, and every later read/write uses the hook).
 */
public final class VoiceBookStore {

    private VoiceBookStore() {}

    private static File dir(Context ctx) {
        File d = new File(MemoryHook.root(ctx), "voicebook");
        if (!d.isDirectory()) d.mkdirs();
        migrate(ctx, d);
        return d;
    }

    private static File legacyDir(Context ctx) {
        return new File(ctx.getFilesDir(), "voicebooks");
    }

    /** One-time copy of any pre-hook books into the hook root. */
    private static void migrate(Context ctx, File dir) {
        File legacy = legacyDir(ctx);
        if (!legacy.isDirectory()) return;
        File[] files = legacy.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            File dest = new File(dir, f.getName());
            if (dest.isFile()) continue;
            try {
                java.nio.file.Files.copy(f.toPath(), dest.toPath());
            } catch (Exception ignored) {}
        }
    }

    private static File fileFor(Context ctx, String showKey) {
        String safe = showKey == null ? "show" : showKey.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) safe = "show";
        return new File(dir(ctx), safe + ".json");
    }

    public static VoiceBook.Book load(Context ctx, String showKey) {
        File f = fileFor(ctx, showKey);
        if (!f.isFile()) {
            VoiceBook.Book b = new VoiceBook.Book();
            b.showKey = showKey == null ? "" : showKey;
            return b;
        }
        try {
            return VoiceBook.fromJson(new String(EnginePackStore.readAll(f), "UTF-8"));
        } catch (Exception e) {
            VoiceBook.Book b = new VoiceBook.Book();
            b.showKey = showKey == null ? "" : showKey;
            return b;
        }
    }

    public static void save(Context ctx, VoiceBook.Book book) {
        if (book == null) return;
        try {
            File f = fileFor(ctx, book.showKey);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(VoiceBook.toJson(book).getBytes("UTF-8"));
            fos.close();
        } catch (Exception ignored) {}
    }

    /** Every book on the phone (headers + cast), for the casting UI. */
    public static List<VoiceBook.Book> listAll(Context ctx) {
        List<VoiceBook.Book> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            try {
                out.add(VoiceBook.fromJson(new String(
                        EnginePackStore.readAll(f), "UTF-8")));
            } catch (Exception ignored) {}
        }
        return out;
    }
}
