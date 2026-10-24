package com.talacore.talatv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live TV — the v0.1.0 channel surface, kept whole and given facets in
 * v0.2.0: genre / language / country filters that combine (English +
 * Movies + United States means exactly that). The built-in directory is
 * assembled from iptv-org's facet indexes, which carry the same streams
 * as the master index with the grouping rotated — joined here by URL.
 */
public class MainActivity extends Activity {

    private static final int REQ_PLAYLISTS = 7;
    private static final String ALL_GENRES = "All genres";
    private static final String FAVORITES = "★ Favorites";
    private static final String ALL_LANGS = "All languages";
    private static final String ALL_COUNTRIES = "All countries";
    private static final String NOT_SPECIFIED = "Not specified";

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ExecutorService tapExec = Executors.newSingleThreadExecutor();
    private final ExecutorService verifyExec = Executors.newSingleThreadExecutor();

    private List<M3uParser.Channel> allChannels = new ArrayList<>();
    /** Rows currently shown: String = group header, Channel = a channel. */
    private final List<Object> displayRows = new ArrayList<>();
    /** Channels only, in display order - the play queue order. */
    private final List<M3uParser.Channel> displayChannels = new ArrayList<>();

    private Set<String> favorites;

    private TextView countView;
    private TextView statusView;
    private ProgressBar progress;
    private EditText searchBox;
    private Spinner genreSpinner, languageSpinner, countrySpinner;
    private ListView listView;
    private ChannelAdapter adapter;
    private CheckBox workingOnlyBox;
    private Button verifyBtn;
    private Button keptBanner;
    private TextView scanLine;
    // v0.17.0: the scan ticker — progress in plain words while
    // the sweep runs; the list re-renders as verdicts land.
    private final Handler scanHandler = new Handler();
    private int scanSeenDone = -1;
    private boolean scanWasRunning = false;
    private final Runnable scanTick = new Runnable() {
        @Override public void run() {
            tickScan();
            scanHandler.postDelayed(this, 600);
        }
    };
    private boolean verifying = false;
    private boolean probingTap = false;
    private boolean langFacetTouched = false;
    /** Filed guide data for now/next lines (v0.11.0); null until loaded. */
    private volatile GuideModel.GuideData guideData;

    private List<String> genreChoices = new ArrayList<>();
    private List<String> languageChoices = new ArrayList<>();
    private List<String> countryChoices = new ArrayList<>();
    private boolean loading = false;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        favorites = ChannelStore.getFavorites(this);
        NameStore.ensureLoaded(this);

        // v0.17.0 THE SANCTUARY LAYOUT: the sanctuary ground.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        int pad = dp(Sanctuary.PAGE_PAD_DP);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "live_title"));
        title.setTextSize(22);
        Sanctuary.goldText(title);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        countView = new TextView(this);
        countView.setTextColor(Sanctuary.MUTED);
        countView.setTextSize(13);
        countView.setPadding(dp(12), 0, 0, 0);
        titleRow.addView(countView);
        root.addView(titleRow);

