package com.talacore.talatv;

/**
 * Kaldi-style 80-bin log filterbank features (v0.12.0) — the front
 * end the 3D-Speaker speaker-embedding model eats, reproducing the
 * recipe sherpa-onnx's speaker embedding extractor implements
 * (Kaldi / kaldi-native-fbank), step for step:
 *
 *   samples scaled to the int16 range (the embedding model's own
 *   metadata says normalize_samples=1 — the extractor works on
 *   Kaldi-scale waveforms);
 *   frames of 400 samples (25 ms) every 160 (10 ms), snip-edges;
 *   per frame: DC offset removed, pre-emphasis 0.97, Povey window
 *   (the Hann window raised to 0.85 — Kaldi's default), zero-padded
 *   to a 512 FFT, power spectrum;
 *   80 triangular mel filters between 20 Hz and Nyquist, spaced on
 *   the Kaldi mel scale (1127 ln(1 + f/700)), energy floored at
 *   float epsilon, natural log;
 *   and the model's declared feature normalization: the per-bin
 *   mean over the segment's frames subtracted (global-mean), which
 *   is what the 3D-Speaker export was trained against.
 *
 * Pure math, no Android and no ONNX: the desktop harness compares
 * embeddings computed through these features against the reference
 * extractor's own, number for number (work/v120/diar).
 */
public final class FbankFeatures {

    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME_LEN = 400;
    public static final int FRAME_SHIFT = 160;
    public static final int NUM_BINS = 80;
    public static final double PREEMPH = 0.97;
    public static final double LOW_FREQ = 20.0;
    /** Kaldi's default top: Nyquist − 400 Hz. Measured load-bearing
     *  (work/v120/diar/sweep_fbank.py): at 8000 the embeddings sit at
     *  cosine ~0.83 to the reference extractor's; at 7600, ~0.99. */
    public static final double HIGH_FREQ = 7600.0;
    public static final double LOG_EPS = 1.1920929e-7;   // float epsilon, Kaldi's floor

    private FbankFeatures() {}

    private static double mel(double freq) {
        return 1127.0 * Math.log(1.0 + freq / 700.0);
    }

    /** Mel filter weights [bin][fftBin] over the 257 positive bins
     *  of a 512 FFT — Kaldi's triangular construction. */
    public static float[][] melFilters() {
        int nFftBins = 257;
        double fftBinWidth = (SAMPLE_RATE / 2.0) / (nFftBins - 1); // 31.25 Hz
        double melLow = mel(LOW_FREQ), melHigh = mel(HIGH_FREQ);
        double melStep = (melHigh - melLow) / (NUM_BINS + 1);
        float[][] w = new float[NUM_BINS][nFftBins];
        for (int b = 0; b < NUM_BINS; b++) {
            double left = melLow + b * melStep;
            double center = melLow + (b + 1) * melStep;
            double right = melLow + (b + 2) * melStep;
            for (int i = 0; i < nFftBins; i++) {
                double m = mel(i * fftBinWidth);
                if (m > left && m < center) {
                    w[b][i] = (float) ((m - left) / (center - left));
                } else if (m >= center && m < right) {
                    w[b][i] = (float) ((right - m) / (right - center));
                }
            }
        }
        return w;
    }

    /** Number of frames snip-edges framing yields for n samples. */
    public static int frameCount(int numSamples) {
        if (numSamples < FRAME_LEN) return 0;
        return 1 + (numSamples - FRAME_LEN) / FRAME_SHIFT;
    }

    /**
     * Features for one audio segment: float[frames][80], log
     * filterbank energies with the global-mean normalization the
     * embedding model declares. Input samples are floats in
     * -1..1 (the PcmSource convention); they are scaled to the
     * Kaldi range inside.
     */
    public static float[][] compute(float[] samples) {
        int n = samples == null ? 0 : samples.length;
        int frames = frameCount(n);
        float[][] out = new float[frames][NUM_BINS];
        if (frames == 0) return out;
        float[][] filters = melFilters();
        double[] window = new double[FRAME_LEN];
        for (int i = 0; i < FRAME_LEN; i++) {
            double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME_LEN - 1));
            window[i] = Math.pow(hann, 0.85);   // Povey
        }
        double[] frame = new double[FRAME_LEN];
        double[] re = new double[512];
        double[] im = new double[512];
        for (int f = 0; f < frames; f++) {
            int off = f * FRAME_SHIFT;
            double mean = 0;
            for (int i = 0; i < FRAME_LEN; i++) {
                frame[i] = samples[off + i] * 32768.0;
                mean += frame[i];
            }
            mean /= FRAME_LEN;
            for (int i = 0; i < FRAME_LEN; i++) frame[i] -= mean;
            // pre-emphasis, Kaldi's order (backwards, first sample scaled)
            for (int i = FRAME_LEN - 1; i > 0; i--) frame[i] -= PREEMPH * frame[i - 1];
            frame[0] *= (1.0 - PREEMPH);
            for (int i = 0; i < FRAME_LEN; i++) frame[i] *= window[i];
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            System.arraycopy(frame, 0, re, 0, FRAME_LEN);
            fft(re, im);
            for (int b = 0; b < NUM_BINS; b++) {
                double e = 0;
                float[] w = filters[b];
                for (int i = 0; i < 257; i++) {
                    if (w[i] != 0) e += w[i] * (re[i] * re[i] + im[i] * im[i]);
                }
                if (e < LOG_EPS) e = LOG_EPS;
                out[f][b] = (float) Math.log(e);
            }
        }
        // global-mean normalization (the model's declared recipe)
        double[] mean = new double[NUM_BINS];
        for (int f = 0; f < frames; f++) {
            for (int b = 0; b < NUM_BINS; b++) mean[b] += out[f][b];
        }
        for (int b = 0; b < NUM_BINS; b++) mean[b] /= frames;
        for (int f = 0; f < frames; f++) {
            for (int b = 0; b < NUM_BINS; b++) out[f][b] -= (float) mean[b];
        }
        return out;
    }

    /** Iterative radix-2 FFT, in place; length must be a power of 2. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cwr = 1, cwi = 0;
                for (int k = 0; k < len / 2; k++) {
                    double ur = re[i + k], ui = im[i + k];
                    double vr = re[i + k + len / 2] * cwr - im[i + k + len / 2] * cwi;
                    double vi = re[i + k + len / 2] * cwi + im[i + k + len / 2] * cwr;
                    re[i + k] = ur + vr; im[i + k] = ui + vi;
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
                    double nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
            }
        }
    }
}
