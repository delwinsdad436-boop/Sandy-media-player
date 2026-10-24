package com.talacore.talatv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * The VCR timer's pure core (v0.11.0): the timer record, its state
 * machine, and every decision that must be provable off-device —
 * conflict detection, fire-time math, storage estimates, and the
 * reconciliation a reopened (or rebooted) app runs over the queue.
 *
 * Two ways in, one queue (James's order, confirmed by the EPG
 * research): a timer is born from a guide programme OR set by hand
 * for any channel — the timer never depends on the guide existing.
 *
 * Honest end states, stated on the Recordings shelf and never
 * softened: RECORDED · PARTIAL_LATE (joined after the start) ·
 * PARTIAL_EARLY (stopped early — out of space) · FAILED_REFUSED
 * (the source refused) · FAILED_SPACE (not enough room, known
 * before firing) · MISSED_OFF (phone off / app dead at showtime) ·
 * MISSED_WAKE (the alarm fired but recording could not start).
 *
 * The queue is a FILE (timers.tsv behind the memory hook); alarms
 * are always re-derived from it, never the other way around.
 */
public final class TimerModel {

    private TimerModel() {}

    public static final long DEFAULT_PAD_BEFORE_MS = 60_000;
    public static final long DEFAULT_PAD_AFTER_MS = 120_000;
    /** Late-join needs at least this much programme left to be worth tape. */
    public static final long LATE_JOIN_MIN_REMAINING_MS = 5 * 60_000;
    /** Storage estimate when the playlist doesn't declare a bitrate. */
    public static final double DEFAULT_MBPS = 5.0;
    /** Pre-flight refuses to start when free space is under estimate × this. */
    public static final double SPACE_SAFETY = 1.2;

    public enum State {
        SET, RECORDING, RECORDED, PARTIAL_LATE, PARTIAL_EARLY,
        FAILED_REFUSED, FAILED_SPACE, MISSED_OFF, MISSED_WAKE, CANCELLED
    }

    /** One timer = one future (or finished) recording. */
    public static final class Timer {
        public String id = "";
        public String channelName = "";
        public String channelUrl = "";
        public String tvgId = "";
        public String title = "";
        /** Programme window (unpadded). */
        public long startMs, stopMs;
        public long padBeforeMs = DEFAULT_PAD_BEFORE_MS;
        public long padAfterMs = DEFAULT_PAD_AFTER_MS;
        public State state = State.SET;
        /** The honest why for terminal states, "" while live. */
        public String detail = "";
        /** "exact" (alarm clock) or "approximate" — shown on the row. */
        public String alarmKind = "exact";
        /** True when recording began after the padded start. */
        public boolean lateJoin = false;
        public long createdAtMs = 0;

        public long fireMs() { return startMs - padBeforeMs; }
        public long untilMs() { return stopMs + padAfterMs; }
        public boolean terminal() {
            return state != State.SET && state != State.RECORDING;
        }
    }

    public static String newId(long nowMs, int seq) {
        return "t" + Long.toString(nowMs, 36) + "x" + seq;
    }

    // ------------------------------------------------------ TSV rows
    // timers.tsv columns (free-text fields URL-encoded, house style):
    // id, state, startMs, stopMs, padBeforeMs, padAfterMs, alarmKind,
    // lateJoin(0/1), createdAtMs, tvgId, title, channelName, channelUrl, detail

    public static String toRow(Timer t) {
        StringBuilder sb = new StringBuilder();
        sb.append(enc(t.id)).append('\t')
          .append(t.state.name()).append('\t')
          .append(t.startMs).append('\t')
          .append(t.stopMs).append('\t')
          .append(t.padBeforeMs).append('\t')
          .append(t.padAfterMs).append('\t')
          .append(enc(t.alarmKind)).append('\t')
          .append(t.lateJoin ? '1' : '0').append('\t')
          .append(t.createdAtMs).append('\t')
          .append(enc(t.tvgId)).append('\t')
          .append(enc(t.title)).append('\t')
          .append(enc(t.channelName)).append('\t')
          .append(enc(t.channelUrl)).append('\t')
          .append(enc(t.detail));
        return sb.toString();
    }

