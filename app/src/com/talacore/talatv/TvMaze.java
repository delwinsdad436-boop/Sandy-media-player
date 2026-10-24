package com.talacore.talatv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * TVmaze public API (https://api.tvmaze.com — free, no key): the catalog.
 * Parse functions take raw JSON text and are pure (JVM-testable); fetching
 * lives in the activities via Net. Nothing here invents metadata: fields
 * TVmaze leaves null stay empty.
 */
public final class TvMaze {

    private static final String BASE = "https://api.tvmaze.com";

    public static final class Show {
        public int id;
        public String name = "";
        public String language = "";
        public String status = "";
        // v0.11.1: TVmaze's own show type (Scripted, Reality,
        // Animation, Documentary, News, Sports, Panel Show, Talk
        // Show, Game Show, Variety, Award Show…). Empty when TVmaze
        // lists none — the catalogue facet reads it as "not listed".
        public String type = "";
        public String premiered = "";
        public String summaryText = "";
        public String imageMedium = "";
        public String imageOriginal = "";
        public double rating = -1;   // -1 = TVmaze has no rating for it
        public List<String> genres = new ArrayList<>();
        // v0.10.0: where the show comes from — network.country first,
        // webChannel.country when there is no broadcast network. Empty
        // when TVmaze lists neither (a web show with no country stated).
        public String countryName = "";
        public String countryCode = "";
    }

    /** One person (GET /search/people): the actor-search face. */
    public static final class Person {
        public int id;
        public String name = "";
        public String imageMedium = "";
        public String countryName = "";
    }

    /** One alternate name TVmaze knows for a show (GET /shows/{id}/akas). */
    public static final class Aka {
        public String name = "";
        public String countryName = "";
        public String countryCode = "";
    }

    public static final class Episode {
        public int id;
        public int season;
        public int number;
        public String name = "";
        public String airdate = "";
        public int runtime;          // minutes, 0 = unknown
        public String summaryText = "";
    }

    private TvMaze() {}

    // ------------------------------------------------------------- URLs

    public static String showUrl(int id) { return BASE + "/shows/" + id; }

    public static String akasUrl(int id) { return BASE + "/shows/" + id + "/akas"; }

    public static String episodesUrl(int id) { return BASE + "/shows/" + id + "/episodes"; }

    public static String showsPageUrl(int page) { return BASE + "/shows?page=" + page; }

    public static String scheduleUrl(String country, String dateIso) {
        return BASE + "/schedule?country=" + country + "&date=" + dateIso;
    }

    public static String searchUrl(String query) {
        try {
            return BASE + "/search/shows?q=" + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return BASE + "/search/shows?q=" + query;
        }
    }

    /** /search/people — actor search rides TVmaze live at query
     *  time (v0.11.1); cast lists are never baked for 90k shows. */
    public static String peopleSearchUrl(String query) {
        try {
            return BASE + "/search/people?q=" + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return BASE + "/search/people?q=" + query;
        }
    }

    /** /people/{id}/castcredits with the shows embedded. */
    public static String castCreditsUrl(int personId) {
        return BASE + "/people/" + personId + "/castcredits?embed=show";
    }

    // ------------------------------------------------------------- parse

    public static Show parseShow(String json) throws Exception {
        return showFrom(new JSONObject(json));
    }

    /** /shows?page=N — a bare array of show objects. */
    public static List<Show> parseShows(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Show> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            out.add(showFrom(arr.getJSONObject(i)));
        }
        return out;
    }

    /** /search/shows — an array of {score, show}. */
    public static List<Show> parseSearch(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Show> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject hit = arr.getJSONObject(i);
            if (hit.has("show")) out.add(showFrom(hit.getJSONObject("show")));
        }
        return out;
    }

    /** /schedule — episode objects carrying their show (top-level "show",
     *  older responses used _embedded.show; both are accepted). */
    public static List<Show> parseScheduleShows(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Show> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject ep = arr.getJSONObject(i);
            JSONObject showObj = ep.optJSONObject("show");
            if (showObj == null) {
                JSONObject embedded = ep.optJSONObject("_embedded");
                if (embedded != null) showObj = embedded.optJSONObject("show");
            }
            if (showObj == null) continue;
            Show s = showFrom(showObj);
            if (seen.add(s.id)) out.add(s);
        }
        return out;
    }

    public static List<Episode> parseEpisodes(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Episode> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Episode e = new Episode();
            e.id = o.optInt("id");
            e.season = o.optInt("season");
            e.number = o.optInt("number");
            e.name = o.optString("name", "");
            e.airdate = o.optString("airdate", "");
            e.runtime = o.optInt("runtime", 0);
            e.summaryText = stripHtml(o.optString("summary", ""));
            out.add(e);
        }
        return out;
    }

    private static Show showFrom(JSONObject o) {
        Show s = new Show();
        s.id = o.optInt("id");
        s.name = o.optString("name", "");
        s.language = o.optString("language", "");
        s.status = o.optString("status", "");
        s.type = o.optString("type", "");
        s.premiered = o.optString("premiered", "");
        s.summaryText = stripHtml(o.optString("summary", ""));
        JSONObject rating = o.optJSONObject("rating");
        if (rating != null && !rating.isNull("average")) {
            s.rating = rating.optDouble("average", -1);
        }
        JSONObject image = o.optJSONObject("image");
        if (image != null) {
            s.imageMedium = image.optString("medium", "");
            s.imageOriginal = image.optString("original", "");
        }
        JSONArray genres = o.optJSONArray("genres");
        if (genres != null) {
            for (int i = 0; i < genres.length(); i++) {
                String g = genres.optString(i, "");
                if (!g.isEmpty()) s.genres.add(g);
            }
        }
        JSONObject[] holders = {o.optJSONObject("network"), o.optJSONObject("webChannel")};
        for (JSONObject h : holders) {
            if (h == null) continue;
            JSONObject c = h.optJSONObject("country");
            if (c != null) {
                s.countryName = c.optString("name", "");
                s.countryCode = c.optString("code", "");
                if (!s.countryName.isEmpty()) break;
            }
        }
        return s;
    }

    /** /shows/{id}/akas — [{name, country:{name, code} | null}]. */
    public static List<Aka> parseAkas(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Aka> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Aka a = new Aka();
            a.name = o.optString("name", "");
            JSONObject c = o.optJSONObject("country");
            if (c != null) {
                a.countryName = c.optString("name", "");
                a.countryCode = c.optString("code", "");
            }
            if (!a.name.isEmpty()) out.add(a);
        }
        return out;
    }

    /** /search/people — an array of {score, person}. */
    public static List<Person> parsePeople(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject hit = arr.getJSONObject(i);
            JSONObject p = hit.optJSONObject("person");
            if (p == null) continue;
            Person person = new Person();
            person.id = p.optInt("id");
            person.name = p.optString("name", "");
            JSONObject image = p.optJSONObject("image");
            if (image != null) person.imageMedium = image.optString("medium", "");
            JSONObject c = p.optJSONObject("country");
            if (c != null) person.countryName = c.optString("name", "");
            if (person.id > 0 && !person.name.isEmpty()) out.add(person);
        }
        return out;
    }

    /** /people/{id}/castcredits?embed=show — credit rows carrying
     *  their show under _embedded.show. Deduped by show id (a person
     *  can hold several roles in one show), TVmaze's own order kept. */
    public static List<Show> parseCastCredits(String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        List<Show> out = new ArrayList<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject credit = arr.getJSONObject(i);
            JSONObject embedded = credit.optJSONObject("_embedded");
            if (embedded == null) continue;
            JSONObject showObj = embedded.optJSONObject("show");
            if (showObj == null) continue;
            Show s = showFrom(showObj);
            if (s.id > 0 && seen.add(s.id)) out.add(s);
        }
        return out;
    }

    /** TVmaze summaries are HTML fragments; the app shows plain text. */
    public static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        String noTags = html.replaceAll("<[^>]*>", " ");
        return noTags
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
