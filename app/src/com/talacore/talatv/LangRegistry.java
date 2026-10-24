package com.talacore.talatv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue language registry (v0.11.0) — the decoupling James
 * ordered. Before this build a language could only open a wall if it
 * also had a full interface table in UiStrings (the 13 Sandy SPEAKS).
 * The catalogue itself never cared: CatalogIndex buckets any language
 * the walk touches. This registry is the door list between them:
 * every language TVmaze carries gets an entry — name, the name in its
 * own script where we know it, and how much of the index it holds —
 * whether or not Sandy speaks it.
 *
 * The registry is DERIVED, not baked: the seed asset
 * (assets/lang_registry.tsv) carries only the last full-index
 * measurement, and the on-phone walk refreshes the gathered counts
 * from CatalogIndex.State on every open. A language TVmaze adds
 * tomorrow appears the first time the walk touches it, with no code
 * change and no new build.
 *
 * Pure core (parse / merge / order) is static and harness-tested;
 * only the asset read touches Android.
 */
public final class LangRegistry {

    private LangRegistry() {}

    /** One registry entry: a language the catalogue can SHOW. */
    public static final class Entry {
        /** TVmaze's language name ("Persian", "Scottish Gaelic"). */
        public final String tvName;
        /** The name in the language's own script, "" when unknown. */
        public final String nativeName;
        /** Shows in the full TVmaze index (last measurement / walk). */
        public final long indexCount;
        /** Shows the on-phone walk has gathered so far. */
        public final long gatheredCount;
        /** The interface language code when Sandy SPEAKS it, else "". */
        public final String spokenCode;

        Entry(String tvName, String nativeName, long indexCount,
              long gatheredCount, String spokenCode) {
            this.tvName = tvName;
            this.nativeName = nativeName == null ? "" : nativeName;
            this.indexCount = indexCount;
            this.gatheredCount = gatheredCount;
            this.spokenCode = spokenCode == null ? "" : spokenCode;
        }

        /** What a wall/chip shows: own script first, TVmaze's name as the floor. */
        public String displayName() {
            return nativeName.isEmpty() ? tvName : nativeName;
        }

        public boolean spoken() { return !spokenCode.isEmpty(); }
    }

    /** TVmaze's bucket name for shows with no language listed. */
    public static final String NO_LANGUAGE = "(none listed)";

    // ---------------------------------------------------- native names
    // The language's name in its own script, for the languages the
    // index carries. Display only — never a lookup key. A language
    // missing here shows TVmaze's English name (the stated fallback).

