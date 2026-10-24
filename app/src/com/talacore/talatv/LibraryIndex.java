package com.talacore.talatv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * My Library indexing (v0.3.0) — the Jellyfin lesson: the video files
 * James already HAS are a source pool, same as his playlists and the
 * Internet Archive. This class is the pure half: it parses a file NAME
 * into (show, season, episode, episode title, year) with exactly the
 * marker rules SourceFinder trusts (EpisodeKey), cleans release-junk
 * tokens off the human parts, and adapts entries into M3uParser.Channel
 * rows so the existing finders search the library unchanged.
 *
 * Honesty rules, same as everywhere in this app: a name that doesn't
 * parse is KEPT (listed as unsorted, never dropped, never dressed up
 * with a guessed show), and the raw file name stays on the entry.
 * The Android half (MediaStore + picked-folder walking, persistence)
 * lives in LibraryStore.
 */
public final class LibraryIndex {

    public static final String SOURCE_NAME = "My Library";

    public static final class Entry {
        public String fileName = "";      // as found on the device
        public String uri = "";           // content:// or file path — set by the scanner
        public String displayName = "";   // label for the Library screen
        public String showName = "";      // series name, or movie/one-off title; "" = unsorted
        public String episodeTitle = "";  // episodes only, when the name carries one
        public int season = -1;           // -1 = not an episode
        public int episode = -1;
        public int year;                  // 0 = none found

        public boolean isEpisode() { return episode >= 0; }
        public boolean isSorted() { return !showName.isEmpty(); }
    }

    private LibraryIndex() {}

