package com.talacore.talatv;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The reading room's kept state (v0.16.0) — everything a FILE
 * on the memory hook, per the house law (file in, file out):
 *
 *   reading/<key>.pos    where the reader left this book:
 *                        char offset, mode, page leaf, stamp.
 *   reading/<key>.marks  the book's bookmarks: one per line,
 *                        char offset TAB label.
 *   reading/settings.tsv the reader's own settings: theme,
 *                        type size, page-vs-scroll mode — kept
 *                        across every book and every session.
 *
 * Keys are source-qualified (ia_<id>, gutenberg_pg1342,
 * local_<bookstore id>) so the same title from two shelves
 * never shares a place by accident.
 *
 * Pure file logic over a root directory (the app passes the
 * MemoryHook root; the harness passes a temp dir).
 */
public final class ReaderBook {

    public static final String MODE_PAGED = "paged";
    public static final String MODE_SCROLL = "scroll";
    public static final String MODE_PAGES = "pages";   // page pictures

    public static final String THEME_DAY = "day";
    public static final String THEME_NIGHT = "night";
    public static final String THEME_CONTRAST = "contrast";

    public static final int MIN_SP = 14;
    public static final int MAX_SP = 44;

    public static final class Pos {
        public int charOffset = 0;
        public String mode = MODE_PAGED;
        public int leaf = 0;          // page-image leaf, pages mode
        public long updatedMs = 0;
    }

    public static final class Mark {
        public int charOffset = 0;
        public String label = "";
    }

    public static final class Settings {
        public String theme = THEME_DAY;
        public int textSp = 21;
        public String mode = MODE_PAGED;
    }

    private ReaderBook() {}

