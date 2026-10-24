package com.talacore.talatv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The house's fetch hand — and since v0.14.0 it walks HYDRA's
 * ladder (see Hydra): every catalogue fetch, Archive resolve,
 * guide fetch, and story-book pull in the app comes through
 * here, so every one of them inherits the Connection Law.
 * Before Hydra this was a single attempt that threw a bare
 * "HTTP 404" — one manner, one try, no words. Now: the ladder
 * for the address (direct → Archive canonical door → the other
 * scheme), SG-1 semantics per head (terminal fails the HEAD
 * fast; slow retries in place inside the shared ~30 s budget),
 * redirects followed BY HAND so an http→https hop — which
 * HttpURLConnection's auto-follow refuses — is just another
 * step on the chain, and exhaustion throws a HydraException
 * carrying the failure in plain words (FailureWords).
 *
 * Learned doors: the head that answered for a host is
 * remembered (in memory here; persisted to the memory hook by
 * HydraStore, primed at boot from SplashActivity).
 */
public final class Net {

    public static final String UA = "Sandy/0.18.0";

    private Net() {}

    /** A resolution that exhausted its ladder, in plain words. */
    public static class HydraException extends Exception {
        public final String kind;     // FailureWords kind
        public final int httpCode;    // the most informative status, 0 = none
        public HydraException(String kind, int httpCode) {
            super(FailureWords.englishFor(kind, httpCode));
            this.kind = kind;
            this.httpCode = httpCode;
        }
    }

    // -------------------------------------------- learned doors

    public interface DoorSink {
        void learned(String host, int manner);
    }

    private static volatile Map<String, Integer> doors = new HashMap<>();
    private static volatile DoorSink doorSink;

    /** Primed at boot (SplashActivity) from the memory hook. */
    public static void primeDoors(Map<String, Integer> learned, DoorSink sink) {
        if (learned != null) doors = new HashMap<>(learned);
        doorSink = sink;
    }

    public static void noteLearned(String host, int manner) {
        if (host == null) return;
        Integer cur = doors.get(host);
        if (cur != null && cur == manner) return;
        doors.put(host, manner);
        DoorSink s = doorSink;
        if (s != null) s.learned(host, manner);
    }

    /**
     * Un-learn a door (v0.14.1): the Active Pinger found this
     * host's learned door terminally dead, so the preference is
     * dropped and the next visit walks the ladder from the top.
     * The persisted file is HydraStore.forget's job; callers in
     * the app use both.
     */
    public static void forgetLearned(String host) {
        if (host == null) return;
        doors.remove(host);
    }

    // -------------------------------------------------- fetching

    private static final int CONNECT_MS = 6000;
    private static final int READ_MS = 10000;
    private static final int MAX_REDIRECTS = 5;

    private static final class Hop {
        int code = 0;
        byte[] body;
        IOException error;
        String finalUrl = "";
    }

    /** One head's chain: a GET, redirects followed by hand —
     *  including across protocols, which the auto-follower
     *  will not do. */
    private static Hop hop(String urlStr) {
        Hop h = new Hop();
        String current = urlStr;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setConnectTimeout(CONNECT_MS);
                conn.setReadTimeout(READ_MS);
                conn.setRequestProperty("User-Agent", UA);
                conn.setInstanceFollowRedirects(false);
                int code = conn.getResponseCode();
                h.code = code;
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty() && i < MAX_REDIRECTS) {
                        current = new URL(new URL(current), loc).toString();
                        continue;
                    }
                    h.finalUrl = current;
                    return h;
                }
                if (code >= 200 && code < 300) {
                    try (InputStream in = conn.getInputStream();
                         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        h.body = bos.toByteArray();
                    }
                }
                h.finalUrl = current;
                return h;
            } catch (IOException e) {
                h.error = e;
                return h;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        h.finalUrl = current;
        return h;
    }

    public static byte[] getBytes(String urlStr) throws Exception {
        long started = System.currentTimeMillis();
        List<Hydra.Head> ladder = Hydra.orderFor(urlStr, doors);
        List<Hydra.Tried> tried = new ArrayList<>();
        for (Hydra.Head head : ladder) {
            int attempt = 0;
            while (true) {
                attempt++;
                Hop h = hop(head.url);
                if (h.error == null && h.code >= 200 && h.code < 300
                        && h.body != null) {
                    noteLearned(Hydra.hostOf(urlStr), head.manner);
                    return h.body;
                }
                int cls;
                String kind;
                int code = h.error == null ? h.code : 0;
                if (h.error != null) {
                    kind = FailureWords.kindForDetail(
                            String.valueOf(h.error));
                    // the OS barring cleartext kills THIS head,
                    // not the attempt — the https head may live
                    cls = FailureWords.CLEARTEXT.equals(kind)
                            ? LinkHealth.CLS_TERMINAL
                            : LinkHealth.classifyIo(h.error);
                } else {
                    kind = FailureWords.kindForHttp(h.code);
                    cls = LinkHealth.classifyHttp(h.code);
                }
                if (cls == LinkHealth.CLS_TERMINAL) {
                    tried.add(new Hydra.Tried(head.manner, code, kind));
                    break;   // this door is dead; another head tries
                }
                long elapsed = System.currentTimeMillis() - started;
                if (!LinkHealth.mayRetry(attempt, elapsed, READ_MS)
                        || !Hydra.mayAdvance(started,
                                System.currentTimeMillis(), READ_MS)) {
                    tried.add(new Hydra.Tried(head.manner, code, kind));
                    break;   // patience spent on this head; advance
                }
                try {
                    Thread.sleep(LinkHealth.BACKOFF_MS[
                            Math.min(attempt - 1,
                                    LinkHealth.BACKOFF_MS.length - 1)]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        Hydra.Tried worst = Hydra.mostInformative(tried);
        if (worst != null) {
            throw new HydraException(
                    worst.kind.isEmpty()
                            ? FailureWords.kindForHttp(worst.httpCode)
                            : worst.kind,
                    worst.httpCode);
        }
        throw new HydraException(FailureWords.NO_ANSWER, 0);
    }

    public static String getString(String urlStr) throws Exception {
        return new String(getBytes(urlStr), StandardCharsets.UTF_8);
    }
}
