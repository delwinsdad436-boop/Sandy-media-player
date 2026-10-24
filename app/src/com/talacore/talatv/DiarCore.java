package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * Diarization math (v0.12.0) — pure logic over the segmentation
 * model's output, mirroring the reference pipeline the pack's
 * models were published with (pyannote segmentation-3.0 + a speaker
 * embedding model, the sherpa-onnx diarization recipe):
 *
 *   the waveform is cut into 10 s windows sliding 1 s; each window
 *   is classified per frame into a POWERSET class over up to 3
 *   local speakers (7 classes: nobody, each single, each pair);
 *   argmax decodes each frame to a set of active local speakers;
 *   per (window, local speaker), the speaker's own active audio —
 *   frames where they speak ALONE — is gathered into one embedding
 *   observation; observations are clustered by embedding cosine;
 *   a cooked LINE is assigned the cluster whose observations speak
 *   the most inside the line's window (James's witness design: the
 *   subtitle timings pre-cut the audio, the diarizer only has to
 *   say WHO is talking inside each cut).
 *
 * Local speaker numbers mean nothing across windows — identity
 * lives in the embeddings and the clusters, never in the labels.
 * Constants are the seg model's own published metadata
 * (window 160000 samples, shift 16000, receptive field 991/270).
 *
 * Pure and deterministic; the harness drives it with the real
 * models over a three-speaker fixture with ground truth
 * (work/v120/diar) and with the reference implementation's own
 * scores as the comparison.
 */
public final class DiarCore {

    public static final int SAMPLE_RATE = 16000;
    public static final int WINDOW_SAMPLES = 160000;      // 10 s
    public static final int WINDOW_SHIFT = 16000;         // 1 s
    public static final int FRAME_SHIFT_SAMPLES = 270;    // receptive_field_shift
    public static final int FRAME_SIZE_SAMPLES = 991;     // receptive_field_size
    public static final int LOCAL_SPEAKERS = 3;
    public static final int NUM_CLASSES = 7;
    /** An observation needs at least this many solo frames (~0.17 s). */
    public static final int MIN_OBS_FRAMES = 10;
    /** A line's dominant speaker needs at least this much attributed
     *  speech inside the line, and this share of all attributed. */
    public static final double LINE_MIN_SEC = 0.30;
    public static final double LINE_MIN_SHARE = 0.40;
    /** Cap on audio gathered into one observation embedding. */
    public static final double OBS_AUDIO_CAP_SEC = 12.0;

    private DiarCore() {}

    /** Frame f of window w centers at this time (seconds). */
    public static double frameCenterSec(int window, int frame) {
        return (window * (double) WINDOW_SHIFT
                + frame * (double) FRAME_SHIFT_SAMPLES
                + FRAME_SIZE_SAMPLES / 2.0) / SAMPLE_RATE;
    }

    /** Powerset table [class][localSpeaker] — class 0 nobody,
     *  1..3 the singles in order, 4..6 the pairs (0,1), (0,2), (1,2):
     *  the reference construction order. */
    public static boolean[][] powerset() {
        boolean[][] m = new boolean[NUM_CLASSES][LOCAL_SPEAKERS];
        int k = 1;
        for (int j = 0; j < LOCAL_SPEAKERS; j++) m[k++][j] = true;
        for (int j = 0; j < LOCAL_SPEAKERS; j++) {
            for (int n = j + 1; n < LOCAL_SPEAKERS; n++) {
                m[k][j] = true;
                m[k][n] = true;
                k++;
            }
        }
        return m;
    }

    /**
     * Decode window logits [window][frame][class] to activity
     * [window][frame][localSpeaker] by per-frame argmax through the
     * powerset table.
     */
    public static boolean[][][] decode(float[][][] logits) {
        boolean[][] map = powerset();
        boolean[][][] out = new boolean[logits.length][][];
        for (int w = 0; w < logits.length; w++) {
            out[w] = new boolean[logits[w].length][LOCAL_SPEAKERS];
            for (int f = 0; f < logits[w].length; f++) {
                int best = 0;
                float bestV = Float.NEGATIVE_INFINITY;
                for (int c = 0; c < NUM_CLASSES; c++) {
                    if (logits[w][f][c] > bestV) { bestV = logits[w][f][c]; best = c; }
                }
                for (int s = 0; s < LOCAL_SPEAKERS; s++) out[w][f][s] = map[best][s];
            }
        }
        return out;
    }

