package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The timer queue's store (v0.11.0): memory/timers.tsv behind the
 * memory hook, nothing else. The FILE is the queue — alarms are
 * re-derived from it at boot and on every open, so a timer set in
 * the app can never exist only inside AlarmManager's memory.
 */
public final class TimerStore {

    private TimerStore() {}

    public static synchronized List<TimerModel.Timer> all(Context ctx) {
        return TimerModel.parse(MemoryHook.read(ctx, MemoryHook.TIMERS));
    }

    public static synchronized void save(Context ctx,
                                         List<TimerModel.Timer> timers) {
        MemoryHook.write(ctx, MemoryHook.TIMERS, TimerModel.serialize(timers));
    }

    public static synchronized TimerModel.Timer get(Context ctx, String id) {
        for (TimerModel.Timer t : all(ctx)) if (t.id.equals(id)) return t;
        return null;
    }

    public static synchronized void add(Context ctx, TimerModel.Timer t) {
        List<TimerModel.Timer> all = all(ctx);
        all.add(t);
        save(ctx, all);
    }

    /** Replace the row with the same id (or append when absent). */
    public static synchronized void update(Context ctx, TimerModel.Timer t) {
        List<TimerModel.Timer> all = all(ctx);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(t.id)) { all.set(i, t); found = true; }
        }
        if (!found) all.add(t);
        save(ctx, all);
    }

    /** Live timers only (SET or RECORDING), soonest first. */
    public static synchronized List<TimerModel.Timer> live(Context ctx) {
        List<TimerModel.Timer> out = new ArrayList<>();
        for (TimerModel.Timer t : all(ctx)) {
            if (t.state == TimerModel.State.SET
                    || t.state == TimerModel.State.RECORDING) out.add(t);
        }
        out.sort((a, b) -> Long.compare(a.startMs, b.startMs));
        return out;
    }
}
