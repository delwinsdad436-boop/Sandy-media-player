package com.talacore.talatv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Subtitle harvest (v0.12.0) — James's subtitle-first pipeline,
 * first stage: the text a video ALREADY carries, lifted out whole.
 *
 * Nearly every video ships with subtitles now, often in several
 * languages. If that text is in ANY known language, the translation
 * matrix can move it directly between written languages — no ear
 * involved, no transcription errors inherited, and the timings are
 * the dub's clock for free. The ear (ASR) is the fallback for video
 * with no usable subtitle track, never the first road.
 *
 * Formats, the three anime actually ships: SRT, WebVTT, and ASS —
 * ASS matters doubly: fansub files carry the SPEAKER'S NAME in the
 * dialogue Name field when the subbers bothered, and VTT voice
 * spans (<v Name>) do the same. Named lines feed the casting desk
 * even before a diarizer hears a word.
 *
 * Pure text work; the harness pins all three parsers on fixtures,
 * including the dirty real-world shapes (override blocks, cue
 * settings, multi-line cues, SDH brackets kept — cleaning is
 * conservative: tags and control codes go, words stay).
 */
public final class SubHarvest {

    public static final class Track {
        /** Normalized language code ("ja", "en", …) or "" when the
         *  file's name doesn't say — never guessed from content. */
        public String lang = "";
        public String format = "";      // srt | vtt | ass
        public final List<CookedSubs.Line> lines = new ArrayList<>();
        /** Lines carrying a speaker name (ASS Name / VTT <v>). */
        public int namedLines = 0;
        public double durationSec = 0;
    }

    private SubHarvest() {}

    // ------------------------------------------------------------ names

    private static final Map<String, String> LANG_ALIASES = new LinkedHashMap<>();
    static {
        String[][] pairs = {
            {"eng", "en"}, {"spa", "es"}, {"fre", "fr"}, {"fra", "fr"},
            {"ger", "de"}, {"deu", "de"}, {"por", "pt"}, {"ita", "it"},
            {"rus", "ru"}, {"ara", "ar"}, {"hin", "hi"}, {"chi", "zh"},
            {"zho", "zh"}, {"jpn", "ja"}, {"kor", "ko"}, {"fas", "fa"},
            {"per", "fa"}, {"english", "en"}, {"spanish", "es"},
            {"french", "fr"}, {"german", "de"}, {"portuguese", "pt"},
            {"italian", "it"}, {"russian", "ru"}, {"arabic", "ar"},
            {"hindi", "hi"}, {"chinese", "zh"}, {"japanese", "ja"},
            {"korean", "ko"}, {"persian", "fa"}, {"farsi", "fa"},
        };
        for (String[] p : pairs) LANG_ALIASES.put(p[0], p[1]);
    }

    /** Normalize a raw language tag to a 2-letter code when known;
     *  a clean 2-letter tag passes; anything else returns "". */
    public static String normalizeLang(String raw) {
        if (raw == null) return "";
        String t = raw.trim().toLowerCase(Locale.US);
        int dash = t.indexOf('-');
        if (dash > 0) t = t.substring(0, dash);
        int us = t.indexOf('_');
        if (us > 0) t = t.substring(0, us);
        String mapped = LANG_ALIASES.get(t);
        if (mapped != null) return mapped;
        if (t.matches("[a-z]{2}")) return t;
        return "";
    }

    /**
     * The language a subtitle FILE NAME declares: "Show.ja.srt",
     * "Show.eng.srt", "Show.en.forced.srt", "Show.english.srt" all
     * speak; "Show.srt" and "Show.tr-en.srt" (a cooked track — ours,
     * not a source) stay "". The stem is the video's file name.
     */
    public static String langFromName(String subFileName, String videoStem) {
        if (subFileName == null) return "";
        String base = subFileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        String lower = base.toLowerCase(Locale.US);
        String stem = videoStem == null ? "" : videoStem.toLowerCase(Locale.US);
        if (!stem.isEmpty() && lower.startsWith(stem)) {
            base = base.substring(stem.length());
        } else {
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
        }
        // base now looks like ".ja", ".en.forced", ".tr-en", ""
        String[] parts = base.split("[.\\-_ ]+");
        for (String p : parts) {
            if (p.isEmpty() || p.equals("srt")
                    || p.equals("vtt") || p.equals("ass")) continue;
            // ".tr-<lang>" is Sandy's own COOKED track, not a source:
            // its declared language is the cooking's output, and the
            // harvest must never mistake it for a track the video
            // carried in.
            if (p.equals("tr")) return "";
            String n = normalizeLang(p);
            if (!n.isEmpty()) return n;
            return "";   // first real token isn't a language: don't guess deeper
        }
        return "";
    }

