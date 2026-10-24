package com.talacore.talatv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the durable state: the playlist list, the favorites set, the
 * parsed-channel cache (so the guide opens instantly and only re-fetches
 * on Refresh or first run), and — new in v0.2.0 — the My Shows watchlist,
 * the watched-episode set, and per-title resume positions.
 */
public final class ChannelStore {

    public static final class Playlist {
        public String name;
        public String url;
        public boolean builtin;
        public Playlist(String name, String url, boolean builtin) {
            this.name = name; this.url = url; this.builtin = builtin;
        }
    }

    public static final String BUILTIN_NAME = "iptv-org — free-to-air (world)";
    public static final String BUILTIN_URL = "https://iptv-org.github.io/iptv/index.m3u";
    // v0.2.0: the built-in guide is assembled from iptv-org's facet indexes,
    // which carry the same streams as index.m3u (verified: identical URL set)
    // with the group set to category / language / country respectively.
    public static final String FACET_CATEGORY_URL = "https://iptv-org.github.io/iptv/index.category.m3u";
    public static final String FACET_LANGUAGE_URL = "https://iptv-org.github.io/iptv/index.language.m3u";
    public static final String FACET_COUNTRY_URL = "https://iptv-org.github.io/iptv/index.country.m3u";

    private static final String PREFS = "talatv";
    private static final String KEY_PLAYLISTS = "playlists";
    private static final String KEY_FAVS = "favs";
    private static final String KEY_CACHE_WHEN = "cache_when";
    private static final String KEY_WATCHLIST = "watchlist";
    private static final String KEY_WATCHED = "watched_keys";
    private static final String KEY_POSITIONS = "positions";
    private static final String CACHE_FILE = "channels.tsv";    // v0.1.0, 7 cols
    private static final String CACHE_FILE2 = "channels2.tsv";  // v0.2.0, 9 cols (+language, country)
    private static final String CACHE_FILE3 = "channels3.tsv";  // v0.5.0, 11 cols (+canonName, canonLogo)

    /** In-process hand-off of the play queue (Binder can't carry 11k rows). */
    public static volatile List<M3uParser.Channel> playQueue = null;
    public static volatile int playIndex = 0;

    private ChannelStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------- playlists

