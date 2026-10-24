package com.talacore.talatv;

import java.util.List;
import java.util.Set;

/**
 * The Two Doors' coverage truth (v0.15.0) — every promise a door
 * surface makes is DERIVED here from the audited machinery, never
 * hard-coded at the surface (the Display Honesty Law):
 *
 *   VOICE  VoiceResolve over the live sets: installed Sandy voice
 *          packs × the phone speech engine's probed languages.
 *   EAR    the phone's speech-recognition service exists or it
 *          doesn't. Per-language support is the SERVICE's truth,
 *          knowable only when a session runs — so coverage says
 *          TRY (an ear exists) or NO_RECOGNIZER, and the LISTENING
 *          session's own outcome refines it on screen. Sandy's own
 *          on-device whisper exists only inside an installed
 *          engine pack and only for that pack's pair language
 *          (today: Japanese, in the ja-en pack) — it backs the
 *          RECORD road, and only there.
 *   MT     TranslatorCore routes over installed engine pairs.
 *
 * Pure: the Android doors probe the live sets and pass them in;
 * the harness pins the derivation from fixtures.
 */
public final class DoorCoverage {

    private DoorCoverage() {}

    /** What one language can do across the doors. */
    public static final class LangCoverage {
        /** PACK / SYSTEM / NONE — Speak's ladder answer. */
        public VoiceResolve.Voice voice = VoiceResolve.Voice.NONE;
        /** An ear exists on this phone to try (system service). */
        public boolean earTry;
        /** Sandy's own whisper can transcribe this language from
         *  a saved recording (installed pack pair source). */
        public boolean whisper;
    }

    public static LangCoverage forRow(VoiceLangs.Row row,
                                      Set<String> installedPackCodes,
                                      Set<String> systemTtsIso1,
                                      boolean recognizerPresent,
                                      Set<String> installedMtPairs) {
        LangCoverage c = new LangCoverage();
        if (row == null) return c;
        c.voice = SpeakQueue.decide(row, installedPackCodes, systemTtsIso1).voice;
        c.earTry = recognizerPresent;
        c.whisper = whisperCovers(row.iso1, installedMtPairs);
        return c;
    }

    /**
     * Sandy's whisper lives in engine packs, bound to the pack
     * pair's SOURCE language (the ja-en pack hears Japanese —
     * OnnxCookerEngine's language token is the pair's source).
     * A language is whisper-covered exactly when an installed
     * ONNX pair starts with it.
     */
    public static boolean whisperCovers(String iso1, Set<String> installedMtPairs) {
        if (iso1 == null || iso1.isEmpty() || installedMtPairs == null) return false;
        String want = iso1.trim().toLowerCase() + "-";
        for (String p : installedMtPairs) {
            if (p != null && p.startsWith(want)) return true;
        }
        return false;
    }

    /** The Translator's route for a pair of sides — same truth
     *  the door refuses or proceeds by. */
    public static TranslatorCore.Route route(String fromIso, String toIso,
                                             Set<String> installedMtPairs) {
        return TranslatorCore.resolveRoute(fromIso, toIso, installedMtPairs);
    }

    /** Languages (voice-table rows) that have ANY door coverage —
     *  used to keep pickers honest: a language with no voice, no
     *  ear, and no route is still listed (the table is the
     *  catalogue's truth) but its state line says so. */
    public static boolean anyCoverage(LangCoverage c) {
        return c != null && (c.voice != VoiceResolve.Voice.NONE || c.earTry);
    }

    /** The row for an ISO-1 tag from the voice table. */
    public static VoiceLangs.Row rowFor(List<VoiceLangs.Row> rows, String iso1) {
        return VoiceLangs.byIso1(rows, iso1);
    }
}
