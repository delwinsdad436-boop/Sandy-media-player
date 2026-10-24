package com.talacore.talatv;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The voice organ for the Two Doors (v0.15.0) — speaks a
 * SpeakQueue aloud through the established voice ladder, the same
 * machinery the Reader proved (ReaderService v0.13.0):
 *
 *   PACK    an installed Sandy voice pack synthesizes on-device
 *           (OnnxVoiceSynth → AudioTrack);
 *   SYSTEM  the phone's own speech engine, its language set
 *           probed live (never assumed);
 *   NONE    decided BEFORE speaking by SpeakQueue.decide — the
 *           caller refuses in plain words; this class is never
 *           asked to fake a voice.
 *
 * One utterance at a time, in queue order; the listener hears
 * each item START (the on-screen receipt follows the voice) and
 * the drain at the end. A pack failure mid-queue is disclosed
 * through onPackFailed and the rest of the queue hands over to
 * the phone's voice — the Reader's own fallback rule.
 *
 * Main-thread confined, like the doors that own it.
 */
public final class VoiceSpeaker {

    public interface Listener {
        /** An item started speaking — show it as the receipt. */
        void onItemStart(SpeakQueue.Item item);
        /** The queue drained (or was stopped empty). */
        void onQueueDone();
        /** The pack voice failed; the phone's voice takes over. */
        void onPackFailed(String packCode);
        /** A spoken item failed outright (engine error). */
        void onItemFailed(SpeakQueue.Item item);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean ttsFailed = false;

    private SpeakQueue queue;
    private volatile int gen = 0;

    private OnnxVoiceSynth packSynth;
    private String packSynthCode = "";
    private AudioTrack packTrack;
    private String currentPackCode = "";

    public VoiceSpeaker(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) { this.listener = l; }

    private final Set<String> probedIso1 = new HashSet<>();

    /** The phone engine's language set (ISO-1 tags), probed live
     *  at warm-up the VoiceCoverage way (isLanguageAvailable per
     *  voice-table row). Live truth for the ladder — empty until
     *  the engine answers; never assumed. */
    public Set<String> systemIso1() {
        return new HashSet<>(probedIso1);
    }

    private void probeLanguages() {
        if (tts == null) return;
        try {
            for (VoiceLangs.Row r : VoiceLangs.load(ctx)) {
                if (r.iso1.isEmpty()) continue;
                int a = tts.isLanguageAvailable(Locale.forLanguageTag(r.iso1));
                if (a >= TextToSpeech.LANG_AVAILABLE) {
                    probedIso1.add(r.iso1);
                }
            }
        } catch (Throwable ignored) {}
    }

    public boolean engineReady() { return ttsReady; }
    public boolean engineFailed() { return ttsFailed; }

    /** Start the phone engine early so probing is real when the
     *  door makes its coverage promises. cb runs on the main
     *  thread when the engine has answered (either way). */
    public void warmUp(Runnable cb) {
        if (tts != null) { if (cb != null) cb.run(); return; }
        try {
            tts = new TextToSpeech(ctx, status -> main.post(() -> {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    probeLanguages();
                    tts.setOnUtteranceProgressListener(
                            new UtteranceProgressListener() {
                                @Override public void onStart(String id) {}
                                @Override public void onDone(String id) {
                                    main.post(VoiceSpeaker.this::next);
                                }
                                @Override public void onError(String id) {
                                    main.post(() -> {
                                        SpeakQueue.Item it = queue == null
                                                ? null : queue.advance();
                                        if (listener != null && it != null) {
                                            listener.onItemFailed(it);
                                        }
                                        next();
                                    });
                                }
                            });
                } else {
                    ttsFailed = true;
                }
                if (cb != null) cb.run();
            }));
        } catch (Throwable t) {
            ttsFailed = true;
            if (cb != null) cb.run();
        }
    }

    /** Speak the queue from its current position. The caller has
     *  already run the ladder per item language (NONE refused
     *  before enqueue); here each item resolves against the LIVE
     *  sets again at speak time, and an item whose voice vanished
     *  is failed honestly, never substituted. */
    public void speak(SpeakQueue q) {
        queue = q;
        gen++;
        next();
    }

    public void stop() {
        gen++;
        if (tts != null) { try { tts.stop(); } catch (Throwable ignored) {} }
        stopPackTrack();
    }

