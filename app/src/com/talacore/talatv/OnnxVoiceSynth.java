package com.talacore.talatv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * The pack voice itself (v0.13.0) — a pure-Java MMS-TTS runner
 * over the ONNX Runtime Sandy already carries. A voice pack's
 * model is a VITS network that eats CHARACTER ids, so no native
 * phonemizer is needed: the front end is the rule sherpa-onnx's
 * character front end runs, reproduced exactly (and pinned by the
 * harness against the shipped token tables):
 *
 *   text splits into sentences at . ! ? :
 *   per sentence: ids = [0], then for every character the pack's
 *   tokens.txt knows, (id, 0) — blank between characters;
 *   characters the table does not know are SKIPPED (the MMS
 *   vocabularies genuinely lack some punctuation; the model was
 *   trained that way).
 *
 * The graph contract (measured from the reference models):
 * inputs x int64 [1,L], x_length, noise_scale 0.667,
 * length_scale (the rate knob), noise_scale_w 0.8; output y
 * float [1,1,L] at 16000 Hz. Sentences join with a breath of
 * silence; the spoken PCM is silence-trimmed at both ends.
 *
 * Cast variation rides the VoiceBook's own numbers: rate through
 * length_scale, pitch through a PCM resample — and the caller
 * always measures the PCM it actually got (the duration
 * predictor samples inside the graph; no honest number exists
 * before the run).
 */
public final class OnnxVoiceSynth implements AutoCloseable {

    public static final int SAMPLE_RATE = 16000;
    private static final float NOISE_SCALE = 0.667f;
    private static final float NOISE_SCALE_W = 0.8f;
    private static final int JOIN_SILENCE = SAMPLE_RATE * 15 / 100;

    private final OrtSession session;
    private final Map<String, Integer> tokens;

    private OnnxVoiceSynth(OrtSession session, Map<String, Integer> tokens) {
        this.session = session;
        this.tokens = tokens;
    }

    /** Open an installed voice pack dir (model.onnx + tokens.txt). */
    public static OnnxVoiceSynth open(File packDir) throws Exception {
        File model = new File(packDir, "model.onnx");
        File tokFile = new File(packDir, "tokens.txt");
        if (!model.isFile()) throw new Exception("the voice pack has no model");
        if (!tokFile.isFile()) throw new Exception("the voice pack has no token table");
        Map<String, Integer> tokens = loadTokens(tokFile);
        if (tokens.isEmpty()) throw new Exception("the voice pack's token table is empty");
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        OrtSession session = env.createSession(model.getPath(), opts);
        return new OnnxVoiceSynth(session, tokens);
    }

    public static Map<String, Integer> loadTokens(File f) throws Exception {
        Map<String, Integer> out = new LinkedHashMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                int sp = line.lastIndexOf(' ');
                if (sp < 0) continue;
                String tok = line.substring(0, sp);
                try {
                    out.put(tok, Integer.parseInt(line.substring(sp + 1).trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    // -------------------------------------------------- the front end

    /**
     * The character front end, pure: text -> one id sequence per
     * sentence. Unknown characters are skipped; a sentence with no
     * known characters yields no sequence.
     */
    public static List<long[]> tokenize(Map<String, Integer> tokens, String text) {
        List<long[]> out = new ArrayList<>();
        if (text == null) return out;
        for (String part : text.split("[.!?:]")) {
            List<Long> ids = new ArrayList<>();
            ids.add(0L);
            for (int i = 0; i < part.length(); i++) {
                String ch = part.substring(i, i + 1);
                Integer id = tokens.get(ch);
                if (id != null) {
                    ids.add((long) id);
                    ids.add(0L);
                }
            }
            if (ids.size() > 1) {
                long[] a = new long[ids.size()];
                for (int i = 0; i < a.length; i++) a[i] = ids.get(i);
                out.add(a);
            }
        }
        return out;
    }

    /** Pitch a PCM buffer by resampling (factor > 1 = higher). */
    public static float[] pitchShift(float[] in, double factor) {
        if (in.length == 0 || factor <= 0 || Math.abs(factor - 1.0) < 0.01) return in;
        int outLen = Math.max(1, (int) Math.round(in.length / factor));
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double pos = i * factor;
            int j = (int) pos;
            double frac = pos - j;
            float a = in[Math.min(j, in.length - 1)];
            float b = in[Math.min(j + 1, in.length - 1)];
            out[i] = (float) (a + (b - a) * frac);
        }
        return out;
    }

    /** Trim leading/trailing near-silence (with a small pad kept). */
    public static float[] trimSilence(float[] in) {
        if (in.length == 0) return in;
        int first = 0;
        while (first < in.length && Math.abs(in[first]) < 0.004f) first++;
        int last = in.length - 1;
        while (last > first && Math.abs(in[last]) < 0.004f) last--;
        if (first >= in.length) return new float[0];
        int pad = SAMPLE_RATE / 20;
        first = Math.max(0, first - pad);
        last = Math.min(in.length - 1, last + pad);
        float[] out = new float[last - first + 1];
        System.arraycopy(in, first, out, 0, out.length);
        return out;
    }

    // -------------------------------------------------- synthesis

    /** Speak one line of text; returns 16 kHz mono PCM (trimmed). */
    public float[] synth(String text, double lengthScale) throws Exception {
        List<long[]> sentences = tokenize(tokens, text);
        if (sentences.isEmpty()) return new float[0];
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        List<float[]> parts = new ArrayList<>();
        int total = 0;
        for (long[] ids : sentences) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("x", OnnxTensor.createTensor(env,
                    LongBuffer.wrap(ids), new long[]{1, ids.length}));
            inputs.put("x_length", OnnxTensor.createTensor(env,
                    LongBuffer.wrap(new long[]{ids.length}), new long[]{1}));
            inputs.put("noise_scale", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(new float[]{NOISE_SCALE}), new long[]{1}));
            inputs.put("length_scale", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(new float[]{(float) lengthScale}), new long[]{1}));
            inputs.put("noise_scale_w", OnnxTensor.createTensor(env,
                    FloatBuffer.wrap(new float[]{NOISE_SCALE_W}), new long[]{1}));
            try (OrtSession.Result r = session.run(inputs)) {
                float[][][] y = (float[][][]) r.get(0).getValue();
                parts.add(y[0][0]);
                total += y[0][0].length + JOIN_SILENCE;
            } finally {
                for (OnnxTensor t : inputs.values()) {
                    try { t.close(); } catch (Exception ignored) {}
                }
            }
        }
        float[] joined = new float[Math.max(0, total - JOIN_SILENCE)];
        int pos = 0;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) pos += JOIN_SILENCE;
            System.arraycopy(parts.get(i), 0, joined, pos, parts.get(i).length);
            pos += parts.get(i).length;
        }
        return trimSilence(joined);
    }

    @Override
    public void close() {
        try { session.close(); } catch (Exception ignored) {}
    }
}
