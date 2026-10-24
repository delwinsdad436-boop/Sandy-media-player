package com.talacore.talatv;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * The timer's time FRAME as one visible thing (v0.12.1, James's
 * field report on 0.11.0/0.11.1: "the timer will pick a day, but it
 * won't pick a time… needs to be a time frame, a set time frame").
 *
 * Pure math + text for the set-timer screen: date + start + end are
 * picked on ONE screen and the frame is summarized in one line
 * ("Tue Oct 20 · 8:00 PM – 9:30 PM") before anything is committed.
 * An end at or before the start is an OVERNIGHT frame: the end
 * rolls to the next day, and the summary prints both days so the
 * roll is unmistakable, never silent.
 */
public final class TimerFrame {

    private TimerFrame() {}

    public static final long DAY_MS = 24L * 3600_000L;

    /** Stop for a start + duration pick (minutes). */
    public static long stopFromDuration(long startMs, long durationMinutes) {
        return startMs + Math.max(1, durationMinutes) * 60_000L;
    }

    /** Whole minutes between start and stop (never negative). */
    public static long durationMinutes(long startMs, long stopMs) {
        return Math.max(0, (stopMs - startMs) / 60_000L);
    }

    /**
     * Compose a frame from a picked day and picked wall-clock times
     * (all in {@code zone}): start = day at startH:startM; end = day
     * at endH:endM, rolled +1 day when it lands at or before the
     * start (the overnight rule above).
     */
    public static long[] compose(long dayStartMs, int startH, int startM,
                                 int endH, int endM, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone);
        c.setTimeInMillis(dayStartMs);
        c.set(Calendar.HOUR_OF_DAY, startH);
        c.set(Calendar.MINUTE, startM);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long start = c.getTimeInMillis();
        c.set(Calendar.HOUR_OF_DAY, endH);
        c.set(Calendar.MINUTE, endM);
        long stop = c.getTimeInMillis();
        if (stop <= start) stop += DAY_MS;
        return new long[]{start, stop};
    }

    /** True when the frame's end falls on a later local day than its start. */
    public static boolean spansMidnight(long startMs, long stopMs, TimeZone zone) {
        return !GuideModel.formatDay(startMs, zone)
                .equals(GuideModel.formatDay(stopMs - 1, zone));
    }

    /**
     * The one-line frame summary the set-timer screen shows live and
     * the commit toast repeats: "Tue Oct 20 · 8:00 PM – 9:30 PM", or
     * with both days when the frame crosses midnight:
     * "Tue Oct 20 · 11:00 PM – Wed Oct 21 · 1:00 AM".
     */
    public static String summary(long startMs, long stopMs, TimeZone zone) {
        StringBuilder sb = new StringBuilder();
        sb.append(GuideModel.formatDay(startMs, zone)).append(" · ")
          .append(GuideModel.formatTime(startMs, zone)).append(" – ");
        if (spansMidnight(startMs, stopMs, zone)) {
            sb.append(GuideModel.formatDay(stopMs, zone)).append(" · ");
        }
        sb.append(GuideModel.formatTime(stopMs, zone));
        return sb.toString();
    }
}
