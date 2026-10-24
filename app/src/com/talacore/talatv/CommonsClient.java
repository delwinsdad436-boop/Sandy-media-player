package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * WIKIMEDIA COMMONS — VIDEO (bound v0.18.0; census Round 2,
 * RESOLVES: keyless MediaWiki API → direct files on
 * upload.wikimedia.org, 206 + verified WebM bytes; receipt in
 * {@link SourceReceipts}). An entire open video library —
 * films, newsreels, government media, community restorations —
 * behind a keyless API whose per-file rights labels are
 * machine-readable: the label rides the API, so it rides the
 * tile (the Europeana lesson, implemented by Commons itself).
 *
 * This class is the pure door: the API addresses, the response
 * parsing, the rendition ladder, and the captions index. The
 * fetching rides Net (Hydra's ladder) in the activity; the
 * proving rides LinkHealth/HydraResolver like everywhere else.
 *
 * Renditions: Commons transcodes each video (VP9 WebM at
 * several sizes, an MPEG-4 QuickTime, sometimes AV1). The
 * ladder below is a MANNER LADDER in James's sense — when one
 * rendition's door doesn't prove, the next is tried (pillar:
 * search out why, find how to make it work, legally) — and the
 * tile carries the winning rendition's codec honestly.
 */
public final class CommonsClient {

    private CommonsClient() {}

    public static final String API = "https://commons.wikimedia.org/w/api.php";
    public static final String SOURCE_ID = "wikimedia_commons";

    /** One film on Commons, as the API describes it. */
    public static final class Item {
        public String pageTitle = "";     // "File:Night of the ....webm"
        public String title = "";         // display title
        public String pageUrl = "";       // the file's own page
        public String originalUrl = "";   // the original file door
        public String mime = "";
        public int width;
        public int height;
        public long sizeBytes;
        /** The per-file rights label, verbatim from the API. */
        public String license = "";
        public String thumbUrl = "";
        public List<Rendition> renditions = new ArrayList<>();
    }

    /** One playable door into a film: a transcoded rendition
     *  (or the original), with its codec named honestly. */
    public static final class Rendition {
        public String url = "";
        public String type = "";          // the API's type string
        public String codec = "";         // "VP9" / "AV1" / "MPEG-4" / ""
        public String container = "";     // "WebM" / "QuickTime" / ""
        public int width;
        public int height;

        /** "480p · VP9 WebM" — what the tile says this door is. */
        public String label() {
            String res = height > 0 ? height + "p" : "";
            String kind = (codec + " " + container).trim();
            if (res.isEmpty()) return kind;
            if (kind.isEmpty()) return res;
            return res + " · " + kind;
        }
    }

    /** One caption track a file offers (TimedText). */
    public static final class Caption {
        public String lang = "";
        public String timedTextTitle = "";
        public String rawUrl = "";
    }

    // ------------------------------------------------------ addresses

    /** The catalogue search door: films matching the words. */
    public static String searchUrl(String query) {
        String q = (query == null ? "" : query.trim()) + " filetype:video";
        return API + "?action=query&format=json&generator=search"
                + "&gsrnamespace=6&gsrlimit=24&gsrsearch=" + enc(q)
                + "&prop=imageinfo%7Cvideoinfo"
                + "&iiprop=url%7Csize%7Cmime%7Cextmetadata"
                + "&viiprop=url%7Csize%7Cmime%7Cderivatives"
                + "&iiurlwidth=480";
    }

    /** A shelf door: featured films, for the shelf's first fill. */
    public static String featuredUrl() {
        return API + "?action=query&format=json&generator=categorymembers"
                + "&gcmtitle=Category:Featured_videos_of_films"
                + "&gcmtype=file&gcmlimit=24"
                + "&prop=imageinfo%7Cvideoinfo"
                + "&iiprop=url%7Csize%7Cmime%7Cextmetadata"
                + "&viiprop=url%7Csize%7Cmime%7Cderivatives"
                + "&iiurlwidth=480";
    }

    /** The captions index door for one file (TimedText namespace). */
    public static String captionIndexUrl(String fileName) {
        return API + "?action=query&format=json&list=allpages"
                + "&apnamespace=102&aplimit=50&apprefix=" + enc(fileName);
    }

    /** The raw caption door: the TimedText page's own text (SRT). */
    public static String captionRawUrl(String timedTextTitle) {
        return "https://commons.wikimedia.org/wiki/"
                + timedTextTitle.replace(' ', '_') + "?action=raw";
    }

    /** "Night of the Living Dead (1968).webm" from the page title. */
    public static String fileNameOf(String pageTitle) {
        if (pageTitle == null) return "";
        String t = pageTitle.startsWith("File:")
                ? pageTitle.substring(5) : pageTitle;
        return t.replace(' ', '_');
    }

    /** The display title: no namespace, no extension, spaced. */
    public static String displayTitleOf(String pageTitle) {
        String t = fileNameOf(pageTitle).replace('_', ' ');
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        return t.trim();
    }

    /**
     * Commons' API garnishes file URLs with utm tracking
     * parameters; the file door itself needs none of them. Strip
     * ONLY the utm_* parameters, only on upload.wikimedia.org —
     * anything else in a query string is the door's own business.
     */
    public static String stripTracking(String url) {
        if (url == null) return "";
        if (!url.contains("upload.wikimedia.org")) return url;
        int q = url.indexOf('?');
        if (q < 0) return url;
        String base = url.substring(0, q);
        StringBuilder keep = new StringBuilder();
        for (String pair : url.substring(q + 1).split("&")) {
            String name = pair.split("=", 2)[0];
            if (name.startsWith("utm_")) continue;
            if (keep.length() > 0) keep.append('&');
            keep.append(pair);
        }
        return keep.length() == 0 ? base : base + "?" + keep;
    }

    // -------------------------------------------------------- parsing

    /** Parse a search/category response into film items. */
    public static List<Item> parseItems(String json) {
        List<Item> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject pages = root.optJSONObject("query");
            if (pages == null) return out;
            pages = pages.optJSONObject("pages");
            if (pages == null) return out;
            List<JSONObject> ordered = new ArrayList<>();
            JSONArray names = pages.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    JSONObject p = pages.optJSONObject(names.optString(i));
                    if (p != null) ordered.add(p);
                }
            }
            Collections.sort(ordered, Comparator.comparingInt(
                    (JSONObject p) -> p.optInt("index", 1 << 20)));
            for (JSONObject p : ordered) {
                Item it = parseItem(p);
                if (it != null) out.add(it);
            }
        } catch (Exception ignored) {
            // a torn catalogue answer yields what parsed, never fiction
        }
        return out;
    }

    private static Item parseItem(JSONObject p) {
        try {
            JSONArray ii = p.optJSONArray("imageinfo");
            JSONArray vi = p.optJSONArray("videoinfo");
            JSONObject i0 = ii == null || ii.length() == 0
                    ? null : ii.optJSONObject(0);
            JSONObject v0 = vi == null || vi.length() == 0
                    ? null : vi.optJSONObject(0);
            JSONObject main = i0 != null ? i0 : v0;
            if (main == null) return null;
            String mime = main.optString("mime", "");
            if (mime.startsWith("audio/")) return null;   // a film shelf
            Item it = new Item();
            it.pageTitle = p.optString("title", "");
            it.title = displayTitleOf(it.pageTitle);
            it.pageUrl = main.optString("descriptionurl", "");
            it.originalUrl = stripTracking(main.optString("url", ""));
            it.mime = mime;
            it.width = main.optInt("width", 0);
            it.height = main.optInt("height", 0);
            it.sizeBytes = main.optLong("size", 0);
            it.thumbUrl = stripTracking(main.optString("thumburl", ""));
            JSONObject em = main.optJSONObject("extmetadata");
            if (em != null) {
                JSONObject lic = em.optJSONObject("LicenseShortName");
                if (lic != null) it.license = lic.optString("value", "");
            }
            JSONObject dsrc = v0 != null ? v0 : i0;
            if (dsrc != null) {
                JSONArray dv = dsrc.optJSONArray("derivatives");
                if (dv != null) {
                    for (int i = 0; i < dv.length(); i++) {
                        Rendition r = parseRendition(dv.optJSONObject(i));
                        if (r != null && !r.url.isEmpty()
                                && !r.url.equals(it.originalUrl)) {
                            it.renditions.add(r);
                        }
                    }
                }
            }
            return it.title.isEmpty() ? null : it;
        } catch (Exception e) {
            return null;
        }
    }

    private static Rendition parseRendition(JSONObject d) {
        if (d == null) return null;
        Rendition r = new Rendition();
        r.url = stripTracking(d.optString("src", ""));
        r.type = d.optString("type", "");
        r.width = d.optInt("width", 0);
        r.height = d.optInt("height", 0);
        r.codec = codecOf(r.type);
        r.container = containerOf(r.type);
        return r;
    }

    /** "video/webm; codecs=\"vp9, opus\"" -> "VP9". */
    public static String codecOf(String type) {
        if (type == null) return "";
        String t = type.toLowerCase(Locale.US);
        if (t.contains("av01") || t.contains("av1")) return "AV1";
        if (t.contains("vp9")) return "VP9";
        if (t.contains("vp8")) return "VP8";
        if (t.contains("mp4v") || t.contains("mp4a")
                || t.contains("quicktime")) return "MPEG-4";
        if (t.contains("theora")) return "Theora";
        return "";
    }

    /** The container half of a rendition's type string. */
    public static String containerOf(String type) {
        if (type == null) return "";
        String t = type.toLowerCase(Locale.US);
        if (t.contains("webm")) return "WebM";
        if (t.contains("quicktime")) return "QuickTime";
        if (t.contains("mp4")) return "MP4";
        if (t.contains("ogg")) return "Ogg";
        return "";
    }

    // ------------------------------------------------- rendition ladder

    /**
     * THE RENDITION LADDER — the manners for one Commons film,
     * in try order (pillar 2: another lawful door when one
     * doesn't prove): the MPEG-4 rendition first (the widest
     * device support in the building), then VP9 at honest
     * sizes, then AV1, then the original file itself. The
     * winning door's label rides the tile.
     */
    public static List<Rendition> renditionLadder(Item it) {
        List<Rendition> out = new ArrayList<>();
        if (it == null) return out;
        List<Rendition> mp4 = new ArrayList<>();
        List<Rendition> vp9Small = new ArrayList<>();
        List<Rendition> vp9Big = new ArrayList<>();
        List<Rendition> av1 = new ArrayList<>();
        List<Rendition> rest = new ArrayList<>();
        for (Rendition r : it.renditions) {
            if ("MPEG-4".equals(r.codec)) mp4.add(r);
            else if ("VP9".equals(r.codec) || "VP8".equals(r.codec)) {
                (r.width <= 720 || r.height <= 720 && r.width <= 960
                        ? vp9Small : vp9Big).add(r);
            } else if ("AV1".equals(r.codec)) av1.add(r);
            else rest.add(r);
        }
        Comparator<Rendition> byWidthDesc =
                (a, b) -> Integer.compare(b.width, a.width);
        Comparator<Rendition> byWidthAsc =
                (a, b) -> Integer.compare(a.width, b.width);
        mp4.sort(byWidthAsc);
        vp9Small.sort(byWidthDesc);
        vp9Big.sort(byWidthAsc);
        av1.sort(byWidthAsc);
        out.addAll(mp4);
        out.addAll(vp9Small);
        out.addAll(vp9Big);
        out.addAll(av1);
        out.addAll(rest);
        if (!it.originalUrl.isEmpty()) {
            Rendition orig = new Rendition();
            orig.url = it.originalUrl;
            orig.type = it.mime;
            orig.codec = codecOf(it.mime);
            orig.container = containerOf(it.mime);
            orig.width = it.width;
            orig.height = it.height;
            out.add(orig);
        }
        return out;
    }

    // -------------------------------------------------------- captions

    /** Parse the TimedText index for one file into caption doors. */
    public static List<Caption> parseCaptions(String json, String fileName) {
        List<Caption> out = new ArrayList<>();
        if (json == null || fileName == null) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject q = root.optJSONObject("query");
            if (q == null) return out;
            JSONArray all = q.optJSONArray("allpages");
            if (all == null) return out;
            String prefix = "TimedText:" + fileName + ".";
            for (int i = 0; i < all.length(); i++) {
                JSONObject pg = all.optJSONObject(i);
                if (pg == null) continue;
                String title = pg.optString("title", "");
                if (!title.startsWith(prefix)
                        || !title.endsWith(".srt")) continue;
                String lang = title.substring(prefix.length(),
                        title.length() - 4);
                if (lang.isEmpty() || lang.contains(".")) continue;
                Caption c = new Caption();
                c.lang = lang;
                c.timedTextTitle = title;
                c.rawUrl = captionRawUrl(title);
                out.add(c);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** The caption to offer first: the viewer's language, else
     *  English, else the first the file offers. */
    public static Caption pickCaption(List<Caption> caps, String lang) {
        if (caps == null || caps.isEmpty()) return null;
        if (lang != null && !lang.isEmpty()) {
            for (Caption c : caps) {
                if (c.lang.equalsIgnoreCase(lang)) return c;
            }
        }
        for (Caption c : caps) {
            if (c.lang.equalsIgnoreCase("en")) return c;
        }
        return caps.get(0);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
