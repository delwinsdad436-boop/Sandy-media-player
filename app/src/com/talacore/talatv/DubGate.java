package com.talacore.talatv;

import java.util.Set;

/**
 * The dub cook's decision table (v0.12.0) — pure logic in the
 * TranslateGate tradition: the plan is made HERE, as data, and
 * every outcome is a state the UI must speak. There is no outcome
 * named "nothing", and no outcome fakes an organ Sandy doesn't
 * have.
 *
 * James's pipeline, as ordered:
 *   SUBTITLE FIRST. A subtitle track the video already carries —
 *   in ANY known language — is harvested and moved by the
 *   translation matrix directly between written languages. For
 *   voice: translate FIRST into the language the viewer wants to
 *   hear, THEN read that text aloud (translate-then-speak).
 *   THE EAR IS THE FALLBACK. Only a video with no usable subtitle
 *   track is listened to (ASR), through the engine pack.
 *   CASTING. With the voice (diarization) pack in, the audio
 *   witnesses who speaks each line and the VoiceBook casts them —
 *   a saved voice per character, per show, per season. Without
 *   it, subtitle files that NAME their speakers (ASS/VTT) still
 *   cast by name; anything else is honestly one voice.
 *
 * The matrix routes ONE hop, directly: subtitle language -> the
 * viewer's language, through an installed pack pair. Pivot chains
 * (ja->en->es through two MT hops) compound two models' errors
 * into lines nobody checked; the gate answers NEEDS_MT instead and
 * names the pair that would serve.
 */
public final class DubGate {

    public enum Route { PASSTHROUGH, DIRECT, NONE }

    /** One-hop matrix routing over the installed pack pairs. */
    public static Route route(String srcLang, String dstLang, Set<String> pairs) {
        if (srcLang == null || dstLang == null
                || srcLang.isEmpty() || dstLang.isEmpty()) return Route.NONE;
        if (srcLang.equals(dstLang)) return Route.PASSTHROUGH;
        if (pairs != null && pairs.contains(srcLang + "-" + dstLang)) return Route.DIRECT;
        return Route.NONE;
    }

    public enum Plan {
        /** Subtitles + voice pack: full cast dub from text. */
        SUB_CAST,
        /** Subtitles name their speakers; cast by name, no pack. */
        SUB_NAMED,
        /** Subtitles, no names, no pack: one voice reads the text. */
        SUB_SOLO,
        /** No subtitles: the engine listens, the pack casts. */
        ASR_CAST,
        /** No subtitles: the engine listens; one voice. */
        ASR_SOLO,
        /** A subtitle file was found but its language is not named
         *  and not pinned by the viewer yet — ask, don't guess. */
        SUB_LANG_UNKNOWN,
        /** The needed translation pair is not installed; the plan
         *  names it (detail = "ja-es"). */
        NEEDS_MT,
        /** No subtitles and no listening engine for this file. */
        NEEDS_ENGINE
    }

    public static final class Decision {
        public final Plan plan;
        /** For NEEDS_MT: the missing pair, "src-dst". */
        public final String detail;
        Decision(Plan p, String d) { plan = p; detail = d; }
        public boolean cooks() {
            return plan == Plan.SUB_CAST || plan == Plan.SUB_NAMED
                    || plan == Plan.SUB_SOLO || plan == Plan.ASR_CAST
                    || plan == Plan.ASR_SOLO;
        }
        public boolean cast() {
            return plan == Plan.SUB_CAST || plan == Plan.SUB_NAMED
                    || plan == Plan.ASR_CAST;
        }
        public boolean fromSubs() {
            return plan == Plan.SUB_CAST || plan == Plan.SUB_NAMED
                    || plan == Plan.SUB_SOLO;
        }
    }

    private DubGate() {}

    /**
     * @param subLang     the harvested track's language; null = no
     *                    usable subtitle track; "" = a track exists
     *                    but its language is unnamed/unpinned
     * @param subNamed    the track names its speakers (ASS/VTT)
     * @param targetLang  the language the viewer wants to hear
     * @param pairs       installed MT pack pairs ("ja-en")
     * @param diarPack    the voice (diarization) pack is installed
     * @param asrPair     the installed ASR engine's pair ("ja-en"),
     *                    or null when no engine can listen
     */
    public static Decision decide(String subLang, boolean subNamed,
                                  String targetLang, Set<String> pairs,
                                  boolean diarPack, String asrPair) {
        if (subLang != null) {
            if (subLang.isEmpty()) {
                return new Decision(Plan.SUB_LANG_UNKNOWN, "");
            }
            Route r = route(subLang, targetLang, pairs);
            if (r == Route.NONE) {
                return new Decision(Plan.NEEDS_MT, subLang + "-" + targetLang);
            }
            if (diarPack) return new Decision(Plan.SUB_CAST, "");
            if (subNamed) return new Decision(Plan.SUB_NAMED, "");
            return new Decision(Plan.SUB_SOLO, "");
        }
        // the ear road
        if (asrPair == null || asrPair.isEmpty()) {
            return new Decision(Plan.NEEDS_ENGINE, "");
        }
        String[] p = asrPair.split("-");
        String asrTarget = p.length == 2 ? p[1] : "";
        if (!asrTarget.equals(targetLang)) {
            // the listener cooks into its own pair's target; serving
            // another hearing language needs that pair's pack
            if (route(asrTarget, targetLang, pairs) != Route.DIRECT) {
                return new Decision(Plan.NEEDS_MT, asrTarget + "-" + targetLang);
            }
            // a direct pack onward exists — the ASR road still cooks
            // (its lines ride the second hop as text; the plan is
            // ASR + a matrix hop, casting unchanged)
        }
        if (diarPack) return new Decision(Plan.ASR_CAST, "");
        return new Decision(Plan.ASR_SOLO, "");
    }
}
