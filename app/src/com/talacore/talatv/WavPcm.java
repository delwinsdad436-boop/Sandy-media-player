package com.talacore.talatv;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAV (RIFF, 16-bit PCM) as a PcmSource — the desktop JVM proof's
 * audio path, and a perfectly good on-device path for WAV sources.
 * Channels are mixed to mono; a non-16 kHz rate is resampled by
 * linear interpolation (bench media is already 16 kHz mono, where
 * the resample is an identity).
 */
public final class WavPcm implements PcmSource {

    private final RandomAccessFile raf;
    private final long dataOff;
    private final int channels;
    private final int sampleRate;
    private final long frames;

    public WavPcm(File wav) throws Exception {
        raf = new RandomAccessFile(wav, "r");
        byte[] hdr = new byte[44];
        if (raf.length() < 44) throw new IllegalArgumentException("not a wav: " + wav);
        raf.readFully(hdr);
        if (hdr[0] != 'R' || hdr[1] != 'I' || hdr[2] != 'F' || hdr[3] != 'F'
                || hdr[8] != 'W' || hdr[9] != 'A' || hdr[10] != 'V' || hdr[11] != 'E') {
            throw new IllegalArgumentException("not a RIFF wav: " + wav);
        }
        ByteBuffer bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        int audioFmt = bb.getShort(20) & 0xffff;
        channels = bb.getShort(22) & 0xffff;
        sampleRate = bb.getInt(24);
        int bits = bb.getShort(34) & 0xffff;
        if (audioFmt != 1 || bits != 16) {
            throw new IllegalArgumentException("only PCM16 wav supported (fmt="
                    + audioFmt + ", bits=" + bits + ")");
        }
        // walk chunks to the data chunk (fmt may be extended)
        long off = 12;
        long dataAt = -1, dataLen = 0;
        byte[] ch = new byte[8];
        while (off + 8 <= raf.length()) {
            raf.seek(off);
            raf.readFully(ch);
            long sz = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                    .getInt(4) & 0xffffffffL;
            if (ch[0] == 'd' && ch[1] == 'a' && ch[2] == 't' && ch[3] == 'a') {
                dataAt = off + 8;
                dataLen = sz;
                break;
            }
            off += 8 + sz + (sz & 1);
        }
        if (dataAt < 0) throw new IllegalArgumentException("no data chunk: " + wav);
        dataOff = dataAt;
        frames = dataLen / (2L * channels);
    }

    @Override
    public double durationSec() {
        return frames / (double) sampleRate;
    }

    @Override
    public float[] read(double fromSec, double durSec) throws Exception {
        int outN = (int) Math.round(durSec * 16000.0);
        float[] out = new float[outN];
        // source-frame window covering the requested span
        double srcFrom = fromSec * sampleRate;
        double srcDur = durSec * sampleRate * (16000.0 / 16000.0);
        long f0 = Math.max(0, (long) Math.floor(srcFrom));
        long f1 = Math.min(frames, (long) Math.ceil(srcFrom + srcDur) + 1);
        if (f1 <= f0) return out;
        int nf = (int) (f1 - f0);
        byte[] raw = new byte[nf * 2 * channels];
        raf.seek(dataOff + f0 * 2L * channels);
        int got = 0;
        while (got < raw.length) {
            int r = raf.read(raw, got, raw.length - got);
            if (r < 0) break;
            got += r;
        }
        nf = got / (2 * channels);
        float[] mono = new float[nf];
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < nf; i++) {
            double acc = 0;
            for (int c = 0; c < channels; c++) {
                acc += bb.getShort((i * channels + c) * 2) / 32768.0;
            }
            mono[i] = (float) (acc / channels);
        }
        if (sampleRate == 16000) {
            int off = (int) Math.round((fromSec * 16000.0) - f0);
            for (int i = 0; i < outN; i++) {
                int idx = off + i;
                out[i] = (idx >= 0 && idx < mono.length) ? mono[idx] : 0f;
            }
        } else {
            double ratio = sampleRate / 16000.0;
            double base = fromSec * 16000.0 - f0 / ratio;
            for (int i = 0; i < outN; i++) {
                double pos = (base + i) * ratio;
                int i0 = (int) Math.floor(pos);
                double frac = pos - i0;
                float a = (i0 >= 0 && i0 < mono.length) ? mono[i0] : 0f;
                float b = (i0 + 1 >= 0 && i0 + 1 < mono.length) ? mono[i0 + 1] : 0f;
                out[i] = (float) (a + (b - a) * frac);
            }
        }
        return out;
    }

    public void close() {
        try { raf.close(); } catch (Exception ignored) {}
    }
}
