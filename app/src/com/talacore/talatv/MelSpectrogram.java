package com.talacore.talatv;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Log-mel features for whisper, mirroring transformers'
 * WhisperFeatureExtractor numpy path step for step (the lab recipe's
 * feature math — the recognizer was benched through it, so the
 * bridge reproduces it rather than approximating it):
 *
 *   reflect-pad the waveform by n_fft/2 on both sides (np.pad
 *   "reflect": mirror, edge sample not repeated);
 *   frames of 400 samples, hop 160, periodic Hann window;
 *   400-point DFT (Bluestein — 400 is not a power of two), power |X|²;
 *   mel = max(1e-10, filtersᵀ · power), filters loaded from the
 *   engine pack (the exact matrix the feature extractor uses,
 *   exported at pack-build time — never re-derived here);
 *   log10; drop the last frame; clamp to (global max − 8);
 *   (x + 4) / 4.
 *
 * All math in double, like numpy's float64 STFT path. Input is one
 * window of audio: exactly 30 s (480000 samples at 16 kHz), shorter
 * tails zero-padded by the caller the way the extractor pads them.
 * Output: float[80][3000], the encoder's input_features layout.
 */
public final class MelSpectrogram {

    public static final int N_FFT = 400;
    public static final int HOP = 160;
    public static final int N_MELS = 80;
    public static final int N_BINS = N_FFT / 2 + 1;      // 201
    public static final int FRAMES = 3000;               // 30 s window
    public static final int SAMPLES = 480000;

    /** filters[freqBin][mel] — the layout of transformers' mel_filters. */
    private final float[][] filters;

    public MelSpectrogram(File melFiltersBin) throws Exception {
        byte[] raw;
        try (FileInputStream in = new FileInputStream(melFiltersBin)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            raw = bos.toByteArray();
        }
        if (raw.length != N_BINS * N_MELS * 4) {
            throw new IllegalStateException("mel_filters.bin is " + raw.length
                    + " bytes, expected " + (N_BINS * N_MELS * 4));
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        filters = new float[N_BINS][N_MELS];
        for (int f = 0; f < N_BINS; f++) {
            for (int m = 0; m < N_MELS; m++) filters[f][m] = bb.getFloat();
        }
    }

    /** Periodic Hann window, n = 0..399: 0.5 − 0.5·cos(2πn/400). */
    private static double[] hann() {
        double[] w = new double[N_FFT];
        for (int n = 0; n < N_FFT; n++) {
            w[n] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * n / N_FFT);
        }
        return w;
    }

    /** np.pad(x, 200, mode="reflect") — mirror without repeating edges. */
    private static double[] reflectPad(float[] x, int pad) {
        int n = x.length;
        double[] out = new double[n + 2 * pad];
        for (int i = 0; i < out.length; i++) {
            int j = i - pad;
            while (j < 0 || j >= n) {
                if (j < 0) j = -j;
                if (j >= n) j = 2 * (n - 1) - j;
            }
            out[i] = x[j];
        }
        return out;
    }

    /**
     * The window's log-mel features.
     * @param pcm16k mono 16 kHz samples in [-1, 1]; length ≤ 480000
     *               (zero-padded to a full window here).
     * @return float[80][3000]
     */
    public float[][] features(float[] pcm16k) {
        float[] x = new float[SAMPLES];
        System.arraycopy(pcm16k, 0, x, 0, Math.min(pcm16k.length, SAMPLES));
        double[] padded = reflectPad(x, N_FFT / 2);
        double[] win = hann();
        int numFrames = 1 + (padded.length - N_FFT) / HOP;   // 3001
        double[][] power = new double[numFrames][N_BINS];
        double[] frame = new double[N_FFT];
        double[] re = new double[N_FFT];
        double[] im = new double[N_FFT];
        for (int f = 0; f < numFrames; f++) {
            int off = f * HOP;
            for (int n = 0; n < N_FFT; n++) {
                frame[n] = padded[off + n] * win[n];
            }
            dft400(frame, re, im);
            for (int b = 0; b < N_BINS; b++) {
                power[f][b] = re[b] * re[b] + im[b] * im[b];
            }
        }
        // mel + log10, frames 0..2999 (the last of 3001 is dropped,
        // exactly like the extractor's [:, :-1])
        float[][] out = new float[N_MELS][FRAMES];
        double max = Double.NEGATIVE_INFINITY;
        double[][] log = new double[N_MELS][FRAMES];
        for (int f = 0; f < FRAMES; f++) {
            for (int m = 0; m < N_MELS; m++) {
                double s = 0;
                for (int b = 0; b < N_BINS; b++) s += filters[b][m] * power[f][b];
                if (s < 1e-10) s = 1e-10;
                double v = Math.log10(s);
                log[m][f] = v;
                if (v > max) max = v;
            }
        }
        double floor = max - 8.0;
        for (int m = 0; m < N_MELS; m++) {
            for (int f = 0; f < FRAMES; f++) {
                out[m][f] = (float) ((Math.max(log[m][f], floor) + 4.0) / 4.0);
            }
        }
        return out;
    }

    // ------------------------------------------------- DFT of size 400

    /**
     * Real-input DFT of length 400 via Bluestein's chirp-z reduction
     * to a 1024-point radix-2 FFT. Exact (not an approximation of the
     * 400-point transform — a zero-padded 512 FFT would compute the
     * WRONG bins). Double precision throughout, like numpy.
     */
    private static void dft400(double[] in, double[] outRe, double[] outIm) {
        final int n = N_FFT;
        final int m = 1024; // next pow2 >= 2n-1
        double[] aRe = new double[m], aIm = new double[m];
        double[] bRe = new double[m], bIm = new double[m];
        for (int i = 0; i < n; i++) {
            double ang = -Math.PI * i * i / n;
            double c = Math.cos(ang), s = Math.sin(ang);
            aRe[i] = in[i] * c;
            aIm[i] = in[i] * s;
            double angB = Math.PI * i * i / n;
            bRe[i] = Math.cos(angB);
            bIm[i] = Math.sin(angB);
            if (i > 0) {
                bRe[m - i] = bRe[i];
                bIm[m - i] = bIm[i];
            }
        }
        fft(aRe, aIm, false);
        fft(bRe, bIm, false);
        for (int i = 0; i < m; i++) {
            double r = aRe[i] * bRe[i] - aIm[i] * bIm[i];
            double j = aRe[i] * bIm[i] + aIm[i] * bRe[i];
            aRe[i] = r;
            aIm[i] = j;
        }
        fft(aRe, aIm, true);
        for (int k = 0; k < N_BINS; k++) {
            double ang = -Math.PI * k * k / n;
            double c = Math.cos(ang), s = Math.sin(ang);
            outRe[k] = aRe[k] * c - aIm[k] * s;
            outIm[k] = aRe[k] * s + aIm[k] * c;
        }
    }

    /** Iterative radix-2 FFT; inverse scales by 1/n. Length must be 2^k. */
    private static void fft(double[] re, double[] im, boolean inverse) {
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
            double ang = 2.0 * Math.PI / len * (inverse ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cwr = 1, cwi = 0;
                for (int k = 0; k < len / 2; k++) {
                    int u = i + k, v = i + k + len / 2;
                    double vr = re[v] * cwr - im[v] * cwi;
                    double vi = re[v] * cwi + im[v] * cwr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    double nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
            }
        }
        if (inverse) {
            for (int i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
        }
    }
}