    public void shutdown() {
        stop();
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
        }
        closePackSynth();
    }

    // ------------------------------------------------ flow

    private void next() {
        if (queue == null) return;
        SpeakQueue.Item item = queue.peek();
        if (item == null) {
            if (listener != null) listener.onQueueDone();
            return;
        }
        VoiceLangs.Row row = VoiceLangs.byIso1(VoiceLangs.load(ctx), item.iso1);
        VoiceResolve.Result r = decidePlus(row);
        if (listener != null) listener.onItemStart(item);
        if (r.voice == VoiceResolve.Voice.PACK) {
            speakPack(item, r.packCode);
        } else if (r.voice == VoiceResolve.Voice.SYSTEM) {
            speakSystem(item, r.iso1);
        } else {
            // Vanished between promise and speech — fail it in
            // the open and move on; never a wrong-language voice.
            queue.advance();
            if (listener != null) listener.onItemFailed(item);
            next();
        }
    }

    private void speakSystem(SpeakQueue.Item item, String iso1) {
        if (tts == null || !ttsReady) {
            queue.advance();
            if (listener != null) listener.onItemFailed(item);
            next();
            return;
        }
        try {
            Locale loc = Locale.forLanguageTag(iso1);
            int res = tts.setLanguage(loc);
            if (res == TextToSpeech.LANG_MISSING_DATA
                    || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                queue.advance();
                if (listener != null) listener.onItemFailed(item);
                next();
                return;
            }
        } catch (Throwable ignored) {}
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int res = tts.speak(item.text, TextToSpeech.QUEUE_FLUSH, params,
                "vs" + System.nanoTime());
        if (res == TextToSpeech.ERROR) {
            queue.advance();
            if (listener != null) listener.onItemFailed(item);
            next();
        }
        // completion arrives via UtteranceProgressListener.onDone
    }

    /** The pack road — ReaderService's proven shape: synthesize
     *  on a worker, play through a static AudioTrack, completion
     *  by play-head. Failure → disclose, hand the queue to the
     *  phone's voice (which means: forget the pack code and let
     *  the next resolution run — this item is re-decided). */
    private void speakPack(SpeakQueue.Item item, String code) {
        currentPackCode = code;
        final int g = gen;
        new Thread(() -> {
            try {
                if (packSynth == null || !code.equals(packSynthCode)) {
                    closePackSynth();
                    packSynth = OnnxVoiceSynth.open(
                            VoicePackStore.packDir(ctx, code));
                    packSynthCode = code;
                }
                float[] pcm = packSynth.synth(item.text, 1.0);
                if (g != gen) return;
                if (pcm == null || pcm.length == 0) {
                    throw new Exception("the pack said nothing");
                }
                short[] s16 = new short[pcm.length];
                for (int i = 0; i < pcm.length; i++) {
                    float v = Math.max(-1f, Math.min(1f, pcm[i]));
                    s16[i] = (short) Math.round(v * 32767f);
                }
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(OnnxVoiceSynth.SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(s16.length * 2,
                                AudioTrack.getMinBufferSize(
                                        OnnxVoiceSynth.SAMPLE_RATE,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT)))
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(s16, 0, s16.length);
                if (g != gen) { track.release(); return; }
                stopPackTrack();
                packTrack = track;
                track.play();
                while (g == gen && track.getPlayState()
                        == AudioTrack.PLAYSTATE_PLAYING) {
                    if (track.getPlaybackHeadPosition() >= s16.length) break;
                    try { Thread.sleep(90); } catch (InterruptedException e) {
                        return;
                    }
                }
                boolean finished = g == gen
                        && track.getPlaybackHeadPosition() >= s16.length;
                stopPackTrack();
                if (finished) {
                    main.post(() -> {
                        if (g == gen) {
                            queue.advance();
                            next();
                        }
                    });
                }
            } catch (Exception e) {
                if (g != gen) return;
                main.post(() -> {
                    if (g != gen) return;
                    // disclose, then re-decide this item with the
                    // pack forgotten — the phone's voice takes it,
                    // or NONE fails it honestly in next().
                    if (listener != null) listener.onPackFailed(code);
                    forgetPack(code);
                    next();
                });
            }
        }, "voice-speaker-pack").start();
    }

    /** After a pack failure, resolution must not pick it again:
     *  close the synth and remember — implemented by clearing the
     *  cached synth; the caller (door) is told via onPackFailed
     *  and decides whether to continue. To keep the ladder inside
     *  this class honest, a failed code is blacklisted for the
     *  life of this speaker. */
    private final java.util.Set<String> failedPacks = new HashSet<>();

    private void forgetPack(String code) {
        failedPacks.add(code);
        closePackSynth();
    }

    private void closePackSynth() {
        if (packSynth != null) {
            try { packSynth.close(); } catch (Throwable ignored) {}
            packSynth = null;
            packSynthCode = "";
        }
    }

    private void stopPackTrack() {
        if (packTrack != null) {
            try { packTrack.pause(); packTrack.flush(); packTrack.release(); }
            catch (Throwable ignored) {}
            packTrack = null;
        }
    }

    /** Ladder with the failed-pack blacklist applied — used by
     *  next() through decidePlus(). */
    VoiceResolve.Result decidePlus(VoiceLangs.Row row) {
        Set<String> packs = new HashSet<>(VoicePackStore.installedCodes(ctx));
        packs.removeAll(failedPacks);
        return SpeakQueue.decide(row, packs, systemIso1());
    }
}
