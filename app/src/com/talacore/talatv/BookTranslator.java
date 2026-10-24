package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ai.onnxruntime.OrtEnvironment;

/**
 * Translated reading's engine half (v0.8.0): the ja->en MT from the
 * installed ONNX engine pack, built MT-ONLY (no whisper is loaded —
 * a book needs no ears), exactly the construction OnnxCookerEngine
 * uses for its MT half: SentencePiece over the pack's sp tables and
 * OpusMtOnnx over the pack's mt/ directory.
 *
 * Translation is chunk-wise with a block cap (~480 chars, split on
 * sentence ends) so a long chunk never becomes one giant decode.
 * The MtLoopGuard rides every chunk: loops are dropped and counted,
 * and the count reaches the reader's ears — never a silent skip.
 *
 * Availability is the pack's own truth: EnginePackStore's status.
 * No pack, no offer — the reader says why in one line instead of
 * showing a door that opens onto nothing.
 */
public final class BookTranslator implements AutoCloseable {

    /** Source-block cap for one MT decode. */
    public static final int BLOCK = 480;

    private final OpusMtOnnx mt;
    private final MtLoopGuard guard = new MtLoopGuard();

    private BookTranslator(OpusMtOnnx mt) {
        this.mt = mt;
    }

    /** Is the ja-en engine pack installed and ONNX-shaped? */
    public static boolean available(Context ctx) {
        try {
            EnginePackStore.Status st = EnginePackStore.status(ctx, "ja-en");
            return st != null && st.onnx() && st.dir != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Open the MT half of the installed pack. Throws when unusable. */
    public static BookTranslator open(Context ctx) throws Exception {
        EnginePackStore.Status st = EnginePackStore.status(ctx, "ja-en");
        if (st == null || !st.onnx() || st.dir == null) {
            throw new Exception("the ja-en engine pack isn't installed");
        }
        File mtDir = new File(st.dir, "mt");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        SentencePiece sp = new SentencePiece(
                new File(mtDir, "sp_pieces.tsv"), new File(mtDir, "vocab.tsv"));
        return new BookTranslator(new OpusMtOnnx(env, mtDir, sp));
    }

    /** A new book resets the loop guard. */
    public void beginBook() {
        guard.reset();
    }

    /** Chunks dropped as loops so far in this book. */
    public int droppedLoops() {
        return guard.dropped();
    }

    /**
     * Translate one speakable chunk. Returns the English to speak,
     * or "" when the guard judged it a loop (the caller advances
     * past it; the drop count is spoken in the state line).
     */
    public String translateChunk(String sourceChunk) throws Exception {
        if (sourceChunk == null || sourceChunk.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String block : blocks(sourceChunk)) {
            String t = mt.translate(block);
            if (t != null && !t.trim().isEmpty()) {
                if (out.length() > 0) out.append(' ');
                out.append(t.trim());
            }
        }
        String whole = out.toString().trim();
        if (guard.feed(sourceChunk, whole) == MtLoopGuard.Verdict.DROP_LOOP) {
            return "";
        }
        return whole;
    }

    /** Split a chunk into <= BLOCK-char pieces on sentence ends. */
    public static List<String> blocks(String chunk) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int i = 0, n = chunk.length();
        while (i < n) {
            int end = sentenceEnd(chunk, i);
            String sent = chunk.substring(i, end).trim();
            if (!sent.isEmpty()) {
                if (cur.length() > 0 && cur.length() + sent.length() + 1 > BLOCK) {
                    out.add(cur.toString());
                    cur = new StringBuilder();
                }
                if (sent.length() > BLOCK) {
                    // one very long sentence: hard pieces at word edges
                    int s = 0;
                    while (s < sent.length()) {
                        int e = Math.min(s + BLOCK, sent.length());
                        if (e < sent.length()) {
                            int sp = sent.lastIndexOf(' ', e);
                            if (sp > s) e = sp;
                        }
                        if (cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(); }
                        out.add(sent.substring(s, e).trim());
                        s = e;
                    }
                } else {
                    if (cur.length() > 0) cur.append(' ');
                    cur.append(sent);
                }
            }
            i = end;
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static int sentenceEnd(String s, int from) {
        int n = s.length();
        for (int k = from; k < n; k++) {
            char c = s.charAt(k);
            if (c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？') {
                int e = k + 1;
                while (e < n && "\"'”’）)]」".indexOf(s.charAt(e)) >= 0) e++;
                boolean cjkTerm = c == '。' || c == '！' || c == '？';
                if (cjkTerm || e >= n || Character.isWhitespace(s.charAt(e))) return e;
            }
        }
        return n;
    }

    @Override
    public void close() {
        try { mt.close(); } catch (Exception ignored) {}
    }
}
