package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * The BookSource framework (v0.16.0) — ONE normalized shape for
 * every lawful, open book source Sandy searches, so the Reader,
 * the Story Fountain and Make-a-Story all drink from the same
 * well. Sources landed in this build, each verified live from
 * the build desk before a line shipped:
 *
 *   ia          Internet Archive — advancedsearch + /metadata.
 *   gutenberg   Project Gutenberg via the Gutendex catalogue —
 *               public file endpoints, formats inline.
 *   openlibrary Open Library search — bibliographic reach; its
 *               readable copies resolve through the Archive leg
 *               (an OL hit carries the IA ids of its editions).
 *   librivox    LibriVox — public-domain audiobooks; openly
 *               reachable section MP3s on the Archive.
 *
 * Named follow-ups (audited, NOT integrated — see the build
 * NOTES): Standard Ebooks (OPDS feeds answered 401 from every
 * public route tried), Wikisource (search works, but a work's
 * text lives across edition subpages — no honest single-text
 * resolution yet), HathiTrust (no credential-free lawful full
 * text verified; metadata only). No logins, no pooled
 * credentials, no wall-bypassing, no pirate/shadow libraries —
 * ever (James's fences, 2026-10-19).
 *
 * DISPLAY HONESTY is the framework's spine: a Hit's state is
 * UNKNOWN until evidence resolves it. OPEN means a readable
 * file is actually accessible (a public sibling in the item's
 * own file list, or a direct public file at the source).
 * WALLED means the book exists but its full text sits behind
 * the source's borrow wall (the Archive's restricted flag, or
 * every readable sibling marked private/encrypted) — named,
 * never circumvented, never promised as readable. A "Read"
 * affordance may render ONLY for OPEN.
 *
 * Pure logic: no Android, no network. The activities fetch
 * through Hydra (Net) and hand the bodies here.
 */
public final class BookSource {

    public static final int UNKNOWN = 0;
    public static final int OPEN = 1;
    public static final int WALLED = 2;

    public static final String IA = "ia";
    public static final String PG = "gutenberg";
    public static final String OL = "openlibrary";
    public static final String LV = "librivox";

    /** The source's own name — worn on every card, never a bucket. */
    public static String sourceName(String sourceId) {
        if (PG.equals(sourceId)) return "Project Gutenberg";
        if (OL.equals(sourceId)) return "Open Library";
        if (LV.equals(sourceId)) return "LibriVox";
        return "Internet Archive";
    }

    /** One normalized book (or audiobook) hit from any source. */
    public static final class Hit {
        public String source = IA;
        public String itemId = "";      // source-local id
        public String title = "";
        public String author = "";
        public String year = "";
        public String language = "";    // as the source states it
        public int state = UNKNOWN;
        public String siblingUrl = "";  // best readable file, OPEN only
        public String siblingKind = ""; // epub / txt / pdf / html
        public boolean pd = false;      // public-domain evidence
        public boolean pageImages = false; // verified page structure
        public int pageCount = 0;       // leaves, 0 = unknown
        public String series = "";      // series name, when metadata carries it
        public final List<String> collections = new ArrayList<>();
        public final List<String> bookshelves = new ArrayList<>();
        public final List<String> iaIds = new ArrayList<>(); // OL editions
        public String readVia = "";     // IA id an OL hit reads through
        public String audioZip = "";    // LibriVox
        public int audioSections = 0;   // LibriVox

        /** The only gate a "Read" promise may hang on. */
        public boolean readable() {
            return state == OPEN && !siblingUrl.isEmpty();
        }
    }

    private BookSource() {}

    // ------------------------------------------------ Internet Archive

