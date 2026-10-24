package com.talacore.talatv;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The Active Pinger (v0.14.1, Addendum 3 + James's naming).
 * While a live stream plays, the buffer is managed ACTIVELY:
 * the pinger knocks on the playing source's door every
 * {@link #PING_INTERVAL_MS} — a light playlist fetch plus the
 * first bytes of the window's first segment — so a dead head
 * is detected INSIDE the buffer runway instead of at
 * starvation, and a frozen window is caught while cover
 * remains. Its evidence feeds three places:
 *
 *   early warning — a playlist terminally gone twice running,
 *   or a window that stopped advancing, starts the player's
 *   maintenance (the Reconnect Doctrine) while the runway is
 *   still full;
 *   door hygiene — a host whose door terminally dies loses its
 *   Hydra learned preference (Net.forgetLearned/HydraStore);
 *   the playback record — every classification-relevant sample
 *   lands in PlaybackRecord, separable by family.
 *
 * What a ping IS, per source class: for HLS (the live shape),
 * the media playlist itself (following the variant hop when
 * the door serves a multivariant list) + a 2 KB range read of
 * its first segment, hashed — the media sequence and the hash
 * together say whether the window is truly advancing, standing
 * still, or recycling names. For a direct media address, the
 * ping is a 2 KB range read of the address itself. A ping
 * never touches the player's own connections; it is a knock,
 * not a second stream.
 */
public final class ActivePinger {

    /** Cadence: a knock every ten seconds while live plays. */
    public static final long PING_INTERVAL_MS = 10_000;
    /** One knock's patience — well inside the runway. */
    public static final int PING_TIMEOUT_MS = 4_000;
    /** Stuck span never judged under fifteen seconds… */
    public static final long STUCK_FLOOR_MS = 15_000;
    /** …and otherwise media3's own stuck math: 3.5 × target. */
    public static final double STUCK_COEFFICIENT = 3.5;

    public interface Listener {
        /** Every sample, good or bad (throttle lives upstream). */
        void onPingSample(Sample s);
        /** The playlist door is terminally gone, twice running. */
        void onPlaylistTerminal(Sample s);
        /** The window stopped advancing across the stuck span. */
        void onWindowStuck(Sample s);
    }

    /** One knock's findings. */
    public static final class Sample {
        public long atMs;
        public String playlistUrl = "";
        public int playlistHttp;          // 0 = no answer at all
        public String failureKind = "";   // FailureWords key when failed
        public boolean ok;                // the playlist was read
        public long mediaSequence = -1;
        public long targetDurationMs = -1;
        public String segmentUrl = "";
        public String segmentHost = "";
        public int segmentHttp;           // 0 = not attempted / silent
        public long segmentCrc = -1;

        /** One-line evidence for the record. */
        public String describe() {
            if (!ok) {
                return "playlist http=" + playlistHttp
                        + (failureKind.isEmpty() ? "" : "/" + failureKind);
            }
            return "playlist http=" + playlistHttp
                    + " seq=" + mediaSequence
                    + " segment http=" + segmentHttp
                    + " crc=" + segmentCrc;
        }
    }

    private final String url;
    private final String userAgent;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private long generation;

    public ActivePinger(String url, String userAgent, Listener listener) {
        this.url = url;
        this.userAgent = userAgent;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        final long gen = ++generation;
        thread = new Thread(() -> runLoop(gen), "active-pinger");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        generation++;
        if (thread != null) thread.interrupt();
        thread = null;
    }

    private void runLoop(long gen) {
        Sample lastOk = null;
        long stuckSince = 0;
        boolean stuckFired = false;
        int terminalStreak = 0;
        boolean terminalFired = false;
        while (running && gen == generation) {
            Sample s = pingOnce(url, userAgent);
            if (!running || gen != generation) return;
            if (listener != null) {
                try {
                    listener.onPingSample(s);
                } catch (Throwable ignored) { }
            }
            // ---- terminal door: two final answers in a row
            if (isTerminalHttp(s.playlistHttp)) {
                terminalStreak++;
                if (terminalStreak >= 2 && !terminalFired) {
                    terminalFired = true;
                    fireTerminal(s, gen);
                }
            } else {
                terminalStreak = 0;
                if (s.ok) terminalFired = false;
            }
            // ---- window advance / stuck judgment
            if (s.ok) {
                if (lastOk != null && sameWindow(lastOk, s)) {
                    if (stuckSince == 0) stuckSince = lastOk.atMs;
                    if (!stuckFired
                            && s.atMs - stuckSince >= stuckSpanMs(
                                    s.targetDurationMs)) {
                        stuckFired = true;
                        fireStuck(s, gen);
                    }
                } else {
                    stuckSince = 0;
                    stuckFired = false;
                }
                lastOk = s;
            }
            try {
                Thread.sleep(PING_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void fireTerminal(Sample s, long gen) {
        if (!running || gen != generation || listener == null) return;
        try {
            listener.onPlaylistTerminal(s);
        } catch (Throwable ignored) { }
    }

    private void fireStuck(Sample s, long gen) {
        if (!running || gen != generation || listener == null) return;
        try {
            listener.onWindowStuck(s);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------ pure law

    public static boolean isTerminalHttp(int http) {
        return http == 404 || http == 410 || http == 401 || http == 403;
    }

    /** How long a window may stand still before it is "stuck". */
    public static long stuckSpanMs(long targetDurationMs) {
        long byTarget = targetDurationMs > 0
                ? (long) (STUCK_COEFFICIENT * targetDurationMs) : 0;
        return Math.max(STUCK_FLOOR_MS, byTarget);
    }

    /**
     * Two samples show the SAME window when the media sequence
     * stands still AND the first segment is the same address
     * with the same opening bytes. (Same address, DIFFERENT
     * bytes = a recycling source — the window "advances" on
     * paper; the record keeps the CRCs so the family is
     * separable later.)
     */
    public static boolean sameWindow(Sample a, Sample b) {
        if (a == null || b == null) return false;
        if (a.mediaSequence >= 0 && b.mediaSequence >= 0) {
            if (a.mediaSequence != b.mediaSequence) return false;
        }
        if (!a.segmentUrl.equals(b.segmentUrl)) return false;
        return a.segmentCrc == b.segmentCrc;
    }

    /** #EXT-X-MEDIA-SEQUENCE value, -1 when absent/unreadable. */
    public static long mediaSequenceOf(String playlistText) {
        return tagLong(playlistText, "#EXT-X-MEDIA-SEQUENCE:");
    }

    /** #EXT-X-TARGETDURATION in ms, -1 when absent. */
    public static long targetDurationMsOf(String playlistText) {
        long s = tagLong(playlistText, "#EXT-X-TARGETDURATION:");
        return s < 0 ? -1 : s * 1000;
    }

    private static long tagLong(String text, String tag) {
        if (text == null) return -1;
        int i = text.indexOf(tag);
        if (i < 0) return -1;
        int from = i + tag.length();
        int to = from;
        while (to < text.length()) {
            char c = text.charAt(to);
            if ((c >= '0' && c <= '9') || c == '-') to++;
            else break;
        }
        if (to == from) return -1;
        try {
            return Long.parseLong(text.substring(from, to));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------ the knock

    private static final class Got {
        int code;
        String body = "";
        String failureKind = "";
        long crc = -1;      // range reads: CRC32 of the bytes read
    }

    private static Got get(String url, String ua, String range) {
        Got g = new Got();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(PING_TIMEOUT_MS);
            c.setReadTimeout(PING_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    ua == null || ua.isEmpty() ? "Sandy/0.14.1" : ua);
            if (range != null) c.setRequestProperty("Range", range);
            g.code = c.getResponseCode();
            InputStream in = g.code >= 400
                    ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                // a ping reads a head, never the whole body
                byte[] buf = new byte[2048];
                java.io.ByteArrayOutputStream all =
                        new java.io.ByteArrayOutputStream();
                int total = 0;
                int n;
                while (total < 65_536
                        && (n = in.read(buf)) != -1) {
                    all.write(buf, 0, n);
                    total += n;
                    if (range != null && total >= 2048) break;
                }
                byte[] bytes = all.toByteArray();
                g.body = new String(bytes, StandardCharsets.UTF_8);
                if (range != null && bytes.length > 0) {
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(bytes, 0, Math.min(bytes.length, 2048));
                    g.crc = crc.getValue();
                }
                in.close();
            }
        } catch (java.io.IOException e) {
            g.code = 0;
            g.failureKind = FailureWords.kindForDetail(String.valueOf(e));
        } catch (Throwable t) {
            g.code = 0;
            g.failureKind = FailureWords.NO_ANSWER;
        } finally {
            if (c != null) c.disconnect();
        }
        return g;
    }

    /** One knock, whole. Also the harness's door into the shape. */
    public static Sample pingOnce(String playlistUrl, String ua) {
        Sample s = new Sample();
        s.atMs = System.currentTimeMillis();
        s.playlistUrl = playlistUrl == null ? "" : playlistUrl;
        Got g = get(s.playlistUrl, ua, null);
        s.playlistHttp = g.code;
        s.failureKind = g.failureKind;
        if (g.code < 200 || g.code >= 300
                || !LinkHealth.headLooksLikePlaylist(g.body)) {
            // a direct media address: the knock is a range read
            // of the address itself
            if (g.code >= 200 && g.code < 300) {
                s.ok = true;
                s.segmentUrl = s.playlistUrl;
                s.segmentHost = hostOf(s.playlistUrl);
                s.segmentHttp = g.code;
            }
            return s;
        }
        String mediaUrl = s.playlistUrl;
        String mediaBody = g.body;
        String variant = LinkHealth.firstVariantUri(g.body);
        if (variant != null) {
            mediaUrl = resolve(s.playlistUrl, variant);
            Got v = get(mediaUrl, ua, null);
            s.playlistHttp = v.code;
            s.failureKind = v.failureKind;
            if (v.code < 200 || v.code >= 300) return s;
            mediaBody = v.body;
        }
        s.ok = true;
        s.mediaSequence = mediaSequenceOf(mediaBody);
        s.targetDurationMs = targetDurationMsOf(mediaBody);
        String seg = LinkHealth.firstSegmentUri(mediaBody);
        if (seg != null) {
            s.segmentUrl = resolve(mediaUrl, seg);
            s.segmentHost = hostOf(s.segmentUrl);
            Got r = get(s.segmentUrl, ua, "bytes=0-2047");
            s.segmentHttp = r.code;
            if (r.code >= 200 && r.code < 300) s.segmentCrc = r.crc;
        }
        return s;
    }

    private static String resolve(String base, String ref) {
        if (ref == null) return base;
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = new URL(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }
}
