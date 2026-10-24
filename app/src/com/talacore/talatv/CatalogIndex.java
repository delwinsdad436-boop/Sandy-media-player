package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The on-demand index, by language (v0.10.0 — James's expansion order:
 * "more tiles with more availability… organized by language picked").
 *
 * Sandy doesn't download the catalogue's contents; she gathers the
 * INDEX — TVmaze's own show records, walked page by page — and files
 * every show under its own language, so a language wall is that
 * language's real depth, not the English slice wearing a flag. This
 * class is the pure half: records, bucketing, country shelves, sorting,
 * and the on-disk cache format (one JSON file per language + one state
 * file). Fetching and rendering live in the activities.
 *
 * Honesty rules baked in:
 *  - a show's language/country are TVmaze's own fields; a show with
 *    no language listed files under NO_LANGUAGE and is never dressed
 *    up as something it isn't;
 *  - sub-languages stand as their own shelves BY COUNTRY (Spanish
 *    from Spain and Spanish from Mexico are two shelves, not one);
 *  - the state says how many index pages have actually been walked —
 *    counts shown in the app are gathered counts, never index totals
 *    the phone hasn't seen.
 */
public final class CatalogIndex {

    /** Language name TVmaze uses when a show lists none. */
    public static final String NO_LANGUAGE = "";

    /** Shelf label for shows whose network/web channel states no country. */
    public static final String COUNTRY_WEB = "Worldwide (web)";

    private CatalogIndex() {}

    // ------------------------------------------------------------- record

    /** One gathered show, in the compact form the wall needs. */
    public static final class Record {
        public int id;
        public String name = "";
        public String language = "";
        public String countryName = "";
        public String countryCode = "";
        public String image = "";
        public String premiered = "";
        public String status = "";
        // v0.11.1: TVmaze's own show type. Records gathered before
        // 0.11.1 read "" here — "not listed", never guessed — until
        // the format-2 walk re-gathers their page.
        public String type = "";
        public double rating = -1;
        public List<String> genres = new ArrayList<>();

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("language", language);
            o.put("countryName", countryName);
            o.put("countryCode", countryCode);
            o.put("image", image);
            o.put("premiered", premiered);
            o.put("status", status);
            o.put("type", type);
            o.put("rating", rating);
            JSONArray g = new JSONArray();
            for (String genre : genres) g.put(genre);
            o.put("genres", g);
            return o;
        }

        public static Record fromJson(JSONObject o) {
            Record r = new Record();
            r.id = o.optInt("id");
            r.name = o.optString("name", "");
            r.language = o.optString("language", "");
            r.countryName = o.optString("countryName", "");
            r.countryCode = o.optString("countryCode", "");
            r.image = o.optString("image", "");
            r.premiered = o.optString("premiered", "");
            r.status = o.optString("status", "");
            r.type = o.optString("type", "");
            r.rating = o.optDouble("rating", -1);
            JSONArray g = o.optJSONArray("genres");
            if (g != null) {
                for (int i = 0; i < g.length(); i++) {
                    String s = g.optString(i, "");
                    if (!s.isEmpty()) r.genres.add(s);
                }
            }
            return r;
        }