    /** Exact sidecar names worth probing beside a video file, best
     *  first: bare (any language), then the spoken codes, then the
     *  common 3-letter tags — for each of srt/vtt/ass. */
    public static List<String> candidateNames(String videoFileName) {
        List<String> out = new ArrayList<>();
        String stem = DubTrack.stemOf(videoFileName);
        if (stem.isEmpty()) return out;
        String[] exts = {"srt", "vtt", "ass"};
        String[] codes = {"ja", "en", "es", "fr", "de", "pt", "it", "ru",
                "ar", "hi", "zh", "ko", "fa"};
        String[] codes3 = {"jpn", "eng", "spa", "fre", "ger", "por", "ita",
                "rus", "ara", "hin", "chi", "kor", "fas"};
        for (String ext : exts) out.add(stem + "." + ext);
        for (String ext : exts) {
            for (String c : codes) out.add(stem + "." + c + "." + ext);
        }
        for (String ext : exts) {
            for (String c : codes3) out.add(stem + "." + c + "." + ext);
        }
        return out;
    }

    public static boolean isSubtitleName(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".srt") || lower.endsWith(".vtt")
                || lower.endsWith(".ass") || lower.endsWith(".ssa");
    }

    /** The format a file name or content declares; "" when neither. */
    public static String sniffFormat(String fileName, String contentHead) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".vtt")) return "vtt";
        if (lower.endsWith(".ass") || lower.endsWith(".ssa")) return "ass";
        if (lower.endsWith(".srt")) return "srt";
        if (contentHead != null) {
            String head = contentHead.trim();
            if (head.startsWith("WEBVTT")) return "vtt";
            if (head.contains("[Script Info]") || head.contains("[Events]")) return "ass";
            if (head.contains("-->")) return "srt";
        }
        return "";
    }

    // ------------------------------------------------------------ parse

    /** Parse a subtitle file's text into a Track. Lines come out in
     *  file order, cleaned; empty-after-cleaning lines drop. */
    public static Track parse(String fileName, String content) {
        Track t = new Track();
        if (content == null) content = "";
        String head = content.substring(0, Math.min(512, content.length()));
        t.format = sniffFormat(fileName, head);
        List<CookedSubs.Line> raw;
        switch (t.format) {
            case "vtt": raw = parseVtt(content, t); break;
            case "ass": raw = parseAss(content, t); break;
            case "srt": raw = CookedSubs.parseSrt(content); break;
            default: raw = new ArrayList<>(); break;
        }
        for (CookedSubs.Line l : raw) {
            l.text = clean(l.text);
            if (l.text.isEmpty() || l.endMs <= l.startMs) continue;
            if (l.speaker == null) l.speaker = "";
            if (!l.speaker.isEmpty()) t.namedLines++;
            t.lines.add(l);
            if (l.endMs / 1000.0 > t.durationSec) t.durationSec = l.endMs / 1000.0;
        }
        return t;
    }

    /** Conservative cleaning: markup and control codes out, words kept. */
    public static String clean(String text) {
        if (text == null) return "";
        String s = text;
        s = s.replaceAll("\\{\\\\[^}]*\\}", " ");       // ASS override blocks
        s = s.replaceAll("\\\\[NnHh]", " ");            // ASS hard/soft breaks + space
        s = s.replaceAll("<[^>]+>", " ");              // HTML-ish tags (SRT/VTT)
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    // ------------------------------------------------------------ VTT

    public static List<CookedSubs.Line> parseVtt(String content, Track t) {
        List<CookedSubs.Line> out = new ArrayList<>();
        String[] blocks = content.replace("\r\n", "\n").split("\n\n");
        for (String block : blocks) {
            String[] ls = block.split("\n");
            long start = -1, end = -1;
            StringBuilder body = new StringBuilder();
            String speaker = "";
            for (String rawLine : ls) {
                String line = rawLine.trim();
                if (line.contains("-->")) {
                    String[] p = line.split("-->");
                    if (p.length == 2) {
                        start = parseVttTime(p[0].trim());
                        String endPart = p[1].trim().split("\\s+")[0];
                        end = parseVttTime(endPart);
                    }
                } else if (!line.isEmpty() && !line.equals("WEBVTT")
                        && !line.startsWith("NOTE") && !line.startsWith("STYLE")
                        && !line.startsWith("REGION")) {
                    // a cue identifier line (no timing yet) is skipped
                    if (start < 0 && !line.contains(":")) continue;
                    if (start < 0) continue;
                    String vName = voiceName(line);
                    if (!vName.isEmpty()) speaker = vName;
                    if (body.length() > 0) body.append(' ');
                    body.append(line);
                }
            }
            if (start >= 0 && end > start) {
                CookedSubs.Line l = new CookedSubs.Line();
                l.startMs = start;
                l.endMs = end;
                l.text = body.toString();
                l.speaker = speaker;
                out.add(l);
            }
        }
        return out;
    }

    /** "<v Taro>…" → "Taro"; anything else → "". */
    static String voiceName(String line) {
        String s = line.trim();
        if (!s.startsWith("<v ") && !s.startsWith("<v.")) return "";
        int gt = s.indexOf('>');
        if (gt < 0) return "";
        String inner = s.substring(2, gt).trim();
        int dot = inner.indexOf('.');
        if (dot >= 0) inner = inner.substring(dot + 1).trim();
        int sp = inner.indexOf(' ');
        if (sp >= 0) inner = inner.substring(0, sp);
        return inner.trim();
    }

    private static long parseVttTime(String t) {
        try {
            String[] p = t.split("[:.]");
            if (p.length == 4) {
                return Long.parseLong(p[0]) * 3600000 + Long.parseLong(p[1]) * 60000
                        + Long.parseLong(p[2]) * 1000 + Long.parseLong(p[3]);
            }
            if (p.length == 3) {
                return Long.parseLong(p[0]) * 60000 + Long.parseLong(p[1]) * 1000
                        + Long.parseLong(p[2]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    // ------------------------------------------------------------ ASS

    public static List<CookedSubs.Line> parseAss(String content, Track t) {
        List<CookedSubs.Line> out = new ArrayList<>();
        String[] fieldOrder = {"Layer", "Start", "End", "Style", "Name",
                "MarginL", "MarginR", "MarginV", "Effect", "Text"};
        boolean inEvents = false;
        for (String rawLine : content.replace("\r\n", "\n").split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("[")) {
                inEvents = line.equalsIgnoreCase("[Events]");
                continue;
            }
            if (!inEvents) continue;
            if (line.startsWith("Format:")) {
                String[] f = line.substring("Format:".length()).split(",");
                fieldOrder = new String[f.length];
                for (int i = 0; i < f.length; i++) fieldOrder[i] = f[i].trim();
                continue;
            }
            if (!line.startsWith("Dialogue:")) continue;
            String rest = line.substring("Dialogue:".length()).trim();
            String[] parts = rest.split(",", fieldOrder.length);
            if (parts.length < fieldOrder.length) continue;
            Map<String, String> fields = new LinkedHashMap<>();
            for (int i = 0; i < fieldOrder.length; i++) {
                fields.put(fieldOrder[i].toLowerCase(Locale.US), parts[i].trim());
            }
            long start = parseAssTime(fields.getOrDefault("start", ""));
            long end = parseAssTime(fields.getOrDefault("end", ""));
            if (start < 0 || end <= start) continue;
            CookedSubs.Line l = new CookedSubs.Line();
            l.startMs = start;
            l.endMs = end;
            l.text = fields.getOrDefault("text", "");
            String name = fields.getOrDefault("name", "");
            l.speaker = name == null ? "" : name;
            out.add(l);
        }
        return out;
    }

    private static long parseAssTime(String t) {
        try {
            String[] p = t.split("[:.]");
            if (p.length != 4) return -1;
            long cs = Long.parseLong(p[3]);
            if (p[3].length() == 2) cs *= 10;   // centiseconds
            return Long.parseLong(p[0]) * 3600000 + Long.parseLong(p[1]) * 60000
                    + Long.parseLong(p[2]) * 1000 + cs;
        } catch (Exception e) {
            return -1;
        }
    }
}
