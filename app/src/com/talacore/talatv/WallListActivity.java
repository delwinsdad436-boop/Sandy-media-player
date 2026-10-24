package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One shelf, whole (v0.10.0): every gathered show for a
 * language+country shelf — or for one country across all languages —
 * as a single scrollable list. Rows load in pages as the scroll
 * reaches for them, so nothing is ever truncated behind a first page:
 * keep scrolling and the shelf keeps coming, to its real end. Every
 * row carries the same honest mark as the wall tiles.
 *
 * v0.11.1 — findability (James's field order): a list of two
 * thousand is not a finding tool until it can be CUT and JUMPED.
 * This list carries the same facet bar as the wall (genre / year /
 * type / status, options derived from this list's own pool), the
 * sort orders, and an A–Z jump strip: tap a letter and the list
 * lands on its first title under it (switching the sort to A–Z
 * first when it isn't). A "facet" mode arrives from the wall with
 * the wall's standing cut in its extras; cuts changed in here are
 * for this visit — the wall keeps its own stance.
 */
public class WallListActivity extends Activity {

    private static final int PAGE = 100;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<CatalogIndex.Record> pool = new ArrayList<>();
    private final List<CatalogIndex.Record> all = new ArrayList<>();
    private int shown = 0;
    private Availability.Shelf shelf;
    private String mode = "";

    private final CatalogIndex.FacetSet facets = new CatalogIndex.FacetSet();
    private CatalogIndex.SortMode sortMode = CatalogIndex.SortMode.BEST;

    private TextView titleView, statusView;
    private ListView listView;
    private ProgressBar progress;
    private LinearLayout facetChipRow, jumpRow;
    private HorizontalScrollView jumpScroll;
    private WallAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra("mode");
        String language = getIntent().getStringExtra("language");
        String country = getIntent().getStringExtra("country");

        // the wall's standing cut, when the door carried one
        facets.genre = extraStr("f_genre");
        facets.type = extraStr("f_type");
        facets.status = extraStr("f_status");
        facets.yearLo = getIntent().getIntExtra("f_year_lo", -1);
        facets.yearHi = getIntent().getIntExtra("f_year_hi", -1);
        facets.yearNotListed = getIntent().getBooleanExtra("f_year_nl", false);
        int sortOrd = getIntent().getIntExtra("f_sort", 0);
        CatalogIndex.SortMode[] modes = CatalogIndex.SortMode.values();
        if (sortOrd >= 0 && sortOrd < modes.length) sortMode = modes[sortOrd];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        root.setPadding(dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP));

        titleView = new TextView(this);
        titleView.setTextSize(19);
        Sanctuary.goldText(titleView);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        root.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setTextSize(12);
        root.addView(statusView);

        HorizontalScrollView facetScroll = new HorizontalScrollView(this);
        facetScroll.setHorizontalScrollBarEnabled(false);
        facetChipRow = new LinearLayout(this);
        facetChipRow.setOrientation(LinearLayout.HORIZONTAL);
        facetScroll.addView(facetChipRow);
        root.addView(facetScroll);

        jumpScroll = new HorizontalScrollView(this);
        jumpScroll.setHorizontalScrollBarEnabled(false);
        jumpRow = new LinearLayout(this);
        jumpRow.setOrientation(LinearLayout.HORIZONTAL);
        jumpScroll.addView(jumpRow);
        root.addView(jumpScroll);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        progress.setIndeterminateTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        listView = new ListView(this);
        listView.setBackgroundColor(Sanctuary.GROUND);
        listView.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        listView.setDividerHeight(1);
        adapter = new WallAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listView.setOnItemClickListener((p, v, pos, id) -> openShow(all.get(pos)));
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 0 && first + visible >= total - 12) grow();
            }
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
        });
        setContentView(root);

        if ("country".equals(mode)) {
            titleView.setText(country == null ? "" : country);
        } else if ("facet".equals(mode)) {
            String lang = language == null ? "" : language;
            String ctry = country == null ? "" : country;
            String t = !ctry.isEmpty() ? ctry : (!lang.isEmpty() ? lang : "Catalog");
            titleView.setText(t + (lang.isEmpty() || ctry.isEmpty()
                    ? "" : " · " + lang));
        } else {
            titleView.setText((country == null ? "" : country)
                    + (language == null || language.isEmpty() ? "" : " · " + language));
        }
        statusView.setText("Loading the shelf…");

        exec.execute(() -> {
            Map<Integer, CatalogIndex.Record> gathered =
                    CatalogStore.loadGathered(this);
            shelf = CatalogStore.shelf(this);
            List<CatalogIndex.Record> picked = new ArrayList<>();
            for (CatalogIndex.Record r : gathered.values()) {
                if ("country".equals(mode)) {
                    if (CatalogIndex.shelfOf(r).equals(country)) picked.add(r);
                } else if ("facet".equals(mode)) {
                    boolean langOk = language == null || language.isEmpty()
                            || r.language.equals(language);
                    boolean ctryOk = country == null || country.isEmpty()
                            || CatalogIndex.shelfOf(r).equals(country);
                    if (langOk && ctryOk) picked.add(r);
                } else {
                    if (r.language.equals(language)
                            && CatalogIndex.shelfOf(r).equals(country)) {
                        picked.add(r);
                    }
                }
            }
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                pool.clear();
                pool.addAll(picked);
                rebuild();
            });
        });
    }

    private String extraStr(String key) {
        String v = getIntent().getStringExtra(key);
        return v == null ? "" : v;
    }

    /** Pool → facet cut → sort → the standing list. With no cut
     *  and the default sort, each mode keeps the exact order it
     *  shipped with (country: language A→Z, wall order inside). */
    private void rebuild() {
        List<CatalogIndex.Record> cut = CatalogIndex.applyFacets(pool, facets);
        if (!facets.active() && sortMode == CatalogIndex.SortMode.BEST
                && "country".equals(mode)) {
            cut.sort((a, b) -> {
                int byLang = a.language.compareToIgnoreCase(b.language);
                if (byLang != 0) return byLang;
                if (a.rating != b.rating) return Double.compare(b.rating, a.rating);
                return a.name.compareToIgnoreCase(b.name);
            });
        } else {
            CatalogIndex.sortBy(cut, sortMode);
        }
        all.clear();
        all.addAll(cut);
        shown = Math.min(PAGE, all.size());
        adapter.notifyDataSetChanged();
        listView.setSelection(0);
        buildFacetBar();
        buildJumpBar();
        if (pool.isEmpty()) {
            statusView.setText("0 shows gathered — the index walk "
                    + "hasn't reached this shelf yet.");
        } else if (facets.active() || sortMode != CatalogIndex.SortMode.BEST) {
            statusView.setText(all.size() + " of " + pool.size() + " shows"
                    + facetSummarySuffix()
                    + " — scroll; the list loads as you go, to its end.");
        } else {
            statusView.setText(all.size() + " shows gathered"
                    + " — scroll; the shelf loads as you go, to its end.");
        }
    }

    private String facetSummarySuffix() {
        StringBuilder sb = new StringBuilder();
        List<String> parts = new ArrayList<>();
        if (!facets.genre.isEmpty()) {
            parts.add(LangStore.tr(this, "facet_genre") + ": " + axisLabel(facets.genre));
        }
        if (facets.yearLo >= 0 || facets.yearNotListed) {
            parts.add(LangStore.tr(this, "facet_year") + ": " + yearFacetLabel());
        }
        if (!facets.type.isEmpty()) {
            parts.add(LangStore.tr(this, "facet_type") + ": " + axisLabel(facets.type));
        }
        if (!facets.status.isEmpty()) {
            parts.add(LangStore.tr(this, "facet_status") + ": " + axisLabel(facets.status));
        }
        if (!parts.isEmpty()) sb.append(" — ").append(String.join(" · ", parts));
        if (sortMode != CatalogIndex.SortMode.BEST) {
            sb.append(" · ").append(LangStore.tr(this, "facet_sort"))
                    .append(": ").append(sortLabel());
        }
        return sb.toString();
    }

    private void grow() {
        if (shown >= all.size()) return;
        shown = Math.min(shown + PAGE, all.size());
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------- facet bar

    private String axisLabel(String v) {
        if (v == null || v.isEmpty()) return LangStore.tr(this, "facet_all");
        if (CatalogIndex.NOT_LISTED.equals(v)) {
            return LangStore.tr(this, "facet_not_listed");
        }
        return v;
    }

    private String yearFacetLabel() {
        if (facets.yearNotListed) return LangStore.tr(this, "facet_not_listed");
        if (facets.yearLo < 0) return LangStore.tr(this, "facet_all");
        if (facets.yearLo == facets.yearHi) return String.valueOf(facets.yearLo);
        if (facets.yearHi - facets.yearLo == 9 && facets.yearLo % 10 == 0) {
            return facets.yearLo + "s";
        }
        return facets.yearLo + "–" + facets.yearHi;
    }

    private String sortLabel() {
        switch (sortMode) {
            case AZ: return LangStore.tr(this, "sort_az");
            case ZA: return LangStore.tr(this, "sort_za");
            case NEWEST: return LangStore.tr(this, "sort_newest");
            case OLDEST: return LangStore.tr(this, "sort_oldest");
            default: return LangStore.tr(this, "sort_best");
        }
    }

    private void buildFacetBar() {
        facetChipRow.removeAllViews();
        if (pool.isEmpty()) return;
        addFacetButton(LangStore.tr(this, "facet_genre") + ": " + axisLabel(facets.genre),
                v -> pickTextFacet("genre"));
        addFacetButton(LangStore.tr(this, "facet_year") + ": " + yearFacetLabel(),
                v -> pickYearFacet());
        addFacetButton(LangStore.tr(this, "facet_type") + ": " + axisLabel(facets.type),
                v -> pickTextFacet("type"));
        addFacetButton(LangStore.tr(this, "facet_status") + ": " + axisLabel(facets.status),
                v -> pickTextFacet("status"));
        addFacetButton(LangStore.tr(this, "facet_sort") + ": " + sortLabel(),
                v -> pickSort());
        if (facets.active()) {
            addFacetButton(LangStore.tr(this, "facet_clear"), v -> {
                facets.genre = "";
                facets.type = "";
                facets.status = "";
                facets.yearLo = -1;
                facets.yearHi = -1;
                facets.yearNotListed = false;
                rebuild();
            });
        }
    }

    private void addFacetButton(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        Sanctuary.styleChip(WallListActivity.this, b, false);
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        facetChipRow.addView(b);
    }

    private void pickTextFacet(String axis) {
        LinkedHashMap<String, Integer> counts = CatalogIndex.facetCounts(pool, axis);
        List<String> values = new ArrayList<>(counts.keySet());
        String current = "genre".equals(axis) ? facets.genre
                : "type".equals(axis) ? facets.type : facets.status;
        String[] items = new String[values.size() + 1];
        items[0] = LangStore.tr(this, "facet_all") + " · " + pool.size();
        for (int i = 0; i < values.size(); i++) {
            items[i + 1] = axisLabel(values.get(i)) + " · " + counts.get(values.get(i));
        }
        int checked = current.isEmpty() ? 0 : values.indexOf(current) + 1;
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "facet_" + axis))
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    String v = which == 0 ? "" : values.get(which - 1);
                    if ("genre".equals(axis)) facets.genre = v;
                    else if ("type".equals(axis)) facets.type = v;
                    else facets.status = v;
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void pickYearFacet() {
        List<Integer> decades = CatalogIndex.decadesIn(pool);
        boolean noYear = CatalogIndex.hasNoYear(pool);
        List<String> labels = new ArrayList<>();
        labels.add(LangStore.tr(this, "facet_all") + " · " + pool.size());
        for (int d : decades) labels.add(d + "s · " + decadeCount(d));
        if (noYear) {
            int n = 0;
            for (CatalogIndex.Record r : pool) if (CatalogIndex.yearOf(r) < 0) n++;
            labels.add(LangStore.tr(this, "facet_not_listed") + " · " + n);
        }
        int checked = 0;
        if (facets.yearNotListed && noYear) checked = labels.size() - 1;
        else if (facets.yearLo >= 0) {
            int idx = decades.indexOf(CatalogIndex.decadeOf(facets.yearLo));
            if (idx >= 0) checked = idx + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "facet_year"))
                .setSingleChoiceItems(labels.toArray(new String[0]), checked,
                        (d, which) -> {
                            d.dismiss();
                            if (which == 0) {
                                facets.yearLo = -1;
                                facets.yearHi = -1;
                                facets.yearNotListed = false;
                                rebuild();
                            } else if (noYear && which == labels.size() - 1) {
                                facets.yearLo = -1;
                                facets.yearHi = -1;
                                facets.yearNotListed = true;
                                rebuild();
                            } else {
                                pickYearInDecade(decades.get(which - 1));
                            }
                        })
                .show();
    }

    private int decadeCount(int decade) {
        int n = 0;
        for (CatalogIndex.Record r : pool) {
            int y = CatalogIndex.yearOf(r);
            if (y >= 0 && CatalogIndex.decadeOf(y) == decade) n++;
        }
        return n;
    }

    private void pickYearInDecade(int decade) {
        List<Integer> years = CatalogIndex.yearsIn(pool, decade);
        if (years.size() <= 1) {
            facets.yearLo = decade;
            facets.yearHi = decade + 9;
            facets.yearNotListed = false;
            rebuild();
            return;
        }
        String[] items = new String[years.size() + 1];
        items[0] = decade + "s — " + LangStore.tr(this, "facet_all");
        for (int i = 0; i < years.size(); i++) items[i + 1] = String.valueOf(years.get(i));
        int checked = 0;
        if (!facets.yearNotListed && facets.yearLo == facets.yearHi
                && years.contains(facets.yearLo)) {
            checked = years.indexOf(facets.yearLo) + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(String.valueOf(decade) + "s")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    if (which == 0) {
                        facets.yearLo = decade;
                        facets.yearHi = decade + 9;
                    } else {
                        facets.yearLo = years.get(which - 1);
                        facets.yearHi = facets.yearLo;
                    }
                    facets.yearNotListed = false;
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void pickSort() {
        CatalogIndex.SortMode[] modes = CatalogIndex.SortMode.values();
        String[] items = {
                LangStore.tr(this, "sort_best"), LangStore.tr(this, "sort_az"),
                LangStore.tr(this, "sort_za"), LangStore.tr(this, "sort_newest"),
                LangStore.tr(this, "sort_oldest")};
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "facet_sort"))
                .setSingleChoiceItems(items, sortMode.ordinal(), (d, which) -> {
                    sortMode = modes[which];
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    // ---------------------------------------------------- jump bar

    /** The A–Z strip: only the letters this list actually holds.
     *  A tap makes the list A–Z (when it isn't) and lands on the
     *  letter's first title, growing the paged rows to reach it. */
    private void buildJumpBar() {
        jumpRow.removeAllViews();
        List<Character> letters = CatalogIndex.lettersIn(all);
        jumpScroll.setVisibility(all.size() > PAGE / 2 && letters.size() > 1
                ? View.VISIBLE : View.GONE);
        if (jumpScroll.getVisibility() != View.VISIBLE) return;
        for (char c : letters) {
            Button b = new Button(this);
            b.setText(String.valueOf(c));
            Sanctuary.styleChip(WallListActivity.this, b, false);
            b.setOnClickListener(v -> jumpTo(c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(2);
            b.setLayoutParams(lp);
            jumpRow.addView(b);
        }
    }

    private void jumpTo(char letter) {
        if (sortMode != CatalogIndex.SortMode.AZ) {
            sortMode = CatalogIndex.SortMode.AZ;
            rebuild();
        }
        int idx = CatalogIndex.firstIndexForLetter(all, letter);
        if (idx < 0) return;
        if (shown < idx + 1) {
            shown = Math.min(idx + PAGE, all.size());
            adapter.notifyDataSetChanged();
        }
        listView.setSelection(idx);
    }

    private void openShow(CatalogIndex.Record r) {
        Intent i = new Intent(this, ShowActivity.class);
        i.putExtra("showId", r.id);
        i.putExtra("showName", r.name);
        i.putExtra("showImage", r.image);
        startActivity(i);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class WallAdapter extends BaseAdapter {
        @Override public int getCount() { return shown; }
        @Override public Object getItem(int pos) { return all.get(pos); }
        @Override public long getItemId(int pos) { return all.get(pos).id; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            LinearLayout ll;
            ImageView img;
            TextView main, sub, mark;
            if (convertView instanceof LinearLayout) {
                ll = (LinearLayout) convertView;
                img = (ImageView) ll.getChildAt(0);
                LinearLayout texts = (LinearLayout) ll.getChildAt(1);
                main = (TextView) texts.getChildAt(0);
                sub = (TextView) texts.getChildAt(1);
                mark = (TextView) texts.getChildAt(2);
            } else {
                ll = new LinearLayout(WallListActivity.this);
                ll.setOrientation(LinearLayout.HORIZONTAL);
                ll.setPadding(dp(6), dp(6), dp(6), dp(6));
                img = new ImageView(WallListActivity.this);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Sanctuary.posterFrame(WallListActivity.this));
                img.setDuplicateParentStateEnabled(true);
                Sanctuary.roundClip(WallListActivity.this, img, Sanctuary.TILE_RADIUS_DP);
                ll.addView(img, new LinearLayout.LayoutParams(dp(46), dp(64)));
                LinearLayout texts = new LinearLayout(WallListActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(10), 0, 0, 0);
                main = new TextView(WallListActivity.this);
                main.setTextColor(Sanctuary.INK);
                main.setTextSize(15);
                sub = new TextView(WallListActivity.this);
                sub.setTextColor(Sanctuary.SUB);
                sub.setTextSize(12);
                mark = new TextView(WallListActivity.this);
                mark.setTextSize(11);
                texts.addView(main);
                texts.addView(sub);
                texts.addView(mark);
                ll.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            CatalogIndex.Record r = all.get(pos);
            main.setText(r.name);
            StringBuilder meta = new StringBuilder();
            if (!r.language.isEmpty()) meta.append(r.language);
            if (!r.genres.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(String.join(" · ", r.genres));
            }
            if (r.rating > 0) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append("★ ").append(String.format(
                        java.util.Locale.US, "%.1f", r.rating));
            }
            if (!r.premiered.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(r.premiered.substring(0, Math.min(4, r.premiered.length())));
            }
            if (!r.type.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(r.type);
            }
            sub.setText(meta.toString());
            boolean avail = CatalogStore.isAvailable(
                    WallListActivity.this, shelf, r);
            int cst = CardState.forShow(avail, false);
            if (CardState.playPromise(cst)) {
                mark.setText("✓ " + LangStore.tr(WallListActivity.this, "wall_available"));
                mark.setTextColor(Sanctuary.TEAL);
            } else {
                // Display Honesty Law (v0.14.0): catalog-only is
                // dimmed + plainly worded BEFORE any tap
                mark.setText(LangStore.tr(WallListActivity.this,
                        CardState.labelKey(cst)));
                mark.setTextColor(Sanctuary.GOLD_DIM);
            }
            ll.setAlpha(CardState.alphaFor(cst));
            ImageCache.load(WallListActivity.this, r.image, img);
            ll.setContentDescription(CardState.playPromise(cst)
                    ? r.name
                    : r.name + " — " + LangStore.tr(WallListActivity.this,
                            CardState.labelKey(cst)));
            return ll;
        }
    }
}
