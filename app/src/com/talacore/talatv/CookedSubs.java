package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cooked subtitle tracks (v0.6.0) — the translation layer's output
 * form, and the record that keeps the source honest (UTX Law 1: the
 * original is never silently replaced).
 *
 * Two sidecars ride beside a video file (naming in DubTrack):
 *   <video>.tr-<lang>.srt   the playable track: translated lines,
 *                           guard-flagged lines prefixed "~ " so
 *                           uncertainty is visible in ANY player.
 *   <video>.tr-<lang>.json  the full record: per line, the original
 *                           text, the translation, the speaker and
 *                           the flag — what the parallel view and
 *                           the voiced renderer read.
 *
 * Pure logic; harness round-trips both formats.
 */
public final class CookedSubs {

    public static final class Line {
        public long startMs, endMs;
        public String text = "";      // translated
        public String orig = "";      // original-language text (may be "")
        public String speaker = "";   // diarizer key; "" until one exists
        public boolean flagged = false;
        /** Voice-fit disclosure (v0.12.0): how the spoken line met
         *  its window — 0 = natural pace, 1 = re-spoken faster to
         *  fit, 2 = still overran. A squeezed line is disclosed in
         *  the record, never hidden (James's line-fit honesty). */
        public int fit = 0;
    }

    private CookedSubs() {}

    // ------------------------------------------------------------- SRT

    public static String writeSrt(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Line l : lines) {
            sb.append(n++).append('\n');
            sb.append(srtTime(l.startMs)).append(" --> ").append(srtTime(l.endMs)).append('\n');
            if (l.flagged) sb.append("~ ");
            sb.append(l.text == null ? "" : l.text).append("\n\n");
        }
        return sb.toString();
    }

    public static List<Line> parseSrt(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null) return out;
        String[] blocks = text.replace("\r\n", "\n").split("\n\n");
        for (String block : blocks) {
            String[] ls = block.split("\n");
            long start = -1, end = -1;
            StringBuilder body = new StringBuilder();
            for (String raw : ls) {
                String line = raw.trim();
                if (line.contains("-->")) {
                    String[] p = line.split("-->");
                    if (p.length == 2) {
                        start = parseSrtTime(p[0].trim());
                        end = parseSrtTime(p[1].trim());
                    }
                } else if (!line.isEmpty() && !line.matches("\\d+")) {
                    if (body.length() > 0) body.append(' ');
                    body.append(line);
                }
            }
            if (start >= 0 && end > start) {
                Line l = new Line();
                l.startMs = start; l.endMs = end;
                String t = body.toString();
                if (t.startsWith("~ ")) { l.flagged = true; t = t.substring(2); }
                l.text = t;
                out.add(l);
            }
        }
        return out;
    }

    private static String srtTime(long ms) {
        long h = ms / 3600000, m = (ms / 60000) % 60, s = (ms / 1000) % 60, x = ms % 1000;
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, x);
    }

    private static long parseSrtTime(String t) {
        try {
            String[] p = t.replace('.', ',').split("[:,]");
            return Long.parseLong(p[0]) * 3600000 + Long.parseLong(p[1]) * 60000
                    + Long.parseLong(p[2]) * 1000 + Long.parseLong(p[3]);
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------ JSON

    public static String writeJson(String lang, List<Line> lines) throws Exception {
        JSONObject o = new JSONObject();
        o.put("sandy.trsubs", 1);
        o.put("lang", lang);
        JSONArray arr = new JSONArray();
        for (Line l : lines) {
            JSONObject lo = new JSONObject();
            lo.put("s", l.startMs);
            lo.put("e", l.endMs);
            lo.put("t", l.text);
            lo.put("o", l.orig);
            lo.put("sp", l.speaker);
            lo.put("f", l.flagged ? 1 : 0);
            if (l.fit != 0) lo.put("fit", l.fit);
            arr.put(lo);
        }
        o.put("lines", arr);
        return o.toString();
    }

    public static List<Line> parseJson(String text) throws Exception {
        List<Line> out = new ArrayList<>();
        JSONObject o = new JSONObject(text);
        JSONArray arr = o.optJSONArray("lines");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject lo = arr.getJSONObject(i);
            Line l = new Line();
            l.startMs = lo.optLong("s", 0);
            l.endMs = lo.optLong("e", 0);
            l.text = lo.optString("t", "");
            l.orig = lo.optString("o", "");
            l.speaker = lo.optString("sp", "");
            l.flagged = lo.optInt("f", 0) != 0;
            l.fit = lo.optInt("fit", 0);
            out.add(l);
        }
        return out;
    }
}
