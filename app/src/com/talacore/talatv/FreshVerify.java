package com.talacore.talatv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-fresh (v0.4.0) — James: "It's gonna resolve and check every
 * time it comes online... things maneuver." On app start, verdicts
 * older than 6 hours quietly re-verify in the background with the
 * deep probe. The queue is ordered the way the household actually
 * watches: FAVORITES first, then channels already carrying verdicts
 * (the surf lists Mom lives in), then never-checked favorites'
 * neighbors by guide order — bounded per launch so the politeness is
 * real, and guarded so it runs at most once per process and never
 * more often than every 30 minutes.
 *
 * Results land in HealthStore like any other probe; the listener (if
 * any) hears about it once, at the end, on the main thread, so a list
 * on screen can quietly re-sort itself.
 */
public final class FreshVerify {

    public static final long STALE_MS = 6L * 3600L * 1000L;
    private static final long MIN_INTERVAL_MS = 30L * 60L * 1000L;
    private static final int MAX_PER_RUN = 250;
    private static final int CONCURRENCY = 6;
    private static final int TIMEOUT_MS = 6000;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    public interface Listener {
        void onFreshDone(int checkedCount);
    }

    private FreshVerify() {}

    /**
     * Starts a background refresh if one is due; returns true when a
     * run was started. Channel supply is a callback so this class
     * stays ignorant of where the guide lives (MainActivity caches it).
     */
    public static boolean maybeRun(Context ctx,
                                   java.util.function.Supplier<List<M3uParser.Channel>> channels,
                                   Listener listener) {
        Context app = ctx.getApplicationContext();
        if (!ChannelStore.getAutoFresh(app)) return false;
        long now = System.currentTimeMillis();
        if (now - ChannelStore.getFreshLastRun(app) < MIN_INTERVAL_MS) return false;
        if (!running.compareAndSet(false, true)) return false;

        new Thread(() -> {
            int checked = 0;
            try {
                List<M3uParser.Channel> all = channels == null ? null : channels.get();
                List<LinkHealth.Target> queue = buildQueue(app, all, now);
                checked = queue.size();
                if (checked > 0) {
                    LinkHealth.probeAll(queue, CONCURRENCY, TIMEOUT_MS,
                            (t, r) -> HealthStore.recordQuiet(app, t.url, r), null);
                    HealthStore.save(app);
                }
                ChannelStore.setFreshLastRun(app, System.currentTimeMillis(), checked);
            } catch (Exception ignored) {
                // background politeness: a failed refresh is silent
            } finally {
                running.set(false);
                if (listener != null) {
                    int n = checked;
                    new Handler(Looper.getMainLooper()).post(() -> listener.onFreshDone(n));
                }
            }
        }, "sandy-fresh-verify").start();
        return true;
    }

    /**
     * The bounded queue, in the Addendum 10(5) priority order
     * ({@link VerifyPriority}): the kept live channel first, then
     * favorites, then the wider shelves — inside a class, verdicts
     * going stale outrank first looks, stalest first. Capped at
     * MAX_PER_RUN; the whole ocean is never re-probed.
     */
    static List<LinkHealth.Target> buildQueue(Context ctx,
                                              List<M3uParser.Channel> channels,
                                              long now) {
        List<LinkHealth.Target> out = new ArrayList<>();
        if (channels == null || channels.isEmpty()) return out;
        Set<String> favs = ChannelStore.getFavorites(ctx);
        ChannelStore.KeptLive kept = ChannelStore.getKeptLive(ctx);
        String keptUrl = kept == null ? "" : kept.url;
        Set<String> seen = new HashSet<>();
        List<VerifyPriority.Entry> entries = new ArrayList<>();
        for (M3uParser.Channel c : channels) {
            if (c == null || !LinkHealth.isRemote(c.url) || !seen.add(c.url)) {
                continue;
            }
            HealthStore.Health h = HealthStore.get(ctx, c.url);
            boolean fav = favs.contains(c.url);
            boolean isKept = c.url.equals(keptUrl);
            int cat = VerifyPriority.category(isKept, fav, false);
            if (h == null) {
                entries.add(new VerifyPriority.Entry(
                        c.url, c.userAgent, cat, 0));
                continue;
            }
            if (now - h.checkedAt < STALE_MS) continue;
            entries.add(new VerifyPriority.Entry(
                    c.url, c.userAgent, cat, h.checkedAt));
        }
        for (VerifyPriority.Entry e : VerifyPriority.order(entries, MAX_PER_RUN)) {
            out.add(new LinkHealth.Target(e.url, e.userAgent));
        }
        return out;
    }
}
