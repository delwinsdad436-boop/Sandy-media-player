package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * THE TRANSLATOR's core (v0.15.0) — the turn-based conversation
 * between two people who share no language, built exactly to
 * James's spec: one side speaks (or types), an honest pause,
 * the HEARD words and the RENDERED words side by side, then the
 * rendering spoken in the other side's language. UNDERSTOOD is
 * the floor — turn-taking is the shipped mode, never blocked on
 * anything live or simultaneous.
 *
 * Routes are derived from the machinery that truly exists: the
 * installed engine packs' MT pairs (TextMt.installedPairs).
 *   DIRECT    one installed pair carries from→to
 *   PIVOT     from→en and en→to are both installed; the hop
 *             through English is DISCLOSED on screen, never hid
 *   IDENTITY  both sides picked the same language — nothing to
 *             translate; the rendering is the words themselves
 *   NONE      no real path. The door refuses BEFORE any words
 *             are captured and names the missing direction —
 *             never faked, never silently routed elsewhere.
 *
 * The verification rule is structural here: a Turn always
 * carries heard AND rendered together, and the transcript file
 * writes both, every turn. Pure: the door and the harness drive
 * the same state machine.
 */
public final class TranslatorCore {

    public enum RouteKind { DIRECT, PIVOT, IDENTITY, NONE }

    public static final class Route {
        public RouteKind kind = RouteKind.NONE;
        /** The pair that carries the words (DIRECT), else "". */
        public String pair = "";
        /** PIVOT legs: from→en then en→to. */
        public String legIn = "";
        public String legOut = "";
        /** NONE: the direction that has no engine, e.g. "ja-fr". */
        public String missingPair = "";

        public boolean ok() { return kind != RouteKind.NONE; }
    }

    /** How a turn's rendering leaves the door. Text is ALWAYS
     *  shown (the verification rule); the mode only governs the
     *  spoken half. */
    public enum OutMode { VOICE, TEXT, BOTH }

    public static boolean speaks(OutMode m) { return m != OutMode.TEXT; }
    public static boolean showsText(OutMode m) { return true; }

    /** Derive the route for from→to from the installed pairs. */
    public static Route resolveRoute(String fromIso, String toIso,
                                     Set<String> installedPairs) {
        Route r = new Route();
        String f = fromIso == null ? "" : fromIso.trim().toLowerCase();
        String t = toIso == null ? "" : toIso.trim().toLowerCase();
        if (f.isEmpty() || t.isEmpty()) {
            r.missingPair = f + "-" + t;
            return r;
        }
        if (f.equals(t)) {
            r.kind = RouteKind.IDENTITY;
            return r;
        }
        Set<String> pairs = installedPairs == null
                ? java.util.Collections.emptySet() : installedPairs;
        String direct = f + "-" + t;
        if (pairs.contains(direct)) {
            r.kind = RouteKind.DIRECT;
            r.pair = direct;
            return r;
        }
        String in = f + "-en";
        String out = "en-" + t;
        if (!f.equals("en") && !t.equals("en")
                && pairs.contains(in) && pairs.contains(out)) {
            r.kind = RouteKind.PIVOT;
            r.legIn = in;
            r.legOut = out;
            return r;
        }
        r.missingPair = direct;
        return r;
    }

    /** One turn of the conversation. Heard and rendered travel
     *  together — there is no state in which a rendering exists
     *  without the words it came from. */
    public static final class Turn {
        public char side;              // 'A' or 'B'
        public String fromIso = "";
        public String toIso = "";
        public String heard = "";
        public String rendered = "";
        /** PIVOT only: the intermediate English text — the hop
         *  is disclosed, and so is what passed through it. */
        public String via = "";
        public OutMode mode = OutMode.BOTH;
        public RouteKind route = RouteKind.NONE;
    }

    /** The conversation: sides, turns, and whose turn it is. */
    public static final class Conversation {
        public String sideA = "en";
        public String sideB = "";
        public final List<Turn> turns = new ArrayList<>();
        private char next = 'A';

        public char nextSide() { return next; }

        public String fromIso() { return next == 'A' ? sideA : sideB; }
        public String toIso() { return next == 'A' ? sideB : sideA; }

