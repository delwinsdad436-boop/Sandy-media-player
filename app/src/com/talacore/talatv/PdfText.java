package com.talacore.talatv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * PDF text-layer extraction IN THE HOUSE (v0.14.0 Addendum 7 — extraction
 * comes home; the One-Hole Law applied to us). A lean, dependency-free
 * reader of a PDF's own text layer: content streams (FlateDecode inflated),
 * literal and hex strings, Tj/TJ/'/" showing operators, simple fonts read
 * as windows-1252, and CID fonts decoded through their /ToUnicode CMaps
 * (bfchar + bfrange). Page breaks follow the content streams in file order.
 * No rendering, no images, no OCR: a book that carries no text layer yields
 * almost nothing, and BookText names that honestly (scanned-image book)
 * instead of handing the viewer an errand to another program.
 */
public final class PdfText {

    public static final class Extract {
        public String text = "";
        public List<String> pages = new ArrayList<>();
        public int pageObjects = 0;
        public int textChars = 0;
    }

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final Charset CP1252 = Charset.forName("windows-1252");
    private static final Pattern OBJ_HEAD =
            Pattern.compile("(\\d+)\\s+\\d+\\s+obj");
    private static final Pattern PAGE_TYPE =
            Pattern.compile("/Type\\s*/Page(?![sA-Za-z])");
    private static final Pattern TO_UNICODE =
            Pattern.compile("/ToUnicode\\s+(\\d+)\\s+\\d+\\s+R");
    private static final Pattern FONT_REF =
            Pattern.compile("/([A-Za-z][A-Za-z0-9]*)\\s+(\\d+)\\s+\\d+\\s+R");

    private PdfText() {}

    public static Extract extract(byte[] pdf) {
        Extract out = new Extract();
        if (pdf == null || pdf.length < 5) return out;
        String raw = new String(pdf, LATIN1);
        Matcher pm = PAGE_TYPE.matcher(raw);
        while (pm.find()) out.pageObjects++;

        // 1) pull every stream, in file order, with its owning obj number.
        List<int[]> owners = new ArrayList<>();        // obj number per stream
        List<String> streams = new ArrayList<>();      // decoded stream text
        int scan = 0;
        while (true) {
            int sIdx = raw.indexOf("stream", scan);
            if (sIdx < 0) break;
            // "stream" must not be the tail of "endstream"
            if (sIdx >= 3 && raw.regionMatches(sIdx - 3, "end", 0, 3)) {
                scan = sIdx + 6; continue;
            }
            int dataStart = sIdx + 6;
            if (dataStart < raw.length() && raw.charAt(dataStart) == '\r') dataStart++;
            if (dataStart < raw.length() && raw.charAt(dataStart) == '\n') dataStart++;
            int eIdx = raw.indexOf("endstream", dataStart);
            if (eIdx < 0) break;
            int dataEnd = eIdx;
            String dict = raw.substring(Math.max(0, sIdx - 1200), sIdx);
            byte[] data = raw.substring(dataStart, dataEnd).getBytes(LATIN1);
            byte[] decoded = data;
            if (dict.contains("FlateDecode")) {
                // Exact slice first; a whitespace-trimmed slice as the
                // alternate manner (a blind trim can eat a checksum byte).
                byte[] inf = inflate(data);
                if (inf == null) {
                    int tEnd = dataEnd;
                    if (tEnd > dataStart && raw.charAt(tEnd - 1) == '\n') tEnd--;
                    if (tEnd > dataStart && raw.charAt(tEnd - 1) == '\r') tEnd--;
                    inf = inflate(raw.substring(dataStart, tEnd).getBytes(LATIN1));
                }
                if (inf != null) decoded = inf;
            }
            int owner = -1;
            Matcher om = OBJ_HEAD.matcher(raw.substring(0, sIdx));
            while (om.find()) owner = Integer.parseInt(om.group(1));
            owners.add(new int[]{owner});
            streams.add(new String(decoded, LATIN1));
            scan = eIdx + 9;
        }

        // 2) ToUnicode CMaps, by owning object.
        Map<Integer, Map<Integer, String>> cmapByObj = new HashMap<>();
        Map<Integer, String> merged = new LinkedHashMap<>();
        for (int i = 0; i < streams.size(); i++) {
            String st = streams.get(i);
            if (st.contains("beginbfchar") || st.contains("beginbfrange")) {
                Map<Integer, String> cm = parseCMap(st);
                if (!cm.isEmpty()) {
                    cmapByObj.put(owners.get(i)[0], cm);
                    for (Map.Entry<Integer, String> e : cm.entrySet()) {
                        if (!merged.containsKey(e.getKey())) {
                            merged.put(e.getKey(), e.getValue());
                        }
                    }
                }
            }
        }

        // 3) font resource name -> its CMap (via font obj -> /ToUnicode ref).
        Map<String, Map<Integer, String>> fontCMap = new HashMap<>();
        Matcher fm = FONT_REF.matcher(raw);
        while (fm.find()) {
            String name = fm.group(1);
            if (!name.startsWith("F")) continue;
            int obj = Integer.parseInt(fm.group(2));
            if (fontCMap.containsKey(name)) continue;
            String body = objectBody(raw, obj);
            if (body == null || !body.contains("/Font")) continue;
            Matcher tm = TO_UNICODE.matcher(body);
            if (tm.find()) {
                Map<Integer, String> cm = cmapByObj.get(
                        Integer.parseInt(tm.group(1)));
                if (cm != null) fontCMap.put(name, cm);
            }
        }

        // 4) walk content streams; collect text per stream (≈ per page).
        StringBuilder whole = new StringBuilder();
        for (String st : streams) {
            if (!st.contains("BT")) continue;
            String pageText = walkContent(st, fontCMap, merged);
            String trimmed = pageText.trim();
            if (!trimmed.isEmpty()) {
                out.pages.add(trimmed);
                if (whole.length() > 0) whole.append("\n\n");
                whole.append(trimmed);
            }
        }
        out.text = whole.toString();
        out.textChars = 0;
        for (int i = 0; i < out.text.length(); i++) {
            if (!Character.isWhitespace(out.text.charAt(i))) out.textChars++;
        }
        return out;
    }

