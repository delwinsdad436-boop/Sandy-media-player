package com.talacore.talatv;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import ai.onnxruntime.OrtEnvironment;

/**
 * The bound cooker engine (v0.6.1): whisper-base ASR + opus-mt MT on
 * ONNX Runtime, behind the Cooker.Engine seam. Construction IS the
 * binding — if the sessions open, isBound() is true; the Translate
 * gate constructs it lazily and a construction failure becomes the
 * spoken ENGINE_FAILED state with the reason, never a silent row.
 *
 * The cook loop is the bench recipe end to end:
 *   240 s chunks (load-bearing: whole-file transcription
 *   degenerates); inside a chunk, fixed 30 s windows; VAD OFF (the
 *   bench's VAD finding: the filter ate real speech in 9 of 19
 *   chunks); hallucination guard over every chunk's segments;
 *   unit merge; opus-mt per unit; CookedSubs lines carrying BOTH
 *   the translation and the original (UTX Law 1 — the source is
 *   never silently replaced).
 *
 * Runs synchronously on the caller's thread (the player drives it
 * from a worker); per chunk it reports (chunkSeconds, wallSeconds,
 * lines so far) so the Cooker FSM's lead math gets real events.
 * Proven end-to-end on the desktop JVM against the bench references
 * (work/v061/BINDING-VERDICT.md); on-device execution is NOT
 * run-tested — no device exists in the build environment.
 */
public final class OnnxCookerEngine implements Cooker.Engine, AutoCloseable {

    public static final double CHUNK_SEC = 240.0;
    public static final double WINDOW_SEC = 30.0;
    /** whisper language token for this pack's pair (ja-en): <|ja|>. */
    public static final int LANG_TOKEN_JA = 50266;

    public interface Listener {
        /** A chunk landed: cooked seconds in it, wall seconds it
         *  took, and the subtitle lines produced so far. */
        void onChunk(int chunkIndex, double chunkSec, double wallSec,
                     List<CookedSubs.Line> linesSoFar);
    }

    private final WhisperOnnx asr;
    private final OpusMtOnnx mt;
    private final MelSpectrogram mel;
    private volatile boolean cancelled = false;

    private OnnxCookerEngine(WhisperOnnx asr, OpusMtOnnx mt, MelSpectrogram mel) {
        this.asr = asr;
        this.mt = mt;
        this.mel = mel;
    }

    /**
     * Open the engine from an installed pack directory (the layout
     * EnginePackStore reports): asr/ and mt/ subdirectories holding
     * the ONNX graphs and the token tables. Throws on any missing or
     * unusable piece — fail-closed, like the pack validator.
     */
    public static OnnxCookerEngine open(File packDir) throws Exception {
        File asrDir = new File(packDir, "asr");
        File mtDir = new File(packDir, "mt");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        WhisperVocab vocab = new WhisperVocab(new File(asrDir, "vocab.tsv"));
        MelSpectrogram mel = new MelSpectrogram(new File(asrDir, "mel_filters.bin"));
        WhisperOnnx asr = new WhisperOnnx(env, asrDir, LANG_TOKEN_JA, vocab);
        SentencePiece sp = new SentencePiece(
                new File(mtDir, "sp_pieces.tsv"), new File(mtDir, "vocab.tsv"));
        OpusMtOnnx mt = new OpusMtOnnx(env, mtDir, sp);
        return new OnnxCookerEngine(asr, mt, mel);
    }

    @Override
    public boolean isBound() {
        return asr != null && mt != null;
    }

    public void cancel() { cancelled = true; }

    /**
     * Cook a whole title from a PCM source. Returns the subtitle
     * lines (translation + original on every line). Listener events
     * fire per chunk; wall time is measured around each chunk.
     */
    public List<CookedSubs.Line> cook(PcmSource src, Listener listener)
            throws Exception {
        List<CookedSubs.Line> lines = new ArrayList<>();
        double total = src.durationSec();
        int nChunks = (int) Math.ceil(total / CHUNK_SEC);
        for (int ci = 0; ci < nChunks; ci++) {
            if (cancelled) break;
            long w0 = System.nanoTime();
            double chunkStart = ci * CHUNK_SEC;
            double chunkLen = Math.min(CHUNK_SEC, total - chunkStart);
            double chunkEnd = chunkStart + chunkLen;
            List<HallucinationGuard.Segment> segs = new ArrayList<>();
            // Whisper's seek: each 30 s window starts at the end of
            // the last decoded segment, not on a fixed grid, so
            // window edges land on content boundaries; a window
            // that yields nothing advances the full 30 s.
            double seek = chunkStart;
            while (seek < chunkEnd - 0.05) {
                if (cancelled) break;
                float[] pcm = src.read(seek, WINDOW_SEC);
                float[][] feats = mel.features(pcm);
                List<HallucinationGuard.Segment> ws =
                        asr.transcribeWindow(feats, seek);
                double lastEnd = -1;
                for (HallucinationGuard.Segment s : ws) {
                    if (s.start < chunkEnd) {
                        segs.add(s);
                        if (s.end > lastEnd) lastEnd = s.end;
                    }
                }
                if (lastEnd > seek + 0.5) seek = lastEnd;
                else seek += WINDOW_SEC;
            }
            // guard, then merge the survivors with an index-aligned
            // guard view (the v0.6.0 harness's own pattern)
            HallucinationGuard.Result g = HallucinationGuard.apply(segs);
            List<HallucinationGuard.Segment> surv = g.surviving(segs);
            HallucinationGuard.Result aligned = new HallucinationGuard.Result();
            for (int i = 0; i < segs.size(); i++) {
                HallucinationGuard.Decision d = g.decisions.get(i);
                if (d.verdict == HallucinationGuard.Verdict.DROP) continue;
                HallucinationGuard.Decision nd = new HallucinationGuard.Decision();
                nd.verdict = d.verdict;
                nd.reason = d.reason;
                aligned.decisions.add(nd);
                if (d.verdict == HallucinationGuard.Verdict.KEEP) aligned.kept++;
                else aligned.flagged++;
            }
            List<UnitMerge.Unit> units = UnitMerge.merge(surv, aligned);
            for (UnitMerge.Unit u : units) {
                String en = mt.translate(u.srcText);
                if (en == null || en.trim().isEmpty()) continue;
                CookedSubs.Line ln = new CookedSubs.Line();
                ln.startMs = Math.round(u.start * 1000.0);
                ln.endMs = Math.round(u.end * 1000.0);
                ln.text = en.trim();
                ln.orig = u.srcText;
                ln.speaker = u.speaker == null ? "" : u.speaker;
                ln.flagged = u.flagged;
                lines.add(ln);
            }
            double wall = (System.nanoTime() - w0) / 1e9;
            if (listener != null) {
                listener.onChunk(ci, chunkLen, wall, new ArrayList<>(lines));
            }
        }
        return lines;
    }

    @Override
    public void close() {
        try { asr.close(); } catch (Exception ignored) {}
        try { mt.close(); } catch (Exception ignored) {}
    }
}
