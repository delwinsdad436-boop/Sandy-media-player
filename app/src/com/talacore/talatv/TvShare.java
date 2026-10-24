package com.talacore.talatv;

import java.util.Locale;
import java.util.Random;

/**
 * Screen-share to a TV (v0.7.0, James's order) — the pure half:
 * what can be shared, where the bytes come from, the watching
 * page, and the small network facts (IPv4 text, HTTP ranges).
 * The Android half (ShareCast: routes, server, sockets) reads
 * like a script off these decisions, and the harness pins every
 * branch here.
 *
 * The three honest routes, as evaluated for this build:
 *  1. FLING — Android's remote-playback protocol (MediaRouter).
 *     A TV/receiver that speaks it takes the stream URL (or the
 *     page-share server's media URL) and plays it itself.
 *  2. PAGE — an in-app web server on the same Wi-Fi hands any
 *     TV browser a self-contained watching page. Local files
 *     are served by the phone; network streams are handed to
 *     the TV directly (a redirect — the TV pulls the stream
 *     itself, so live stays live).
 *  3. MIRROR — the phone's own screen mirroring, handed off to
 *     the system Cast settings (ShareCast). The app cannot do
 *     Miracast itself; it walks the viewer to the door.
 *
 * Google Cast (Chromecast) is deliberately NOT here: its SDK
 * requires Google Play services and a registered receiver app,
 * neither of which this sideloaded, Play-services-free build
 * can assume. The NOTES say so in plain words.
 */
public final class TvShare {

    /** Where /media bytes come from for a playing item. */
    public enum Kind {
        /** A file on this phone (library file, exported recording). */
        FILE,
        /** A network stream: the TV fetches it itself (redirect). */
        REDIRECT,
        /** Nothing playable to hand over (exotic scheme). */
        PAGE_ONLY
    }

    public static final class Plan {
        public final Kind kind;
        public final String url;
        Plan(Kind k, String u) { kind = k; url = u; }
    }

    private TvShare() {}

    public static Plan planFor(String url) {
        if (url == null || url.isEmpty()) return new Plan(Kind.PAGE_ONLY, url);
        if (url.startsWith("file://") || url.startsWith("content://")
                || url.startsWith("/")) {
            return new Plan(Kind.FILE, url);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return new Plan(Kind.REDIRECT, url);
        }
        return new Plan(Kind.PAGE_ONLY, url);
    }

    /**
     * The URL a remote-playback route should play, or null when
     * flinging isn't possible for this plan right now: a network
     * stream flings as itself; a phone file flings as the page
     * server's media URL (so the server must be running — the
     * caller starts it and passes its base).
     */
    public static String flingUrlFor(Plan plan, String serverBase) {
        if (plan == null) return null;
        switch (plan.kind) {
            case REDIRECT: return plan.url;
            case FILE: return serverBase == null ? null
                    : serverBase + "/media";
            default: return null;
        }
    }

    /** The watching-page address to show the viewer, big. */
    public static String watchUrl(String ip, int port, String token) {
        return "http://" + ip + ":" + port + "/watch?k=" + token;
    }

    /** The media address under a running page server. */
    public static String mediaUrl(String ip, int port, String token) {
        return "http://" + ip + ":" + port + "/media?k=" + token;
    }

    /** A fresh page-server token: 16 hex chars, from the given rng. */
    public static String newToken(Random rng) {
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append("0123456789abcdef".charAt(rng.nextInt(16)));
        }
        return sb.toString();
    }

    /** WifiManager-style little-endian int -> "192.168.1.20". */
    public static String formatIpv4(int littleEndian) {
        return (littleEndian & 0xff) + "."
                + ((littleEndian >> 8) & 0xff) + "."
                + ((littleEndian >> 16) & 0xff) + "."
                + ((littleEndian >> 24) & 0xff);
    }

    /** Address-order bytes (NetworkInterface style) -> dotted text. */
    public static String formatIpv4(byte[] b) {
        if (b == null || b.length != 4) return null;
        return (b[0] & 0xff) + "." + (b[1] & 0xff) + "."
                + (b[2] & 0xff) + "." + (b[3] & 0xff);
    }

    public static boolean isLoopback(String ip) {
        return ip == null || ip.startsWith("127.") || ip.equals("0.0.0.0");
    }

    /** Content type for a media URL/name; octet-stream when unsure. */
    public static String mimeFor(String urlOrName) {
        if (urlOrName == null) return "application/octet-stream";
        String s = urlOrName.toLowerCase(Locale.US);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        if (s.endsWith(".m3u8")) return "application/vnd.apple.mpegurl";
        if (s.endsWith(".mp4") || s.endsWith(".m4v")) return "video/mp4";
        if (s.endsWith(".mkv")) return "video/x-matroska";
        if (s.endsWith(".webm")) return "video/webm";
        if (s.endsWith(".ts")) return "video/mp2t";
        if (s.endsWith(".mov")) return "video/quicktime";
        if (s.endsWith(".mp3")) return "audio/mpeg";
        if (s.endsWith(".m4a")) return "audio/mp4";
        if (s.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    /**
     * Parse an HTTP Range header against a known length.
     * Returns {start, endInclusive}, or null when the header is
     * absent, malformed, or unsatisfiable (the caller then
     * answers 200 with the whole body, the forgiving default).
     */
    public static long[] parseRange(String header, long length) {
        if (header == null || length <= 0) return null;
        String h = header.trim().toLowerCase(Locale.US);
        if (!h.startsWith("bytes=")) return null;
        String spec = h.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf(',') >= 0) return null;
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long start, end;
            if (a.isEmpty()) {           // suffix range: last N bytes
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                start = Math.max(0, length - n);
                end = length - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? length - 1 : Long.parseLong(b);
            }
            if (start < 0 || start >= length || end < start) return null;
            if (end >= length) end = length - 1;
            return new long[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * The self-contained watching page (James's compression
     * insight, put to work: the page carries its own player —
     * any browser that opens it is the projector). mediaPath is
     * the same-origin media address ("/media?k=…"), so the page
     * never holds a raw stream URL it shouldn't.
     */
    public static String playerPage(String title, String mediaPath,
                                    boolean playable) {
        String t = esc(title == null || title.isEmpty() ? "Sandy" : title);
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\">");
        sb.append("<title>").append(t).append(" — Sandy</title>");
        sb.append("<style>body{background:#101014;color:#fff;"
                + "font-family:sans-serif;margin:0;padding:24px;"
                + "text-align:center}h1{font-size:28px;font-weight:normal}"
                + "video{width:100%;max-width:1100px;background:#000}"
                + "p{color:#b9b9c4;font-size:17px;line-height:1.5}"
                + "</style></head><body>");
        sb.append("<h1>").append(t).append("</h1>");
        if (playable) {
            sb.append("<video controls autoplay playsinline src=\"")
                    .append(esc(mediaPath)).append("\"></video>");
            sb.append("<p>This TV is watching through Sandy, on the "
                    + "phone that shared this page. If the picture "
                    + "stalls, check that the TV and the phone are on "
                    + "the same Wi-Fi.</p>");
        } else {
            sb.append("<p>Sandy can show this page, but this title "
                    + "isn't a kind the TV can play from here. Try "
                    + "sharing a recording or a video file from the "
                    + "phone instead.</p>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }
}
