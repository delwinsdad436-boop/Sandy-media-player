package com.talacore.talatv;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses season/episode markers and quality labels out of the free-form
 * names found in playlists (and archive file names). This is a matching
 * technique, not a source of truth: when nothing parses, the answer is
 * null / "unknown quality" — never a guess dressed up as a fact.
 */
public final class EpisodeKey {

    public final int season;   // 0 = no season stated, or an explicit S00 (specials)
    public final int episode;
    /**
     * True when the marker actually named a season (SxxExx, "Season N
     * Episode M", NxNN). False for episode-only word markers ("Episode
     * 7"), whose season is unstated. An explicit "S00E01" has
     * seasonKnown=true with season 0 — it is a specials numbering, NOT
     * season 1, and matchers must not promote it.
     */
    public final boolean seasonKnown;

    public EpisodeKey(int season, int episode) {
        this(season, episode, true);
    }

    public EpisodeKey(int season, int episode, boolean seasonKnown) {
        this.season = season;
        this.episode = episode;
        this.seasonKnown = seasonKnown;
    }

    // Boundaries are "not preceded by a letter/digit" + "not followed by
    // a digit" rather than \b: real file names glue markers with
    // underscores ("Bonanza_s01e01_-_..."), and \b does not exist next to
    // '_' (a word character), which silently un-parsed exactly the
    // underscore-heavy names the Internet Archive and phones produce.
    private static final Pattern P_SE =
            Pattern.compile("(?i)(?<![A-Za-z0-9])S[\\s._\\-]*(\\d{1,2})[\\s._\\-]*E[\\s._\\-]*(\\d{1,3})(?![0-9])");
    private static final Pattern P_SEASON_EP =
            Pattern.compile("(?i)(?<![A-Za-z0-9])season[\\s._\\-]*(\\d{1,2})\\b[^A-Za-z0-9]{0,12}?episode[\\s._\\-]*(\\d{1,3})(?![0-9])");
    private static final Pattern P_X =
            Pattern.compile("(?<![A-Za-z0-9])(\\d{1,2})x(\\d{1,3})(?![0-9])");
    private static final Pattern P_EP_WORD =
            Pattern.compile("(?i)(?<![A-Za-z0-9])episode[\\s._\\-]*(\\d{1,3})(?![0-9])");
    private static final Pattern P_EP_SHORT =
            Pattern.compile("(?i)(?<![A-Za-z0-9])ep\\.?[\\s._\\-]*(\\d{1,3})(?![0-9])");

    /** First marker found wins, in SxxExx > "Season N Episode M" > NxNN > "Episode N" > "Ep. N" order. */
    public static EpisodeKey parse(String name) {
        int[] s = parseSpan(name);
        return s == null ? null : new EpisodeKey(s[0], s[1], s[4] == 1);
    }

    /**
     * Like parse(), but also reports where the marker sits:
     * {season, episode, startIndex, endIndex, seasonKnown?1:0} of the
     * matched marker in the input, or null when no marker is present.
     * Library file-name parsing splits show name / episode title around
     * the marker.
     */
    public static int[] parseSpan(String name) {
        if (name == null) return null;
        Matcher m = P_SE.matcher(name);
        if (m.find()) return span(m, false);
        m = P_SEASON_EP.matcher(name);
        if (m.find()) return span(m, false);
        m = P_X.matcher(name);
        if (m.find()) return span(m, false);
        m = P_EP_WORD.matcher(name);
        if (m.find()) return span(m, true);
        m = P_EP_SHORT.matcher(name);
        if (m.find()) return span(m, true);
        return null;
    }

    private static int[] span(Matcher m, boolean episodeOnly) {
        if (episodeOnly) {
            return new int[]{0, num(m.group(1)), m.start(), m.end(), 0};
        }
        return new int[]{num(m.group(1)), num(m.group(2)), m.start(), m.end(), 1};
    }

    /** True when this key can stand for (season, number) in a matcher:
     *  exact season, or an episode-only marker (season unstated) when the
     *  wanted season is 1. An explicit S00 never stands for season 1. */
    public boolean matches(int wantSeason, int wantEpisode) {
        if (episode != wantEpisode) return false;
        if (season == wantSeason) return true;
        return !seasonKnown && wantSeason == 1;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    /** Title comparison form: lowercase, punctuation -> space, collapsed. */
    public static String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.US);
        StringBuilder b = new StringBuilder(lower.length());
        boolean lastSpace = true; // also trims leading space
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(c);
                lastSpace = false;
            } else if (!lastSpace) {
                b.append(' ');
                lastSpace = true;
            }
        }
        int len = b.length();
        if (len > 0 && b.charAt(len - 1) == ' ') b.setLength(len - 1);
        return b.toString();
    }

    /** Whole-phrase containment on normalized text (space-padded both sides). */
    public static boolean containsPhrase(String haystackNorm, String needleNorm) {
        if (needleNorm.isEmpty() || haystackNorm.isEmpty()) return false;
        return (" " + haystackNorm + " ").contains(" " + needleNorm + " ");
    }

    // ------------------------------------------------------------- quality

    /**
     * Quality parsed from name/URL tokens only. Explicit numbers beat
     * letters; a CAM tag poisons the entry to the bottom tier no matter
     * what else it claims; nothing found = "unknown quality" (tier 0),
     * ranked with the bottom, above only CAM.
     */
    public static int qualityTier(String text) {
        return quality(text)[0];
    }

    public static String qualityLabel(String text) {
        int tier = quality(text)[0];
        switch (tier) {
            case 6: return "2160p";
            case 5: return "1080p";
            case 4: return "720p";
            case 3: return "HD";
            case 2: return "480p";
            case 1: return "SD";
            case -1: return "CAM";
            default: return "unknown quality";
        }
    }

    private static int[] quality(String text) {
        if (text == null) return new int[]{0};
        String t = " " + text.toUpperCase(Locale.US) + " ";
        // CAM-family tags poison the entry to the bottom, whatever else it
        // claims. A bare ".ts" is NOT read as telesync — it is a common
        // container suffix on legitimate streams.
        if (hasToken(t, "CAM") || t.contains("HDCAM") || t.contains("HDTS")
                || t.contains("TELESYNC")) {
            return new int[]{-1};
        }
        if (t.contains("2160") || hasToken(t, "4K") || hasToken(t, "UHD")) return new int[]{6};
        if (t.contains("1080")) return new int[]{5};
        if (t.contains("720")) return new int[]{4};
        if (hasToken(t, "HD") || hasToken(t, "HDTV")) return new int[]{3};
        if (t.contains("480")) return new int[]{2};
        if (hasToken(t, "SD")) return new int[]{1};
        return new int[]{0};
    }

    /** True if the (space-padded, uppercase) text contains the token as a word. */
    private static boolean hasToken(String paddedUpper, String token) {
        String[] parts = paddedUpper.split("[^A-Z0-9]+");
        for (String p : parts) {
            if (p.equals(token)) return true;
        }
        return false;
    }
}
