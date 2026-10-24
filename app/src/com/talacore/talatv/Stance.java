package com.talacore.talatv;

import org.json.JSONObject;

/**
 * The Live TV stance (v0.6.0, field note #2d: "BACK LOSES YOUR
 * PLACE"). The complaint was never only the scroll: coming back from
 * a channel, the viewer had to reselect the LANGUAGE though he'd
 * just selected the genre — the whole stance was thrown away.
 *
 * A stance is the viewer's whole place in Live TV, kept as one
 * record: the three facets, the search text, the working-only
 * toggle, and where in the list they were standing (row + offset).
 * Live TV saves it whenever the viewer leaves for the player and
 * restores it on return — navigation gets the same position-hook
 * law as playback (item 8), applied to the list itself.
 *
 * Pure logic; the harness round-trips it.
 */
public final class Stance {

    public String genre = "";
    public String language = "";
    public String country = "";
    public String query = "";
    public boolean workingOnly = false;
    public int firstVisible = 0;
    public int topPx = 0;

    public Stance() {}

    public String toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("genre", genre);
        o.put("language", language);
        o.put("country", country);
        o.put("query", query);
        o.put("workingOnly", workingOnly);
        o.put("firstVisible", firstVisible);
        o.put("topPx", topPx);
        return o.toString();
    }

    public static Stance fromJson(String text) {
        Stance s = new Stance();
        if (text == null || text.isEmpty()) return s;
        try {
            JSONObject o = new JSONObject(text);
            s.genre = o.optString("genre", "");
            s.language = o.optString("language", "");
            s.country = o.optString("country", "");
            s.query = o.optString("query", "");
            s.workingOnly = o.optBoolean("workingOnly", false);
            s.firstVisible = o.optInt("firstVisible", 0);
            s.topPx = o.optInt("topPx", 0);
        } catch (Exception ignored) {
            // a corrupt stance is no stance — the screen opens fresh
        }
        return s;
    }
}