    public static List<Playlist> getPlaylists(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String raw = p.getString(KEY_PLAYLISTS, null);
        List<Playlist> out = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    out.add(new Playlist(o.getString("name"), o.getString("url"),
                            o.optBoolean("builtin", false)));
                }
            } catch (Exception ignored) { /* fall through to default */ }
        }
        if (out.isEmpty()) {
            out.add(new Playlist(BUILTIN_NAME, BUILTIN_URL, true));
            savePlaylists(ctx, out);
        }
        return out;
    }

    public static void savePlaylists(Context ctx, List<Playlist> lists) {
        try {
            JSONArray arr = new JSONArray();
            for (Playlist pl : lists) {
                JSONObject o = new JSONObject();
                o.put("name", pl.name);
                o.put("url", pl.url);
                o.put("builtin", pl.builtin);
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY_PLAYLISTS, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------- favorites

    public static Set<String> getFavorites(Context ctx) {
        Set<String> s = prefs(ctx).getStringSet(KEY_FAVS, null);
        return s == null ? new HashSet<>() : new HashSet<>(s);
    }

    public static void setFavorite(Context ctx, String url, boolean fav) {
        Set<String> s = getFavorites(ctx);
        if (fav) s.add(url); else s.remove(url);
        prefs(ctx).edit().putStringSet(KEY_FAVS, s).apply();
    }

    /**
     * The quality POSTURE (v0.12.1): "low" | "balanced" | "ultra" —
     * James's shape for AutoPlay: low-first (fastest start),
     * balanced (the happy medium the pipe sustains), ultra (highest
     * quality, buffer and all). Default balanced.
     */
    public static String getQualityPosture(Context ctx) {
        return prefs(ctx).getString("quality_posture", "balanced");
    }

    public static void setQualityPosture(Context ctx, String v) {
        prefs(ctx).edit().putString("quality_posture", v).apply();
    }

    /**
     * Auto-advance (v0.14.0, James's one-tap rule): when ON, a
     * finished episode rolls straight into the pre-resolved next
     * one; when OFF (the default), the next episode is SHOWN and
     * waits for the viewer's tap — a choice made once in
     * Settings, never a countdown pressing anyone.
     */
    public static boolean getAutoAdvanceNext(Context ctx) {
        return prefs(ctx).getBoolean("auto_advance_next", false);
    }

    public static void setAutoAdvanceNext(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("auto_advance_next", v).apply();
    }

    public static boolean getAutoSkip(Context ctx) {
        return prefs(ctx).getBoolean("autoskip", true);
    }

    public static void setAutoSkip(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("autoskip", v).apply();
    }

    // ------------------------------------------- v0.4.0 operator settings
    // First-run defaults ARE the setup (James is the operator; Mom just
    // surfs): auto-fresh ON, working-links-first ON, 30-minute buffer —
    // a fresh install is already right before anyone touches Settings.

    /** Live timeshift buffer size, minutes (10/30/60). */
    public static int getTimeshiftMinutes(Context ctx) {
        return TimeshiftMath.clampMinutes(
                prefs(ctx).getInt("timeshift_minutes", TimeshiftMath.DEFAULT_MINUTES));
    }

    public static void setTimeshiftMinutes(Context ctx, int v) {
        prefs(ctx).edit().putInt("timeshift_minutes", TimeshiftMath.clampMinutes(v)).apply();
    }

    /** Re-verify stale verdicts quietly on app start (default ON). */
    public static boolean getAutoFresh(Context ctx) {
        return prefs(ctx).getBoolean("autofresh", true);
    }

    public static void setAutoFresh(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("autofresh", v).apply();
    }

    /** Default state of the "Working links only" lists (default ON). */
    public static boolean getWorkingFirst(Context ctx) {
        return prefs(ctx).getBoolean("working_first", true);
    }

    public static void setWorkingFirst(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("working_first", v).apply();
    }

    /** When the background auto-fresh last ran (epoch ms; 0 = never). */
    public static long getFreshLastRun(Context ctx) {
        return prefs(ctx).getLong("fresh_last_run", 0);
    }

    public static void setFreshLastRun(Context ctx, long when, int count) {
        prefs(ctx).edit().putLong("fresh_last_run", when)
                .putInt("fresh_last_count", count).apply();
    }

    public static int getFreshLastCount(Context ctx) {
        return prefs(ctx).getInt("fresh_last_count", 0);
    }

    /**
     * Per-title prepared-dub choice. Default is TRUE: a sidecar exists
     * because the operator prepared it to be heard, so a title with a
     * dub plays the dub until someone picks the original in the menu —
     * that pick is what gets stored here.
     */
    public static boolean getDubChoice(Context ctx, String contentKey) {
        if (contentKey == null) return true;
        try {
            String raw = prefs(ctx).getString("dub_choices", null);
            if (raw == null) return true;
            org.json.JSONObject o = new org.json.JSONObject(raw);
            return o.has(contentKey) ? o.getBoolean(contentKey) : true;
        } catch (Exception e) {
            return true;
        }
    }

    public static void setDubChoice(Context ctx, String contentKey, boolean dub) {
        if (contentKey == null) return;
        try {
            String raw = prefs(ctx).getString("dub_choices", null);
            org.json.JSONObject o = raw == null ? new org.json.JSONObject()
                    : new org.json.JSONObject(raw);
            o.put(contentKey, dub);
            prefs(ctx).edit().putString("dub_choices", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------- cache

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    public static void saveCache(Context ctx, List<M3uParser.Channel> channels) {
        File f = new File(ctx.getFilesDir(), CACHE_FILE3);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (M3uParser.Channel c : channels) {
                w.write(enc(c.name));  w.write('\t');
                w.write(enc(c.group)); w.write('\t');
                w.write(enc(c.url));   w.write('\t');
                w.write(enc(c.logo));  w.write('\t');
                w.write(enc(c.tvgId)); w.write('\t');
                w.write(enc(c.userAgent)); w.write('\t');
                w.write(enc(c.source)); w.write('\t');
                w.write(enc(c.language)); w.write('\t');
                w.write(enc(c.country)); w.write('\t');
                w.write(enc(c.canonName)); w.write('\t');
                w.write(enc(c.canonLogo));
                w.write('\n');
            }
        } catch (Exception ignored) { }
        prefs(ctx).edit().putLong(KEY_CACHE_WHEN, System.currentTimeMillis()).apply();
    }

    public static List<M3uParser.Channel> loadCache(Context ctx) {
        List<M3uParser.Channel> v3 = readCacheFile(ctx, CACHE_FILE3, 11);
        if (v3 != null) return v3;
        List<M3uParser.Channel> v2 = readCacheFile(ctx, CACHE_FILE2, 9);
        if (v2 != null) return v2;
        return readCacheFile(ctx, CACHE_FILE, 7); // v0.1.0 cache still readable
    }

    private static List<M3uParser.Channel> readCacheFile(Context ctx, String name, int cols) {
        File f = new File(ctx.getFilesDir(), name);
        if (!f.exists()) return null;
        List<M3uParser.Channel> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < cols) continue;
                M3uParser.Channel c = new M3uParser.Channel();
                c.name = dec(p[0]); c.group = dec(p[1]); c.url = dec(p[2]);
                c.logo = dec(p[3]); c.tvgId = dec(p[4]); c.userAgent = dec(p[5]);
                c.source = dec(p[6]);
                if (cols >= 9) { c.language = dec(p[7]); c.country = dec(p[8]); }
                if (cols >= 11) { c.canonName = dec(p[9]); c.canonLogo = dec(p[10]); }
                out.add(c);
            }
        } catch (Exception e) {
            return null;
        }
        return out.isEmpty() ? null : out;
    }

    public static boolean hasCache(Context ctx) {
        return new File(ctx.getFilesDir(), CACHE_FILE3).exists()
                || new File(ctx.getFilesDir(), CACHE_FILE2).exists()
                || new File(ctx.getFilesDir(), CACHE_FILE).exists();
    }

    // ------------------------------------------------- watchlist (My Shows)

    public static List<TvMaze.Show> getWatchlist(Context ctx) {
        List<TvMaze.Show> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_WATCHLIST, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                TvMaze.Show s = new TvMaze.Show();
                s.id = o.optInt("id");
                s.name = o.optString("name", "");
                s.imageMedium = o.optString("image", "");
                s.language = o.optString("language", "");
                s.status = o.optString("status", "");
                String g = o.optString("genres", "");
                if (!g.isEmpty()) {
                    for (String part : g.split(",")) s.genres.add(part);
                }
                out.add(s);
            }
        } catch (Exception ignored) { }
        return out;
    }

    public static boolean isWatchlisted(Context ctx, int showId) {
        for (TvMaze.Show s : getWatchlist(ctx)) {
            if (s.id == showId) return true;
        }
        return false;
    }

    public static void setWatchlisted(Context ctx, TvMaze.Show show, boolean add) {
        List<TvMaze.Show> list = getWatchlist(ctx);
        list.removeIf(s -> s.id == show.id);
        if (add) list.add(0, show);
        try {
            JSONArray arr = new JSONArray();
            for (TvMaze.Show s : list) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("image", s.imageMedium);
                o.put("language", s.language);
                o.put("status", s.status);
                StringBuilder g = new StringBuilder();
                for (String genre : s.genres) {
                    if (g.length() > 0) g.append(',');
                    g.append(genre);
                }
                o.put("genres", g.toString());
                arr.put(o);
            }
            prefs(ctx).edit().putString(KEY_WATCHLIST, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    // --------------------------------- Archive-proven titles (v0.10.0)

    private static final String KEY_IA_PROVEN = "ia_proven_ids";

    /**
     * Shows a Sources visit actually found on the Internet Archive.
     * This is the only way a catalogue tile may claim Archive
     * availability — proven by a past check, never assumed.
     */
    public static boolean isArchiveProven(Context ctx, int showId) {
        java.util.Set<String> s = prefs(ctx).getStringSet(KEY_IA_PROVEN, null);
        return s != null && s.contains(String.valueOf(showId));
    }

    public static void markArchiveProven(Context ctx, int showId) {
        java.util.Set<String> cur = prefs(ctx).getStringSet(KEY_IA_PROVEN, null);
        java.util.Set<String> next = new java.util.HashSet<>();
        if (cur != null) next.addAll(cur);
        if (next.add(String.valueOf(showId))) {
            prefs(ctx).edit().putStringSet(KEY_IA_PROVEN, next).apply();
        }
    }

    // ------------------------------------------- watched + resume positions

    /** Stable identity for an on-demand title, independent of the source picked. */
    public static String episodeKey(int showId, int season, int number) {
        return "ep:" + showId + ":S" + season + "E" + number;
    }

    public static String titleKey(String title) {
        return "title:" + EpisodeKey.normalize(title);
    }

    public static boolean isWatched(Context ctx, String contentKey) {
        Set<String> s = prefs(ctx).getStringSet(KEY_WATCHED, null);
        return s != null && s.contains(contentKey);
    }

    public static void setWatched(Context ctx, String contentKey, boolean watched) {
        Set<String> s = prefs(ctx).getStringSet(KEY_WATCHED, null);
        Set<String> copy = s == null ? new HashSet<>() : new HashSet<>(s);
        if (watched) copy.add(contentKey); else copy.remove(contentKey);
        prefs(ctx).edit().putStringSet(KEY_WATCHED, copy).apply();
    }

    // v0.11.0: positions live in the filed kept places now (memory
    // hook). The first touch seeds the file from the legacy
    // preferences JSON, once, behind the kind marker — after that
    // the file is the only truth and the prefs copy is frozen.
    private static void seedPlaces(Context ctx) {
        try {
            if (!KeptPlaces.seeded(ctx, "pos")) {
                List<MemoryFiles.KeptPlace> rows = new ArrayList<>();
                String raw = prefs(ctx).getString(KEY_POSITIONS, null);
                if (raw != null) {
                    JSONObject o = new JSONObject(raw);
                    java.util.Iterator<String> keys = o.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        long pos = o.optLong(k, 0);
                        if (pos > 0) rows.add(new MemoryFiles.KeptPlace(
                                "pos", k, pos, System.currentTimeMillis(), ""));
                    }
                }
                KeptPlaces.seed(ctx, "pos", rows);
            }
            if (!KeptPlaces.seeded(ctx, "live")) {
                List<MemoryFiles.KeptPlace> rows = new ArrayList<>();
                String raw = prefs(ctx).getString("kept_live", null);
                if (raw != null) {
                    JSONObject o = new JSONObject(raw);
                    rows.add(new MemoryFiles.KeptPlace("live", "live",
                            o.optLong("pos", 0), o.optLong("savedAt", 0), raw));
                }
                KeptPlaces.seed(ctx, "live", rows);
            }
        } catch (Exception ignored) { }
    }

    public static long getPosition(Context ctx, String contentKey) {
        seedPlaces(ctx);
        return KeptPlaces.getPosition(ctx, contentKey);
    }

    public static void savePosition(Context ctx, String contentKey, long positionMs) {
        seedPlaces(ctx);
        KeptPlaces.putPosition(ctx, contentKey, positionMs);
    }

    // ------------------------------------------------- v0.5.0 state

    /** The chosen UI language code ("en", "es", …) or "" when unchosen. */
    public static String getUiLanguage(Context ctx) {
        return prefs(ctx).getString("ui_language", "");
    }

    public static void setUiLanguage(Context ctx, String code) {
        prefs(ctx).edit().putString("ui_language", code == null ? "" : code).apply();
    }

    public static boolean hasChosenLanguage(Context ctx) {
        return !getUiLanguage(ctx).isEmpty();
    }

    // ------------------------------------------ v0.6.0: the stance

    /** The whole Live TV stance (facets + search + scroll), one record. */
    public static String getStanceJson(Context ctx) {
        return prefs(ctx).getString("live_stance", "");
    }

    public static void setStanceJson(Context ctx, String json) {
        prefs(ctx).edit().putString("live_stance", json == null ? "" : json).apply();
    }

    /** Rumble cues (haptics) — default ON (Settings toggle exists). */
    public static boolean getRumble(Context ctx) {
        return prefs(ctx).getBoolean("rumble", true);
    }

    public static void setRumble(Context ctx, boolean v) {
        prefs(ctx).edit().putBoolean("rumble", v).apply();
    }

    /**
     * What the kept last-channel buffer does when it fills: HOLD the
     * beginning (default — the family's sleep case: "when you wake up,
     * hopefully you caught the whole episode"), or ROLL (the classic
     * sliding window that keeps the latest).
     */
    public static boolean getBufferFullHold(Context ctx) {
        return prefs(ctx).getBoolean("buffer_full_hold", true);
    }

    public static void setBufferFullHold(Context ctx, boolean hold) {
        prefs(ctx).edit().putBoolean("buffer_full_hold", hold).apply();
    }

    // ------------------------------------- the kept live place (item 8)

    /**
     * The last live channel's kept place. Live TV can't resume a fixed
     * position — the broadcast moved on — so what is kept is the
     * channel, the moment's distance behind the live edge when Sandy
     * closed, and whether the buffer filled and HELD. Together with
     * the persisted timeshift cache (TimeshiftCache no longer wipes on
     * close for this channel), reopening the channel can offer the
     * place back — honestly recomputed by LiveResumeMath, because the
     * edge kept moving while the app was shut.
     */
    public static final class KeptLive {
        public String url = "";
        public String name = "";
        public String userAgent = "";
        public long positionMs = 0;
        public long behindMs = 0;
        public long savedAtMs = 0;
        public boolean held = false;
    }

    public static KeptLive getKeptLive(Context ctx) {
        seedPlaces(ctx);
        String raw = KeptPlaces.getExtra(ctx, "live", "live");
        if (raw == null) return null;
        try {
            JSONObject o = new JSONObject(raw);
            KeptLive k = new KeptLive();
            k.url = o.optString("url", "");
            k.name = o.optString("name", "");
            k.userAgent = o.optString("ua", "");
            k.positionMs = o.optLong("pos", 0);
            k.behindMs = o.optLong("behind", 0);
            k.savedAtMs = o.optLong("savedAt", 0);
            k.held = o.optBoolean("held", false);
            return k.url.isEmpty() ? null : k;
        } catch (Exception e) {
            return null;
        }
    }

    public static void setKeptLive(Context ctx, KeptLive k) {
        if (k == null || k.url == null || k.url.isEmpty()) return;
        seedPlaces(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("url", k.url);
            o.put("name", k.name == null ? "" : k.name);
            o.put("ua", k.userAgent == null ? "" : k.userAgent);
            o.put("pos", k.positionMs);
            o.put("behind", k.behindMs);
            o.put("savedAt", k.savedAtMs);
            o.put("held", k.held);
            KeptPlaces.putPlace(ctx, "live", "live", k.positionMs, o.toString());
        } catch (Exception ignored) { }
    }

    public static void clearKeptLive(Context ctx) {
        seedPlaces(ctx);
        KeptPlaces.removePlace(ctx, "live", "live");
    }

    // ------------------------------------- recording display names
    // v0.11.0: recordings metadata is a FILE now (memory/recordings
    // .tsv, via RecordingsMeta). This face stays for its callers;
    // the first touch seeds the file from the legacy record_meta
    // preferences, once (RecordingsMeta.seed no-ops when the file
    // exists). A record-now name lands as the recording's title AND
    // its channel (the caller passes the channel's display name);
    // timer recordings write the fuller row themselves.

    private static void seedRecordings(Context ctx) {
        if (prefs(ctx).getBoolean("record_meta_migrated", false)) return;
        try {
            String raw = prefs(ctx).getString("record_meta", null);
            if (raw != null) {
                JSONObject o = new JSONObject(raw);
                java.util.Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String id = keys.next();
                    JSONObject m = o.optJSONObject(id);
                    if (m == null) continue;
                    if (RecordingsMeta.get(ctx, id) != null) continue;
                    MemoryFiles.RecordingMeta r = new MemoryFiles.RecordingMeta();
                    r.id = id;
                    r.title = m.optString("name", "");
                    r.channel = r.title;
                    r.airedStartMs = m.optLong("startedAt", 0);
                    r.origin = "now";
                    RecordingsMeta.upsert(ctx, r);
                }
            }
            prefs(ctx).edit().putBoolean("record_meta_migrated", true).apply();
        } catch (Exception ignored) { }
    }

    /** Recordings keep their channel's display name beside the download. */
    public static void putRecordMeta(Context ctx, String id, String name) {
        seedRecordings(ctx);
        MemoryFiles.RecordingMeta r = RecordingsMeta.get(ctx, id);
        if (r == null) {
            r = new MemoryFiles.RecordingMeta();
            r.id = id;
            r.origin = "now";
            r.airedStartMs = System.currentTimeMillis();
        }
        r.title = name == null ? "" : name;
        if (r.channel.isEmpty()) r.channel = r.title;
        RecordingsMeta.upsert(ctx, r);
    }

    public static String getRecordName(Context ctx, String id) {
        seedRecordings(ctx);
        MemoryFiles.RecordingMeta r = RecordingsMeta.get(ctx, id);
        if (r == null) return "";
        return r.title.isEmpty() ? r.channel : r.title;
    }

    public static void removeRecordMeta(Context ctx, String id) {
        seedRecordings(ctx);
        RecordingsMeta.remove(ctx, id);
    }
}
