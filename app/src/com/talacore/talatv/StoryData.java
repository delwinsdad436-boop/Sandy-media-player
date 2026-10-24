package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story Mode's file formats (v0.14.0) — pure parsing and
 * writing, so the harness can round-trip every one of them.
 * Everything here lives as a plain file on the memory hook
 * (see StoryFiles); these are just the shapes.
 *
 * CURATION — the shelf is curated going IN, not filtered after:
 * the curation file names every book that may stand on the
 * children's shelf. A row:
 *   id \t title \t author \t lang(iso1) \t taleGroup \t iaId \t
 *   search terms \t cast (comma names, narrator first) \t
 *   illustrated (1 when the edition is known to carry pictures)
 * Books are resolved through the Archive's own front door: the
 * row's archive id first; if that door doesn't answer, a search
 * under the row's own terms. Nothing joins the shelf that this
 * file didn't place there.
 *
 * CARTRIDGE — a child's own storyboard: a JSON manifest beside
 * its audio files in one folder. The manifest is the cartridge.
 *
 * JOURNALS — a TSV index: id, the teller's name, the date, the
 * audio file name.
 */
public final class StoryData {

    private StoryData() {}

    // ------------------------------------------------------ curation

    public static final class Row {
        public String id = "";
        public String title = "";
        public String author = "";
        public String lang = "";        // iso1
        public String taleGroup = "";   // same tale, another tongue
        public String iaId = "";        // may be "" (search resolves)
        public String search = "";      // the row's own search terms
        public final List<String> cast = new ArrayList<>();
        public boolean illustrated = false;
        /** v0.16.0: a direct readable-file URL (a Gutenberg book a
         *  fountain/made row reads from, bypassing the IA leg). */
        public String directUrl = "";
        /** v0.16.0: the BookSource id this row came from
         *  ("ia" for the legacy curated rows). */
        public String source = "ia";
    }

    /** One row back to its TSV line (columns 10/11 appended in
     *  v0.16.0 — older readers stop at column 9, unharmed). */
    public static String rowToLine(Row r) {
        StringBuilder cast = new StringBuilder();
        for (String nm : r.cast) {
            if (cast.length() > 0) cast.append(',');
            cast.append(nm);
        }
        return r.id + "\t" + clean(r.title) + "\t" + clean(r.author)
                + "\t" + r.lang + "\t" + clean(r.taleGroup) + "\t"
                + r.iaId + "\t" + clean(r.search) + "\t" + cast + "\t"
                + (r.illustrated ? "1" : "0") + "\t"
                + (r.directUrl == null ? "" : r.directUrl) + "\t"
                + (r.source == null ? "ia" : r.source);
    }

    private static String clean(String v) {
        return v == null ? "" : v.replace('\t', ' ').replace('\n', ' ');
    }

    public static List<Row> parseCuration(String tsv) {
        List<Row> out = new ArrayList<>();
        if (tsv == null) return out;
        for (String line : tsv.split("\n")) {
            Row r = parseCurationLine(line);
            if (r != null) out.add(r);
        }
        return out;
    }

