package com.talacore.talatv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted link-health verdicts (v0.3.0, tiered in v0.4.0). Probing a
 * big shelf costs time and goodwill, so verdicts survive the session:
 * every probe — manual Verify, tap-time check, background auto-fresh,
 * and every real play (READY = plays, failure = dead) — writes here,
 * and badges, filters and surf order read from here. James's point
 * stands: "things maneuver," so verdicts also carry WHEN they were
 * earned, and freshness is the caller's business (auto-fresh re-probes
 * anything older than 6 hours).
 *
 * v0.4.0 tiers (LinkHealth.TIER_*): PLAYS / CONNECTS / DEAD replace the
 * 0.3.0 OK/DEAD binary, and each record keeps a dead streak — how many
 * verdicts in a row came back DEAD — because a link that has died
 * three times running is a different animal from one having a bad day.
 *
 * Storage: filesDir/health2.tsv, tab-separated, URL-encoded fields:
 *   enc(url) \t tier \t checkedAt \t deadStreak \t enc(detail)
 * The 0.3.0 health.tsv (url \t status \t checkedAt \t detail) is
 * migrated once, honestly: an old OK only ever proved the playlist
 * answered, so it lands as CONNECTS, never PLAYS; an old DEAD stays
 * DEAD with a streak of 1. Unknown rows are skipped, never guessed.
 */
public final class HealthStore {

