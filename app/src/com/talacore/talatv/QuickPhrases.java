package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * Speak For Me's quick phrases (v0.15.0) — the handful of things a
 * person who cannot speak needs to say OFTEN ("Yes", "I need
 * help", "Thank you"), kept as a plain file on the memory hook
 * (speak_phrases.tsv) — file in, file out, like everything Sandy
 * keeps. One phrase per line; backslash escapes keep a phrase that
 * contains a tab or a newline intact across the round-trip.
 *
 * Pure: the door reads the file, parses here, writes the
 * serialization back. The harness pins the round-trip.
 */
public final class QuickPhrases {

    public static final int MAX = 24;

    private QuickPhrases() {}

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "").replace("\n", "\\n");
    }

    public static String unescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 't') out.append('\t');
                else if (n == 'n') out.append('\n');
                else out.append(n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Parse the file body into phrases (blanks and # lines skipped). */
    public static List<String> parse(String fileText) {
        List<String> out = new ArrayList<>();
        if (fileText == null) return out;
        for (String line : fileText.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String p = unescape(t);
            if (!p.isEmpty() && !out.contains(p)) out.add(p);
            if (out.size() >= MAX) break;
        }
        return out;
    }

    /** Serialize phrases back to the file body. */
    public static String serialize(List<String> phrases) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy — Speak For Me quick phrases (one per line)\n");
        if (phrases != null) {
            int n = 0;
            for (String p : phrases) {
                if (p == null || p.trim().isEmpty()) continue;
                sb.append(escape(p.trim())).append('\n');
                if (++n >= MAX) break;
            }
        }
        return sb.toString();
    }

    /** Add a phrase (deduped, capped); returns the new list. */
    public static List<String> add(List<String> phrases, String phrase) {
        List<String> out = phrases == null
                ? new ArrayList<>() : new ArrayList<>(phrases);
        String p = phrase == null ? "" : phrase.trim();
        if (p.isEmpty() || out.contains(p)) return out;
        out.add(p);
        while (out.size() > MAX) out.remove(0);
        return out;
    }

    /** Remove a phrase; returns the new list. */
    public static List<String> remove(List<String> phrases, String phrase) {
        List<String> out = phrases == null
                ? new ArrayList<>() : new ArrayList<>(phrases);
        out.remove(phrase);
        return out;
    }
}