    private static final Map<String, String> NATIVE = new HashMap<>();
    static {
        NATIVE.put("English", "English");
        NATIVE.put("Russian", "Русский");
        NATIVE.put("Japanese", "日本語");
        NATIVE.put("Chinese", "中文");
        NATIVE.put("Korean", "한국어");
        NATIVE.put("French", "Français");
        NATIVE.put("Spanish", "Español");
        NATIVE.put("Dutch", "Nederlands");
        NATIVE.put("Thai", "ไทย");
        NATIVE.put("Norwegian", "Norsk");
        NATIVE.put("German", "Deutsch");
        NATIVE.put("Swedish", "Svenska");
        NATIVE.put("Turkish", "Türkçe");
        NATIVE.put("Portuguese", "Português");
        NATIVE.put("Hindi", "हिन्दी");
        NATIVE.put("Italian", "Italiano");
        NATIVE.put("Ukrainian", "Українська");
        NATIVE.put("Polish", "Polski");
        NATIVE.put("Arabic", "العربية");
        NATIVE.put("Danish", "Dansk");
        NATIVE.put("Hungarian", "Magyar");
        NATIVE.put("Tagalog", "Tagalog");
        NATIVE.put("Finnish", "Suomi");
        NATIVE.put("Czech", "Čeština");
        NATIVE.put("Bengali", "বাংলা");
        NATIVE.put("Hebrew", "עברית");
        NATIVE.put("Greek", "Ελληνικά");
        NATIVE.put("Urdu", "اردو");
        NATIVE.put("Serbian", "Српски");
        NATIVE.put("Romanian", "Română");
        NATIVE.put("Vietnamese", "Tiếng Việt");
        NATIVE.put("Scottish Gaelic", "Gàidhlig");
        NATIVE.put("Armenian", "Հայերեն");
        NATIVE.put("Icelandic", "Íslenska");
        NATIVE.put("Tamil", "தமிழ்");
        NATIVE.put("Slovak", "Slovenčina");
        NATIVE.put("Catalan", "Català");
        NATIVE.put("Indonesian", "Bahasa Indonesia");
        NATIVE.put("Croatian", "Hrvatski");
        NATIVE.put("Marathi", "मराठी");
        NATIVE.put("Telugu", "తెలుగు");
        NATIVE.put("Malayalam", "മലയാളം");
        NATIVE.put("Bulgarian", "Български");
        NATIVE.put("Estonian", "Eesti");
        NATIVE.put("Afrikaans", "Afrikaans");
        NATIVE.put("Kazakh", "Қазақша");
        NATIVE.put("Persian", "فارسی");
        NATIVE.put("Malay", "Bahasa Melayu");
        NATIVE.put("Uzbek", "Oʻzbek");
        NATIVE.put("Latvian", "Latviešu");
        NATIVE.put("Slovenian", "Slovenščina");
        NATIVE.put("Lithuanian", "Lietuvių");
        NATIVE.put("Georgian", "ქართული");
        NATIVE.put("Javanese", "Basa Jawa");
        NATIVE.put("Kannada", "ಕನ್ನಡ");
        NATIVE.put("Azerbaijani", "Azərbaycan");
        NATIVE.put("Galician", "Galego");
        NATIVE.put("Panjabi", "ਪੰਜਾਬੀ");
        NATIVE.put("Basque", "Euskara");
        NATIVE.put("Belarusian", "Беларуская");
        NATIVE.put("Mongolian", "Монгол");
        NATIVE.put("Zulu", "isiZulu");
        NATIVE.put("Burmese", "မြန်မာ");
        NATIVE.put("Divehi", "ދިވެހި");
        NATIVE.put("Bosnian", "Bosanski");
        NATIVE.put("Kyrgyz", "Кыргызча");
        NATIVE.put("Malagasy", "Malagasy");
        NATIVE.put("Yoruba", "Èdè Yorùbá");
        NATIVE.put("Albanian", "Shqip");
        NATIVE.put("Pashto", "پښتو");
        NATIVE.put("Kongo", "Kikongo");
        NATIVE.put("Wolof", "Wolof");
        NATIVE.put("Latin", "Latina");
        NATIVE.put("Luxembourgish", "Lëtzebuergesch");
        NATIVE.put("Chechen", "Нохчийн");
        NATIVE.put("Sinhalese", "සිංහල");
        NATIVE.put("Gujarati", "ગુજરાતી");
        NATIVE.put("Swahili", "Kiswahili");
        NATIVE.put("Fijian", "Vosa Vakaviti");
        NATIVE.put("Lao", "ລາວ");
        NATIVE.put("Central Khmer", "ខ្មែរ");
        NATIVE.put("Welsh", "Cymraeg");
        NATIVE.put("Irish", "Gaeilge");
    }

    public static String nativeNameFor(String tvName) {
        String n = NATIVE.get(tvName);
        return n == null ? "" : n;
    }

    // -------------------------------------------------------- spoken
    // The languages Sandy SPEAKS are UiStrings' tables — the registry
    // bridges by TVmaze name so the two lists can never drift apart:
    // a spoken language that exists in UiStrings but not yet in the
    // catalogue data still gets an entry (indexCount 0 until the
    // walk touches it — Persian before v0.10.1 was exactly that case).

    /** tvName -> interface code, derived from UiStrings itself. */
    public static Map<String, String> spokenByTvName() {
        Map<String, String> m = new HashMap<>();
        for (String[] lang : UiStrings.LANGUAGES) {
            String facet = UiStrings.facetFor(lang[0]);
            if (!facet.isEmpty()) m.put(facet, lang[0]);
        }
        return m;
    }

    // ----------------------------------------------------- pure core

