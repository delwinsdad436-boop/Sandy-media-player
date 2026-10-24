package com.talacore.talatv;

/**
 * The Translate sheet's decision table (v0.6.1) — pure logic, no
 * Android, so the JVM harness can pin EVERY row.
 *
 * Why this class exists: in 0.6.0 the sheet's only row was
 * "Cook a new translation…" and its tap disappeared into a single
 * fragile output surface; on James's phone (live channel, no
 * sidecars) the tap produced NO visible state at all — a silent
 * dead end, field-reported and screenshot-confirmed. The gates
 * themselves (Cooker.begin) were always correct; what failed was
 * that the decision lived inside UI code with exactly one place
 * to speak and no obligation to be heard.
 *
 * The rule from 0.6.1 on: the decision is made HERE, as data, and
 * every outcome is a SPOKEN state — the sheet carries it as its
 * message, the status bar repeats it, and a toast repeats it
 * again. There is no outcome named "nothing". The outcomes, in
 * precedence order:
 *
 *   EXISTING        a prepared dub / cooked sidecar is already here
 *   STREAM_HAS_LANG the stream itself already carries a text track
 *                   in the viewer's language (say so — don't offer
 *                   to cook what the viewer already has)
 *   NEEDS_PACK      no engine pack installed (shelf directions)
 *   PACK_UNBOUND    a pack is in, but this build cannot run it
 *                   (a CTranslate2 pack — the ONNX engine binds only
 *                   ONNX packs; resource honesty, UTX Law 5)
 *   ENGINE_FAILED   an ONNX pack is in, but the runtime would not
 *                   start on this device (reason carried in detail)
 *   LIVE_EDGE       engine bound + LIVE playback: live cannot cook
 *                   from the edge (Delwin's record-then-dub model) —
 *                   the door is ● Record; the action starts recording
 *   RECORDING_NEXT  engine bound + a recording playing back that
 *                   is still being made (v0.7.0 rewording: the
 *                   blocker is no longer "not built" — it is
 *                   "not finished catching yet; stop it first")
 *   (a SETTLED recording — stopped or finished, bytes caught —
 *   falls through to COOK: RecordingExport assembles its cached
 *   spans into one openable file and the cooker cooks from it)
 *   STREAM_COOK     engine bound + a network title: the on-phone
 *                   cooker cooks files on this phone; network titles
 *                   cook on the shelf, once, for every Sandy
 *   COOK            engine bound + a local file: cook it here, now
 */
public final class TranslateGate {

    public enum Outcome {
        EXISTING, STREAM_HAS_LANG, NEEDS_PACK, PACK_UNBOUND,
        ENGINE_FAILED, LIVE_EDGE, RECORDING_NEXT, STREAM_COOK, COOK
    }

    public static final class Decision {
        public final Outcome outcome;
        /** Extra fact for outcomes that carry one (engine failure reason). */
        public final String detail;
        Decision(Outcome o, String d) { outcome = o; detail = d; }
        /** True when the sheet's cook row becomes the ● Record door. */
        public boolean recordDoor() { return outcome == Outcome.LIVE_EDGE; }
        /** True when tapping the cook row should start a real cook. */
        public boolean startsCook() { return outcome == Outcome.COOK; }
    }

    private TranslateGate() {}

    /**
     * @param hasExisting   prepared dub or cooked sidecar attached
     * @param streamHasLang the playing stream itself carries a text
     *                      track in the viewer's language
     * @param packInstalled an engine pack is installed (any format)
     * @param packRunnable  the installed pack is an ONNX pack (the
     *                      only format this build's engine binds)
     * @param engineBound   the ONNX runtime started and the pack's
     *                      sessions can open on this device
     * @param engineFailWhy why the engine did not start ("" if n/a)
     * @param live          live playback (not a file / recording)
     * @param recording     playing back a recording (cache-backed)
     * @param recSettled    the recording is stopped/finished with
     *                      bytes caught (v0.7.0: only then can its
     *                      spans be assembled into a cookable file)
     * @param localSource   the title is a local file the audio
     *                      decoder can open (file path / content URI)
     */
    public static Decision decide(boolean hasExisting, boolean streamHasLang,
            boolean packInstalled, boolean packRunnable, boolean engineBound,
            String engineFailWhy, boolean live, boolean recording,
            boolean recSettled, boolean localSource) {
        if (hasExisting) return new Decision(Outcome.EXISTING, "");
        if (streamHasLang) return new Decision(Outcome.STREAM_HAS_LANG, "");
        if (!packInstalled) return new Decision(Outcome.NEEDS_PACK, "");
        if (!packRunnable) return new Decision(Outcome.PACK_UNBOUND, "");
        if (!engineBound) return new Decision(Outcome.ENGINE_FAILED,
                engineFailWhy == null ? "" : engineFailWhy);
        if (live) return new Decision(Outcome.LIVE_EDGE, "");
        if (recording && !recSettled) return new Decision(Outcome.RECORDING_NEXT, "");
        if (recording) return new Decision(Outcome.COOK, "");
        if (!localSource) return new Decision(Outcome.STREAM_COOK, "");
        return new Decision(Outcome.COOK, "");
    }
}
