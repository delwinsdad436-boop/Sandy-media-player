package com.talacore.talatv;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The headers a fetch knocks with (v0.19.0 — closing the
 * fifteen-second window's second mechanism).
 *
 * The v0.14.1 diagnosis named it plainly: restreamer segment
 * CDNs gate on the door's MANNER — a playlist that answers a
 * bare fetch can still refuse its segments to a player that
 * arrives with no Referer, and the refusal lands at the first
 * window boundary (~10–15 s in: the opening segments ride the
 * playlist's coattails, the next window must knock again).
 * The build record claimed the player's fetches were given
 * the door knowledge then; the source said otherwise — no
 * Referer was sent anywhere in the app. v0.14.1's continuity
 * law still saved the channel (recovery instead of a skip),
 * but the first refusal kept happening where a proper knock
 * prevents it.
 *
 * The knock a real player makes: it arrives at a segment the
 * way a browser arrives from the page that listed it — the
 * Referer is the ORIGIN of the address being fetched
 * (scheme://host[:port]/). That is the one header derived
 * here, applied at every fetch hand the app has: the player
 * (both HTTP factories), the timeshift/recordings upstreams,
 * the probe (LinkHealth), the Active Pinger, and Net's hops.
 * A channel's own User-Agent, when the playlist entry carries
 * one, still outranks the app fallback — that rule is older
 * than this class and unchanged.
 *
 * Pure: no Android, no sockets. The harness pins the shapes.
 */
public final class DoorHeaders {

    private DoorHeaders() {}

    /**
     * The Referer a fetch of {@code url} naturally carries:
     * the URL's own origin with a trailing slash — e.g.
     * {@code http://cdn.example:8080/list.m3u8} knocks as
     * {@code http://cdn.example:8080/}. "" when no origin can
     * be derived (null, malformed, non-HTTP, hostless): the
     * caller then sends no Referer, exactly as before.
     */
    public static String refererFor(String url) {
        if (url == null) return "";
        try {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "";
            }
            String host = u.getHost();
            if (host == null || host.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(host);
            int port = u.getPort();
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            if (port > 0 && port != defaultPort) {
                sb.append(':').append(port);
            }
            sb.append('/');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Default request properties for a media3 HTTP factory
     * about to fetch {@code url}'s stream: the Referer above,
     * or an empty map when none can be derived (the factory
     * is then left exactly as it was).
     */
    public static Map<String, String> requestPropertiesFor(String url) {
        Map<String, String> props = new LinkedHashMap<>();
        String referer = refererFor(url);
        if (!referer.isEmpty()) props.put("Referer", referer);
        return props;
    }
}
