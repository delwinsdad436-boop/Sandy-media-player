package com.talacore.talatv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Story Mode's shelf in the house (v0.14.0) — every file the
 * story room keeps lives under the ONE memory hook, reached
 * through MemoryHook and nowhere else:
 *
 *   memory/story/curation.tsv       the curated shelf (seeded
 *                                     once from the app's assets;
 *                                     the file here is the list)
 *   memory/story/books/<id>.txt     a tale's text, once fetched
 *   memory/story/books/<id>.meta    its title/author/lang/group
 *   memory/story/casts/<id>.json    the book's voice book
 *   memory/story/voices/<id>.tsv    family-voice index
 *   memory/story/voices/<id>/…      the family's recordings
 *   memory/story/cartridges/<cid>/  a child's storyboard:
 *                                     manifest.json + its audio
 *   memory/story/journals.tsv       the voice journals index
 *   memory/story/journals/<jid>.m4a the journals themselves
 *
 * Settings-shaped things (the bedtime toggles) live in a small
 * preferences file, as settings do everywhere in the house; the
 * family's records are all files.
 */
public final class StoryFiles {

    private StoryFiles() {}

    private static final String PREFS = "talatv_story";

    public static File storyRoot(Context ctx) {
        File d = new File(MemoryHook.root(ctx), "story");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File sub(Context ctx, String name) {
        File d = new File(storyRoot(ctx), name);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File booksDir(Context ctx) { return sub(ctx, "books"); }
    public static File castsDir(Context ctx) { return sub(ctx, "casts"); }
    public static File voicesDir(Context ctx) { return sub(ctx, "voices"); }
    public static File cartridgesDir(Context ctx) { return sub(ctx, "cartridges"); }
    public static File journalsDir(Context ctx) { return sub(ctx, "journals"); }

    private static String safe(String id) {
        return id == null ? "book"
                : id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ------------------------------------------------------ curation

    /** The curated shelf — seeded from assets on first run. */
    public static List<StoryData.Row> curation(Context ctx) {
        File f = new File(storyRoot(ctx), "curation.tsv");
        if (!f.isFile()) {
            String seed = "";
            try (InputStream in = ctx.getAssets().open("story_curation.tsv")) {
                byte[] buf = new byte[8192];
                StringBuilder b = new StringBuilder();
                int n;
                java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
                seed = all.toString("UTF-8");
                b.append(seed);
            } catch (Exception ignored) {}
            if (!seed.isEmpty()) MemoryHook.writeFile(f, seed);
        }
        return StoryData.parseCuration(MemoryHook.readFile(f));
    }

    // --------------------------------------- fountain + made (v0.16.0)

    /**
     * The Story Fountain's resolved rows (v0.16.0): children's
     * books verified live through the BookSource gate (named
     * children's collection + open + resolvable), cached here
     * so the shelf stands offline for books already held and
     * the fountain deepens instead of restarting.
     */
    public static List<StoryData.Row> fountain(Context ctx) {
        return StoryData.parseCuration(MemoryHook.readFile(
                new File(storyRoot(ctx), "fountain.tsv")));
    }

    public static void saveFountain(Context ctx, List<StoryData.Row> rows) {
        StringBuilder b = new StringBuilder();
        for (StoryData.Row r : rows) b.append(StoryData.rowToLine(r)).append("\n");
        MemoryHook.writeFile(new File(storyRoot(ctx), "fountain.tsv"),
                b.toString());
    }

    /** Books a reader story-fied with Make a Story (v0.16.0). */
    public static List<StoryData.Row> made(Context ctx) {
        return StoryData.parseCuration(MemoryHook.readFile(
                new File(storyRoot(ctx), "made.tsv")));
    }

    public static void addMade(Context ctx, StoryData.Row row) {
        List<StoryData.Row> rows = made(ctx);
        rows.removeIf(r -> r.id.equals(row.id));
        rows.add(row);
        StringBuilder b = new StringBuilder();
        for (StoryData.Row r : rows) b.append(StoryData.rowToLine(r)).append("\n");
        MemoryHook.writeFile(new File(storyRoot(ctx), "made.tsv"),
                b.toString());
    }

    /**
     * Every row the story machinery can tell: the curated shelf,
     * the fountain's verified books, and made stories — first
     * occurrence of an id wins (curation outranks).
     */
    public static List<StoryData.Row> allRows(Context ctx) {
        List<StoryData.Row> out = new ArrayList<>();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (List<StoryData.Row> part : java.util.Arrays.asList(
                curation(ctx), fountain(ctx), made(ctx))) {
            for (StoryData.Row r : part) {
                if (seen.add(r.id)) out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------ book texts

    public static File bookTextFile(Context ctx, String bookId) {
        return new File(booksDir(ctx), safe(bookId) + ".txt");
    }

    public static File bookMetaFile(Context ctx, String bookId) {
        return new File(booksDir(ctx), safe(bookId) + ".meta");
    }

    public static boolean bookHeld(Context ctx, String bookId) {
        File f = bookTextFile(ctx, bookId);
        return f.isFile() && f.length() > 0;
    }

    public static String bookText(Context ctx, String bookId) {
        return MemoryHook.readFile(bookTextFile(ctx, bookId));
    }

    public static void saveBook(Context ctx, StoryData.Row row, String text) {
        MemoryHook.writeFile(bookTextFile(ctx, row.id), text);
        String meta = row.id + "\t" + row.title + "\t" + row.author + "\t"
                + row.lang + "\t" + row.taleGroup + "\t" + row.iaId + "\n";
        MemoryHook.writeFile(bookMetaFile(ctx, row.id), meta);
    }

    // ------------------------------------------------------ casts

    public static File castFile(Context ctx, String bookId) {
        return new File(castsDir(ctx), safe(bookId) + ".json");
    }

    public static StoryCast.Book loadCast(Context ctx, StoryData.Row row) {
        File f = castFile(ctx, row.id);
        if (f.isFile()) {
            try {
                StoryCast.Book b = StoryCast.fromJson(MemoryHook.readFile(f));
                if (b.bookId.equals(row.id) && b.narrator() != null) return b;
            } catch (Exception ignored) {}
        }
        StoryCast.Book b = StoryCast.newBook(row.id, row.title, row.lang);
        for (String nm : row.cast) {
            if (!nm.equalsIgnoreCase(StoryCast.NARRATOR)) {
                StoryCast.enroll(b, nm);
            }
        }
        assignCards(b);
        saveCast(ctx, b);
        return b;
    }

    /** Pin every cast member's picture card (once, in the file). */
    public static void assignCards(StoryCast.Book b) {
        for (StoryCast.Entry e : b.cast) {
            if (e.cardId == null || e.cardId.isEmpty()) {
                e.cardId = e.narrator ? StoryPanels.NARRATOR_CARD
                        : StoryPanels.cardFor(b.bookId, e.name);
            }
        }
    }

    public static void saveCast(Context ctx, StoryCast.Book b) {
        try {
            MemoryHook.writeFile(castFile(ctx, b.bookId), StoryCast.toJson(b));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------ family voices

    public static File voiceIndexFile(Context ctx, String bookId) {
        return new File(voicesDir(ctx), safe(bookId) + ".tsv");
    }

    public static File bookVoicesDir(Context ctx, String bookId) {
        File d = new File(voicesDir(ctx), safe(bookId));
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** chunkIndex -> [castName, audioFileName] for one book. */
    public static Map<Integer, String[]> voiceIndex(Context ctx, String bookId) {
        return StoryData.parseVoiceIndex(
                MemoryHook.readFile(voiceIndexFile(ctx, bookId)));
    }

    public static void saveVoiceIndex(Context ctx, String bookId,
                                      Map<Integer, String[]> idx) {
        MemoryHook.writeFile(voiceIndexFile(ctx, bookId),
                StoryData.voiceIndexToTsv(idx));
    }

    // ------------------------------------------------------ cartridges

    public static File cartridgeDir(Context ctx, String cartridgeId) {
        File d = new File(cartridgesDir(ctx), safe(cartridgeId));
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static void saveCartridge(Context ctx, StoryData.Cartridge c) {
        try {
            MemoryHook.writeFile(
                    new File(cartridgeDir(ctx, c.id), "manifest.json"),
                    StoryData.cartridgeToJson(c));
        } catch (Exception ignored) {}
    }

    public static List<StoryData.Cartridge> cartridges(Context ctx) {
        List<StoryData.Cartridge> out = new ArrayList<>();
        File[] dirs = cartridgesDir(ctx).listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                File m = new File(d, "manifest.json");
                if (!m.isFile()) continue;
                try {
                    out.add(StoryData.cartridgeFromJson(MemoryHook.readFile(m)));
                } catch (Exception ignored) {}
            }
        }
        out.sort((a, b) -> Long.compare(b.createdMs, a.createdMs));
        return out;
    }

    // ------------------------------------------------------ journals

    public static File journalsIndexFile(Context ctx) {
        return new File(storyRoot(ctx), "journals.tsv");
    }

    public static List<StoryData.Journal> journals(Context ctx) {
        return StoryData.parseJournals(
                MemoryHook.readFile(journalsIndexFile(ctx)));
    }

    public static void saveJournals(Context ctx, List<StoryData.Journal> js) {
        MemoryHook.writeFile(journalsIndexFile(ctx), StoryData.journalsToTsv(js));
    }

    // ------------------------------------------------------ settings

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Bedtime shape: the wind-down is armed for tellings. */
    public static boolean bedtime(Context ctx) {
        return prefs(ctx).getBoolean("bedtime", false);
    }

    public static void setBedtime(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("bedtime", v).apply();
    }

    /** Bilingual hearing stands per book (key: the book's id). */
    public static boolean bilingual(Context ctx, String bookId) {
        return prefs(ctx).getBoolean("bilingual_" + safe(bookId), false);
    }

    public static void setBilingual(Context ctx, String bookId, boolean v) {
        prefs(ctx).edit().putBoolean("bilingual_" + safe(bookId), v).apply();
    }

    /** The kept place inside a story book: a chunk index. */
    public static int storyPlace(Context ctx, String bookId) {
        return prefs(ctx).getInt("place_" + safe(bookId), 0);
    }

    public static void setStoryPlace(Context ctx, String bookId, int chunk) {
        prefs(ctx).edit().putInt("place_" + safe(bookId), chunk).apply();
    }
}
