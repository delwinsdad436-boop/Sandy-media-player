package com.talacore.talatv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TeaTV-style engine, lawful sources only: given a catalog title
 * (TVmaze show episode, or a movie title), find playable entries among
 * the playlists the user has loaded, matched by title + SxxExx markers.
 * Results are quality-ranked best-first; the ranking comes only from
 * tokens in the entry's own name/URL — unlabeled stays "unknown quality".
 */
public final class SourceFinder {

    public static final class Source {
        public String kind = "";        // "Playlist", "My Library" or "Internet Archive"
        public String label = "";       // entry name / archive file name
        public String detail = "";      // playlist name / archive item title
        public String url = "";
        public String userAgent = "";
        public String qualityLabel = "unknown quality";
        public int qualityTier = 0;
        public long size;               // bytes when known (archive), else 0
        public String subUrl = "";      // sidecar subtitle (archive items)
        public String subMime = "";
        public String subLang = "";
        /** Language evidence (v0.12.1, for AutoPlay): the playlist
         *  facet name ("English") and/or the Archive MARC code
         *  ("eng") — "" when the source carries neither. */
        public String langFacet = "";
        public String langMarc = "";
    }

    private SourceFinder() {}

    // ------------------------------------------------------- episodes

    /**
     * Playlist entries that look like this exact episode of this show.
     * Strong match: title phrase + exact SxxExx. Weaker fallbacks (kept,
     * sorted after strong ones): title phrase + episode-only marker when
     * the wanted season is 1; title phrase + the episode's own name.
     */
    public static List<Source> findEpisode(List<M3uParser.Channel> channels,
                                           String showName, int season, int number,
                                           String episodeName) {
        List<Source> strong = new ArrayList<>();
        List<Source> weak = new ArrayList<>();
        if (channels == null) return strong;
        String showNorm = EpisodeKey.normalize(showName);
        String epNorm = EpisodeKey.normalize(episodeName);
        for (M3uParser.Channel c : channels) {
            String nameNorm = EpisodeKey.normalize(c.name);
            if (!EpisodeKey.containsPhrase(nameNorm, showNorm)) continue;
            EpisodeKey key = EpisodeKey.parse(c.name);
            if (key != null && key.season == season && key.episode == number) {
                strong.add(fromChannel(c));
            } else if (key != null && !key.seasonKnown && key.episode == number && season == 1) {
                weak.add(fromChannel(c));
            } else if (key == null && !epNorm.isEmpty()
                    && EpisodeKey.containsPhrase(nameNorm, epNorm)) {
                weak.add(fromChannel(c));
            }
        }
        List<Source> out = new ArrayList<>(strong);
        out.addAll(weak);
        sortByQuality(out);
        return out;
    }

    // ------------------------------------------------------- movies / titles

    /**
     * Playlist entries matching a plain title (movies, one-off specials).
     * Entries carrying episode markers are excluded — those are episodes,
     * not the movie.
     */
    public static List<Source> findTitle(List<M3uParser.Channel> channels, String title) {
        List<Source> out = new ArrayList<>();
        if (channels == null) return out;
        String titleNorm = EpisodeKey.normalize(title);
        for (M3uParser.Channel c : channels) {
            String nameNorm = EpisodeKey.normalize(c.name);
            if (!EpisodeKey.containsPhrase(nameNorm, titleNorm)) continue;
            if (EpisodeKey.parse(c.name) != null) continue;
            out.add(fromChannel(c));
        }
        sortByQuality(out);
        return out;
    }

    // ------------------------------------------------------- VOD shelves

    /** Entries that live on a playlist's on-demand shelves (Movies/Series/VOD groups). */
    public static boolean isVodEntry(M3uParser.Channel c) {
        String g = c.group == null ? "" : c.group.toLowerCase(java.util.Locale.US);
        return g.contains("movie") || g.contains("series") || g.contains("vod")
                || g.contains("show") || g.contains("film");
    }

    public static Source fromChannel(M3uParser.Channel c) {
        Source s = new Source();
        // Library entries carry source "My Library" (LibraryIndex) — the
        // row must say so, not pretend the file came from a playlist.
        s.kind = LibraryIndex.SOURCE_NAME.equals(c.source) ? "My Library" : "Playlist";
        s.label = c.name;
        s.detail = c.source;
        s.url = c.url;
        s.userAgent = c.userAgent;
        s.qualityTier = EpisodeKey.qualityTier(c.name + " " + c.url);
        s.qualityLabel = EpisodeKey.qualityLabel(c.name + " " + c.url);
        s.langFacet = c.language == null ? "" : c.language;
        return s;
    }

    public static Source fromArchiveFile(IaClient.Item item, IaClient.FileEntry f,
                                         IaClient.FileEntry sub) {
        Source s = new Source();
        s.kind = "Internet Archive";
        s.label = f.name;
        s.detail = item.title.isEmpty() ? item.identifier : item.title;
        s.url = f.url;
        s.size = f.size;
        s.qualityTier = EpisodeKey.qualityTier(f.name);
        s.qualityLabel = EpisodeKey.qualityLabel(f.name);
        s.langMarc = item.language == null ? "" : item.language;
        if (sub != null) {
            s.subUrl = sub.url;
            s.subMime = sub.subMime;
            s.subLang = sub.subLang;
        }
        return s;
    }

    private static final java.util.Set<String> JUNK_TOKENS = new java.util.HashSet<>(
            java.util.Arrays.asList("2160p", "1080p", "720p", "480p", "360p", "hd", "sd",
                    "cam", "web", "webrip", "bluray", "bdrip", "dvdrip", "x264", "x265",
                    "h264", "h265", "hevc", "aac", "mp4", "mkv", "avi", "extended",
                    "remastered", "proper", "repack"));

    /**
     * A playlist entry's raw name ("Night of the Living Dead (1968) [1080p]")
     * cleaned into a searchable title ("Night of the Living Dead 1968"):
     * bracketed tags and release-junk tokens out, the year kept.
     */
    public static String cleanTitleForSearch(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\[[^\\]]*\\]", " ");
        s = s.replaceAll("\\((\\d{4})\\)", " $1 ");
        s = s.replaceAll("[\\(\\)]", " ");
        StringBuilder b = new StringBuilder();
        for (String tok : s.split("[^A-Za-z0-9]+")) {
            if (tok.isEmpty()) continue;
            if (JUNK_TOKENS.contains(tok.toLowerCase(java.util.Locale.US))) continue;
            if (b.length() > 0) b.append(' ');
            b.append(tok);
        }
        return b.toString().trim();
    }

    /** Best quality first; ties broken by larger known size, then name. */
    public static void sortByQuality(List<Source> sources) {
        Collections.sort(sources, (a, b) -> {
            if (a.qualityTier != b.qualityTier) return b.qualityTier - a.qualityTier;
            if (a.size != b.size) return a.size > b.size ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
    }
}
