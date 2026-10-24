package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Internet Archive (archive.org) — public/open video items only.
 * advancedsearch finds items by title; /metadata/{id} lists the item's
 * files, from which playable video files and sidecar subtitle files
 * (.srt/.vtt) are picked. Parse functions are pure (JVM-testable).
 *
 * v0.3.0 — the free-shelf search got honest about how IA actually looks.
 * Measured on the live index (2026-10-17): a bare title search for a show
 * returns hundreds of hits, the top rows mostly junk (car rentals, drone
 * shows, reaction videos); but adding the EPISODE TITLE as a second
 * title clause collapses it to a handful of hits with the genuine
 * episode on top (Dragnet "The Big Deal": 187 -> 3 hits, 2 genuine;
 * Bonanza S1E1: 2 hits, both genuine; Firefly "Heart of Gold": exactly
 * 1 hit, the episode itself). So episodes search episode-aware first,
 * everything is ranked client-side by title/key/collection evidence,
 * and an opened item only contributes files that match the episode —
 * never every video in a bundle.
 */
public final class IaClient {

    public static final class Item {
        public String identifier = "";
        public String title = "";
        public int year;                                  // 0 = archive didn't say
        /** The Archive's own language tag (MARC code, e.g. "spa"), "" when untagged. */
        public String language = "";
        public final List<String> collections = new ArrayList<>();
        public int score;                                 // set by rankItems()
    }

    public static final class FileEntry {
        public String name = "";
        public String url = "";
        public long size;              // bytes, 0 = archive didn't say
        public String format = "";
        public boolean video;
        public boolean subtitle;
        public String subMime = "";    // for subtitle entries
        public String subLang = "";    // best-effort from the file name
    }

    private IaClient() {}

    // ------------------------------------------------------------ queries

    private static final String FIELDS =
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year&fl%5B%5D=collection"
            + "&fl%5B%5D=language";

    private static String buildUrl(String q, int rows) {
        try {
            return "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q, "UTF-8")
                    + FIELDS + "&rows=" + rows + "&page=1&output=json";
        } catch (UnsupportedEncodingException e) {
            return "https://archive.org/advancedsearch.php?q=" + q + "&output=json";
        }
    }

