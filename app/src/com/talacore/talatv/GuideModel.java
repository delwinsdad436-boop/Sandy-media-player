package com.talacore.talatv;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The TV guide's pure core (v0.11.0): programmes as epoch rows, and
 * the now/next + day math over them. Everything is stored and
 * compared in epoch milliseconds; the viewer's timezone enters ONLY
 * at render (callers pass the local day's start/length, computed
 * from their own zone) — so guide math can never smuggle a source
 * timezone into a displayed time.
 *
 * Honesty rules baked in: a channel with no rows has NO programme
 * (callers print "no guide data" on its face — nothing is ever
 * interpolated or invented); a gap between programmes is a gap,
 * shown as "no programme information", never papered over.
 *
 * Pure: no Android, no I/O. Harness-tested across timezones and DST.
 */
public final class GuideModel {

    private GuideModel() {}

    /** One programme on one channel. Times are epoch milliseconds. */
    public static final class Programme {
        public final String channelId;   // Sandy's base tvg-id
        public final long startMs;
        public final long stopMs;
        public final String title;
        public final String desc;
        public final String category;

        public Programme(String channelId, long startMs, long stopMs,
                         String title, String desc, String category) {
            this.channelId = channelId;
            this.startMs = startMs;
            this.stopMs = stopMs;
            this.title = title == null ? "" : title;
            this.desc = desc == null ? "" : desc;
            this.category = category == null ? "" : category;
        }

        public boolean covers(long nowMs) {
            return startMs <= nowMs && nowMs < stopMs;
        }

        public long durationMs() { return Math.max(0, stopMs - startMs); }
    }

    /** Programmes for many channels, each channel's rows start-sorted. */
    public static final class GuideData {
        public final Map<String, List<Programme>> byChannel = new HashMap<>();
        /** Latest stop across everything (the "data through" claim). */
        public long throughMs = 0;
        /** Earliest start across everything. */
        public long fromMs = Long.MAX_VALUE;

        public void add(Programme p) {
            if (p == null || p.channelId == null || p.stopMs <= p.startMs) return;
            List<Programme> rows = byChannel.get(p.channelId);
            if (rows == null) {
                rows = new ArrayList<>();
                byChannel.put(p.channelId, rows);
            }
            rows.add(p);
            if (p.stopMs > throughMs) throughMs = p.stopMs;
            if (p.startMs < fromMs) fromMs = p.startMs;
        }

        /** Sort every channel's rows; call once after bulk adds. */
        public void finish() {
            for (List<Programme> rows : byChannel.values()) {
                Collections.sort(rows, new Comparator<Programme>() {
                    @Override public int compare(Programme a, Programme b) {
                        return Long.compare(a.startMs, b.startMs);
                    }
                });
            }
            if (byChannel.isEmpty()) fromMs = 0;
        }

        public List<Programme> rowsFor(String channelId) {
            List<Programme> rows = byChannel.get(channelId);
            return rows == null ? Collections.<Programme>emptyList() : rows;
        }

        public boolean hasGuide(String channelId) {
            return byChannel.containsKey(channelId);
        }

        public int channelCount() { return byChannel.size(); }

        /**
         * The programme on at {@code nowMs}, or null — including when
         * rows exist but a gap covers now (a gap is information, and
         * it is shown as "no programme information", never filled).
         */
        public Programme now(String channelId, long nowMs) {
            List<Programme> rows = rowsFor(channelId);
            int lo = 0, hi = rows.size() - 1, best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (rows.get(mid).startMs <= nowMs) { best = mid; lo = mid + 1; }
                else hi = mid - 1;
            }
            if (best < 0) return null;
            Programme p = rows.get(best);
            return p.covers(nowMs) ? p : null;
        }

        /** The next programme after the current one (or after now). */
        public Programme next(String channelId, long nowMs) {
            for (Programme p : rowsFor(channelId)) {
                if (p.startMs > nowMs) return p;
            }
            return null;
        }

        /**
         * Everything overlapping the caller's local day window
         * [dayStartMs, dayStartMs+dayLengthMs) — the caller owns the
         * timezone math (a DST day is 23h or 25h long and still one
         * day). Programmes spanning midnight appear on both days.
         */
        public List<Programme> day(String channelId, long dayStartMs, long dayLengthMs) {
            long end = dayStartMs + dayLengthMs;
            List<Programme> out = new ArrayList<>();
            for (Programme p : rowsFor(channelId)) {
                if (p.startMs < end && p.stopMs > dayStartMs) out.add(p);
            }
            return out;
        }

        /** Drop rows that ended before {@code cutoffMs}. */
        public void prune(long cutoffMs) {
            List<String> empty = new ArrayList<>();
            for (Map.Entry<String, List<Programme>> e : byChannel.entrySet()) {
                List<Programme> keep = new ArrayList<>();
                for (Programme p : e.getValue()) {
                    if (p.stopMs > cutoffMs) keep.add(p);
                }
                if (keep.isEmpty()) empty.add(e.getKey());
                else e.setValue(keep);
            }
            for (String k : empty) byChannel.remove(k);
            long t = 0, f = Long.MAX_VALUE;
            for (List<Programme> rows : byChannel.values()) {
                for (Programme p : rows) {
                    if (p.stopMs > t) t = p.stopMs;
                    if (p.startMs < f) f = p.startMs;
                }
            }
            throughMs = t;
            fromMs = byChannel.isEmpty() ? 0 : f;
        }
    }

    // ------------------------------------------------- XMLTV parsing

    /**
     * Parse an XMLTV timestamp ("20261018220000 +0300", also the
     * offset-less and short forms some files use) to epoch ms.
     * Offset-less times are read as UTC — XMLTV's own convention is
     * that a missing offset means the publisher's zone is unknown,
     * and treating it as UTC at least never invents a local claim.
     * Returns -1 on anything unparseable (the row is dropped, never
     * guessed).
     */
    public static long parseXmltvTime(String raw) {
        if (raw == null) return -1;
        String s = raw.trim();
        if (s.length() < 14) return -1;
        String digits = s.substring(0, 14);
        for (int i = 0; i < 14; i++) {
            if (!Character.isDigit(digits.charAt(i))) return -1;
        }
        String zone = "UTC";
        String rest = s.substring(14).trim();
        if (rest.length() >= 5) {
            // "+0300" / "-0500" — SimpleDateFormat wants "+0300" as Z
            // only in the XXX form; use GMT offset id which always works.
            zone = "GMT" + rest.substring(0, 3) + ":" + rest.substring(3, 5);
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone(zone));
        f.setLenient(false);
        try {
            return f.parse(digits).getTime();
        } catch (ParseException | NumberFormatException e) {
            return -1;
        }
    }

    /** The house time-of-day format for guide rows ("7:00 PM"). */
    public static String formatTime(long epochMs, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(zone);
        return f.format(new java.util.Date(epochMs));
    }

    /** The house day format for guide headers ("Sun, Oct 18"). */
    public static String formatDay(long epochMs, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, MMM d", Locale.US);
        f.setTimeZone(zone);
        return f.format(new java.util.Date(epochMs));
    }
}
