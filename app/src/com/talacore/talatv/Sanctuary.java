package com.talacore.talatv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.TextView;

/**
 * THE SANCTUARY LAYOUT (v0.17.0) — James's design order of
 * 2026-10-20, from the ten Gemini Notebook panels filed at
 * design-sanctuary/: "that layout, that's the kind of layout I
 * want Sandy to have" — the dark room, light in two colors (warm
 * gold and teal), smoked-glass panels with thin lit edges,
 * posters forward — with the big streamers' streamlining
 * (Crunchyroll / Netflix / Amazon finish, hero-led home, one
 * disciplined tile geometry per rail) and FLUID as an acceptance
 * criterion: rails glide, focus travel is smooth, nothing janks.
 * Corporate polish, never corporate behavior.
 *
 * This class is the single source of the look: every main surface
 * takes its grounds, glass, edges, and ink from these tokens, so
 * the room cannot drift back into per-screen improvisation. The
 * panels are STYLE reference only — nothing here invents content
 * or capability; the honesty fences stand in the build notes.
 *
 * The token values are plain compile-time constants on purpose:
 * the desktop harness reads them directly and pins them, so the
 * palette is verified the way the counts are.
 */
public final class Sanctuary {

    private Sanctuary() {}

    // ------------------------------------------------ the grounds
    /** The teal-charcoal wash at the top of the room. */
    public static final int GROUND_TOP = 0xFF0C1417;
    /** The room itself: near-black, warm side of neutral. */
    public static final int GROUND = 0xFF07090C;
    /** The floor of the room, bottom of every gradient. */
    public static final int GROUND_DEEP = 0xFF04060A;

    // --------------------------------------------------- the glass
    /** Smoked glass, lit end of the fill gradient. */
    public static final int PANEL_HI = 0xFF1B2530;
    /** Smoked glass. */
    public static final int PANEL = 0xFF121A21;
    /** The deepest glass — fields and wells. */
    public static final int PANEL_DEEP = 0xFF0B1015;
    /** The poster well before real art lands in it. */
    public static final int POSTER_VOID = 0xFF0E141A;
    /** Glass laid over moving pictures (player chrome). */
    public static final int OVERLAY = 0xB3121A21;

    // --------------------------------------------------- the light
    /** Warm gold — the room's warm light. */
    public static final int GOLD = 0xFFF0B84C;
    /** Headline gold, a breath paler than the light itself. */
    public static final int GOLD_TEXT = 0xFFF6CD7E;
    /** Gold at rest — catalogue marks, quiet accents. */
    public static final int GOLD_DIM = 0xFFC9A55C;
    /** The far end of a primary button's gold gradient. */
    public static final int GOLD_DEEP = 0xFFD9A441;
    /** Teal — the room's cool light. */
    public static final int TEAL = 0xFF55C9B8;
    /** The resting lit edge, unfocused (solid-stroke form). */
    public static final int EDGE = 0xFF7A5E2E;
    /** The teal end of a resting edge. */
    public static final int EDGE_TEAL = 0xFF2F6F66;
    /** Ink that sits ON gold (primary button labels). */
    public static final int ON_GOLD = 0xFF241A06;

    // ----------------------------------------------------- the ink
    /** Warm white — names, titles, what matters most. */
    public static final int INK = 0xFFF5F0E6;
    /** Body text — warm paper. */
    public static final int BODY = 0xFFDCD4C3;
    /** Quiet lines — status, freshness, secondary facts. */
    public static final int MUTED = 0xFFA79E8E;
    /** Sub-lines under names. */
    public static final int SUB = 0xFF8E887B;
    /** Hints and the faintest furniture. */
    public static final int FAINT = 0xFF6F6A5E;
    /** The one warm red — functional, never decoration. */
    public static final int WARN = 0xFFE08A7A;
    /** Hairline between rows. */
    public static final int DIVIDER = 0xFF232E36;

