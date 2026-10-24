package com.talacore.talatv;

import android.content.Context;
import android.net.Uri;

import androidx.media3.common.MimeTypes;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;

import java.io.File;
import java.util.concurrent.Executors;

/**
 * Record what's playing (v0.5.0, item 3 — Delwin's #3, bounded by
 * James: "record what's playing", no scheduled DVR).
 *
 * Uses media3's own DownloadManager to pull the live stream being
 * watched into a recordings cache — for an HLS source that means the
 * downloader follows the live playlist and keeps fetching segments
 * until told to stop, so what lands on the phone is the part of the
 * broadcast that aired while you were recording. Stopping keeps what
 * was caught ("Stop & keep"); the segments are real files in the
 * recordings cache and the Recordings shelf plays them through the
 * player like anything else.
 *
 * Honest edges, by design:
 *  - A source that refuses (403, DRM, a non-HLS protocol the
 *    downloader can't take) fails the download, and the shelf shows
 *    the failure plainly instead of a fake recording.
 *  - One fixed user agent rides on recordings ("Sandy/0.14.1") —
 *    media3's stock downloaders don't take per-request headers, so a
 *    stream that gates on a special user agent can refuse; the
 *    failure is shown, not hidden.
 *  - Recordings run while Sandy is open; closing the app pauses
 *    them (the DownloadManager resumes them next launch).
 *
 * The recordings cache is a separate directory from the timeshift
 * buffer and is never evicted silently — only the viewer deletes a
 * recording.
 */
public final class Downloads {

    private static volatile DownloadManager manager;
    private static volatile SimpleCache cache;
    private static volatile StandaloneDatabaseProvider dbProvider;

    private Downloads() {}

    public static synchronized void init(Context ctx) {
        if (manager != null) return;
        Context app = ctx.getApplicationContext();
        try {
            dbProvider = new StandaloneDatabaseProvider(app);
            File dir = new File(app.getFilesDir(), "recordings-cache");
            cache = new SimpleCache(dir, new NoOpCacheEvictor(), dbProvider);
            CacheDataSource.Factory cacheDs = new CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(
                            new DefaultHttpDataSource.Factory()
                                    .setUserAgent("Sandy/0.14.1")
                                    .setAllowCrossProtocolRedirects(true))
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            // (Context, DatabaseProvider, Cache, DataSource.Factory,
            // Executor) — media3 builds its downloader factory from
            // the cache data source internally (verified by javap
            // against the staged 1.4.1 classes).
            manager = new DownloadManager(app, dbProvider, cache, cacheDs,
                    Executors.newCachedThreadPool());
        } catch (Throwable t) {
            // Hardened in 0.8.0: store construction must fail closed
            // and quiet (recordings show their refused state), never
            // take the app down — and the failure leaves evidence.
            CrashGuard.log(app, "Downloads.init", t);
            manager = null;
            cache = null;
        }
    }

    public static DownloadManager manager(Context ctx) {
        init(ctx);
        return manager;
    }

    public static SimpleCache cache(Context ctx) {
        init(ctx);
        return cache;
    }

    /** Data source factory for playing a recording back (cache-first). */
    public static CacheDataSource.Factory playbackFactory(Context ctx) {
        init(ctx);
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(
                        new DefaultHttpDataSource.Factory()
                                .setUserAgent("Sandy/0.14.1")
                                .setAllowCrossProtocolRedirects(true))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    public static boolean isRecording(Context ctx, String url) {
        Download d = get(ctx, url);
        return d != null && (d.state == Download.STATE_DOWNLOADING
                || d.state == Download.STATE_QUEUED
                || d.state == Download.STATE_RESTARTING);
    }

    public static Download get(Context ctx, String url) {
        DownloadManager m = manager(ctx);
        if (m == null) return null;
        try {
            return m.getDownloadIndex().getDownload(url);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Start recording a live url. Returns false when refused early. */
    public static boolean start(Context ctx, String url, boolean hls) {
        DownloadManager m = manager(ctx);
        if (m == null) return false;
        try {
            DownloadRequest.Builder b = new DownloadRequest.Builder(url, Uri.parse(url));
            if (hls) b.setMimeType(MimeTypes.APPLICATION_M3U8);
            m.addDownload(b.build());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Stop & keep what was caught. */
    public static void stopKeep(Context ctx, String url) {
        DownloadManager m = manager(ctx);
        if (m == null) return;
        try { m.setStopReason(url, 1); } catch (Throwable ignored) {}
    }

    /** Resume a stopped recording (if the source still offers it). */
    public static void resume(Context ctx, String url) {
        DownloadManager m = manager(ctx);
        if (m == null) return;
        try { m.setStopReason(url, Download.STOP_REASON_NONE); } catch (Throwable ignored) {}
    }

    /** Delete a recording and its bytes. */
    public static void delete(Context ctx, String url) {
        DownloadManager m = manager(ctx);
        if (m == null) return;
        try { m.removeDownload(url); } catch (Throwable ignored) {}
        ChannelStore.removeRecordMeta(ctx, url);
    }

    /** Plain-language state line for the Recordings shelf. */
    public static String stateLine(Download d) {
        if (d == null) return "";
        long mb = Math.max(0, d.getBytesDownloaded()) / (1024 * 1024);
        switch (d.state) {
            case Download.STATE_DOWNLOADING:
                return "● Recording… " + mb + " MB so far";
            case Download.STATE_QUEUED:
            case Download.STATE_RESTARTING:
                return "● Recording — starting…";
            case Download.STATE_COMPLETED:
                return "Recorded · " + mb + " MB";
            case Download.STATE_STOPPED:
                return "Stopped — " + mb + " MB kept · tap to play";
            case Download.STATE_FAILED:
                return "Failed — the source refused or the connection dropped ("
                        + mb + " MB caught)";
            case Download.STATE_REMOVING:
                return "Deleting…";
            default:
                return mb + " MB";
        }
    }
}
