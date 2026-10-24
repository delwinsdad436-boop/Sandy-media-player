package com.talacore.talatv;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The local-video model (v0.9.0) — the pure half of Sandy's
 * first-class local player. James's order (2026-10-18): the video
 * player is a function INSIDE Sandy, whole — browse the videos on
 * this phone, tap one, and it plays with the full treatment.
 *
 * What lives here (no Android imports, so the harness can pin every
 * rule): the shelf's row model and its merge order across the three
 * places a file can come from (the phone's video index, My Library's
 * picked folders, the Downloads folders), the per-file resume key,
 * the resume offer policy, the opened-set next/previous arithmetic,
 * the playback-speed cycle, and the clock format the shelf and the
 * player both speak.
 *
 * The Android half (the MediaStore query itself, the file pickers,
 * the screen) lives in FilesActivity; playback itself rides the
 * one PlayerActivity every other source uses — no second player.
 */
public final class LocalFiles {

    /** Where a row came from — labeled honestly on the shelf. */
    public enum Origin { DEVICE, FOLDER, DOWNLOADS }

    public static final class Row {
        public String uri = "";        // content:// or plain path
        public String title = "";      // what the row says
        public long durationMs = -1;   // -1 = the index didn't say
        public long sizeBytes = -1;    // -1 = unknown
        public Origin origin = Origin.DEVICE;

        /** Same file reached by two doors is ONE row. */
        public String identity() {
            return uri == null ? "" : uri;
        }
    }

    private LocalFiles() {}

    // ------------------------------------------------------- shelf order

    /**
     * Merge the three doors into one shelf. Order is deliberate and
     * pinned by the harness: Downloads first (what the viewer just
     * went and got), then the phone's own videos, then picked-folder
     * files; inside a door, newest name order is NOT assumed — rows
     * sort A→Z by title so the shelf is findable, and a file that
     * arrives by two doors appears once, under the earlier door.
     */
    public static List<Row> mergeShelves(List<Row> downloads,
                                         List<Row> device,
                                         List<Row> folder) {
        List<Row> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        appendDoor(out, seen, downloads);
        appendDoor(out, seen, device);
        appendDoor(out, seen, folder);
        return out;
    }

    private static void appendDoor(List<Row> out, Set<String> seen, List<Row> door) {
        if (door == null) return;
        List<Row> sorted = new ArrayList<>(door);
        sorted.sort(Comparator.comparing(
                (Row r) -> r.title == null ? "" : r.title.toLowerCase(Locale.US)));
        for (Row r : sorted) {
            if (r == null || r.identity().isEmpty()) continue;
            if (seen.add(r.identity())) out.add(r);
        }
    }

    // ------------------------------------------------------- resume

    /**
     * The per-video resume key. It lives in the same SharedPreferences
     * position store as every catalogue title (ChannelStore), under
     * its own "local:" namespace so a file can never collide with an
     * episode key ("ep:…") or a title key. The key is the file's URI —
     * stable across rescans for MediaStore and SAF files alike.
     */
    public static String keyFor(String uri) {
        return "local:" + (uri == null ? "" : uri);
    }

    public static String keyFor(Row r) {
        return keyFor(r == null ? null : r.uri);
    }

    /** The resume offer policy — one rule, stated once, pinned by tests. */
    public static final class ResumePolicy {
        /** Don't offer to resume a place too near the start to matter. */
        public static final long MIN_OFFER_MS = 10_000;
        /** …or close enough to the end that the video is finished. */
        public static final long END_MARGIN_MS = 15_000;

        private ResumePolicy() {}

        public static boolean shouldOffer(long savedMs, long durationMs) {
            if (savedMs <= MIN_OFFER_MS) return false;
            if (durationMs > 0 && savedMs >= durationMs - END_MARGIN_MS) return false;
            return true;
        }

        /** A finished (or never-started) place clears back to zero. */
        public static boolean isFinished(long savedMs, long durationMs) {
            return durationMs > 0 && savedMs > 0
                    && savedMs >= durationMs - END_MARGIN_MS;
        }

        /** Never resume past the end, whatever a stale record says. */
        public static long clamp(long savedMs, long durationMs) {
            if (savedMs < 0) return 0;
            if (durationMs > 0 && savedMs > durationMs) return durationMs;
            return savedMs;
        }
    }

    // ------------------------------------------------------- opened set

    /** Index of the next file in the opened set, or -1 at the end. */
    public static int nextIndex(int index, int size) {
        if (size <= 0 || index < 0 || index >= size - 1) return -1;
        return index + 1;
    }

    /** Index of the previous file in the opened set, or -1 at the start. */
    public static int prevIndex(int index, int size) {
        if (size <= 0 || index <= 0 || index >= size) return -1;
        return index - 1;
    }

    // ------------------------------------------------------- speed

    /** The speed cycle the player's speed button walks, in order. */
    public static final float[] SPEEDS = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};

    /** The next speed after {@code current} (wraps; unknown = 1.0×). */
    public static float nextSpeed(float current) {
        int best = 2; // 1.0×
        float bestDiff = Float.MAX_VALUE;
        for (int i = 0; i < SPEEDS.length; i++) {
            float d = Math.abs(SPEEDS[i] - current);
            if (d < bestDiff) { bestDiff = d; best = i; }
        }
        return SPEEDS[(best + 1) % SPEEDS.length];
    }

    /** "1×", "1.25×", "0.5×" — the label the button wears. */
    public static String speedLabel(float speed) {
        if (speed == Math.floor(speed)) return ((int) speed) + "×";
        String s = Float.toString(speed);
        return s + "×";
    }

    // ------------------------------------------------------- clock

    /** mm:ss under an hour, h:mm:ss over it; negative/unknown = 0:00. */
    public static String formatClock(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** Human size for a shelf row; unknown stays honestly blank. */
    public static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }
}
