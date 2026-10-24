package com.talacore.talatv;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.List;

/**
 * Reconciliation (v0.11.0): on every boot and every app open, the
 * filed queue is read and each live timer gets what TimerModel's
 * state machine says it is owed — re-arm the future, fire the
 * late-joinable, and mark the gone ones MISSED with the honest
 * reason. This is what makes "the phone was off" a stated outcome
 * instead of a silent hole, and what re-derives every alarm from
 * the file after a reboot (alarms do not survive one; the file does).
 */
public final class TimerReconcile {

    private TimerReconcile() {}

    public static void run(Context ctx) {
        try {
            List<TimerModel.Timer> timers = TimerStore.all(ctx);
            long now = System.currentTimeMillis();
            boolean changed = false;
            for (TimerModel.Decision d : TimerModel.reconcile(timers, now)) {
                TimerModel.Timer t = d.timer;
                switch (d.action) {
                    case REARM:
                        String kind = TimerArm.arm(ctx, t);
                        if (!kind.equals(t.alarmKind)) {
                            t.alarmKind = kind;
                            changed = true;
                        }
                        break;
                    case FIRE_NOW:
                    case FIRE_LATE:
                        startService(ctx, t.id);
                        break;
                    case MARK_MISSED_OFF:
                        t.state = TimerModel.State.MISSED_OFF;
                        t.detail = "Missed — the phone was off";
                        changed = true;
                        break;
                    case MARK_INTERRUPTED:
                        t.state = TimerModel.State.PARTIAL_EARLY;
                        t.detail = "Partial — recording interrupted "
                                + "(the app was stopped)";
                        changed = true;
                        break;
                    default:
                        break;
                }
            }
            if (changed) TimerStore.save(ctx, timers);
        } catch (Exception ignored) { }
    }

    static void startService(Context ctx, String timerId) {
        Intent i = new Intent(ctx, TimerRecordService.class);
        i.putExtra(TimerArm.EXTRA_TIMER_ID, timerId);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }
}
