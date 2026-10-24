package com.talacore.talatv;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The voiced leg (v0.6.0) — a REAL on-device renderer, no seam:
 * Android's own speech engine speaks the cooked subtitle lines into
 * a .wav that rides beside the video like a prepared dub.
 *
 * Per line: the voice comes from the show's voice book (Delwin's
 * casting: this character = this voice, this pitch, this rate). A
 * line whose natural reading overruns its window is re-spoken faster
 * (up to 2x, two tries) — window fitting, the same contract the
 * desktop lab proved, done with the tools the phone actually has.
 * A line that still overruns is placed anyway and counted in the
 * report — the renderer never hides a misfit.
 *
 * What it is NOT: a diarizer. Lines carry speaker keys only when a
 * cooked record provides them; with no book and no speakers, every
 * line is one voice (the NOTES say so plainly).
 *
 * Runs on its own thread; progress reports COOKED SECONDS (the end
 * of the last finished line) so the cooker state machine can ride it.
 */
public final class TtsDubRenderer {

    public static final class Report {
        public int lines = 0;
        public int refits = 0;
        public int overran = 0;
        public int formatSkips = 0;
        public double spokenSec = 0;
        /** Per-line fit disclosure, aligned to the input lines
         *  (v0.12.0): 0 natural pace, 1 re-spoken faster to fit,
         *  2 still overran its window — the same numbers
         *  CookedSubs.Line.fit records, so a squeezed line is
         *  disclosed in the saved record, never hidden. */
        public int[] lineFit = new int[0];
    }

    public interface Callback {
        void onProgress(double cookedSec);
        void onDone(File wav, Report report);
        void onError(String why);
    }

    private final Context ctx;
    private volatile boolean cancelled = false;

    public TtsDubRenderer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void cancel() { cancelled = true; }

    /** Voice names the phone's speech engine offers (for casting). */
    public static void availableVoiceNames(Context ctx,
                                           java.util.function.Consumer<List<String>> cb) {
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(ctx.getApplicationContext(), status -> {
            List<String> names = new ArrayList<>();
            if (status == TextToSpeech.SUCCESS) {
                try {
                    for (Voice v : holder[0].getVoices()) names.add(v.getName());
                } catch (Exception ignored) {}
            }
            try { holder[0].shutdown(); } catch (Exception ignored) {}
            cb.accept(names);
        });
    }

    public void render(List<CookedSubs.Line> lines, VoiceBook.Book book,
                       String langCode, File outWav, Callback cb) {
        new Thread(() -> doRender(lines, book, langCode, outWav, cb),
                "tts-dub-render").start();
    }

    /**
     * The pack leg (v0.13.0): the same render, spoken by an
     * installed Sandy voice pack (OnnxVoiceSynth) instead of the
     * phone's speech engine. Casting rides the book exactly as on
     * the system leg — rate through the model's length scale,
     * pitch through a PCM resample — and the fit loop closes on
     * MEASURED PCM, the same contract: a line is re-spoken faster
     * (up to 2x, two tries), a line that still overruns is placed
     * anyway and counted, never hidden.
     */
    public void renderPack(List<CookedSubs.Line> lines, VoiceBook.Book book,
                           String packCode, File outWav, Callback cb) {
        new Thread(() -> doRenderPack(lines, book, packCode, outWav, cb),
                "pack-dub-render").start();
    }