    /** One row back; null on a malformed row (skipped, never fatal). */
    public static Timer fromRow(String row) {
        if (row == null) return null;
        String[] c = row.split("\t", -1);
        if (c.length < 14) return null;
        try {
            Timer t = new Timer();
            t.id = dec(c[0]);
            t.state = State.valueOf(c[1]);
            t.startMs = Long.parseLong(c[2]);
            t.stopMs = Long.parseLong(c[3]);
            t.padBeforeMs = Long.parseLong(c[4]);
            t.padAfterMs = Long.parseLong(c[5]);
            t.alarmKind = dec(c[6]);
            t.lateJoin = "1".equals(c[7]);
            t.createdAtMs = Long.parseLong(c[8]);
            t.tvgId = dec(c[9]);
            t.title = dec(c[10]);
            t.channelName = dec(c[11]);
            t.channelUrl = dec(c[12]);
            t.detail = dec(c[13]);
            if (t.id.isEmpty() || t.stopMs <= t.startMs) return null;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    public static String serialize(List<Timer> timers) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy recording timers - one row per timer; the queue is\n");
        sb.append("# this file (memory hook). Alarms re-derive from it.\n");
        for (Timer t : timers) sb.append(toRow(t)).append('\n');
        return sb.toString();
    }

    public static List<Timer> parse(String content) {
        List<Timer> out = new ArrayList<>();
        if (content == null) return out;
        for (String line : content.split("\n")) {
            // Never trim the row itself: the last field may be a
            // legitimately empty encoded value, and trim() would
            // eat its separator and shorten the row.
            String probe = line.trim();
            if (probe.isEmpty() || probe.startsWith("#")) continue;
            Timer t = fromRow(line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line);
            if (t != null) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------- decisions

    /**
     * One recorder, one tape: two live timers conflict when their
     * PADDED windows overlap. Resolved at set time, in the open —
     * never silently at fire time.
     */
    public static Timer conflict(List<Timer> timers, Timer candidate) {
        for (Timer t : timers) {
            if (t.state != State.SET && t.state != State.RECORDING) continue;
            if (candidate.id.equals(t.id)) continue;
            if (candidate.fireMs() < t.untilMs() && t.fireMs() < candidate.untilMs()) {
                return t;
            }
        }
        return null;
    }

    public enum Action { REARM, FIRE_NOW, FIRE_LATE, MARK_MISSED_OFF,
                         MARK_INTERRUPTED, NONE }

    public static final class Decision {
        public final Timer timer;
        public final Action action;
        Decision(Timer t, Action a) { timer = t; action = a; }
    }

    /**
     * What a reopened/rebooted app owes each SET timer at {@code nowMs}:
     * future → re-arm; inside its window with real programme left →
     * fire late (it joins mid-show and the shelf will say Partial);
     * window already gone without ever firing → missed, phone off —
     * the honest default, because a phone that was on and armed
     * leaves a RECORDING row behind, and this one has none.
     */
    public static List<Decision> reconcile(List<Timer> timers, long nowMs) {
        List<Decision> out = new ArrayList<>();
        for (Timer t : timers) {
            if (t.state == State.RECORDING) {
                // Was recording when the app/process died. Past its
                // window it can only be closed honestly: interrupted.
                out.add(new Decision(t, nowMs > t.untilMs()
                        ? Action.MARK_INTERRUPTED : Action.NONE));
                continue;
            }
            if (t.state != State.SET) { out.add(new Decision(t, Action.NONE)); continue; }
            if (nowMs < t.fireMs()) {
                out.add(new Decision(t, Action.REARM));
            } else if (nowMs < t.startMs) {
                // Woke inside the pad — the pad absorbed the
                // lateness; the show itself hasn't started.
                out.add(new Decision(t, Action.FIRE_NOW));
            } else if (nowMs <= t.stopMs - LATE_JOIN_MIN_REMAINING_MS) {
                out.add(new Decision(t, Action.FIRE_LATE));
            } else {
                out.add(new Decision(t, Action.MARK_MISSED_OFF));
            }
        }
        return out;
    }

    /** The terminal state a finished recording lands in. */
    public static State endState(boolean started, boolean lateJoin,
                                 boolean stoppedEarlySpace,
                                 boolean sourceRefused) {
        if (!started) return sourceRefused ? State.FAILED_REFUSED : State.MISSED_WAKE;
        if (stoppedEarlySpace) return State.PARTIAL_EARLY;
        if (lateJoin) return State.PARTIAL_LATE;
        return State.RECORDED;
    }

    // ------------------------------------------------------ storage

    /** Bytes one hour at {@code mbps} occupies (Mbps × 0.45 GB/h). */
    public static long bytesForDuration(double mbps, long durationMs) {
        double hours = durationMs / 3_600_000.0;
        return (long) (mbps * 0.45 * hours * 1_000_000_000.0);
    }

    /** Pre-flight verdict: can this recording start, honestly? */
    public static boolean spaceOk(long freeBytes, double mbps, Timer t) {
        long need = bytesForDuration(mbps, t.untilMs() - t.fireMs());
        return freeBytes >= (long) (need * SPACE_SAFETY);
    }

    public static long neededBytes(double mbps, Timer t) {
        return (long) (bytesForDuration(mbps, t.untilMs() - t.fireMs())
                * SPACE_SAFETY);
    }

    /** Human size for rows and refusals ("2.3 GB"). */
    public static String sizeText(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(java.util.Locale.US, "%.1f GB",
                    bytes / 1_000_000_000.0);
        }
        if (bytes >= 1_000_000L) {
            return String.format(java.util.Locale.US, "%.0f MB",
                    bytes / 1_000_000.0);
        }
        return Math.max(0, bytes / 1000) + " KB";
    }

    // -------------------------------------------------------- codec

    public static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    public static String dec(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
