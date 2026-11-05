package com.talacore.talatv;

/**
 * The version, in ONE place (v0.20.0 — the fix pass).
 *
 * Before this class existed, the version was re-typed by hand
 * into every surface that spoke it, and the surfaces drifted
 * apart: by v0.18.0 the Settings About line still said 0.14.1,
 * eight User-Agent fallbacks still said Sandy/0.14.1, Net's UA
 * said 0.18.0, and the crash stamp said 0.18.0 — four versions
 * of the truth in one app, which is how people ended up holding
 * an old build believing it was current (James's version-drift
 * complaint). Now every spoken version in the app derives from
 * these constants: the About line, the crash stamp, and every
 * User-Agent. The manifest carries its own copy (badging is the
 * check that they agree — the build verifies it), and the
 * harness pins these values so a bump that forgets a surface
 * fails the checks instead of shipping.
 *
 * Pure constants: no Android, no state. The harness pins them.
 */
public final class AppVersion {

    /** versionName, exactly as the manifest states it. */
    public static final String NAME = "0.20.0";

    /** versionCode, exactly as the manifest states it. */
    public static final int CODE = 26;

    /** The User-Agent every fetch in the app falls back to. */
    public static final String UA = "Sandy/" + NAME;

    /** The stamp line for crash logs and the About screen. */
    public static final String STAMP =
            "Sandy " + NAME + " (versionCode " + CODE + ")";

    private AppVersion() {}
}
