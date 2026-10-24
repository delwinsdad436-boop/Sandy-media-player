package com.talacore.talatv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StatFs;

import androidx.media3.exoplayer.offline.Download;

import java.io.File;

/**
 * The VCR, running (v0.11.0): a foreground service that records one
 * timer's channel for its programme's duration and then files the
 * honest outcome. This is also the cure for the standing shortfall
 * that recordings pause when the app closes — a timer recording
 * lives here, in a service with a wake lock, not in an activity.
 *
 * The flow mirrors the proven record-now path exactly: Downloads
 * (media3) captures, stopKeep keeps the pieces, RecordingExport
 * assembles the one file into files/recording-export. What this
 * service adds is the schedule around it: pre-flight space check,
 * late-join honesty, the watchdog, and the end state written to
 * BOTH the timer queue and the recordings metadata file.
 */
public final class TimerRecordService extends Service {

    private static final String CHAN = "sandy_timers";
    private static final long SPACE_FLOOR_BYTES = 150L * 1000 * 1000;

    private volatile boolean stopped = false;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String timerId = intent == null ? null
                : intent.getStringExtra(TimerArm.EXTRA_TIMER_ID);
        TimerModel.Timer t = timerId == null ? null : TimerStore.get(this, timerId);
        if (t == null || t.state != TimerModel.State.SET) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = notification("Recording soon", t);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(notifId(t), n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(notifId(t), n);
        }
        final TimerModel.Timer timer = t;
        new Thread(() -> run(timer), "sandy-timer-rec").start();
        return START_NOT_STICKY;
    }

    private static int notifId(TimerModel.Timer t) {
        return 4100 + (Math.abs(t.id.hashCode()) % 3000);
    }

    private void run(TimerModel.Timer t) {
        Context ctx = getApplicationContext();
        long now = System.currentTimeMillis();
        try {
            // --- too late to matter: the programme already ended.
            if (now > t.stopMs) {
                finish(t, TimerModel.State.MISSED_WAKE,
                        "Missed — started too late, the programme had ended");
                return;
            }
            boolean lateJoin = now > t.startMs;

            // --- pre-flight: refuse honestly rather than die mid-tape.
            long free = freeBytes(ctx);
            if (!TimerModel.spaceOk(free, TimerModel.DEFAULT_MBPS, t)) {
                finish(t, TimerModel.State.FAILED_SPACE,
                        "Failed — not enough space (needs about "
                                + TimerModel.sizeText(TimerModel.neededBytes(
                                        TimerModel.DEFAULT_MBPS, t))
                                + ", phone has "
                                + TimerModel.sizeText(free) + " free)");
                return;
            }

            // --- metadata first, so the shelf knows it from birth.
            ChannelStore.putRecordMeta(ctx, t.channelUrl, t.channelName);
            MemoryFiles.RecordingMeta meta = new MemoryFiles.RecordingMeta();
            meta.id = t.channelUrl;
            meta.title = t.title.isEmpty() ? t.channelName : t.title;
            meta.channel = t.channelName;
            meta.airedStartMs = t.startMs;
            meta.airedStopMs = t.stopMs;
            meta.origin = "timer";
            meta.state = "";
            RecordingsMeta.upsert(ctx, meta);

            // --- start the capture.
            boolean ok = Downloads.start(ctx, t.channelUrl, guessHls(t.channelUrl));
            if (!ok && !Downloads.isRecording(ctx, t.channelUrl)) {
                finish(t, TimerModel.State.FAILED_REFUSED,
                        "Failed — the source refused the recording");
                return;
            }
            t.state = TimerModel.State.RECORDING;
            t.lateJoin = lateJoin;
            TimerStore.update(ctx, t);
            notifyText(t, "● Recording");

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "sandy:timer-rec");
                wakeLock.acquire(Math.max(60_000L, t.untilMs() - now + 120_000L));
            }

            // --- watchdog: sleep to the padded end; bail early on space.
            boolean spaceEarly = false;
            while (!stopped && System.currentTimeMillis() < t.untilMs()) {
                try { Thread.sleep(30_000L); } catch (InterruptedException e) {
                    break;
                }
                if (freeBytes(ctx) < SPACE_FLOOR_BYTES) {
                    spaceEarly = true;
                    break;
                }
            }

            // --- stop, settle, assemble the one file.
            Downloads.stopKeep(ctx, t.channelUrl);
            Download d = settle(ctx, t.channelUrl);
            boolean caught = false;
            if (d != null && d.getBytesDownloaded() > 0
                    && d.state != Download.STATE_FAILED) {
                try {
                    File f = RecordingExport.export(ctx, d,
                            new File(ctx.getFilesDir(), "recording-export"));
                    caught = f != null && f.isFile() && f.length() > 0;
                } catch (Exception ignored) { }
            }
            if (!caught) {
                boolean refused = d != null && d.state == Download.STATE_FAILED;
                finish(t, refused ? TimerModel.State.FAILED_REFUSED
                                : TimerModel.State.PARTIAL_EARLY,
                        refused ? "Failed — the source refused or the "
                                        + "connection dropped"
                                : "Partial — nothing could be assembled "
                                        + "from what was caught");
                return;
            }
            TimerModel.State end = TimerModel.endState(
                    true, lateJoin, spaceEarly, false);
            finish(t, end, spaceEarly
                    ? "Partial — stopped early, the phone ran out of space"
                    : lateJoin ? "Partial — started late"
                               : "Recorded");
        } catch (Exception e) {
            try {
                finish(t, TimerModel.State.MISSED_WAKE,
                        "Missed — the recording couldn't run ("
                                + e.getClass().getSimpleName() + ")");
            } catch (Exception ignored) { }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(true);
            stopSelf();
        }
    }

    /** Poll the download index until the capture settles out of motion. */
    private Download settle(Context ctx, String url) {
        for (int i = 0; i < 20; i++) {
            Download d = Downloads.get(ctx, url);
            if (d == null) return null;
            if (d.state != Download.STATE_DOWNLOADING
                    && d.state != Download.STATE_QUEUED
                    && d.state != Download.STATE_RESTARTING) return d;
            try { Thread.sleep(3000L); } catch (InterruptedException e) {
                return d;
            }
        }
        return Downloads.get(ctx, url);
    }

    /** Write the end state to the queue AND the recordings file, then say so. */
    private void finish(TimerModel.Timer t, TimerModel.State state, String detail) {
        Context ctx = getApplicationContext();
        t.state = state;
        t.detail = detail;
        TimerStore.update(ctx, t);
        MemoryFiles.RecordingMeta meta = RecordingsMeta.get(ctx, t.channelUrl);
        if (meta != null) {
            meta.state = state.name();
            RecordingsMeta.upsert(ctx, meta);
        }
        notifyText(t, detail);
    }

    private long freeBytes(Context ctx) {
        try {
            return new StatFs(ctx.getFilesDir().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE; // unknown space must not fake a refusal
        }
    }

    /** The player's own HLS guess, same rule (PlayerActivity). */
    static boolean guessHls(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) return true;
        String path = lower.split("\\?")[0];
        return !path.substring(path.lastIndexOf('/') + 1).contains(".");
    }

    // -------------------------------------------------- notifications

    private Notification notification(String title, TimerModel.Timer t) {
        NotificationManager nm = (NotificationManager)
                getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHAN, "Recording timers", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, TimersActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(
                this, ("rec:" + t.id).hashCode(), open, flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHAN)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(t.title.isEmpty() ? t.channelName : t.title)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void notifyText(TimerModel.Timer t, String title) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(notifId(t), notification(title, t));
        } catch (Exception ignored) { }
    }

    @Override
    public void onDestroy() {
        stopped = true;
        super.onDestroy();
    }
}