    /**
     * advancedsearch over mediatype:texts. Title mode searches
     * title + subject words; author mode searches creator.
     * marcLang (a MARC code like "eng") filters where given.
     */
    public static String iaSearchUrl(String query, boolean byAuthor,
                                     String marcLang, int rows, int page) {
        String q = (byAuthor ? "creator:(" : "title:(")
                + iaQueryWords(query) + ") AND mediatype:(texts)";
        if (marcLang != null && !marcLang.isEmpty()) {
            q += " AND language:(" + marcLang + ")";
        }
        return "https://archive.org/advancedsearch.php?q=" + enc(q)
                + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year"
                + "&fl[]=language&fl[]=collection"
                + "&rows=" + Math.max(1, rows)
                + "&page=" + Math.max(1, page)
                + "&output=json";
    }

    /**
     * The Story Fountain's IA gate: the International Children's
     * Digital Library collection (identifier "iacl" — measured
     * 3,313 texts on 2026-10-19; the tokens "childrens_library",
     * "childrens" and "childrensbooks" measure ZERO at the
     * Archive, whatever the web calls them). Most-downloaded
     * first, so the shelf fills with the loved books first.
     */
    public static String iaChildrenUrl(int rows, int page) {
        String q = "collection:(iacl) AND mediatype:(texts)";
        return "https://archive.org/advancedsearch.php?q=" + enc(q)
                + "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=year"
                + "&fl[]=language&fl[]=collection"
                + "&sort[]=downloads+desc"
                + "&rows=" + Math.max(1, rows)
                + "&page=" + Math.max(1, page)
                + "&output=json";
    }

    public static List<Hit> parseIaSearch(String json) {
        List<Hit> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject resp = root.optJSONObject("response");
            if (resp == null) return out;
            JSONArray docs = resp.optJSONArray("docs");
            if (docs == null) return out;
            for (int i = 0; i < docs.length(); i++) {
                JSONObject d = docs.optJSONObject(i);
                if (d == null) continue;
                Hit h = new Hit();
                h.source = IA;
                h.itemId = d.optString("identifier", "");
                h.title = d.optString("title", "");
                h.author = joinNames(d.opt("creator"));
                h.year = yearOf(d.opt("year"));
                h.language = d.optString("language", "");
                Object col = d.opt("collection");
                if (col instanceof JSONArray) {
                    JSONArray a = (JSONArray) col;
                    for (int j = 0; j < a.length(); j++) {
                        h.collections.add(a.optString(j, ""));
                    }
                } else if (col != null) {
                    h.collections.add(String.valueOf(col));
                }
                if (!h.itemId.isEmpty()) out.add(h);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * THE AVAILABILITY DERIVATION (Archive leg). Reads one
     * /metadata body and settles the hit:
     *   - metadata "access-restricted-item": true → WALLED.
     *   - else the best PUBLIC readable sibling (EPUB → TXT →
     *     text-layer PDF; private and encrypted/LCP files never
     *     count) → OPEN.
     *   - readable-format files exist but every one is private
     *     or encrypted → WALLED (the wall's shape without the
     *     flag).
     *   - no readable-format files at all → stays UNKNOWN.
     * Page structure: a PUBLIC scandata file (the Archive's
     * standard book-reader structure) marks pageImages; the
     * leaf count is filled separately from the scandata body.
     */
    public static void applyIaMetadata(Hit h, String metadataJson) {
        try {
            JSONObject root = new JSONObject(metadataJson);
            JSONObject meta = root.optJSONObject("metadata");
            boolean restricted = false;
            if (meta != null) {
                Object r = meta.opt("access-restricted-item");
                restricted = r != null && "true".equalsIgnoreCase(
                        String.valueOf(r));
                if (h.series.isEmpty()) {
                    h.series = meta.optString("series", "");
                }
                if (h.title.isEmpty()) {
                    h.title = meta.optString("title", "");
                }
            }
            JSONArray files = root.optJSONArray("files");
            String epub = "", djvu = "", txt = "", pdf = "";
            boolean sawReadable = false;
            boolean scandataPublic = false;
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.optJSONObject(i);
                    if (f == null) continue;
                    String name = f.optString("name", "");
                    String low = name.toLowerCase(Locale.US);
                    boolean priv = f.optBoolean("private", false);
                    if (low.endsWith("_scandata.xml")) {
                        if (!priv) scandataPublic = true;
                        continue;
                    }
                    boolean encrypted = low.contains("encrypted")
                            || low.contains(".lcp");
                    boolean isEpub = low.endsWith(".epub");
                    boolean isDjvu = low.endsWith("_djvu.txt");
                    boolean isTxt = low.endsWith(".txt")
                            && !low.contains("searchtext")
                            && !low.endsWith("_djvu.txt");
                    boolean isPdf = low.endsWith(".pdf")
                            && "Text PDF".equalsIgnoreCase(
                                    f.optString("format", ""));
                    if (!(isEpub || isDjvu || isTxt || isPdf)) continue;
                    sawReadable = true;
                    if (priv || encrypted) continue;   // wall evidence
                    String url = iaFileUrl(h.itemId, name);
                    if (isEpub && epub.isEmpty()) epub = url;
                    else if (isDjvu && djvu.isEmpty()) djvu = url;
                    else if (isTxt && txt.isEmpty()) txt = url;
                    else if (isPdf && pdf.isEmpty()) pdf = url;
                }
            }
            h.pageImages = scandataPublic;
            if (restricted) {
                h.state = WALLED;
                h.siblingUrl = "";
                h.siblingKind = "";
                h.pd = false;
            } else if (!epub.isEmpty() || !djvu.isEmpty()
                    || !txt.isEmpty() || !pdf.isEmpty()) {
                h.state = OPEN;
                if (!epub.isEmpty()) {
                    h.siblingUrl = epub; h.siblingKind = "epub";
                } else if (!djvu.isEmpty()) {
                    h.siblingUrl = djvu; h.siblingKind = "txt";
                } else if (!txt.isEmpty()) {
                    h.siblingUrl = txt; h.siblingKind = "txt";
                } else {
                    h.siblingUrl = pdf; h.siblingKind = "pdf";
                }
                h.pd = !collectionsContain(h, LENDING_COLLECTIONS);
            } else if (sawReadable) {
                h.state = WALLED;   // every readable file is locked
                h.pd = false;
            }
        } catch (Exception ignored) {}
    }

