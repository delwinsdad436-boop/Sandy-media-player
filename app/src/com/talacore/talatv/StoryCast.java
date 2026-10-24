package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The story book's voice book (v0.14.0) — the dub's VoiceBook
 * casting pointed at storybooks. A book is cast ONCE: a narrator
 * and one voice per character, saved as a file on the memory
 * hook, so the witch sounds like the witch in every chapter.
 *
 * A cast entry is one of:
 *   narrator  — reads everything that isn't dialogue
 *   character — a named person in the tale, with a pitch/rate of
 *               their own (applied over the pack or phone voice)
 *   family    — a real person of the house (Grandma, Dad) who
 *               recorded their own reading; where a recording
 *               covers a passage, the recording plays and no
 *               synthesized voice speaks that passage at all.
 *
 * Dialogue attribution is a heuristic and is honest about it:
 * quoted speech is found by the quote marks; the speaker is the
 * name a speech verb leans on ("…," said the Fox / "…" cried
 * Mother); speech nobody claims is read by the narrator. The
 * cast is collected over the whole book before the first telling
 * so voices never swap mid-book.
 *
 * Pure logic + org.json; the harness drives it with fixtures.
 */
public final class StoryCast {

    public static final String NARRATOR = "Narrator";

    /** Deterministic casting palette (pitch, rate) for characters,
     *  in enrollment order — the same book always casts the same. */
    public static final double[][] PALETTE = {
            {1.30, 1.00}, {0.78, 0.95}, {1.50, 1.05}, {0.65, 0.90},
            {1.12, 1.10}, {0.90, 1.00}, {1.65, 0.98}, {0.72, 1.08},
            {1.42, 0.92}, {0.85, 1.12}, {1.22, 0.88}, {0.60, 1.02},
    };

    public static final class Entry {
        public String name = "";
        public boolean narrator = false;
        public boolean family = false;
        public double pitch = 1.0;
        public double rate = 1.0;
        /** The phone-voice name to prefer, "" = the engine default. */
        public String ttsVoice = "";
        /** The bundled picture card that IS this character in this
         *  book (StoryPanels) — assigned once, never changes. */
        public String cardId = "";
        /** Family recordings: chunk index -> audio file name. */
        public final Map<Integer, String> recordings = new LinkedHashMap<>();
    }

    public static final class Book {
        public String bookId = "";
        public String title = "";
        public String lang = "";       // ISO-1 the book is written in
        public final List<Entry> cast = new ArrayList<>();

        public Entry narrator() {
            for (Entry e : cast) if (e.narrator) return e;
            return null;
        }

        public Entry byName(String name) {
            if (name == null) return null;
            for (Entry e : cast) {
                if (e.name.equalsIgnoreCase(name)) return e;
            }
            return null;
        }
    }

    /** One speakable piece of a chunk: who says it, and the words. */
    public static final class Segment {
        public final String speaker;   // a cast name, or NARRATOR
        public final String text;
        public Segment(String speaker, String text) {
            this.speaker = speaker;
            this.text = text;
        }
    }

    private StoryCast() {}

    /** A fresh book with only its narrator cast (calm pace). */
    public static Book newBook(String bookId, String title, String lang) {
        Book b = new Book();
        b.bookId = bookId == null ? "" : bookId;
        b.title = title == null ? "" : title;
        b.lang = lang == null ? "" : lang;
        Entry n = new Entry();
        n.name = NARRATOR;
        n.narrator = true;
        n.pitch = 1.0;
        n.rate = 0.92;
        b.cast.add(n);
        return b;
    }

    /**
     * Cast a character by name — an existing entry wins (by name,
     * case-insensitive); a new name takes the next palette slot.
     * Curated cast names are enrolled through here too, so a
     * curated witch and a discovered witch are the same person.
     */
    public static Entry enroll(Book b, String name) {
        if (b == null || name == null) return null;
        String clean = name.trim();
        if (clean.isEmpty()) return b.narrator();
        Entry found = b.byName(clean);
        if (found != null) return found;
        Entry e = new Entry();
        e.name = clean;
        int slot = 0;
        for (Entry x : b.cast) if (!x.narrator) slot++;
        double[] pr = PALETTE[slot % PALETTE.length];
        e.pitch = pr[0];
        e.rate = pr[1];
        b.cast.add(e);
        return e;
    }

    /** Cast a family member as themselves (their own voice). */
    public static Entry enrollFamily(Book b, String name) {
        Entry e = enroll(b, name);
        if (e != null) {
            e.family = true;
            e.pitch = 1.0;
            e.rate = 1.0;
        }
        return e;
    }

    // ------------------------------------------------ attribution

    private static final String[] VERBS = {
            "said", "asked", "cried", "whispered", "shouted", "replied",
            "answered", "called", "laughed", "sighed", "exclaimed",
            "murmured", "growled", "begged", "warned", "promised",
            "declared", "remarked", "screamed", "sobbed", "grumbled",
    };

