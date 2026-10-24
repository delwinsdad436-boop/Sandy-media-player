package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NATIONAL FILM ARCHIVE OF JAPAN — JAPANESE ANIMATED FILM
 * CLASSICS (bound v0.18.0; census Round 2, RESOLVES: catalogue
 * → play page → the National Institute of Informatics viewer →
 * HLS on h10.cs.nii.ac.jp, master → variant → segment proven
 * to the byte, 206 verified MPEG-TS; receipt in
 * {@link SourceReceipts}). A whole national animation heritage,
 * 1917–1945, streamed by the archive itself as a public
 * service. Rights posture, from the census: the archive states
 * copying/replication is prohibited — Sandy binds the STREAM
 * through the archive's own door; she does not copy the films.
 *
 * There is no API; the catalogue is the archive's own list
 * pages (HTTPS web), parsed for their own work entries. The
 * stream door per work is the pattern the census proved:
 *   https://h10.cs.nii.ac.jp/stream/nfc/<id>_en/hls/auto/master.m3u8
 * with fixed-rendition doors (…/hls/480/…, /360/, /240/, /144/)
 * standing behind the auto door as alternate dialects — when
 * the auto door doesn't prove, a fixed door is another lawful
 * manner (pillar 2), each tried through Hydra's own walk.
 *
 * This class is the pure door: addresses + list parsing. The
 * fetching rides Net; the proving rides HydraResolver.
 */
public final class NfajClient {

    private NfajClient() {}

    public static final String SOURCE_ID = "nfaj_janim";
    public static final String SITE = "https://animation.filmarchives.jp";
    public static final String STREAM_HOST = "https://h10.cs.nii.ac.jp";

    /** One work in the archive's catalogue. */
    public static final class Work {
        public int id;
        public String titleEn = "";
        public String titleJa = "";
        public String year = "";
        public String thumbUrl = "";
        public String viewUrl = "";
    }

    // ------------------------------------------------------ addresses

    /** The catalogue list door (page 1 carries no query). */
    public static String listUrl(int page) {
        if (page <= 1) return SITE + "/en/works";
        return SITE + "/en/works?page=" + page;
    }

    /** The work's own catalogue page at the archive. */
    public static String viewUrl(int id) {
        return SITE + "/en/works/view/" + id;
    }

    /** The censused stream door for a work (auto rendition). */
    public static String masterUrl(int id) {
        return STREAM_HOST + "/stream/nfc/" + id + "_en/hls/auto/master.m3u8";
    }

    /** A fixed-rendition stream door (480/360/240/144). */
    public static String renditionUrl(int id, int height) {
        return STREAM_HOST + "/stream/nfc/" + id + "_en/hls/"
                + height + "/master.m3u8";
    }

    /**
     * The stream doors for a work, in try order: the auto door
     * the census proved, then the fixed-rendition doors as
     * alternate dialects of the same lawful stream.
     */
    public static List<String> streamDoors(int id) {
        List<String> out = new ArrayList<>();
        out.add(masterUrl(id));
        out.add(renditionUrl(id, 480));
        out.add(renditionUrl(id, 360));
        out.add(renditionUrl(id, 240));
        out.add(renditionUrl(id, 144));
        return out;
    }

    // -------------------------------------------------------- parsing

    private static final Pattern BLOCK = Pattern.compile(
            "<li class=\"work-result\">(.*?)</li>", Pattern.DOTALL);
    private static final Pattern VIEW_ID = Pattern.compile(
            "works/view/(\\d+)");
    private static final Pattern THUMB = Pattern.compile(
            "<img[^>]+src=\"([^\"]+)\"");
    private static final Pattern HEADING = Pattern.compile(
            "<h2 class=\"work-result-heading\">(.*?)</h2>", Pattern.DOTALL);
    private static final Pattern TITLE_JA = Pattern.compile(
            "<p class=\"work-result-title-en\">(.*?)</p>", Pattern.DOTALL);
    private static final Pattern YEAR = Pattern.compile("(\\d{4})");

    /** Parse one catalogue list page into its works. */
    public static List<Work> parseList(String html) {
        List<Work> out = new ArrayList<>();
        if (html == null) return out;
        Matcher blocks = BLOCK.matcher(html);
        while (blocks.find()) {
            String b = blocks.group(1);
            Matcher idm = VIEW_ID.matcher(b);
            if (!idm.find()) continue;
            Work w = new Work();
            try {
                w.id = Integer.parseInt(idm.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            Matcher hm = HEADING.matcher(b);
            if (hm.find()) w.titleEn = cleanText(hm.group(1));
            Matcher jm = TITLE_JA.matcher(b);
            if (jm.find()) w.titleJa = cleanText(jm.group(1));
            Matcher tm = THUMB.matcher(b);
            if (tm.find()) {
                String src = tm.group(1).trim();
                w.thumbUrl = src.startsWith("http") ? src : SITE + src;
            }
            // The year stands in the block's props line, after the
            // titles; the first 4-digit year in the block is it
            // (ids are 4 digits too — search past the view link).
            int linkEnd = b.indexOf("work-result-summary");
            Matcher ym = YEAR.matcher(linkEnd >= 0
                    ? b.substring(linkEnd) : b);
            if (ym.find()) w.year = ym.group(1);
            w.viewUrl = viewUrl(w.id);
            if (w.id > 0) out.add(w);
        }
        return out;
    }

    /** HTML text → plain text: tags out, the common entities in. */
    public static String cleanText(String html) {
        if (html == null) return "";
        String t = html.replaceAll("<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
        return t.replaceAll("\\s+", " ").trim();
    }
}
