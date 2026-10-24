package com.talacore.talatv;

/**
 * The Display Honesty Law at the title card (v0.14.0, James,
 * 2026-10-19): A TILE IS A PROMISE. A card's availability binds
 * to a RESOLVED PLAYABLE SOURCE, never to catalogue presence
 * alone. The catalogue (TVmaze) knows a show EXISTS; Sandy's
 * shelves know whether she can PLAY it. A catalogue-only title
 * must not present a play promise: it renders visually
 * distinct — dimmed, plainly worded "Catalog only" BEFORE any
 * tap, no play affordance — and the tap is never where the
 * viewer learns the truth. Where resolution is still running,
 * the card shows its checking state and earns the play promise
 * only when a source resolves; if none resolves, it settles
 * into the catalog-only presentation.
 *
 * This class is the pure state every renderer consults, so
 * walls, lists, and search results cannot drift apart.
 */
public final class CardState {

    /** A playable source stands resolved: the full promise. */
    public static final int PLAYABLE = 0;
    /** Catalogue metadata only: no play promise, dimmed, labeled. */
    public static final int CATALOG_ONLY = 1;
    /** Resolution still running: no promise yet, checking shown. */
    public static final int CHECKING = 2;

    private CardState() {}

    /** The state a show card is in, from its shelf truth. */
    public static int forShow(boolean onShelves, boolean resolutionPending) {
        if (onShelves) return PLAYABLE;
        return resolutionPending ? CHECKING : CATALOG_ONLY;
    }

    /** Only a PLAYABLE card may present as playable. */
    public static boolean playPromise(int state) {
        return state == PLAYABLE;
    }

    /** The whole-card dim a non-playable card renders at. */
    public static float alphaFor(int state) {
        return state == PLAYABLE ? 1.0f : 0.5f;
    }

    /** The availability line's string key for the state. */
    public static String labelKey(int state) {
        switch (state) {
            case PLAYABLE: return "wall_available";
            case CHECKING: return "card_checking";
            default: return "wall_catalog_only";
        }
    }
}