        public TvMaze.Show toShow() {
            TvMaze.Show s = new TvMaze.Show();
            s.id = id;
            s.name = name;
            s.language = language;
            s.countryName = countryName;
            s.countryCode = countryCode;
            s.imageMedium = image;
            s.premiered = premiered;
            s.status = status;
            s.type = type;
            s.rating = rating;
            s.genres = new ArrayList<>(genres);
            return s;
        }
    }

    public static Record fromShow(TvMaze.Show s) {
        Record r = new Record();
        r.id = s.id;
        r.name = s.name;
        r.language = s.language;
        r.countryName = s.countryName;
        r.countryCode = s.countryCode;
        r.image = s.imageMedium;
        r.premiered = s.premiered;
        r.status = s.status;
        r.type = s.type;
        r.rating = s.rating;
        r.genres = new ArrayList<>(s.genres);
        return r;
    }

    // ------------------------------------------------------------- state

    /** How far the index walk has honestly gotten on this phone. */
    public static final class State {
        /** Bucket format this state was written under. Format 1
         *  (through 0.11.0) dropped each show's TYPE; format 2
         *  (0.11.1) carries it. A format-1 state with a finished
         *  walk earns one honest re-walk so the type lands. */
        public static final int FORMAT_TYPE = 2;
        public int format = 1;
        public int pagesWalked;          // next page to fetch = pagesWalked
        public boolean complete;         // a page answered 404/empty
        public long updatedAtMs;
        /** TVmaze language name -> gathered count. */
        public final Map<String, Integer> counts = new TreeMap<>();

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("format", format);
            o.put("pagesWalked", pagesWalked);
            o.put("complete", complete);
            o.put("updatedAtMs", updatedAtMs);
            JSONObject c = new JSONObject();
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                c.put(e.getKey(), e.getValue());
            }
            o.put("counts", c);
            return o;
        }

        public static State fromJson(String json) {
            State st = new State();
            if (json == null || json.isEmpty()) return st;
            try {
                JSONObject o = new JSONObject(json);
                st.format = o.optInt("format", 1);
                st.pagesWalked = o.optInt("pagesWalked", 0);
                st.complete = o.optBoolean("complete", false);
                st.updatedAtMs = o.optLong("updatedAtMs", 0);
                JSONObject c = o.optJSONObject("counts");
                if (c != null) {
                    JSONArray names = c.names();
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String k = names.optString(i, "");
                            st.counts.put(k, c.optInt(k, 0));
                        }
                    }
                }
            } catch (Exception ignored) { }
            return st;
        }

        public int totalGathered() {
            int n = 0;
            for (int v : counts.values()) n += v;
            return n;
        }
    }

    // ------------------------------------------------------------- files

    /** Cache file name for one language bucket ("" -> the no-language file). */
    public static String bucketFileName(String languageName) {
        String key = (languageName == null || languageName.isEmpty())
                ? "none" : languageName.toLowerCase(java.util.Locale.US)
                        .replaceAll("[^a-z0-9]+", "_");
        return "lang_" + key + ".json";
    }

    public static String bucketToJson(List<Record> bucket) throws Exception {
        JSONArray arr = new JSONArray();
        for (Record r : bucket) arr.put(r.toJson());
        return arr.toString();
    }

    public static List<Record> bucketFromJson(String json) {
        List<Record> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Record.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------- shaping

    /** The TVmaze language name a UI language code opens ("" = none). */
    public static String languageNameForCode(String code) {
        return UiStrings.facetFor(code);
    }

    /** Every show in the bucket for one TVmaze language name. */
    public static List<Record> bucketOf(List<Record> all, String languageName) {
        List<Record> out = new ArrayList<>();
        String want = languageName == null ? "" : languageName;
        for (Record r : all) {
            String lang = r.language == null ? "" : r.language;
            if (lang.equals(want)) out.add(r);
        }
        return out;
    }

    /** The shelf key a record sits on: its country, or the web shelf. */
    public static String shelfOf(Record r) {
        return (r.countryName == null || r.countryName.isEmpty())
                ? COUNTRY_WEB : r.countryName;
    }

    /**
     * Country shelves for one language bucket: shelf name -> its shows,
     * shelves ordered home country first (when given and present), the
     * web shelf last, the rest A→Z. Shows inside a shelf are wall-sorted.
     */
    public static LinkedHashMap<String, List<Record>> shelves(
            List<Record> bucket, String homeCountryName) {
        Map<String, List<Record>> byShelf = new LinkedHashMap<>();
        for (Record r : bucket) {
            byShelf.computeIfAbsent(shelfOf(r), k -> new ArrayList<>()).add(r);
        }
        List<String> names = sortShelves(byShelf.keySet(), homeCountryName);
        LinkedHashMap<String, List<Record>> out = new LinkedHashMap<>();
        for (String n : names) {
            List<Record> members = byShelf.get(n);
            sortShows(members);
            out.put(n, members);
        }
        return out;
    }

    public static List<String> sortShelves(Collection<String> shelves,
                                           String homeCountryName) {
        List<String> out = new ArrayList<>(shelves);
        Collections.sort(out, (a, b) -> {
            int ra = shelfRank(a, homeCountryName);
            int rb = shelfRank(b, homeCountryName);
            if (ra != rb) return ra - rb;
            return a.compareToIgnoreCase(b);
        });
        return out;
    }

    private static int shelfRank(String shelf, String homeCountryName) {
        if (homeCountryName != null && !homeCountryName.isEmpty()
                && shelf.equalsIgnoreCase(homeCountryName)) return 0;
        if (COUNTRY_WEB.equals(shelf)) return 2;
        return 1;
    }

    /** Wall order inside a shelf: best rated first, newest premieres
     *  break ties, then the title's own name. Unrated goes last. */
    public static void sortShows(List<Record> shows) {
        Collections.sort(shows, (a, b) -> {
            if (a.rating != b.rating) return Double.compare(b.rating, a.rating);
            int byDate = b.premiered.compareTo(a.premiered);
            if (byDate != 0) return byDate;
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    /** Merge freshly walked shows into the gathered set, by show id. */
    public static void mergeInto(Map<Integer, Record> gathered,
                                 List<TvMaze.Show> page) {
        for (TvMaze.Show s : page) {
            if (s.id > 0) gathered.put(s.id, fromShow(s));
        }
    }

    /** Recompute the honest per-language counts for the state file. */
    public static void recount(State st, Collection<Record> all) {
        st.counts.clear();
        for (Record r : all) {
            String lang = r.language == null ? "" : r.language;
            st.counts.merge(lang, 1, Integer::sum);
        }
    }

    // --------------------------------------- facets + sorts (v0.11.1)

    /**
     * Findability (James's field order from 0.11.0 in his hands): a
     * wall of two thousand titles nobody can cut is a wall that
     * fails its one job — FINDING. Facets cut any gathered pool by
     * genre / year / type / status; every facet's option list is
     * DERIVED from the pool itself, so a chip never promises a
     * category the pool doesn't hold, and a category the data does
     * hold is never hidden. Fields TVmaze left blank stay blank and
     * answer only to the "not listed" facet — never guessed into a
     * category.
     */
    public enum SortMode { BEST, AZ, ZA, NEWEST, OLDEST }

    /** A facet value meaning "the record lists none" (type/status/
     *  genre). Chosen so it can never collide with TVmaze data. */
    public static final String NOT_LISTED = "\u0001not-listed";

    /** One cut across a pool. Empty string = no cut on that axis. */
    public static final class FacetSet {
        public String genre = "";
        public String type = "";
        public String status = "";
        /** Inclusive premiere-year range; lo < 0 = no year cut. */
        public int yearLo = -1, yearHi = -1;
        /** The year cut means "premiered not listed". */
        public boolean yearNotListed = false;

        public boolean active() {
            return !genre.isEmpty() || !type.isEmpty() || !status.isEmpty()
                    || yearLo >= 0 || yearNotListed;
        }

        public boolean matches(Record r) {
            if (!genre.isEmpty()) {
                if (NOT_LISTED.equals(genre)) {
                    if (!r.genres.isEmpty()) return false;
                } else if (!r.genres.contains(genre)) return false;
            }
            if (!type.isEmpty()) {
                if (NOT_LISTED.equals(type)) {
                    if (!r.type.isEmpty()) return false;
                } else if (!type.equals(r.type)) return false;
            }
            if (!status.isEmpty()) {
                if (NOT_LISTED.equals(status)) {
                    if (!r.status.isEmpty()) return false;
                } else if (!status.equals(r.status)) return false;
            }
            if (yearNotListed) {
                if (yearOf(r) >= 0) return false;
            } else if (yearLo >= 0) {
                int y = yearOf(r);
                if (y < yearLo || y > yearHi) return false;
            }
            return true;
        }
    }

    /** The pool cut by the facets, in pool order. */
    public static List<Record> applyFacets(List<Record> pool, FacetSet f) {
        List<Record> out = new ArrayList<>();
        if (f == null || !f.active()) { out.addAll(pool); return out; }
        for (Record r : pool) if (f.matches(r)) out.add(r);
        return out;
    }

    /**
     * Value → count for one text axis over the pool, biggest first,
     * ties A→Z; a "not listed" entry (key NOT_LISTED) is present
     * only when the pool actually holds unlisted records.
     */
    public static LinkedHashMap<String, Integer> facetCounts(
            List<Record> pool, String axis) {
        Map<String, Integer> counts = new HashMap<>();
        int unlisted = 0;
        for (Record r : pool) {
            switch (axis) {
                case "genre":
                    if (r.genres.isEmpty()) unlisted++;
                    else for (String g : r.genres) {
                        counts.merge(g, 1, Integer::sum);
                    }
                    break;
                case "type":
                    if (r.type.isEmpty()) unlisted++;
                    else counts.merge(r.type, 1, Integer::sum);
                    break;
                case "status":
                    if (r.status.isEmpty()) unlisted++;
                    else counts.merge(r.status, 1, Integer::sum);
                    break;
                default: break;
            }
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        Collections.sort(es, (a, b) -> {
            int byCount = b.getValue() - a.getValue();
            return byCount != 0 ? byCount
                    : a.getKey().compareToIgnoreCase(b.getKey());
        });
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : es) out.put(e.getKey(), e.getValue());
        if (unlisted > 0) out.put(NOT_LISTED, unlisted);
        return out;
    }

    /** The premiere year a record states, or -1 when it states none
     *  (or states something that isn't a year). */
    public static int yearOf(Record r) {
        if (r == null || r.premiered == null || r.premiered.length() < 4) {
            return -1;
        }
        try {
            int y = Integer.parseInt(r.premiered.substring(0, 4));
            return (y >= 1800 && y <= 2200) ? y : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** The decade bucket a year sits in (1994 → 1990). */
    public static int decadeOf(int year) { return (year / 10) * 10; }

    /** Decade starts present in the pool, oldest first. */
    public static List<Integer> decadesIn(List<Record> pool) {
        java.util.TreeSet<Integer> ds = new java.util.TreeSet<>();
        for (Record r : pool) {
            int y = yearOf(r);
            if (y >= 0) ds.add(decadeOf(y));
        }
        return new ArrayList<>(ds);
    }

    /** Individual years present inside one decade, newest first. */
    public static List<Integer> yearsIn(List<Record> pool, int decade) {
        java.util.TreeSet<Integer> ys = new java.util.TreeSet<>();
        for (Record r : pool) {
            int y = yearOf(r);
            if (y >= 0 && decadeOf(y) == decade) ys.add(y);
        }
        List<Integer> out = new ArrayList<>(ys);
        out.sort(Collections.reverseOrder());
        return out;
    }

    /** True when the pool holds records with no premiere year. */
    public static boolean hasNoYear(List<Record> pool) {
        for (Record r : pool) if (yearOf(r) < 0) return true;
        return false;
    }

    /** Sort a list into the picked order. Unknown dates/ratings
     *  always sink to the end of their axis — unknown is never
     *  dressed as oldest, newest, or best. */
    public static void sortBy(List<Record> shows, SortMode mode) {
        if (mode == null || mode == SortMode.BEST) { sortShows(shows); return; }
        switch (mode) {
            case AZ:
                Collections.sort(shows, (a, b) -> {
                    int byName = a.name.compareToIgnoreCase(b.name);
                    return byName != 0 ? byName : Integer.compare(a.id, b.id);
                });
                break;
            case ZA:
                Collections.sort(shows, (a, b) -> {
                    int byName = b.name.compareToIgnoreCase(a.name);
                    return byName != 0 ? byName : Integer.compare(a.id, b.id);
                });
                break;
            case NEWEST:
                Collections.sort(shows, (a, b) -> {
                    int byDate = dateKey(b).compareTo(dateKey(a));
                    if (byDate != 0) return byDate;
                    if (a.rating != b.rating) return Double.compare(b.rating, a.rating);
                    return a.name.compareToIgnoreCase(b.name);
                });
                break;
            case OLDEST:
                Collections.sort(shows, (a, b) -> {
                    String da = dateKey(a), db = dateKey(b);
                    if (da.isEmpty() != db.isEmpty()) return da.isEmpty() ? 1 : -1;
                    int byDate = da.compareTo(db);
                    if (byDate != 0) return byDate;
                    if (a.rating != b.rating) return Double.compare(b.rating, a.rating);
                    return a.name.compareToIgnoreCase(b.name);
                });
                break;
            default: break;
        }
    }

    /** Sortable premiere date: "" when none is stated (sinks last
     *  in NEWEST by plain string order, explicitly last in OLDEST). */
    private static String dateKey(Record r) {
        return (r.premiered == null || yearOf(r) < 0) ? "" : r.premiered;
    }

    /** The A–Z jump letter of a title: its first letter, upper-cased;
     *  anything else (digits, symbols, empty) files under '#'. */
    public static char letterOf(String name) {
        if (name == null || name.isEmpty()) return '#';
        char c = Character.toUpperCase(name.charAt(0));
        return (c >= 'A' && c <= 'Z') ? c : '#';
    }

    /** First index in an A–Z-sorted list whose title files under
     *  the letter, or -1 when the list holds none. '#' reads as
     *  "before A" — index 0 when any '#' titles exist. */
    public static int firstIndexForLetter(List<Record> azSorted, char letter) {
        for (int i = 0; i < azSorted.size(); i++) {
            if (letterOf(azSorted.get(i).name) == letter) return i;
        }
        return -1;
    }

    /** The jump letters a list actually holds, '#' first, A–Z after. */
    public static List<Character> lettersIn(List<Record> shows) {
        boolean[] seen = new boolean[27];
        for (Record r : shows) {
            char c = letterOf(r.name);
            seen[c == '#' ? 0 : (c - 'A' + 1)] = true;
        }
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            if (seen[i]) out.add(i == 0 ? '#' : (char) ('A' + i - 1));
        }
        return out;
    }
}
