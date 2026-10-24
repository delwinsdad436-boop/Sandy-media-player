package com.talacore.talatv;

import android.content.Context;
import android.net.Uri;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.offline.Download;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording → one file (v0.7.0): the bridge BINDING-VERDICT §8
 * named as this build's first job.
 *
 * A recording lives in the recordings cache as spans the
 * DownloadManager wrote — for an HLS source, a media playlist
 * span plus one span per segment, each keyed by its own URI
 * (media3's default cache key is the URI; verified against the
 * staged 1.4.1 classes). The cooker needs a single openable
 * file, so this class assembles one:
 *
 *   HLS recording  -> read the playlist span, walk its segments
 *                     in playlist order, concatenate their bytes
 *                     into one MPEG-TS file. (Concatenating TS
 *                     segments is what an HLS player itself does
 *                     logically; the packets are self-syncing.)
 *   anything else  -> the recording IS one resource: copy its
 *                     cached bytes into one file.
 *
 * Reads go through a cache-ONLY CacheDataSource — no upstream
 * factory, BLOCK_ON_CACHE set — so a hole in the recording is a
 * loud failure here, never a silent network re-fetch. Encrypted
 * playlists (EXT-X-KEY) refuse plainly: their segments are
 * ciphertext no decoder on this phone can open as-is.
 *
 * The playlist walking and the byte concatenation are pure and
 * harness-pinned; only the span reads need Android.
 */
public final class RecordingExport {

    public static final class ExportException extends Exception {
        public ExportException(String msg) { super(msg); }
    }

    private RecordingExport() {}

    // ------------------------------------------------ pure: names