    public static Row parseCurationLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) return null;
        String[] p = t.split("\t", -1);
        if (p.length < 5) return null;
        Row r = new Row();
        r.id = p[0].trim();
        r.title = p[1].trim();
        r.author = p[2].trim();
        r.lang = p[3].trim().toLowerCase(java.util.Locale.US);
        r.taleGroup = p[4].trim();
        if (p.length > 5) r.iaId = p[5].trim();
        if (p.length > 6) r.search = p[6].trim();
        if (p.length > 7) {
            for (String nm : p[7].split(",")) {
                String s = nm.trim();
                if (!s.isEmpty()) r.cast.add(s);
            }
        }
        if (p.length > 8) r.illustrated = "1".equals(p[8].trim());
        if (p.length > 9) r.directUrl = p[9].trim();
        if (p.length > 10 && !p[10].trim().isEmpty()) {
            r.source = p[10].trim();
        }
        if (r.id.isEmpty() || r.title.isEmpty() || r.lang.isEmpty()) return null;
        return r;
    }

    public static Row rowById(List<Row> rows, String id) {
        if (rows == null || id == null) return null;
        for (Row r : rows) if (r.id.equals(id)) return r;
        return null;
    }

    /**
     * The same tale in another tongue — the bilingual hearing's
     * pairing. Returns the row in {@code wantLang} sharing this
     * row's tale group, or null (a gap named, never faked).
     */
    public static Row bilingualPair(List<Row> rows, Row row, String wantLang) {
        if (rows == null || row == null || wantLang == null
                || row.taleGroup.isEmpty()) return null;
        String want = wantLang.trim().toLowerCase(java.util.Locale.US);
        for (Row r : rows) {
            if (r.taleGroup.equals(row.taleGroup)
                    && r.lang.equals(want) && !r.id.equals(row.id)) {
                return r;
            }
        }
        return null;
    }

    /** All the OTHER tongues this tale stands in, on this shelf. */
    public static List<Row> pairOptions(List<Row> rows, Row row) {
        List<Row> out = new ArrayList<>();
        if (rows == null || row == null || row.taleGroup.isEmpty()) return out;
        for (Row r : rows) {
            if (r.taleGroup.equals(row.taleGroup) && !r.id.equals(row.id)) {
                out.add(r);
            }
        }
        return out;
    }

    // ------------------------------------------------------ cartridge

    public static final class Cartridge {
        public String id = "";
        public String title = "";
        public long createdMs = 0;
        /** v0.16.0 Make-a-Story provenance: the StoryFiles book
         *  this cartridge was made from ("" for a child's own
         *  storyboard), the book's title, and its source's name.
         *  The shelf label reads: Made from <fromBook> (<fromSource>). */
        public String bookId = "";
        public String fromBook = "";
        public String fromSource = "";
        public final List<CartridgePanel> panels = new ArrayList<>();
    }

    public static final class CartridgePanel {
        public String panel = "";     // a StoryPanels id
        public String caption = "";   // the child's words for it
        public String audio = "";     // recorded telling file, "" = none
    }

    public static String cartridgeToJson(Cartridge c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", c.id);
        o.put("title", c.title);
        o.put("createdMs", c.createdMs);
        o.put("bookId", c.bookId);
        o.put("fromBook", c.fromBook);
        o.put("fromSource", c.fromSource);
        JSONArray arr = new JSONArray();
        for (CartridgePanel p : c.panels) {
            JSONObject po = new JSONObject();
            po.put("panel", p.panel);
            po.put("caption", p.caption);
            po.put("audio", p.audio);
            arr.put(po);
        }
        o.put("panels", arr);
        return o.toString();
    }

    public static Cartridge cartridgeFromJson(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        Cartridge c = new Cartridge();
        c.id = o.optString("id", "");
        c.title = o.optString("title", "");
        c.createdMs = o.optLong("createdMs", 0);
        c.bookId = o.optString("bookId", "");
        c.fromBook = o.optString("fromBook", "");
        c.fromSource = o.optString("fromSource", "");
        JSONArray arr = o.optJSONArray("panels");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject po = arr.getJSONObject(i);
                CartridgePanel p = new CartridgePanel();
                p.panel = po.optString("panel", "");
                p.caption = po.optString("caption", "");
                p.audio = po.optString("audio", "");
                c.panels.add(p);
            }
        }
        if (c.id.isEmpty()) throw new Exception("cartridge has no id");
        return c;
    }

    /** A safe file/folder name from a title + a stamp. */
    public static String safeId(String title, long stamp) {
        String base = title == null ? "" : title.toLowerCase(
                java.util.Locale.US).replaceAll("[^a-z0-9]+", "-");
        base = base.replaceAll("^-+|-+$", "");
        if (base.length() > 24) base = base.substring(0, 24);
        if (base.isEmpty()) base = "story";
        return base + "-" + stamp;
    }

    // ------------------------------------------------------ journals

    public static final class Journal {
        public String id = "";
        public String name = "";      // the teller's name
        public long dateMs = 0;
        public String file = "";      // audio file name
    }

    public static List<Journal> parseJournals(String tsv) {
        List<Journal> out = new ArrayList<>();
        if (tsv == null) return out;
        for (String line : tsv.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] p = t.split("\t", -1);
            if (p.length < 4) continue;
            Journal j = new Journal();
            j.id = p[0].trim();
            j.name = p[1].trim();
            try { j.dateMs = Long.parseLong(p[2].trim()); }
            catch (NumberFormatException e) { continue; }
            j.file = p[3].trim();
            if (j.id.isEmpty() || j.file.isEmpty()) continue;
            out.add(j);
        }
        return out;
    }

    public static String journalsToTsv(List<Journal> journals) {
        StringBuilder b = new StringBuilder();
        for (Journal j : journals) {
            b.append(j.id).append('\t')
             .append(j.name.replace('\t', ' ')).append('\t')
             .append(j.dateMs).append('\t')
             .append(j.file).append('\n');
        }
        return b.toString();
    }

    // ------------------------------------------------- family voices

    /** The family-voice index for one book: TSV lines
     *  chunkIndex \t castName \t audioFile. */
    public static Map<Integer, String[]> parseVoiceIndex(String tsv) {
        Map<Integer, String[]> out = new LinkedHashMap<>();
        if (tsv == null) return out;
        for (String line : tsv.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] p = t.split("\t", -1);
            if (p.length < 3) continue;
            try {
                out.put(Integer.parseInt(p[0].trim()),
                        new String[]{p[1].trim(), p[2].trim()});
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static String voiceIndexToTsv(Map<Integer, String[]> idx) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Integer, String[]> e : idx.entrySet()) {
            b.append(e.getKey()).append('\t')
             .append(e.getValue()[0].replace('\t', ' ')).append('\t')
             .append(e.getValue()[1]).append('\n');
        }
        return b.toString();
    }
}