    public static final class Health {
        public final int tier;
        public final long checkedAt;
        public final int deadStreak;
        public final String detail;
        Health(int tier, long checkedAt, int deadStreak, String detail) {
            this.tier = tier;
            this.checkedAt = checkedAt;
            this.deadStreak = deadStreak;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static Map<String, Health> cache;
    private static boolean dirty;

    private HealthStore() {}

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), "health2.tsv");
    }

    private static File legacyFile(Context ctx) {
        return new File(ctx.getFilesDir(), "health.tsv");
    }

    public static synchronized Map<String, Health> load(Context ctx) {
        if (cache != null) return cache;
        cache = new HashMap<>();
        File f = file(ctx);
        if (f.isFile()) {
            readInto(f, cache, false);
        } else {
            // First 0.4.0 launch: carry the 0.3.0 verdicts forward at
            // their honest new weight (see class doc).
            File legacy = legacyFile(ctx);
            if (legacy.isFile()) {
                readInto(legacy, cache, true);
                dirty = true;
                saveLocked(ctx);
            }
        }
        return cache;
    }

    private static void readInto(File f, Map<String, Health> into, boolean legacy) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                try {
                    if (legacy) {
                        if (p.length < 3) continue;
                        int old = Integer.parseInt(p[1]);
                        int tier = old == 1 ? LinkHealth.TIER_CONNECTS : LinkHealth.TIER_DEAD;
                        into.put(dec(p[0]), new Health(tier, Long.parseLong(p[2]),
                                tier == LinkHealth.TIER_DEAD ? 1 : 0,
                                p.length > 3 ? dec(p[3]) : ""));
                    } else {
                        if (p.length < 4) continue;
                        int tier = Integer.parseInt(p[1]);
                        into.put(dec(p[0]), new Health(tier, Long.parseLong(p[2]),
                                Integer.parseInt(p[3]),
                                p.length > 4 ? dec(p[4]) : ""));
                    }
                } catch (Exception ignored) {
                    // a torn line is skipped, never guessed at
                }
            }
        } catch (Exception ignored) {}
    }

    public static synchronized Health get(Context ctx, String url) {
        if (url == null) return null;
        return load(ctx).get(url);
    }

    /** Records a verdict (called from probe sinks on worker threads). */
    public static synchronized void recordQuiet(Context ctx, String url,
                                                LinkHealth.Result r) {
        if (url == null || r == null) return;
        // v0.11.0: a SLOW outcome is not a verdict on the link's life
        // (James's field report — a slow server got filed DEAD and
        // wore the ✗ until a manual re-tap). Nothing is filed: any
        // earlier verdict stands, and the next probe decides fresh.
        if (r.tier == LinkHealth.TIER_DEAD && r.slow) return;
        Map<String, Health> m = load(ctx);
        Health prev = m.get(url);
        int streak = 0;
        if (r.tier == LinkHealth.TIER_DEAD) {
            streak = (prev == null ? 0 : prev.deadStreak) + 1;
        }
        m.put(url, new Health(r.tier, r.checkedAt, streak, r.detail));
        dirty = true;
    }

    /** Records a verdict and writes through immediately (tap-time events). */
    public static synchronized void record(Context ctx, String url,
                                           LinkHealth.Result r) {
        recordQuiet(ctx, url, r);
        saveLocked(ctx);
    }

    /** Writes pending verdicts; safe to call any time, no-ops when clean. */
    public static synchronized void save(Context ctx) {
        if (!dirty && cache != null) return;
        saveLocked(ctx);
    }

    private static void saveLocked(Context ctx) {
        if (cache == null) return;
        try (FileWriter w = new FileWriter(file(ctx), false)) {
            for (Map.Entry<String, Health> e : cache.entrySet()) {
                Health h = e.getValue();
                w.write(enc(e.getKey()) + "\t" + h.tier + "\t" + h.checkedAt
                        + "\t" + h.deadStreak + "\t" + enc(h.detail) + "\n");
            }
            dirty = false;
        } catch (Exception ignored) {}
    }

    /** {plays, connects, dead} over every recorded verdict. */
    public static synchronized int[] counts(Context ctx) {
        int plays = 0, connects = 0, dead = 0;
        for (Health h : load(ctx).values()) {
            if (h.tier == LinkHealth.TIER_PLAYS) plays++;
            else if (h.tier == LinkHealth.TIER_CONNECTS) connects++;
            else dead++;
        }
        return new int[]{plays, connects, dead};
    }

    /**
     * Surf rank for lists (Mom-first ordering): segment-proven first,
     * then never-checked, then connects-only, then dead — and a link
     * that has died 3+ verdicts running sinks below everything, bad
     * day or not. Lower is earlier.
     */
    public static int surfRank(Health h) {
        if (h == null) return 1;
        if (h.tier == LinkHealth.TIER_PLAYS) return 0;
        if (h.tier == LinkHealth.TIER_CONNECTS) return 2;
        return h.deadStreak >= 3 ? 4 : 3;
    }

    // ------------------------------------------------------------- badges

    public static String badgeSymbol(Health h) {
        if (h == null) return "";
        if (h.tier == LinkHealth.TIER_PLAYS) return "✓ ";
        if (h.tier == LinkHealth.TIER_CONNECTS) return "~ ";
        return "✗ ";
    }

    /** The second line a row shows; "" when there is no verdict yet. */
    public static String badgeText(Health h, long now) {
        if (h == null) return "";
        String when = LinkHealth.friendlyWhen(h.checkedAt, now);
        if (h.tier == LinkHealth.TIER_PLAYS) return "✓ Plays · verified " + when;
        if (h.tier == LinkHealth.TIER_CONNECTS) {
            return "~ Connects · video unproven · " + when
                    + (h.detail.isEmpty() ? "" : " (" + h.detail + ")");
        }
        return "✗ Not working · checked " + when
                + (h.detail.isEmpty() ? "" : " (" + h.detail + ")");
    }

    // ------------------------------------------------------------- urls

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    static String enc(String s) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int c = value & 0xff;
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~';
            if (safe) b.append((char) c);
            else b.append('%').append(HEX[(c >> 4) & 0xf]).append(HEX[c & 0xf]);
        }
        return b.toString();
    }

    static String dec(String s) {
        if (s == null) return "";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (Exception ignored) {}
            }
            out.write((byte) c);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** For the harness / future callers: a stable snapshot list. */
    public static List<String> knownUrls(Context ctx) {
        return new ArrayList<>(load(ctx).keySet());
    }
}
