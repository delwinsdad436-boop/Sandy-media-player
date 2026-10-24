package com.talacore.talatv;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * The diarization seam (v0.12.0): the pack's two ONNX models behind
 * one door — pyannote segmentation-3.0 (who is speaking, frame by
 * frame) and the 3D-Speaker embedding model (whose voice it is).
 * Adopted components, never rebuilt: the models ride the shelf in
 * the diarization pack (DiarPack) and this class only runs them.
 *
 * Segmentation runs in the reference recipe's windows: 10 s of
 * waveform sliding 1 s, batches of 16 windows. Embeddings run over
 * FbankFeatures (the Kaldi front end the embedding model was
 * trained against — validated number-for-number against the
 * reference extractor in work/v120/diar).
 *
 * Construction IS the binding, like the cooker engine: if the
 * sessions open, the organ exists; if they don't, the caller gets
 * the exception and the dub flow plans without it (single voice,
 * said plainly) — the missing organ is never simulated.
 */
public final class DiarOnnx implements AutoCloseable {

    private final OrtSession seg;
    private final OrtSession emb;
    private final String segIn;
    private final String embIn;

    private DiarOnnx(OrtSession seg, OrtSession emb, String segIn, String embIn) {
        this.seg = seg;
        this.emb = emb;
        this.segIn = segIn;
        this.embIn = embIn;
    }

    /** Open both models from an installed diarization pack dir
     *  (seg/model.onnx + emb/model.onnx). Throws on any failure. */
    public static DiarOnnx open(File packDir) throws Exception {
        File segFile = new File(new File(packDir, "seg"), "model.onnx");
        File embFile = new File(new File(packDir, "emb"), "model.onnx");
        if (!segFile.isFile()) throw new Exception("the voice pack has no segmentation model");
        if (!embFile.isFile()) throw new Exception("the voice pack has no speaker model");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        OrtSession seg = env.createSession(segFile.getPath(), opts);
        OrtSession emb = env.createSession(embFile.getPath(), opts);
        return new DiarOnnx(seg, emb,
                seg.getInputNames().iterator().next(),
                emb.getInputNames().iterator().next());
    }

    public boolean isBound() { return seg != null && emb != null; }

    public interface Progress {
        void onWindows(int done, int total);
    }

    /**
     * Segment a whole title: logits [window][frame][class] in the
     * model's own layout. Windows start every WINDOW_SHIFT samples;
     * the tail window is zero-padded (the reference recipe).
     */
    public float[][][] segment(PcmSource src, Progress progress) throws Exception {
        double durSec = src.durationSec();
        int totalSamples = (int) Math.round(durSec * DiarCore.SAMPLE_RATE);
        List<Integer> starts = new ArrayList<>();
        if (totalSamples <= DiarCore.WINDOW_SAMPLES) {
            starts.add(0);
        } else {
            for (int s = 0; s + DiarCore.WINDOW_SAMPLES <= totalSamples;
                 s += DiarCore.WINDOW_SHIFT) {
                starts.add(s);
            }
            int last = starts.get(starts.size() - 1) + DiarCore.WINDOW_SAMPLES;
            if (last < totalSamples) starts.add(starts.get(starts.size() - 1)
                    + DiarCore.WINDOW_SHIFT);
        }
        List<float[][]> out = new ArrayList<>();
        int batch = 16;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        for (int b = 0; b < starts.size(); b += batch) {
            int n = Math.min(batch, starts.size() - b);
            float[][][] in = new float[n][1][DiarCore.WINDOW_SAMPLES];
            for (int i = 0; i < n; i++) {
                double fromSec = starts.get(b + i) / (double) DiarCore.SAMPLE_RATE;
                double wantSec = DiarCore.WINDOW_SAMPLES / (double) DiarCore.SAMPLE_RATE;
                float[] pcm = src.read(fromSec, wantSec);
                if (pcm != null) {
                    System.arraycopy(pcm, 0, in[i][0], 0,
                            Math.min(pcm.length, DiarCore.WINDOW_SAMPLES));
                }
            }
            try (OnnxTensor tensor = OnnxTensor.createTensor(env, in);
                 OrtSession.Result r = seg.run(Collections.singletonMap(segIn, tensor))) {
                float[][][] y = (float[][][]) r.get(0).getValue();
                for (float[][] windowFrames : y) out.add(windowFrames);
            }
            if (progress != null) progress.onWindows(Math.min(b + n, starts.size()), starts.size());
        }
        return out.toArray(new float[0][][]);
    }

    /**
     * One speaker embedding for an audio slice (the model's raw
     * 512-dim output; callers compare with VoiceBook.cosine, which
     * is norm-free). Null when the slice is too short to frame.
     */
    public float[] embed(float[] pcm) throws Exception {
        float[][] feats = FbankFeatures.compute(pcm);
        if (feats.length == 0) return null;
        float[][][] in = new float[1][feats.length][];
        in[0] = feats;
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, in);
             OrtSession.Result r = emb.run(Collections.singletonMap(embIn, tensor))) {
            float[][] out = (float[][]) r.get(0).getValue();
            return out[0];
        }
    }

    @Override
    public void close() {
        try { seg.close(); } catch (Exception ignored) {}
        try { emb.close(); } catch (Exception ignored) {}
    }
}
