package com.talacore.talatv;

import java.util.HashMap;
import java.util.Map;

/**
 * Cache keys for live playback (v0.14.1 — the loop half of the
 * fifteen-second window). The timeshift cache's old rule was:
 * playlists get one-time keys (never served stale), segments
 * get STABLE keys. Stable was the bug's other face: media3's
 * CacheDataSource serves a cached span without ever contacting
 * upstream, and a SimpleCache keeps no notion of freshness —
 * so on a source that RECYCLES segment URLs (the same basename
 * naming brand-new content in the next window, a known
 * janky-restreamer pattern) Sandy re-served her own cached
 * bytes under the new timeline position, and the identical
 * ~15-second window played again, presented as live.
 *
 * The law now: a segment URL names the same content only
 * within one fetch generation of one tune. Keys carry a
 * per-tune session salt, and a per-URL generation counter
 * that increments on EVERY key request — the second fetch of
 * the same address in a tune is keyed differently from the
 * first, so it cannot be answered with the first fetch's
 * bytes. Rewind inside the window costs a re-fetch the source
 * still serves; what the source itself rolled off was already
 * honestly gone (LiveResumeMath says so at the kept place).
 *
 * Pure policy — the counters are an ordinary map the cache
 * owns; the harness pins the key arithmetic.
 */
public final class LiveSegmentKeys {

    private final long sessionSalt;
    private final Map<String, Integer> generations = new HashMap<>();

    public LiveSegmentKeys(long sessionSalt) {
        this.sessionSalt = sessionSalt;
    }

    /** The key string for one address at one generation. */
    public String keyForGeneration(String url, int gen) {
        return "s" + sessionSalt + "g" + gen + "#" + url;
    }

    /**
     * The cache key for one fetch. Playlists (HLS-looking
     * addresses) get a guaranteed one-time key, as before.
     * Segments get salt + generation + address.
     */
    public synchronized String keyFor(String url) {
        if (url == null) return "null#" + System.nanoTime();
        if (LinkHealth.looksHls(url)) {
            return url + "#t" + System.nanoTime();
        }
        Integer g = generations.get(url);
        int gen = g == null ? 0 : g;
        generations.put(url, gen + 1);
        return keyForGeneration(url, gen);
    }

    /** How many times this address has been keyed this tune. */
    public synchronized int generationOf(String url) {
        Integer g = generations.get(url);
        return g == null ? 0 : g;
    }
}
