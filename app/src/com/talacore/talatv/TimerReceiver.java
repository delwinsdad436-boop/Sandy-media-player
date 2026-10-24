package com.talacore.talatv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The timer's doorbell (v0.11.0). Two rings only:
 *  - TIMER_FIRE (from TimerArm's alarm): start the recording
 *    service for that one timer id — the service re-reads the queue
 *    file, so a stale or cancelled timer fires nothing.
 *  - BOOT_COMPLETED: alarms die with the phone; the queue file
 *    doesn't. Run reconciliation — re-arm what's future, fire what
 *    can still be joined, mark what was missed while off.
 */
public final class TimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            TimerReconcile.run(ctx);
            return;
        }
        if (TimerArm.ACTION_FIRE.equals(action)) {
            String id = intent.getStringExtra(TimerArm.EXTRA_TIMER_ID);
            if (id == null) return;
            TimerModel.Timer t = TimerStore.get(ctx, id);
            if (t == null || t.state != TimerModel.State.SET) return;
            TimerReconcile.startService(ctx, id);
        }
    }
}
