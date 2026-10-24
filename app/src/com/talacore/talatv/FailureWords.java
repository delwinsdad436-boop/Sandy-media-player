package com.talacore.talatv;

/**
 * Failure words (v0.14.0, James: a bare "HTTP error" is
 * banned). When a play or a resolve fails, the viewer's surface
 * states the actual result in plain words — the status the
 * server spoke, or the silence it left — so a field failure
 * reports ITSELF instead of hiding behind a code word. The same
 * words are what Hydra's exhaustion report carries and what
 * gets logged with the failure.
 *
 * Each kind has a UiStrings key (all 13 tables) for the viewer
 * surfaces that localize, and a plain-English sentence for the
 * surfaces that speak English in this house's code (status
 * lines). The kind is decided from the real outcome: the HTTP
 * status when the server answered, the wire failure when it
 * didn't.
 */
public final class FailureWords {

    public static final String REFUSED = "refused";     // 401/403: it said no
    public static final String GONE = "gone";           // 404/410: not there
    public static final String SERVER = "server";       // 5xx: their side broke
    public static final String TIMEOUT = "timeout";     // answered too slowly
    public static final String ADDRESS = "address";     // no such host / refused connect
    public static final String CLEARTEXT = "cleartext"; // the OS door barred plain http
    public static final String NO_ANSWER = "noanswer";  // silence, retries spent

    private FailureWords() {}

    /** The kind for an HTTP status (0 = no answer at all). */
    public static String kindForHttp(int code) {
        if (code == 404 || code == 410) return GONE;
        if (code == 401 || code == 403) return REFUSED;
        if (code == 408) return TIMEOUT;
        if (code >= 500) return SERVER;
        if (code >= 400) return REFUSED;
        return NO_ANSWER;
    }

    /** The kind for a wire failure's detail text. */
    public static String kindForDetail(String detail) {
        if (detail == null) return NO_ANSWER;
        String d = detail.toLowerCase(java.util.Locale.US);
        if (d.contains("cleartext")) return CLEARTEXT;
        if (d.contains("unknownhost") || d.contains("unknown host")
                || d.contains("connectexception")
                || d.contains("connection refused")) return ADDRESS;
        if (d.contains("timeout") || d.contains("timed out")
                || d.contains("didn't answer")) return TIMEOUT;
        if (d.contains("http 404") || d.contains("http 410")) return GONE;
        if (d.contains("http 403") || d.contains("http 401")) return REFUSED;
        if (d.contains("http 5")) return SERVER;
        return NO_ANSWER;
    }

    /** The kind a finished LinkHealth result carries. */
    public static String kindFor(LinkHealth.Result r) {
        if (r == null) return NO_ANSWER;
        if (r.httpCode > 0) return kindForHttp(r.httpCode);
        if (r.slow) return TIMEOUT;
        return kindForDetail(r.detail);
    }

    /** The UiStrings key a viewer surface localizes with. */
    public static String keyFor(String kind) {
        switch (kind == null ? "" : kind) {
            case REFUSED: return "fail_refused";
            case GONE: return "fail_gone";
            case SERVER: return "fail_server";
            case TIMEOUT: return "fail_timeout";
            case ADDRESS: return "fail_address";
            case CLEARTEXT: return "fail_cleartext";
            default: return "fail_no_answer";
        }
    }

    /** The plain-English sentence (status-line surfaces). */
    public static String englishFor(String kind, int httpCode) {
        switch (kind == null ? "" : kind) {
            case REFUSED:
                return "The server refused (HTTP " + httpCode + ") — it said no.";
            case GONE:
                return "It's gone (HTTP " + httpCode + ") — the server says it isn't there.";
            case SERVER:
                return "The server is in trouble (HTTP " + httpCode + ") — their side, not yours.";
            case TIMEOUT:
                return "It didn't answer in time — the server is slow.";
            case ADDRESS:
                return "That address doesn't answer — it may be written wrong, or the host is down.";
            case CLEARTEXT:
                return "This phone barred the plain (unencrypted) connection the source needs.";
            default:
                return "No answer — every way in was tried and none answered.";
        }
    }

    /** Words for a finished probe result, in one call. */
    public static String englishFor(LinkHealth.Result r) {
        return englishFor(kindFor(r), r == null ? 0 : r.httpCode);
    }
}
