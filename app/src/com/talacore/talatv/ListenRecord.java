package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * LISTEN FOR ME's record (v0.15.0) — one heard utterance, kept
 * honestly: what state the ear ended in, whose ear produced the
 * words, and the FILES that carry it (the sound and the transcript
 * together on the memory hook — file in, file out).
 *
 * The states are the door's honest surface, never polished:
 *   LISTENING      the ear is open right now
 *   HEARD          words came back from a recognizer
 *   UNSURE         the ear answered but can't stand behind it
 *                  (no match, a timeout, a whisper pass that
 *                  found nothing it trusts)
 *   NO_RECOGNIZER  this phone has no ear for that language —
 *                  said plainly, before anyone talks at it
 *
 * The transcript's source is part of the record, because the two
 * ears are different machinery and the difference must not hide:
 *   SYSTEM_EAR     the phone's speech-recognition service (may
 *                  use the network — the service's own reality)
 *   SANDY_WHISPER  Sandy's own on-device whisper, from an
 *                  installed engine pack, over the saved sound
 *   NONE           no words at all (an unsure/silent take)
 *
 * Pure: file bodies and the index line are built and parsed here
 * so the harness pins the round-trip; ListenStore does the IO.
 */
public final class ListenRecord {

    public enum State { LISTENING, HEARD, UNSURE, NO_RECOGNIZER }

    public enum Source { SYSTEM_EAR, SANDY_WHISPER, NONE }

    // SpeechRecognizer error codes (android.speech.SpeechRecognizer),
    // copied as literals so this class stays Android-free; the door
    // passes the raw int through stateForError.
    private static final int ERR_NETWORK_TIMEOUT = 1;
    private static final int ERR_NETWORK = 2;
    private static final int ERR_AUDIO = 3;
    private static final int ERR_SERVER = 4;
    private static final int ERR_CLIENT = 5;
    private static final int ERR_SPEECH_TIMEOUT = 6;
    private static final int ERR_NO_MATCH = 7;
    private static final int ERR_RECOGNIZER_BUSY = 8;
    private static final int ERR_INSUFFICIENT_PERMISSIONS = 9;
    private static final int ERR_LANGUAGE_NOT_SUPPORTED = 12;
    private static final int ERR_LANGUAGE_UNAVAILABLE = 13;

    /**
     * Map a recognizer error to the honest state. A miss or a
     * timeout is UNSURE — the ear was there, the words weren't.
     * A language the service doesn't carry, or the service itself
     * failing, is NO_RECOGNIZER — there was no ear to hear with.
     */
    public static State stateForError(int error) {
        switch (error) {
            case ERR_NO_MATCH:
            case ERR_SPEECH_TIMEOUT:
                return State.UNSURE;
            case ERR_LANGUAGE_NOT_SUPPORTED:
            case ERR_LANGUAGE_UNAVAILABLE:
            case ERR_NETWORK:
            case ERR_NETWORK_TIMEOUT:
            case ERR_SERVER:
            case ERR_CLIENT:
            case ERR_RECOGNIZER_BUSY:
            case ERR_INSUFFICIENT_PERMISSIONS:
            case ERR_AUDIO:
            default:
                return State.NO_RECOGNIZER;
        }
    }

    /** One kept utterance. */
    public static final class Record {
        public String id = "";          // e.g. 20261019-143022
        public String iso1 = "";        // the language listened in
        public State state = State.UNSURE;
        public Source source = Source.NONE;
        public String text = "";        // the words ("" when none)
        public String audioName = "";   // wav file name ("" = no sound kept)
        public long createdMs = 0;

        public String transcriptName() {
            return id.isEmpty() ? "" : "listen_" + id + ".txt";
        }

        public String audioFileName() {
            return id.isEmpty() ? "" : "listen_" + id + ".wav";
        }
    }

    private ListenRecord() {}