    private static final String OPENQ = "“\"‘«";
    private static final String CLOSEQ = "”\"’»";

    /**
     * Split one chunk of book text into speakable segments.
     * {@code knownNames} are the book's cast names (the curation's
     * list plus everyone enrolled so far); a speech tag naming
     * one of them — or a capitalized name-like tag — enrolls and
     * attributes. Unclaimed speech falls to the narrator, except
     * a quote that directly continues the previous segment's
     * speaker in the same chunk, which stays with that speaker.
     */
    public static List<Segment> attribute(Book book, String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        String lastSpeaker = NARRATOR;
        int i = 0, n = text.length();
        StringBuilder narration = new StringBuilder();
        while (i < n) {
            char c = text.charAt(i);
            if (OPENQ.indexOf(c) >= 0) {
                int close = findClose(text, i + 1, c);
                if (close < 0) { narration.append(c); i++; continue; }
                String spoken = text.substring(i + 1, close).trim();
                // the tag zone: a little narration on both sides
                String before = narration.toString();
                int afterEnd = close + 1;
                StringBuilder afterBuf = new StringBuilder();
                while (afterEnd < n && text.charAt(afterEnd) != '.'
                        && text.charAt(afterEnd) != '!'
                        && text.charAt(afterEnd) != '?'
                        && OPENQ.indexOf(text.charAt(afterEnd)) < 0
                        && afterBuf.length() < 60) {
                    afterBuf.append(text.charAt(afterEnd));
                    afterEnd++;
                }
                String speaker = findSpeaker(book,
                        before.substring(Math.max(0, before.length() - 60)),
                        afterBuf.toString());
                if (speaker == null) {
                    // unclaimed: a quote opening mid-exchange (the
                    // narration between is only a tag remnant)
                    // stays with the last speaker; otherwise the
                    // narrator reads it.
                    speaker = before.trim().length() <= 3
                            && !lastSpeaker.equals(NARRATOR)
                            ? lastSpeaker : NARRATOR;
                }
                if (!before.trim().isEmpty()) {
                    emit(out, NARRATOR, before);
                    narration = new StringBuilder();
                }
                if (!spoken.isEmpty()) {
                    emit(out, speaker, spoken);
                    if (!speaker.equals(NARRATOR)) lastSpeaker = speaker;
                }
                // the tag remnant after the quote is narration
                narration.append(afterBuf);
                i = afterEnd;
            } else {
                narration.append(c);
                i++;
            }
        }
        if (!narration.toString().trim().isEmpty()) {
            emit(out, NARRATOR, narration.toString());
        }
        return out;
    }

    private static void emit(List<Segment> out, String speaker, String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return;
        if (!out.isEmpty() && out.get(out.size() - 1).speaker.equals(speaker)) {
            Segment prev = out.get(out.size() - 1);
            out.set(out.size() - 1, new Segment(speaker, prev.text + " " + t));
            return;
        }
        out.add(new Segment(speaker, t));
    }

