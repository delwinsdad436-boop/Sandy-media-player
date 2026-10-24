package com.talacore.talatv;

import android.content.Context;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The windowed poster rail (v0.17.0, THE SANCTUARY LAYOUT — FLUID
 * is an acceptance criterion, James's word).
 *
 * The old home rails built EVERY tile up front inside a plain
 * HorizontalScrollView — a rail of forty posters meant forty
 * inflated cards and forty image decodes before the room could
 * settle, and the proven-available shelf was uncapped. This rail
 * keeps only the tiles around the visible window alive: a fixed
 * pool of tile views is recycled through {@link Tiles#bindTile}
 * as the window slides, with lead/tail spacer views preserving
 * the full scroll width so the scrollbar, the fling physics and
 * focus travel all behave like one long rail.
 *
 * The contract that keeps it honest and smooth:
 *   - stridePx is the FULL layout width of one tile, margins
 *     included; total row width is always count × stride.
 *   - bindTile is TOTAL: it sets every property of the tile for
 *     its new index, so a recycled tile can never show a previous
 *     title's remains (the Lying Tile law at the view level).
 *   - The row is forced LTR so window math is identical in every
 *     language; the room around it still flips with the locale.
 *   - Image loading stays ImageCache's job (off the UI thread);
 *     this class never decodes anything.
 */
public final class PosterRail extends HorizontalScrollView {

    /** The tile supply a rail renders. Implemented by the room. */
    public interface Tiles {
        int count();
        /** Full layout width of one tile, margins included. */
        int stridePx();
        View createTile();
        /** Set EVERY property of tile for index. Called on the UI thread. */
        void bindTile(View tile, int index);
    }

    private final LinearLayout row;
    private final View lead;
    private final View tail;
    private final ArrayDeque<View> pool = new ArrayDeque<>();
    private final Map<Integer, View> live = new HashMap<>();
    private final Map<View, Integer> boundIndex = new HashMap<>();
    private Tiles tiles;
    private int winFirst = -1;
    private int winLast = -1;

    public PosterRail(Context ctx) {
        super(ctx);
        setHorizontalScrollBarEnabled(false);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(LAYOUT_DIRECTION_LTR);
        lead = new View(ctx);
        tail = new View(ctx);
        row.addView(lead, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT));
        row.addView(tail, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT));
        addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT));
    }

    /** (Re)point the rail at a tile supply; the window rebuilds. */
    public void setTiles(Tiles t) {
        tiles = t;
        for (View v : live.values()) {
            row.removeView(v);
            pool.push(v);
        }
        live.clear();
        boundIndex.clear();
        winFirst = -1;
        winLast = -1;
        updateWindow();
    }

    @Override
    protected void onScrollChanged(int x, int y, int oldx, int oldy) {
        super.onScrollChanged(x, y, oldx, oldy);
        updateWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        updateWindow();
    }

    private void updateWindow() {
        if (tiles == null) return;
        int n = tiles.count();
        int stride = Math.max(1, tiles.stridePx());
        if (n == 0) {
            setSpacer(lead, 0);
            setSpacer(tail, 0);
            return;
        }
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        if (vw <= 0) vw = stride * 3;
        int sx = Math.max(0, getScrollX());
        int first = clamp(sx / stride - 1, 0, n - 1);
        int last = clamp((sx + vw) / stride + 1, 0, n - 1);
        if (first == winFirst && last == winLast) return;
        winFirst = first;
        winLast = last;
        // Recycle the tiles that slid out of the window.
        Iterator<Map.Entry<Integer, View>> it = live.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, View> e = it.next();
            if (e.getKey() < first || e.getKey() > last) {
                row.removeView(e.getValue());
                pool.push(e.getValue());
                boundIndex.remove(e.getValue());
                it.remove();
            }
        }
        setSpacer(lead, first * stride);
        setSpacer(tail, (n - 1 - last) * stride);
        // Detach the survivors and re-lay the window in order
        // between the spacers: lead, tiles ascending, tail.
        for (View v : live.values()) row.removeView(v);
        int insertAt = 1;
        for (int i = first; i <= last; i++) {
            View v = live.get(i);
            if (v == null) {
                v = pool.isEmpty() ? tiles.createTile() : pool.pop();
                live.put(i, v);
            }
            Integer bound = boundIndex.get(v);
            if (bound == null || bound != i) {
                tiles.bindTile(v, i);
                boundIndex.put(v, i);
            }
            row.addView(v, insertAt++);
        }
    }

    private static void setSpacer(View v, int widthPx) {
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp.width != widthPx) {
            lp.width = widthPx;
            v.setLayoutParams(lp);
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Bring a tile into view and give it focus — the TV-style
     * travel the focus order promises. Scrolls smoothly, then
     * focuses once the window has slid to hold the tile.
     */
    public void focusTile(final int index) {
        if (tiles == null || index < 0 || index >= tiles.count()) return;
        int stride = Math.max(1, tiles.stridePx());
        int target = Math.max(0, index * stride - stride / 2);
        smoothScrollTo(target, 0);
        postDelayed(() -> {
            View v = live.get(index);
            if (v != null) v.requestFocus();
        }, 380);
    }

    /** Scroll just enough that index is fully inside the window. */
    public void bringIntoView(int index) {
        if (tiles == null || index < 0 || index >= tiles.count()) return;
        int stride = Math.max(1, tiles.stridePx());
        int left = index * stride;
        int right = left + stride;
        int sx = getScrollX();
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        if (left < sx) smoothScrollTo(Math.max(0, left - 8), 0);
        else if (right > sx + vw) smoothScrollTo(right - vw + 8, 0);
    }
}