    /**
     * Consolidate window runs into speech EVENTS: the same stretch
     * of solo speech is seen by ~7 overlapping windows, and those
     * sightings are one event, embedded ONCE at full length.
     *
     * This step is what makes the prints trustworthy, measured on
     * the fixture both ways: embeddings of short window runs are
     * noisy enough that two genuinely different voices drift over
     * the cluster threshold and merge (3 voices collapsed to 2),
     * while turn-scale prints separate cleanly (same-voice cosine
     * floor 0.667 vs different-voice ceiling 0.279). Runs join an
     * event when they substantially overlap in time (≥ 0.4 s and
     * ≥ half the shorter run); the event's span is the MEDIAN
     * start/end of its sightings, so one bleeding sighting cannot
     * stretch it. An event needs at least two sightings, or one
     * sighting of 1.5 s+, to count — a lone flicker is not speech.
     */
    public static List<Observation> events(List<Observation> runs) {
        List<Observation> sorted = new ArrayList<>(runs);
        sorted.sort((a, b) -> Double.compare(
                a.spans.isEmpty() ? 0 : a.spans.get(0)[0],
                b.spans.isEmpty() ? 0 : b.spans.get(0)[0]));
        List<List<Observation>> groups = new ArrayList<>();
        for (Observation run : sorted) {
            if (run.spans.isEmpty()) continue;
            double rs = run.spans.get(0)[0], re = run.spans.get(0)[1];
            List<Observation> joined = null;
            for (List<Observation> g : groups) {
                double gs = Double.MAX_VALUE, ge = -Double.MAX_VALUE;
                for (Observation m : g) {
                    gs = Math.min(gs, m.spans.get(0)[0]);
                    ge = Math.max(ge, m.spans.get(0)[1]);
                }
                double ov = Math.min(re, ge) - Math.max(rs, gs);
                double shorter = Math.min(re - rs, ge - gs);
                if (ov >= 0.4 && shorter > 0 && ov >= 0.5 * shorter) {
                    joined = g;
                    break;
                }
            }
            if (joined == null) {
                joined = new ArrayList<>();
                groups.add(joined);
            }
            joined.add(run);
        }
        List<Observation> out = new ArrayList<>();
        for (List<Observation> g : groups) {
            List<Double> starts = new ArrayList<>(), ends = new ArrayList<>();
            for (Observation m : g) {
                starts.add(m.spans.get(0)[0]);
                ends.add(m.spans.get(0)[1]);
            }
            starts.sort(null);
            ends.sort(null);
            double s = starts.get(starts.size() / 2);
            double e = ends.get(ends.size() / 2);
            if (e <= s) continue;
            if (g.size() < 2 && e - s < 1.5) continue;
            Observation ev = new Observation();
            ev.window = -1;
            ev.localSpeaker = g.get(0).localSpeaker;
            ev.spans.add(new double[]{s, e});
            ev.activeSec = e - s;
            out.add(ev);
        }
        return out;
    }

    /** One (window, local speaker) voice observation. */
    public static final class Observation {
        public int window;
        public int localSpeaker;
        /** Solo-active spans, seconds [start, end), in title time. */
        public final List<double[]> spans = new ArrayList<>();
        public double activeSec = 0;
        /** Filled by the embedding seam; null until embedded. */
        public float[] embedding;
        /** Cluster index after clustering; -1 until then. */
        public int cluster = -1;
    }

    /**
     * Build observations from decoded activity: per window and local
     * speaker, EACH RUN of frames where that speaker is the ONLY
     * active one is its own observation (overlaps excluded — a
     * voiceprint should be one voice).
     *
     * The run granularity is load-bearing, measured on the fixture
     * (work/v120/diar): the segmentation model's local labels are
     * only per-window conveniences and can be REUSED for a different
     * person later in the same window (in fixture window 2, local
     * speaker 1 covers turns of both A and B). Aggregating a whole
     * window per label mixes voices into prints that cluster as
     * mush (same-voice pairs down to 0.29, different-voice pairs up
     * to 0.97); per-run prints stay pure, and clustering across runs
     * is what gives a voice its global identity — the reference
     * pipeline's own shape. Flicker gaps of up to 3 frames inside a
     * run merge; a run needs MIN_OBS_FRAMES solo frames to count.
     */
    public static List<Observation> observations(boolean[][][] act) {
        List<Observation> out = new ArrayList<>();
        for (int w = 0; w < act.length; w++) {
            int frames = act[w].length;
            for (int s = 0; s < LOCAL_SPEAKERS; s++) {
                int runStart = -1;
                int gap = 0;
                int lastSolo = -1;
                for (int f = 0; f < frames; f++) {
                    boolean solo = act[w][f][s];
                    if (solo) {
                        for (int o = 0; o < LOCAL_SPEAKERS; o++) {
                            if (o != s && act[w][f][o]) { solo = false; break; }
                        }
                    }
                    if (solo) {
                        if (runStart < 0) runStart = f;
                        lastSolo = f;
                        gap = 0;
                    } else if (runStart >= 0) {
                        if (++gap > 3) {
                            emitRun(out, w, s, runStart, lastSolo);
                            runStart = -1;
                            gap = 0;
                        }
                    }
                }
                if (runStart >= 0) emitRun(out, w, s, runStart, lastSolo);
            }
        }
        return out;
    }