    /** The closing quote PAIRED with the opener — an apostrophe
     *  inside dialogue ("don’t") must never end a quote early. */
    private static int findClose(String text, int from, char open) {
        char want;
        switch (open) {
            case '“': want = '”'; break;
            case '‘': want = '’'; break;
            case '«': want = '»'; break;
            case '"': want = '"'; break;
            case '\'': want = '\''; break;
            default: want = 0;
        }
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (want != 0 ? c == want : CLOSEQ.indexOf(c) >= 0) return i;
        }
        return -1;
    }

    /**
     * Find who a speech verb leans on, in the tag before or after
     * a quote. Patterns: "said the Fox", "the Fox said", "said
     * Alice", "Mother cried". A candidate must be a cast name or
     * look like one (a capitalized word or two, optionally led by
     * "the"). Returns the cast entry's canonical name, enrolling
     * a new character when the tag names a plausible new person.
     */
    public static String findSpeaker(Book book, String before, String after) {
        String hit = scanTag(book, after, true);
        if (hit != null) return hit;
        return scanTag(book, before, false);
    }

    private static String scanTag(Book book, String tag, boolean afterQuote) {
        if (tag == null) return null;
        String t = " " + tag.replaceAll("[,;:]", " ").trim() + " ";
        String low = t.toLowerCase(Locale.US);
        for (String verb : VERBS) {
            int vi = low.indexOf(" " + verb + " ");
            if (vi < 0) continue;
            String rest = afterQuote
                    ? t.substring(vi + verb.length() + 1)
                    : t.substring(0, vi);
            String name = nameFrom(book, rest, afterQuote);
            if (name != null) return name;
        }
        return null;
    }

    private static String nameFrom(Book book, String rest, boolean forward) {
        String[] words = rest.trim().split("\\s+");
        if (words.length == 0) return null;
        // known cast names first (longest match, either direction)
        String joined = " " + rest.trim().toLowerCase(Locale.US) + " ";
        String best = null;
        for (Entry e : book.cast) {
            if (e.narrator) continue;
            String nm = " " + e.name.toLowerCase(Locale.US) + " ";
            if (joined.contains(nm) && (best == null
                    || e.name.length() > best.length())) {
                best = e.name;
            }
        }
        if (best != null) return best;
        // a plausible new name: [the] Capitalized [Capitalized]
        List<String> seq = new ArrayList<>();
        if (forward) {
            for (String w : words) {
                String c = cleanWord(w);
                if (c.equalsIgnoreCase("the") && seq.isEmpty()) continue;
                if (isNameWord(c)) { seq.add(c); if (seq.size() == 2) break; }
                else break;
            }
        } else {
            for (int i = words.length - 1; i >= 0; i--) {
                String c = cleanWord(words[i]);
                if (isNameWord(c)) { seq.add(0, c); if (seq.size() == 2) break; }
                else if (c.equalsIgnoreCase("the") && !seq.isEmpty()) break;
                else if (!seq.isEmpty()) break;
            }
        }
        if (seq.isEmpty()) return null;
        String name = String.join(" ", seq);
        if (name.length() < 2 || name.length() > 24) return null;
        Entry e = enroll(book, name);
        return e == null ? null : e.name;
    }

    private static String cleanWord(String w) {
        return w.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", "");
    }

    private static boolean isNameWord(String w) {
        if (w.length() < 2) return false;
        if (!Character.isUpperCase(w.charAt(0))) return false;
        // sentence furniture that is capitalized but never a name
        String low = w.toLowerCase(Locale.US);
        switch (low) {
            case "the": case "and": case "but": case "then": case "so":
            case "when": case "while": case "after": case "before":
            case "suddenly": case "soon": case "now": case "there":
            case "here": case "one": case "it": case "he": case "she":
            case "they": case "we": case "you": case "in": case "on":
            case "at": case "to": case "a": case "an":
                return false;
            default:
                return true;
        }
    }

    /** Collect the cast over the whole book (chunk texts in order)
     *  so the casting stands complete before the first telling. */
    public static void collectCast(Book book, List<String> chunkTexts) {
        if (book == null || chunkTexts == null) return;
        for (String t : chunkTexts) attribute(book, t);
    }

    // ------------------------------------------------------------ JSON

    public static String toJson(Book b) throws Exception {
        JSONObject o = new JSONObject();
        o.put("bookId", b.bookId);
        o.put("title", b.title);
        o.put("lang", b.lang);
        JSONArray arr = new JSONArray();
        for (Entry e : b.cast) {
            JSONObject eo = new JSONObject();
            eo.put("name", e.name);
            eo.put("narrator", e.narrator);
            eo.put("family", e.family);
            eo.put("pitch", e.pitch);
            eo.put("rate", e.rate);
            eo.put("ttsVoice", e.ttsVoice);
            eo.put("cardId", e.cardId);
            JSONObject rec = new JSONObject();
            for (Map.Entry<Integer, String> r : e.recordings.entrySet()) {
                rec.put(String.valueOf(r.getKey()), r.getValue());
            }
            eo.put("recordings", rec);
            arr.put(eo);
        }
        o.put("cast", arr);
        return o.toString();
    }

    public static Book fromJson(String text) throws Exception {
        Book b = new Book();
        JSONObject o = new JSONObject(text);
        b.bookId = o.optString("bookId", "");
        b.title = o.optString("title", "");
        b.lang = o.optString("lang", "");
        JSONArray arr = o.optJSONArray("cast");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject eo = arr.getJSONObject(i);
                Entry e = new Entry();
                e.name = eo.optString("name", "");
                e.narrator = eo.optBoolean("narrator", false);
                e.family = eo.optBoolean("family", false);
                e.pitch = eo.optDouble("pitch", 1.0);
                e.rate = eo.optDouble("rate", 1.0);
                e.ttsVoice = eo.optString("ttsVoice", "");
                e.cardId = eo.optString("cardId", "");
                JSONObject rec = eo.optJSONObject("recordings");
                if (rec != null) {
                    java.util.Iterator<String> it = rec.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        try {
                            e.recordings.put(Integer.parseInt(k),
                                    rec.optString(k, ""));
                        } catch (NumberFormatException ignored) {}
                    }
                }
                b.cast.add(e);
            }
        }
        if (b.narrator() == null && !b.cast.isEmpty()) {
            // a book with no narrator entry is malformed; the
            // first entry does not silently become the narrator —
            // the caller re-casts from scratch.
            throw new Exception("cast has no narrator");
        }
        return b;
    }
}
