package com.talacore.talatv;

import android.content.Context;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;

import java.io.File;

/**
 * The live timeshift buffer's disk layer (v0.4.0). Every live fetch
 * runs through a CacheDataSource over a size-capped SimpleCache, so
 * segments the player has pulled once are re-read from the phone, not
 * re-streamed: pause holds your place while the window keeps what it
 * fetched, rewind replays from disk, and a rough link stops mattering
 * the moment the video is local.
 *
 * Two honesty details, both load-bearing:
 *
 * 1. PLAYLISTS ARE NEVER SERVED FROM CACHE. A live manifest is a
 *    moving window; a cached copy would freeze the channel in the
 *    past. The CacheKeyFactory gives playlist-looking URLs a one-time
 *    key (a guaranteed miss; the tiny write is evicted as junk).
 *    SEGMENTS are generation-keyed since v0.14.1 (LiveSegmentKeys):
 *    the old stable keys let a source that recycles segment URLs be
 *    answered with Sandy's own earlier bytes — the same window
 *    replayed as if live. Now a segment address names the same
 *    content only within one fetch generation of one tune.
 *
 * 2. IT IS A BUFFER, NOT A RECORDING — with one family amendment
 *    (v0.5.0, item 8): the buffer is wiped when the channel CHANGES,
 *    but the LAST channel's buffer now survives the player closing,
 *    so the viewer can be offered their place back on reopen (the
 *    place is re-derived honestly by LiveResumeMath — the live edge
 *    moved while they were gone, and whatever rolled past the
 *    window is gone). Only the last channel is kept this way.
 *
 *    And when the kept buffer FILLS, the family's ruling is HOLD,
 *    DON'T SLIDE (Settings can switch it back to Roll): the capture
 *    stops and the beginning is preserved — the fall-asleep case:
 *    "when you wake up, hopefully you caught the whole episode." An
 *    episode longer than the window still loses its tail; hold saves
 *    the front, not the impossible. Hold is implemented as a
 *    no-evictor cache plus the player's fill watch (PlayerActivity
 *    latches the hold and stops capturing through the cache); Roll
 *    keeps the classic LRU slide.
 *
 * The byte cap comes from the minutes setting via TimeshiftMath
 * (nominal 3 Mbps): the cap, and the source's own live window, are
 * the two bounds on how far back — or forward — the buffer reaches.
 * Storage honesty: 60 minutes at the nominal rate is ~1.35 GB.
 */
@UnstableApi
public final class TimeshiftCache {

    private static SimpleCache cache;
    private static long cacheCapBytes = -1;
    private static boolean cacheHold = true;

    private TimeshiftCache() {}

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "timeshift-cache");
    }

    private static synchronized SimpleCache cache(Context ctx, long capBytes) {
        boolean hold = ChannelStore.getBufferFullHold(ctx);
        if (cache != null && cacheCapBytes == capBytes && cacheHold == hold) return cache;
        releaseLocked();
        File d = dir(ctx);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        // Hold: no evictor — the beginning is sacred; the PLAYER stops
        // the capture when the cap is reached (see keptFull). Roll:
        // LRU evictor slides the window as before.
        cache = hold
                ? new SimpleCache(d, new NoOpCacheEvictor(),
                        new StandaloneDatabaseProvider(ctx))
                : new SimpleCache(d, new LeastRecentlyUsedCacheEvictor(capBytes),
                        new StandaloneDatabaseProvider(ctx));
        cacheCapBytes = capBytes;
        cacheHold = hold;
        return cache;
    }

    private static void releaseLocked() {
        if (cache != null) {
            try { cache.release(); } catch (Exception ignored) {}
            cache = null;
            cacheCapBytes = -1;
        }
        keys = null;   // a released cache ends the tune (key salt dies with it)
    }

    /** Bytes the kept buffer currently holds (-1 when no cache is live). */
    public static synchronized long cacheSpaceBytes(Context ctx) {
        if (cache == null) return -1;
        try { return cache.getCacheSpace(); } catch (Exception e) { return -1; }
    }

    /**
     * Hold-mode fill test: the kept buffer has reached its cap, so
     * capture must stop and the front must be preserved. A 1% margin
     * keeps the latch from firing on the cap's own rounding.
     */
    public static synchronized boolean keptFull(Context ctx, int capMinutes) {
        if (cache == null) return false;
        long cap = TimeshiftMath.byteCapForMinutes(capMinutes);
        long space = cacheSpaceBytes(ctx);
        return space >= 0 && space >= (long) (cap * 0.99);
    }

    /**
     * A data-source factory for live playback: HTTP upstream (honoring
     * the channel's User-Agent when one was requested), reads/writes
     * through the timeshift cache.
     */
    public static synchronized DataSource.Factory liveFactory(Context ctx,
                                                              String userAgent,
                                                              int capMinutes) {
        Cache c = cache(ctx.getApplicationContext(),
                TimeshiftMath.byteCapForMinutes(capMinutes));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent == null || userAgent.isEmpty()
                        ? "Sandy/0.14.1" : userAgent)
                // media3's defaults (8 s/8 s), stated on purpose
                // since v0.14.1: a segment leg that hangs longer
                // than this is a dead leg, and the continuity
                // machinery — not a long socket wait — owns what
                // happens next.
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setAllowCrossProtocolRedirects(true);
        CacheDataSource.Factory f = new CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setCacheKeyFactory(KEY_FACTORY);
        return f;
    }

    // The key law lives in LiveSegmentKeys (v0.14.1): playlists
    // get one-time keys (never a cache hit); segments get a
    // per-tune salt + a per-address generation, so a fetch can
    // never be answered with an earlier fetch's bytes — the
    // stable-key loop is closed. The generation counter's life
    // is the cache's: a wipe (channel change) starts a new tune.
    private static LiveSegmentKeys keys;

    private static synchronized LiveSegmentKeys keys() {
        if (keys == null) keys = new LiveSegmentKeys(System.nanoTime());
        return keys;
    }

    private static final CacheKeyFactory KEY_FACTORY = dataSpec -> {
        String url = dataSpec.uri.toString();
        LiveSegmentKeys k = keys();
        int before = k.generationOf(url);
        String key = k.keyFor(url);
        if (before >= 1 && cache != null) {
            // This address is being fetched AGAIN in this tune:
            // the previous generation's span is unreachable from
            // here on (that is the point), so don't let it sit in
            // the cache as weight — in Hold mode nothing else
            // would ever evict it.
            try {
                cache.removeResource(k.keyForGeneration(url, before - 1));
            } catch (Throwable ignored) {
                // a span in use stays; harmless either way
            }
        }
        return key;
    };

    /**
     * Drop the whole buffer — called on CHANNEL CHANGE only since
     * v0.5.0. Closing the player no longer wipes: the last channel's
     * buffer is the kept place (see the class doc, point 2).
     */
    public static synchronized void wipe(Context ctx) {
        releaseLocked();
        deleteTree(dir(ctx.getApplicationContext()));
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