    /**
     * Parse the seed TSV (name TAB full-index count; '#' comments and
     * blank lines skipped; a malformed line is skipped, never fatal).
     */
    public static Map<String, Long> parseSeed(String tsv) {
        Map<String, Long> out = new HashMap<>();
        if (tsv == null) return out;
        for (String line : tsv.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int tab = t.indexOf('\t');
            if (tab <= 0) continue;
            try {
                out.put(t.substring(0, tab).trim(),
                        Long.parseLong(t.substring(tab + 1).trim()));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * The registry: the union of the seed measurement, the languages
     * the walk has actually gathered, and the spoken set. Ordered for
     * the picker: spoken languages first (in UiStrings picker order),
     * then by full-index size, then A-to-Z; the no-language bucket
     * rides last, always labeled, never hidden.
     */
    public static List<Entry> build(Map<String, Long> seedCounts,
                                    Map<String, Integer> gatheredCounts) {
        Map<String, String> spoken = spokenByTvName();
        Map<String, Entry> byName = new HashMap<>();
        if (seedCounts != null) {
            for (Map.Entry<String, Long> e : seedCounts.entrySet()) {
                byName.put(e.getKey(), new Entry(e.getKey(),
                        nativeNameFor(e.getKey()), e.getValue(), 0,
                        spoken.getOrDefault(e.getKey(), "")));
            }
        }
        if (gatheredCounts != null) {
            for (Map.Entry<String, Integer> e : gatheredCounts.entrySet()) {
                Entry old = byName.get(e.getKey());
                if (old == null) {
                    byName.put(e.getKey(), new Entry(e.getKey(),
                            nativeNameFor(e.getKey()), 0, e.getValue(),
                            spoken.getOrDefault(e.getKey(), "")));
                } else {
                    byName.put(e.getKey(), new Entry(old.tvName,
                            old.nativeName, old.indexCount, e.getValue(),
                            old.spokenCode));
                }
            }
        }
        for (Map.Entry<String, String> e : spoken.entrySet()) {
            if (!byName.containsKey(e.getKey())) {
                byName.put(e.getKey(), new Entry(e.getKey(),
                        nativeNameFor(e.getKey()), 0, 0, e.getValue()));
            }
        }
        List<Entry> out = new ArrayList<>(byName.values());
        final Map<String, Integer> pickerOrder = new HashMap<>();
        for (int i = 0; i < UiStrings.LANGUAGES.length; i++) {
            pickerOrder.put(UiStrings.LANGUAGES[i][0], i);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                boolean aNone = NO_LANGUAGE.equals(a.tvName);
                boolean bNone = NO_LANGUAGE.equals(b.tvName);
                if (aNone != bNone) return aNone ? 1 : -1;
                if (a.spoken() && b.spoken()) {
                    return Integer.compare(
                            pickerOrder.getOrDefault(a.spokenCode, 99),
                            pickerOrder.getOrDefault(b.spokenCode, 99));
                }
                if (a.spoken() != b.spoken()) return a.spoken() ? -1 : 1;
                if (a.indexCount != b.indexCount) {
                    return Long.compare(b.indexCount, a.indexCount);
                }
                return a.tvName.compareTo(b.tvName);
            }
        });
        return out;
    }

    /** Find one entry by TVmaze name; null when the registry lacks it. */
    public static Entry find(List<Entry> registry, String tvName) {
        if (registry == null || tvName == null) return null;
        for (Entry e : registry) if (e.tvName.equals(tvName)) return e;
        return null;
    }

    // --------------------------------------------------- Android glue

    /** Load the registry for this phone: seed asset + the walk's state. */
    public static List<Entry> load(Context ctx) {
        String tsv = "";
        try (InputStream in = ctx.getAssets().open("lang_registry.tsv");
             BufferedReader r = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            tsv = sb.toString();
        } catch (Exception ignored) { }
        CatalogIndex.State st = CatalogStore.loadState(ctx);
        Map<String, Integer> gathered = st == null
                ? Collections.<String, Integer>emptyMap() : st.counts;
        return build(parseSeed(tsv), gathered);
    }
}