    /** Lucene-hostile characters out of a free-text query term. */
    private static String queryTerm(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\\\+\\-!(){}\\[\\]^\"~*?:/]", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Movie / plain-title search (v0.2.0 behavior, wider page + year/collection fields). */
    public static String searchUrl(String titleQuery) {
        String q = "title:(" + queryTerm(titleQuery) + ") AND mediatype:(movies)";
        return buildUrl(q, 20);
    }

    /**
     * Episode search, stage 1: show title AND episode title. This is the
     * precision instrument measured above — use it whenever the episode
     * has a name.
     */
    public static String episodeSearchUrl(String showName, String episodeName) {
        String q = "title:(" + queryTerm(showName) + ") AND title:("
                + queryTerm(episodeName) + ") AND mediatype:(movies)";
        return buildUrl(q, 15);
    }

    /**
     * Episode search, stage 2 (fallback): show title scoped to the
     * collections where television actually lives on IA. Wider net —
     * ranking decides what floats.
     */
    public static String showScopedSearchUrl(String showName) {
        String q = "title:(" + queryTerm(showName) + ") AND mediatype:(movies)"
                + " AND (collection:(television) OR collection:(classic_tv)"
                + " OR collection:(opensource_movies) OR collection:(feature_films))";
        return buildUrl(q, 25);
    }

    public static String metadataUrl(String identifier) {
        return "https://archive.org/metadata/" + identifier;
    }

    // --------------------------------------- language leg (v0.10.0)

    /**
     * The Archive's language code for a TVmaze language name. IA tags
     * its items with MARC codes (bibliographic forms: fre/ger/chi,
     * measured live 2026-10-18 — the -T forms fra/deu/zho return
     * almost nothing). "" when we have no honest mapping.
     *
     * Persian is the measured exception (v0.10.1, live 2026-10-18,
     * mediatype:movies): fas 74,338 / per 2,557 / fa 0 — the Archive
     * files Persian under its -T code, so fas is the honest mapping.
     */
    public static String iaLanguageCode(String tvmazeLanguageName) {
        if (tvmazeLanguageName == null) return "";
        switch (tvmazeLanguageName) {
            case "English": return "eng";
            case "Spanish": return "spa";
            case "French": return "fre";
            case "German": return "ger";
            case "Portuguese": return "por";
            case "Italian": return "ita";
            case "Russian": return "rus";
            case "Arabic": return "ara";
            case "Hindi": return "hin";
            case "Chinese": return "chi";
            case "Japanese": return "jpn";
            case "Korean": return "kor";
            case "Persian": return "fas";
            default:
                // v0.11.0: the catalogue opened to every language
                // TVmaze carries; the Archive leg follows where the
                // Archive's own tags were MEASURED (advancedsearch,
                // mediatype:movies, live 2026-10-18 — counts in the
                // build record; e.g. swe 307,229 · hun 109,664 ·
                // ukr 73,254 · dut 13,020). A name absent here has
                // no measured Archive tag: no language leg runs for
                // it — the untagged legs run as always, and nothing
                // is claimed that wasn't counted.
                String c = IA_MARC.get(tvmazeLanguageName);
                return c == null ? "" : c;
        }
    }

    private static final java.util.Map<String, String> IA_MARC =
            new java.util.HashMap<>();
    static {
        IA_MARC.put("Dutch", "dut");
        IA_MARC.put("Thai", "tha");
        IA_MARC.put("Norwegian", "nor");
        IA_MARC.put("Swedish", "swe");
        IA_MARC.put("Turkish", "tur");
        IA_MARC.put("Ukrainian", "ukr");
        IA_MARC.put("Polish", "pol");
        IA_MARC.put("Danish", "dan");
        IA_MARC.put("Hungarian", "hun");
        IA_MARC.put("Tagalog", "tgl");
        IA_MARC.put("Finnish", "fin");
        IA_MARC.put("Czech", "cze");
        IA_MARC.put("Hebrew", "heb");
        IA_MARC.put("Greek", "gre");
        IA_MARC.put("Bengali", "ben");
        IA_MARC.put("Urdu", "urd");
        IA_MARC.put("Serbian", "srp");
        IA_MARC.put("Romanian", "rum");
        IA_MARC.put("Vietnamese", "vie");
        IA_MARC.put("Armenian", "arm");
        IA_MARC.put("Icelandic", "ice");
        IA_MARC.put("Tamil", "tam");
        IA_MARC.put("Slovak", "slo");
        IA_MARC.put("Catalan", "cat");
        IA_MARC.put("Indonesian", "ind");
        IA_MARC.put("Croatian", "hrv");
        IA_MARC.put("Bulgarian", "bul");
        IA_MARC.put("Estonian", "est");
        IA_MARC.put("Malayalam", "mal");
        IA_MARC.put("Telugu", "tel");
        IA_MARC.put("Marathi", "mar");
        IA_MARC.put("Malay", "may");
        IA_MARC.put("Kazakh", "kaz");
        IA_MARC.put("Uzbek", "uzb");
        IA_MARC.put("Latvian", "lav");
        IA_MARC.put("Slovenian", "slv");
        IA_MARC.put("Lithuanian", "lit");
        IA_MARC.put("Georgian", "geo");
        IA_MARC.put("Welsh", "wel");
        IA_MARC.put("Irish", "gle");
        IA_MARC.put("Panjabi", "pan");
        IA_MARC.put("Gujarati", "guj");
        IA_MARC.put("Kannada", "kan");
        IA_MARC.put("Sinhalese", "sin");
        IA_MARC.put("Burmese", "bur");
        IA_MARC.put("Mongolian", "mon");
        IA_MARC.put("Pashto", "pus");
        IA_MARC.put("Swahili", "swa");
        IA_MARC.put("Javanese", "jav");
        IA_MARC.put("Basque", "baq");
        IA_MARC.put("Galician", "glg");
        IA_MARC.put("Albanian", "alb");
        IA_MARC.put("Azerbaijani", "aze");
        IA_MARC.put("Belarusian", "bel");
        IA_MARC.put("Bosnian", "bos");
        IA_MARC.put("Kyrgyz", "kir");
        IA_MARC.put("Malagasy", "mlg");
        IA_MARC.put("Yoruba", "yor");
        IA_MARC.put("Lao", "lao");
        IA_MARC.put("Central Khmer", "khm");
        IA_MARC.put("Zulu", "zul");
        IA_MARC.put("Afrikaans", "afr");
        IA_MARC.put("Fijian", "fij");
        IA_MARC.put("Kongo", "kon");
        IA_MARC.put("Wolof", "wol");
        IA_MARC.put("Chechen", "che");
        IA_MARC.put("Latin", "lat");
        IA_MARC.put("Divehi", "div");
        IA_MARC.put("Scottish Gaelic", "gla");
    }

    /** Episode search restricted to items the Archive tags with this language. */
    public static String episodeLangSearchUrl(String showName, String episodeName,
                                              String iaCode) {
        String q = "title:(" + queryTerm(showName) + ") AND title:("
                + queryTerm(episodeName) + ") AND mediatype:(movies)"
                + " AND language:(" + iaCode + ")";
        return buildUrl(q, 15);
    }

    /** Show-title search restricted to items tagged with this language. */
    public static String showLangSearchUrl(String showName, String iaCode) {
        String q = "title:(" + queryTerm(showName) + ") AND mediatype:(movies)"
                + " AND language:(" + iaCode + ")";
        return buildUrl(q, 20);
    }

    /**
     * Stable partition: items the Archive itself tags with the wanted
     * language float first, everything else keeps its ranked order.
     * An untagged item is NOT promoted — unknown is not a match.
     */
    public static List<Item> preferLanguage(List<Item> ranked, String iaCode) {
        if (iaCode == null || iaCode.isEmpty() || ranked == null) {
            return ranked == null ? new ArrayList<>() : ranked;
        }
        List<Item> tagged = new ArrayList<>();
        List<Item> rest = new ArrayList<>();
        for (Item it : ranked) {
            if (iaCode.equals(it.language)) tagged.add(it);
            else rest.add(it);
        }
        tagged.addAll(rest);
        return tagged;
    }

    // ------------------------------------------------------------ ranking

    private static Set<String> tokenSet(String normalized) {
        Set<String> out = new HashSet<>();
        if (normalized == null) return out;
        for (String t : normalized.split(" ")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Fraction of the needle's tokens that appear in the haystack (both normalized). */
    static double tokenCoverage(String haystackNorm, String needleNorm) {
        Set<String> needle = tokenSet(needleNorm);
        if (needle.isEmpty()) return 0;
        Set<String> hay = tokenSet(haystackNorm);
        int hit = 0;
        for (String t : needle) if (hay.contains(t)) hit++;
        return (double) hit / needle.size();
    }

    private static boolean hasJunkWords(String titleNorm) {
        return titleNorm.contains("reaction") || titleNorm.contains("trailer")
                || titleNorm.contains("review") || titleNorm.contains("unboxing")
                || titleNorm.contains("podcast") || titleNorm.contains("audiobook");
    }

    /**
     * Evidence score for one search hit against what we actually want.
     * Show-title coverage gates everything (zero coverage = not about
     * this show at all, dropped by rankItems). Episode-key and
     * episode-title matches dominate; television collections add a
     * little; news/community/social collections and reaction/trailer
     * bait subtract. Nothing here invents facts — it only orders hits.
     */
    public static int scoreItem(Item it, String showName, String epName,
                                int season, int number, int wantYear) {
        String titleNorm = EpisodeKey.normalize(it.title);
        String idNorm = EpisodeKey.normalize(it.identifier);
        String showNorm = EpisodeKey.normalize(showName);
        if (showNorm.isEmpty()) return 0;
        double cov = Math.max(tokenCoverage(titleNorm, showNorm),
                tokenCoverage(idNorm, showNorm) * 0.9);
        if (cov <= 0) return 0;
        int score = (int) Math.round(cov * 40);

        EpisodeKey k = EpisodeKey.parse(it.title);
        if (k == null) k = EpisodeKey.parse(it.identifier);
        if (number > 0 && k != null && k.matches(season, number)) {
            score += 35;
        }
        if (epName != null && !epName.isEmpty()) {
            score += (int) Math.round(
                    tokenCoverage(titleNorm, EpisodeKey.normalize(epName)) * 25);
        }
        for (String c : it.collections) {
            String cl = c.toLowerCase(Locale.US);
            if (cl.equals("television") || cl.equals("feature_films")
                    || cl.startsWith("classic_tv")) {
                score += 6;
            } else if (cl.equals("opensource_movies")) {
                score += 3;
            }
            if (cl.equals("tvnews") || cl.equals("tvarchive")
                    || cl.equals("community_media") || cl.equals("social-media-video")
                    || cl.equals("newsandpublicaffairs")) {
                score -= 8;
            }
        }
        if (hasJunkWords(titleNorm)) score -= 15;
        if (wantYear > 0 && it.year > 0) {
            if (it.year == wantYear) score += 8;
            else if (Math.abs(it.year - wantYear) > 3) score -= 6;
        }
        return score;
    }

    /** Score, drop the zero-evidence hits, sort best-first. Sets Item.score. */
    public static List<Item> rankItems(List<Item> items, String showName, String epName,
                                       int season, int number, int wantYear) {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            it.score = scoreItem(it, showName, epName, season, number, wantYear);
            if (it.score > 0) out.add(it);
        }
        out.sort((a, b) -> b.score - a.score);
        return out;
    }

    /**
     * Could this item plausibly BE the episode (as opposed to merely
     * mentioning the show)? True when the item's own title/identifier
     * carries the episode key, or the episode title is fully covered by
     * the item title with no reaction/trailer bait words. Only then may
     * its unmarked video files pass the episode filter.
     */
    public static boolean itemMatchesEpisode(Item it, String epName,
                                             int season, int number) {
        EpisodeKey k = EpisodeKey.parse(it.title);
        if (k == null) k = EpisodeKey.parse(it.identifier);
        if (number > 0 && k != null && k.matches(season, number)) {
            return true;
        }
        if (epName != null && !epName.isEmpty()) {
            String titleNorm = EpisodeKey.normalize(it.title);
            return tokenCoverage(titleNorm, EpisodeKey.normalize(epName)) >= 0.99
                    && !hasJunkWords(titleNorm);
        }
        return false;
    }

    /**
     * From one opened item, the video files that are THIS episode:
     * exact key match first; then episode-title phrase match; then —
     * only when the item itself matched the episode and holds just a
     * few videos — all of them (single-episode items carry unmarked
     * files, e.g. "Dragnet_Big_deal_04_26_56.mp4"). Special-features
     * folders (deleted scenes, featurettes, extras) never pass: a
     * deleted scene tagged S01E01 is not S01E01.
     */
    public static List<FileEntry> filterEpisodeFiles(List<FileEntry> files,
                                                     int season, int number,
                                                     String epName,
                                                     boolean itemMatched) {
        List<FileEntry> videos = new ArrayList<>();
        for (FileEntry f : files) {
            if (!f.video) continue;
            if (isSpecialFeaturePath(f.name)) continue;
            videos.add(f);
        }
        List<FileEntry> byKey = new ArrayList<>();
        for (FileEntry f : videos) {
            EpisodeKey k = EpisodeKey.parse(f.name);
            if (k != null && k.matches(season, number)) {
                byKey.add(f);
            }
        }
        if (!byKey.isEmpty()) return byKey;
        String epNorm = EpisodeKey.normalize(epName);
        if (!epNorm.isEmpty()) {
            List<FileEntry> byName = new ArrayList<>();
            for (FileEntry f : videos) {
                if (EpisodeKey.containsPhrase(EpisodeKey.normalize(f.name), epNorm)) {
                    byName.add(f);
                }
            }
            if (!byName.isEmpty()) return byName;
        }
        if (itemMatched && videos.size() <= 6) return videos;
        return new ArrayList<>();
    }

    private static boolean isSpecialFeaturePath(String name) {
        String low = name.toLowerCase(Locale.US);
        return low.contains("deleted scene") || low.contains("featurette")
                || low.contains("/extras/") || low.contains("bonus feature")
                || low.contains("behind the scenes");
    }

    /** A year (1900–2099) sitting in a free-text title, else 0. */
    public static int yearOf(String text) {
        if (text == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)").matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    // ------------------------------------------------------------ parsing

    public static List<Item> parseSearch(String json) throws Exception {
        List<Item> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONObject response = root.optJSONObject("response");
        if (response == null) return out;
        JSONArray docs = response.optJSONArray("docs");
        if (docs == null) return out;
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.getJSONObject(i);
            Item it = new Item();
            it.identifier = d.optString("identifier", "");
            it.title = d.optString("title", "");
            it.year = optYear(d.opt("year"));
            Object lang = d.opt("language");
            if (lang instanceof JSONArray && ((JSONArray) lang).length() > 0) {
                it.language = ((JSONArray) lang).optString(0, "");
            } else if (lang instanceof String) {
                it.language = (String) lang;
            }
            Object coll = d.opt("collection");
            if (coll instanceof JSONArray) {
                JSONArray arr = (JSONArray) coll;
                for (int j = 0; j < arr.length(); j++) {
                    it.collections.add(arr.optString(j, ""));
                }
            } else if (coll instanceof String && !((String) coll).isEmpty()) {
                it.collections.add((String) coll);
            }
            if (!it.identifier.isEmpty()) out.add(it);
        }
        return out;
    }

    private static int optYear(Object y) {
        if (y instanceof Number) return ((Number) y).intValue();
        if (y instanceof String) {
            try { return Integer.parseInt(((String) y).trim()); }
            catch (Exception e) { return 0; }
        }
        if (y instanceof JSONArray && ((JSONArray) y).length() > 0) {
            return optYear(((JSONArray) y).opt(0));
        }
        return 0;
    }

    /**
     * From one item's metadata: every playable video file and every sidecar
     * subtitle, with direct download URLs. Derivative/thumbnail clutter is
     * filtered by extension + format, conservatively.
     */
    public static List<FileEntry> parseMetadata(String json, String identifier) throws Exception {
        List<FileEntry> out = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray files = root.optJSONArray("files");
        if (files == null) return out;
        for (int i = 0; i < files.length(); i++) {
            JSONObject f = files.getJSONObject(i);
            String name = f.optString("name", "");
            if (name.isEmpty()) continue;
            String format = f.optString("format", "");
            String lower = name.toLowerCase(Locale.US);
            String ext = lower.contains(".")
                    ? lower.substring(lower.lastIndexOf('.') + 1) : "";

            FileEntry e = new FileEntry();
            e.name = name;
            e.format = format;
            e.size = parseLong(f.optString("size", "0"));
            e.url = "https://archive.org/download/" + identifier + "/" + encodePath(name);

            if (ext.equals("srt") || ext.equals("vtt")) {
                e.subtitle = true;
                e.subMime = ext.equals("vtt")
                        ? "text/vtt" : "application/x-subrip";
                e.subLang = guessLang(name);
                out.add(e);
                continue;
            }
            boolean videoExt = ext.equals("mp4") || ext.equals("m4v") || ext.equals("mkv")
                    || ext.equals("avi") || ext.equals("ogv") || ext.equals("mov")
                    || ext.equals("webm");
            boolean videoFmt = format.contains("MPEG4") || format.contains("h.264")
                    || format.contains("Matroska") || format.contains("Ogg Video")
                    || format.contains("QuickTime") || format.contains("Windows Media")
                    || format.contains("WebM");
            // thumbnails / previews / sample clips are not the feature
            if (lower.contains("_thumb") || lower.contains(".thumbs")
                    || format.contains("Thumbnail") || format.contains("Animated GIF")
                    || lower.contains("_sample")) {
                continue;
            }
            if (videoExt || videoFmt) {
                e.video = true;
                out.add(e);
            }
        }
        return out;
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; }
    }

    /** Encode each path segment, keep the slashes. */
    private static String encodePath(String name) {
        try {
            String[] parts = name.split("/");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) b.append('/');
                b.append(URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            }
            return b.toString();
        } catch (UnsupportedEncodingException e) {
            return name;
        }
    }

    /** "movie.en.srt" -> "en"; "subs/english.vtt" -> "en"; else "" (unknown). */
    private static String guessLang(String fileName) {
        String lower = fileName.toLowerCase(Locale.US);
        String[] toks = lower.split("[^a-z]+");
        for (String t : toks) {
            if (t.equals("en") || t.equals("eng") || t.equals("english")) return "en";
            if (t.equals("es") || t.equals("spa") || t.equals("spanish")) return "es";
            if (t.equals("fr") || t.equals("fre") || t.equals("french")) return "fr";
            if (t.equals("de") || t.equals("ger") || t.equals("german")) return "de";
            if (t.equals("pt") || t.equals("por") || t.equals("portuguese")) return "pt";
            if (t.equals("it") || t.equals("ita") || t.equals("italian")) return "it";
        }
        return "";
    }
}