    /** Source-qualified, filename-safe book key. */
    public static String keyFor(String source, String itemId) {
        String s = (source == null ? "" : source) + "_"
                + (itemId == null ? "" : itemId);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == '-' || c == '_'
                    ? c : '_');
        }
        String key = b.toString();
        if (key.length() > 80) {
            key = key.substring(0, 56) + "_"
                    + Integer.toHexString(key.hashCode());
        }
        return key;
    }

    public static File dir(File root) {
        return new File(root, "reading");
    }

    public static File posFile(File root, String key) {
        return new File(dir(root), key + ".pos");
    }

    public static File marksFile(File root, String key) {
        return new File(dir(root), key + ".marks");
    }

    public static File settingsFile(File root) {
        return new File(dir(root), "settings.tsv");
    }

    // ------------------------------------------------ positions

    public static String posToText(Pos p) {
        return p.charOffset + "\t" + p.mode + "\t" + p.leaf + "\t"
                + p.updatedMs + "\n";
    }

    public static Pos posFromText(String text) {
        Pos p = new Pos();
        if (text == null) return p;
        String[] f = text.trim().split("\t", -1);
        try {
            if (f.length > 0) p.charOffset = Math.max(0,
                    Integer.parseInt(f[0].trim()));
            if (f.length > 1 && !f[1].trim().isEmpty()) {
                p.mode = f[1].trim();
            }
            if (f.length > 2) p.leaf = Math.max(0,
                    Integer.parseInt(f[2].trim()));
            if (f.length > 3) p.updatedMs = Long.parseLong(f[3].trim());
        } catch (Exception ignored) {}
        return p;
    }

    public static Pos loadPos(File root, String key) {
        return posFromText(read(posFile(root, key)));
    }

    public static void savePos(File root, String key, Pos p) {
        write(posFile(root, key), posToText(p));
    }

    // ------------------------------------------------ bookmarks

    public static String marksToText(List<Mark> marks) {
        StringBuilder b = new StringBuilder();
        for (Mark m : marks) {
            b.append(m.charOffset).append('\t')
                    .append(m.label == null ? "" : m.label.replace('\t', ' ')
                            .replace('\n', ' '))
                    .append('\n');
        }
        return b.toString();
    }

    public static List<Mark> marksFromText(String text) {
        List<Mark> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length == 0) continue;
            try {
                Mark m = new Mark();
                m.charOffset = Integer.parseInt(f[0].trim());
                m.label = f.length > 1 ? f[1] : "";
                if (m.charOffset >= 0) out.add(m);
            } catch (Exception ignored) {}
        }
        out.sort((a, b) -> Integer.compare(a.charOffset, b.charOffset));
        return out;
    }

    public static List<Mark> loadMarks(File root, String key) {
        return marksFromText(read(marksFile(root, key)));
    }

    public static void saveMarks(File root, String key, List<Mark> marks) {
        write(marksFile(root, key), marksToText(marks));
    }

    /** Add-or-replace a bookmark at an offset; returns the list. */
    public static List<Mark> withMark(List<Mark> marks, int charOffset,
                                      String label) {
        List<Mark> out = new ArrayList<>();
        for (Mark m : marks) {
            if (m.charOffset != charOffset) out.add(m);
        }
        Mark m = new Mark();
        m.charOffset = charOffset;
        m.label = label == null ? "" : label;
        out.add(m);
        out.sort((a, b) -> Integer.compare(a.charOffset, b.charOffset));
        return out;
    }

    // ------------------------------------------------ settings

    public static String settingsToText(Settings s) {
        return "theme\t" + s.theme + "\n"
                + "sp\t" + s.textSp + "\n"
                + "mode\t" + s.mode + "\n";
    }

    public static Settings settingsFromText(String text) {
        Settings s = new Settings();
        if (text == null) return s;
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 2) continue;
            String k = f[0].trim(), v = f[1].trim();
            try {
                if ("theme".equals(k) && (THEME_DAY.equals(v)
                        || THEME_NIGHT.equals(v)
                        || THEME_CONTRAST.equals(v))) s.theme = v;
                else if ("sp".equals(k)) s.textSp = Math.max(MIN_SP,
                        Math.min(MAX_SP, Integer.parseInt(v)));
                else if ("mode".equals(k) && (MODE_PAGED.equals(v)
                        || MODE_SCROLL.equals(v))) s.mode = v;
            } catch (Exception ignored) {}
        }
        return s;
    }

    public static Settings loadSettings(File root) {
        return settingsFromText(read(settingsFile(root)));
    }

    public static void saveSettings(File root, Settings s) {
        write(settingsFile(root), settingsToText(s));
    }

    /** The next theme in the reader's cycle: day → night → contrast. */
    public static String nextTheme(String theme) {
        if (THEME_DAY.equals(theme)) return THEME_NIGHT;
        if (THEME_NIGHT.equals(theme)) return THEME_CONTRAST;
        return THEME_DAY;
    }

    // ------------------------------------------------ pagination

    /**
     * Page starts for paged mode: fixed char windows snapped
     * back to a word boundary (a page never opens mid-word
     * when a space is near). Pure, so the harness pins it and
     * the room's page numbers are the same on every device.
     */
    public static List<Integer> pageStarts(String text, int charsPerPage) {
        List<Integer> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add(0);
            return out;
        }
        int cpp = Math.max(120, charsPerPage);
        int at = 0;
        out.add(0);
        while (at + cpp < text.length()) {
            int end = at + cpp;
            int snap = end;
            for (int i = end; i > end - 60 && i > at; i--) {
                if (Character.isWhitespace(text.charAt(i))) {
                    snap = i + 1;
                    break;
                }
            }
            at = snap;
            out.add(at);
        }
        return out;
    }

    /** Chars per page for a type size — bigger type, fewer chars. */
    public static int charsPerPage(int textSp) {
        double scale = 21.0 / Math.max(MIN_SP, Math.min(MAX_SP, textSp));
        return Math.max(220, (int) (950 * scale * scale));
    }

    /** Index of the page containing a char offset. */
    public static int pageAtOffset(List<Integer> starts, int charOffset) {
        int page = 0;
        for (int i = 0; i < starts.size(); i++) {
            if (starts.get(i) <= charOffset) page = i;
            else break;
        }
        return page;
    }

    // ------------------------------------------------ io

    private static String read(File f) {
        try {
            if (!f.isFile()) return "";
            return new String(Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(File f, String text) {
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }
}