    private static void emitRun(List<Observation> out, int window, int speaker,
                                int f0, int f1) {
        if (f1 - f0 + 1 < MIN_OBS_FRAMES) return;
        Observation ob = new Observation();
        ob.window = window;
        ob.localSpeaker = speaker;
        double half = (FRAME_SHIFT_SAMPLES / 2.0) / SAMPLE_RATE;
        double a = frameCenterSec(window, f0) - half;
        double b = frameCenterSec(window, f1) + half;
        ob.spans.add(new double[]{a, b});
        ob.activeSec = b - a;
        out.add(ob);
    }

    /** A cluster of observations: one voice, as far as the
     *  embeddings can tell. Centroid is the duration-weighted mean
     *  of member embeddings, L2-normalized. */
    public static final class Cluster {
        public final List<Observation> members = new ArrayList<>();
        public float[] centroid;
        public double totalActiveSec = 0;
    }

    /** A cluster needs at least this much attributed solo speech
     *  to count as a voice. Below the floor it is a fragment —
     *  window-edge slivers and decode flicker — never a cast
     *  member (Delwin's law: one-line background voices may not
     *  stabilize, and fragments must not pretend to). */
    public static final double MIN_CLUSTER_SEC = 1.2;
    public static final int MIN_CLUSTER_RUNS = 1;
    /** Centroid-merge margin under the cluster threshold: fragment
     *  clusters of the SAME voice hover just under the join
     *  threshold (measured 0.43–0.51 on the narrator control),
     *  while the fixture's genuinely different voices sit at
     *  0.17–0.24. */
    public static final double MERGE_MARGIN = 0.08;
    /** A cluster with at least this much attributed speech is a
     *  VOICE: fragments may be absorbed into a voice, but two
     *  voices never merge into each other. The first merge design
     *  let absorbed fragments drag a voice's centroid until two
     *  real voices chained together (fixture: 3 voices collapsed
     *  to 2); absorption is judged once, against the voices' own
     *  pass-1 centroids, and never chains. */
    public static final double VOICE_SEC = 4.0;

    /**
     * Clustering, three deterministic passes:
     *   1. greedy — longest observations first (their prints are
     *      the most reliable); an observation joins the cluster
     *      whose centroid it matches best at or above
     *      {@code threshold}, otherwise it founds a new cluster;
     *   2. absorption — a non-voice cluster whose centroid matches
     *      a VOICE cluster's pass-1 centroid at or above
     *      threshold − MERGE_MARGIN folds into that voice (a
     *      voice's fragments reunite; voices never merge);
     *   3. evidence floor — clusters under MIN_CLUSTER_SEC /
     *      MIN_CLUSTER_RUNS dissolve; their observations witness
     *      nothing and lines they alone covered stay honestly
     *      unattributed.
     */
    public static List<Cluster> cluster(List<Observation> obs, double threshold) {
        List<Observation> sorted = new ArrayList<>(obs);
        sorted.sort((a, b) -> Double.compare(b.activeSec, a.activeSec));
        List<Cluster> clusters = new ArrayList<>();
        for (Observation ob : sorted) {
            if (ob.embedding == null) continue;
            Cluster best = null;
            double bestScore = -1;
            for (Cluster c : clusters) {
                double s = VoiceBook.cosine(ob.embedding, c.centroid);
                if (s > bestScore) { bestScore = s; best = c; }
            }
            if (best != null && bestScore >= threshold) {
                best.members.add(ob);
                refold(best);
            } else {
                Cluster c = new Cluster();
                c.members.add(ob);
                c.centroid = normalized(ob.embedding.clone());
                clusters.add(c);
            }
        }
        for (Cluster c : clusters) {
            c.totalActiveSec = 0;
            for (Observation ob : c.members) c.totalActiveSec += ob.activeSec;
        }
        // pass 2: fragment absorption into voices, judged once
        List<Cluster> voices = new ArrayList<>();
        for (Cluster c : clusters) {
            if (c.totalActiveSec >= VOICE_SEC) voices.add(c);
        }
        if (!voices.isEmpty()) {
            List<Cluster> rest = new ArrayList<>();
            for (Cluster c : clusters) {
                if (voices.contains(c)) { rest.add(c); continue; }
                Cluster best = null;
                double bestScore = threshold - MERGE_MARGIN;
                for (Cluster v : voices) {
                    double s = VoiceBook.cosine(c.centroid, v.centroid);
                    if (s >= bestScore) { bestScore = s; best = v; }
                }
                if (best != null) {
                    best.members.addAll(c.members);
                    best.totalActiveSec += c.totalActiveSec;
                } else {
                    rest.add(c);
                }
            }
            for (Cluster v : voices) refold(v);
            clusters = rest;
        }
        // pass 3: the evidence floor
        List<Cluster> kept = new ArrayList<>();
        for (Cluster c : clusters) {
            c.totalActiveSec = 0;
            for (Observation ob : c.members) c.totalActiveSec += ob.activeSec;
            if (c.totalActiveSec >= MIN_CLUSTER_SEC
                    && c.members.size() >= MIN_CLUSTER_RUNS) {
                kept.add(c);
            }
        }
        for (int i = 0; i < kept.size(); i++) {
            for (Observation ob : kept.get(i).members) ob.cluster = i;
        }
        return kept;
    }

