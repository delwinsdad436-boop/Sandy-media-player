package com.talacore.talatv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The TV guide's store (v0.11.0): hosted XMLTV in, filed programmes
 * out — everything through the memory hook.
 *
 * Coverage is MEASURED, never promised: the bundled bridge
 * (assets/guide_map.tsv, distilled at build time from iptv-org's
 * guides.json against the live hosted files — 1,345 of Sandy's
 * 10,261 built-in channels, 1,228 of them id-verified inside the
 * files themselves; regenerated for 0.11.1 with a HEAD-liveness
 * check on every file, which dropped the 12 dead Flash rows the
 * 0.11.0 map shipped and re-preferred 3 channels onto the living
 * Foxtel file) says which hosted file MIGHT carry a channel.
 * The file is the proof: a channel shows programmes only when its
 * id was actually parsed out of a fetched file. No map row, or a
 * stale one, reads on the channel's face as "No guide for this
 * channel" — nothing is fabricated, ever.
 *
 * Fetches are per-source scoped files (the mjh per-service/country
 * files, a few MB each — never the 63 MB all-countries file), ETag-
 * conditional, parsed streaming, and cached as filed TSV rows
 * (epochs only) under memory/guide/. Freshness is kept per source
 * in memory/guide_state.tsv and stated on the guide's face.
 */
public final class GuideStore {

    private GuideStore() {}

    /** One bridge row: where a channel's guide may live. */
    public static final class MapRow {
        public String tvgId, source, fileUrl, xmlId, evidence;
    }

    /** Per-source freshness. */
    public static final class SourceState {
        public long fetchedAtMs = 0;
        public String etag = "";
    }

    private static List<MapRow> mapCache;
    private static Map<String, MapRow> mapIndex;
    private static GuideModel.GuideData dataCache;

    // --------------------------------------------------------- map

    /** Seed the hook's guide_map.tsv from the bundled asset
     *  whenever the bundled map differs from the filed copy
     *  (v0.11.1: the comparison is the whole text -- the 0.11.0
     *  first-data-line test could leave a regenerated map
     *  unseeded when its first row happened to survive). */
    private static void seedMap(Context ctx) {
        String asset = readAsset(ctx, "guide_map.tsv");
        if (asset.isEmpty()) return;
        String filed = MemoryHook.read(ctx, MemoryHook.GUIDE_MAP);
        if (filed.isEmpty() || !asset.equals(filed)) {
            MemoryHook.write(ctx, MemoryHook.GUIDE_MAP, asset);
            mapCache = null;
            mapIndex = null;
        }
    }