    private void doRenderPack(List<CookedSubs.Line> lines, VoiceBook.Book book,
                              String packCode, File outWav, Callback cb) {
        Report report = new Report();
        report.lines = lines.size();
        report.lineFit = new int[lines.size()];
        VoicePackStore.Installed inst =
                VoicePackStore.installed(ctx).get(packCode);
        if (inst == null) {
            cb.onError("the voice pack is not installed");
            return;
        }
        try (OnnxVoiceSynth synth = OnnxVoiceSynth.open(inst.dir)) {
            ByteArrayOutputStream track = new ByteArrayOutputStream();
            int sampleRate = OnnxVoiceSynth.SAMPLE_RATE, channels = 1;
            int byteRate = sampleRate * channels * 2;
            long cursorMs = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (cancelled) { cb.onError("stopped"); return; }
                CookedSubs.Line line = lines.get(i);
                if (line.text == null || line.text.trim().isEmpty()) {
                    cb.onProgress(line.endMs / 1000.0);
                    continue;
                }
                VoiceBook.CastEntry cast = castFor(book, line, i);
                double windowSec = Math.max(0.4, (line.endMs - line.startMs) / 1000.0);
                byte[] pcm = null;
                double rateMult = 1.0;
                int fit = 0;
                for (int attempt = 0; attempt < 3; attempt++) {
                    double castRate = cast != null ? cast.rate : 1.0;
                    double lengthScale = Math.max(0.5, Math.min(2.0,
                            castRate * rateMult));
                    float[] spoken = synth.synth(line.text, lengthScale);
                    if (cast != null) {
                        spoken = OnnxVoiceSynth.pitchShift(spoken, cast.pitch);
                    }
                    if (spoken.length == 0) { pcm = null; break; }
                    pcm = toPcm16(spoken);
                    double natural = (double) pcm.length / byteRate;
                    if (natural <= windowSec * 1.02 || attempt == 2) {
                        if (natural > windowSec * 1.02) { report.overran++; fit = 2; }
                        else if (attempt > 0) fit = 1;
                        break;
                    }
                    report.refits++;
                    rateMult = Math.min(2.0, rateMult * (natural / windowSec));
                }
                if (pcm != null) {
                    report.lineFit[i] = fit;
                    long gapMs = line.startMs - cursorMs;
                    if (gapMs > 0) {
                        int gapBytes = (int) (byteRate * gapMs / 1000);
                        track.write(new byte[gapBytes], 0, gapBytes);
                        cursorMs += gapMs;
                    }
                    track.write(pcm, 0, pcm.length);
                    cursorMs = line.startMs + (long) (pcm.length * 1000.0 / byteRate);
                    report.spokenSec = cursorMs / 1000.0;
                }
                cb.onProgress(line.endMs / 1000.0);
            }
            if (report.spokenSec == 0 && track.size() == 0) {
                cb.onError("the voice pack produced no usable audio");
                return;
            }
            writeWav(outWav, track.toByteArray(), sampleRate, channels);
            cb.onDone(outWav, report);
        } catch (Exception e) {
            cb.onError("voice pack rendering failed (" + e.getMessage() + ")");
        }
    }

    private static byte[] toPcm16(float[] in) {
        byte[] out = new byte[in.length * 2];
        for (int i = 0; i < in.length; i++) {
            float v = Math.max(-1f, Math.min(1f, in[i]));
            int s = Math.round(v * 32767f);
            out[i * 2] = (byte) s;
            out[i * 2 + 1] = (byte) (s >> 8);
        }
        return out;
    }

    // ------------------------------------------------------------ core

    private void doRender(List<CookedSubs.Line> lines, VoiceBook.Book book,
                          String langCode, File outWav, Callback cb) {
        Report report = new Report();
        report.lines = lines.size();
        report.lineFit = new int[lines.size()];
        final TextToSpeech[] holder = new TextToSpeech[1];
        final CountDownLatch initLatch = new CountDownLatch(1);
        final boolean[] initOk = {false};
        holder[0] = new TextToSpeech(ctx, status -> {
            initOk[0] = status == TextToSpeech.SUCCESS;
            initLatch.countDown();
        });
        try {
            if (!initLatch.await(15, TimeUnit.SECONDS) || !initOk[0]) {
                cb.onError("the phone's speech engine did not start");
                return;
            }
            TextToSpeech tts = holder[0];
            Locale loc = langCode == null || langCode.isEmpty()
                    ? Locale.getDefault() : Locale.forLanguageTag(langCode);
            tts.setLanguage(loc);

            Map<String, CountDownLatch> latches = new HashMap<>();
            Map<String, Boolean> errors = new HashMap<>();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) {
                    CountDownLatch l = latches.get(id);
                    if (l != null) l.countDown();
                }
                @Override public void onError(String id) {
                    errors.put(id, Boolean.TRUE);
                    CountDownLatch l = latches.get(id);
                    if (l != null) l.countDown();
                }
            });

            List<Voice> voices = new ArrayList<>();
            try { voices.addAll(tts.getVoices()); } catch (Exception ignored) {}

            File workDir = new File(ctx.getCacheDir(), "tts-dub");
            EnginePackStore.deleteTree(workDir);
            workDir.mkdirs();

            ByteArrayOutputStream track = new ByteArrayOutputStream();
            int sampleRate = 0, channels = 0, byteRate = 0;
            long cursorMs = 0;

            for (int i = 0; i < lines.size(); i++) {
                if (cancelled) { cb.onError("stopped"); shutdown(tts); return; }
                CookedSubs.Line line = lines.get(i);
                if (line.text == null || line.text.trim().isEmpty()) {
                    cb.onProgress(line.endMs / 1000.0);
                    continue;
                }
                VoiceBook.CastEntry cast = castFor(book, line, i);
                applyCast(tts, cast, voices, book == null ? 0 : book.cast.indexOf(cast));
                double windowSec = Math.max(0.4, (line.endMs - line.startMs) / 1000.0);
                byte[] pcm = null;
                int[] fmt = null;
                double rateMult = 1.0;
                int fit = 0;
                for (int attempt = 0; attempt < 3; attempt++) {
                    File uf = new File(workDir, "u" + i + "_" + attempt + ".wav");
                    String id = "u" + i + "_" + attempt;
                    CountDownLatch latch = new CountDownLatch(1);
                    latches.put(id, latch);
                    tts.setSpeechRate((float) Math.max(0.5,
                            Math.min(2.0, (cast != null ? cast.rate : 1.0) * rateMult)));
                    Bundle params = new Bundle();
                    int r = tts.synthesizeToFile(line.text, params, uf, id);
                    if (r != TextToSpeech.SUCCESS
                            || !latch.await(60, TimeUnit.SECONDS)
                            || errors.containsKey(id) || !uf.isFile()) {
                        pcm = null;
                        break;
                    }
                    WavData w = readWav(uf);
                    if (w == null) { pcm = null; break; }
                    fmt = new int[]{w.sampleRate, w.channels, w.byteRate};
                    pcm = w.pcm;
                    double natural = w.byteRate > 0
                            ? (double) w.pcm.length / w.byteRate : windowSec;
                    if (natural <= windowSec * 1.02 || attempt == 2) {
                        if (natural > windowSec * 1.02) { report.overran++; fit = 2; }
                        else if (attempt > 0) fit = 1;
                        break;
                    }
                    report.refits++;
                    rateMult = Math.min(2.0, rateMult * (natural / windowSec));
                }
                if (pcm != null) report.lineFit[i] = fit;
                if (pcm != null && fmt != null) {
                    if (sampleRate == 0) {
                        sampleRate = fmt[0]; channels = fmt[1]; byteRate = fmt[2];
                    }
                    if (fmt[0] == sampleRate && fmt[1] == channels && fmt[2] == byteRate) {
                        long gapMs = line.startMs - cursorMs;
                        if (gapMs > 0) {
                            int gapBytes = (int) (byteRate * gapMs / 1000);
                            track.write(new byte[gapBytes], 0, gapBytes);
                            cursorMs += gapMs;
                        }
                        track.write(pcm, 0, pcm.length);
                        cursorMs = line.startMs + (long) (pcm.length * 1000.0 / byteRate);
                        report.spokenSec = cursorMs / 1000.0;
                    } else {
                        report.formatSkips++;
                    }
                }
                cb.onProgress(line.endMs / 1000.0);
            }

            if (sampleRate == 0) {
                cb.onError("the speech engine produced no usable audio");
                shutdown(tts);
                return;
            }
            writeWav(outWav, track.toByteArray(), sampleRate, channels);
            shutdown(tts);
            cb.onDone(outWav, report);
        } catch (Exception e) {
            try { shutdown(holder[0]); } catch (Exception ignored) {}
            cb.onError("voice rendering failed (" + e.getMessage() + ")");
        }
    }

    private void shutdown(TextToSpeech tts) {
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) {} }
    }

    private VoiceBook.CastEntry castFor(VoiceBook.Book book, CookedSubs.Line line,
                                        int lineIndex) {
        if (book == null || book.cast.isEmpty()) return null;
        if (line.speaker != null && !line.speaker.isEmpty()) {
            for (VoiceBook.CastEntry e : book.cast) {
                if (line.speaker.equals(e.speakerKey)) return e;
            }
        }
        return book.cast.get(0);
    }

    private void applyCast(TextToSpeech tts, VoiceBook.CastEntry cast,
                           List<Voice> voices, int castIndex) {
        if (cast == null) {
            tts.setPitch(1.0f);
            return;
        }
        tts.setPitch((float) cast.pitch);
        Voice chosen = null;
        if (cast.ttsVoice != null && !cast.ttsVoice.isEmpty()) {
            for (Voice v : voices) {
                if (cast.ttsVoice.equals(v.getName())) { chosen = v; break; }
            }
        }
        if (chosen == null && castIndex > 0 && !voices.isEmpty()) {
            // Unassigned extra cast members get a deterministic
            // different voice rather than the lead's.
            chosen = voices.get(Math.abs(castIndex) % voices.size());
        }
        if (chosen != null) {
            try { tts.setVoice(chosen); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ WAV io

    private static final class WavData {
        int sampleRate, channels, byteRate;
        byte[] pcm;
    }

    private static WavData readWav(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] all = readAllBytes(in);
            if (all.length < 44 || all[0] != 'R' || all[1] != 'I') return null;
            WavData w = new WavData();
            int pos = 12;
            while (pos + 8 <= all.length) {
                String id = new String(all, pos, 4, "ASCII");
                long size = u32(all, pos + 4);
                if ("fmt ".equals(id)) {
                    w.channels = u16(all, pos + 10);
                    w.sampleRate = (int) u32(all, pos + 12);
                    w.byteRate = (int) u32(all, pos + 16);
                } else if ("data".equals(id)) {
                    int len = (int) Math.min(size, all.length - (pos + 8));
                    w.pcm = new byte[len];
                    System.arraycopy(all, pos + 8, w.pcm, 0, len);
                    return w.sampleRate > 0 ? w : null;
                }
                pos += 8 + size + (size % 2);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeWav(File out, byte[] pcm, int sampleRate, int channels)
            throws Exception {
        int bits = 16;
        int byteRate = sampleRate * channels * bits / 8;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write("RIFF".getBytes("ASCII"));
            fos.write(le32(36 + pcm.length));
            fos.write("WAVE".getBytes("ASCII"));
            fos.write("fmt ".getBytes("ASCII"));
            fos.write(le32(16));
            fos.write(le16(1));
            fos.write(le16(channels));
            fos.write(le32(sampleRate));
            fos.write(le32(byteRate));
            fos.write(le16(channels * bits / 8));
            fos.write(le16(bits));
            fos.write("data".getBytes("ASCII"));
            fos.write(le32(pcm.length));
            fos.write(pcm);
        }
    }

    private static byte[] readAllBytes(FileInputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8)
                | ((b[off + 2] & 0xffL) << 16) | ((b[off + 3] & 0xffL) << 24);
    }

    private static byte[] le16(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }
}
