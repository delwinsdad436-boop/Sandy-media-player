package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import ai.onnxruntime.OrtEnvironment;

/**
 * Text-to-text translation (v0.12.0) — the matrix half of James's
 * subtitle-first pipeline: known text in one language, moved
 * directly to another, no ear involved. Same construction as
 * BookTranslator (SentencePiece + OpusMtOnnx over an installed
 * engine pack's mt/ tables), generalized to whichever pair the
 * matrix is asked for: a route exists exactly when its pack is
 * installed, and the dub gate (DubGate) decides from this class's
 * installed-pairs truth — never from hope.
 *
 * The MtLoopGuard rides every call, as it does for books: a line
 * the model loops on is dropped and COUNTED, and the count is
 * reported — never a silent skip.
 */
public final class TextMt implements AutoCloseable {

    public final String pair;
    private final OpusMtOnnx mt;
    private final MtLoopGuard guard = new MtLoopGuard();

    private TextMt(String pair, OpusMtOnnx mt) {
        this.pair = pair;
        this.mt = mt;
    }

    /** The pairs whose packs are installed and ONNX-runnable. */
    public static Set<String> installedPairs(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        try {
            File root = new File(ctx.getFilesDir(), "engine");
            File[] recs = root.listFiles((d, n) -> n.endsWith(".json"));
            if (recs != null) {
                for (File rec : recs) {
                    String pair = rec.getName().replace(".json", "");
                    EnginePackStore.Status st = EnginePackStore.status(ctx, pair);
                    if (st != null && st.onnx()) out.add(pair);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static boolean available(Context ctx, String pair) {
        try {
            EnginePackStore.Status st = EnginePackStore.status(ctx, pair);
            return st != null && st.onnx() && st.dir != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Open a pair's MT. Throws when the pack isn't usable. */
    public static TextMt open(Context ctx, String pair) throws Exception {
        EnginePackStore.Status st = EnginePackStore.status(ctx, pair);
        if (st == null || !st.onnx() || st.dir == null) {
            throw new Exception("the " + pair + " translation pack isn't installed");
        }
        File mtDir = new File(st.dir, "mt");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        SentencePiece sp = new SentencePiece(
                new File(mtDir, "sp_pieces.tsv"), new File(mtDir, "vocab.tsv"));
        return new TextMt(pair, new OpusMtOnnx(env, mtDir, sp));
    }

    /** Lines the loop guard has dropped so far. */
    public int droppedLoops() {
        return guard.dropped();
    }

    /**
     * Translate one line of text. Returns the translation, or ""
     * when the guard judged the decode a loop (the caller keeps the
     * line silent and the drop is counted for disclosure).
     */
    public String translate(String source) throws Exception {
        if (source == null || source.trim().isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String block : BookTranslator.blocks(source)) {
            String t = mt.translate(block);
            if (t != null && !t.trim().isEmpty()) {
                if (out.length() > 0) out.append(' ');
                out.append(t.trim());
            }
        }
        String whole = out.toString().trim();
        if (guard.feed(source, whole) == MtLoopGuard.Verdict.DROP_LOOP) {
            return "";
        }
        return whole;
    }

    @Override
    public void close() {
        try { mt.close(); } catch (Exception ignored) {}
    }
}
