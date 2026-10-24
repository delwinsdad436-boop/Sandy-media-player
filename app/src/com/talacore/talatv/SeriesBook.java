package com.talacore.talatv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The SERIES tier of the book VoiceBook (v0.16.0, Addendum 5 —
 * James: "Also to carry through the series as a whole").
 *
 * The dub's law, carried to books: a character changing voice
 * mid-series is a failed telling. So above each book's own
 * VoiceBook (StoryCast.Book, kept by StoryFiles) sits a SERIES
 * VoiceBook, resolved FIRST:
 *
 *   series → book → narrator default
 *
 * A character already cast in the series book speaks with that
 * voice in every volume; new characters found in a later
 * volume ENROLL into the series book and are inherited by the
 * volumes after. A deliberate recast is recorded at the SERIES
 * level (one decision, whole series) in the recasts ledger —
 * never a silent per-book drift.
 *
 * Series identity comes from the source metadata where it is
 * carried (the Archive's "series" field, read by BookSource),
 * or the reader groups books into a series BY HAND — both live
 * as files on the memory hook:
 *
 *   story/series/index.tsv       key TAB name TAB source
 *   story/series/membership.tsv  book key TAB series key
 *   story/series/<key>.json      the series VoiceBook (StoryCast JSON)
 *   story/series/<key>.recasts.tsv  name TAB from voice TAB to voice TAB stamp
 *
 * The merge/enroll/recast logic is pure; the IO is thin.
 */
public final class SeriesBook {

    public static final class Group {
        public String key = "";
        public String name = "";
        public String source = "";   // "metadata" or "hand"
    }

    public static final class Recast {
        public String name = "";
        public String fromVoice = "";
        public String toVoice = "";
        public long stampMs = 0;
    }

    private SeriesBook() {}

    /** Name matching key: trimmed, case-folded. */
    public static String norm(String name) {
        return name == null ? "" : name.trim().toLowerCase(
                java.util.Locale.US);
    }

    /** Stable key for a series name ("" for no series). */
    public static String keyFor(String seriesName) {
        if (seriesName == null) return "";
        String s = seriesName.trim().toLowerCase(java.util.Locale.US);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        String key = b.toString().replaceAll("_+", "_");
        while (key.startsWith("_")) key = key.substring(1);
        while (key.endsWith("_")) key = key.substring(0, key.length() - 1);
        if (key.length() > 60) {
            key = key.substring(0, 44) + "_"
                    + Integer.toHexString(key.hashCode());
        }
        return key;
    }

    // ------------------------------------------------ the merge

    /**
     * SERIES-FIRST resolution: the cast a telling actually
     * uses. Every series entry wins over the book's same-named
     * entry (voice, family, pitch, rate, card); book-only
     * entries ride along (they enroll into the series next).
     * The narrator is a cast member like any other: a series
     * narrator outranks a book narrator.
     */
    public static StoryCast.Book resolve(StoryCast.Book series,
                                         StoryCast.Book book) {
        StoryCast.Book out = StoryCast.newBook(
                book.bookId, book.title, book.lang);
        Map<String, StoryCast.Entry> byName = new LinkedHashMap<>();
        for (StoryCast.Entry e : book.cast) {
            byName.put(norm(e.name), e);
        }
        if (series != null) {
            for (StoryCast.Entry e : series.cast) {
                byName.put(norm(e.name), e);
            }
        }
        out.cast.addAll(byName.values());
        return out;
    }

    /**
     * Enrollment: everyone in the resolved cast who is not yet
     * in the series book joins it (first voice wins — a later
     * volume never overwrites an earlier casting by accident).
     * Returns the grown series book.
     */
    public static StoryCast.Book enrollInto(StoryCast.Book series,
                                            StoryCast.Book resolved) {
        StoryCast.Book out = StoryCast.newBook(
                series.bookId, series.title, series.lang);
        out.cast.addAll(series.cast);
        for (StoryCast.Entry e : resolved.cast) {
            if (out.byName(e.name) == null) out.cast.add(e);
        }
        return out;
    }

    /**
     * A deliberate recast, at the series level: replace one
     * member's voice in the series book and return the ledger
     * line that records the decision. The caller persists both.
     */
    public static Recast recast(StoryCast.Book series, String name,
                                String newVoice, long stampMs) {
        StoryCast.Entry e = series.byName(name);
        if (e == null) return null;
        Recast r = new Recast();
        r.name = e.name;
        r.fromVoice = e.ttsVoice == null ? "" : e.ttsVoice;
        r.toVoice = newVoice == null ? "" : newVoice;
        r.stampMs = stampMs;
        e.ttsVoice = r.toVoice;
        return r;
    }

    // ------------------------------------------------ ledgers (pure text)

    public static String indexToText(List<Group> groups) {
        StringBuilder b = new StringBuilder();
        for (Group g : groups) {
            b.append(g.key).append('\t').append(clean(g.name)).append('\t')
                    .append(clean(g.source)).append('\n');
        }
        return b.toString();
    }

    public static List<Group> indexFromText(String text) {
        List<Group> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 2 || f[0].trim().isEmpty()) continue;
            Group g = new Group();
            g.key = f[0].trim();
            g.name = f[1];
            g.source = f.length > 2 ? f[2] : "";
            out.add(g);
        }
        return out;
    }

    public static String membershipToText(Map<String, String> map) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            b.append(e.getKey()).append('\t').append(e.getValue())
                    .append('\n');
        }
        return b.toString();
    }

    public static Map<String, String> membershipFromText(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 2 || f[0].trim().isEmpty()) continue;
            out.put(f[0].trim(), f[1].trim());
        }
        return out;
    }

    public static String recastsToText(List<Recast> recasts) {
        StringBuilder b = new StringBuilder();
        for (Recast r : recasts) {
            b.append(clean(r.name)).append('\t').append(clean(r.fromVoice))
                    .append('\t').append(clean(r.toVoice)).append('\t')
                    .append(r.stampMs).append('\n');
        }
        return b.toString();
    }

    public static List<Recast> recastsFromText(String text) {
        List<Recast> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 3 || f[0].trim().isEmpty()) continue;
            Recast r = new Recast();
            r.name = f[0];
            r.fromVoice = f[1];
            r.toVoice = f[2];
            try {
                r.stampMs = f.length > 3 ? Long.parseLong(f[3].trim()) : 0;
            } catch (Exception ignored) {}
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------ files (thin IO)

    public static File dir(File root) {
        return new File(new File(root, "story"), "series");
    }

    public static File castFile(File root, String seriesKey) {
        return new File(dir(root), seriesKey + ".json");
    }

    public static StoryCast.Book loadCast(File root, String seriesKey,
                                          String seriesName, String lang) {
        File f = castFile(root, seriesKey);
        if (f.isFile()) {
            try {
                StoryCast.Book b = StoryCast.fromJson(read(f));
                if (b != null && b.narrator() != null) return b;
            } catch (Exception ignored) {}
        }
        return StoryCast.newBook("series:" + seriesKey, seriesName, lang);
    }

    public static void saveCast(File root, StoryCast.Book seriesBook) {
        String key = seriesBook.bookId.startsWith("series:")
                ? seriesBook.bookId.substring("series:".length())
                : keyFor(seriesBook.title);
        try {
            write(castFile(root, key), StoryCast.toJson(seriesBook));
        } catch (Exception ignored) {}
    }

    public static List<Group> loadIndex(File root) {
        return indexFromText(read(new File(dir(root), "index.tsv")));
    }

    public static void saveIndex(File root, List<Group> groups) {
        write(new File(dir(root), "index.tsv"), indexToText(groups));
    }

    public static Map<String, String> loadMembership(File root) {
        return membershipFromText(
                read(new File(dir(root), "membership.tsv")));
    }

    public static void saveMembership(File root, Map<String, String> map) {
        write(new File(dir(root), "membership.tsv"), membershipToText(map));
    }

    /**
     * The series key for a book: hand/metadata membership first
     * (the membership file), "" when the book stands alone.
     */
    public static String seriesKeyFor(File root, String bookKey) {
        if (bookKey == null || bookKey.isEmpty()) return "";
        String k = loadMembership(root).get(bookKey);
        return k == null ? "" : k;
    }

    /**
     * Group a book into a series (by metadata name or by hand —
     * the caller passes which). Writes membership + index and
     * returns the series key.
     */
    public static String group(File root, String bookKey, String seriesName,
                               String source) {
        String key = keyFor(seriesName);
        if (key.isEmpty() || bookKey == null || bookKey.isEmpty()) return "";
        Map<String, String> mem = loadMembership(root);
        mem.put(bookKey, key);
        saveMembership(root, mem);
        List<Group> idx = loadIndex(root);
        boolean found = false;
        for (Group g : idx) if (g.key.equals(key)) { found = true; break; }
        if (!found) {
            Group g = new Group();
            g.key = key;
            g.name = seriesName.trim();
            g.source = source == null ? "" : source;
            idx.add(g);
            saveIndex(root, idx);
        }
        return key;
    }

    public static void appendRecast(File root, String seriesKey,
                                    Recast r) {
        File f = new File(dir(root), seriesKey + ".recasts.tsv");
        List<Recast> all = recastsFromText(read(f));
        all.add(r);
        write(f, recastsToText(all));
    }

    public static List<Recast> loadRecasts(File root, String seriesKey) {
        return recastsFromText(
                read(new File(dir(root), seriesKey + ".recasts.tsv")));
    }

    // ------------------------------------------------ io

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static String read(File f) {
        try {
            if (!f.isFile()) return "";
            return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File f, String text) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }
}
