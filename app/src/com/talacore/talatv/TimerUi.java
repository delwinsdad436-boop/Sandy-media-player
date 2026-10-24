package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;
import java.util.TimeZone;

/**
 * Setting a timer, shared by the guide and the Timers screen
 * (v0.11.0): build the record, check it against the queue IN THE
 * OPEN (one recorder, one tape — an overlap is resolved by the
 * person, here, never silently at fire time), file it, arm it,
 * and say what got armed. Also the two permission walks the
 * research requires, asked at set time and labeled honestly:
 * battery-optimization exemption, and — only when the fallback
 * path had to be used — the exact-alarm grant.
 */
public final class TimerUi {

    private TimerUi() {}

    public static TimerModel.Timer build(String channelName, String url,
                                         String tvgId, String title,
                                         long startMs, long stopMs) {
        TimerModel.Timer t = new TimerModel.Timer();
        t.id = TimerModel.newId(System.currentTimeMillis(),
                (int) (Math.random() * 100000));
        t.channelName = channelName == null ? "" : channelName;
        t.channelUrl = url == null ? "" : url;
        t.tvgId = tvgId == null ? "" : tvgId;
        t.title = title == null ? "" : title;
        t.startMs = startMs;
        t.stopMs = stopMs;
        t.createdAtMs = System.currentTimeMillis();
        return t;
    }

    /** File + arm a timer, resolving any overlap with the person first. */
    public static void commit(Activity act, TimerModel.Timer t, Runnable done) {
        List<TimerModel.Timer> all = TimerStore.all(act);
        TimerModel.Timer clash = TimerModel.conflict(all, t);
        if (clash != null) {
            String when = GuideModel.formatDay(clash.startMs,
                    TimeZone.getDefault()) + " "
                    + GuideModel.formatTime(clash.startMs, TimeZone.getDefault());
            new AlertDialog.Builder(act)
                    .setTitle("That overlaps another timer")
                    .setMessage("“" + label(clash) + "” is set for " + when
                            + ". One recorder can only tape one thing "
                            + "at a time.")
                    .setPositiveButton("Cancel the other timer", (d, w) -> {
                        clash.state = TimerModel.State.CANCELLED;
                        TimerStore.update(act, clash);
                        TimerArm.disarm(act, clash.id);
                        commit(act, t, done);
                    })
                    .setNegativeButton("Don't set this one", (d, w) -> { })
                    .show();
            return;
        }
        TimerStore.add(act, t);
        t.alarmKind = TimerArm.arm(act, t);
        TimerStore.update(act, t);
        askBatteryExemption(act);
        if ("approximate".equals(t.alarmKind)) askExactAlarm(act);
        Toast.makeText(act, "⏰ Timer set — “" + label(t) + "” · "
                        + GuideModel.formatDay(t.startMs, TimeZone.getDefault())
                        + " " + GuideModel.formatTime(t.startMs,
                                TimeZone.getDefault()),
                Toast.LENGTH_LONG).show();
        if (done != null) done.run();
    }

    public static String label(TimerModel.Timer t) {
        return t.title.isEmpty() ? t.channelName : t.title;
    }

    /** The state line a timer row shows, in the viewer's language where
     *  Sandy speaks it; the recorded detail rides verbatim after it. */
    public static String stateText(Context ctx, TimerModel.Timer t) {
        switch (t.state) {
            case SET:
                return "Set — " + GuideModel.formatDay(t.startMs,
                        TimeZone.getDefault()) + " "
                        + GuideModel.formatTime(t.startMs, TimeZone.getDefault())
                        + ("approximate".equals(t.alarmKind)
                                ? " · " + LangStore.tr(ctx, "timer_approx") : "");
            case RECORDING:
                return "● Recording…";
            case RECORDED:
                return LangStore.tr(ctx, "state_recorded");
            case PARTIAL_LATE:
            case PARTIAL_EARLY:
                return LangStore.tr(ctx, "state_partial")
                        + (t.detail.isEmpty() ? "" : " — " + t.detail);
            case FAILED_REFUSED:
            case FAILED_SPACE:
                return LangStore.tr(ctx, "state_failed")
                        + (t.detail.isEmpty() ? "" : " — " + t.detail);
            case MISSED_OFF:
            case MISSED_WAKE:
                return LangStore.tr(ctx, "state_missed")
                        + (t.detail.isEmpty() ? "" : " — " + t.detail);
            case CANCELLED:
                return "Cancelled";
            default:
                return t.state.name();
        }
    }

    private static void askBatteryExemption(Activity act) {
        try {
            if (act.getSharedPreferences("talatv", Context.MODE_PRIVATE)
                    .getBoolean("timer_batt_asked", false)) return;
            act.getSharedPreferences("talatv", Context.MODE_PRIVATE)
                    .edit().putBoolean("timer_batt_asked", true).apply();
            PowerManager pm = (PowerManager) act.getSystemService(
                    Context.POWER_SERVICE);
            if (pm == null || Build.VERSION.SDK_INT < 23
                    || pm.isIgnoringBatteryOptimizations(act.getPackageName())) {
                return;
            }
            new AlertDialog.Builder(act)
                    .setTitle("Let timers wake the phone")
                    .setMessage("So a timer can record while the phone "
                            + "sleeps, Sandy needs to be allowed to run "
                            + "in the background. Nothing else changes.")
                    .setPositiveButton("Allow", (d, w) -> {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            i.setData(Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                        } catch (Exception ignored) { }
                    })
                    .setNegativeButton("Not now", (d, w) -> { })
                    .show();
        } catch (Exception ignored) { }
    }

    private static void askExactAlarm(Activity act) {
        try {
            if (Build.VERSION.SDK_INT < 31) return;
            android.app.AlarmManager am = (android.app.AlarmManager)
                    act.getSystemService(Context.ALARM_SERVICE);
            if (am == null || am.canScheduleExactAlarms()) return;
            new AlertDialog.Builder(act)
                    .setTitle("Timers may start late")
                    .setMessage("This phone hasn't given Sandy permission "
                            + "for exact alarms, so timers are approximate "
                            + "and may start late. You can allow exact "
                            + "alarms in the phone's settings.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        try {
                            Intent i = new Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                            i.setData(Uri.parse("package:" + act.getPackageName()));
                            act.startActivity(i);
                        } catch (Exception ignored) { }
                    })
                    .setNegativeButton("Leave approximate", (d, w) -> { })
                    .show();
        } catch (Exception ignored) { }
    }
}
