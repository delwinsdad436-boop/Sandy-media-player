package com.talacore.talatv;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Link health (v0.3.0, deepened in v0.4.0) — the quality layer the raw
 * directory lacks. James's field report on 0.3.0: verified channels
 * "may connect, but they're not working — no video." He was right: a
 * 0.3.0 probe stopped at the playlist, and a playlist is only a list —
 * the video segments it points to can be dead while the list answers.
 *
 * So the 0.4.0 probe walks the whole chain: playlist -> (master ->
 * first variant) -> media playlist -> FIRST SEGMENT, and the verdict
 * is a tier, not a yes/no:
 *
 *   PLAYS         a piece of the actual video answered just now
 *   CONNECTS_ONLY the playlist answers, but the video pieces don't
 *   DEAD          the playlist itself never answered / wasn't one
 *
 * Result.ok stays in the code as a convenience and means tier==PLAYS —
 * "working" in this app now means proven to the segment, nothing less.
 *
 * Pure java.net — the same class runs in the JVM harness against
 * captured chains. Persistence of verdicts lives in HealthStore.
 */
public final class LinkHealth {

    // Verdict tiers (persisted by HealthStore).
    public static final int TIER_DEAD = 0;
    public static final int TIER_CONNECTS = 1;
    public static final int TIER_PLAYS = 2;

    public static final class Result {
        public int tier = TIER_DEAD;  // verdict tier (v0.4.0)
        public boolean ok;            // == tier == TIER_PLAYS
        public boolean responded;     // any HTTP response arrived at all
        public int httpCode;          // final status, 0 = no response
        public String detail = "";    // plain reason, fit to show a user
        public long checkedAt;        // epoch ms when the probe ran
        /**
         * v0.11.0 (James's field report, 2026-10-18): this outcome was
         * caused by SLOWNESS — a timeout, a reset, an overloaded
         * server — not by a terminal answer. A slow result is NOT a
         * death verdict: HealthStore refuses to file it as DEAD, and
         * callers must word it as what it is ("didn't answer in
         * time — it may just be slow"), never "dead".
         */
        public boolean slow;
        /** How many decisive attempts the patient probe ran (1 = once). */
        public int attempts = 1;

        public Result finish(int tier, boolean responded, String detail) {
            this.tier = tier;
            this.ok = tier == TIER_PLAYS;
            this.responded = responded;
            this.detail = detail;
            return this;
        }

        /** A DEAD-shaped outcome whose cause was slowness, not death. */
        public Result finishSlow(String detail) {
            finish(TIER_DEAD, responded, detail);
            this.slow = true;
            return this;
        }
    }

    // ------------------------------------- outcome classification
    // v0.11.0: the split the old probe never made. TERMINAL answers
    // are the server (or the address) saying the thing is gone or
    // wrong — declare dead fast and finally. RETRYABLE outcomes are
    // the server not managing to answer — slow, reset, overloaded —
    // and history (James, SG-1 ep 3, 2026-10-18) says they very
    // often play on the next try.

    public static final int CLS_OK = 0;
    public static final int CLS_TERMINAL = 1;
    public static final int CLS_RETRYABLE = 2;

    public static int classifyHttp(int code) {
        if (code >= 200 && code < 300) return CLS_OK;
        if (code == 404 || code == 410) return CLS_TERMINAL;   // gone
        if (code == 408 || code == 429) return CLS_RETRYABLE;  // timeout / throttled
        if (code >= 500) return CLS_RETRYABLE;                 // server in trouble
        return CLS_TERMINAL;                                   // other 4xx: it said no
    }

    public static int classifyIo(IOException e) {
        if (e instanceof SocketTimeoutException) return CLS_RETRYABLE;
        if (e instanceof UnknownHostException) return CLS_TERMINAL;
        if (e instanceof java.net.ConnectException) return CLS_TERMINAL; // refused
        // Resets, dropped tunnels, handshake stalls: the wire failed,
        // not the address — a retry is a fair ask.
        return CLS_RETRYABLE;
    }

    // ------------------------------------------------- patience
    // Patient but bounded (James's bounds, both directions: retry
    // the slow, never hang forever). Three decisive attempts, short
    // backoffs, and a hard 30s cap across the whole resolution —
    // a retry is only started when the cap can still hold an
    // attempt's first hop.

    public static final int MAX_ATTEMPTS = 3;
    public static final long TOTAL_CAP_MS = 30_000;
    public static final long[] BACKOFF_MS = {1000, 2000};

    public interface Patience {
        /** Fires before each retry: the server is slow, we're still trying. */
        void onSlow(int nextAttempt, int maxAttempts);
    }

    public interface Sleeper { void sleep(long ms) throws InterruptedException; }

    private static volatile Sleeper sleeper = ms -> Thread.sleep(ms);