    // -------------------------------------------------- the rhythm
    /** Page padding on main surfaces. */
    public static final int PAGE_PAD_DP = 16;
    /**
     * Dormant TV token (fixpass-0200 restyle, design-diff §4):
     * the documented 5% overscan margin for a future TV surface.
     * Phone layouts do NOT use it.
     */
    public static final int PAGE_PAD_TV_DP = 48;
    /** Air above a section header. */
    public static final int SECTION_GAP_DP = 24;
    /** Rail card stride — ONE geometry for every poster rail. */
    public static final int RAIL_CARD_W_DP = 116;
    /** Rail poster width inside the card. */
    public static final int RAIL_IMG_W_DP = 108;
    /** Rail poster height inside the card (true 2:3 at 108 wide). */
    public static final int RAIL_IMG_H_DP = 162;
    /** Grid poster height (2:3 at the ~104dp a 3-column phone
     *  grid cell actually gets; grids share width by weight). */
    public static final int GRID_IMG_H_DP = 156;
    /** Glass panel corner. */
    public static final int PANEL_RADIUS_DP = 12;
    /** Poster tile corner (restyle: reads as art, not card). */
    public static final int TILE_RADIUS_DP = 8;
    /** Hero billboard height (restyle, design-diff §2). */
    public static final int HERO_H_DP = 288;
    /** Minimum height of a primary action (restyle, §10). */
    public static final int BTN_H_DP = 48;

    // ---------------------------------------------------- the type
    // One type scale for the main surfaces (restyle, §5). The
    // floor is a hard accessibility constraint for Sandy's older
    // viewers: no body/caption text on a main surface below
    // TEXT_FLOOR, and no restyle move ever reduces a size.
    /** Page titles (raised to this where they stood below it). */
    public static final int TEXT_PAGE_TITLE = 26;
    /** Section headers: white, bold, structural. */
    public static final int TEXT_SECTION = 20;
    /** Hero billboard title. */
    public static final int TEXT_HERO_TITLE = 30;
    /** Body / summary text. */
    public static final int TEXT_BODY = 15;
    /** Names under posters (kept for older viewers). */
    public static final int TEXT_TILE_NAME = 14;
    /** Meta / status / mark lines — also the floor. */
    public static final int TEXT_META = 14;
    /** The floor: nothing smaller on a main surface. */
    public static final int TEXT_FLOOR = 14;

    // --------------------------------------------------- the focus
    /** Focus pop scale — the documented TV genre scale (~1.1). */
    public static final float FOCUS_SCALE = 1.10f;
    /** Focus elevation, in dp (converted at the call site). */
    public static final int FOCUS_ELEVATION_DP = 8;
    /** Focus pop duration, ms. */
    public static final int FOCUS_ANIM_MS = 160;

    public static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // -------------------------------------------------- drawables

