package com.talacore.talatv;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Opus-MT (Marian) ja->en on ONNX Runtime (v0.6.1) — the translator
 * half of the bound engine. The recipe is the phone bench's CT2
 * recipe exactly, ported from the verified desktop prototype
 * (work/v061/proto_mt.py):
 *
 *   source ids = SentencePiece pieces of the text + </s> (appending
 *   </s> is mandatory — without it the decoder loops);
 *   encoder over the ids; the decoder starts from <pad> (60715),
 *   step 0 through decoder_model (which also produces the self- and
 *   cross-attention caches), later steps one token through
 *   decoder_with_past (only the self cache comes back; the cross
 *   cache is re-fed unchanged); BEAM 4; <pad> suppressed at every
 *   step (the generation config's bad_words rule); stop on </s>;
 *   max 200 generated tokens (the bench's max_decoding_length).
 *
 * Past tensors are carried as plain float arrays copied out of each
 * step's result; names are discovered from the graphs at open time
 * and classified self (".decoder.") vs cross (".encoder.").
 */
public final class OpusMtOnnx implements AutoCloseable {

    public static final int EOS = 0;        // </s>
    public static final int PAD = 60715;    // <pad> = decoder start
    public static final int BEAM = 4;
    public static final int MAX_LEN = 200;
    private static final int VOCAB = 60716;
    private static final int HEADS = 8;
    private static final int HEAD_DIM = 64;
    private static final double NEG = -1e30;

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final OrtSession decoderPast;
    private final List<String> selfIn;
    private final List<String> crossIn;
    private final SentencePiece sp;

    public OpusMtOnnx(OrtEnvironment env, File mtDir, SentencePiece sp)
            throws OrtException {
        this.env = env;
        this.sp = sp;
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(2);
        o.setInterOpNumThreads(1);
        encoder = env.createSession(new File(mtDir, "encoder_model.onnx").getPath(), o);
        decoder = env.createSession(new File(mtDir, "decoder_model.onnx").getPath(), o);
        decoderPast = env.createSession(
                new File(mtDir, "decoder_with_past_model.onnx").getPath(), o);
        Map<String, String> self = new TreeMap<>();
        Map<String, String> cross = new TreeMap<>();
        for (String name : decoderPast.getInputNames()) {
            if (!name.startsWith("past_key_values")) continue;
            if (name.contains(".decoder.")) self.put(sortKey(name), name);
            else if (name.contains(".encoder.")) cross.put(sortKey(name), name);
        }
        if (self.isEmpty() || cross.isEmpty()) {
            throw new IllegalStateException("decoder_with_past past tensors "
                    + "not found (self=" + self.size() + ", cross=" + cross.size() + ")");
        }
        selfIn = new ArrayList<>(self.values());
        crossIn = new ArrayList<>(cross.values());
    }

    private static String sortKey(String name) {
        String[] p = name.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < p.length; i++) {
            String t = p[i];
            if (t.matches("\\d+")) sb.append(String.format("%04d", Integer.parseInt(t)));
            else if (t.equals("key")) sb.append("0");
            else if (t.equals("value")) sb.append("1");
            else sb.append("0");
            sb.append('.');
        }
        return sb.toString();
    }

    private static String presentName(String pastInName) {
        return pastInName.replace("past_key_values", "present");
    }

    /** A past tensor as a FLAT float array + sequence length (see
     *  WhisperOnnx.FlatPast for why: nested-array tensor creation
     *  is reflective and dominated the first bridge version). */
    private static final class FlatPast {
        final float[] data;
        final int len;
        FlatPast(float[] data, int len) { this.data = data; this.len = len; }
    }

    private FlatPast readPast(OrtSession.Result r, String name) throws Exception {
        OnnxTensor t = (OnnxTensor) r.get(name).get();
        long[] shape = t.getInfo().getShape();
        java.nio.FloatBuffer fb = t.getFloatBuffer();
        float[] d = new float[fb.remaining()];
        fb.get(d);
        return new FlatPast(d, (int) shape[2]);
    }

    private static final class Beam {
        int[] tokens;                 // generated tokens only
        double score;
        FlatPast[] selfPast;
        boolean finished;
    }

    /** Translate one merged unit of source text. */
    public String translate(String sourceText) throws Exception {
        // the recipe's mandatory trailing </s> (SentencePiece.encode
        // leaves it off on purpose; without it the decoder loops)
        int[] pieces = sp.encode(sourceText);
        int[] srcIds = Arrays.copyOf(pieces, pieces.length + 1);
        srcIds[pieces.length] = EOS;
        long[][] ids = new long[1][srcIds.length];
        long[][] mask = new long[1][srcIds.length];
        for (int i = 0; i < srcIds.length; i++) {
            ids[0][i] = srcIds[i];
            mask[0][i] = 1;
        }
        float[] encFlat;
        int srcLen = srcIds.length;
        Map<String, OnnxTensor> ef = new HashMap<>();
        OnnxTensor idT = OnnxTensor.createTensor(env, ids);
        OnnxTensor maskT = OnnxTensor.createTensor(env, mask);
        ef.put("input_ids", idT);
        ef.put("attention_mask", maskT);
        try (OrtSession.Result r = encoder.run(ef)) {
            OnnxTensor t = (OnnxTensor) r.get(0);
            java.nio.FloatBuffer fb = t.getFloatBuffer();
            encFlat = new float[fb.remaining()];
            fb.get(encFlat);
        } finally {
            idT.close();
            maskT.close();
        }
        // ---- step 0: <pad> through decoder_model ----
        FlatPast[] selfPast0;
        FlatPast[] crossPast;
        double[] lp0;
        Map<String, OnnxTensor> f0 = new HashMap<>();
        OnnxTensor startT = OnnxTensor.createTensor(env, new long[][]{{PAD}});
        OnnxTensor encT = OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(encFlat), new long[]{1, srcLen, 512});
        OnnxTensor encMaskT = OnnxTensor.createTensor(env, mask);
        f0.put("input_ids", startT);
        f0.put("encoder_hidden_states", encT);
        f0.put("encoder_attention_mask", encMaskT);
        try (OrtSession.Result r = decoder.run(f0)) {
            OnnxTensor lt = (OnnxTensor) r.get("logits").get();
            java.nio.FloatBuffer lb = lt.getFloatBuffer();
            float[] lg = new float[VOCAB];
            lb.position(lb.remaining() - VOCAB);
            lb.get(lg);
            lp0 = logSoftmax(lg);
            selfPast0 = new FlatPast[selfIn.size()];
            for (int i = 0; i < selfIn.size(); i++) {
                selfPast0[i] = readPast(r, presentName(selfIn.get(i)));
            }
            crossPast = new FlatPast[crossIn.size()];
            for (int i = 0; i < crossIn.size(); i++) {
                crossPast[i] = readPast(r, presentName(crossIn.get(i)));
            }
        } finally {
            startT.close();
            encT.close();
            encMaskT.close();
        }
        lp0[PAD] = NEG;
        // constant per translation: tensorize ONCE (the first
        // bridge version re-created the cross pasts and the mask
        // from nested arrays on every beam step — measured as the
        // runtime dominator; see BINDING-VERDICT.md)
        OnnxTensor[] crossT = new OnnxTensor[crossIn.size()];
        for (int i = 0; i < crossIn.size(); i++) {
            FlatPast p = crossPast[i];
            crossT[i] = OnnxTensor.createTensor(env,
                    java.nio.FloatBuffer.wrap(p.data),
                    new long[]{1, HEADS, p.len, HEAD_DIM});
        }
        OnnxTensor maskStepT = OnnxTensor.createTensor(env, mask);
        try {
            return translateBeams(lp0, selfPast0, crossT, maskStepT);
        } finally {
            for (OnnxTensor t : crossT) {
                try { t.close(); } catch (Exception ignored) {}
            }
            try { maskStepT.close(); } catch (Exception ignored) {}
        }
    }

    private String translateBeams(double[] lp0, FlatPast[] selfPast0,
                                  OnnxTensor[] crossT, OnnxTensor maskStepT)
            throws Exception {
        List<Beam> beams = new ArrayList<>();
        for (int t : topK(lp0, BEAM)) {
            Beam b = new Beam();
            b.tokens = new int[]{t};
            b.score = lp0[t];
            b.selfPast = selfPast0;
            b.finished = (t == EOS);
            beams.add(b);
        }
        // ---- steps 1.. : one token through decoder_with_past ----
        for (int step = 1; step < MAX_LEN; step++) {
            boolean allFin = true;
            for (Beam b : beams) if (!b.finished) { allFin = false; break; }
            if (allFin) break;
            List<Object[]> cand = new ArrayList<>(); // score, beamIdx, token, newSelfPast
            for (int bi = 0; bi < beams.size(); bi++) {
                Beam b = beams.get(bi);
                if (b.finished) {
                    cand.add(new Object[]{b.score, bi, null, null});
                    continue;
                }
                Map<String, OnnxTensor> feed = new HashMap<>();
                List<OnnxTensor> made = new ArrayList<>();
                OnnxTensor id1 = OnnxTensor.createTensor(env,
                        new long[][]{{b.tokens[b.tokens.length - 1]}});
                made.add(id1);
                feed.put("input_ids", id1);
                feed.put("encoder_attention_mask", maskStepT);
                for (int i = 0; i < selfIn.size(); i++) {
                    FlatPast p = b.selfPast[i];
                    OnnxTensor t = OnnxTensor.createTensor(env,
                            java.nio.FloatBuffer.wrap(p.data),
                            new long[]{1, HEADS, p.len, HEAD_DIM});
                    made.add(t);
                    feed.put(selfIn.get(i), t);
                }
                for (int i = 0; i < crossIn.size(); i++) {
                    feed.put(crossIn.get(i), crossT[i]);
                }
                float[] logits;
                FlatPast[] newSelf = new FlatPast[selfIn.size()];
                try (OrtSession.Result r = decoderPast.run(feed)) {
                    OnnxTensor lt = (OnnxTensor) r.get("logits").get();
                    java.nio.FloatBuffer lb = lt.getFloatBuffer();
                    logits = new float[VOCAB];
                    lb.position(lb.remaining() - VOCAB);
                    lb.get(logits);
                    for (int i = 0; i < selfIn.size(); i++) {
                        newSelf[i] = readPast(r, presentName(selfIn.get(i)));
                    }
                } finally {
                    for (OnnxTensor t : made) t.close();
                }
                double[] lp = logSoftmax(logits);
                lp[PAD] = NEG;
                for (int t : topK(lp, BEAM)) {
                    cand.add(new Object[]{b.score + lp[t], bi, t, newSelf});
                }
            }
            cand.sort((a, z) -> Double.compare((Double) z[0], (Double) a[0]));
            List<Beam> next = new ArrayList<>();
            for (Object[] c : cand) {
                if (next.size() >= BEAM) break;
                Beam src = beams.get((Integer) c[1]);
                Beam nb = new Beam();
                if (c[2] == null) {
                    nb.tokens = src.tokens;
                    nb.score = src.score;
                    nb.selfPast = src.selfPast;
                    nb.finished = true;
                } else {
                    int t = (Integer) c[2];
                    nb.tokens = Arrays.copyOf(src.tokens, src.tokens.length + 1);
                    nb.tokens[src.tokens.length] = t;
                    nb.score = (Double) c[0];
                    @SuppressWarnings("unchecked")
                    FlatPast[] np = (FlatPast[]) c[3];
                    nb.selfPast = np;
                    nb.finished = (t == EOS);
                }
                next.add(nb);
            }
            beams = next;
        }
        // Selection: GNMT length penalty — score / ((5 + len) / 6)
        // — among FINISHED hypotheses only. The desktop prototype
        // measured four rules against the CT2 reference; this is
        // the only one reproducing all of CT2's behaviors at once:
        // plain mean logprob preferred a doubled sentence (its
        // second half is near-free logprob) and let an unfinished
        // loop win the chant unit (~200 repetitions of one word);
        // raw cumulative score killed the loops but truncated
        // tails (", Your Highness." lost). Unfinished hypotheses
        // are excluded unless none finished.
        Beam best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Beam b : beams) {
            if (!b.finished || b.tokens.length == 0) continue;
            double s = b.score / ((5 + b.tokens.length) / 6.0);
            if (s > bestScore) { bestScore = s; best = b; }
        }
        if (best == null) {
            for (Beam b : beams) {
                if (b.tokens.length == 0) continue;
                double s = b.score / ((5 + b.tokens.length) / 6.0);
                if (s > bestScore) { bestScore = s; best = b; }
            }
        }
        if (best == null) return "";
        return sp.decode(best.tokens);
    }

    private static double[] logSoftmax(float[] logits) {
        double max = Double.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;
        double sum = 0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = logits[i] - max;
            sum += Math.exp(out[i]);
        }
        double lse = Math.log(sum);
        for (int i = 0; i < out.length; i++) out[i] -= lse;
        return out;
    }

    private static int[] topK(double[] lp, int k) {
        int[] out = new int[k];
        boolean[] used = new boolean[lp.length];
        for (int j = 0; j < k; j++) {
            int best = -1;
            for (int i = 0; i < lp.length; i++) {
                if (!used[i] && (best < 0 || lp[i] > lp[best])) best = i;
            }
            used[best] = true;
            out[j] = best;
        }
        return out;
    }

    @Override
    public void close() {
        try { encoder.close(); } catch (Exception ignored) {}
        try { decoder.close(); } catch (Exception ignored) {}
        try { decoderPast.close(); } catch (Exception ignored) {}
    }
}