        // The kept place (v0.5.0, item 8): the last live channel's
        // buffer survived the app closing — one tap returns to it and
        // the player offers the place back.
        keptBanner = new Button(this);
        keptBanner.setVisibility(View.GONE);
        Sanctuary.styleButton(this, keptBanner, false);
        keptBanner.setOnClickListener(v -> openKeptChannel());
        root.addView(keptBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Search, visibly a search (v0.6.0, field note #2e): a
        // labeled, framed bar at the top of Live TV — Delwin could
        // not find it when it was a bare text field among buttons.
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(Sanctuary.field(this));
        searchRow.setPadding(dp(12), dp(4), dp(12), dp(4));
        TextView searchIcon = new TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(18);
        searchIcon.setContentDescription(LangStore.tr(this, "live_search_hint"));
        searchRow.addView(searchIcon);
        searchBox = new EditText(this);
        searchBox.setHint(LangStore.tr(this, "live_search_hint"));
        searchBox.setContentDescription(LangStore.tr(this, "live_search_hint"));
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Sanctuary.INK);
        searchBox.setHintTextColor(Sanctuary.SUB);
        searchBox.setBackgroundColor(Color.TRANSPARENT);
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams searchRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchRowLp.setMargins(0, dp(6), 0, dp(6));
        root.addView(searchRow, searchRowLp);

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        genreSpinner = new Spinner(this);
        controlRow.addView(genreSpinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button playlistsBtn = new Button(this);
        playlistsBtn.setText(LangStore.tr(this, "live_playlists"));
        Sanctuary.styleButton(this, playlistsBtn, false);
        playlistsBtn.setOnClickListener(v -> startActivityForResult(
                new Intent(this, PlaylistsActivity.class), REQ_PLAYLISTS));
        controlRow.addView(playlistsBtn);
        Button refreshBtn = new Button(this);
        refreshBtn.setText(LangStore.tr(this, "live_refresh"));
        Sanctuary.styleButton(this, refreshBtn, false);
        refreshBtn.setOnClickListener(v -> fetchAll(false));
        controlRow.addView(refreshBtn);
        // The TV Guide door (v0.11.0): what's on, what's next, the
        // day's schedule, and timer recording — one tap from Live TV.
        Button guideBtn = new Button(this);
        guideBtn.setText(LangStore.tr(this, "guide_btn"));
        Sanctuary.styleButton(this, guideBtn, false);
        guideBtn.setOnClickListener(v -> startActivity(
                new Intent(this, GuideActivity.class)));
        controlRow.addView(guideBtn);
        root.addView(controlRow);

        LinearLayout facetRow = new LinearLayout(this);
        facetRow.setOrientation(LinearLayout.HORIZONTAL);
        languageSpinner = new Spinner(this);
        facetRow.addView(languageSpinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        countrySpinner = new Spinner(this);
        facetRow.addView(countrySpinner, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(facetRow);

        // Link health (v0.3.0): hide the links already proven dead, and
        // a Verify action that probes the visible set and badges it.
        LinearLayout healthRow = new LinearLayout(this);
        healthRow.setOrientation(LinearLayout.HORIZONTAL);
        healthRow.setGravity(Gravity.CENTER_VERTICAL);
        workingOnlyBox = new CheckBox(this);
        workingOnlyBox.setText(LangStore.tr(this, "live_working_only"));
        workingOnlyBox.setTextColor(Sanctuary.BODY);
        // Operator default (v0.4.0): a fresh install starts with
        // working-links-first ON; the toggle writes the pref back.
        workingOnlyBox.setChecked(ChannelStore.getWorkingFirst(this));
        workingOnlyBox.setOnCheckedChangeListener((b, checked) -> {
            ChannelStore.setWorkingFirst(this, checked);
            rebuildDisplay();
        });
        healthRow.addView(workingOnlyBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        verifyBtn = new Button(this);
        verifyBtn.setText(LangStore.tr(this, "live_verify"));
        Sanctuary.styleButton(this, verifyBtn, false);
        verifyBtn.setOnClickListener(v -> verifyVisible());
        healthRow.addView(verifyBtn);
        // v0.17.0 THE CHANNEL SCAN: the full sweep — every link
        // in Live TV and the catalog pretested through Hydra;
        // the terminally dead come off the shelf entirely.
        Button scanBtn = new Button(this);
        scanBtn.setText(LangStore.tr(this, "scan_action"));
        scanBtn.setContentDescription(LangStore.tr(this, "scan_action"));
        Sanctuary.styleButton(this, scanBtn, false);
        scanBtn.setOnClickListener(v -> startScan());
        healthRow.addView(scanBtn);
        root.addView(healthRow);

        scanLine = new TextView(this);
        scanLine.setTextColor(Sanctuary.SUB);
        scanLine.setTextSize(12);
        scanLine.setPadding(dp(2), dp(2), 0, 0);
        root.addView(scanLine);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        progress.setProgressTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        progress.setIndeterminateTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        // Service / section tile rows (v0.12.1) — peers over the list.
        TextView svcLabel = new TextView(this);
        svcLabel.setText(LangStore.tr(this, "live_by_service"));
        svcLabel.setTextColor(Sanctuary.MUTED);
        svcLabel.setTextSize(12);
        root.addView(svcLabel);
        serviceTiles = new LinearLayout(this);
        serviceTiles.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView svcScroll =
                new android.widget.HorizontalScrollView(this);
        svcScroll.addView(serviceTiles);
        root.addView(svcScroll);
        TextView secLabel = new TextView(this);
        secLabel.setText(LangStore.tr(this, "live_by_section"));
        secLabel.setTextColor(Sanctuary.MUTED);
        secLabel.setTextSize(12);
        root.addView(secLabel);
        sectionTiles = new LinearLayout(this);
        sectionTiles.setOrientation(LinearLayout.HORIZONTAL);
        android.widget.HorizontalScrollView secScroll =
                new android.widget.HorizontalScrollView(this);
        secScroll.addView(sectionTiles);
        root.addView(secScroll);

        listView = new ListView(this);
        listView.setBackgroundColor(Sanctuary.GROUND);
        listView.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        listView.setDividerHeight(1);
        adapter = new ChannelAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusView = new TextView(this);
        statusView.setTextColor(Sanctuary.TEAL);
        statusView.setTextSize(12);
        root.addView(statusView);

        setContentView(root);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { rebuildDisplay(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        AdapterView.OnItemSelectedListener facetListener =
                new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                        rebuildDisplay();
                    }
                    @Override public void onNothingSelected(AdapterView<?> p) { }
                };
        genreSpinner.setOnItemSelectedListener(facetListener);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                langFacetTouched = true;
                rebuildDisplay();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        countrySpinner.setOnItemSelectedListener(facetListener);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Object row = displayRows.get(position);
            if (row instanceof M3uParser.Channel) {
                playChannel((M3uParser.Channel) row);
            }
        });

        List<M3uParser.Channel> cached = ChannelStore.loadCache(this);
        if (cached != null) {
            allChannels = cached;
            afterChannelsLoaded("Loaded from cache — tap Refresh for the latest guide.");
        } else {
            fetchAll(true);
        }
        // Auto-fresh (v0.4.0): stale verdicts quietly re-verify in the
        // background — favorites + the surf lists first. Also triggered
        // from the boot screen; the guard makes double-calls harmless.
        triggerFreshVerify();
    }

    private void triggerFreshVerify() {
        FreshVerify.maybeRun(this,
                () -> ChannelStore.loadCache(getApplicationContext()),
                n -> {
                    if (n > 0 && !isFinishing()) {
                        adapter.notifyDataSetChanged();
                        rebuildDisplay();
                    }
                });
    }

    /**
     * Directory warm-up for the boot screen (v0.4.0): when no cached
     * guide exists yet, fetch and cache it headlessly so Live TV opens
     * warm. Runs entirely off statics; safe from any thread.
     */
    static void warmUpDirectory(Context ctx) {
        try {
            NameStore.ensureLoaded(ctx);
            if (ChannelStore.loadCache(ctx) != null) return;
            List<M3uParser.Channel> merged = fetchMergedGuide(ctx);
            if (merged != null && !merged.isEmpty()) {
                // Real names (v0.5.0): join before the cache is written.
                NameStore.refresh(ctx);
                ChannelNames.applyToAll(merged);
                ChannelStore.saveCache(ctx, merged);
            }
        } catch (Exception ignored) {
            // a failed warm-up just means Live TV fetches on open, as before
        }
    }

    /** The fetchAll worker body, as a headless static for warm-up reuse. */
    private static List<M3uParser.Channel> fetchMergedGuide(Context ctx) throws Exception {
        List<M3uParser.Channel> merged = new ArrayList<>();
        Map<String, Boolean> seenUrl = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (ChannelStore.Playlist pl : ChannelStore.getPlaylists(ctx)) {
            try {
                List<M3uParser.Channel> parsed;
                if (pl.builtin) {
                    parsed = fetchBuiltinWithFacets(pl, notes);
                } else {
                    byte[] bytes = download(pl.url);
                    parsed = M3uParser.parse(new ByteArrayInputStream(bytes), pl.name);
                }
                for (M3uParser.Channel c : parsed) {
                    if (seenUrl.put(c.url, Boolean.TRUE) == null) merged.add(c);
                }
            } catch (Exception ignored) {
                // one playlist failing costs that playlist, not the guide
            }
        }
        return merged;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PLAYLISTS && resultCode == RESULT_OK) {
            fetchAll(false); // playlist set changed: rebuild the merged guide
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- loading

    private void fetchAll(boolean firstRun) {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setText(firstRun
                ? "Fetching the channel guide for the first time…"
                : "Refreshing the channel guide…");
        List<ChannelStore.Playlist> playlists = ChannelStore.getPlaylists(this);
        exec.execute(() -> {
            // Real names (v0.5.0): keep the name map as fresh as the
            // guide — best-effort; a failure keeps the map on disk.
            NameStore.refresh(this);
            List<M3uParser.Channel> merged = new ArrayList<>();
            Map<String, Boolean> seenUrl = new LinkedHashMap<>();
            List<String> notes = new ArrayList<>();
            for (ChannelStore.Playlist pl : playlists) {
                try {
                    List<M3uParser.Channel> parsed;
                    if (pl.builtin) {
                        parsed = fetchBuiltinWithFacets(pl, notes);
                    } else {
                        byte[] bytes = download(pl.url);
                        parsed = M3uParser.parse(new ByteArrayInputStream(bytes), pl.name);
                    }
                    int added = 0;
                    for (M3uParser.Channel c : parsed) {
                        if (seenUrl.put(c.url, Boolean.TRUE) == null) {
                            merged.add(c);
                            added++;
                        }
                    }
                    notes.add(pl.name + ": " + added);
                } catch (Exception e) {
                    notes.add(pl.name + ": DIDN'T LOAD (" + shortErr(e) + ")");
                }
            }
            runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (!merged.isEmpty()) {
                    ChannelNames.applyToAll(merged);
                    allChannels = merged;
                    ChannelStore.saveCache(this, merged);
                    afterChannelsLoaded("Guide updated " + nowStamp() + " — " + String.join(" · ", notes));
                } else {
                    statusView.setTextColor(Sanctuary.WARN);
                    statusView.setText("Couldn't load any playlist. Check the connection and tap Refresh. " + String.join(" · ", notes));
                    if (!allChannels.isEmpty()) {
                        Toast.makeText(this, "Refresh failed — showing the saved guide", Toast.LENGTH_LONG).show();
                    }
                }
            });
        });
    }

    /**
     * The built-in directory, assembled from the facet indexes: category
     * file as the base (same streams, same attrs as the master index),
     * language and country joined in by URL. A facet file failing costs
     * that facet, not the guide.
     */
    private static List<M3uParser.Channel> fetchBuiltinWithFacets(ChannelStore.Playlist pl,
                                                           List<String> notes) throws Exception {
        byte[] bytes = download(ChannelStore.FACET_CATEGORY_URL);
        List<M3uParser.Channel> parsed =
                M3uParser.parse(new ByteArrayInputStream(bytes), pl.name);
        Map<String, String> langByUrl = facetMap(ChannelStore.FACET_LANGUAGE_URL);
        Map<String, String> countryByUrl = facetMap(ChannelStore.FACET_COUNTRY_URL);
        int withLang = 0, withCountry = 0;
        for (M3uParser.Channel c : parsed) {
            String lang = langByUrl.get(c.url);
            if (lang != null && !lang.isEmpty()) { c.language = lang; withLang++; }
            String ctry = countryByUrl.get(c.url);
            if (ctry != null && !ctry.isEmpty()) { c.country = ctry; withCountry++; }
        }
        notes.add("facets: " + withLang + " with language, " + withCountry + " with country");
        return parsed;
    }

    private static Map<String, String> facetMap(String url) {
        try {
            byte[] bytes = download(url);
            List<M3uParser.Channel> parsed =
                    M3uParser.parse(new ByteArrayInputStream(bytes), "facet");
            Map<String, String> map = new HashMap<>();
            for (M3uParser.Channel c : parsed) map.put(c.url, c.group);
            return map;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static String nowStamp() {
        return new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date());
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static byte[] download(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("User-Agent", Net.UA);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------- display

    private void afterChannelsLoaded(String status) {
        statusView.setTextColor(Sanctuary.TEAL);
        statusView.setText(status);
        rebuildFacets();
        rebuildTiles();
        rebuildDisplay();
        restoreStanceIfNeeded();
        refreshKeptBanner();
    }

    // ------------------------------------------------- service/section tiles
    // v0.12.1 (James): "subdivide the channels by what they are."
    // Two tile rows stand over the Live list as PEERS — by SERVICE
    // (Pluto TV, Plex, Samsung TV Plus… read off the guide bridge's
    // file trees, else the logo host) and by SECTION (the category
    // in each channel's canonical definition). A tile filters the
    // list to its channels; tapping it again (or All) clears it.
    // One tap from door to channels, no machinery in between.

    private LinearLayout serviceTiles, sectionTiles;
    private String activeService = "", activeSection = "";
    private Map<String, String> svcByBase;

    private void rebuildTiles() {
        if (serviceTiles == null) return;
        List<GuideStore.MapRow> rows;
        try {
            rows = GuideStore.map(this);
        } catch (Exception e) {
            rows = new ArrayList<>();
        }
        svcByBase = LiveGroups.serviceByBaseId(rows);
        fillTileRow(serviceTiles, LiveGroups.groupByService(allChannels, svcByBase),
                true);
        fillTileRow(sectionTiles, LiveGroups.groupBySection(allChannels),
                false);
    }

    private void fillTileRow(LinearLayout row,
                             Map<String, List<M3uParser.Channel>> groups,
                             boolean service) {
        row.removeAllViews();
        String active = service ? activeService : activeSection;
        Button all = new Button(this);
        all.setText(LangStore.tr(this, "live_all")
                + (active.isEmpty() ? " ✓" : ""));
        Sanctuary.styleChip(this, all, active.isEmpty());
        all.setOnClickListener(v -> {
            if (service) activeService = ""; else activeSection = "";
            rebuildTiles();
            rebuildDisplay();
        });
        row.addView(all);
        // Display Honesty Law, QC clause (v0.14.0): a tile's
        // number is the count of VERIFIED-LIVE links only —
        // the checker's output, read fresh at render (a link
        // that later dies leaves the count at the next render;
        // FreshVerify re-checks in the background). A group
        // with no verified link yet prints NO number — an
        // unverified count is never a claim Sandy makes.
        Map<String, HealthStore.Health> health = HealthStore.load(this);
        Map<String, Integer> tiers = new HashMap<>();
        for (Map.Entry<String, HealthStore.Health> he : health.entrySet()) {
            tiers.put(he.getKey(), he.getValue().tier);
        }
        for (Map.Entry<String, List<M3uParser.Channel>> e : groups.entrySet()) {
            List<String> urls = new ArrayList<>();
            for (M3uParser.Channel c : e.getValue()) urls.add(c.url);
            String count = DisplayCounts.countText(
                    DisplayCounts.verifiedCount(urls, tiers));
            Button b = new Button(this);
            b.setText(e.getKey() + (count.isEmpty() ? "" : " · " + count)
                    + (e.getKey().equals(active) ? " ✓" : ""));
            Sanctuary.styleChip(this, b, e.getKey().equals(active));
            final String key = e.getKey();
            b.setOnClickListener(v -> {
                if (service) {
                    activeService = key.equals(activeService) ? "" : key;
                } else {
                    activeSection = key.equals(activeSection) ? "" : key;
                }
                rebuildTiles();
                rebuildDisplay();
            });
            row.addView(b);
        }
    }

    private void rebuildFacets() {
        String prevGenre = chosen(genreSpinner, genreChoices);
        String prevLang = chosen(languageSpinner, languageChoices);
        String prevCountry = chosen(countrySpinner, countryChoices);

        Map<String, Integer> genreCounts = new HashMap<>();
        Map<String, Integer> langCounts = new HashMap<>();
        Map<String, Integer> countryCounts = new HashMap<>();
        boolean anyNoLang = false, anyNoCountry = false;
        for (M3uParser.Channel c : allChannels) {
            genreCounts.merge(c.group, 1, Integer::sum);
            if (c.language.isEmpty()) anyNoLang = true;
            else langCounts.merge(c.language, 1, Integer::sum);
            if (c.country.isEmpty()) anyNoCountry = true;
            else countryCounts.merge(c.country, 1, Integer::sum);
        }

        List<String> groups = new ArrayList<>(genreCounts.keySet());
        Collections.sort(groups, String.CASE_INSENSITIVE_ORDER);
        genreChoices = new ArrayList<>();
        genreChoices.add(ALL_GENRES);
        genreChoices.add(FAVORITES);
        genreChoices.addAll(groups);

        languageChoices = new ArrayList<>();
        languageChoices.add(ALL_LANGS);
        languageChoices.addAll(byCountDesc(langCounts));
        if (anyNoLang) languageChoices.add(NOT_SPECIFIED);

        countryChoices = new ArrayList<>();
        countryChoices.add(ALL_COUNTRIES);
        countryChoices.addAll(byCountDesc(countryCounts));
        if (anyNoCountry) countryChoices.add(NOT_SPECIFIED);

        setSpinnerChoices(genreSpinner, genreChoices, prevGenre);
        setSpinnerChoices(languageSpinner, languageChoices, prevLang);
        setSpinnerChoices(countrySpinner, countryChoices, prevCountry);
        // v0.5.0 (Delwin #4): the chosen language is the default surf
        // filter — until the viewer picks a facet by hand.
        if (!langFacetTouched) {
            String uiLang = ChannelStore.getUiLanguage(this);
            if (uiLang != null && !uiLang.isEmpty() && !"en".equals(uiLang)
                    && ALL_LANGS.equals(chosen(languageSpinner, languageChoices))) {
                int idx = languageChoices.indexOf(UiStrings.facetFor(uiLang));
                if (idx >= 0) languageSpinner.setSelection(idx);
            }
        }
        // The title count is a presented count (v0.17.0): links
        // the channel scan convicted dead are off the shelf and
        // out of the number — printed counts are verified counts.
        int presented = 0;
        Map<String, ChannelScan.Rec> scanRecs = ScanStore.load(this);
        for (M3uParser.Channel c : allChannels) {
            if (ChannelScan.verdictOf(scanRecs, c.url)
                    != ChannelScan.V_DEAD) presented++;
        }
        countView.setText(presented + " "
                + LangStore.tr(this, "live_channels_suffix"));
    }

    private static List<String> byCountDesc(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        Collections.sort(entries, (a, b) -> {
            int d = b.getValue() - a.getValue();
            return d != 0 ? d : a.getKey().compareToIgnoreCase(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) out.add(e.getKey());
        return out;
    }

    private static String chosen(Spinner spinner, List<String> choices) {
        if (spinner == null || choices == null || choices.isEmpty()) return null;
        Object sel = spinner.getSelectedItem();
        return sel == null ? null : String.valueOf(sel);
    }

    private void setSpinnerChoices(Spinner spinner, List<String> choices, String prevValue) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, choices);
        spinner.setAdapter(ad);
        if (prevValue != null) {
            int idx = choices.indexOf(prevValue);
            if (idx >= 0) spinner.setSelection(idx);
        }
    }

    private void rebuildDisplay() {
        displayRows.clear();
        displayChannels.clear();
        String genre = chosen(genreSpinner, genreChoices);
        if (genre == null) genre = ALL_GENRES;
        String lang = chosen(languageSpinner, languageChoices);
        if (lang == null) lang = ALL_LANGS;
        String country = chosen(countrySpinner, countryChoices);
        if (country == null) country = ALL_COUNTRIES;
        String q = searchBox.getText().toString().trim().toLowerCase(Locale.US);
        boolean favMode = FAVORITES.equals(genre);
        boolean allGenres = ALL_GENRES.equals(genre);
        boolean allLangs = ALL_LANGS.equals(lang);
        boolean allCountries = ALL_COUNTRIES.equals(country);
        boolean workingOnly = workingOnlyBox != null && workingOnlyBox.isChecked();

        List<M3uParser.Channel> filtered = new ArrayList<>();
        int hiddenDead = 0;
        int scanRemoved = 0;
        for (M3uParser.Channel c : allChannels) {
            // v0.17.0 THE CHANNEL SCAN: a link the scan convicted
            // terminally dead is OFF the shelf — not badged, not
            // greyed, gone, in every mode (so the counts below stay
            // verified counts: these never enter them).
            if (ScanStore.verdictOf(this, c.url) == ChannelScan.V_DEAD) {
                scanRemoved++;
                continue;
            }
            if (favMode && !favorites.contains(c.url)) continue;
            if (!allGenres && !favMode && !c.group.equals(genre)) continue;
            if (!allLangs) {
                if (NOT_SPECIFIED.equals(lang)) {
                    if (!c.language.isEmpty()) continue;
                } else if (!c.language.equals(lang)) continue;
            }
            if (!allCountries) {
                if (NOT_SPECIFIED.equals(country)) {
                    if (!c.country.isEmpty()) continue;
                } else if (!c.country.equals(country)) continue;
            }
            if (!q.isEmpty() && !ChannelNames.matchesQuery(c, q)) continue;
            if (workingOnly) {
                // "Working links only" hides only what a probe has
                // PROVEN dead — unchecked links stay, wearing their "?".
                HealthStore.Health h = HealthStore.get(this, c.url);
                if (h != null && h.tier == LinkHealth.TIER_DEAD) {
                    hiddenDead++;
                    continue;
                }
            }
            // Tile filters (v0.12.1): the active service/section
            // tiles narrow the same list the facets narrow.
            if (!activeService.isEmpty()
                    && !activeService.equals(
                            LiveGroups.serviceOf(c, svcByBase))) {
                continue;
            }
            if (!activeSection.isEmpty()
                    && !LiveGroups.sectionsOf(c).contains(activeSection)) {
                continue;
            }
            filtered.add(c);
        }
        // Mom-first surf order (v0.4.0): proven-to-play channels first,
        // then never-checked, then connects-only, then dead — a link
        // dead 3+ verdicts running sinks last of all. Name breaks ties.
        Comparator<M3uParser.Channel> byHealthThenName = (a, b) -> {
            int ra = HealthStore.surfRank(HealthStore.get(this, a.url));
            int rb = HealthStore.surfRank(HealthStore.get(this, b.url));
            if (ra != rb) return Integer.compare(ra, rb);
            return a.displayName().compareToIgnoreCase(b.displayName());
        };
        Comparator<M3uParser.Channel> byName = byHealthThenName;
        boolean unfiltered = allGenres && allLangs && allCountries && q.isEmpty();
        if (!q.isEmpty()) {
            // SEARCH (field note #2e): one flat result list — the
            // person asked for matches, not rooms.
            filtered.sort(byName);
            for (M3uParser.Channel c : filtered) {
                displayRows.add(c);
                displayChannels.add(c);
            }
        } else if (!allLangs && allCountries && !favMode) {
            // INSIDE A LANGUAGE (field notes #2a + #2e): the list is
            // grouped by COUNTRY — the viewer's home country first
            // (named as such), the rest A->Z under their own headers,
            // nothing hidden. Inside a country, the health-first
            // surf order rules as everywhere.
            Map<String, List<M3uParser.Channel>> byCountry = new LinkedHashMap<>();
            for (M3uParser.Channel c : filtered) {
                byCountry.computeIfAbsent(c.country, k -> new ArrayList<>()).add(c);
            }
            String home = homeCountryName();
            List<String> cs = new ArrayList<>(byCountry.keySet());
            Collections.sort(cs, String.CASE_INSENSITIVE_ORDER);
            if (!home.isEmpty() && byCountry.containsKey(home)) {
                addSection(home + " — " + LangStore.tr(this, "live_home_tag"),
                        byCountry.get(home), byName);
                cs.remove(home);
            }
            for (String co : cs) {
                addSection(co.isEmpty() ? NOT_SPECIFIED : co,
                        byCountry.get(co), byName);
            }
        } else if (unfiltered) {
            // DEFAULT BROWSING: genre ROOMS (field note #2a) —
            // header rows between alphabetical groups, as since 0.5.0.
            Map<String, List<M3uParser.Channel>> byGroup = new LinkedHashMap<>();
            for (M3uParser.Channel c : filtered) {
                byGroup.computeIfAbsent(c.group, k -> new ArrayList<>()).add(c);
            }
            List<String> gs = new ArrayList<>(byGroup.keySet());
            Collections.sort(gs, String.CASE_INSENSITIVE_ORDER);
            for (String g : gs) {
                addSection(g, byGroup.get(g), byName);
            }
        } else {
            // A facet is picked (one genre, one country, favorites):
            // that IS the room — flat inside it.
            filtered.sort(byName);
            for (M3uParser.Channel c : filtered) {
                displayRows.add(c);
                displayChannels.add(c);
            }
        }
        adapter.notifyDataSetChanged();
        if (!loading) {
            statusView.setTextColor(Sanctuary.MUTED);
            if (favMode && displayChannels.isEmpty()) {
                statusView.setText("No favorites yet — tap the ☆ on any channel to keep it here.");
            } else if (!unfiltered || workingOnly || scanRemoved > 0) {
                statusView.setText(displayChannels.size() + " channels match — "
                        + genre + " · " + lang + " · " + country
                        + (q.isEmpty() ? "" : " · “" + q + "”")
                        + (workingOnly && hiddenDead > 0
                            ? " · " + hiddenDead + " dead hidden" : "")
                        + (scanRemoved > 0
                            ? " · " + scanRemoved + " removed by scan" : ""));
            }
        }
    }

    /** One section of the list: a header row + its channels. */
    private void addSection(String header, List<M3uParser.Channel> members,
                            Comparator<M3uParser.Channel> order) {
        if (members == null || members.isEmpty()) return;
        members.sort(order);
        displayRows.add(header + "  (" + members.size() + ")");
        for (M3uParser.Channel c : members) {
            displayRows.add(c);
            displayChannels.add(c);
        }
    }

    /**
     * The viewer's home country, in the facet's own naming (the
     * directory's country names are English: "United States",
     * "Japan"…). "" when the phone's locale names none of them —
     * then no country is lifted and the order is plain A->Z.
     */
    private String homeCountryName() {
        try {
            String home = java.util.Locale.getDefault()
                    .getDisplayCountry(java.util.Locale.US);
            return home == null ? "" : home.trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------- the stance (v0.6.0)

    private boolean stanceRestored = false;

    /**
     * Field note #2d: BACK LOSES THE WHOLE STANCE. The stance —
     * genre, language, country, search, working-only, AND where in
     * the list the viewer was standing — is saved as one record when
     * they leave for the player, and restored whole on return.
     */
    private void saveStance() {
        try {
            Stance s = new Stance();
            s.genre = chosen(genreSpinner, genreChoices);
            s.language = chosen(languageSpinner, languageChoices);
            s.country = chosen(countrySpinner, countryChoices);
            if (s.genre == null) s.genre = "";
            if (s.language == null) s.language = "";
            if (s.country == null) s.country = "";
            s.query = searchBox == null ? ""
                    : searchBox.getText().toString().trim();
            s.workingOnly = workingOnlyBox != null && workingOnlyBox.isChecked();
            if (listView != null) {
                s.firstVisible = listView.getFirstVisiblePosition();
                View top = listView.getChildAt(0);
                s.topPx = top == null ? 0 : top.getTop() - listView.getPaddingTop();
            }
            ChannelStore.setStanceJson(this, s.toJson());
        } catch (Exception ignored) {}
    }

    private void restoreStanceIfNeeded() {
        if (stanceRestored) return;
        String json = ChannelStore.getStanceJson(this);
        if (json == null || json.isEmpty()) { stanceRestored = true; return; }
        Stance s = Stance.fromJson(json);
        stanceRestored = true;
        try {
            selectSpinner(genreSpinner, genreChoices, s.genre);
            if (!s.language.isEmpty()) langFacetTouched = true;
            selectSpinner(languageSpinner, languageChoices, s.language);
            selectSpinner(countrySpinner, countryChoices, s.country);
            if (!s.query.isEmpty()) searchBox.setText(s.query);
            if (workingOnlyBox != null) workingOnlyBox.setChecked(s.workingOnly);
            rebuildDisplay();
            if (listView != null && s.firstVisible > 0) {
                final int row = s.firstVisible, px = s.topPx;
                listView.post(() -> listView.setSelectionFromTop(row, px));
            }
        } catch (Exception ignored) {}
    }

    private void selectSpinner(Spinner spinner, List<String> choices, String value) {
        if (spinner == null || choices == null || value == null || value.isEmpty()) return;
        int idx = choices.indexOf(value);
        if (idx >= 0) spinner.setSelection(idx);
    }

    @Override
    protected void onPause() {
        scanHandler.removeCallbacks(scanTick);
        super.onPause();
        saveStance();
    }

    // ------------------------------------------------------------- playing

    private void launchPlayer(M3uParser.Channel ch) {
        saveStance();
        int idx = displayChannels.indexOf(ch);
        ChannelStore.playQueue = new ArrayList<>(displayChannels);
        ChannelStore.playIndex = Math.max(idx, 0);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", ch.url);
        i.putExtra("name", ch.displayName());
        i.putExtra("ua", ch.userAgent);
        startActivity(i);
    }

    // ------------------------------------------------- the kept place

    @Override
    protected void onResume() {
        super.onResume();
        refreshKeptBanner();
        TimerReconcile.run(getApplicationContext());
        updateScanLine();
        scanHandler.removeCallbacks(scanTick);
        scanHandler.post(scanTick);
        // Refresh the filed guide snapshot for now/next row lines.
        new Thread(() -> {
            GuideModel.GuideData d = GuideStore.data(getApplicationContext());
            if (d != guideData) {
                guideData = d;
                runOnUiThread(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }
        }, "sandy-guide-snap").start();
    }

    /** "▶ Title (–8:00 PM) · Next: …" for a channel, or "" — only
     *  ever from programmes actually filed for this channel. */
    private String guideLineFor(M3uParser.Channel c) {
        GuideModel.GuideData d = guideData;
        if (d == null) return "";
        String base = GuideStore.baseId(c.tvgId);
        if (!d.hasGuide(base)) return "";
        long now = System.currentTimeMillis();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        GuideModel.Programme n = d.now(base, now);
        GuideModel.Programme x = d.next(base, now);
        if (n == null && x == null) return "";
        StringBuilder sb = new StringBuilder();
        if (n != null) {
            sb.append("▶ ").append(n.title).append(" (–")
              .append(GuideModel.formatTime(n.stopMs, zone)).append(')');
        }
        if (x != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(LangStore.tr(this, "guide_next")).append(": ")
              .append(x.title).append(' ')
              .append(GuideModel.formatTime(x.startMs, zone));
        }
        return sb.toString();
    }

    /** Show the "pick up where you left off" door when a place is kept. */
    private void refreshKeptBanner() {
        if (keptBanner == null) return;
        ChannelStore.KeptLive k = ChannelStore.getKeptLive(this);
        if (k == null) {
            keptBanner.setVisibility(View.GONE);
            return;
        }
        String name = ChannelNames.cleanName(k.name);
        keptBanner.setText("▶ " + name + " — "
                + LangStore.tr(this, "kept_resume")
                + (k.held ? " · " + LangStore.tr(this, "kept_held") : ""));
        keptBanner.setContentDescription(LangStore.tr(this, "kept_resume") + ": " + name);
        keptBanner.setVisibility(View.VISIBLE);
    }

    /**
     * Return to the kept channel. Launched directly (no tap probe):
     * the place matters more than the verdict, and the player is the
     * ground truth anyway — if the link died, it will say so there.
     */
    private void openKeptChannel() {
        ChannelStore.KeptLive k = ChannelStore.getKeptLive(this);
        if (k == null) return;
        for (M3uParser.Channel c : displayChannels) {
            if (c.url.equals(k.url)) {
                launchPlayer(c);
                return;
            }
        }
        for (M3uParser.Channel c : allChannels) {
            if (c.url.equals(k.url)) {
                ChannelStore.playQueue = Collections.singletonList(c);
                ChannelStore.playIndex = 0;
                Intent i = new Intent(this, PlayerActivity.class);
                i.putExtra("url", c.url);
                i.putExtra("name", c.displayName());
                i.putExtra("ua", c.userAgent);
                startActivity(i);
                return;
            }
        }
        M3uParser.Channel c = new M3uParser.Channel();
        c.url = k.url;
        c.name = k.name;
        c.userAgent = k.userAgent;
        ChannelStore.playQueue = Collections.singletonList(c);
        ChannelStore.playIndex = 0;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", c.url);
        i.putExtra("name", c.displayName());
        i.putExtra("ua", c.userAgent);
        startActivity(i);
    }

    /**
     * Tap-to-play goes through the health layer, deep since v0.4.0:
     * the probe walks playlist -> first segment. PLAYS opens the
     * player. CONNECTS-ONLY also opens it — the playlist answered and
     * the player is the ground truth (its verdict overwrites the
     * probe's either way) — but the user is told the video is
     * unproven first. DEAD never opens: the link is marked ✗ with the
     * time, the user is told plainly, and with auto-skip on the app
     * walks forward to the next link that plays.
     */
    private void playChannel(M3uParser.Channel ch) {
        if (!LinkHealth.isRemote(ch.url)) {
            launchPlayer(ch);
            return;
        }
        if (probingTap) return;
        probingTap = true;
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setText("Checking " + ch.displayName() + "…");
        tapExec.execute(() -> {
            // Patient probe (v0.11.0): slow is retried in place
            // before any verdict is spoken (James's field report).
            LinkHealth.Result r = LinkHealth.probe(ch.url, ch.userAgent, 6000,
                    (next, max) -> runOnUiThread(() ->
                            statusView.setText("Server is slow — still trying… "
                                    + "(attempt " + next + " of " + max + ")")));
            HealthStore.record(this, ch.url, r);
            runOnUiThread(() -> {
                probingTap = false;
                adapter.notifyDataSetChanged();
                if (r.tier == LinkHealth.TIER_PLAYS) {
                    launchPlayer(ch);
                } else if (r.tier == LinkHealth.TIER_CONNECTS) {
                    Toast.makeText(this, ch.displayName() + " answers, but its video is "
                            + "unproven — trying it.", Toast.LENGTH_LONG).show();
                    launchPlayer(ch);
                } else if (r.slow) {
                    // Not dead — slow. Nothing is marked ✗; the
                    // sentence says what actually happened.
                    statusView.setTextColor(Sanctuary.GOLD_TEXT);
                    statusView.setText(ch.displayName() + " didn't answer in time — "
                            + "it may just be slow. It is not marked dead; "
                            + "tap it again to retry.");
                    Toast.makeText(this, "Didn't answer in time (" + r.detail
                                    + ") — not dead, just slow. Tap again to retry.",
                            Toast.LENGTH_LONG).show();
                } else {
                    statusView.setTextColor(Sanctuary.WARN);
                    statusView.setText(ch.displayName() + " is dead — " + r.detail + ". Marked ✗.");
                    Toast.makeText(this, "That link is dead (" + r.detail + ")",
                            Toast.LENGTH_LONG).show();
                    if (ChannelStore.getAutoSkip(this)) advanceToWorking(ch);
                }
            });
        });
    }

    /**
     * After a dead tap with auto-skip on: probe forward, preferring a
     * link proven to the segment (PLAYS). A connects-only candidate is
     * remembered and used only if nothing ahead actually plays.
     */
    private void advanceToWorking(M3uParser.Channel from) {
        int start = displayChannels.indexOf(from);
        List<M3uParser.Channel> candidates = new ArrayList<>();
        for (int i = start + 1; i < displayChannels.size() && candidates.size() < 10; i++) {
            if (LinkHealth.isRemote(displayChannels.get(i).url)) {
                candidates.add(displayChannels.get(i));
            }
        }
        if (candidates.isEmpty()) {
            statusView.setText("No more links after that one to skip to.");
            return;
        }
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setText("Looking for the next link that plays…");
        tapExec.execute(() -> {
            M3uParser.Channel firstConnects = null;
            for (M3uParser.Channel cand : candidates) {
                // The skip-walk stays single-attempt (breadth over
                // patience); slow candidates simply don't qualify —
                // and thanks to the HealthStore guard they aren't
                // filed dead for it either.
                LinkHealth.Result r = LinkHealth.probeOnce(cand.url, cand.userAgent, 6000);
                HealthStore.record(this, cand.url, r);
                if (r.tier == LinkHealth.TIER_PLAYS) {
                    runOnUiThread(() -> {
                        adapter.notifyDataSetChanged();
                        Toast.makeText(this, "Skipped to " + cand.displayName() + " — it plays.",
                                Toast.LENGTH_LONG).show();
                        launchPlayer(cand);
                    });
                    return;
                }
                if (r.tier == LinkHealth.TIER_CONNECTS && firstConnects == null) {
                    firstConnects = cand;
                }
            }
            M3uParser.Channel fallback = firstConnects;
            runOnUiThread(() -> {
                adapter.notifyDataSetChanged();
                if (fallback != null) {
                    Toast.makeText(this, "Nothing ahead is proven — trying "
                            + fallback.displayName() + ", which at least answers.",
                            Toast.LENGTH_LONG).show();
                    launchPlayer(fallback);
                } else {
                    statusView.setTextColor(Sanctuary.WARN);
                    statusView.setText("None of the next " + candidates.size()
                            + " links answered either. They are marked ✗.");
                }
            });
        });
    }

    // ------------------------------------------------------------- verify

    private static final int VERIFY_CAP = 1000;

    /**
     * "Verify links": probe the set currently on screen (after filters)
     * in bounded batches — concurrency 8, 6s timeouts, capped — and
     * badge everything from what actually answered. The report is the
     * measured line: "X of Y answered — A working, B dead, C didn't answer."
     */
    private void verifyVisible() {
        if (verifying) return;
        List<LinkHealth.Target> targets = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (M3uParser.Channel c : displayChannels) {
            if (targets.size() >= VERIFY_CAP) break;
            if (LinkHealth.isRemote(c.url) && seen.add(c.url)) {
                targets.add(new LinkHealth.Target(c.url, c.userAgent));
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Nothing on screen to verify.", Toast.LENGTH_SHORT).show();
            return;
        }
        verifying = true;
        verifyBtn.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setMax(targets.size());
        progress.setProgress(0);
        progress.setVisibility(View.VISIBLE);
        boolean capped = displayChannels.size() > targets.size();
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setText("Verifying " + targets.size() + " links"
                + (capped ? " (the first " + VERIFY_CAP + " on screen)" : "") + "…");
        verifyExec.execute(() -> {
            int[] counts = LinkHealth.probeAll(targets, 8, 6000,
                    (t, r) -> HealthStore.recordQuiet(this, t.url, r),
                    (done, total, working, answered) -> {
                        if (done % 10 == 0 || done == total) {
                            runOnUiThread(() -> {
                                progress.setProgress(done);
                                statusView.setText("Verifying… " + done + "/" + total
                                        + " checked · " + answered + " answered · "
                                        + working + " working so far");
                            });
                        }
                    });
            HealthStore.save(this);
            int total = counts[0], answered = counts[1], plays = counts[2];
            int connects = counts[3], dead = counts[4];
            int silent = total - answered;
            runOnUiThread(() -> {
                verifying = false;
                verifyBtn.setEnabled(true);
                progress.setVisibility(View.GONE);
                progress.setIndeterminate(true);
                Haptics.goSign(this);   // the go sign you can FEEL (v0.5.0)
                rebuildDisplay();
                statusView.setTextColor(Sanctuary.TEAL);
                statusView.setText(answered + " of " + total + " answered — "
                        + plays + " play, " + connects + " connect only, "
                        + dead + " dead"
                        + (silent > 0 ? ", " + silent + " didn't answer" : "")
                        + ". Badges updated.");
            });
        });
    }

    // ------------------------------------------------------------- adapter

    private static final class Holder {
        TextView name, sub, star;
    }

    // ------------------------------------------ the channel scan

    /** The Scan button: a manual sweep — every link re-tested. */
    private void startScan() {
        ScanEngine.start(this, true);
        scanSeenDone = -1;
        updateScanLine();
    }

    /** The standing scan line: progress in plain words while
     *  the sweep runs; the last run's verified counts standing;
     *  the honest "not yet" before the first sweep. */
    private void updateScanLine() {
        if (scanLine == null) return;
        if (ScanEngine.isRunning()) {
            int[] p = ScanEngine.progress();
            scanLine.setText(String.format(Locale.US,
                    LangStore.tr(this, "scan_progress"), p[0], p[1]));
            return;
        }
        ChannelScan.Summary s = ScanStore.summary(this);
        if (s.lastScanMs <= 0) {
            scanLine.setText(LangStore.tr(this, "scan_none"));
        } else {
            String when = new SimpleDateFormat("MMM d, HH:mm", Locale.US)
                    .format(new Date(s.lastScanMs));
            String line = String.format(Locale.US,
                    LangStore.tr(this, "scan_summary"),
                    when, s.tested, s.live, s.removed);
            if (s.listed > 0) {
                // v0.18.0: listed links are counted as what they
                // are — on the shelf, not proven to play — never
                // folded into the live count.
                line += String.format(Locale.US,
                        LangStore.tr(this, "scan_listed_part"), s.listed);
            }
            scanLine.setText(line);
        }
    }

    /** As verdicts land, the shelf follows them — progressively,
     *  never a frozen room; the title count follows too. */
    private void tickScan() {
        boolean running = ScanEngine.isRunning();
        updateScanLine();
        int done = ScanEngine.progress()[0];
        boolean finished = scanWasRunning && !running;
        if ((running && scanSeenDone >= 0 && done - scanSeenDone >= 20)
                || finished) {
            scanSeenDone = done;
            if (!loading) {
                rebuildDisplay();
                rebuildTiles();
                refreshCountsLine();
            }
        } else if (running && scanSeenDone < 0) {
            scanSeenDone = done;
        }
        scanWasRunning = running;
    }

    /** Recompute the title's presented count after verdicts land. */
    private void refreshCountsLine() {
        if (countView == null || allChannels == null) return;
        int presented = 0;
        Map<String, ChannelScan.Rec> scanRecs = ScanStore.load(this);
        for (M3uParser.Channel c : allChannels) {
            if (ChannelScan.verdictOf(scanRecs, c.url)
                    != ChannelScan.V_DEAD) presented++;
        }
        countView.setText(presented + " "
                + LangStore.tr(this, "live_channels_suffix"));
    }

    private final class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return displayRows.size(); }
        @Override public Object getItem(int pos) { return displayRows.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public boolean isEnabled(int pos) {
            return displayRows.get(pos) instanceof M3uParser.Channel;
        }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int pos) {
            return displayRows.get(pos) instanceof String ? 0 : 1;
        }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            Object row = displayRows.get(pos);
            if (row instanceof String) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                tv.setText((String) row);
                tv.setTextColor(Sanctuary.GOLD_TEXT);
                tv.setTextSize(14);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
                tv.setPadding(dp(6), dp(10), dp(6), dp(4));
                tv.setBackgroundColor(Sanctuary.PANEL_HI);
                return tv;
            }
            M3uParser.Channel c = (M3uParser.Channel) row;
            LinearLayout ll;
            Holder h;
            if (convertView instanceof LinearLayout) {
                ll = (LinearLayout) convertView;
                h = (Holder) ll.getTag();
            } else {
                ll = new LinearLayout(MainActivity.this);
                ll.setOrientation(LinearLayout.HORIZONTAL);
                ll.setGravity(Gravity.CENTER_VERTICAL);
                ll.setPadding(dp(8), dp(8), dp(4), dp(8));
                h = new Holder();
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                h.name = new TextView(MainActivity.this);
                h.name.setTextColor(Sanctuary.INK);
                h.name.setTextSize(16);
                h.sub = new TextView(MainActivity.this);
                h.sub.setTextColor(Sanctuary.SUB);
                h.sub.setTextSize(12);
                texts.addView(h.name);
                texts.addView(h.sub);
                ll.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                h.star = new TextView(MainActivity.this);
                h.star.setTextSize(24);
                h.star.setPadding(dp(10), dp(4), dp(10), dp(4));
                ll.addView(h.star);
                ll.setTag(h);
            }
            // Health badge: from the persisted probe record only —
            // ✓ plays (proven to the segment), ~ connects only,
            // ✗ dead, no badge = never checked. No record, no claim.
            HealthStore.Health hh = HealthStore.get(MainActivity.this, c.url);
            h.name.setText(HealthStore.badgeSymbol(hh) + c.displayName());
            StringBuilder subLine = new StringBuilder(hh == null
                    ? "? Not checked yet"
                    : HealthStore.badgeText(hh, System.currentTimeMillis()));
            // The DEFINITION line (v0.6.0, field note #2c): what the
            // channel IS — the database's categories when it has
            // them, else the playlist's group; absent rather than
            // invented when neither knows.
            String def = ChannelNames.definitionFor(c);
            subLine.append("  ·  ").append(def.isEmpty() ? c.group : def);
            if (!c.language.isEmpty()) subLine.append("  ·  ").append(c.language);
            if (!c.country.isEmpty()) subLine.append("  ·  ").append(c.country);
            subLine.append("  ·  ").append(c.source);
            // Now/next from the filed guide (v0.11.0) — shown only
            // when this channel's guide was actually fetched and
            // parsed; absent otherwise, never invented.
            String guideLine = guideLineFor(c);
            h.sub.setText(guideLine.isEmpty() ? subLine.toString()
                    : guideLine + "\n" + subLine);
            // Every row speaks (v0.5.0 accessibility hooks): TalkBack
            // and Braille displays read the row through this label.
            ll.setContentDescription(c.displayName() + ". " + subLine);
            boolean fav = favorites.contains(c.url);
            h.star.setText(fav ? "★" : "☆");
            h.star.setContentDescription(fav
                    ? "Favorite — tap to remove" : "Tap to favorite");
            h.star.setTextColor(fav ? Sanctuary.GOLD : Sanctuary.FAINT);
            h.star.setOnClickListener(v -> {
                boolean nowFav = !favorites.contains(c.url);
                ChannelStore.setFavorite(MainActivity.this, c.url, nowFav);
                favorites = ChannelStore.getFavorites(MainActivity.this);
                rebuildDisplay();
            });
            return ll;
        }
    }
}