    private static final String[] LENDING_COLLECTIONS = {
            "inlibrary", "printdisabled",
    };

    private static boolean collectionsContain(Hit h, String[] names) {
        for (String c : h.collections) {
            for (String n : names) if (n.equals(c)) return true;
        }
        return false;
    }

    /** The Archive's standard download door for one file. */
    public static String iaFileUrl(String itemId, String fileName) {
        return "https://archive.org/download/" + itemId + "/"
                + encPath(fileName);
    }

    /** One page of a scanned book, as a picture (BookReader). */
    public static String iaPageUrl(String itemId, int leaf) {
        return "https://archive.org/download/" + itemId
                + "/page/n" + Math.max(0, leaf) + ".jpg";
    }

    /**
     * Leaf count from a scandata body: each page element carries
     * one leafNum attribute. 0 when the structure isn't there —
     * page mode is never offered on an uncounted book.
     */
    public static int scandataLeafCount(String xml) {
        if (xml == null) return 0;
        int count = 0, at = 0;
        while ((at = xml.indexOf("leafNum", at)) >= 0) {
            count++;
            at += 7;
        }
        return count;
    }

    // ------------------------------------------------ Project Gutenberg

    public static String gutSearchUrl(String query, String langIso1) {
        String u = "https://gutendex.com/books/?search=" + enc(query);
        if (langIso1 != null && !langIso1.isEmpty()) {
            u += "&languages=" + enc(langIso1);
        }
        return u;
    }

