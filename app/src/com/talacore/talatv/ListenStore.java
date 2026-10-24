package com.talacore.talatv;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Listen For Me's shelf work (v0.15.0) — the RECORD road's IO:
 * while the ear listens, the sound is captured alongside it
 * (AudioRecord, 16 kHz mono — whisper's own rate), and a kept
 * utterance lands on the memory hook as a TRIO: the WAV, the
 * transcript file, and one line in listen_index.tsv. Sound and
 * words together, always — ListenRecord builds the bodies, this
 * class writes them.
 *
 * The whisper pass: when the listened language is the source of
 * an installed ONNX engine pair (today Japanese in ja-en), the
 * saved sound is ALSO transcribed by Sandy's own on-device
 * whisper (OnnxCookerEngine — built for cooked files, run here
 * over the short saved WAV through WavPcm), and its words —
 * carried on the cooked lines' orig text — win the transcript,
 * labeled SANDY_WHISPER. Otherwise the transcript is the system
 * ear's final text (SYSTEM_EAR), or the record stands UNSURE
 * with no words. Nothing is ever polished into certainty.
 */
public final class ListenStore {

    public static final String INDEX = "listen_index.tsv";
    public static final int SAMPLE_RATE = 16000;

    private ListenStore() {}

    /** A fresh record id from the clock: 20261019-143022. */
    public static String newId() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
    }

    /**
     * Keep a record: audio (when pcm != null) + transcript +
     * index line, written together. Returns the record with its
     * file names filled in, or null when the write failed.
     */
    public static ListenRecord.Record save(Context ctx,
                                           ListenRecord.Record r,
                                           short[] pcm) {
        try {
            File root = MemoryHook.root(ctx);
            if (pcm != null && pcm.length > 0) {
                File wav = new File(root, r.audioFileName());
                try (FileOutputStream out = new FileOutputStream(wav)) {
                    out.write(ListenRecord.toWav(pcm, SAMPLE_RATE));
                }
                r.audioName = r.audioFileName();
            } else {
                r.audioName = "";
            }
            MemoryHook.writeFile(new File(root, r.transcriptName()),
                    ListenRecord.transcriptBody(r));
            File idx = new File(root, INDEX);
            String prev = idx.exists() ? MemoryHook.readFile(idx) : "";
            if (prev == null) prev = "";
            if (prev.isEmpty()) {
                prev = "# Sandy — Listen For Me index"
                        + " (id, language, state, source, audio, when)\n";
            }
            StringBuilder sb = new StringBuilder(prev);
            if (!prev.endsWith("\n")) sb.append('\n');
            sb.append(ListenRecord.indexLine(r));
            MemoryHook.writeFile(idx, sb.toString());
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** The kept records, oldest first (from the index file). */
    public static List<ListenRecord.Record> records(Context ctx) {
        try {
            File idx = MemoryHook.file(ctx, INDEX);
            if (!idx.exists()) return new ArrayList<>();
            String body = MemoryHook.readFile(idx);
            return ListenRecord.parseIndex(body == null ? "" : body);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Run Sandy's whisper over captured samples. pair is the
     * installed engine pair whose source language was heard
     * (e.g. "ja-en"). The samples are fed through a PcmSource, so
     * the whisper hears exactly the sound the record keeps.
     * Returns the heard words (the cooked lines' original text,
     * joined), or "" when the whisper found nothing it trusts,
     * or null when the pass couldn't run at all.
     */
    public static String transcribeWithWhisper(Context ctx, final short[] pcm,
                                               String pair) {
        OnnxCookerEngine engine = null;
        try {
            EnginePackStore.Status st = EnginePackStore.status(ctx, pair);
            if (st == null || !st.onnx() || st.dir == null) return null;
            engine = OnnxCookerEngine.open(st.dir);
            PcmSource src = new PcmSource() {
                @Override public double durationSec() {
                    return pcm == null ? 0 : pcm.length / (double) SAMPLE_RATE;
                }
                @Override public float[] read(double fromSec, double durSec) {
                    if (pcm == null) return new float[0];
                    int from = (int) Math.round(fromSec * SAMPLE_RATE);
                    int count = (int) Math.round(durSec * SAMPLE_RATE);
                    if (from < 0) from = 0;
                    if (from >= pcm.length) return new float[0];
                    int n = Math.min(count, pcm.length - from);
                    float[] out = new float[n];
                    for (int i = 0; i < n; i++) out[i] = pcm[from + i] / 32768f;
                    return out;
                }
            };
            List<CookedSubs.Line> lines = engine.cook(src, null);
            StringBuilder sb = new StringBuilder();
            if (lines != null) {
                for (CookedSubs.Line ln : lines) {
                    String t = ln.orig == null ? "" : ln.orig.trim();
                    if (!t.isEmpty()) {
                        if (sb.length() > 0) sb.append(' ');
                        sb.append(t);
                    }
                }
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (engine != null) {
                try { engine.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ------------------------------------------------ mic capture

    /**
     * The parallel sound capture for the RECORD road: records
     * 16 kHz mono PCM while the recognizer listens, so the kept
     * record holds the actual sound of the utterance. Bounded at
     * 5 minutes — an utterance, not a wiretap.
     */
    public static final class WavRecorder {
        private static final int MAX_SAMPLES = SAMPLE_RATE * 60 * 5;
        private AudioRecord rec;
        private Thread thread;
        private volatile boolean running = false;
        private final List<short[]> chunks = new ArrayList<>();
        private int total = 0;

        public boolean start() {
            try {
                int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) return false;
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(min, SAMPLE_RATE));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    rec.release();
                    rec = null;
                    return false;
                }
                chunks.clear();
                total = 0;
                running = true;
                rec.startRecording();
                thread = new Thread(() -> {
                    short[] buf = new short[4096];
                    while (running) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n > 0) {
                            synchronized (chunks) {
                                if (total < MAX_SAMPLES) {
                                    short[] c = new short[n];
                                    System.arraycopy(buf, 0, c, 0, n);
                                    chunks.add(c);
                                    total += n;
                                }
                            }
                        }
                    }
                }, "listen-wav");
                thread.start();
                return true;
            } catch (Throwable t) {
                running = false;
                return false;
            }
        }

        /** Stop and hand back the captured samples (may be empty). */
        public short[] stop() {
            running = false;
            if (thread != null) {
                try { thread.join(600); } catch (InterruptedException ignored) {}
                thread = null;
            }
            if (rec != null) {
                try { rec.stop(); } catch (Throwable ignored) {}
                try { rec.release(); } catch (Throwable ignored) {}
                rec = null;
            }
            synchronized (chunks) {
                short[] out = new short[total];
                int off = 0;
                for (short[] c : chunks) {
                    System.arraycopy(c, 0, out, off, c.length);
                    off += c.length;
                }
                chunks.clear();
                total = 0;
                return out;
            }
        }
    }
}