    private static final Set<String> VIDEO_EXTS = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "m4v", "mov", "webm", "ts", "m2ts", "mpg",
            "mpeg", "wmv", "ogv", "flv", "3gp"));

    public static boolean isVideoFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        return dot >= 0 && VIDEO_EXTS.contains(lower.substring(dot + 1));
    }

    private static final Pattern P_YEAR =
            Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern P_DISC_SUFFIX =
            Pattern.compile("(?i)[\\s._\\-]*[\\(\\[]?\\s*disc[\\s._\\-]*(\\d{1,2})\\s*[\\)\\]]?\\s*$");
    private static final Pattern P_CAMERA =
            Pattern.compile("(?i)^(vid|img|mov|dsc|pxl|mvimg|screen recording|whatsapp video)[\\s._\\-]*\\d.*");

    /** Release-junk tokens stripped from human title parts — tags only, never title words. */
    private static final Set<String> JUNK = new HashSet<>(Arrays.asList(
            "2160p", "1080p", "720p", "480p", "360p", "4k", "uhd", "bluray",
            "bdrip", "brrip", "dvdrip", "dvdscr", "hdtv", "pdtv", "webdl",
            "webrip", "dl", "x264", "x265", "h264", "h265", "hevc", "avc", "aac",
            "ac3", "dts", "10bit", "8bit", "proper", "repack", "remastered",
            "yify", "rarbg", "fgt", "ettv", "etrg", "galaxyrg", "silence"));

    // ------------------------------------------------------------- parsing

    public static Entry fromFile(String fileName, String uri) {
        Entry e = new Entry();
        e.fileName = fileName == null ? "" : fileName;
        e.uri = uri == null ? "" : uri;

        String base = e.fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        String stem = stripExtension(base);
        e.year = yearOf(stem);

        int[] span = EpisodeKey.parseSpan(stem);
        if (span != null) {
            e.season = span[0];
            e.episode = span[1];
            e.showName = stripYearToken(cleanPart(stem.substring(0, span[2])), e.year);
            e.episodeTitle = stripYearToken(cleanPart(stem.substring(span[3])), e.year);
            if (e.showName.isEmpty()) {
                // marker with nothing before it ("S01E01.mkv") — keep the
                // episode numbers, admit we don't know the show
                e.displayName = stem;
            } else {
                e.displayName = e.showName + " — S"
                        + String.format(Locale.US, "%02dE%02d", e.season, e.episode)
                        + (e.episodeTitle.isEmpty() ? "" : " · " + e.episodeTitle);
            }
            return e;
        }

        // Not an episode: a movie, a one-off, or a disc extra.
        String t = stem;
        Matcher dm = P_DISC_SUFFIX.matcher(t);
        if (dm.find()) t = t.substring(0, dm.start());
        String cleaned = stripYearToken(cleanPart(t), e.year);
        if (cleaned.isEmpty() || !hasLetter(cleaned) || P_CAMERA.matcher(base).find()) {
            // unsorted — kept, labeled with its real name, no invented title
            e.showName = "";
            e.displayName = stem;
            return e;
        }
        e.showName = cleaned;
        e.displayName = stem;
        return e;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            if (VIDEO_EXTS.contains(ext)) return name.substring(0, dot);
        }
        return name;
    }

    private static int yearOf(String text) {
        Matcher m = P_YEAR.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Human part of a file name with release junk removed: bracketed
     * tags out, separators become spaces, junk tokens dropped. Title
     * words — including digits like the "13" in "Apollo 13" — stay.
     */
    static String cleanPart(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\[[^\\]]*\\]", " ");
        s = s.replaceAll("\\{[^}]*\\}", " ");
        // "WEB-DL" as a compound tag out — but a bare "Web" can be a title
        // word ("Charlotte's Web"), so only the compound is stripped here;
        // "dl" alone is in the junk set as backstop.
        s = s.replaceAll("(?i)\\bweb[\\s._\\-]*dl\\b", " ");
        StringBuilder b = new StringBuilder();
        boolean first = true;
        for (String tok : s.split("[^A-Za-z0-9']+")) {
            if (tok.isEmpty()) continue;
            String low = tok.toLowerCase(Locale.US);
            if (JUNK.contains(low)) continue;
            if (low.matches("\\d{3,4}p")) continue;                    // 1080p as bare token
            if (low.matches("[xh]\\d{3}")) continue;                  // x265 / h264 fragments
            if (b.length() > 0) b.append(' ');
            b.append(titleCaseToken(tok, first));
            first = false;
        }
        return b.toString().trim();
    }

    /** Small words that stay lowercase mid-title ("Heart of Gold", "Night of the Living Dead"). */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "of", "the", "a", "an", "and", "or", "for", "to", "in", "on", "at", "vs"));

    /**
     * Scene-style names are often all-lowercase ("firefly.1x03...") or
     * all-caps; a uniform-case token is title-cased so the same show
     * doesn't split into "firefly" and "Firefly" in the census. Mixed
     * case is the author's own and stays; short all-caps tokens read as
     * acronyms (NCIS, SG1) and stay too; stopwords stay lower mid-title.
     */
    private static String titleCaseToken(String tok, boolean first) {
        boolean hasLetter = false, allLower = true, allUpper = true;
        for (int i = 0; i < tok.length(); i++) {
            char ch = tok.charAt(i);
            if (Character.isLetter(ch)) {
                hasLetter = true;
                if (Character.isLowerCase(ch)) allUpper = false;
                if (Character.isUpperCase(ch)) allLower = false;
            }
        }
        if (!hasLetter || (!allLower && !allUpper)) return tok;
        if (allUpper && tok.length() <= 4) return tok;
        if (allLower && !first && STOPWORDS.contains(tok.toLowerCase(Locale.US))) return tok;
        return Character.toUpperCase(tok.charAt(0)) + tok.substring(1).toLowerCase(Locale.US);
    }

    /** Drop a standalone year token when the part has other words to stand on. */
    private static String stripYearToken(String part, int year) {
        if (year <= 0 || part.isEmpty()) return part;
        String y = String.valueOf(year);
        StringBuilder b = new StringBuilder();
        boolean dropped = false;
        for (String tok : part.split(" ")) {
            if (!dropped && tok.equals(y)) { dropped = true; continue; }
            if (b.length() > 0) b.append(' ');
            b.append(tok);
        }
        String out = b.toString().trim();
        return out.isEmpty() ? part : out;
    }

    // ------------------------------------------------------------- census

    public static int countEpisodes(List<Entry> entries) {
        int n = 0;
        for (Entry e : entries) if (e.isEpisode() && e.isSorted()) n++;
        return n;
    }

    public static int countUnsorted(List<Entry> entries) {
        int n = 0;
        for (Entry e : entries) if (!e.isSorted()) n++;
        return n;
    }

    /** Show name -> episode count, for the Library screen. Sorted by name. */
    public static Map<String, Integer> episodesByShow(List<Entry> entries) {
        Map<String, Integer> out = new TreeMap<>();
        for (Entry e : entries) {
            if (e.isEpisode() && e.isSorted()) {
                out.merge(e.showName, 1, Integer::sum);
            }
        }
        return out;
    }

    /** Non-episode sorted entries (movies, specials, disc extras). */
    public static List<Entry> oneOffs(List<Entry> entries) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (!e.isEpisode() && e.isSorted()) out.add(e);
        return out;
    }

    // ------------------------------------------------- SourceFinder adapter

    /**
     * The library in the shape the source finders already speak. The
     * channel NAME is rebuilt as "Show S01E01 Episode Title" so the
     * phrase + marker matching lands exactly as it does for playlists;
     * source is "My Library" so result rows label it honestly.
     */
    public static M3uParser.Channel toChannel(Entry e) {
        M3uParser.Channel c = new M3uParser.Channel();
        if (e.isEpisode() && e.isSorted()) {
            c.name = e.showName + String.format(Locale.US, " S%02dE%02d", e.season, e.episode)
                    + (e.episodeTitle.isEmpty() ? "" : " " + e.episodeTitle);
        } else if (e.isSorted()) {
            c.name = e.showName + (e.year > 0 ? " (" + e.year + ")" : "");
        } else {
            c.name = e.fileName;   // unsorted: the real name, matchable as-is
        }
        c.url = e.uri;
        c.source = SOURCE_NAME;
        c.group = e.isEpisode() && e.isSorted() ? e.showName : SOURCE_NAME;
        return c;
    }

    public static List<M3uParser.Channel> toChannels(List<Entry> entries) {
        List<M3uParser.Channel> out = new ArrayList<>();
        if (entries == null) return out;
        for (Entry e : entries) out.add(toChannel(e));
        return out;
    }

    /** Dedupe helper for the scanner: same URI twice = one file. */
    public static List<Entry> dedupeByUri(List<Entry> entries) {
        Map<String, Entry> byUri = new HashMap<>();
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.uri.isEmpty() || byUri.putIfAbsent(e.uri, e) == null) out.add(e);
        }
        return out;
    }
}
