package com.talacore.talatv;

/**
 * What language is this book written in? (v0.8.0) — pure Java,
 * script-based detection over a sample, the honest kind: it reads
 * the characters, not a filename.
 *
 *   kana present          -> Japanese ("ja")
 *   hangul present        -> Korean ("ko")
 *   han without kana/hangul -> Chinese ("zh")
 *   otherwise             -> "other" (Latin and everything else)
 *
 * Japanese is the gate that matters for translated reading: the
 * installed engine pack is ja->en, and kana is what proves a book
 * is Japanese rather than Chinese — a han-only book is NOT offered
 * the ja->en engine. It would be a wrong translation wearing a
 * right one's clothes.
 */
public final class BookLang {

    private BookLang() {}

    public static final String JA = "ja";
    public static final String KO = "ko";
    public static final String ZH = "zh";
    public static final String OTHER = "other";

    /** How much of the sample to judge by. */
    public static final int SAMPLE = 4000;

    public static String detect(String text) {
        if (text == null || text.isEmpty()) return OTHER;
        int n = Math.min(text.length(), SAMPLE);
        int kana = 0, hangul = 0, han = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF)) { // hiragana + katakana
                kana++;
            } else if (c >= 0xAC00 && c <= 0xD7A3) { // hangul syllables
                hangul++;
            } else if (c >= 0x4E00 && c <= 0x9FFF) { // han ideographs
                han++;
            }
        }
        if (kana > 0) return JA;
        if (hangul > 0) return KO;
        if (han > 0) return ZH;
        return OTHER;
    }
}