    /**
     * Gutendex results resolve AT PARSE: the formats map is the
     * file list. Sibling preference is the house order — EPUB →
     * TXT → HTML (HTML rides BookText's html intake). A book with
     * no readable format stays UNKNOWN, never promised.
     * pd = the catalogue's own copyright flag, inverted.
     */
    public static List<Hit> parseGutSearch(String json) {
        List<Hit> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray results = root.optJSONArray("results");
            if (results == null) return out;
            for (int i = 0; i < results.length(); i++) {
                JSONObject b = results.optJSONObject(i);
                if (b == null) continue;
                Hit h = new Hit();
                h.source = PG;
                h.itemId = "pg" + b.optInt("id", 0);
                h.title = b.optString("title", "");
                JSONArray authors = b.optJSONArray("authors");
                if (authors != null && authors.length() > 0) {
                    h.author = authors.optJSONObject(0) != null
                            ? authors.optJSONObject(0).optString("name", "")
                            : "";
                }
                JSONArray langs = b.optJSONArray("languages");
                if (langs != null && langs.length() > 0) {
                    h.language = langs.optString(0, "");
                }
                h.pd = !b.optBoolean("copyright", true);
                JSONArray shelves = b.optJSONArray("bookshelves");
                if (shelves != null) {
                    for (int j = 0; j < shelves.length(); j++) {
                        h.bookshelves.add(shelves.optString(j, ""));
                    }
                }
                JSONObject formats = b.optJSONObject("formats");
                if (formats != null) {
                    String epub = formats.optString(
                            "application/epub+zip", "");
                    String txt = bestTextFormat(formats);
                    String html = formats.optString("text/html", "");
                    if (!epub.isEmpty()) {
                        h.siblingUrl = epub; h.siblingKind = "epub";
                    } else if (!txt.isEmpty()) {
                        h.siblingUrl = txt; h.siblingKind = "txt";
                    } else if (!html.isEmpty()) {
                        h.siblingUrl = html; h.siblingKind = "html";
                    }
                    if (!h.siblingUrl.isEmpty()) h.state = OPEN;
                }
                if (!h.title.isEmpty()) out.add(h);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** text/plain comes in several charset flavors; prefer the
     *  plain .txt file, then any utf-8 variant. */
    private static String bestTextFormat(JSONObject formats) {
        String fallback = "";
        java.util.Iterator<String> keys = formats.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!k.startsWith("text/plain")) continue;
            String v = formats.optString(k, "");
            if (v.isEmpty()) continue;
            if (v.toLowerCase(Locale.US).endsWith(".txt")) return v;
            if (fallback.isEmpty()) fallback = v;
        }
        return fallback;
    }

    // ------------------------------------------------ Open Library

    public static String olSearchUrl(String query, boolean byAuthor,
                                     String langIso1) {
        String u = "https://openlibrary.org/search.json?"
                + (byAuthor ? "author=" : "title=") + enc(query)
                + "&fields=title,author_name,first_publish_year,"
                + "public_scan_b,lending_edition_s,ia,language"
                + "&limit=20";
        if (langIso1 != null && !langIso1.isEmpty()) {
            u += "&language=" + enc(langIso1);
        }
        return u;
    }

