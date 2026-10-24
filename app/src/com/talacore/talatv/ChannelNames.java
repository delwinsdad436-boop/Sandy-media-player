package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Real channel names (v0.5.0, Delwin's field report #1: "the names are
 * just more randomized... you can't tell what a station is").
 *
 * The built-in directory's playlist names are feed titles, not station
 * names — 9,538 of the 11,356 carry junk like "(1080p)", "[Not 24/7]"
 * or a trailing "HD". iptv-org's own channels database knows the real
 * name (and a logo) for the channel behind each tvg-id. This class is
 * the join: tvg-id (minus its @feed suffix) -> {canonical name, logo}.
 *
 * The map itself is a plain TSV (key \t urlenc(name) \t urlenc(logo)),
 * seeded from a bundled snapshot (app/assets/channels_map.tsv, built
 * by work/v050/make_map.py) and refreshed from the live API files
 * (buildMapTsv below) whenever the guide refreshes. Measured at build
 * time on the real directory: 11,356 of 11,356 entries mapped to a
 * canonical name; 11,079 also got a logo. User-loaded playlists can
 * carry ids the database doesn't know — those fall back to cleanName.
 *
 * Pure logic only (no Android imports) so the JVM harness can drive
 * it against the real snapshot files.
 */
public final class ChannelNames {

    /** tvg-id key -> {canonical name, logo url}. */
    private static volatile Map<String, String[]> map = new HashMap<>();

    private ChannelNames() {}

    public static void setMap(Map<String, String[]> m) {
        map = m == null ? new HashMap<>() : m;
    }

    public static Map<String, String[]> getMap() {
        return map;
    }

    /** The join key: the database id is the tvg-id before any @feed. */
    public static String keyOf(String tvgId) {
        if (tvgId == null) return "";
        int at = tvgId.indexOf('@');
        return at >= 0 ? tvgId.substring(0, at) : tvgId;
    }

    /** Apply the map to one channel (sets canonName / canonLogo). */
    public static void applyTo(M3uParser.Channel c) {
        if (c == null || c.tvgId == null || c.tvgId.isEmpty()) return;
        String[] hit = map.get(keyOf(c.tvgId));
        if (hit == null) return;
        if (hit.length > 0 && hit[0] != null && !hit[0].isEmpty()) c.canonName = hit[0];
        if (hit.length > 1 && hit[1] != null && !hit[1].isEmpty()) c.canonLogo = hit[1];
        if (hit.length > 2 && hit[2] != null && !hit[2].isEmpty()) c.canonDef = hit[2];
    }

    /**
     * The per-channel DEFINITION line (v0.6.0, field note #2c): one
     * plain phrase saying what the channel is. Source order: the
     * channels database's own categories (joined into the map at
     * build/refresh time); else the playlist's own group when it
     * says something real. When neither knows, this returns "" —
     * honest absence: the row shows no definition rather than an
     * invented one.
     */
    public static String definitionFor(M3uParser.Channel c) {
        if (c == null) return "";
        if (c.canonDef != null && !c.canonDef.isEmpty()) return c.canonDef;
        String g = c.group == null ? "" : c.group.trim();
        if (!g.isEmpty() && !"Undefined".equalsIgnoreCase(g)) return g;
        return "";
    }

    public static void applyToAll(List<M3uParser.Channel> channels) {
        if (channels == null) return;
        for (M3uParser.Channel c : channels) applyTo(c);
    }

    /** Count of channels in the list that the map names. */
    public static int mappedCount(List<M3uParser.Channel> channels) {
        int n = 0;
        if (channels != null) {
            for (M3uParser.Channel c : channels) {
                if (!c.canonName.isEmpty()) n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ search

    /**
     * Live TV search (v0.5.0, Delwin #2): a query matches the canonical
     * name, the raw playlist name, or the group — case-insensitively.
     */
    public static boolean matchesQuery(M3uParser.Channel c, String qLowerTrimmed) {
        if (qLowerTrimmed == null || qLowerTrimmed.isEmpty()) return true;
        if (c.displayName().toLowerCase(Locale.US).contains(qLowerTrimmed)) return true;
        if (c.name != null && c.name.toLowerCase(Locale.US).contains(qLowerTrimmed)) return true;
        if (c.group != null && c.group.toLowerCase(Locale.US).contains(qLowerTrimmed)) return true;
        return false;
    }

    // ------------------------------------------------------- name cleaner

    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern PAREN_RES = Pattern.compile(
            "\\(\\s*(\\d{3,4}\\s*[pi]|hd|sd|fhd|uhd|4k|8k|hevc|h\\.?264|h\\.?265|x264|x265)\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_QUALITY = Pattern.compile(
            "\\s+(HD|SD|FHD|UHD|4K|HEVC)$", Pattern.CASE_INSENSITIVE);

    /**
     * The fallback for unmapped entries: strip the feed-title junk and
     * keep the station. Conservative on purpose — bracketed labels and
     * resolution tokens go; meaningful parentheses (a country, a
     * region) stay; only a trailing standalone quality word is cut.
     * Never returns empty: if cleaning would erase the name, the raw
     * name (trimmed) is the answer.
     */
    public static String cleanName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return s;
        s = BRACKET_TAG.matcher(s).replaceAll(" ");
        s = PAREN_RES.matcher(s).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        String prev;
        do {
            prev = s;
            s = TRAILING_QUALITY.matcher(s).replaceAll("").trim();
        } while (!s.equals(prev));
        s = s.replaceAll("[\\s·|]+$", "").trim();
        return s.isEmpty() ? raw.trim() : s;
    }

    // ------------------------------------------------------------ map TSV

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    public static Map<String, String[]> parseMapTsv(String text) throws IOException {
        Map<String, String[]> out = new HashMap<>();
        BufferedReader r = new BufferedReader(new StringReader(text == null ? "" : text));
        String line;
        while ((line = r.readLine()) != null) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 2) continue;
            String logo = p.length > 2 ? dec(p[2]) : "";
            String def = p.length > 3 ? dec(p[3]) : "";
            out.put(p[0], new String[]{dec(p[1]), logo, def});
        }
        return out;
    }

    public static String toMapTsv(Map<String, String[]> m) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy channel-name map - iptv-org database\n");
        for (Map.Entry<String, String[]> e : m.entrySet()) {
            sb.append(e.getKey()).append('\t')
              .append(enc(e.getValue()[0])).append('\t')
              .append(enc(e.getValue().length > 1 ? e.getValue()[1] : ""))
              .append('\t')
              .append(enc(e.getValue().length > 2 ? e.getValue()[2] : ""))
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * A channel's definition from its database record (v0.6.0): the
     * first two categories, humanized — "Animation · Kids". Empty
     * when the database lists none (26,262 of 31,632 records carry
     * categories in the 2026-10-17 snapshot; the rest stay honestly
     * undefined here and fall back to the playlist group at display).
     */
    private static String definitionOf(JSONObject c) {
        JSONArray cats = c.optJSONArray("categories");
        if (cats == null || cats.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cats.length() && i < 2; i++) {
            String cat = cats.optString(i, "");
            if (cat.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Character.toUpperCase(cat.charAt(0)));
            if (cat.length() > 1) sb.append(cat.substring(1));
        }
        return sb.toString();
    }

    // ------------------------------------------------- build from the API

    /**
     * Build the join map from iptv-org's published API files
     * (https://iptv-org.github.io/api/channels.json + logos.json) —
     * the same rule as work/v050/make_map.py: canonical name from the
     * channels database; logo = an in-use raster (PNG/JPG/WEBP),
     * preferring the smallest that is at least 200 px wide.
     */
    public static Map<String, String[]> buildMap(String channelsJson, String logosJson)
            throws Exception {
        Map<String, String[]> out = new HashMap<>();
        JSONArray chans = new JSONArray(channelsJson);
        for (int i = 0; i < chans.length(); i++) {
            JSONObject c = chans.getJSONObject(i);
            String id = c.optString("id", "");
            String name = c.optString("name", "");
            if (!id.isEmpty() && !name.isEmpty()) {
                out.put(id, new String[]{name, "", definitionOf(c)});
            }
        }
        if (logosJson != null && !logosJson.isEmpty()) {
            Map<String, Integer> bestWidth = new HashMap<>();
            JSONArray logos = new JSONArray(logosJson);
            for (int i = 0; i < logos.length(); i++) {
                JSONObject l = logos.getJSONObject(i);
                if (!l.optBoolean("in_use", true)) continue;
                String fmt = l.optString("format", "").toUpperCase(Locale.US);
                if (!("PNG".equals(fmt) || "JPG".equals(fmt)
                        || "JPEG".equals(fmt) || "WEBP".equals(fmt))) continue;
                String cid = l.optString("channel", "");
                String url = l.optString("url", "");
                if (cid.isEmpty() || url.isEmpty() || !out.containsKey(cid)) continue;
                int w = l.optInt("width", 0);
                Integer cur = bestWidth.get(cid);
                if (cur == null || (cur < 200 && w >= 200) || (w >= 200 && w < cur)) {
                    bestWidth.put(cid, w);
                    out.get(cid)[1] = url;
                }
            }
        }
        return out;
    }
}
