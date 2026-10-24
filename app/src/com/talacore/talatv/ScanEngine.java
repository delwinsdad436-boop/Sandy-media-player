package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * THE CHANNEL SCAN's engine (v0.17.0) — the sweep itself.
 *
 * The inventory is every link Sandy would present from the
 * loaded playlists: Live TV channels and Movies & VOD entries
 * alike (one channel cache backs the guide, the movies shelf,
 * the series shelves and the catalogue wall's availability, so
 * one sweep covers them all — see sandy-0.17.0-NOTES.md for the
 * honest scope of what a verdict proves per class).
 *
 * Each due link is walked by Hydra — never a hand-rolled probe.
 *
 * v0.18.0: the walk is {@link HydraResolver#proveExhaustive} —
 * THE PLAYER'S OWN LADDER, the same exhaustive manner walk the
 * player runs to maintain a playing channel. Up to 0.17.0 the
 * scan used the tune-in walk ({@code prove}), which stops at
 * the first door that merely CONNECTS (playlist answers); the
 * scan then filed LIVE at the playlist tier while the player
 * consumes the segment tier — James's field defect, tiles that
 * "say they're available, but when you go to play them, you
 * can't." Now a connects-only answer never stops the scan's
 * ladder either: every lawful manner is tried (James: "if it
 * doesn't work, search out why and find out how to make it
 * work — legally"), and only a door proven to the segment
 * earns LIVE. See {@link ChannelScan#classify}.
 *
 * Verdicts classify by {@link ChannelScan#classify} (SG-1:
 * terminal = dead on the spot; slow = never dead), land in
 * {@link ScanStore} the moment they are decided, and also flow
 * into HealthStore when the walk's best result agrees with the
 * verdict at the same tier, so badges and surf order tell the
 * same story as the shelf.
 *
 * The sweep runs on its own threads with bounded concurrency
 * ({@link ChannelScan#CONCURRENCY}); the UI never blocks on it.
 * It is PROGRESSIVE: verdicts persist as they land and surfaces
 * poll {@link #progress()} to re-render — a half-finished scan
 * has already told the truth about everything it reached. A
 * non-manual start respects the cadence (fresh verdicts are not
 * re-walked); the Scan button is a manual start and re-tests
 * the whole inventory, which is also how a link that came back
 * to life earns its shelf place again.
 */
public final class ScanEngine {

    private ScanEngine() {}

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final AtomicInteger DONE = new AtomicInteger(0);
    private static final AtomicInteger TOTAL = new AtomicInteger(0);

    public static boolean isRunning() {
        return RUNNING.get();
    }

    /** {done, total} of the current (or last) run. */
    public static int[] progress() {
        return new int[]{DONE.get(), TOTAL.get()};
    }

    /**
     * Starts a sweep if none is running. Returns true when this
     * call started it. Manual = the Scan button (re-tests all);
     * non-manual respects the cadence per link.
     */
    public static boolean start(Context ctx, boolean manual) {
        if (ctx == null) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;
        final Context app = ctx.getApplicationContext();
        new Thread(() -> run(app, manual), "sandy-channel-scan").start();
        return true;
    }

    /**
     * Does the walk's best result agree with the scan verdict AT
     * THE VERDICT'S OWN TIER (v0.18.0)? LIVE is a segment-tier
     * claim, so only a PLAYS result files under it; LISTED is a
     * playlist-tier claim (CONNECTS); DEAD files only a terminal
     * dead result — a slow result NEVER agrees with DEAD (SG-1:
     * slow is not dead, and HealthStore keeps that guard too).
     * UNKNOWN files nothing: no verdict, no health claim.
     */
    public static boolean agreesAtTier(int verdict, LinkHealth.Result r) {
        if (r == null) return false;
        switch (verdict) {
            case ChannelScan.V_LIVE:
                return r.tier == LinkHealth.TIER_PLAYS;
            case ChannelScan.V_LISTED:
                return r.tier == LinkHealth.TIER_CONNECTS;
            case ChannelScan.V_DEAD:
                return r.tier == LinkHealth.TIER_DEAD && !r.slow;
            default:
                return false;
        }
    }

    private static void run(Context app, boolean manual) {
        try {
            long now = System.currentTimeMillis();
            List<M3uParser.Channel> channels = ChannelStore.loadCache(app);
            Map<String, ChannelScan.Rec> recs = ScanStore.load(app);
            // The inventory: every presentable link, once, by url.
            Map<String, String> inventory = new LinkedHashMap<>();
            if (channels != null) {
                for (M3uParser.Channel c : channels) {
                    if (c == null || c.url == null
                            || !LinkHealth.isRemote(c.url)) continue;
                    if (!inventory.containsKey(c.url)) {
                        inventory.put(c.url, SourceFinder.isVodEntry(c)
                                ? ChannelScan.KIND_VOD : ChannelScan.KIND_LIVE);
                    }
                }
            }
            List<String> due = new ArrayList<>();
            for (Map.Entry<String, String> e : inventory.entrySet()) {
                if (ChannelScan.isDue(recs.get(e.getKey()), now, manual)) {
                    due.add(e.getKey());
                }
            }
            TOTAL.set(due.size());
            DONE.set(0);
            if (!due.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(
                        ChannelScan.CONCURRENCY);
                for (final String url : due) {
                    final String kind = inventory.get(url);
                    pool.execute(() -> {
                        try {
                            // v0.18.0: the player's own ladder, walked
                            // exhaustively — a connects-only door does
                            // not stop it; every lawful manner is tried
                            // before any verdict is spoken.
                            HydraResolver.Outcome o =
                                    HydraResolver.proveExhaustive(
                                            app, url, Net.UA, null);
                            int resultTier = o.result == null
                                    ? LinkHealth.TIER_DEAD : o.result.tier;
                            boolean slow = o.result != null && o.result.slow;
                            int verdict = ChannelScan.classify(
                                    o.bestTier, resultTier, slow);
                            ScanStore.record(app, new ChannelScan.Rec(
                                    url, kind, verdict,
                                    System.currentTimeMillis(),
                                    o.result == null ? "" : o.result.detail));
                            if (o.result != null && agreesAtTier(
                                    verdict, o.result)) {
                                // The shelf and the badges tell one story:
                                // file the probe only when it agrees with
                                // the scan verdict it produced, at the
                                // verdict's own tier (v0.18.0).
                                HealthStore.recordQuiet(app, url, o.result);
                            }
                        } catch (Exception ignored) {
                            // One bad link never sinks the sweep; it
                            // simply carries no verdict this run.
                        } finally {
                            DONE.incrementAndGet();
                        }
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(24, TimeUnit.HOURS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                HealthStore.save(app);
            }
            ChannelScan.Summary s = ChannelScan.summarize(
                    ScanStore.load(app), System.currentTimeMillis(), due.size());
            ScanStore.finishRun(app, s);
        } catch (Exception ignored) {
            // The sweep is background politeness: a failed run
            // leaves the previous record standing, untouched.
        } finally {
            RUNNING.set(false);
        }
    }
}