    private static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name);
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static synchronized List<MapRow> map(Context ctx) {
        if (mapCache != null) return mapCache;
        seedMap(ctx);
        List<MapRow> rows = new ArrayList<>();
        String text = MemoryHook.read(ctx, MemoryHook.GUIDE_MAP);
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            String[] c = s.split("\t", -1);
            if (c.length < 5) continue;
            MapRow r = new MapRow();
            r.tvgId = c[0].trim();
            r.source = c[1].trim();
            r.fileUrl = c[2].trim();
            r.xmlId = c[3].trim();
            r.evidence = c[4].trim();
            if (!r.tvgId.isEmpty() && !r.fileUrl.isEmpty()) rows.add(r);
        }
        mapCache = rows;
        return rows;
    }

    /** tvg-id → row index (pure; built once per map). v0.12.1: the
     *  guide screen used to linear-scan the whole 1,345-row map for
     *  EVERY channel (~17M compares on the UI thread for a full
     *  channel cache) — a device-only blowup that read in the hand
     *  as "the guide is not coming up". Lookups are O(1) now. */
    public static Map<String, MapRow> indexById(List<MapRow> rows) {
        Map<String, MapRow> out = new HashMap<>();
        if (rows != null) {
            for (MapRow r : rows) {
                if (r.tvgId != null && !r.tvgId.isEmpty()) {
                    out.putIfAbsent(r.tvgId, r);
                }
            }
        }
        return out;
    }

    /** Does the bridge carry this channel at all? */
    public static synchronized MapRow rowFor(Context ctx, String tvgBaseId) {
        if (tvgBaseId == null) return null;
        if (mapIndex == null) mapIndex = indexById(map(ctx));
        return mapIndex.get(tvgBaseId);
    }

    /** fileUrl → (xmlId → tvgId) bridges, for the parser. */
    public static Map<String, Map<String, String>> bridges(Context ctx) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (MapRow r : map(ctx)) {
            Map<String, String> b = out.get(r.fileUrl);
            if (b == null) { b = new HashMap<>(); out.put(r.fileUrl, b); }
            b.put(r.xmlId, r.tvgId);
        }
        return out;
    }

    /** The distinct hosted files covering any of these channels. */
    public static List<String> filesFor(Context ctx, List<String> tvgBaseIds) {
        return filesFor(ctx, new java.util.HashSet<>(tvgBaseIds));
    }

    /** Set form (v0.12.1): the List form's contains() made the
     *  bridge scan quadratic in the channel count. */
    public static List<String> filesFor(Context ctx,
                                        java.util.Set<String> tvgBaseIds) {
        Map<String, Boolean> wanted = new LinkedHashMap<>();
        for (MapRow r : map(ctx)) {
            if (tvgBaseIds.contains(r.tvgId)) wanted.put(r.fileUrl, Boolean.TRUE);
        }
        return new ArrayList<>(wanted.keySet());
    }

    /** Cache-file key for a source file URL. */
    public static String sourceKey(String fileUrl) {
        String s = fileUrl.replace("https://", "").replace("http://", "");
        return s.replaceAll("[^A-Za-z0-9]+", "_");
    }

    // ------------------------------------------------------- state

    public static synchronized Map<String, SourceState> state(Context ctx) {
        Map<String, SourceState> out = new HashMap<>();
        String text = MemoryHook.read(ctx, MemoryHook.GUIDE_STATE);
        for (String line : text.split("\n")) {
            String probe = line.trim();
            if (probe.isEmpty() || probe.startsWith("#")) continue;
            String s = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
            String[] c = s.split("\t", -1);
            if (c.length < 3) continue;
            try {
                SourceState ss = new SourceState();
                ss.fetchedAtMs = Long.parseLong(c[1]);
                ss.etag = TimerModel.dec(c[2]);
                out.put(TimerModel.dec(c[0]), ss);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static synchronized void saveState(Context ctx,
                                               Map<String, SourceState> st) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy guide freshness - file url, fetched-at ms, etag.\n");
        for (Map.Entry<String, SourceState> e : st.entrySet()) {
            sb.append(TimerModel.enc(e.getKey())).append('\t')
              .append(e.getValue().fetchedAtMs).append('\t')
              .append(TimerModel.enc(e.getValue().etag)).append('\n');
        }
        MemoryHook.write(ctx, MemoryHook.GUIDE_STATE, sb.toString());
    }

    /** A source is stale when never fetched or fetched > 6h ago. */
    public static boolean stale(SourceState ss, long nowMs) {
        return ss == null || ss.fetchedAtMs == 0
                || nowMs - ss.fetchedAtMs > 6 * 3600_000L;
    }

    // ------------------------------------------------------- fetch

    public static final class FetchResult {
        public int filesOk = 0, filesFailed = 0, programmes = 0;
        public boolean anyOk() { return filesOk > 0; }
    }

    /**
     * Fetch + parse + file the given source files (blocking — call
     * off the UI thread). A failed file keeps its old cache and its
     * old freshness stamp: the label on the guide's face stays true.
     */
    public static synchronized FetchResult fetch(Context ctx, List<String> fileUrls) {
        FetchResult res = new FetchResult();
        Map<String, Map<String, String>> bridges = bridges(ctx);
        Map<String, SourceState> st = state(ctx);
        for (String fileUrl : fileUrls) {
            Map<String, String> bridge = bridges.get(fileUrl);
            if (bridge == null || bridge.isEmpty()) continue;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(fileUrl).openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", Net.UA);
                SourceState old = st.get(fileUrl);
                if (old != null && !old.etag.isEmpty()) {
                    c.setRequestProperty("If-None-Match", old.etag);
                }
                int code = c.getResponseCode();
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    SourceState ss = old == null ? new SourceState() : old;
                    ss.fetchedAtMs = System.currentTimeMillis();
                    st.put(fileUrl, ss);
                    res.filesOk++;
                    continue;
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    res.filesFailed++;
                    continue;
                }
                InputStream in = c.getInputStream();
                if (fileUrl.endsWith(".gz")) in = new GZIPInputStream(in);
                // Pipe evidence (v0.12.1): a guide fetch is a real,
                // timed transfer — count it and feed the meter, so
                // the connection statement rests on measurement.
                final long[] byteCount = {0};
                final long fetchStart = System.currentTimeMillis();
                InputStream raw = in;
                in = new java.io.FilterInputStream(raw) {
                    @Override public int read() throws java.io.IOException {
                        int b = super.read();
                        if (b >= 0) byteCount[0]++;
                        return b;
                    }
                    @Override public int read(byte[] b, int off, int len)
                            throws java.io.IOException {
                        int n = super.read(b, off, len);
                        if (n > 0) byteCount[0] += n;
                        return n;
                    }
                };
                GuideModel.GuideData data;
                try {
                    data = XmltvParser.parse(in, bridge);
                } finally {
                    try { in.close(); } catch (Exception ignored) { }
                }
                PipeMeter.sample(ctx, byteCount[0],
                        System.currentTimeMillis() - fetchStart);
                writeCache(ctx, fileUrl, data);
                SourceState ss = new SourceState();
                ss.fetchedAtMs = System.currentTimeMillis();
                ss.etag = c.getHeaderField("ETag");
                if (ss.etag == null) ss.etag = "";
                st.put(fileUrl, ss);
                res.filesOk++;
                res.programmes += countProgrammes(data);
                dataCache = null;
            } catch (Exception e) {
                res.filesFailed++;
            }
        }
        saveState(ctx, st);
        return res;
    }

    private static int countProgrammes(GuideModel.GuideData d) {
        int n = 0;
        for (List<GuideModel.Programme> rows : d.byChannel.values()) n += rows.size();
        return n;
    }

    // ------------------------------------------------------- cache

    private static void writeCache(Context ctx, String fileUrl,
                                   GuideModel.GuideData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy guide cache - channel, start, stop, title, desc,\n");
        sb.append("# category (epochs, url-encoded text). Source: ")
          .append(fileUrl).append('\n');
        long cutoff = System.currentTimeMillis() - 6 * 3600_000L;
        for (Map.Entry<String, List<GuideModel.Programme>> e
                : data.byChannel.entrySet()) {
            for (GuideModel.Programme p : e.getValue()) {
                if (p.stopMs <= cutoff) continue;
                sb.append(TimerModel.enc(p.channelId)).append('\t')
                  .append(p.startMs).append('\t')
                  .append(p.stopMs).append('\t')
                  .append(TimerModel.enc(p.title)).append('\t')
                  .append(TimerModel.enc(p.desc)).append('\t')
                  .append(TimerModel.enc(p.category)).append('\n');
            }
        }
        MemoryHook.writeFile(MemoryHook.guideFile(ctx, sourceKey(fileUrl)),
                sb.toString());
    }

    /** All filed programmes, pruned to the live window. Cached. */
    public static synchronized GuideModel.GuideData data(Context ctx) {
        if (dataCache != null) return dataCache;
        GuideModel.GuideData d = new GuideModel.GuideData();
        File dir = MemoryHook.guideDir(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".tsv")) continue;
                String text = MemoryHook.readFile(f);
                for (String line : text.split("\n")) {
                    // No trim: a programme with an empty category
                    // still has six fields, and trim() would eat
                    // the last separator and drop the row.
                    String probe = line.trim();
                    if (probe.isEmpty() || probe.startsWith("#")) continue;
                    String s = line.endsWith("\r")
                            ? line.substring(0, line.length() - 1) : line;
                    String[] c = s.split("\t", -1);
                    if (c.length < 6) continue;
                    try {
                        d.add(new GuideModel.Programme(
                                TimerModel.dec(c[0]),
                                Long.parseLong(c[1]), Long.parseLong(c[2]),
                                TimerModel.dec(c[3]), TimerModel.dec(c[4]),
                                TimerModel.dec(c[5])));
                    } catch (NumberFormatException ignored) { }
                }
            }
        }
        d.finish();
        d.prune(System.currentTimeMillis() - 6 * 3600_000L);
        dataCache = d;
        return d;
    }

    public static synchronized void invalidateData() { dataCache = null; }

    /** Base tvg-id of a channel (strip any @feed suffix). */
    public static String baseId(String tvgId) {
        if (tvgId == null) return "";
        int at = tvgId.indexOf('@');
        return at < 0 ? tvgId : tvgId.substring(0, at);
    }
}
