package com.talacore.talatv;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Book text extraction (v0.8.0, the read-aloud build) — pure Java,
 * no Android imports, so the harness pins it exactly.
 *
 * EPUB: read the zip, follow META-INF/container.xml to the OPF,
 * walk the SPINE in order (the book's own reading order — never zip
 * order, never filename order), take each content document's text.
 * Chapter titles come from the NCX (EPUB 2) or the nav document
 * (EPUB 3) when present, matched to spine items by file name; an
 * item without a mapped title falls back to its own &lt;title&gt; /
 * first heading, then to "Part N". HTML is stripped by walking the
 * parsed DOM; when a content document won't parse as XML, a
 * conservative tag stripper is the fallback (still in spine order).
 *
 * TXT: charset-honest decode — BOMs honored (UTF-8 / UTF-16LE /
 * UTF-16BE); without a BOM, strict UTF-8 first and windows-1252 on
 * malformed input (the usual shape of an old .txt).
 *
 * PDF: read IN THE HOUSE since v0.14.0 (Addendum 7 — extraction
 * comes home) by {@link PdfText}, a lean text-layer reader. A PDF
 * with a real text layer reads like any other book. A PDF whose
 * pages are pictures (scanned images, no text layer) is named
 * plainly — SCANNED_NO_TEXT, a spoken line in the app — never a
 * silent blank and never an errand to another program. Reading
 * scanned pictures (OCR) is a later organ, shared with Eater's
 * ingest, and is named as a gap until it lands.
 *
 * ONE INTAKE (v0.14.0 Addendum 8 — "read any file"): this class
 * is the single mouth of Read Aloud, the Reader, Story Mode book
 * intake, and — when its build comes — Speak For Me. Supported:
 * PDF (text layer), EPUB, TXT, HTML, DOCX, ODT, RTF. Anything
 * outside those classes is named UNSUPPORTED_KIND in plain
 * words, never a dead end or an errand elsewhere.
 */
public final class BookText {

    private BookText() {}

    public static final String KIND_EPUB = "epub";
    public static final String KIND_TXT = "txt";
    public static final String KIND_PDF = "pdf";
    public static final String KIND_HTML = "html";
    public static final String KIND_DOCX = "docx";
    public static final String KIND_ODT = "odt";
    public static final String KIND_RTF = "rtf";
    public static final String KIND_UNSUPPORTED = "unsupported";

    /** A PDF whose pages are pictures — no text layer to read. */
    public static final String SCANNED_NO_TEXT = "scanned-no-text";
    /** A file class this intake does not read; named, never faked. */
    public static final String UNSUPPORTED_KIND = "unsupported-kind";
    public static final String NO_TEXT_FOUND = "no-text-found";

    public static final class Chapter {
        public final String title;
        /** Character offset into Result.text where the chapter starts. */
        public final int charOffset;

        public Chapter(String title, int charOffset) {
            this.title = title;
            this.charOffset = charOffset;
        }
    }

    public static final class Result {
        public boolean ok = false;
        public String kind = "";
        /** When !ok: SCANNED_NO_TEXT / UNSUPPORTED_KIND / NO_TEXT_FOUND. */
        public String failReason = "";
        public String text = "";
        public List<Chapter> chapters = new ArrayList<>();
    }