    /** The raw dictionary region of object n (best-effort, lean). */
    private static String objectBody(String raw, int objNum) {
        Matcher m = OBJ_HEAD.matcher(raw);
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) == objNum) {
                int end = raw.indexOf("endobj", m.end());
                if (end < 0) end = Math.min(raw.length(), m.end() + 2000);
                return raw.substring(m.end(), end);
            }
        }
        return null;
    }

    private static byte[] inflate(byte[] data) {
        // zlib wrapping first; bare deflate as the alternate manner.
        try {
            InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(data));
            return drain(in);
        } catch (Exception ignored) { }
        try {
            InflaterInputStream in = new InflaterInputStream(
                    new ByteArrayInputStream(data), new Inflater(true));
            return drain(in);
        } catch (Exception ignored) { }
        return null;
    }

    private static byte[] drain(InflaterInputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toByteArray();
    }

    // ---------------------------------------------------------- ToUnicode

    static Map<Integer, String> parseCMap(String st) {
        Map<Integer, String> map = new LinkedHashMap<>();
        // bfchar: <src> <dst>
        Matcher m = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                .matcher(st);
        // Ranges need pairing context; handle bfrange blocks separately:
        int idx = 0;
        while (true) {
            int b = st.indexOf("beginbfrange", idx);
            if (b < 0) break;
            int e = st.indexOf("endbfrange", b);
            if (e < 0) break;
            String block = st.substring(b, e);
            Matcher rm = Pattern.compile(
                    "<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                    .matcher(block);
            while (rm.find()) {
                int lo = Integer.parseInt(rm.group(1), 16);
                int hi = Integer.parseInt(rm.group(2), 16);
                int dst = Integer.parseInt(rm.group(3), 16);
                if (hi - lo > 4096) continue;
                for (int c = lo; c <= hi; c++) {
                    map.put(c, String.valueOf((char) (dst + (c - lo))));
                }
            }
            // bfrange with an array of destinations.
            Matcher am = Pattern.compile(
                    "<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[(.*?)\\]",
                    Pattern.DOTALL).matcher(block);
            while (am.find()) {
                int lo = Integer.parseInt(am.group(1), 16);
                Matcher dm = Pattern.compile("<([0-9A-Fa-f]+)>")
                        .matcher(am.group(3));
                int off = 0;
                while (dm.find()) {
                    map.put(lo + off, utf16FromHex(dm.group(1)));
                    off++;
                }
            }
            idx = e + 10;
        }
        // bfchar blocks (skip pairs already claimed by ranges above is fine —
        // bfchar entries carry their own explicit destinations).
        idx = 0;
        while (true) {
            int b = st.indexOf("beginbfchar", idx);
            if (b < 0) break;
            int e = st.indexOf("endbfchar", b);
            if (e < 0) break;
            Matcher cm = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                    .matcher(st.substring(b, e));
            while (cm.find()) {
                map.put(Integer.parseInt(cm.group(1), 16),
                        utf16FromHex(cm.group(2)));
            }
            idx = e + 9;
        }
        return map;
    }

    private static String utf16FromHex(String hex) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 3 < hex.length() + 1 && i + 1 < hex.length(); i += 4) {
            // destination strings are UTF-16BE code units (4 hex digits each)
            if (i + 4 <= hex.length()) {
                sb.append((char) Integer.parseInt(hex.substring(i, i + 4), 16));
            }
        }
        if (sb.length() == 0 && hex.length() >= 2) {
            // single-byte destination: plain code point
            sb.append((char) Integer.parseInt(hex, 16));
        }
        return sb.toString();
    }

    // ------------------------------------------------------- content walk

    private static String walkContent(String st,
            Map<String, Map<Integer, String>> fontCMap,
            Map<Integer, String> merged) {
        StringBuilder sb = new StringBuilder();
        String font = "";
        int i = 0, n = st.length();
        StringBuilder pending = new StringBuilder();  // decoded, awaiting show op
        boolean pendingIsArray = false;
        while (i < n) {
            char c = st.charAt(i);
            if (c == '(') {
                StrLit lit = parseLiteral(st, i);
                pending.append(decodeBytes(lit.bytes,
                        fontCMap.get(font), merged));
                i = lit.next;
            } else if (c == '<' && i + 1 < n && st.charAt(i + 1) != '<') {
                int close = st.indexOf('>', i);
                if (close < 0) break;
                byte[] bytes = hexBytes(st.substring(i + 1, close));
                pending.append(decodeBytes(bytes,
                        fontCMap.get(font), merged));
                i = close + 1;
            } else if (c == '[') {
                pendingIsArray = true;
                i++;
            } else if (c == ']') {
                i++;
            } else if (c == '/') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(st.charAt(j)))) j++;
                String name = st.substring(i + 1, j);
                // font selection looks like: /F1 12 Tf
                int k = skipWs(st, j);
                int e = k;
                while (e < n && (Character.isDigit(st.charAt(e))
                        || st.charAt(e) == '.')) e++;
                int k2 = skipWs(st, e);
                if (k2 + 1 < n && st.charAt(k2) == 'T' && st.charAt(k2 + 1) == 'f'
                        && e > k) {
                    font = name;
                    i = k2 + 2;
                } else {
                    i = j;
                }
            } else if (Character.isLetter(c) || c == '\'' || c == '"') {
                int j = i;
                while (j < n && (Character.isLetter(st.charAt(j)))) j++;
                String op = st.substring(i, j);
                if (op.equals("Tj") || op.equals("TJ")
                        || op.equals("'") || op.equals("\"")) {
                    sb.append(pending);
                    pending.setLength(0);
                    pendingIsArray = false;
                    if (op.equals("'") || op.equals("\"")) newline(sb);
                    i = j;
                } else if (op.equals("Td") || op.equals("TD")
                        || op.equals("Tm") || op.equals("T*")) {
                    newline(sb);
                    pending.setLength(0);
                    i = j;
                } else if (op.equals("ET")) {
                    newline(sb);
                    pending.setLength(0);
                    i = j;
                } else {
                    i = Math.max(j, i + 1);
                }
                if ((c == '\'' || c == '"') && op.isEmpty()) {
                    // ' and " show the string on the NEXT line: break first.
                    newline(sb);
                    sb.append(pending);
                    pending.setLength(0);
                    i = i + 1;
                }
            } else if (Character.isDigit(c) || c == '-' || c == '+') {
                int j = i;
                while (j < n && (Character.isDigit(st.charAt(j))
                        || st.charAt(j) == '.' || st.charAt(j) == '-'
                        || st.charAt(j) == '+')) j++;
                if (pendingIsArray) {
                    try {
                        double v = Double.parseDouble(st.substring(i, j));
                        if (v <= -120) sb.append(pendingSpace(sb, pending));
                    } catch (NumberFormatException ignored) { }
                }
                i = j;
            } else {
                i++;
            }
        }
        return sb.toString();
    }

    private static String pendingSpace(StringBuilder sb, StringBuilder pending) {
        // a large negative TJ kern means a word gap: flush + space
        sb.append(pending);
        pending.setLength(0);
        return " ";
    }

    private static void newline(StringBuilder sb) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }

    /** Decode shown bytes: CID (2-byte codes via CMap) when the font has
     *  one, else the merged CMap if the bytes parse cleanly as codes,
     *  else plain windows-1252. */
    private static String decodeBytes(byte[] bytes,
            Map<Integer, String> fontMap, Map<Integer, String> merged) {
        Map<Integer, String> use = fontMap;
        if (use == null && bytes.length >= 2 && bytes.length % 2 == 0
                && !merged.isEmpty()) {
            boolean allHit = true;
            for (int i = 0; i < bytes.length; i += 2) {
                int code = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
                if (!merged.containsKey(code)) { allHit = false; break; }
            }
            if (allHit) use = merged;
        }
        if (use != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i + 1 < bytes.length; i += 2) {
                int code = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
                String mapped = use.get(code);
                if (mapped != null) sb.append(mapped);
            }
            return sb.toString();
        }
        return new String(bytes, CP1252);
    }

    private static final class StrLit {
        byte[] bytes; int next;
    }

    private static StrLit parseLiteral(String st, int open) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int i = open + 1, depth = 1, n = st.length();
        while (i < n && depth > 0) {
            char c = st.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char e = st.charAt(i + 1);
                if (e >= '0' && e <= '7') {
                    int v = 0, k = 0;
                    while (k < 3 && i + 1 + k < n) {
                        char d = st.charAt(i + 1 + k);
                        if (d < '0' || d > '7') break;
                        v = v * 8 + (d - '0'); k++;
                    }
                    bos.write(v);
                    i += 1 + k;
                } else {
                    switch (e) {
                        case 'n': bos.write('\n'); break;
                        case 'r': bos.write('\r'); break;
                        case 't': bos.write('\t'); break;
                        case 'b': bos.write('\b'); break;
                        case 'f': bos.write('\f'); break;
                        case '(': bos.write('('); break;
                        case ')': bos.write(')'); break;
                        case '\\': bos.write('\\'); break;
                        case '\n': break;               // line continuation
                        case '\r': break;
                        default: bos.write(e); break;
                    }
                    i += 2;
                }
            } else if (c == '(') {
                depth++; bos.write('('); i++;
            } else if (c == ')') {
                depth--;
                if (depth > 0) bos.write(')');
                i++;
            } else {
                bos.write((byte) c);
                i++;
            }
        }
        StrLit out = new StrLit();
        out.bytes = bos.toByteArray();
        out.next = i;
        return out;
    }

    private static byte[] hexBytes(String hex) {
        String h = hex.replaceAll("\\s+", "");
        if (h.length() % 2 == 1) h += "0";
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