    /** Stable export base name for a recording request id (a URL). */
    public static String exportBaseFor(String requestId) {
        String safe = (requestId == null ? "recording" : requestId)
                .replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.length() > 60) {
            safe = safe.substring(0, 44) + "-"
                    + Integer.toHexString(requestId.hashCode());
        }
        return safe;
    }

    /** File extension for an assembled export of the given kind. */
    public static String extensionFor(boolean hls, String sourceUri) {
        if (hls) return ".ts";
        String s = sourceUri == null ? "" : sourceUri.toLowerCase();
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        int dot = s.lastIndexOf('.');
        if (dot > slash && dot >= 0) {
            String ext = s.substring(dot);
            if (ext.length() <= 6 && ext.matches("\\.[a-z0-9]+")) return ext;
        }
        return ".bin";
    }

    // ------------------------------------------------ pure: HLS

    /** The playlist's segment (or variant) URIs, in order. */
    public static List<String> playlistEntries(String playlistText) {
        List<String> out = new ArrayList<>();
        if (playlistText == null) return out;
        for (String raw : playlistText.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
        }
        return out;
    }

    /** A master playlist points at variant playlists, not media. */
    public static boolean isMasterPlaylist(String playlistText) {
        return playlistText != null && playlistText.contains("#EXT-X-STREAM-INF");
    }

    /** The encryption method of a media playlist, or null when clear. */
    public static String encryptionMethod(String playlistText) {
        if (playlistText == null) return null;
        for (String raw : playlistText.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY:")) {
                int m = line.indexOf("METHOD=");
                if (m >= 0) {
                    String rest = line.substring(m + 7);
                    int cut = rest.indexOf(',');
                    if (cut >= 0) rest = rest.substring(0, cut);
                    String method = rest.trim();
                    return method.equalsIgnoreCase("NONE") ? null : method;
                }
                return "AES-128";
            }
        }
        return null;
    }

    /** Resolve a possibly-relative playlist entry against its base. */
    public static String resolveUri(String base, String entry) {
        if (entry == null) return null;
        if (entry.contains("://") || entry.startsWith("data:")) return entry;
        try {
            return new java.net.URI(base).resolve(entry).toString();
        } catch (Exception e) {
            return entry;
        }
    }

    // ------------------------------------------------ pure: bytes

    /** Concatenate files, in order, into out. Returns bytes written. */
    public static long concatFiles(List<File> parts, File out) throws IOException {
        long total = 0;
        byte[] buf = new byte[64 * 1024];
        try (OutputStream os = new FileOutputStream(out)) {
            for (File part : parts) {
                try (InputStream is = new FileInputStream(part)) {
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                }
            }
        }
        return total;
    }

    // ------------------------------------------------ glue: spans

    private static CacheDataSource.Factory cacheOnlyFactory(Context ctx)
            throws ExportException {
        androidx.media3.datasource.cache.SimpleCache cache = Downloads.cache(ctx);
        if (cache == null) {
            throw new ExportException("the recordings store isn't awake");
        }
        return new CacheDataSource.Factory()
                .setCache(cache)
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE);
    }

    // v0.8.0 fix (found by code reading during the 0.7.0 forensics):
    // 0.7.0 created ONE CacheDataSource for the whole export and
    // closed it in a finally after EVERY span, then reused the closed
    // source for the next span — open-after-close on a stateful
    // media3 class. Each span now gets a fresh data source from the
    // factory, opened and closed inside its own read.

    private static byte[] readSpan(CacheDataSource.Factory factory, String uri)
            throws ExportException {
        DataSource ds = factory.createDataSource();
        DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(uri)).build();
        try {
            long len = ds.open(spec);
            if (len < 0) len = 0;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(
                    (int) Math.min(Math.max(len, 8192), 64 * 1024 * 1024));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = ds.read(buf, 0, buf.length)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new ExportException("a piece of the recording is missing "
                    + "from the cache (" + shortUri(uri) + "): "
                    + (e.getMessage() == null ? e.getClass().getSimpleName()
                       : e.getMessage()));
        } finally {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    private static String shortUri(String uri) {
        if (uri == null) return "?";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 && slash < uri.length() - 1
                ? uri.substring(slash + 1) : uri;
    }

    private static void writeSpanTo(CacheDataSource.Factory factory, String uri,
                                    OutputStream os, byte[] buf)
            throws ExportException {
        DataSource ds = factory.createDataSource();
        DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(uri)).build();
        try {
            ds.open(spec);
            int n;
            while ((n = ds.read(buf, 0, buf.length)) > 0) os.write(buf, 0, n);
        } catch (Exception e) {
            throw new ExportException("a piece of the recording is missing "
                    + "from the cache (" + shortUri(uri) + "): "
                    + (e.getMessage() == null ? e.getClass().getSimpleName()
                       : e.getMessage()));
        } finally {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Assemble the recording into one file inside outDir and
     * return it. The export is cached: an up-to-date export for
     * the recording's current byte count is reused, so a cook
     * after the first pays only for the cook itself.
     */
    public static File export(Context ctx, Download d, File outDir)
            throws ExportException {
        try {
            return exportInner(ctx, d, outDir);
        } catch (ExportException e) {
            throw e;
        } catch (IOException e) {
            throw new ExportException("assembling the recording failed: "
                    + (e.getMessage() == null
                       ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static File exportInner(Context ctx, Download d, File outDir)
            throws ExportException, IOException {
        if (d == null) throw new ExportException("no recording");
        if (d.state == Download.STATE_DOWNLOADING
                || d.state == Download.STATE_QUEUED
                || d.state == Download.STATE_RESTARTING) {
            throw new ExportException("that recording is still being made — "
                    + "stop it first, then cook");
        }
        if (d.getBytesDownloaded() <= 0) {
            throw new ExportException("that recording caught nothing to cook");
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new ExportException("no room to assemble the recording");
        }
        String id = d.request.id;
        String uri = d.request.uri.toString();
        boolean looksHls = uri.toLowerCase().contains(".m3u8")
                || MimeTypesIsHls(d);
        String base = exportBaseFor(id);
        File stamp = new File(outDir, base + ".stamp");
        String wantStamp = d.getBytesDownloaded() + ":" + d.startTimeMs;
        File tsFile = new File(outDir, base + ".ts");

        CacheDataSource.Factory dsFactory = cacheOnlyFactory(ctx);
        try {
            if (looksHls) {
                if (tsFile.isFile() && tsFile.length() > 0
                        && wantStamp.equals(readStamp(stamp))) {
                    return tsFile;
                }
                byte[] plBytes = readSpan(dsFactory, uri);
                String playlist = new String(plBytes, "UTF-8");
                if (!playlist.contains("#EXTM3U")) {
                    throw new ExportException("the recording's playlist "
                            + "couldn't be read back");
                }
                String mediaPlaylistUri = uri;
                if (isMasterPlaylist(playlist)) {
                    List<String> variants = playlistEntries(playlist);
                    if (variants.isEmpty()) {
                        throw new ExportException("the recording's master "
                                + "playlist names no variants");
                    }
                    mediaPlaylistUri = resolveUri(uri, variants.get(0));
                    playlist = new String(readSpan(dsFactory, mediaPlaylistUri), "UTF-8");
                    if (isMasterPlaylist(playlist)) {
                        throw new ExportException("the recording's playlist "
                                + "nests too deep to assemble");
                    }
                }
                String method = encryptionMethod(playlist);
                if (method != null) {
                    throw new ExportException("that recording is scrambled ("
                            + method + ") — its pieces are locked, and this "
                            + "phone can't cook from them");
                }
                List<String> segments = playlistEntries(playlist);
                if (segments.isEmpty()) {
                    throw new ExportException("the recording's playlist "
                            + "names no pieces");
                }
                File tmp = new File(outDir, base + ".ts.part");
                byte[] buf = new byte[64 * 1024];
                try (OutputStream os = new FileOutputStream(tmp)) {
                    for (String seg : segments) {
                        writeSpanTo(dsFactory, resolveUri(mediaPlaylistUri, seg), os, buf);
                    }
                }
                if (tmp.length() <= 0) {
                    tmp.delete();
                    throw new ExportException("the assembled recording is empty");
                }
                if (!tmp.renameTo(tsFile)) {
                    throw new ExportException("the assembled recording "
                            + "wouldn't settle into place");
                }
                writeStamp(stamp, wantStamp);
                return tsFile;
            }
            // One-resource recording: copy its cached bytes whole.
            String ext = extensionFor(false, uri);
            File out = new File(outDir, base + ext);
            if (out.isFile() && out.length() == d.getBytesDownloaded()
                    && wantStamp.equals(readStamp(stamp))) {
                return out;
            }
            File tmp = new File(outDir, base + ext + ".part");
            try (OutputStream os = new FileOutputStream(tmp)) {
                writeSpanTo(dsFactory, uri, os, new byte[64 * 1024]);
            }
            if (tmp.length() <= 0) {
                tmp.delete();
                throw new ExportException("the assembled recording is empty");
            }
            if (!tmp.renameTo(out)) {
                throw new ExportException("the assembled recording "
                        + "wouldn't settle into place");
            }
            writeStamp(stamp, wantStamp);
            return out;
        } finally {
            // the CacheDataSource itself is closed per-read above;
            // nothing else to release (the factory's source is a
            // lightweight reader over the shared cache)
        }
    }

    private static boolean MimeTypesIsHls(Download d) {
        try {
            return d.request.mimeType != null
                    && d.request.mimeType.toLowerCase().contains("mpegurl");
        } catch (Exception e) {
            return false;
        }
    }

    private static String readStamp(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeStamp(File f, String s) {
        try {
            java.nio.file.Files.write(f.toPath(), s.getBytes("UTF-8"));
        } catch (Exception ignored) {}
    }
}
