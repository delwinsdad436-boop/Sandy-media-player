package com.talacore.talatv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HYDRA — the Connection Law (v0.14.0, James, 2026-10-19:
 * "Can't you write a law that will take care of that issue?
 * If it's the other way, use the other way; if it's another
 * way, use that way."). One body (this law), many heads (the
 * manners). Every resolution in the house routes through here:
 * live tune, catalogue pull, Archive resolve, resume, guide
 * fetch. A head failing never kills the attempt — another head
 * tries — and failure is declared only when the ladder is
 * exhausted, in plain words (FailureWords), never a bare
 * "HTTP error".
 *
 * The manners, per published address, in ladder order:
 *   DIRECT        the address exactly as published
 *   IA_CANONICAL  for an Archive file: the archive.org/download
 *                 door, which redirects to whichever node holds
 *                 the file (the Archive's own rotation — the
 *                 metadata's pinned node is only one door)
 *   SCHEME        the other scheme: many streams are published
 *                 http-only or https-only; the other one is a
 *                 different door, not a retry
 * A RETRY is the same head again (for slowness, SG-1 semantics:
 * terminal answers fail the HEAD fast, slow answers retry in
 * place). An ALTERNATE MANNER is the next head. The whole
 * resolution shares one ~30 s budget (LinkHealth.TOTAL_CAP_MS).
 *
 * LEARNING: the head that answered for a host is remembered —
 * persisted as a file on the memory hook by HydraStore — and
 * the next visit to that host starts at the remembered door.
 * A remembered door that starts failing re-walks the ladder.
 *
 * This class is the pure policy: heads, order, budget, the
 * learned-door file's shape, and the exhaustion report. The
 * network hands are Net (fetches) and HydraResolver (stream
 * proof), both walking exactly this ladder.
 */
public final class Hydra {

    public static final int M_DIRECT = 0;
    public static final int M_IA_CANONICAL = 1;
    public static final int M_SCHEME = 2;

    /** Hard cap across a whole resolution, all heads together. */
    public static final long TOTAL_BUDGET_MS = LinkHealth.TOTAL_CAP_MS;

    private Hydra() {}

    /** One door into a source: a manner and the address it opens. */
    public static final class Head {
        public final int manner;
        public final String url;
        public Head(int manner, String url) {
            this.manner = manner;
            this.url = url;
        }
    }

    /** The ladder for one published address, in try order. */
    public static List<Head> heads(String url) {
        List<Head> out = new ArrayList<>();
        if (url == null || url.isEmpty()) return out;
        out.add(new Head(M_DIRECT, url));
        String canon = iaCanonical(url);
        if (canon != null && !canon.equals(url)) {
            out.add(new Head(M_IA_CANONICAL, canon));
        }
        String swap = schemeSwap(url);
        if (swap != null) out.add(new Head(M_SCHEME, swap));
        return out;
    }

    /**
     * For an Archive file address on a pinned node
     * (iaNNNN.us.archive.org/download/&lt;id&gt;/&lt;path&gt; or
     * &lt;id&gt;.us.archive.org/...), the canonical door:
     * https://archive.org/download/&lt;id&gt;/&lt;path&gt;.
     * Null when the address is not an Archive file address, or
     * already is the canonical door.
     */
    public static String iaCanonical(String url) {
        if (url == null) return null;
        int dl = url.indexOf("/download/");
        if (dl < 0) return null;
        String rest = url.substring(dl + "/download/".length());
        if (rest.isEmpty()) return null;
        String host = hostOf(url);
        if (host == null || !host.endsWith("archive.org")) return null;
        if (host.equals("archive.org") || host.equals("www.archive.org")) {
            return null;   // already the canonical door
        }
        return "https://archive.org/download/" + rest;
    }

    /** The same address through the other scheme, or null. */
    public static String schemeSwap(String url) {
        if (url == null) return null;
        if (url.startsWith("https://")) return "http://" + url.substring(8);
        if (url.startsWith("http://")) return "https://" + url.substring(7);
        return null;
    }

    public static String hostOf(String url) {
        if (url == null) return null;
        try {
            String h = new java.net.URL(url).getHost();
            return h == null || h.isEmpty() ? null : h.toLowerCase(java.util.Locale.US);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------ learning

    /** host -> the manner that answered there (the learned door). */
    public static Map<String, Integer> parseDoors(String tsv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (tsv == null) return out;
        for (String line : tsv.split("\n")) {
            String[] p = line.split("\t");
            if (p.length < 2) continue;
            try {
                out.put(dec(p[0]), Integer.parseInt(p[1].trim()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public static String doorsToTsv(Map<String, Integer> doors) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : doors.entrySet()) {
            sb.append(enc(e.getKey())).append('\t')
                    .append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /**
     * The ladder as THIS host remembers it: the learned door
     * first (when it is still on the ladder), the rest in law
     * order behind it. A learned door that fails re-walks the
     * whole ladder — the caller simply continues down the list.
     */
    public static List<Head> orderFor(String url, Map<String, Integer> doors) {
        List<Head> ladder = heads(url);
        if (doors == null || ladder.size() < 2) return ladder;
        String host = hostOf(url);
        Integer learned = host == null ? null : doors.get(host);
        if (learned == null) return ladder;
        for (int i = 1; i < ladder.size(); i++) {
            if (ladder.get(i).manner == learned) {
                List<Head> out = new ArrayList<>(ladder);
                Head h = out.remove(i);
                out.add(0, h);
                return out;
            }
        }
        return ladder;
    }

    // ------------------------------------------------ budget

    /** May another head start? The budget is shared, hard-capped. */
    public static boolean mayAdvance(long startedMs, long nowMs, long nextTimeoutMs) {
        return nowMs - startedMs + nextTimeoutMs <= TOTAL_BUDGET_MS;
    }

    // --------------------------------------- the exhaustion report

    /** One head's answer, kept for the failure report. */
    public static final class Tried {
        public final int manner;
        public final int httpCode;    // 0 = no HTTP answer at all
        public final String kind;     // FailureWords kind, or ""
        public Tried(int manner, int httpCode, String kind) {
            this.manner = manner;
            this.httpCode = httpCode;
            this.kind = kind == null ? "" : kind;
        }
    }

    /**
     * The answer that best explains the failure, for the plain-
     * words report: a TERMINAL answer (the server actually said
     * no — refused, gone) outranks silence, because it is the
     * truth the server spoke; among terminal answers the LAST
     * one wins (the final door's word). Silence (timeouts) is
     * reported only when nothing answered at all.
     */
    public static Tried mostInformative(List<Tried> tried) {
        if (tried == null || tried.isEmpty()) return null;
        Tried lastTerminal = null;
        for (Tried t : tried) {
            if (t.httpCode > 0
                    && LinkHealth.classifyHttp(t.httpCode) == LinkHealth.CLS_TERMINAL) {
                lastTerminal = t;
            }
        }
        if (lastTerminal != null) return lastTerminal;
        return tried.get(tried.size() - 1);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