    /** What kind of book do these bytes/name claim to be? */
    public static String kindOf(byte[] data, String name) {
        if (data != null && data.length >= 4
                && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F') {
            return KIND_PDF;
        }
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".pdf")) return KIND_PDF;
        if (n.endsWith(".epub")) return KIND_EPUB;
        if (n.endsWith(".docx")) return KIND_DOCX;
        if (n.endsWith(".odt")) return KIND_ODT;
        if (n.endsWith(".rtf")) return KIND_RTF;
        if (n.endsWith(".html") || n.endsWith(".htm")) return KIND_HTML;
        if (n.endsWith(".txt") || n.endsWith(".text") || n.endsWith(".md")) {
            return KIND_TXT;
        }
        if (data != null) {
            String head = headOf(data, 64);
            if (head.startsWith("{\\rtf")) return KIND_RTF;
            String low = head.toLowerCase();
            if (low.startsWith("<!doctype html") || low.startsWith("<html")) {
                return KIND_HTML;
            }
            // Sniff the zip classes by their insides, whatever the
            // file was renamed to: Word carries word/document.xml,
            // OpenDocument carries content.xml with no OPF, EPUB
            // is the OPF house.
            if (data.length >= 2 && data[0] == 'P' && data[1] == 'K') {
                try {
                    java.util.Map<String, byte[]> z = readZip(data);
                    if (z.containsKey("word/document.xml")) return KIND_DOCX;
                    if (z.containsKey("content.xml")
                            && !hasOpf(z)) return KIND_ODT;
                    return KIND_EPUB;
                } catch (Exception e) {
                    return KIND_EPUB;
                }
            }
            if (looksLikeText(data)) return KIND_TXT;
        }
        return KIND_UNSUPPORTED;
    }

    public static Result extract(byte[] data, String name) {
        Result r = new Result();
        String kind = kindOf(data, name);
        r.kind = kind;
        if (data == null || data.length == 0) {
            r.failReason = NO_TEXT_FOUND;
            return r;
        }
        if (KIND_UNSUPPORTED.equals(kind)) {
            r.failReason = UNSUPPORTED_KIND;
            return r;
        }
        try {
            if (KIND_EPUB.equals(kind)) return extractEpub(data);
            if (KIND_PDF.equals(kind)) return extractPdf(data);
            if (KIND_DOCX.equals(kind)) { r.text = extractDocx(data); }
            else if (KIND_ODT.equals(kind)) { r.text = extractOdt(data); }
            else if (KIND_RTF.equals(kind)) { r.text = rtfToText(data); }
            else if (KIND_HTML.equals(kind)) { r.text = htmlToText(data); }
            else { r.text = decodeText(data); }
            r.ok = !r.text.trim().isEmpty();
            if (!r.ok) r.failReason = NO_TEXT_FOUND;
            return r;
        } catch (Exception e) {
            Result fail = new Result();
            fail.kind = kind;
            fail.failReason = NO_TEXT_FOUND;
            return fail;
        }
    }

    /** PDF: the text layer, read at home by PdfText. Substantial
     *  text = a readable book, with Page chapters; almost none =
     *  a scanned-image book, named plainly. */
    private static Result extractPdf(byte[] data) {
        Result r = new Result();
        r.kind = KIND_PDF;
        PdfText.Extract ex = PdfText.extract(data);
        // The line: real SHOWN text on a page is a text layer (even a
        // short one — title pages are short). A scanned-image book's
        // streams hold pictures, not text-showing operators, so it
        // yields no pages of text at all.
        if (!ex.pages.isEmpty() && ex.textChars >= 2) {
            r.ok = true;
            r.text = ex.text;
            int offset = 0;
            for (int i = 0; i < ex.pages.size(); i++) {
                r.chapters.add(new Chapter("Page " + (i + 1), offset));
                offset += ex.pages.get(i).length() + 2;
            }
        } else {
            r.failReason = SCANNED_NO_TEXT;
        }
        return r;
    }

    private static String headOf(byte[] data, int max) {
        int len = Math.min(data.length, max);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            char c = (char) (data[i] & 0xFF);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                if (sb.length() > 0) sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** True when the bytes read as plain text: no NULs and almost
     *  everything printable in the sample. */
    static boolean looksLikeText(byte[] data) {
        int n = Math.min(data.length, 8192);
        if (n == 0) return false;
        int printable = 0;
        for (int i = 0; i < n; i++) {
            int b = data[i] & 0xFF;
            if (b == 0) return false;
            if (b == '\n' || b == '\r' || b == '\t'
                    || (b >= 0x20 && b != 0x7F) || b >= 0x80) printable++;
        }
        return printable * 10 >= n * 9;
    }

    private static boolean hasOpf(java.util.Map<String, byte[]> z) {
        for (String k : z.keySet()) if (k.endsWith(".opf")) return true;
        return false;
    }

    /** Zip entries as a map (shared by the DOCX/ODT readers). */
    static java.util.Map<String, byte[]> readZip(byte[] data) throws Exception {
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(data));
        java.util.zip.ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zin.getNextEntry()) != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
            out.put(e.getName(), bos.toByteArray());
        }
        zin.close();
        return out;
    }

    // ------------------------------------------------- DOCX / ODT / RTF

    /** DOCX: word/document.xml — w:t runs inside w:p paragraphs. */
    private static String extractDocx(byte[] data) throws Exception {
        byte[] xml = readZip(data).get("word/document.xml");
        if (xml == null) return "";
        try {
            Document d = parseXml(xml);
            StringBuilder sb = new StringBuilder();
            walkDocx(d.getDocumentElement(), sb);
            return sb.toString();
        } catch (Exception e) {
            String s = new String(xml, StandardCharsets.UTF_8);
            s = s.replaceAll("</w:p>", "\n");
            return stripAllTags(s);
        }
    }

    private static void walkDocx(Node node, StringBuilder sb) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            sb.append(node.getNodeValue());
            return;
        }
        String tag = node.getNodeName();
        if ("w:tab".equals(tag)) { sb.append('\t'); return; }
        if ("w:br".equals(tag) || "w:cr".equals(tag)) { sb.append('\n'); return; }
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) walkDocx(kids.item(i), sb);
        if ("w:p".equals(tag)) sb.append('\n');
    }

    /** ODT: content.xml — text:p / text:h paragraphs. */
    private static String extractOdt(byte[] data) throws Exception {
        byte[] xml = readZip(data).get("content.xml");
        if (xml == null) return "";
        try {
            Document d = parseXml(xml);
            StringBuilder sb = new StringBuilder();
            walkOdt(d.getDocumentElement(), sb);
            return sb.toString();
        } catch (Exception e) {
            String s = new String(xml, StandardCharsets.UTF_8);
            s = s.replaceAll("</text:p>", "\n").replaceAll("</text:h>", "\n");
            return stripAllTags(s);
        }
    }

    private static void walkOdt(Node node, StringBuilder sb) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            sb.append(node.getNodeValue());
            return;
        }
        String tag = node.getNodeName();
        if ("text:tab".equals(tag)) { sb.append('\t'); return; }
        if ("text:line-break".equals(tag)) { sb.append('\n'); return; }
        if ("text:s".equals(tag)) { sb.append(' '); return; }
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) walkOdt(kids.item(i), sb);
        if ("text:p".equals(tag) || "text:h".equals(tag)) sb.append('\n');
    }

    private static String stripAllTags(String xml) {
        String s = xml.replaceAll("<[^>]+>", "");
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** RTF: a lean reader — destination groups skipped, control
     *  words turned to the text they stand for (\par, \'hh hex,
     *  unicode escapes, fallback skipped). */
    public static String rtfToText(byte[] data) {
        String s = new String(data, Charset.forName("windows-1252"));
        StringBuilder out = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '{') {
                // destination group? {\* …} or {\fonttbl …} etc.
                int j = i + 1;
                String dest = "";
                if (j < n && s.charAt(j) == '\\') {
                    int k = j + 1;
                    if (k < n && s.charAt(k) == '*') { dest = "*"; }
                    else {
                        int e = k;
                        while (e < n && Character.isLetter(s.charAt(e))) e++;
                        dest = s.substring(k, e);
                    }
                }
                if (dest.equals("*") || dest.equals("fonttbl")
                        || dest.equals("colortbl") || dest.equals("stylesheet")
                        || dest.equals("info") || dest.equals("pict")
                        || dest.equals("object") || dest.equals("themedata")
                        || dest.equals("colorschememapping")) {
                    int depth = 1;
                    i++;
                    while (i < n && depth > 0) {
                        char d = s.charAt(i);
                        if (d == '\\') { i += 2; continue; }
                        if (d == '{') depth++;
                        else if (d == '}') depth--;
                        i++;
                    }
                    continue;
                }
                i++;
            } else if (c == '}') {
                i++;
            } else if (c == '\\') {
                i = rtfEscape(s, i, out);
            } else if (c == '\r' || c == '\n') {
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int rtfEscape(String s, int i, StringBuilder out) {
        int n = s.length();
        if (i + 1 >= n) return n;
        char e = s.charAt(i + 1);
        if (e == '\'') {
            if (i + 3 < n) {
                try {
                    int v = Integer.parseInt(s.substring(i + 2, i + 4), 16);
                    byte[] b = { (byte) v };
                    out.append(new String(b, Charset.forName("windows-1252")));
                } catch (NumberFormatException ignored) { }
            }
            return i + 4;
        }
        if (Character.isLetter(e)) {
            int j = i + 1;
            while (j < n && Character.isLetter(s.charAt(j))) j++;
            String word = s.substring(i + 1, j);
            int k = j;
            boolean neg = false;
            if (k < n && (s.charAt(k) == '-' || s.charAt(k) == '+')) {
                neg = s.charAt(k) == '-'; k++;
            }
            int numStart = k;
            while (k < n && Character.isDigit(s.charAt(k))) k++;
            int num = 0;
            boolean hasNum = k > numStart;
            if (hasNum) {
                try { num = Integer.parseInt(s.substring(numStart, k)); }
                catch (NumberFormatException ignored) { }
                if (neg) num = -num;
            }
            if (k < n && s.charAt(k) == ' ') k++;
            switch (word) {
                case "par": case "line": out.append('\n'); break;
                case "tab": out.append('\t'); break;
                case "u":
                    if (hasNum) {
                        out.append((char) (num < 0 ? num + 65536 : num));
                        // skip the one fallback char (or \'hh form)
                        if (k + 1 < n && s.charAt(k) == '\\'
                                && s.charAt(k + 1) == '\'') return k + 4;
                        return Math.min(k + 1, n);
                    }
                    break;
                case "emdash": out.append('—'); break;
                case "endash": out.append('–'); break;
                case "lquote": out.append('‘'); break;
                case "rquote": out.append('’'); break;
                case "ldblquote": out.append('“'); break;
                case "rdblquote": out.append('”'); break;
                case "bullet": out.append('•'); break;
                default: break;
            }
            return k;
        }
        // control symbol
        switch (e) {
            case '{': out.append('{'); break;
            case '}': out.append('}'); break;
            case '\\': out.append('\\'); break;
            case '~': out.append(' '); break;
            case '_': out.append('-'); break;
            default: break;
        }
        return i + 2;
    }

    // ------------------------------------------------------------ TXT

    /** Charset-honest decode for plain-text books. */
    public static String decodeText(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            CharBuffer cb = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data));
            return cb.toString();
        } catch (CharacterCodingException e) {
            Charset cp1252 = Charset.forName("windows-1252");
            return new String(data, cp1252);
        }
    }

    // ------------------------------------------------------------ EPUB

    private static Result extractEpub(byte[] data) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            byte[] buf = new byte[32 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                entries.put(e.getName(), bos.toByteArray());
            }
        }
        Result r = new Result();
        r.kind = KIND_EPUB;
        if (entries.isEmpty()) {
            r.failReason = NO_TEXT_FOUND;
            return r;
        }
        // container.xml -> OPF path
        String opfPath = null;
        byte[] container = entries.get("META-INF/container.xml");
        if (container != null) {
            Document doc = parseXml(container);
            NodeList roots = doc.getElementsByTagName("rootfile");
            if (roots.getLength() > 0) {
                opfPath = ((Element) roots.item(0)).getAttribute("full-path");
            }
        }
        if (opfPath == null || !entries.containsKey(opfPath)) {
            // Fall back to any .opf in the zip (a malformed container
            // shouldn't bury an otherwise readable book).
            for (String k : entries.keySet()) {
                if (k.toLowerCase().endsWith(".opf")) { opfPath = k; break; }
            }
        }
        if (opfPath == null) {
            r.failReason = NO_TEXT_FOUND;
            return r;
        }
        String opfDir = opfPath.contains("/")
                ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
        Document opf = parseXml(entries.get(opfPath));
        // manifest: id -> href + media-type
        Map<String, String> idHref = new LinkedHashMap<>();
        Map<String, String> idType = new LinkedHashMap<>();
        NodeList items = opf.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element it = (Element) items.item(i);
            idHref.put(it.getAttribute("id"), it.getAttribute("href"));
            idType.put(it.getAttribute("id"), it.getAttribute("media-type"));
        }
        // spine: ordered itemrefs
        List<String> spineHrefs = new ArrayList<>();
        NodeList refs = opf.getElementsByTagName("itemref");
        for (int i = 0; i < refs.getLength(); i++) {
            String idref = ((Element) refs.item(i)).getAttribute("idref");
            String href = idHref.get(idref);
            if (href != null) spineHrefs.add(opfDir + href);
        }
        if (spineHrefs.isEmpty()) {
            r.failReason = NO_TEXT_FOUND;
            return r;
        }
        // Titles: NCX (epub2) or nav (epub3), keyed by file name.
        Map<String, String> titlesByFile = new LinkedHashMap<>();
        String ncxHref = null;
        NodeList spines = opf.getElementsByTagName("spine");
        if (spines.getLength() > 0) {
            ncxHref = ((Element) spines.item(0)).getAttribute("toc");
        }
        if (ncxHref != null && idHref.containsKey(ncxHref)
                && entries.containsKey(opfDir + idHref.get(ncxHref))) {
            try {
                Document ncx = parseXml(entries.get(opfDir + idHref.get(ncxHref)));
                NodeList points = ncx.getElementsByTagName("navPoint");
                for (int i = 0; i < points.getLength(); i++) {
                    Element p = (Element) points.item(i);
                    String label = firstText(p, "text");
                    NodeList contents = p.getElementsByTagName("content");
                    if (label != null && contents.getLength() > 0) {
                        String src = ((Element) contents.item(0)).getAttribute("src");
                        String file = stripFragment(src);
                        if (!file.isEmpty()) {
                            titlesByFile.put(fileName(file), label.trim());
                        }
                    }
                }
            } catch (Exception ignored) {
                // Titles are a courtesy; the spine text is the book.
            }
        }
        // Body: spine order, always.
        StringBuilder body = new StringBuilder();
        List<Chapter> chapters = new ArrayList<>();
        int part = 0;
        for (String href : spineHrefs) {
            byte[] raw = entries.get(href);
            if (raw == null) continue;
            String docText = htmlToText(raw);
            String trimmed = docText == null ? "" : docText.trim();
            if (trimmed.isEmpty()) continue;
            part++;
            String title = titlesByFile.get(fileName(href));
            if (title == null || title.isEmpty()) title = docTitle(raw);
            if (title == null || title.isEmpty()) title = "Part " + part;
            if (body.length() > 0) body.append("\n\n");
            chapters.add(new Chapter(title, body.length()));
            body.append(trimmed);
        }
        r.text = body.toString();
        r.chapters = chapters;
        r.ok = !r.text.trim().isEmpty();
        if (!r.ok) r.failReason = NO_TEXT_FOUND;
        return r;
    }

    private static Document parseXml(byte[] raw) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        // XXE hardening: books are other people's files.
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
        catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); }
        catch (Exception ignored) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
        catch (Exception ignored) {}
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(raw));
    }

    private static String firstText(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        if (l.getLength() == 0) return null;
        String t = l.item(0).getTextContent();
        return t == null ? null : t;
    }

    private static String stripFragment(String src) {
        if (src == null) return "";
        int h = src.indexOf('#');
        return h >= 0 ? src.substring(0, h) : src;
    }

    private static String fileName(String path) {
        int s = path.lastIndexOf('/');
        return s >= 0 ? path.substring(s + 1) : path;
    }

    /** The content document's own title: &lt;title&gt; or first heading. */
    private static String docTitle(byte[] raw) {
        try {
            Document d = parseXml(raw);
            String t = firstText(d.getDocumentElement(), "title");
            if (t != null && !t.trim().isEmpty()) return t.trim();
            for (String h : new String[]{"h1", "h2"}) {
                String v = firstText(d.getDocumentElement(), h);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * HTML/XHTML to spoken text: DOM walk when it parses (block
     * elements become paragraph breaks), regex strip as fallback.
     */
    public static String htmlToText(byte[] raw) {
        try {
            Document d = parseXml(raw);
            Element body = null;
            NodeList bodies = d.getElementsByTagName("body");
            if (bodies.getLength() > 0) body = (Element) bodies.item(0);
            StringBuilder sb = new StringBuilder();
            walkText(body != null ? body : d.getDocumentElement(), sb);
            String out = sb.toString().replaceAll("[ \t\\x0B\f]+", " ")
                    .replaceAll("\n{3,}", "\n\n").trim();
            if (!out.isEmpty()) return out;
        } catch (Exception ignored) {
            // fall through to the stripper
        }
        String s = new String(raw, StandardCharsets.UTF_8);
        s = s.replaceAll("(?is)<script.*?</script>", " ");
        s = s.replaceAll("(?is)<style.*?</style>", " ");
        s = s.replaceAll("(?i)<br[^>]*>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr)>", "\n\n");
        s = s.replaceAll("<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
        return s.replaceAll("[ \t\\x0B\f]+", " ")
                .replaceAll("\n{3,}", "\n\n").trim();
    }

    private static void walkText(Node node, StringBuilder sb) {
        if (node == null) return;
        if (node.getNodeType() == Node.TEXT_NODE) {
            sb.append(node.getNodeValue());
            return;
        }
        String name = node.getNodeName() == null ? "" : node.getNodeName().toLowerCase();
        boolean block = name.equals("p") || name.equals("div") || name.equals("h1")
                || name.equals("h2") || name.equals("h3") || name.equals("h4")
                || name.equals("h5") || name.equals("h6") || name.equals("li")
                || name.equals("blockquote") || name.equals("section");
        if (name.equals("script") || name.equals("style")) return;
        if (name.equals("br")) { sb.append('\n'); return; }
        if (block) sb.append("\n\n");
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) walkText(kids.item(i), sb);
        if (block) sb.append("\n\n");
    }
}
