package com.talacore.talatv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The voice language table (v0.13.0) — the 83 languages of the
 * show catalogue (assets/lang_registry.tsv's named rows), each
 * with what the MEASUREMENT found for it (work/v130/coverage_table
 * .tsv, measured 2026-10-18 against the live model houses):
 *
 *   tvName     the catalogue's own name for the language
 *   packCode   the MMS-TTS model code (ISO 639-3) when a native-
 *              script model exists and a Sandy voice pack can be
 *              built for it; empty when no pack exists
 *   iso1       the two-letter tag the phone's speech engine and
 *              the translation pairs speak in
 *   kind       native — a native-script MMS model exists
 *              uroman — the MMS model was trained on romanized
 *                       text, so a pack would speak gibberish
 *                       from native text (Korean); no pack
 *              none   — no MMS model exists at all
 *
 * The table ships as assets/voice_langs.tsv, GENERATED from the
 * measured coverage table so the phone resolves from the same
 * rows the build NOTES print. Resolution itself lives in
 * VoiceResolve; this class is only the data and its lookups.
 */
public final class VoiceLangs {

    public static final class Row {
        public String tvName = "";
        public String packCode = "";
        public String iso1 = "";
        public String kind = "none";
    }

    private static List<Row> rows;

    private VoiceLangs() {}

    public static List<Row> load(Context ctx) {
        if (rows != null) return rows;
        List<Row> out = new ArrayList<>();
        try (InputStream in = ctx.getAssets().open("voice_langs.tsv");
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                Row r = parseLine(line);
                if (r != null) out.add(r);
            }
        } catch (Exception ignored) {}
        rows = out;
        return rows;
    }

    /** One table line -> a row (null for comments/blanks/short). */
    public static Row parseLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) return null;
        String[] p = line.split("\t", -1);
        if (p.length < 4) return null;
        Row r = new Row();
        r.tvName = p[0].trim();
        r.packCode = p[1].trim();
        r.iso1 = p[2].trim();
        r.kind = p[3].trim();
        if (r.tvName.isEmpty() || r.kind.equals("-")) return null;
        return r;
    }

    public static Row byTvName(List<Row> rows, String tvName) {
        if (rows == null || tvName == null) return null;
        for (Row r : rows) if (r.tvName.equalsIgnoreCase(tvName)) return r;
        return null;
    }

    public static Row byIso1(List<Row> rows, String iso1) {
        if (rows == null || iso1 == null) return null;
        String want = iso1.trim().toLowerCase(java.util.Locale.US);
        int dash = want.indexOf('-');
        if (dash > 0) want = want.substring(0, dash);
        for (Row r : rows) if (r.iso1.equals(want)) return r;
        return null;
    }

    public static Row byPackCode(List<Row> rows, String packCode) {
        if (rows == null || packCode == null) return null;
        for (Row r : rows) {
            if (!r.packCode.isEmpty() && r.packCode.equals(packCode)) return r;
        }
        return null;
    }
}
