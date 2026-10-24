package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * Book chunking (v0.8.0) — pure Java. A book becomes a row of
 * speakable chunks: paragraph-first, then sentence-aware, with a
 * hard ceiling so no single utterance runs away. The bookmark the
 * reader keeps is (chunk index + char offset), the same shape as
 * the kept place in a channel: a position in the work, saved as
 * she reads, offered back in words when she returns.
 *
 * Targets: ~700 chars per chunk, never over 900 (CJK text without
 * spaces hard-splits at the ceiling). Sentence ends include the
 * Japanese/Chinese terminators (。！？) alongside . ! ? — the
 * books this reader translates are Japanese first.
 */
public final class BookChunker {

    private BookChunker() {}

    public static final int TARGET = 700;
    public static final int MAX = 900;

    public static final class Chunk {
        public final int index;
        /** Char offset into the book text where this chunk starts. */
        public final int startChar;
        /** Exclusive end char offset. */
        public final int endChar;
        public final String text;
        /** Title of the chapter this chunk belongs to ("" if none). */
        public final String chapterTitle;

        public Chunk(int index, int startChar, int endChar, String text,
                     String chapterTitle) {
            this.index = index;
            this.startChar = startChar;
            this.endChar = endChar;
            this.text = text;
            this.chapterTitle = chapterTitle;
        }
    }

    public static List<Chunk> chunk(String text, List<BookText.Chapter> chapters) {
        List<Chunk> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        // Split into paragraphs, remembering offsets.
        List<int[]> paras = new ArrayList<>(); // [start, end)
        int i = 0, len = text.length();
        while (i < len) {
            int j = text.indexOf('\n', i);
            if (j < 0) j = len;
            // a paragraph runs to a blank line or EOF
            int end = j;
            while (end < len) {
                int nj = text.indexOf('\n', end + 1);
                if (nj < 0) { end = len; break; }
                String between = text.substring(end + 1, nj).trim();
                if (between.isEmpty()) { break; }
                end = nj;
            }
            paras.add(new int[]{i, Math.min(end, len)});
            i = end + 1;
            while (i < len && (text.charAt(i) == '\n')) i++;
        }
        StringBuilder cur = new StringBuilder();
        int curStart = -1;
        for (int[] p : paras) {
            String para = text.substring(p[0], p[1]);
            // Split an over-long paragraph into sentence units.
            List<String> units = new ArrayList<>();
            List<Integer> unitOff = new ArrayList<>();
            splitUnits(para, p[0], units, unitOff);
            for (int u = 0; u < units.size(); u++) {
                String unit = units.get(u);
                int unitStart = unitOff.get(u);
                if (unit.trim().isEmpty()) continue;
                if (curStart < 0) curStart = unitStart;
                if (cur.length() > 0 && cur.length() + unit.length() + 1 > TARGET) {
                    emit(out, cur, curStart, text, chapters);
                    cur = new StringBuilder();
                    curStart = unitStart;
                }
                if (cur.length() > 0) cur.append(' ');
                cur.append(unit.trim());
            }
        }
        if (cur.length() > 0) emit(out, cur, curStart, text, chapters);
        return out;
    }

    private static void emit(List<Chunk> out, StringBuilder cur, int curStart,
                             String fullText, List<BookText.Chapter> chapters) {
        String t = cur.toString().trim();
        if (t.isEmpty() || curStart < 0) return;
        int end = Math.min(curStart + t.length(), fullText.length());
        // endChar tracks the chunk's own extent in the source; the
        // joins above collapse whitespace, so extent is approximate
        // by the join width — the bookmark stores startChar of the
        // NEXT chunk for resume, which is exact.
        String title = "";
        if (chapters != null) {
            for (BookText.Chapter c : chapters) {
                if (c.charOffset <= curStart) title = c.title;
                else break;
            }
        }
        out.add(new Chunk(out.size(), curStart, end, t, title));
    }

    /** Sentence-split one paragraph; over-long sentences hard-split. */
    private static void splitUnits(String para, int paraOff,
                                   List<String> units, List<Integer> offs) {
        int start = 0, n = para.length();
        for (int k = 0; k < n; k++) {
            char c = para.charAt(k);
            boolean term = c == '.' || c == '!' || c == '?'
                    || c == '。' || c == '！' || c == '？';
            if (!term) continue;
            // absorb closing quotes/brackets after the terminator
            int e = k + 1;
            while (e < n && "\"'”’）)]」".indexOf(para.charAt(e)) >= 0) e++;
            // Latin terminators need whitespace after (else "3.5"
            // and "e.g." would split); CJK terminators end the
            // sentence outright — Japanese runs sentences together
            // with no space at all, and missing that split once
            // glued whole paragraphs into single units.
            boolean cjkTerm = c == '。' || c == '！' || c == '？';
            if (!cjkTerm && e < n && !Character.isWhitespace(para.charAt(e))) continue;
            addUnit(para, paraOff, start, e, units, offs);
            start = e;
        }
        if (start < n) addUnit(para, paraOff, start, n, units, offs);
    }

    private static void addUnit(String para, int paraOff, int s, int e,
                                List<String> units, List<Integer> offs) {
        while (s < e) {
            int remain = e - s;
            if (remain <= MAX) {
                units.add(para.substring(s, e));
                offs.add(paraOff + s);
                return;
            }
            // hard split at the last word boundary inside the ceiling,
            // else exactly at the ceiling (spaceless scripts).
            int cut = -1;
            for (int k = Math.min(s + MAX, e - 1); k > s; k--) {
                if (Character.isWhitespace(para.charAt(k))) { cut = k; break; }
            }
            if (cut < 0) cut = s + MAX;
            units.add(para.substring(s, cut));
            offs.add(paraOff + s);
            s = cut;
            while (s < e && Character.isWhitespace(para.charAt(s))) s++;
        }
    }

    // ---------------------------------------------------- bookmark math

    /** The chunk a char offset belongs to (resume lands here). */
    public static int chunkIndexAt(List<Chunk> chunks, int charOffset) {
        if (chunks == null || chunks.isEmpty()) return 0;
        int idx = 0;
        for (Chunk c : chunks) {
            if (c.startChar <= charOffset) idx = c.index;
            else break;
        }
        return idx;
    }

    /** Where a chunk sits in the whole book, 0..1. */
    public static double progress(List<Chunk> chunks, int chunkIndex, int totalChars) {
        if (chunks == null || chunks.isEmpty() || totalChars <= 0) return 0;
        int idx = Math.max(0, Math.min(chunkIndex, chunks.size() - 1));
        return Math.min(1.0, (double) chunks.get(idx).startChar / totalChars);
    }

    /** The chapter containing a chunk index ("" when the book has none). */
    public static String chapterAt(List<Chunk> chunks, int chunkIndex) {
        if (chunks == null || chunks.isEmpty()) return "";
        int idx = Math.max(0, Math.min(chunkIndex, chunks.size() - 1));
        return chunks.get(idx).chapterTitle;
    }
}