    /**
     * Open Library is a catalogue: its hits resolve through the
     * Archive leg. A hit carrying IA edition ids stays UNKNOWN
     * until applyIaMetadata settles it (readVia = the first id).
     * A hit with NO Archive copy but a lending edition is WALLED
     * on the spot; a hit with neither makes no promise at all.
     */
    public static List<Hit> parseOlSearch(String json) {
        List<Hit> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray docs = root.optJSONArray("docs");
            if (docs == null) return out;
            for (int i = 0; i < docs.length(); i++) {
                JSONObject d = docs.optJSONObject(i);
                if (d == null) continue;
                Hit h = new Hit();
                h.source = OL;
                h.title = d.optString("title", "");
                JSONArray an = d.optJSONArray("author_name");
                if (an != null && an.length() > 0) {
                    h.author = an.optString(0, "");
                }
                int yr = d.optInt("first_publish_year", 0);
                h.year = yr > 0 ? String.valueOf(yr) : "";
                JSONArray langs = d.optJSONArray("language");
                if (langs != null && langs.length() > 0) {
                    h.language = langs.optString(0, "");
                }
                JSONArray ia = d.optJSONArray("ia");
                if (ia != null) {
                    for (int j = 0; j < ia.length(); j++) {
                        String id = ia.optString(j, "");
                        if (!id.isEmpty()) h.iaIds.add(id);
                    }
                }
                h.itemId = h.iaIds.isEmpty() ? "" : h.iaIds.get(0);
                h.readVia = h.itemId;
                boolean lending = d.has("lending_edition_s")
                        && !d.isNull("lending_edition_s");
                if (h.iaIds.isEmpty()) {
                    h.state = lending ? WALLED : UNKNOWN;
                } else {
                    h.state = UNKNOWN;   // the Archive leg settles it
                }
                if (!h.title.isEmpty()) out.add(h);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------ LibriVox

    public static String lvSearchUrl(String titleQuery) {
        return "https://librivox.org/api/feed/audiobooks?title="
                + enc("^" + (titleQuery == null ? "" : titleQuery.trim()))
                + "&format=json&limit=10";
    }

    public static String lvSectionsUrl(String lvId) {
        return "https://librivox.org/api/feed/audiobooks?id="
                + enc(lvId) + "&format=json&extended=1";
    }

    /**
     * LibriVox hits are audiobooks: OPEN when the recording is
     * openly reachable (the feed's own archive.org zip door and
     * a positive section count are the evidence). LibriVox
     * recordings are public domain by the project's own charter.
     */
    public static List<Hit> parseLvSearch(String json) {
        List<Hit> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray books = root.optJSONArray("books");
            if (books == null) return out;
            for (int i = 0; i < books.length(); i++) {
                JSONObject b = books.optJSONObject(i);
                if (b == null) continue;
                Hit h = new Hit();
                h.source = LV;
                h.itemId = "lv" + b.optString("id", "");
                h.title = b.optString("title", "");
                h.author = lvAuthor(b.optJSONArray("authors"));
                h.language = b.optString("language", "");
                h.year = b.optString("copyright_year", "");
                h.audioZip = b.optString("url_zip_file", "");
                h.audioSections = parseInt(b.optString("num_sections", ""));
                h.pd = true;
                if (!h.audioZip.isEmpty() && h.audioSections > 0) {
                    h.state = OPEN;
                }
                if (!h.title.isEmpty()) out.add(h);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** [section title, listen url, seconds] rows, feed order. */
    public static List<String[]> parseLvSections(String json) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray books = root.optJSONArray("books");
            if (books == null || books.length() == 0) return out;
            JSONObject b = books.optJSONObject(0);
            JSONArray secs = b != null ? b.optJSONArray("sections") : null;
            if (secs == null) return out;
            for (int i = 0; i < secs.length(); i++) {
                JSONObject s = secs.optJSONObject(i);
                if (s == null) continue;
                String url = s.optString("listen_url", "");
                if (url.isEmpty()) continue;
                String title = s.optString("title", "");
                if (title.isEmpty()) {
                    title = "Section " + s.optString("section_number", "");
                }
                out.add(new String[]{title, url,
                        s.optString("playtime", "0")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * The on-book audio sibling, verified: a LibriVox search for
     * the book's title matches only when a returned recording's
     * title equals the book's (normalized) AND the author
     * surnames meet. Anything less is not a match — the button
     * simply never appears.
     */
    public static boolean lvMatches(String searchJson, String title,
                                    String author) {
        for (Hit h : parseLvSearch(searchJson)) {
            if (h.state != OPEN) continue;
            if (!normTitle(h.title).equals(normTitle(title))) continue;
            if (author == null || author.isEmpty()) return true;
            String want = surname(author), got = surname(h.author);
            if (!want.isEmpty() && want.equals(got)) return true;
        }
        return false;
    }

    private static String lvAuthor(JSONArray authors) {
        if (authors == null || authors.length() == 0) return "";
        JSONObject a = authors.optJSONObject(0);
        if (a == null) return "";
        String first = a.optString("first_name", "");
        String last = a.optString("last_name", "");
        return (first + " " + last).trim();
    }

    // ------------------------------------------------ story gates

    /**
     * The children's collections/shelves the fountain trusts —
     * the gate curation moved to. "iacl" is the Archive's
     * International Children's Digital Library (measured 3,313
     * texts, 2026-10-19). Gutenberg shelves qualify by their own
     * names ("Children's Literature", "Children's Fiction", …).
     */
    public static boolean inChildrensCollection(Hit h) {
        for (String c : h.collections) {
            if ("iacl".equals(c)) return true;
        }
        for (String s : h.bookshelves) {
            if (s != null && s.startsWith("Children")) return true;
        }
        return false;
    }

    /**
     * STORY ELIGIBILITY (the fountain gate): named children's
     * collection membership + open + public-domain evidence +
     * a resolvable readable sibling. Anything else is not a
     * story tile, whatever it is.
     */
    public static boolean storyEligible(Hit h) {
        return h != null && h.state == OPEN && h.pd
                && !h.siblingUrl.isEmpty() && inChildrensCollection(h);
    }

    /** Printed shelf counts derive from verified resolutions only. */
    public static int fountainVerified(List<Hit> hits) {
        int n = 0;
        for (Hit h : hits) if (storyEligible(h)) n++;
        return n;
    }

    /**
     * MAKE-A-STORY ELIGIBILITY: any book Sandy can actually read
     * end to end — OPEN with a resolvable text sibling. Walled
     * and unresolved books get the plain no (by never offering).
     */
    public static boolean storyFiable(Hit h) {
        return h != null && h.state == OPEN && !h.siblingUrl.isEmpty();
    }

    // ------------------------------------------------ helpers

    /** advancedsearch word list: strip Lucene operators, keep words. */
    public static String iaQueryWords(String query) {
        if (query == null) return "";
        String cleaned = query.replaceAll("[+\\-&|!(){}\\[\\]^\"~*?:/\\\\]", " ");
        return cleaned.trim().replaceAll("\\s+", " ");
    }

    public static String normTitle(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** "Austen, Jane" / "Jane Austen" → "austen". */
    public static String surname(String author) {
        if (author == null) return "";
        String a = author.trim();
        if (a.contains(",")) {
            return normTitle(a.substring(0, a.indexOf(',')));
        }
        String[] parts = a.split("\\s+");
        return normTitle(parts[parts.length - 1]);
    }

    private static String joinNames(Object v) {
        if (v == null) return "";
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                if (b.length() > 0) b.append(", ");
                b.append(a.optString(i, ""));
            }
            return b.toString();
        }
        return String.valueOf(v);
    }

    private static String yearOf(Object v) {
        if (v == null) return "";
        if (v instanceof Number) return String.valueOf(((Number) v).intValue());
        String s = String.valueOf(v).trim();
        return s.length() > 4 ? s.substring(0, 4) : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Query-component encoding (UTF-8, space as %20). */
    public static String enc(String s) {
        return encode(s, false);
    }

    /** Path-segment encoding (keeps the name readable, escapes rest). */
    public static String encPath(String s) {
        return encode(s, true);
    }

    private static String encode(String s, boolean path) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (byte by : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = by & 0xFF;
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~' || (path && c == '/');
            if (safe) {
                b.append((char) c);
            } else {
                b.append('%');
                b.append(Character.toUpperCase(Character.forDigit(
                        (c >> 4) & 0xF, 16)));
                b.append(Character.toUpperCase(Character.forDigit(
                        c & 0xF, 16)));
            }
        }
        return b.toString();
    }
}