    private static void refold(Cluster c) {
        int dim = c.centroid.length;
        double[] acc = new double[dim];
        double wsum = 0;
        for (Observation ob : c.members) {
            double w = Math.max(0.05, ob.activeSec);
            for (int i = 0; i < dim; i++) acc[i] += w * ob.embedding[i];
            wsum += w;
        }
        float[] next = new float[dim];
        for (int i = 0; i < dim; i++) next[i] = (float) (acc[i] / wsum);
        c.centroid = normalized(next);
    }

    public static float[] normalized(float[] v) {
        double n = 0;
        for (float f : v) n += f * (double) f;
        n = Math.sqrt(n);
        if (n <= 0) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    /**
     * Which cluster speaks this line: the cluster whose observations
     * hold the most solo-active time inside [startSec, endSec].
     * Returns the cluster index, or -1 when no cluster clears the
     * dominance bar (LINE_MIN_SEC and LINE_MIN_SHARE) — an unknown
     * line is an honest line; it gets no invented speaker.
     */
    public static int assignLine(List<Cluster> clusters, double startSec, double endSec) {
        if (clusters == null || clusters.isEmpty() || endSec <= startSec) return -1;
        double[] per = new double[clusters.size()];
        double total = 0;
        for (int ci = 0; ci < clusters.size(); ci++) {
            double sum = 0;
            for (Observation ob : clusters.get(ci).members) {
                for (double[] sp : ob.spans) {
                    double a = Math.max(sp[0], startSec);
                    double b = Math.min(sp[1], endSec);
                    if (b > a) sum += b - a;
                }
            }
            per[ci] = sum;
            total += sum;
        }
        int best = -1;
        double bestV = 0;
        for (int ci = 0; ci < per.length; ci++) {
            if (per[ci] > bestV) { bestV = per[ci]; best = ci; }
        }
        if (best < 0 || bestV < LINE_MIN_SEC) return -1;
        if (total > 0 && bestV / total < LINE_MIN_SHARE) return -1;
        return best;
    }

    /**
     * Gather an observation's audio for embedding: its solo spans,
     * longest first, up to OBS_AUDIO_CAP_SEC total, concatenated.
     * The reader supplies samples by (startSec, durSec).
     */
    public interface SliceReader {
        float[] read(double startSec, double durSec) throws Exception;
    }

    public static float[] observationAudio(Observation ob, SliceReader reader)
            throws Exception {
        List<double[]> spans = new ArrayList<>(ob.spans);
        spans.sort((a, b) -> Double.compare(b[1] - b[0], a[1] - a[0]));
        List<float[]> parts = new ArrayList<>();
        double budget = OBS_AUDIO_CAP_SEC;
        int total = 0;
        for (double[] sp : spans) {
            if (budget <= 0) break;
            double len = Math.min(sp[1] - sp[0], budget);
            float[] pcm = reader.read(sp[0], len);
            if (pcm != null && pcm.length > 0) {
                parts.add(pcm);
                total += pcm.length;
                budget -= pcm.length / (double) SAMPLE_RATE;
            }
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
