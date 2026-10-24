package com.talacore.talatv;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Keeping her place in a book (v0.8.0) — the read-aloud twin of the
 * kept place in a channel. Per-book bookmark in SharedPreferences
 * (survives cache clears; it is settings, not cache): which chunk
 * she reached, the char offset, whether the book is finished, and
 * whether she was hearing it translated. Plus the two global
 * reading comforts: speaking rate and chosen voice.
 */
public final class ReaderStore {

    private ReaderStore() {}

    private static final String PREFS = "talatv_reader";

    public static final String MODE_ORIGINAL = "orig";
    public static final String MODE_TRANSLATED = "tran";

    public static final class Bookmark {
        public int chunk = 0;
        public int charOffset = 0;
        public int totalChunks = 0;
        public String chapter = "";
        public boolean finished = false;
        public String mode = MODE_ORIGINAL;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // v0.11.0: a book's place is a kept place — it lives in the
    // filed kept places (memory hook, kind "book": position = chunk,
    // extra = the rest as JSON), beside every other kept place in
    // the house. First touch seeds the file from the legacy
    // per-book preference keys, once, behind the kind marker.
    private static void seedBooks(Context ctx) {
        if (KeptPlaces.seeded(ctx, "book")) return;
        java.util.List<MemoryFiles.KeptPlace> rows = new java.util.ArrayList<>();
        try {
            SharedPreferences p = prefs(ctx);
            for (String key : p.getAll().keySet()) {
                if (!key.endsWith(".chunk")) continue;
                String bookId = key.substring(0, key.length() - ".chunk".length());
                Bookmark b = new Bookmark();
                b.chunk = p.getInt(key, 0);
                b.charOffset = p.getInt(bookId + ".char", 0);
                b.totalChunks = p.getInt(bookId + ".total", 0);
                b.chapter = p.getString(bookId + ".chapter", "");
                b.finished = p.getBoolean(bookId + ".finished", false);
                b.mode = p.getString(bookId + ".mode", MODE_ORIGINAL);
                rows.add(new MemoryFiles.KeptPlace("book", bookId, b.chunk,
                        System.currentTimeMillis(), extraOf(b)));
            }
        } catch (Exception ignored) { }
        KeptPlaces.seed(ctx, "book", rows);
    }

    private static String extraOf(Bookmark b) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("char", b.charOffset);
            o.put("total", b.totalChunks);
            o.put("chapter", b.chapter == null ? "" : b.chapter);
            o.put("finished", b.finished);
            o.put("mode", b.mode == null ? MODE_ORIGINAL : b.mode);
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static Bookmark bookmark(Context ctx, String bookId) {
        seedBooks(ctx);
        Bookmark b = new Bookmark();
        b.chunk = (int) KeptPlaces.getPlacePosition(ctx, "book", bookId);
        String extra = KeptPlaces.getExtra(ctx, "book", bookId);
        if (extra != null) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(extra);
                b.charOffset = o.optInt("char", 0);
                b.totalChunks = o.optInt("total", 0);
                b.chapter = o.optString("chapter", "");
                b.finished = o.optBoolean("finished", false);
                b.mode = o.optString("mode", MODE_ORIGINAL);
            } catch (Exception ignored) { }
        }
        return b;
    }

    public static void save(Context ctx, String bookId, int chunk, int charOffset,
                            int totalChunks, String chapter, String mode) {
        seedBooks(ctx);
        Bookmark b = bookmark(ctx, bookId);
        b.chunk = chunk;
        b.charOffset = charOffset;
        b.totalChunks = totalChunks;
        b.chapter = chapter == null ? "" : chapter;
        b.mode = mode == null ? MODE_ORIGINAL : mode;
        KeptPlaces.putPlace(ctx, "book", bookId, chunk, extraOf(b));
    }

    public static void markFinished(Context ctx, String bookId, boolean finished) {
        seedBooks(ctx);
        Bookmark b = bookmark(ctx, bookId);
        b.finished = finished;
        KeptPlaces.putPlace(ctx, "book", bookId, b.chunk, extraOf(b));
    }

    /** A finished (or started-over) book begins again at chunk 0. */
    public static void resetPlace(Context ctx, String bookId) {
        seedBooks(ctx);
        Bookmark b = bookmark(ctx, bookId);
        b.chunk = 0;
        b.charOffset = 0;
        b.finished = false;
        KeptPlaces.putPlace(ctx, "book", bookId, 0, extraOf(b));
    }

    // ------------------------------------------------ global comforts

    public static float rate(Context ctx) {
        return prefs(ctx).getFloat("rate", 1.0f);
    }

    public static void setRate(Context ctx, float rate) {
        float r = Math.max(0.5f, Math.min(2.0f, rate));
        prefs(ctx).edit().putFloat("rate", r).apply();
    }

    /** The TTS voice's own name ("" = the phone's default voice). */
    public static String voiceName(Context ctx) {
        return prefs(ctx).getString("voice", "");
    }

    public static void setVoiceName(Context ctx, String name) {
        prefs(ctx).edit().putString("voice", name == null ? "" : name).apply();
    }
}