    /** The room's ground: teal wash at the top falling to black. */
    public static Drawable ground() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{GROUND_TOP, GROUND, GROUND_DEEP});
        g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return g;
    }

    /** A hairline rule lit gold at one end, teal at the other. */
    public static Drawable rule() {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{GOLD, TEAL});
    }

    public static Drawable divider() {
        return new ColorDrawable(DIVIDER);
    }

    /**
     * Smoked glass. RESTYLE (fixpass-0200, design-diff §1): at
     * rest the panel is UNFRAMED — flat glass fill, no lit edge;
     * the streamers reserve light for focus, and so does Sandy
     * now. Focused, pressed, or selected, the gold-to-teal edge
     * lights fully and throws a soft glow — the sanctuary's
     * focus language, unchanged, just no longer spent at rest.
     */
    public static Drawable glassPanel(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                new LitEdgeDrawable(d, PANEL_RADIUS_DP, true));
        s.addState(new int[]{android.R.attr.state_pressed},
                new LitEdgeDrawable(d, PANEL_RADIUS_DP, true));
        s.addState(new int[]{android.R.attr.state_selected},
                new LitEdgeDrawable(d, PANEL_RADIUS_DP, true));
        s.addState(new int[]{}, new LitEdgeDrawable(d, PANEL_RADIUS_DP,
                false, PANEL_HI, PANEL, false));
        return s;
    }

    /**
     * The poster frame: at rest, NO frame at all — the void fill
     * only, art sitting raw on the ground (design-diff §1, the
     * Disney+ rule: posters never sit in visible frames). The lit
     * edge appears only when the poster state is focused (its
     * card's state, via duplicateParentState at the call sites).
     */
    public static Drawable posterFrame(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                new LitEdgeDrawable(d, TILE_RADIUS_DP, true,
                        POSTER_VOID, PANEL_DEEP));
        s.addState(new int[]{},
                new LitEdgeDrawable(d, TILE_RADIUS_DP, false,
                        POSTER_VOID, PANEL_DEEP, false));
        return s;
    }

    /**
     * A text field's glass: deepest fill, resting edge KEPT —
     * a field must stay findable on the ground; it is furniture,
     * not art, so §1's unframing does not retire its quiet edge.
     */
    public static Drawable field(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return new LitEdgeDrawable(d, TILE_RADIUS_DP, false,
                PANEL_DEEP, PANEL_DEEP);
    }

    /**
     * The hero's bottom scrim (restyle, design-diff §2): art
     * melts into the ground — transparent through the top ~half,
     * the room's GROUND at the bottom, so title and meta text
     * sitting in it survive any frame. Replaces the flat OVERLAY
     * caption band on the hero only; OVERLAY keeps its player job.
     */
    public static Drawable heroScrim() {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x0007090C, 0x0007090C, GROUND});
        g.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return g;
    }

    /**
     * Buttons in the sanctuary register. A primary button is the
     * hero panel's silver-gold pill (dark ink on gold light); a
     * plain button is smoked glass with the lit edge. Both take
     * their focused/pressed state from the same edge language.
     */
    public static Drawable glassButton(Context ctx, boolean primary) {
        float d = ctx.getResources().getDisplayMetrics().density;
        StateListDrawable s = new StateListDrawable();
        if (primary) {
            s.addState(new int[]{android.R.attr.state_focused},
                    goldPill(d, true));
            s.addState(new int[]{android.R.attr.state_pressed},
                    goldPill(d, true));
            s.addState(new int[]{}, goldPill(d, false));
            return s;
        }
        s.addState(new int[]{android.R.attr.state_focused},
                new LitEdgeDrawable(d, PANEL_RADIUS_DP, true));
        s.addState(new int[]{android.R.attr.state_pressed},
                new LitEdgeDrawable(d, PANEL_RADIUS_DP, true));
        s.addState(new int[]{android.R.attr.state_selected},
                new LitEdgeDrawable(d, PANEL_RADIUS_DP, true));
        // Resting secondary button: flat glass, no edge (§1).
        s.addState(new int[]{}, new LitEdgeDrawable(d, PANEL_RADIUS_DP,
                false, PANEL_HI, PANEL, false));
        return s;
    }

    private static GradientDrawable goldPill(float d, boolean lit) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                lit ? new int[]{0xFFFBE3A1, GOLD}
                        : new int[]{0xFFF3D489, GOLD_DEEP});
        g.setCornerRadius(PANEL_RADIUS_DP * d);
        g.setStroke((int) Math.max(1, (lit ? 2 : 1) * d), EDGE);
        return g;
    }

    /** Style a button into the register (background + ink).
     *  A primary button also takes the genre's stature: a
     *  BTN_H_DP minimum height (restyle, design-diff §10). */
    public static void styleButton(Context ctx, Button b, boolean primary) {
        b.setBackground(glassButton(ctx, primary));
        b.setTextColor(primary ? ON_GOLD : INK);
        if (primary) b.setMinHeight(dp(ctx, BTN_H_DP));
    }

    /** A chip: glass, lit when it carries the standing pick;
     *  unselected chips rest unframed like every panel (§1). */
    public static void styleChip(Context ctx, Button b, boolean selected) {
        float d = ctx.getResources().getDisplayMetrics().density;
        b.setBackground(new LitEdgeDrawable(d, PANEL_RADIUS_DP, selected,
                PANEL_HI, PANEL, false));
        b.setTextColor(selected ? GOLD_TEXT : MUTED);
    }

    /** The wordmark treatment: headline gold with its own glow. */
    public static void goldText(TextView t) {
        t.setTextColor(GOLD_TEXT);
        t.setShadowLayer(14f, 0f, 0f, 0x55F0B84C);
    }

    /** Round a view's corners by clipping it to a rounded outline. */
    public static void roundClip(Context ctx, View v, int radiusDp) {
        final float r = radiusDp
                * ctx.getResources().getDisplayMetrics().density;
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    /**
     * The focus pop the streamers taught: a focused tile lifts a
     * breath toward the viewer. Pure presentation — callers wire
     * it as (or inside) the tile's focus listener.
     */
    public static void applyPop(View v, boolean focused) {
        float s = focused ? FOCUS_SCALE : 1.0f;
        v.animate().scaleX(s).scaleY(s).setDuration(FOCUS_ANIM_MS).start();
        v.setElevation(focused
                ? dp(v.getContext(), FOCUS_ELEVATION_DP) : 0f);
    }

    public static void focusPop(View v) {
        v.setOnFocusChangeListener((view, hasFocus) ->
                applyPop(view, hasFocus));
    }

    /**
     * A glass panel with a gradient-lit edge, drawn by hand: the
     * platform's GradientDrawable can stroke only one flat color,
     * and the panels' edges run gold into teal. Fill is a vertical
     * glass gradient; the edge is a horizontal gold-to-teal
     * gradient stroke; lit, the edge widens and a wider, fainter
     * pass under it reads as glow.
     */
    static final class LitEdgeDrawable extends Drawable {
        private final float density;
        private final float radiusDp;
        private final boolean lit;
        private final int fillTop;
        private final int fillBottom;
        /** Restyle (§1): when false and not lit, NO edge is drawn
         *  at rest — the fill runs to the bounds, unframed. */
        private final boolean framedAtRest;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int alpha = 255;

        LitEdgeDrawable(float density, float radiusDp, boolean lit) {
            this(density, radiusDp, lit, PANEL_HI, PANEL);
        }

        LitEdgeDrawable(float density, float radiusDp, boolean lit,
                        int fillTop, int fillBottom) {
            this(density, radiusDp, lit, fillTop, fillBottom, true);
        }

        LitEdgeDrawable(float density, float radiusDp, boolean lit,
                        int fillTop, int fillBottom, boolean framedAtRest) {
            this.density = density;
            this.radiusDp = radiusDp;
            this.lit = lit;
            this.fillTop = fillTop;
            this.fillBottom = fillBottom;
            this.framedAtRest = framedAtRest;
            edgePaint.setStyle(Paint.Style.STROKE);
            glowPaint.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            fillPaint.setShader(new LinearGradient(0, bounds.top, 0,
                    bounds.bottom, fillTop, fillBottom, Shader.TileMode.CLAMP));
            Shader edge = new LinearGradient(bounds.left, 0, bounds.right, 0,
                    GOLD, TEAL, Shader.TileMode.CLAMP);
            edgePaint.setShader(edge);
            glowPaint.setShader(edge);
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float r = radiusDp * density;
            boolean framed = lit || framedAtRest;
            float edgeW = (lit ? 2.2f : 1.1f) * density;
            float inset = framed ? edgeW / 2f : 0f;
            rect.set(b.left + inset, b.top + inset,
                    b.right - inset, b.bottom - inset);
            canvas.drawRoundRect(rect, r, r, fillPaint);
            if (!framed) return;   // unframed at rest: fill only
            if (lit) {
                glowPaint.setStrokeWidth(7f * density);
                glowPaint.setAlpha((int) (56 * alpha / 255f));
                canvas.drawRoundRect(rect, r, r, glowPaint);
            }
            edgePaint.setStrokeWidth(edgeW);
            edgePaint.setAlpha((int) ((lit ? 255 : 168) * alpha / 255f));
            canvas.drawRoundRect(rect, r, r, edgePaint);
        }

        @Override public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) {
            fillPaint.setColorFilter(cf);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
