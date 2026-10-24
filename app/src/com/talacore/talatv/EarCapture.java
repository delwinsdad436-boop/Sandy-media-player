package com.talacore.talatv;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * The ear organ for the Two Doors (v0.15.0) — a thin honest body
 * around the phone's speech-recognition service
 * (android.speech.SpeechRecognizer). Shared by Listen For Me and
 * the Translator so both hear through the same organ and report
 * the same states.
 *
 * What this organ IS, said plainly (and again in the NOTES): the
 * device's recognition SERVICE — on most phones a system service
 * that may send the audio over the network to do its work; that
 * is the service's reality, not Sandy's choice, and it is never
 * hidden. Which languages it carries is likewise the service's
 * truth, learned per session: presence is probed here, support
 * is what the session reports, mapped to ListenRecord.State by
 * the pure mapping (unsure is shown as unsure).
 *
 * Live partial results stream to the listener as they arrive —
 * that stream is Listen's "big immediate text". Sandy's own
 * on-device whisper exists only inside installed engine packs
 * and is NOT this organ; it serves the RECORD road in Listen
 * (ListenStore.transcribeWithWhisper), for the pack pair's
 * source language only.
 *
 * Main-thread confined.
 */
public final class EarCapture {

    public interface Listener {
        /** Live words, still forming. */
        void onPartial(String text);
        /** The take ended: final words ("" when none) + state. */
        void onFinal(String text, ListenRecord.State state);
        /** The ear opened and is hearing. */
        void onListening();
    }

    private final Context ctx;
    private SpeechRecognizer rec;
    private Listener listener;
    private boolean active = false;

    public EarCapture(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Does this phone have a recognition service at all? */
    public static boolean present(Context ctx) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isActive() { return active; }

    /**
     * Open the ear for one take in the given language (ISO-1).
     * False = no recognizer on this phone; the caller shows
     * NO_RECOGNIZER and never pretends to listen.
     */
    public boolean start(String iso1) {
        if (!present(ctx)) return false;
        stop();
        try {
            rec = SpeechRecognizer.createSpeechRecognizer(ctx);
        } catch (Throwable t) {
            return false;
        }
        final String lang = iso1 == null || iso1.isEmpty() ? "en" : iso1;
        rec.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) {
                if (listener != null) listener.onListening();
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float f) {}
            @Override public void onBufferReceived(byte[] b) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                active = false;
                if (listener != null) {
                    listener.onFinal("", ListenRecord.stateForError(error));
                }
            }
            @Override public void onResults(Bundle b) {
                active = false;
                String text = best(b);
                if (listener != null) {
                    listener.onFinal(text, text.isEmpty()
                            ? ListenRecord.State.UNSURE
                            : ListenRecord.State.HEARD);
                }
            }
            @Override public void onPartialResults(Bundle b) {
                String text = best(b);
                if (listener != null && !text.isEmpty()) {
                    listener.onPartial(text);
                }
            }
            @Override public void onEvent(int i, Bundle b) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                Locale.forLanguageTag(lang).toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            active = true;
            rec.startListening(intent);
            return true;
        } catch (Throwable t) {
            active = false;
            return false;
        }
    }

    private static String best(Bundle b) {
        if (b == null) return "";
        ArrayList<String> all =
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (all == null || all.isEmpty() || all.get(0) == null) return "";
        return all.get(0).trim();
    }

    public void stop() {
        if (rec != null) {
            try { rec.stopListening(); } catch (Throwable ignored) {}
            try { rec.destroy(); } catch (Throwable ignored) {}
            rec = null;
        }
        active = false;
    }
}
