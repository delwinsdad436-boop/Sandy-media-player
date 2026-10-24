package com.talacore.talatv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The storyboard panels (v0.14.0) — a STORYBOARD, not a movie:
 * still, flat, drawn picture cards that advance as the telling
 * goes. Every panel the app can show is BUNDLED (a vector
 * drawable in the app's own res/), so the set is closed and
 * honest — nothing is rendered up out of thin air.
 *
 * Two kinds of cards:
 *   scene cards     — where we are: forest, sea, night, home…
 *                     picked from the passage's own words.
 *   character cards — who is here: a fox, a bear, a grandmother…
 *                     assigned to a cast member ONCE per book
 *                     (by name, deterministically) and never
 *                     changed inside that book — the visual twin
 *                     of voice continuity.
 *
 * Where a book's own edition carries illustrations and the
 * Archive exposes them, StoryTell shows those instead; the
 * cards are the floor that always exists. Pure logic.
 */
public final class StoryPanels {

    public static final class Panel {
        public final String id;       // drawable name story_<id>
        public final String label;    // the picture's name (English)
        public final boolean scene;
        public Panel(String id, String label, boolean scene) {
            this.id = id;
            this.label = label;
            this.scene = scene;
        }
    }

    private static final List<Panel> SCENES = new ArrayList<>();
    private static final List<Panel> CHARACTERS = new ArrayList<>();
    private static final Map<String, Panel> BY_ID = new LinkedHashMap<>();

    private static void scene(String id, String label) {
        Panel p = new Panel(id, label, true);
        SCENES.add(p);
        BY_ID.put(id, p);
    }

    private static void character(String id, String label) {
        Panel p = new Panel(id, label, false);
        CHARACTERS.add(p);
        BY_ID.put(id, p);
    }

    static {
        scene("meadow", "Meadow");
        scene("forest", "Forest");
        scene("sea", "The Sea");
        scene("night", "Night");
        scene("home", "Home");
        scene("garden", "Garden");
        scene("road", "The Road");
        scene("snow", "Snow");
        scene("mountain", "Mountains");
        scene("river", "River");
        scene("village", "Village");
        scene("castle", "Castle");
        scene("kitchen", "Kitchen");
        scene("boat", "Boat");

        character("fox", "Fox");
        character("bear", "Bear");
        character("rabbit", "Rabbit");
        character("owl", "Owl");
        character("wolf", "Wolf");
        character("cat", "Cat");
        character("dog", "Dog");
        character("mouse", "Mouse");
        character("lion", "Lion");
        character("tortoise", "Tortoise");
        character("girl", "Girl");
        character("boy", "Boy");
        character("grandmother", "Grandmother");
        character("grandfather", "Grandfather");
        character("mother", "Mother");
        character("father", "Father");
        character("witch", "Witch");
        character("king", "King");
        character("star", "Star");
    }

    /** The fallback scene when a passage names no place. */
    public static final String DEFAULT_SCENE = "meadow";
    /** The card for narration with no character on stage. */
    public static final String NARRATOR_CARD = "star";

    private StoryPanels() {}

    public static List<Panel> scenes() { return SCENES; }
    public static List<Panel> characters() { return CHARACTERS; }
    public static List<Panel> all() {
        List<Panel> out = new ArrayList<>(SCENES);
        out.addAll(CHARACTERS);
        return out;
    }
    public static Panel byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    // ---------------------------------------------------- scenes

    /** Scene words, in the house's best-covered tongues. The
     *  first scene (in this list's priority order) whose word
     *  the passage carries wins; no hit = the passage keeps
     *  whatever scene came before (the caller passes it in). */
    private static final String[][] SCENE_WORDS = {
            {"night", "night", "midnight", "moon", "stars", "dark",
                    "noche", "luna", "nacht", "mond",
                    "nuit", "lune", "ночь", "夜", "밤"},
            {"sea", "sea", "ocean", "wave", "shore", "beach",
                    "mar", "playa", "meer", "strand", "plage"},
            {"snow", "snow", "winter", "ice", "frost",
                    "nieve", "invierno", "schnee", "winter",
                    "neige", "hiver"},
            {"forest", "forest", "woods", "trees", "wald",
                    "bosque", "forêt", "лес"},
            {"river", "river", "stream", "brook", "río", "fluss",
                    "rivière"},
            {"mountain", "mountain", "hill", "montaña", "berg",
                    "montagne"},
            {"castle", "castle", "palace", "kingdom", "castillo",
                    "schloss", "château"},
            {"village", "village", "town", "market", "pueblo",
                    "dorf", "village"},
            {"garden", "garden", "flowers", "jardín", "garten",
                    "jardin"},
            {"kitchen", "kitchen", "hearth", "fire", "cocina",
                    "küche", "cuisine"},
            {"boat", "boat", "ship", "sail", "barco", "boot",
                    "bateau"},
            {"home", "home", "house", "cottage", "casa", "haus",
                    "maison"},
            {"road", "road", "path", "way", "camino", "weg",
                    "chemin"},
    };

    /** The scene a passage names, or "" when it names none. */
    public static String sceneFor(String text) {
        if (text == null) return "";
        String low = " " + text.toLowerCase(Locale.US) + " ";
        for (String[] row : SCENE_WORDS) {
            for (int i = 1; i < row.length; i++) {
                String w = row[i];
                // word-ish match: CJK words match as substrings
                // (no spaces in the script); Latin words need a
                // boundary so "sea" doesn't fire inside "searched".
                if (isCjk(w)) {
                    if (low.contains(w)) return row[0];
                } else if (low.contains(" " + w + " ")
                        || low.contains(" " + w + ",")
                        || low.contains(" " + w + ".")
                        || low.contains(" " + w + "!")
                        || low.contains(" " + w + "?")) {
                    return row[0];
                }
            }
        }
        return "";
    }

    private static boolean isCjk(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF || c >= 0x3040 && c <= 0x30FF
                    || c >= 0xAC00 && c <= 0xD7AF || c >= 0x0400 && c <= 0x04FF) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------ characters

    /**
     * The picture card that IS this character in this book. A
     * name that names a bundled animal/person keeps it (a fox is
     * a fox in every book); any other name takes a card by a
     * stable hash of book + name, so the assignment is the same
     * on every open — and once StoryTell stores it in the cast
     * file, it is pinned there besides.
     */
    public static String cardFor(String bookId, String name) {
        if (name == null || name.isEmpty()) return NARRATOR_CARD;
        String low = name.toLowerCase(Locale.US);
        for (Panel p : CHARACTERS) {
            if (low.contains(p.id)) return p.id;
        }
        if (low.contains("grandma") || low.contains("granny")
                || low.contains("abuela") || low.contains("großmutter")
                || low.contains("oma")) return "grandmother";
        if (low.contains("grandpa") || low.contains("abuelo")
                || low.contains("großvater") || low.contains("opa")) {
            return "grandfather";
        }
        if (low.contains("mother") || low.contains("mama")
                || low.contains("madre") || low.contains("mutter")
                || low.contains("maman")) return "mother";
        if (low.contains("father") || low.contains("papa")
                || low.contains("padre") || low.contains("vater")) {
            return "father";
        }
        if (low.contains("princess") || low.contains("queen")) return "girl";
        if (low.contains("prince")) return "boy";
        int h = (bookId + "|" + low).hashCode();
        // humans and creatures share one pool of faces; the hash
        // only picks WHICH, consistently.
        Panel p = CHARACTERS.get(Math.abs(h) % CHARACTERS.size());
        return p.id;
    }

    /** A full panel choice for one passage: scene + up to two
     *  character cards (the ones speaking in it). */
    public static final class Choice {
        public String sceneId = DEFAULT_SCENE;
        public final List<String> charIds = new ArrayList<>();
    }

    public static Choice choose(String prevScene, String passageText,
                                List<String> speakerCardIds) {
        Choice c = new Choice();
        String s = sceneFor(passageText);
        c.sceneId = !s.isEmpty() ? s
                : (prevScene == null || prevScene.isEmpty()
                        ? DEFAULT_SCENE : prevScene);
        if (speakerCardIds != null) {
            for (String id : speakerCardIds) {
                if (id != null && !id.isEmpty() && !c.charIds.contains(id)
                        && c.charIds.size() < 2) {
                    c.charIds.add(id);
                }
            }
        }
        return c;
    }

    /** Drawable resource name for a panel id. */
    public static String drawableName(String panelId) {
        return "story_" + panelId;
    }
}
