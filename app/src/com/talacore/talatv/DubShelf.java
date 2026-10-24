package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The dubbed-episodes shelf (v0.12.0) — the cook's output, kept as
 * a FILE through the memory hook like everything Sandy keeps
 * (file in, file out): files/memory/dubs.tsv, one row per cooked
 * dub, pointing at the dubbed audio + translated subtitle files
 * under filesDir/dubbed/<show>/. A cooked dub is a file in the
 * library, listed here, playable from here, deletable from here.
 *
 * Columns (tab-separated, fields sanitized):
 *   id, cookedAtEpochSec, showKey, showTitle, season, episode,
 *   targetLang, videoUrl, dubPath, srtPath, jsonPath,
 *   lineCount, castCount, nearCount
 */
public final class DubShelf {

    public static final String FILE = "dubs.tsv";

    public static final class Entry {
        public String id = "";
        public long cookedAtSec = 0;
        public String showKey = "";
        public String showTitle = "";
        public int season = 0;
        public int episode = 0;
        public String targetLang = "";
        public String videoUrl = "";
        public String dubPath = "";
        public String srtPath = "";
        public String jsonPath = "";
        public int lineCount = 0;
        public int castCount = 0;
        public int nearCount = 0;

        public String displayName() {
            StringBuilder sb = new StringBuilder();
            sb.append(showTitle == null || showTitle.isEmpty() ? showKey : showTitle);
            if (season > 0 && episode > 0) {
                sb.append(String.format(java.util.Locale.US, " S%02dE%02d", season, episode));
            } else if (episode > 0) {
                sb.append(" · ").append(episode);
            }
            if (targetLang != null && !targetLang.isEmpty()) {
                sb.append(" → ").append(targetLang.toUpperCase(java.util.Locale.US));
            }
            return sb.toString();
        }
    }

    private DubShelf() {}

    // ------------------------------------------------------------ io

    public static List<Entry> list(Context ctx) {
        return parse(MemoryHook.read(ctx, FILE));
    }

    /** Insert or replace the row with this id. */
    public static void add(Context ctx, Entry e) {
        List<Entry> all = list(ctx);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(e.id)) { all.set(i, e); replaced = true; break; }
        }
        if (!replaced) all.add(e);
        MemoryHook.write(ctx, FILE, toTsv(all));
    }

    public static void remove(Context ctx, String id) {
        List<Entry> all = list(ctx);
        List<Entry> kept = new ArrayList<>();
        for (Entry e : all) if (!e.id.equals(id)) kept.add(e);
        MemoryHook.write(ctx, FILE, toTsv(kept));
    }

    // ------------------------------------------------------------ tsv (pure)

    public static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String row : text.split("\n")) {
            if (row.trim().isEmpty()) continue;
            String[] c = row.split("\t", -1);
            if (c.length < 14) continue;
            Entry e = new Entry();
            e.id = c[0];
            e.cookedAtSec = parseLong(c[1]);
            e.showKey = c[2];
            e.showTitle = c[3];
            e.season = (int) parseLong(c[4]);
            e.episode = (int) parseLong(c[5]);
            e.targetLang = c[6];
            e.videoUrl = c[7];
            e.dubPath = c[8];
            e.srtPath = c[9];
            e.jsonPath = c[10];
            e.lineCount = (int) parseLong(c[11]);
            e.castCount = (int) parseLong(c[12]);
            e.nearCount = (int) parseLong(c[13]);
            out.add(e);
        }
        return out;
    }

    public static String toTsv(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(clean(e.id)).append('\t')
              .append(e.cookedAtSec).append('\t')
              .append(clean(e.showKey)).append('\t')
              .append(clean(e.showTitle)).append('\t')
              .append(e.season).append('\t')
              .append(e.episode).append('\t')
              .append(clean(e.targetLang)).append('\t')
              .append(clean(e.videoUrl)).append('\t')
              .append(clean(e.dubPath)).append('\t')
              .append(clean(e.srtPath)).append('\t')
              .append(clean(e.jsonPath)).append('\t')
              .append(e.lineCount).append('\t')
              .append(e.castCount).append('\t')
              .append(e.nearCount).append('\n');
        }
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ')
                .replace('\r', ' ').trim();
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }
}
