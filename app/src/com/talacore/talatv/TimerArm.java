package com.talacore.talatv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Arming the VCR timers (v0.11.0). The alarm is a POINTER at the
 * queue file, nothing more: it carries only the timer's id, and the
 * queue is re-read when it fires.
 *
 * Primary mechanism is the alarm-clock API (setAlarmClock): it needs
 * no exact-alarm permission, fires through doze, and is the platform's
 * own "wake me for this" contract — the digital VCR timer. When even
 * that is refused, the fallback lands inexact and the timer's row
 * says so in the open ("approximate" rides the timer record and is
 * printed on its face), never silently.
 */
public final class TimerArm {

    private TimerArm() {}

    public static final String ACTION_FIRE = "com.talacore.talatv.TIMER_FIRE";
    public static final String EXTRA_TIMER_ID = "timer_id";

    private static PendingIntent fireIntent(Context ctx, String timerId) {
        Intent i = new Intent(ctx, TimerReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_TIMER_ID, timerId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, timerId.hashCode(), i, flags);
    }

    /**
     * Arm one timer at its padded fire time. Returns the kind that
     * actually got armed: "exact" or "approximate".
     */
    public static String arm(Context ctx, TimerModel.Timer t) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return "approximate";
        PendingIntent pi = fireIntent(ctx, t.id);
        long at = t.fireMs();
        try {
            Intent show = new Intent(ctx, TimersActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent showPi = PendingIntent.getActivity(
                    ctx, ("show:" + t.id).hashCode(), show, flags);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
            return "exact";
        } catch (Exception ignored) { }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return "approximate";
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            return "exact";
        } catch (Exception ignored) { }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception ignored) { }
        return "approximate";
    }

    public static void disarm(Context ctx, String timerId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try { am.cancel(fireIntent(ctx, timerId)); } catch (Exception ignored) { }
    }

    /** Can this phone promise exact alarms at all right now? */
    public static boolean exactAvailable(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
        // setAlarmClock is always available to the app itself; the
        // exact-alarm permission only gates the fallback path.
        return true;
    }
}
