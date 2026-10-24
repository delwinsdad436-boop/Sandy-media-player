package com.talacore.talatv;

import java.util.Set;

/**
 * Voice resolution (v0.13.0) — given a catalogue language, which
 * voice speaks it on THIS phone? The order is the disclosure law
 * made code:
 *
 *   PACK    an installed Sandy voice pack for the language
 *           (VoicePackStore) — the voice that covers them all,
 *           present because somebody installed its file;
 *   SYSTEM  the phone's own speech engine reports a voice for
 *           the language (probed live, never assumed);
 *   NONE    neither — said plainly BEFORE any cook, never a
 *           silent wrong-language or single-voice substitute.
 *
 * Pure: the installed-pack set and the system-availability set
 * are inputs (sets of pack codes / ISO-1 tags), so the harness
 * drives the whole table on the desktop.
 */
public final class VoiceResolve {

    public enum Voice { PACK, SYSTEM, NONE }

    public static final class Result {
        public Voice voice = Voice.NONE;
        /** The pack code when voice == PACK. */
        public String packCode = "";
        /** The ISO-1 tag when voice == SYSTEM. */
        public String iso1 = "";
    }

    private VoiceResolve() {}

    public static Result resolve(VoiceLangs.Row row,
                                 Set<String> installedPackCodes,
                                 Set<String> systemIso1) {
        Result r = new Result();
        if (row == null) return r;
        if (!row.packCode.isEmpty() && installedPackCodes != null
                && installedPackCodes.contains(row.packCode)) {
            r.voice = Voice.PACK;
            r.packCode = row.packCode;
            return r;
        }
        if (!row.iso1.isEmpty() && systemIso1 != null
                && systemIso1.contains(row.iso1)) {
            r.voice = Voice.SYSTEM;
            r.iso1 = row.iso1;
            return r;
        }
        return r;
    }
}