        /** Record a completed turn; the floor passes to the
         *  other side. Refuses (null) when the route is NONE —
         *  a turn that can't be rendered is never recorded as
         *  if it had been. */
        public Turn addTurn(String heard, String rendered, OutMode mode,
                            Route route) {
            return addTurn(heard, rendered, "", mode, route);
        }

        public Turn addTurn(String heard, String rendered, String via,
                            OutMode mode, Route route) {
            if (route == null || !route.ok()) return null;
            Turn t = new Turn();
            t.side = next;
            t.fromIso = fromIso();
            t.toIso = toIso();
            t.heard = heard == null ? "" : heard;
            t.rendered = rendered == null ? "" : rendered;
            t.via = via == null ? "" : via;
            t.mode = mode == null ? OutMode.BOTH : mode;
            t.route = route.kind;
            turns.add(t);
            next = next == 'A' ? 'B' : 'A';
            return t;
        }

        /** Swap the two sides' languages; turns already taken
         *  keep the directions they actually happened in. */
        public void swapSides() {
            String tmp = sideA;
            sideA = sideB;
            sideB = tmp;
        }

        public void reset() {
            turns.clear();
            next = 'A';
        }
    }

    // ------------------------------------------------ transcript file

    /** The conversation as a file body — both halves of every
     *  turn, with directions (the receipt, kept). */
    public static String transcriptBody(Conversation c) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy — Translator conversation\n");
        sb.append("A: ").append(c.sideA).append('\n');
        sb.append("B: ").append(c.sideB).append('\n');
        sb.append("---\n");
        int n = 0;
        for (Turn t : c.turns) {
            n++;
            sb.append("TURN ").append(n).append(" | ").append(t.side)
                    .append(' ').append(t.fromIso).append("->").append(t.toIso)
                    .append(" | mode: ").append(t.mode.name())
                    .append(" | route: ").append(t.route.name()).append('\n');
            sb.append("HEARD: ").append(escape(t.heard)).append('\n');
            if (!t.via.isEmpty()) {
                sb.append("VIA: ").append(escape(t.via)).append('\n');
            }
            sb.append("RENDERED: ").append(escape(t.rendered)).append('\n');
        }
        return sb.toString();
    }

    /** Parse a transcript file body back into a conversation. */
    public static Conversation parseTranscript(String body) {
        Conversation c = new Conversation();
        if (body == null) return c;
        Turn cur = null;
        for (String line : body.split("\n", -1)) {
            if (line.startsWith("A: ") && cur == null && c.turns.isEmpty()) {
                c.sideA = line.substring(3).trim();
            } else if (line.startsWith("B: ") && cur == null && c.turns.isEmpty()) {
                c.sideB = line.substring(3).trim();
            } else if (line.startsWith("TURN ")) {
                cur = new Turn();
                cur.side = 'A';
                String[] p = line.split("\\|");
                if (p.length >= 2) {
                    String dir = p[1].trim();           // "A en->ja"
                    String[] d = dir.split("\\s+");
                    if (d.length >= 1 && d[0].length() == 1) cur.side = d[0].charAt(0);
                    if (d.length >= 2) {
                        String[] io = d[1].split("->");
                        if (io.length == 2) {
                            cur.fromIso = io[0].trim();
                            cur.toIso = io[1].trim();
                        }
                    }
                }
                if (p.length >= 3) {
                    String m = p[2].trim().replace("mode:", "").trim();
                    try { cur.mode = OutMode.valueOf(m); } catch (Exception ignored) {}
                }
                if (p.length >= 4) {
                    String m = p[3].trim().replace("route:", "").trim();
                    try { cur.route = RouteKind.valueOf(m); } catch (Exception ignored) {}
                }
                c.turns.add(cur);
                // keep the alternation where the file left it
                c.next = cur.side == 'A' ? 'B' : 'A';
                if (!c.turns.isEmpty() && c.turns.size() == 1) {
                    // first turn defines nothing about the sides'
                    // languages beyond its own direction
                }
            } else if (line.startsWith("HEARD: ") && cur != null) {
                cur.heard = unescape(line.substring(7));
            } else if (line.startsWith("VIA: ") && cur != null) {
                cur.via = unescape(line.substring(5));
            } else if (line.startsWith("RENDERED: ") && cur != null) {
                cur.rendered = unescape(line.substring(10));
            }
        }
        return c;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n);
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private TranslatorCore() {}
}
