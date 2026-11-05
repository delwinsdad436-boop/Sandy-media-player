package com.talacore.talatv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *
 * STAGED WARM-UP (fixpass-0200, item 14 — James's design): a total
 * open-time sweep is NOT the shape. Proof arrives in stages:
 *   (a) HER CHANNELS FIRST — {@link #runFavoritesFirst} deep-probes
 *       every stale/unproven favorite when Live TV opens, BEFORE
 *       and independent of the general queue below: it is not
 *       bounded by MAX_PER_RUN and not gated by MIN_INTERVAL_MS
 *       (favorites are few and are the daily door), but it IS
 *       polite — the same CONCURRENCY and TIMEOUT_MS, the same
 *       auto-fresh opt-out ({@code ChannelStore.getAutoFresh}),
 *       at most one pass in flight, and at most once per screen
 *       open (the caller's guard in MainActivity).
 *   (b) PROVE-ON-VIEW — {@link #runSectionProof} deep-probes a
 *       bounded screenful of one display section's stale/unproven
 *       members as the viewer enters that section.
 *   (c) THE SLOW BACKGROUND REFRESH — {@link #maybeRun} itself,
 *       unchanged: bounded, interval-gated, priority-ordered.
 *   (d) Tap-time exhaustive walk + player healing stays the final
 *       word (PlayerActivity; untouched by this class).
 *
 * Every pass probes through the ONE shared {@link #PROBE_LOCK}, so
 * no two passes ever probe concurrently — a warm pass submitted
 * while the general refresh is running queues behind it on the
 * single warm thread instead of being dropped, and the general
 * refresh likewise waits its turn behind a warm pass. The probe
 * itself is LinkHealth's deep probe (probeAll / probeOnce): for
 * HLS it walks playlist -> variant -> media playlist -> first
 * segment and earns TIER_PLAYS only when actual media bytes
 * answer. That proves the segment door answered just now; it is
 * NOT ScanEngine's in-player proof (the player's own exhaustive
 * ladder, walked while actually playing), and no stored verdict
 * here claims more than the probe earned.
 */
public final class FreshVerify {

    public static final long STALE_MS = 6L * 3600L * 1000L;
    private static final long MIN_INTERVAL_MS = 30L * 60L * 1000L;
    private static final int MAX_PER_RUN = 250;
    private static final int CONCURRENCY = 6;
    private static final int TIMEOUT_MS = 6000;

    private static final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Item 14(b) screenful bound — the worker's pick, stated
     * plainly: 24 channels is roughly two screens of list rows,
     * so entering a section proves what she can see plus the
     * screen she is about to reach, and no more.
     */
    public static final int SECTION_PROOF_CAP = 24;

    /**
     * The one probe lock every pass in this class shares (item
     * 14): probeAll executions serialize on it, so warm passes and
     * the general refresh never hammer the network — or the same
     * doors — at the same time.
     */
    private static final Object PROBE_LOCK = new Object();

    /**
     * Warm passes (favorites-first, prove-on-view) run on this
     * single thread, in submission order: favorites are submitted
     * first at open, section passes follow as sections are
     * entered. Each pass re-filters for staleness when it actually
     * runs, so a pass queued behind another finds fresh verdicts
     * already filed and probes nothing.
     */
    private static final ExecutorService warmExec =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sandy-warm-proof");
                t.setDaemon(true);
                return t;
            });

    /** At most one favorites-first pass in flight, ever. */
    private static final AtomicBoolean favoritesInFlight =
            new AtomicBoolean(false);

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
                    // Item 14: the probe itself holds the shared
                    // lock — if a warm pass is mid-probe this run
                    // waits its turn rather than probing alongside
                    // it. Caps, cadence and queue order above are
                    // exactly as they stood.
                    synchronized (PROBE_LOCK) {
                        LinkHealth.probeAll(queue, CONCURRENCY, TIMEOUT_MS,
                                (t, r) -> HealthStore.recordQuiet(app, t.url, r), null);
                        HealthStore.save(app);
                    }
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

    // ------------------------------------------------ staged warm-up

    /**
     * (a) HER CHANNELS FIRST (item 14): deep-probe every favorite
     * whose stored verdict is missing or stale (older than
     * {@link #STALE_MS}), at the standing CONCURRENCY / TIMEOUT_MS,
     * recorded exactly the way the general refresh records
     * (HealthStore.recordQuiet per result, then HealthStore.save).
     *
     * Deliberately NOT governed by the general refresh's bounds:
     * no MAX_PER_RUN cap (every due favorite is probed — favorites
     * are the household's chosen few, not the ocean) and no
     * MIN_INTERVAL_MS gate (a screen open an hour after the last
     * general run still gets its favorites proven). The standing
     * auto-fresh opt-out still governs — it is the household's one
     * switch for background re-verification. At most one favorites
     * pass is in flight; once-per-screen-open is the caller's
     * guard (MainActivity). The pass queues on the warm thread and
     * probes under {@link #PROBE_LOCK}, so it never runs
     * concurrently with a general refresh — and if a general
     * refresh started at boot is still probing, this pass waits
     * behind it and re-filters when it runs, so it is queued, not
     * starved or lost.
     *
     * Returns true when a pass was queued; the listener (if any)
     * then ALWAYS fires once on the main thread with the number
     * actually probed (0 when nothing was due by run time).
     */
    public static boolean runFavoritesFirst(Context ctx,
            java.util.function.Supplier<List<M3uParser.Channel>> channels,
            Listener listener) {
        Context app = ctx.getApplicationContext();
        if (!ChannelStore.getAutoFresh(app)) return false;
        if (!favoritesInFlight.compareAndSet(false, true)) return false;
        warmExec.execute(() -> {
            int checked = 0;
            try {
                synchronized (PROBE_LOCK) {
                    List<M3uParser.Channel> all =
                            channels == null ? null : channels.get();
                    Set<String> favs = ChannelStore.getFavorites(app);
                    List<LinkHealth.Target> queue = dueTargets(app, all,
                            favs, System.currentTimeMillis(), 0);
                    checked = queue.size();
                    if (checked > 0) {
                        LinkHealth.probeAll(queue, CONCURRENCY, TIMEOUT_MS,
                                (t, r) -> HealthStore.recordQuiet(app, t.url, r),
                                null);
                        HealthStore.save(app);
                    }
                }
            } catch (Exception ignored) {
                // background politeness: a failed warm-up is silent
            } finally {
                favoritesInFlight.set(false);
                if (listener != null) {
                    int n = checked;
                    new Handler(Looper.getMainLooper()).post(
                            () -> listener.onFreshDone(n));
                }
            }
        });
        return true;
    }

    /**
     * (b) PROVE-ON-VIEW (item 14): deep-probe one display section's
     * stale/unproven members, bounded at {@link #SECTION_PROOF_CAP}
     * per pass (screenful-scale). Same probe, same recording, same
     * shared lock and warm thread as the favorites pass, so section
     * passes serialize behind the favorites pass and each other;
     * staleness is re-filtered at run time, so a section whose
     * members were proven by an earlier pass probes nothing.
     * Returns true when a pass was queued; the listener contract
     * matches {@link #runFavoritesFirst}.
     */
    public static boolean runSectionProof(Context ctx,
            java.util.function.Supplier<List<M3uParser.Channel>> members,
            Listener listener) {
        Context app = ctx.getApplicationContext();
        if (!ChannelStore.getAutoFresh(app)) return false;
        warmExec.execute(() -> {
            int checked = 0;
            try {
                synchronized (PROBE_LOCK) {
                    List<M3uParser.Channel> section =
                            members == null ? null : members.get();
                    List<LinkHealth.Target> queue = dueTargets(app, section,
                            null, System.currentTimeMillis(),
                            SECTION_PROOF_CAP);
                    checked = queue.size();
                    if (checked > 0) {
                        LinkHealth.probeAll(queue, CONCURRENCY, TIMEOUT_MS,
                                (t, r) -> HealthStore.recordQuiet(app, t.url, r),
                                null);
                        HealthStore.save(app);
                    }
                }
            } catch (Exception ignored) {
                // background politeness: a failed warm-up is silent
            } finally {
                if (listener != null) {
                    int n = checked;
                    new Handler(Looper.getMainLooper()).post(
                            () -> listener.onFreshDone(n));
                }
            }
        });
        return true;
    }

    /** Context wrapper: due targets read against the filed verdicts. */
    private static List<LinkHealth.Target> dueTargets(Context ctx,
            List<M3uParser.Channel> channels, Set<String> onlyUrls,
            long now, int cap) {
        Map<String, Long> checkedAt = new HashMap<>();
        for (Map.Entry<String, HealthStore.Health> e
                : HealthStore.load(ctx).entrySet()) {
            if (e.getValue() != null) {
                checkedAt.put(e.getKey(), e.getValue().checkedAt);
            }
        }
        return dueTargets(channels, checkedAt, onlyUrls, now, cap);
    }

    /**
     * The due-target selection, pure (no Android, no store) so the
     * JVM driver can pin it: channels in incoming order,
     * de-duplicated by URL, remote links only, restricted to
     * {@code onlyUrls} when that set is non-null (the favorites
     * pass), keeping only channels whose verdict is missing from
     * {@code checkedAtByUrl} or stale at {@code now} (age >=
     * {@link #STALE_MS} — the same boundary buildQueue uses).
     * {@code cap} <= 0 means uncapped (the favorites pass);
     * otherwise the result stops at {@code cap} targets.
     */
    static List<LinkHealth.Target> dueTargets(
            List<M3uParser.Channel> channels,
            Map<String, Long> checkedAtByUrl, Set<String> onlyUrls,
            long now, int cap) {
        List<LinkHealth.Target> out = new ArrayList<>();
        if (channels == null || channels.isEmpty()) return out;
        Set<String> seen = new HashSet<>();
        for (M3uParser.Channel c : channels) {
            if (c == null || !LinkHealth.isRemote(c.url)
                    || !seen.add(c.url)) {
                continue;
            }
            if (onlyUrls != null && !onlyUrls.contains(c.url)) continue;
            Long checked = checkedAtByUrl == null
                    ? null : checkedAtByUrl.get(c.url);
            if (checked != null && now - checked < STALE_MS) continue;
            out.add(new LinkHealth.Target(c.url, c.userAgent));
            if (cap > 0 && out.size() >= cap) break;
        }
        return out;
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