    /** Harness hook; the app never calls this. */
    public static void setSleeper(Sleeper s) {
        sleeper = s == null ? (ms -> Thread.sleep(ms)) : s;
    }

    /** The cap math, pure: may another attempt start? */
    public static boolean mayRetry(int attempt, long elapsedMs, int timeoutMs) {
        if (attempt >= MAX_ATTEMPTS) return false;
        long backoff = BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)];
        return elapsedMs + backoff + timeoutMs <= TOTAL_CAP_MS;
    }

    public static final class Target {
        public final String url;
        public final String userAgent;
        public Target(String url, String userAgent) {
            this.url = url;
            this.userAgent = userAgent == null ? "" : userAgent;
        }
    }

    public interface Sink {
        void onResult(Target target, Result result);
    }

    /**
     * One HTTP hop: a GET with redirects NOT followed, returning the
     * status, the Location header (for 3xx), and the first bytes of the
     * body. The probe logic — redirect chasing, chain walking, verdicts
     * — lives in probe() and is shared verbatim between the app and the
     * JVM harness; only this hop touches a socket, through
     * HttpURLConnection exactly as the player reaches the network.
     * The seam exists so the harness can replay real captured responses
     * (this build sandbox resets JVM sockets outright — measured).
     */
    public interface Transport {
        Response get(String url, String userAgent, int timeoutMs) throws IOException;
    }

    public static final class Response {
        public final int code;
        public final String location;
        public final String bodyHead;
        public Response(int code, String location, String bodyHead) {
            this.code = code;
            this.location = location;
            this.bodyHead = bodyHead == null ? "" : bodyHead;
        }
    }

    private static volatile Transport transport = new HttpTransport();

    /** Harness hook; the app never calls this. */
    public static void setTransport(Transport t) {
        transport = t == null ? new HttpTransport() : t;
    }

    private static final class HttpTransport implements Transport {
        @Override
        public Response get(String url, String userAgent, int timeoutMs) throws IOException {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setConnectTimeout(timeoutMs);
                conn.setReadTimeout(timeoutMs);
                conn.setInstanceFollowRedirects(false);   // probe() follows by hand (http->https too)
                conn.setRequestProperty("User-Agent", userAgent);
                int code = conn.getResponseCode();
                String location = conn.getHeaderField("Location");
                String head = "";
                if (code >= 200 && code < 300) {
                    head = readHead(conn.getInputStream(), HEAD_BYTES);
                }
                return new Response(code, location, head);
            } finally {
                conn.disconnect();
            }
        }
    }

    /** Playlists can run long; 64 KB of head is room for the first segment line. */
    private static final int HEAD_BYTES = 65536;

    public interface Progress {
        void onProgress(int done, int total, int working, int answered);
    }

    private LinkHealth() {}

    public static boolean isRemote(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * Same "is this an HLS stream" heuristic the player uses: an .m3u8 in
     * the address, or no file extension at all (scripts/redirectors that
     * hide the manifest). Direct media files (.mp4/.mkv/…) are not HLS.
     */
    public static boolean looksHls(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8")) return true;
        String tail = lower.substring(lower.lastIndexOf('/') + 1);
        return !tail.contains(".") || lower.contains(".php") || lower.contains("play?");
    }

    public static boolean headLooksLikePlaylist(String head) {
        return head != null && head.contains("#EXTM3U");
    }

    // -------------------------------------------------- playlist parsing

    /** First variant URI in a master playlist (line after #EXT-X-STREAM-INF), or null. */
    public static String firstVariantUri(String playlistText) {
        if (playlistText == null) return null;
        boolean wantNext = false;
        for (String raw : playlistText.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                wantNext = true;
            } else if (wantNext) {
                if (line.startsWith("#")) continue;
                if (!line.isEmpty()) return line;
            }
        }
        return null;
    }

    public static boolean isMasterPlaylist(String playlistText) {
        return playlistText != null && playlistText.contains("#EXT-X-STREAM-INF");
    }

    /**
     * First segment URI in a media playlist: the first non-comment line
     * that is not itself a playlist reference (a nested .m3u8 line in a
     * media playlist is a variant, not a segment). Key/map lines
     * (#EXT-X-KEY / #EXT-X-MAP carry URI="..." attributes) are skipped —
     * the segment proper is what proves video.
     */
    public static String firstSegmentUri(String playlistText) {
        if (playlistText == null) return null;
        for (String raw : playlistText.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String lower = line.toLowerCase(Locale.US);
            String path = lower;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.endsWith(".m3u8")) continue;
            return line;
        }
        return null;
    }

    // ------------------------------------------------------------- probe

    /** A hop outcome the chain walker can act on. */
    private static final class Hop {
        Response resp;
        String finalUrl;
        String errorDetail;    // non-null: the hop itself failed (timeout/DNS/IO)
        boolean responded;
        int failureCls = CLS_TERMINAL;  // classifyIo() of the failure
    }

    private static Hop fetchChain(String url, String ua, int timeoutMs) {
        Hop h = new Hop();
        String current = url;
        try {
            for (int i = 0; i <= 5; i++) {
                Response resp = transport.get(current, ua, timeoutMs);
                h.resp = resp;
                h.finalUrl = current;
                h.responded = resp.code > 0;
                if (resp.code == 301 || resp.code == 302 || resp.code == 303
                        || resp.code == 307 || resp.code == 308) {
                    if (resp.location == null || resp.location.isEmpty() || i == 5) {
                        return h;
                    }
                    current = new URL(new URL(current), resp.location).toString();
                    continue;
                }
                return h;
            }
        } catch (SocketTimeoutException e) {
            h.errorDetail = "didn't answer in time";
            h.failureCls = classifyIo(e);
        } catch (UnknownHostException e) {
            h.errorDetail = "address didn't resolve";
            h.failureCls = classifyIo(e);
        } catch (IOException e) {
            String m = e.getMessage();
            h.errorDetail = "didn't answer" + (m == null || m.isEmpty()
                    ? "" : " (" + (m.length() > 40 ? m.substring(0, 40) : m) + ")");
            h.failureCls = classifyIo(e);
        } catch (Exception e) {
            // The network stack can fail with unchecked exceptions too
            // (malformed redirects, tunnel/parser failures). A probe
            // must never take the app down — any failure is a verdict.
            h.errorDetail = "didn't answer";
            h.failureCls = CLS_TERMINAL;
        }
        return h;
    }

    /**
     * One link, one tiered verdict, ONE attempt (the batch checker
     * and the auto-skip walk use this — breadth over patience).
     * Failures are classified as they land: a retryable failure
     * (timeout, reset, a server in trouble) comes back DEAD-shaped
     * but flagged {@code slow} — it is not a death verdict, and
     * HealthStore will not file it as one.
     */
    public static Result probeOnce(String url, String userAgent, int timeoutMs) {
        Result r = new Result();
        r.checkedAt = System.currentTimeMillis();
        if (!isRemote(url)) {
            // on-device file (My Library): it is here by definition
            return r.finish(TIER_PLAYS, true, "on this phone");
        }
        String ua = (userAgent == null || userAgent.isEmpty()) ? "Sandy/0.14.1" : userAgent;

        // Direct media file: the file answering with bytes IS the video
        // answering — there is no playlist layer to hide behind.
        if (!looksHls(url)) {
            Hop h = fetchChain(url, ua, timeoutMs);
            if (h.errorDetail != null) {
                if (h.failureCls == CLS_RETRYABLE) return r.finishSlow(h.errorDetail);
                return r.finish(TIER_DEAD, false, h.errorDetail);
            }
            r.httpCode = h.resp.code;
            r.responded = h.responded;
            if (h.resp.code >= 200 && h.resp.code < 300) {
                return r.finish(TIER_PLAYS, true, "plays");
            }
            if (classifyHttp(h.resp.code) == CLS_RETRYABLE) {
                return r.finishSlow("the server answered HTTP " + h.resp.code
                        + " — it may just be busy");
            }
            return r.finish(TIER_DEAD, h.responded, "HTTP " + h.resp.code);
        }

        // HLS: walk playlist -> variant -> media playlist -> segment.
        Hop h = fetchChain(url, ua, timeoutMs);
        if (h.errorDetail != null) {
            if (h.failureCls == CLS_RETRYABLE) return r.finishSlow(h.errorDetail);
            return r.finish(TIER_DEAD, false, h.errorDetail);
        }
        r.httpCode = h.resp.code;
        r.responded = h.responded;
        if (h.resp.code < 200 || h.resp.code >= 300) {
            if (classifyHttp(h.resp.code) == CLS_RETRYABLE) {
                return r.finishSlow("the server answered HTTP " + h.resp.code
                        + " — it may just be busy");
            }
            return r.finish(TIER_DEAD, h.responded, "HTTP " + h.resp.code);
        }
        if (!headLooksLikePlaylist(h.resp.bodyHead)) {
            return r.finish(TIER_DEAD, true, "answered, but didn't send a playlist");
        }
        String playlistUrl = h.finalUrl;
        String text = h.resp.bodyHead;

        // Master playlist: descend into the first variant.
        if (isMasterPlaylist(text)) {
            String variant = firstVariantUri(text);
            if (variant == null) {
                return r.finish(TIER_CONNECTS, true, "playlist answers, but lists no streams");
            }
            String variantUrl = resolve(playlistUrl, variant);
            Hop vh = fetchChain(variantUrl, ua, timeoutMs);
            if (vh.errorDetail != null || vh.resp == null
                    || vh.resp.code < 200 || vh.resp.code >= 300
                    || !headLooksLikePlaylist(vh.resp.bodyHead)) {
                return r.finish(TIER_CONNECTS, true,
                        "playlist answers, but its video list doesn't");
            }
            playlistUrl = vh.finalUrl;
            text = vh.resp.bodyHead;
        }

        String segment = firstSegmentUri(text);
        if (segment == null) {
            return r.finish(TIER_CONNECTS, true, "playlist answers, but holds no video yet");
        }
        Hop sh = fetchChain(resolve(playlistUrl, segment), ua, timeoutMs);
        if (sh.errorDetail != null) {
            return r.finish(TIER_CONNECTS, true, "playlist answers, but the video didn't");
        }
        if (sh.resp != null && sh.resp.code >= 200 && sh.resp.code < 300
                && sh.resp.bodyHead != null && !sh.resp.bodyHead.isEmpty()) {
            return r.finish(TIER_PLAYS, true, "plays");
        }
        int code = sh.resp == null ? 0 : sh.resp.code;
        return r.finish(TIER_CONNECTS, true,
                code > 0 ? "playlist answers, but the video didn't (HTTP " + code + ")"
                         : "playlist answers, but the video didn't");
    }

    /**
     * The patient probe — what a person's tap gets (v0.11.0, James's
     * field report). A retryable outcome is retried in place, a few
     * attempts over several seconds, before any verdict reaches the
     * user: the word "dead" is never spoken while retries remain.
     * Terminal outcomes return on the first attempt, fast and final.
     * The whole resolution is hard-capped (TOTAL_CAP_MS) so patience
     * can never become a hang.
     */
    public static Result probe(String url, String userAgent, int timeoutMs) {
        return probe(url, userAgent, timeoutMs, null);
    }

    public static Result probe(String url, String userAgent, int timeoutMs,
                               Patience patience) {
        long started = System.currentTimeMillis();
        int attempt = 1;
        while (true) {
            Result r = probeOnce(url, userAgent, timeoutMs);
            r.attempts = attempt;
            if (!r.slow) return r;
            long elapsed = System.currentTimeMillis() - started;
            if (!mayRetry(attempt, elapsed, timeoutMs)) return r;
            if (patience != null) patience.onSlow(attempt + 1, MAX_ATTEMPTS);
            try {
                sleeper.sleep(BACKOFF_MS[Math.min(attempt - 1,
                        BACKOFF_MS.length - 1)]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return r;
            }
            attempt++;
        }
    }

    private static String resolve(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
    }

    private static String readHead(InputStream in, int maxBytes) {
        if (in == null) return "";
        try {
            byte[] buf = new byte[maxBytes];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------- batch

    /**
     * Probe a set of links with bounded concurrency and polite timeouts.
     * Every verdict goes to the sink (per target) as it lands; progress
     * fires per completion. Returns {total, answered, plays,
     * connectsOnly, deadButAnswered} where "answered" counts links that
     * produced any HTTP response and "plays" counts tier PLAYS — the
     * numbers the UI reports.
     */
    public static int[] probeAll(List<Target> targets, int concurrency, int timeoutMs,
                                 Sink sink, Progress progress) {
        int total = targets.size();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger plays = new AtomicInteger();
        AtomicInteger connects = new AtomicInteger();
        AtomicInteger deadAnswered = new AtomicInteger();
        AtomicInteger answered = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        for (Target t : targets) {
            pool.execute(() -> {
                // The batch walks single-attempt (probeOnce): breadth
                // over patience — but a slow outcome is not counted
                // or filed as dead either (see Result.slow).
                Result r = probeOnce(t.url, t.userAgent, timeoutMs);
                if (r.tier == TIER_PLAYS) plays.incrementAndGet();
                else if (r.tier == TIER_CONNECTS) connects.incrementAndGet();
                else if (r.responded && !r.slow) deadAnswered.incrementAndGet();
                if (r.responded) answered.incrementAndGet();
                if (sink != null) sink.onResult(t, r);
                int d = done.incrementAndGet();
                if (progress != null) progress.onProgress(d, total, plays.get(), answered.get());
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(6, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new int[]{total, answered.get(), plays.get(), connects.get(), deadAnswered.get()};
    }

    // ------------------------------------------------------------- display

    /** "just now" / "12m ago" / "3h ago" / "2d ago" / "Mar 3" — for badges. */
    public static String friendlyWhen(long checkedAt, long now) {
        long diff = now - checkedAt;
        if (diff < 60_000) return "just now";
        if (diff < 3_600_000) return (diff / 60_000) + "m ago";
        if (diff < 86_400_000) return (diff / 3_600_000) + "h ago";
        if (diff < 7 * 86_400_000L) return (diff / 86_400_000L) + "d ago";
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(checkedAt));
    }
}
