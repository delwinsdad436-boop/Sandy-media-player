package com.talacore.talatv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.zip.Deflater;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Whisper-base on ONNX Runtime (v0.6.1) — the recognizer half of
 * the bound engine. The decode is the phone bench's recipe as
 * whisper itself runs it, ported from the verified desktop
 * prototype (work/v061/proto_asr.py), whose staged runs found and
 * fixed each of these rules empirically:
 *
 *   fixed 30 s windows (features per window, MelSpectrogram),
 *   seeked within a chunk to the last segment's end (whisper's own
 *   windowing — driven by OnnxCookerEngine);
 *   prompt <|startoftranscript|> <|ja|> <|transcribe|> run ONCE
 *   through decoder_model, which also produces the self- and
 *   cross-attention caches; every later step is one token through
 *   decoder_with_past, which returns only the updated self cache
 *   (the cross cache is re-fed unchanged);
 *   BEAM 5 search; the generation config's suppress-token list at
 *   every step, begin-suppress at the first; <|notimestamps|>
 *   (50363) suppressed everywhere; the FIRST generated token forced
 *   to be a timestamp token (timestamp mode — without it, whole
 *   windows fall into no-timestamps free-run: measured 4,800 JA
 *   chars against the reference's 912);
 *   whisper's TIMESTAMP MASS RULE at every step: when the summed
 *   probability of the timestamp tokens beats the best single text
 *   token, only a timestamp may come next. Without it the shouting
 *   scene decoded as one perfect phrase repeated ~20 times under a
 *   single timestamp; with it, the same window yields the phrase
 *   in two clean timestamped segments;
 *   timestamps constrained to the window and non-decreasing;
 *   total decoder length capped at 448 INCLUDING the prompt —
 *   whisper's positional table ends there, and running past it
 *   makes the with_past graph fail (also found by the prototype);
 *   a window is DEGENERATE when its text's compression ratio
 *   exceeds 2.4 while it failed to segment (< 3 timestamp tokens —
 *   the observed loop signature: one long unsegmented blob), or
 *   its mean logprob is under -1.0 (whisper's two thresholds).
 *   A degenerate window is re-decoded by SAMPLING on whisper's
 *   temperature ladder (0.2, 0.4, 0.6, 0.8, 1.0, seeded per window
 *   for reproducibility); the first rung passing the same test
 *   wins; if none does, the window yields nothing. A properly
 *   timestamped window of genuinely repetitive content (a chant)
 *   is NOT degenerate — cross-segment repetition is the
 *   hallucination guard's jurisdiction downstream;
 *   segments split on timestamp tokens; per-segment avg logprob;
 *   the window's no_speech_prob read off the prompt step's
 *   distribution, the way whisper itself reports it.
 *
 * Past tensors are carried as plain float arrays copied out of
 * each step's result, so no OrtSession.Result lifetime rules
 * apply to them. Past-tensor names are discovered from the
 * graphs at open time and classified self (".decoder.") vs
 * cross (".encoder."), so the class does not depend on one
 * export vintage's naming.
 *
 * Language is Japanese in this pack (the pair is ja-en); the
 * language token is a constructor argument so another pair's
 * pack reuses the class unchanged.
 */
public final class WhisperOnnx implements AutoCloseable {

    public static final int SOT = 50258;
    public static final int TRANSCRIBE = 50359;
    public static final int EOS = 50257;
    public static final int NO_SPEECH = 50362;
    public static final int NO_TIMESTAMPS = 50363;
    public static final int TS_BASE = 50364;
    public static final int BEAM = 5;
    private static final int VOCAB = 51865;
    private static final int HEADS = 8;
    private static final int HEAD_DIM = 64;
    /** Total decoder sequence length cap, prompt included. */
    public static final int MAX_LENGTH = 448;
    /** Whisper's degeneration thresholds. */
    public static final double COMPRESSION_LIMIT = 2.4;
    public static final double LOGPROB_LIMIT = -1.0;
    private static final double[] LADDER = {0.2, 0.4, 0.6, 0.8, 1.0};

    /** The generation config's suppress_tokens (whisper-base). */
    private static final int[] SUPPRESS = {
            1, 2, 7, 8, 9, 10, 14, 25, 26, 27, 28, 29, 31, 58, 59, 60,
            61, 62, 63, 90, 91, 92, 93, 359, 503, 522, 542, 873, 893,
            902, 918, 922, 931, 1350, 1853, 1982, 2460, 2627, 3246,
            3253, 3268, 3536, 3846, 3961, 4183, 4667, 6585, 6647, 7273,
            9061, 9383, 10428, 10929, 11938, 12033, 12331, 12562, 13793,
            14157, 14635, 15265, 15618, 16553, 16604, 18362, 18956,
            20075, 21675, 22520, 26130, 26161, 26435, 28279, 29464,
            31650, 32302, 32470, 36865, 42863, 47425, 49870, 50254,
            50258, 50358, 50359, 50360, 50361, 50362};
    private static final int[] BEGIN_SUPPRESS = {220, 50257};
    private static final double NEG = -1e30;

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final OrtSession decoderPast;
    /** with_past past-input names, self-attention, in (layer,kind) order. */
    private final List<String> selfIn;
    /** with_past past-input names, cross-attention, same ordering. */
    private final List<String> crossIn;
    private final int langToken;
    private final WhisperVocab vocab;

    public WhisperOnnx(OrtEnvironment env, java.io.File asrDir, int langToken,
                       WhisperVocab vocab) throws OrtException {
        this.env = env;
        this.langToken = langToken;
        this.vocab = vocab;
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(2);
        o.setInterOpNumThreads(1);
        encoder = env.createSession(new java.io.File(asrDir, "encoder_model.onnx").getPath(), o);
        decoder = env.createSession(new java.io.File(asrDir, "decoder_model.onnx").getPath(), o);
        decoderPast = env.createSession(
                new java.io.File(asrDir, "decoder_with_past_model.onnx").getPath(), o);
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

    /** Sort key ordering by layer, then key-before-value, so the self
     *  and cross lists line up with the step-0 presents by name. */
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

    /** The step-0 output name feeding a given with_past past input. */
    private static String presentName(String pastInName) {
        return pastInName.replace("past_key_values", "present");
    }

    /**
     * A past tensor as a FLAT float array + its sequence length.
     * The bridge carries pasts flat end-to-end: OnnxTensor
     * creation from nested Java arrays walks them reflectively
     * (TensorInfo.extractShape / Array.get per sub-array) and that
     * walk, not the model, dominated the first bridge version's
     * runtime. Flat buffers bulk-copy.
     */
    private static final class FlatPast {
        final float[] data;
        final int len;
        FlatPast(float[] data, int len) { this.data = data; this.len = len; }
    }

    /** Copy a result tensor's values out (before the Result closes). */
    private FlatPast readPast(OrtSession.Result r, String name) throws Exception {
        OnnxTensor t = (OnnxTensor) r.get(name).get();
        long[] shape = t.getInfo().getShape();
        java.nio.FloatBuffer fb = t.getFloatBuffer();
        float[] d = new float[fb.remaining()];
        fb.get(d);
        return new FlatPast(d, (int) shape[2]);
    }

    private float[] readLogits(OrtSession.Result r, int vocab) throws Exception {
        OnnxTensor t = (OnnxTensor) r.get("logits").get();
        java.nio.FloatBuffer fb = t.getFloatBuffer();
        float[] last = new float[vocab];
        fb.position(fb.remaining() - vocab);
        fb.get(last);
        return last;
    }

    /** One 30 s window of audio -> recognized segments (guard's shape). */
    public List<HallucinationGuard.Segment> transcribeWindow(
            float[][] mel, double windowStartSec) throws Exception {
        // mel is [80][3000]; the encoder wants [1,80,3000] — the
        // same order, flattened into one buffer
        float[] melFlat = new float[80 * 3000];
        for (int m = 0; m < 80; m++) {
            System.arraycopy(mel[m], 0, melFlat, m * 3000, 3000);
        }
        float[] encFlat;
        try (OnnxTensor in = OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(melFlat), new long[]{1, 80, 3000});
             OrtSession.Result r = encoder.run(
                     Collections.singletonMap("input_features", in))) {
            OnnxTensor t = (OnnxTensor) r.get(0);
            java.nio.FloatBuffer fb = t.getFloatBuffer();
            encFlat = new float[fb.remaining()];
            fb.get(encFlat);
        }
        DecodeResult dr = beamDecode(encFlat);
        if (dr.degenerate) {
            DecodeResult accepted = null;
            for (double temp : LADDER) {
                DecodeResult sr = sampleDecode(encFlat, temp,
                        1234L + (long) windowStartSec);
                if (!sr.degenerate) { accepted = sr; break; }
            }
            if (accepted == null) return new ArrayList<>();
            dr = accepted;
        }
        return toSegments(dr, windowStartSec);
    }

    // ------------------------------------------------------------ step 0

    private static final class Step0 {
        double[] lpRaw;               // prompt step's logprobs, unmasked
        FlatPast[] selfPast;
        FlatPast[] crossPast;
    }

    private Step0 runStep0(float[] encFlat) throws Exception {
        int[] prompt = {SOT, langToken, TRANSCRIBE};
        Step0 s = new Step0();
        Map<String, OnnxTensor> feed = new HashMap<>();
        long[][] promptArr = new long[1][prompt.length];
        for (int i = 0; i < prompt.length; i++) promptArr[0][i] = prompt[i];
        OnnxTensor ids = OnnxTensor.createTensor(env, promptArr);
        OnnxTensor encT = OnnxTensor.createTensor(env,
                java.nio.FloatBuffer.wrap(encFlat), new long[]{1, 1500, 512});
        feed.put("input_ids", ids);
        feed.put("encoder_hidden_states", encT);
        try (OrtSession.Result r = decoder.run(feed)) {
            s.lpRaw = logSoftmax(readLogits(r, VOCAB));
            s.selfPast = new FlatPast[selfIn.size()];
            for (int i = 0; i < selfIn.size(); i++) {
                s.selfPast[i] = readPast(r, presentName(selfIn.get(i)));
            }
            s.crossPast = new FlatPast[crossIn.size()];
            for (int i = 0; i < crossIn.size(); i++) {
                s.crossPast[i] = readPast(r, presentName(crossIn.get(i)));
            }
        } finally {
            ids.close();
            encT.close();
        }
        return s;
    }

    /**
     * The cross-attention pasts are CONSTANT for a whole window —
     * 12 tensors of [1,8,1500,64] ≈ 37 MB. They are tensorized
     * ONCE per decode and the same OnnxTensor objects are fed to
     * every step. (The first bridge version re-created them from
     * nested arrays on every step; that re-upload dominated the
     * runtime — measured, see BINDING-VERDICT.md.)
     */
    private OnnxTensor[] tensorizeCross(FlatPast[] crossPast) throws Exception {
        OnnxTensor[] out = new OnnxTensor[crossPast.length];
        for (int i = 0; i < crossPast.length; i++) {
            FlatPast p = crossPast[i];
            out[i] = OnnxTensor.createTensor(env,
                    java.nio.FloatBuffer.wrap(p.data),
                    new long[]{1, HEADS, p.len, HEAD_DIM});
        }
        return out;
    }

    private static void closeAll(OnnxTensor[] ts) {
        if (ts == null) return;
        for (OnnxTensor t : ts) {
            try { t.close(); } catch (Exception ignored) {}
        }
    }

    /** One with_past step: returns {logits, newSelfPast}. */
    private Object[] runStep(int lastToken, FlatPast[] selfPast,
                             OnnxTensor[] crossT) throws Exception {
        Map<String, OnnxTensor> feed = new HashMap<>();
        List<OnnxTensor> made = new ArrayList<>();
        OnnxTensor ids = OnnxTensor.createTensor(env, new long[][]{{lastToken}});
        made.add(ids);
        feed.put("input_ids", ids);
        for (int i = 0; i < selfIn.size(); i++) {
            FlatPast p = selfPast[i];
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
            logits = readLogits(r, VOCAB);
            for (int i = 0; i < selfIn.size(); i++) {
                newSelf[i] = readPast(r, presentName(selfIn.get(i)));
            }
        } finally {
            for (OnnxTensor t : made) t.close();
        }
        return new Object[]{logits, newSelf};
    }

    // ------------------------------------------------------------ decoding

    private static final class Beam {
        int[] tokens;                 // generated tokens only
        double score;
        FlatPast[] selfPast;
        List<Double> tokenLps = new ArrayList<>();
        boolean finished;
    }

    private static final class DecodeResult {
        int[] tokens = new int[0];
        double[] lps = new double[0];
        double noSpeechProb;
        boolean degenerate;
    }

    private DecodeResult beamDecode(float[] encFlat) throws Exception {
        Step0 s0 = runStep0(encFlat);
        OnnxTensor[] crossT = tensorizeCross(s0.crossPast);
        try {
            return beamDecodeRun(s0, crossT);
        } finally {
            closeAll(crossT);
        }
    }

    private DecodeResult beamDecodeRun(Step0 s0, OnnxTensor[] crossT) throws Exception {
        double noSpeech = Math.exp(s0.lpRaw[NO_SPEECH]);
        double[] lp0 = s0.lpRaw.clone();
        for (int s : SUPPRESS) lp0[s] = NEG;
        for (int s : BEGIN_SUPPRESS) lp0[s] = NEG;
        lp0[NO_TIMESTAMPS] = NEG;
        for (int t = 0; t < TS_BASE; t++) lp0[t] = NEG;   // first token = timestamp
        List<Beam> beams = new ArrayList<>();
        for (int t : topK(lp0, BEAM)) {
            Beam b = new Beam();
            b.tokens = new int[]{t};
            b.score = lp0[t];
            b.selfPast = s0.selfPast;
            b.tokenLps.add(lp0[t]);
            b.finished = (t == EOS);
            beams.add(b);
        }
        int maxGen = MAX_LENGTH - 3;
        for (int step = 1; step < maxGen; step++) {
            boolean allFin = true;
            for (Beam b : beams) if (!b.finished) { allFin = false; break; }
            if (allFin) break;
            List<Object[]> cand = new ArrayList<>(); // score, beamIdx, token, newSelfPast, lp
            for (int bi = 0; bi < beams.size(); bi++) {
                Beam b = beams.get(bi);
                if (b.finished) {
                    cand.add(new Object[]{b.score, bi, null, null, null});
                    continue;
                }
                Object[] st = runStep(b.tokens[b.tokens.length - 1],
                        b.selfPast, crossT);
                float[] logits = (float[]) st[0];
                @SuppressWarnings("unchecked")
                FlatPast[] newSelf = (FlatPast[]) st[1];
                double[] lp = maskStep(logSoftmax(logits), b.tokens);
                for (int t : topK(lp, BEAM)) {
                    cand.add(new Object[]{b.score + lp[t], bi, t, newSelf, lp[t]});
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
                    nb.tokenLps = src.tokenLps;
                    nb.finished = true;
                } else {
                    int t = (Integer) c[2];
                    nb.tokens = Arrays.copyOf(src.tokens, src.tokens.length + 1);
                    nb.tokens[src.tokens.length] = t;
                    nb.score = (Double) c[0];
                    @SuppressWarnings("unchecked")
                    FlatPast[] np = (FlatPast[]) c[3];
                    nb.selfPast = np;
                    nb.tokenLps = new ArrayList<>(src.tokenLps);
                    nb.tokenLps.add((Double) c[4]);
                    nb.finished = (t == EOS);
                }
                next.add(nb);
            }
            beams = next;
        }
        Beam best = null;
        double bestMean = Double.NEGATIVE_INFINITY;
        for (Beam b : beams) {
            if (b.tokens.length == 0) continue;
            double mean = b.score / b.tokens.length;
            if (mean > bestMean) { bestMean = mean; best = b; }
        }
        DecodeResult out = new DecodeResult();
        out.noSpeechProb = noSpeech;
        if (best != null) {
            out.tokens = best.tokens;
            out.lps = new double[best.tokenLps.size()];
            for (int i = 0; i < out.lps.length; i++) out.lps[i] = best.tokenLps.get(i);
        }
        out.degenerate = isDegenerate(out.tokens, out.lps);
        return out;
    }

    private DecodeResult sampleDecode(float[] encFlat, double temp,
                                      long seed) throws Exception {
        Step0 s0 = runStep0(encFlat);
        OnnxTensor[] crossT = tensorizeCross(s0.crossPast);
        try {
            return sampleDecodeRun(s0, crossT, temp, seed);
        } finally {
            closeAll(crossT);
        }
    }

    private DecodeResult sampleDecodeRun(Step0 s0, OnnxTensor[] crossT,
                                         double temp, long seed) throws Exception {
        double noSpeech = Math.exp(s0.lpRaw[NO_SPEECH]);
        Random rng = new Random(seed);
        List<Integer> toks = new ArrayList<>();
        List<Double> lps = new ArrayList<>();
        double[] lp0 = s0.lpRaw.clone();
        for (int s : SUPPRESS) lp0[s] = NEG;
        for (int s : BEGIN_SUPPRESS) lp0[s] = NEG;
        lp0[NO_TIMESTAMPS] = NEG;
        for (int t = 0; t < TS_BASE; t++) lp0[t] = NEG;
        int t = draw(lp0, temp, rng);
        toks.add(t);
        lps.add(s0.lpRaw[t]);
        FlatPast[] selfPast = s0.selfPast;
        int maxGen = MAX_LENGTH - 3;
        for (int step = 1; step < maxGen; step++) {
            if (toks.get(toks.size() - 1) == EOS) break;
            Object[] st = runStep(toks.get(toks.size() - 1), selfPast, crossT);
            float[] logits = (float[]) st[0];
            @SuppressWarnings("unchecked")
            FlatPast[] newSelf = (FlatPast[]) st[1];
            selfPast = newSelf;
            double[] rawLp = logSoftmax(logits);
            int[] hist = new int[toks.size()];
            for (int i = 0; i < hist.length; i++) hist[i] = toks.get(i);
            double[] masked = maskStep(rawLp.clone(), hist);
            t = draw(masked, temp, rng);
            toks.add(t);
            lps.add(rawLp[t]);
        }
        DecodeResult out = new DecodeResult();
        out.noSpeechProb = noSpeech;
        out.tokens = new int[toks.size()];
        for (int i = 0; i < out.tokens.length; i++) out.tokens[i] = toks.get(i);
        out.lps = new double[lps.size()];
        for (int i = 0; i < out.lps.length; i++) out.lps[i] = lps.get(i);
        out.degenerate = isDegenerate(out.tokens, out.lps);
        return out;
    }

    /** Draw one token from softmax(maskedLogprobs / temp). */
    private static int draw(double[] maskedLp, double temp, Random rng) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : maskedLp) {
            double z = v / temp;
            if (z > max) max = z;
        }
        double sum = 0;
        double[] w = new double[maskedLp.length];
        for (int i = 0; i < w.length; i++) {
            w[i] = Math.exp(maskedLp[i] / temp - max);
            sum += w[i];
        }
        double x = rng.nextDouble() * sum;
        double acc = 0;
        for (int i = 0; i < w.length; i++) {
            acc += w[i];
            if (x <= acc) return i;
        }
        return w.length - 1;
    }

    /** The per-step masks: suppress list, no-timestamps token,
     *  timestamp window/monotonicity, then the timestamp mass rule. */
    private static double[] maskStep(double[] lp, int[] history) {
        for (int s : SUPPRESS) lp[s] = NEG;
        lp[NO_TIMESTAMPS] = NEG;
        int lastTs = -1;
        for (int i = history.length - 1; i >= 0; i--) {
            if (history[i] >= TS_BASE) { lastTs = history[i]; break; }
        }
        for (int t = TS_BASE; t < lp.length; t++) {
            double ts = (t - TS_BASE) * 0.02;
            if (ts > 30.0 || (lastTs >= 0 && t < lastTs)) lp[t] = NEG;
        }
        // timestamp mass rule: if the timestamps' total mass beats
        // the best text token, only a timestamp may come next
        double tsMax = Double.NEGATIVE_INFINITY;
        for (int t = TS_BASE; t < lp.length; t++) if (lp[t] > tsMax) tsMax = lp[t];
        if (tsMax > NEG / 2) {
            double sum = 0;
            for (int t = TS_BASE; t < lp.length; t++) {
                if (lp[t] > NEG / 2) sum += Math.exp(lp[t] - tsMax);
            }
            double mass = tsMax + Math.log(sum);
            double bestText = Double.NEGATIVE_INFINITY;
            for (int t = 0; t < TS_BASE; t++) if (lp[t] > bestText) bestText = lp[t];
            if (mass > bestText) {
                for (int t = 0; t < TS_BASE; t++) lp[t] = NEG;
            }
        }
        return lp;
    }

    /** The degeneration test (see class doc). The compression ratio
     *  is computed on the decoded text (special tokens excluded,
     *  WhisperVocab's own decode). */
    private boolean isDegenerate(int[] tokens, double[] lps) {
        if (tokens.length == 0) return false;
        double sum = 0;
        for (double v : lps) sum += v;
        double mean = sum / tokens.length;
        if (mean < LOGPROB_LIMIT) return true;
        int nTs = 0;
        for (int t : tokens) if (t >= TS_BASE) nTs++;
        if (nTs >= 3) return false;
        String text = vocab.decode(tokens);
        byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        if (raw.length == 0) return false;
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        double ratio = raw.length / (double) Math.max(1, bos.size());
        return ratio > COMPRESSION_LIMIT;
    }

    // ------------------------------------------------------------ segments

    private List<HallucinationGuard.Segment> toSegments(DecodeResult dr,
                                                        double winStart) {
        List<HallucinationGuard.Segment> segs = new ArrayList<>();
        List<Integer> curToks = new ArrayList<>();
        List<Double> curLps = new ArrayList<>();
        double curStart = -1, lastTs = 0;
        for (int i = 0; i < dr.tokens.length; i++) {
            int t = dr.tokens[i];
            double lp = i < dr.lps.length ? dr.lps[i] : 0.0;
            if (t >= TS_BASE) {
                double ts = (t - TS_BASE) * 0.02;
                if (ts < lastTs) ts = lastTs;
                lastTs = ts;
                if (curStart < 0) {
                    curStart = ts;
                } else {
                    flushSeg(segs, curToks, curLps, winStart + curStart,
                            winStart + ts, dr.noSpeechProb);
                    curStart = ts;
                }
            } else if (t == EOS) {
                if (!curToks.isEmpty()) {
                    double end = lastTs > curStart ? lastTs : curStart + 0.02;
                    flushSeg(segs, curToks, curLps, winStart + curStart,
                            winStart + end, dr.noSpeechProb);
                    curStart = -1;
                }
            } else {
                if (curStart < 0) curStart = lastTs;
                curToks.add(t);
                curLps.add(lp);
            }
        }
        if (!curToks.isEmpty()) {
            double end = lastTs > curStart ? lastTs : curStart + 0.02;
            flushSeg(segs, curToks, curLps, winStart + curStart,
                    winStart + end, dr.noSpeechProb);
        }
        return segs;
    }

    private void flushSeg(List<HallucinationGuard.Segment> segs,
                          List<Integer> toks, List<Double> lps,
                          double start, double end, double noSpeech) {
        if (toks.isEmpty()) return;
        int[] arr = new int[toks.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = toks.get(i);
        String text = vocab.decode(arr).trim();
        if (!text.isEmpty()) {
            double sum = 0;
            for (double v : lps) sum += v;
            double avg = lps.isEmpty() ? 0.0 : sum / lps.size();
            segs.add(new HallucinationGuard.Segment(start, end, text, avg, noSpeech));
        }
        toks.clear();
        lps.clear();
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