    /** The transcript file body: a small header, then the words. */
    public static String transcriptBody(Record r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy — Listen For Me record\n");
        sb.append("id: ").append(r.id).append('\n');
        sb.append("language: ").append(r.iso1).append('\n');
        sb.append("state: ").append(r.state.name()).append('\n');
        sb.append("source: ").append(r.source.name()).append('\n');
        sb.append("audio: ").append(r.audioName).append('\n');
        sb.append("---\n");
        sb.append(r.text == null ? "" : r.text).append('\n');
        return sb.toString();
    }

    /** Parse a transcript file body back into a record. */
    public static Record parseTranscript(String body) {
        Record r = new Record();
        if (body == null) return r;
        StringBuilder words = new StringBuilder();
        boolean inWords = false;
        for (String line : body.split("\n", -1)) {
            if (inWords) {
                if (words.length() > 0) words.append('\n');
                words.append(line);
                continue;
            }
            if (line.equals("---")) { inWords = true; continue; }
            int c = line.indexOf(':');
            if (c < 0) continue;
            String k = line.substring(0, c).trim();
            String v = line.substring(c + 1).trim();
            try {
                if (k.equals("id")) r.id = v;
                else if (k.equals("language")) r.iso1 = v;
                else if (k.equals("state")) r.state = State.valueOf(v);
                else if (k.equals("source")) r.source = Source.valueOf(v);
                else if (k.equals("audio")) r.audioName = v;
            } catch (Exception ignored) {}
        }
        r.text = words.toString().trim();
        return r;
    }

    /** The index line (listen_index.tsv): one row per kept record. */
    public static String indexLine(Record r) {
        return r.id + "\t" + r.iso1 + "\t" + r.state.name() + "\t"
                + r.source.name() + "\t" + r.audioName + "\t"
                + r.createdMs + "\n";
    }

    /** Parse the whole index back into records (order preserved). */
    public static List<Record> parseIndex(String body) {
        List<Record> out = new ArrayList<>();
        if (body == null) return out;
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] p = t.split("\t", -1);
            if (p.length < 5) continue;
            Record r = new Record();
            r.id = p[0];
            r.iso1 = p[1];
            try { r.state = State.valueOf(p[2]); } catch (Exception ignored) {}
            try { r.source = Source.valueOf(p[3]); } catch (Exception ignored) {}
            r.audioName = p[4];
            if (p.length > 5) {
                try { r.createdMs = Long.parseLong(p[5]); } catch (Exception ignored) {}
            }
            out.add(r);
        }
        return out;
    }

    /**
     * A complete 16-bit PCM WAV file (RIFF) over the given samples —
     * the sound half of a record. Pure bytes; ListenStore writes
     * them, the harness pins the header.
     */
    public static byte[] toWav(short[] pcm, int sampleRate) {
        if (pcm == null) pcm = new short[0];
        int dataBytes = pcm.length * 2;
        byte[] out = new byte[44 + dataBytes];
        putAscii(out, 0, "RIFF");
        putIntLE(out, 4, 36 + dataBytes);
        putAscii(out, 8, "WAVE");
        putAscii(out, 12, "fmt ");
        putIntLE(out, 16, 16);
        putShortLE(out, 20, (short) 1);          // PCM
        putShortLE(out, 22, (short) 1);          // mono
        putIntLE(out, 24, sampleRate);
        putIntLE(out, 28, sampleRate * 2);       // byte rate
        putShortLE(out, 32, (short) 2);          // block align
        putShortLE(out, 34, (short) 16);         // bits per sample
        putAscii(out, 36, "data");
        putIntLE(out, 40, dataBytes);
        for (int i = 0; i < pcm.length; i++) {
            putShortLE(out, 44 + i * 2, pcm[i]);
        }
        return out;
    }

    private static void putAscii(byte[] b, int off, String s) {
        for (int i = 0; i < s.length(); i++) b[off + i] = (byte) s.charAt(i);
    }

    private static void putIntLE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void putShortLE(byte[] b, int off, short v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }
}
