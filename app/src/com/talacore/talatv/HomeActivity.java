package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The catalog home — the streaming-service face.
 *
 * v0.10.0 — the ON-DEMAND EXPANSION (James's order): LANGUAGE IS THE
 * FRONT DOOR. The catalogue is the TVmaze index, gathered onto the
 * phone page by page and filed by each show's own language, so picking
 * a language rebuilds the wall around that language's shows — their
 * countries as shelves (Spain and Mexico are two shelves of Spanish,
 * never one), their titles in their own names. Country chips scrub
 * inside the language; a country door stands beside it. Tiles say
 * honestly what they are: "On your shelves" only on evidence (a
 * loaded source, a file on this phone, or an Archive find a past
 * Sources visit actually made) — everything else is marked
 * "Catalog only". Proven-available tiles are never truncated; the
 * gathered counts are the phone's own, stated as gathered.
 */
public class HomeActivity extends Activity {

    private static final long CATALOG_TTL_MS = 12L * 3600L * 1000L;
    private static final int WALK_CHUNK = 12;     // index pages per walk
    private static final int GRID_CHUNK = 90;     // country grid growth
    private static final int SHELF_CAP = 40;      // preview cap per country shelf

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private ScrollView catalogScroll;
    private LinearLayout sectionsBox;
    private LinearLayout langChipRow;
    private HorizontalScrollView countryScroll;
    private LinearLayout countryChipRow;
    private HorizontalScrollView facetScroll;
    private LinearLayout facetChipRow;
    private ListView resultsList;
    private ListView myShowsList;
    private TextView myShowsEmpty;
    private TextView statusView;
    private TextView scanLine;
    private ProgressBar progress;
    private EditText searchBox;
    private Button catalogTab, myShowsTab;

    // v0.17.0 THE CHANNEL SCAN surface state: the ticker watches
    // the engine while this room is resumed — progress in plain
    // words, and the wall re-renders as verdicts land.
    private final Handler scanHandler = new Handler();
    private int scanSeenDone = -1;
    private boolean scanWasRunning = false;
    private final Runnable scanTick = new Runnable() {
        @Override public void run() {
            tickScan();
            scanHandler.postDelayed(this, 600);
        }
    };

    private List<TvMaze.Show> searchResults = new ArrayList<>();
    private List<TvMaze.Show> watchlist = new ArrayList<>();
    private boolean loading = false;

    // v0.11.1 findability: the facet cut + sort standing on the
    // wall right now (persisted, like the wall's language/country).
    // "" = no cut on that axis; CatalogIndex.NOT_LISTED is the
    // "lists none" value. resultsMode steers the results list:
    // "shows" (title search) | "people" | "credits" (actor search).
    private final CatalogIndex.FacetSet facets = new CatalogIndex.FacetSet();
    private CatalogIndex.SortMode facetSort = CatalogIndex.SortMode.BEST;
    private String resultsMode = "shows";
    private List<TvMaze.Person> peopleResults = new ArrayList<>();
    private List<CatalogIndex.Record> creditResults = new ArrayList<>();

    // the gathered index + the wall's stance
    private final Map<Integer, CatalogIndex.Record> gathered = new LinkedHashMap<>();
    private CatalogIndex.State indexState = new CatalogIndex.State();
    private List<TvMaze.Show> airingToday = new ArrayList<>();
    private Availability.Shelf shelf;
    private final Set<Integer> availIds = new HashSet<>();
    // v0.11.0: holds the wall's TVmaze LANGUAGE NAME ("" = all
    // languages) — the wall was decoupled from the interface tables,
    // so any language TVmaze carries can stand here, spoken or not.
    // (The field kept its old name to keep the diff honest and small;
    // the persisted pref is the new "wall_lang_name", migrated below.)
    private String wallLangCode = "";   // "" = all languages

    /** A wall pick handed over by LanguageIndexActivity. */
    public static String pendingWallLanguage = "";
    private String wallCountry = "";    // "" = every country shelf
    private int wallGridShown = GRID_CHUNK;
    private final Map<Integer, Boolean> availMemo = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.11.0 migration: the wall key used to be an interface
        // language CODE ("fa"); it is a TVmaze language NAME now
        // ("Persian"). A legacy code translates through the facet
        // bridge exactly once; the name is what gets kept.
        wallLangCode = getPreferences(MODE_PRIVATE).getString("wall_lang_name", null);
        if (wallLangCode == null) {
            String legacy = getPreferences(MODE_PRIVATE)
                    .getString("wall_lang_code", "");
            String facet = UiStrings.facetFor(legacy);
            // Every real interface code has a facet; a longer legacy
            // value can only already be a name (defensive), anything
            // else falls back to All rather than a phantom wall.
            wallLangCode = !facet.isEmpty() ? facet
                    : (legacy.length() > 3 ? legacy : "");
            getPreferences(MODE_PRIVATE).edit()
                    .putString("wall_lang_name", wallLangCode).apply();
        }
        wallCountry = getPreferences(MODE_PRIVATE).getString("wall_country", "");

        // v0.11.1: restore the standing facet cut + sort. The
        // "not listed" value rides prefs as plain text (see
        // facetPrefRead) — the sentinel itself never touches XML.
        facets.genre = facetPrefRead("facet_genre");
        facets.type = facetPrefRead("facet_type");
        facets.status = facetPrefRead("facet_status");
        facets.yearLo = getPreferences(MODE_PRIVATE).getInt("facet_year_lo", -1);
        facets.yearHi = getPreferences(MODE_PRIVATE).getInt("facet_year_hi", -1);
        facets.yearNotListed = getPreferences(MODE_PRIVATE)
                .getBoolean("facet_year_nl", false);
        int sortOrd = getPreferences(MODE_PRIVATE).getInt("facet_sort", 0);
        CatalogIndex.SortMode[] modes = CatalogIndex.SortMode.values();
        if (sortOrd >= 0 && sortOrd < modes.length) facetSort = modes[sortOrd];

        // v0.17.0 THE SANCTUARY LAYOUT: the room itself is the
        // panels' ground — deep charcoal falling to near-black,
        // teal breathing at the top; gold and teal are the only
        // light in it (see Sanctuary).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        int pad = dp(Sanctuary.PAGE_PAD_DP);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Sandy");
        title.setTextSize(30);
        Sanctuary.goldText(title);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button settingsBtn = new Button(this);
        settingsBtn.setText(LangStore.tr(this, "home_settings"));
        settingsBtn.setContentDescription(LangStore.tr(this, "home_settings"));
        Sanctuary.styleButton(this, settingsBtn, false);
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        titleRow.addView(settingsBtn);
        Button refreshBtn = new Button(this);
        refreshBtn.setText(LangStore.tr(this, "home_refresh"));
        refreshBtn.setContentDescription(LangStore.tr(this, "home_refresh"));
        Sanctuary.styleButton(this, refreshBtn, false);
        refreshBtn.setOnClickListener(v -> loadCatalog(true));
        titleRow.addView(refreshBtn);
        // v0.17.0 THE CHANNEL SCAN: the sweep stands on the home
        // surface too — it pretests the catalog's links as well.
        Button scanBtn = new Button(this);
        scanBtn.setText(LangStore.tr(this, "scan_action"));
        scanBtn.setContentDescription(LangStore.tr(this, "scan_action"));
        Sanctuary.styleButton(this, scanBtn, false);
        scanBtn.setOnClickListener(v -> startScan());
        titleRow.addView(scanBtn);
        root.addView(titleRow);
        View rule = new View(this);
        rule.setBackground(Sanctuary.rule());
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
        ruleLp.topMargin = dp(8);
        ruleLp.bottomMargin = dp(10);
        root.addView(rule, ruleLp);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        catalogTab = tabButton(LangStore.tr(this, "home_catalog"), v -> showPane(0));
        myShowsTab = tabButton(LangStore.tr(this, "home_my_shows"), v -> showPane(1));
        Button liveTab = tabButton(LangStore.tr(this, "home_live_tv"), v ->
                startActivity(new Intent(this, MainActivity.class)));
        tabRow.addView(catalogTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(myShowsTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(liveTab, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabRow);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- catalog pane
        catalogScroll = new ScrollView(this);
        LinearLayout catalogBox = new LinearLayout(this);
        catalogBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchBox = new EditText(this);
        searchBox.setHint(LangStore.tr(this, "home_search_shows_hint"));
        searchBox.setContentDescription(LangStore.tr(this, "home_search_shows_hint"));
        searchBox.setSingleLine(true);
        searchBox.setTextColor(Sanctuary.INK);
        searchBox.setHintTextColor(Sanctuary.SUB);
        searchBox.setBackground(Sanctuary.field(this));
        searchBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button searchBtn = new Button(this);
        searchBtn.setText(LangStore.tr(this, "home_search_btn"));
        searchBtn.setContentDescription(LangStore.tr(this, "home_search_btn"));
        Sanctuary.styleButton(this, searchBtn, true);
        searchBtn.setOnClickListener(v -> doSearch());
        searchRow.addView(searchBtn);
        // v0.11.1: the same query box, searched as a person
        Button actorBtn = new Button(this);
        actorBtn.setText(LangStore.tr(this, "search_by_actor"));
        actorBtn.setContentDescription(LangStore.tr(this, "search_by_actor"));
        Sanctuary.styleButton(this, actorBtn, false);
        actorBtn.setOnClickListener(v -> doActorSearch());
        searchRow.addView(actorBtn);
        catalogBox.addView(searchRow);

        // v0.14.0: the Story door — a room in the house where
        // children are guests. It stands big and early on the home
        // surface, in the open like every other door.
        Button storyBtn = new Button(this);
        storyBtn.setText("📖  " + LangStore.tr(this, "home_stories"));
        storyBtn.setContentDescription(LangStore.tr(this, "home_stories"));
        storyBtn.setTextSize(21);
        storyBtn.setAllCaps(false);
        storyBtn.setMinHeight(dp(68));
        // The Story door leads the doors: the one gold pill.
        storyBtn.setBackground(Sanctuary.glassButton(this, true));
        storyBtn.setTextColor(Sanctuary.ON_GOLD);
        storyBtn.setOnClickListener(v ->
                startActivity(new Intent(this, StoryActivity.class)));
        LinearLayout.LayoutParams storyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        storyLp.topMargin = dp(10);
        catalogBox.addView(storyBtn, storyLp);

        // v0.15.0: THE TWO DOORS — Speak For Me and Listen For Me,
        // standing big and early beside the Story door, with the
        // Translator between them: a voice for a person who
        // cannot speak, ears that turn speech into big text, and
        // the room where two languages meet turn by turn.
        Button speakBtn = doorButton("🗣  " + LangStore.tr(this, "door_speak"),
                v -> startActivity(new Intent(this, SpeakActivity.class)));
        catalogBox.addView(speakBtn, storyLp);
        Button listenBtn = doorButton("👂  " + LangStore.tr(this, "door_listen"),
                v -> startActivity(new Intent(this, ListenActivity.class)));
        catalogBox.addView(listenBtn, storyLp);
        Button translatorBtn = doorButton("🌐  " + LangStore.tr(this, "door_translator"),
                v -> startActivity(new Intent(this, TranslatorActivity.class)));
        catalogBox.addView(translatorBtn, storyLp);

        // v0.16.0: THE READER — find a book on the open shelves
        // (Archive, Gutenberg, Open Library, LibriVox) and read
        // it on screen, in big print, in the same one app.
        Button readerBtn = doorButton("📚  " + LangStore.tr(this, "door_reader"),
                v -> startActivity(new Intent(this, BookSearchActivity.class)));
        catalogBox.addView(readerBtn, storyLp);

        // v0.18.0: ARCHIVE FILMS — the two shelves the census
        // bound (Wikimedia Commons; the National Film Archive
        // of Japan's animated classics), each standing behind
        // its verification receipt.
        Button filmsBtn = doorButton("🎬  " + LangStore.tr(this, "door_films"),
                v -> startActivity(new Intent(this, ArchiveFilmsActivity.class)));
        catalogBox.addView(filmsBtn, storyLp);

        // v0.10.0: the language door — a chip row over the whole wall
        HorizontalScrollView langScroll = new HorizontalScrollView(this);
        langScroll.setHorizontalScrollBarEnabled(false);
        langChipRow = new LinearLayout(this);
        langChipRow.setOrientation(LinearLayout.HORIZONTAL);
        langScroll.addView(langChipRow);
        LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        langLp.topMargin = dp(6);
        catalogBox.addView(langScroll, langLp);

        // the country scrub — lives inside a chosen language
        countryScroll = new HorizontalScrollView(this);
        countryScroll.setHorizontalScrollBarEnabled(false);
        countryScroll.setVisibility(View.GONE);
        countryChipRow = new LinearLayout(this);
        countryChipRow.setOrientation(LinearLayout.HORIZONTAL);
        countryScroll.addView(countryChipRow);
        catalogBox.addView(countryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // v0.11.1: the facet bar — one button per axis, each labeled
        // with its standing pick; the option lists inside are cut
        // from the wall's own pool at render time.
        facetScroll = new HorizontalScrollView(this);
        facetScroll.setHorizontalScrollBarEnabled(false);
        facetChipRow = new LinearLayout(this);
        facetChipRow.setOrientation(LinearLayout.HORIZONTAL);
        facetScroll.addView(facetChipRow);
        catalogBox.addView(facetScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        progress.setProgressTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        progress.setIndeterminateTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        catalogBox.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        statusView = new TextView(this);
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setTextSize(12);
        catalogBox.addView(statusView);

        // v0.17.0: the scan's standing line — last sweep's
        // verified counts, or its progress while it runs.
        scanLine = new TextView(this);
        scanLine.setTextColor(Sanctuary.SUB);
        scanLine.setTextSize(12);
        scanLine.setPadding(0, dp(2), 0, 0);
        catalogBox.addView(scanLine);

        sectionsBox = new LinearLayout(this);
        sectionsBox.setOrientation(LinearLayout.VERTICAL);
        catalogBox.addView(sectionsBox);

        Button vodBtn = new Button(this);
        vodBtn.setText(LangStore.tr(this, "home_movies_vod"));
        Sanctuary.styleButton(this, vodBtn, false);
        vodBtn.setOnClickListener(v ->
                startActivity(new Intent(this, VodActivity.class)));
        LinearLayout.LayoutParams vodLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vodLp.topMargin = dp(16);
        catalogBox.addView(vodBtn, vodLp);

        // v0.3.0: the third door — the videos already on this phone
        Button libBtn = new Button(this);
        libBtn.setText(LangStore.tr(this, "home_my_library"));
        Sanctuary.styleButton(this, libBtn, false);
        libBtn.setOnClickListener(v ->
                startActivity(new Intent(this, LibraryActivity.class)));
        LinearLayout.LayoutParams libLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        libLp.topMargin = dp(8);
        catalogBox.addView(libBtn, libLp);

        // v0.10.0: the country door, standing on its own
        Button countryBtn = new Button(this);
        countryBtn.setText("Browse by country — every language");
        countryBtn.setContentDescription(countryBtn.getText());
        Sanctuary.styleButton(this, countryBtn, false);
        countryBtn.setOnClickListener(v ->
                startActivity(new Intent(this, CountryIndexActivity.class)));
        LinearLayout.LayoutParams countryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countryLp.topMargin = dp(8);
        catalogBox.addView(countryBtn, countryLp);
        catalogScroll.addView(catalogBox);
        content.addView(catalogScroll);

        // ---- search results pane
        resultsList = new ListView(this);
        resultsList.setBackgroundColor(Sanctuary.GROUND);
        resultsList.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        resultsList.setDividerHeight(1);
        resultsList.setVisibility(View.GONE);
        resultsList.setOnItemClickListener((p, v, pos, id) -> {
            if ("people".equals(resultsMode)) {
                if (pos < peopleResults.size()) openPerson(peopleResults.get(pos));
            } else if ("credits".equals(resultsMode)) {
                if (pos < creditResults.size()) openShow(creditResults.get(pos));
            } else if (pos < searchResults.size()) {
                openShow(searchResults.get(pos));
            }
        });
        content.addView(resultsList);

        // ---- my shows pane
        myShowsList = new ListView(this);
        myShowsList.setBackgroundColor(Sanctuary.GROUND);
        myShowsList.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        myShowsList.setDividerHeight(1);
        myShowsList.setVisibility(View.GONE);
        myShowsList.setOnItemClickListener((p, v, pos, id) ->
                openShow(watchlist.get(pos)));
        content.addView(myShowsList);
        myShowsEmpty = new TextView(this);
        myShowsEmpty.setText("No shows saved yet.\n\nOpen any show and tap “+ My Shows” — "
                + "it will wait for you here, with your place kept.");
        myShowsEmpty.setTextColor(Sanctuary.MUTED);
        myShowsEmpty.setTextSize(15);
        myShowsEmpty.setPadding(dp(8), dp(16), dp(8), 0);
        myShowsEmpty.setVisibility(View.GONE);
        content.addView(myShowsEmpty);

        setContentView(root);
        showPane(0);
        loadCatalog(false);
        // Auto-fresh also starts here (the boot screen starts it too;
        // FreshVerify's guards make the double trigger a no-op).
        FreshVerify.maybeRun(this,
                () -> ChannelStore.loadCache(getApplicationContext()), null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScanLine();
        scanHandler.removeCallbacks(scanTick);
        scanHandler.post(scanTick);
        if (!pendingWallLanguage.isEmpty()) {
            String pick = pendingWallLanguage;
            pendingWallLanguage = "";
            pickLanguage(pick);
        }
        TimerReconcile.run(getApplicationContext());
        watchlist = ChannelStore.getWatchlist(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1,
                new ArrayList<String>()) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                TvMaze.Show s = watchlist.get(pos);
                ((TextView) v.findViewById(android.R.id.text1)).setText(s.name);
                ((TextView) v.findViewById(android.R.id.text1)).setTextColor(Sanctuary.INK);
                String meta = String.join(" · ", s.genres)
                        + (s.language.isEmpty() ? "" : "  ·  " + s.language)
                        + (s.status.isEmpty() ? "" : "  ·  " + s.status);
                ((TextView) v.findViewById(android.R.id.text2)).setText(meta);
                ((TextView) v.findViewById(android.R.id.text2))
                        .setTextColor(Sanctuary.SUB);
                return v;
            }
            @Override public int getCount() { return watchlist.size(); }
        };
        myShowsList.setAdapter(ad);
        if (myShowsList.getVisibility() == View.VISIBLE) {
            myShowsEmpty.setVisibility(watchlist.isEmpty() ? View.VISIBLE : View.GONE);
            myShowsList.setVisibility(watchlist.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        scanHandler.removeCallbacks(scanTick);
        super.onPause();
    }

    // ------------------------------------------ the channel scan

    /** The Scan button: a manual sweep — every link re-tested. */
    private void startScan() {
        ScanEngine.start(this, true);
        scanSeenDone = -1;
        updateScanLine();
    }

    /** The standing scan line: progress while the sweep runs,
     *  the last run's verified counts when it stands, the honest
     *  "not yet" before the first one. */
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

    /** While this room is up: keep the scan line truthful, and
     *  as verdicts land, let the wall's evidence follow them —
     *  shelves update progressively, never a frozen room. */
    private void tickScan() {
        boolean running = ScanEngine.isRunning();
        updateScanLine();
        int done = ScanEngine.progress()[0];
        boolean finished = scanWasRunning && !running;
        if ((running && scanSeenDone >= 0 && done - scanSeenDone >= 20)
                || finished) {
            scanSeenDone = done;
            exec.execute(() -> {
                shelf = CatalogStore.shelf(HomeActivity.this);
                computeAvailIds();
                runOnUiThread(() -> {
                    availMemo.clear();
                    if (!loading) renderWall();
                });
            });
        } else if (running && scanSeenDone < 0) {
            scanSeenDone = done;
        }
        scanWasRunning = running;
    }

    /** A big front door button, Story-door sized (v0.15.0).
     *  v0.17.0: smoked glass with a lit edge, ink text. */
    private Button doorButton(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setContentDescription(label);
        b.setTextSize(21);
        b.setAllCaps(false);
        b.setMinHeight(dp(68));
        b.setBackground(Sanctuary.glassButton(this, false));
        b.setTextColor(Sanctuary.INK);
        b.setOnClickListener(onClick);
        return b;
    }

    private Button tabButton(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        Sanctuary.styleButton(this, b, false);
        b.setOnClickListener(onClick);
        return b;
    }

    private void showPane(int which) {
        catalogScroll.setVisibility(which == 0 ? View.VISIBLE : View.GONE);
        resultsList.setVisibility(View.GONE);
        boolean shows = which == 1;
        myShowsList.setVisibility(shows && !watchlist.isEmpty() ? View.VISIBLE : View.GONE);
        myShowsEmpty.setVisibility(shows && watchlist.isEmpty() ? View.VISIBLE : View.GONE);
        catalogTab.setEnabled(which != 0);
        myShowsTab.setEnabled(which != 1);
        // The standing tab wears the gold; the other stays glass.
        catalogTab.setBackground(Sanctuary.glassButton(this, which == 0));
        catalogTab.setTextColor(which == 0 ? Sanctuary.ON_GOLD : Sanctuary.INK);
        myShowsTab.setBackground(Sanctuary.glassButton(this, which == 1));
        myShowsTab.setTextColor(which == 1 ? Sanctuary.ON_GOLD : Sanctuary.INK);
    }

    private void openShow(TvMaze.Show s) {
        Intent i = new Intent(this, ShowActivity.class);
        i.putExtra("showId", s.id);
        i.putExtra("showName", s.name);
        i.putExtra("showImage", s.imageMedium);
        startActivity(i);
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

    // ------------------------------------------------- the index walk

    private File catalogFile(String name) {
        File dir = new File(getFilesDir(), "catalog");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private void loadCatalog(boolean force) {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setText(force ? "Refreshing the catalog…"
                : "Loading the catalog…");
        exec.execute(() -> {
            Exception failure = null;
            try {
                gathered.clear();
                gathered.putAll(CatalogStore.loadGathered(this));
                indexState = CatalogStore.loadState(this);
                CatalogIndex.recount(indexState, gathered.values());

                // v0.11.1 format heal: records gathered before the
                // TYPE field existed carry no type. A format-1 state
                // earns ONE re-walk from page 0 — mergeInto replaces
                // records by id as pages land, so the type arrives
                // honestly, from TVmaze, page by page; until a page
                // is re-walked its records read "not listed" on the
                // type facet, never a guess. The cache keeps serving
                // the wall the whole time.
                boolean typeHeal = false;
                if (indexState.format < CatalogIndex.State.FORMAT_TYPE
                        && !gathered.isEmpty()) {
                    indexState.format = CatalogIndex.State.FORMAT_TYPE;
                    indexState.pagesWalked = 0;
                    indexState.complete = false;
                    typeHeal = true;
                    CatalogStore.saveState(this, indexState);
                }

                // today's schedule (kept from cache inside its TTL)
                File sf = catalogFile("schedule.json");
                String scheduleJson;
                if (!force && sf.exists()
                        && System.currentTimeMillis() - sf.lastModified() < CATALOG_TTL_MS) {
                    scheduleJson = readFile(sf);
                } else {
                    String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(new Date());
                    try {
                        scheduleJson = Net.getString(TvMaze.scheduleUrl("US", today));
                        writeFile(sf, scheduleJson);
                    } catch (Exception e) {
                        scheduleJson = readFile(sf); // stale beats none
                        if (scheduleJson == null && gathered.isEmpty()) throw e;
                    }
                }
                airingToday = scheduleJson == null ? new ArrayList<>()
                        : TvMaze.parseScheduleShows(scheduleJson);

                // the walk: a first chunk on a fresh phone, a deepening
                // chunk on Refresh — afterwards the wall flies offline.
                if (gathered.isEmpty() || force || typeHeal) {
                    walkChunk(WALK_CHUNK);
                }
                shelf = CatalogStore.shelf(this);
                computeAvailIds();
            } catch (Exception e) {
                failure = e;
            }
            final Exception fFailure = failure;
            runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                availMemo.clear();
                if (fFailure != null && gathered.isEmpty() && airingToday.isEmpty()) {
                    statusView.setTextColor(Sanctuary.WARN);
                    statusView.setText("Couldn't load the catalog (" + shortErr(fFailure)
                            + "). Check the connection and tap Refresh.");
                    return;
                }
                renderWall();
            });
        });
    }

    /**
     * Walk up to maxPages more of the TVmaze index, filing every show
     * into its language bucket as we go. Buckets + walk state are
     * saved page by page, so quitting mid-walk loses nothing. A 404
     * (or an empty page) is the index honestly ending — not an error.
     */
    private void walkChunk(int maxPages) {
        // any walk this version runs writes the format-2 state:
        // its records carry type, fresh or re-gathered alike
        indexState.format = CatalogIndex.State.FORMAT_TYPE;
        for (int i = 0; i < maxPages && !indexState.complete; i++) {
            int page = indexState.pagesWalked;
            String json;
            try {
                json = Net.getString(TvMaze.showsPageUrl(page));
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("404")) {
                    indexState.complete = true;
                }
                break; // connection trouble: keep what we have
            }
            List<TvMaze.Show> shows;
            try {
                shows = TvMaze.parseShows(json);
            } catch (Exception e) {
                break;
            }
            if (shows.isEmpty()) {
                indexState.complete = true;
                break;
            }
            Set<String> touched = new HashSet<>();
            for (TvMaze.Show s : shows) touched.add(s.language == null ? "" : s.language);
            CatalogIndex.mergeInto(gathered, shows);
            indexState.pagesWalked = page + 1;
            for (String lang : touched) {
                CatalogStore.saveBucket(this, gathered, lang);
            }
            CatalogStore.saveState(this, indexState);
        }
        CatalogIndex.recount(indexState, gathered.values());
        CatalogStore.saveState(this, indexState);
    }

    private void gatherMore() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        statusView.setText("Gathering more of the catalog…");
        exec.execute(() -> {
            int before = indexState.pagesWalked;
            walkChunk(WALK_CHUNK + 3);
            computeAvailIds();
            final int walked = indexState.pagesWalked - before;
            runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (walked == 0 && !indexState.complete) {
                    Toast.makeText(this,
                            "The catalog didn't answer — check the connection.",
                            Toast.LENGTH_LONG).show();
                }
                renderWall();
            });
        });
    }

    // ------------------------------------------------------------- wall

    /** Precompute shelf-evidence verdicts off the UI thread: with a
     *  full index gathered this pass touches every record once, so
     *  rendering never pays it per tile. */
    private void computeAvailIds() {
        availIds.clear();
        if (shelf == null) return;
        for (CatalogIndex.Record r : gathered.values()) {
            if (shelf.carries(r.name)) availIds.add(r.id);
        }
    }

    private boolean isAvailable(CatalogIndex.Record r) {
        Boolean memo = availMemo.get(r.id);
        if (memo != null) return memo;
        boolean v = availIds.contains(r.id)
                || ChannelStore.isArchiveProven(this, r.id);
        availMemo.put(r.id, v);
        return v;
    }

    private List<CatalogIndex.Record> availableOf(List<CatalogIndex.Record> pool) {
        List<CatalogIndex.Record> out = new ArrayList<>();
        for (CatalogIndex.Record r : pool) if (isAvailable(r)) out.add(r);
        CatalogIndex.sortShows(out);
        return out;
    }

    private String walkStateText() {
        if (indexState.complete) {
            return " The whole TVmaze index is gathered on this phone.";
        }
        return " The index walk has gathered " + indexState.pagesWalked
                + " pages so far — “Gather more” at the bottom walks further.";
    }

    /** The wall stands in LIST MODE when a facet cut or a
     *  non-default sort is standing: the pool flattens into one
     *  cut, sorted, growing grid instead of the curated shelves.
     *  Clearing both brings the shelves back. */
    private boolean listMode() {
        return facets.active() || facetSort != CatalogIndex.SortMode.BEST;
    }

    private void renderWall() {
        buildLangChips();
        sectionsBox.removeAllViews();
        List<CatalogIndex.Record> all = new ArrayList<>(gathered.values());

        if (wallLangCode.isEmpty()) {
            countryScroll.setVisibility(View.GONE);
            buildFacetBar(all);
            if (listMode()) {
                statusView.setTextColor(Sanctuary.MUTED);
                statusView.setText(facetSummary(all));
                addFacetGrid("Catalog", all);
                addGatherMoreIfNeeded();
                return;
            }
            statusView.setTextColor(Sanctuary.MUTED);
            statusView.setText("Catalog — " + gathered.size()
                    + " shows gathered from TVmaze." + walkStateText()
                    + " Pick a language above for its own wall.");
            List<CatalogIndex.Record> airing = new ArrayList<>();
            for (TvMaze.Show s : airingToday) airing.add(CatalogIndex.fromShow(s));
            List<CatalogIndex.Record> avail = availableOf(all);
            // v0.17.0: THE HERO — one large featured presence at
            // the top of the wall, streamer-style. Its honesty is
            // the house law unchanged: only a proven-available
            // show with real art may stand here; when none does,
            // no hero stands at all — never a dressed-up guess.
            CatalogIndex.Record heroRec = null;
            for (CatalogIndex.Record r : airing) {
                if (!r.image.isEmpty() && isAvailable(r)) { heroRec = r; break; }
            }
            if (heroRec == null) {
                for (CatalogIndex.Record r : avail) {
                    if (!r.image.isEmpty()) { heroRec = r; break; }
                }
            }
            if (heroRec != null) addHero(heroRec);
            addSection("Airing today", airing, 20);
            if (!avail.isEmpty()) {
                addSection(LangStore.tr(this, "wall_available"), avail,
                        Integer.MAX_VALUE);
            }
            List<CatalogIndex.Record> rated = new ArrayList<>();
            for (CatalogIndex.Record r : all) if (r.rating > 0) rated.add(r);
            CatalogIndex.sortShows(rated);
            addSection("Top rated", rated, 24);
            Map<String, List<CatalogIndex.Record>> byGenre = new HashMap<>();
            for (CatalogIndex.Record r : all) {
                for (String g : r.genres) {
                    byGenre.computeIfAbsent(g, k -> new ArrayList<>()).add(r);
                }
            }
            List<Map.Entry<String, List<CatalogIndex.Record>>> genreRows =
                    new ArrayList<>(byGenre.entrySet());
            Collections.sort(genreRows, (a, b) -> b.getValue().size() - a.getValue().size());
            for (Map.Entry<String, List<CatalogIndex.Record>> e : genreRows) {
                if (e.getValue().size() < 4) continue;
                List<CatalogIndex.Record> members = new ArrayList<>(e.getValue());
                CatalogIndex.sortShows(members);
                addSection(e.getKey(), members, 16);
            }
        } else {
            String langName = wallLangCode; // a TVmaze name since v0.11.0
            List<CatalogIndex.Record> bucket = CatalogIndex.bucketOf(all, langName);
            LinkedHashMap<String, List<CatalogIndex.Record>> shelves =
                    CatalogIndex.shelves(bucket, homeCountryName());
            if (!shelves.containsKey(wallCountry)) wallCountry = "";
            buildCountryChips(shelves, bucket.size());
            countryScroll.setVisibility(shelves.size() > 1
                    ? View.VISIBLE : View.GONE);
            int availCount = availableOf(bucket).size();
            statusView.setTextColor(Sanctuary.MUTED);
            statusView.setText(langName + " — " + bucket.size() + " shows gathered"
                    + (availCount > 0 ? " · " + availCount + " "
                        + LangStore.tr(this, "wall_available") : "")
                    + "." + walkStateText());

            // v0.11.1: the facet bar's pool is the wall you're
            // standing in — this language, narrowed to the picked
            // country when one is picked.
            List<CatalogIndex.Record> basePool = wallCountry.isEmpty()
                    ? bucket : shelves.get(wallCountry);
            if (basePool == null) basePool = new ArrayList<>();
            buildFacetBar(basePool);
            if (listMode()) {
                statusView.setText(facetSummary(basePool));
                addFacetGrid(wallCountry.isEmpty() ? langName
                        : langName + " · " + wallCountry, basePool);
            } else if (!wallCountry.isEmpty()) {
                addGridSection(wallCountry, shelves.get(wallCountry));
            } else {
                List<CatalogIndex.Record> avail = availableOf(bucket);
                if (!avail.isEmpty()) {
                    // proven-available tiles: present in full, never capped
                    addSection(LangStore.tr(this, "wall_available"), avail,
                            Integer.MAX_VALUE);
                }
                for (Map.Entry<String, List<CatalogIndex.Record>> e : shelves.entrySet()) {
                    addSection(e.getKey(), e.getValue(), SHELF_CAP);
                    if (e.getValue().size() > SHELF_CAP) {
                        addSeeAllButton(langName, e.getKey(), e.getValue().size());
                    }
                }
                if (bucket.isEmpty()) {
                    addTextLine("Nothing in " + langName + " has been gathered yet — "
                            + "the index walk hasn't reached it. “Gather more” below "
                            + "walks further; a connection is needed to gather.");
                }
            }
        }

        addGatherMoreIfNeeded();
    }

    /** The hero (v0.17.0): the featured presence at the wall's
     *  top — real art, big type, the availability mark, inside a
     *  smoked-glass frame. The caller guarantees the record is
     *  proven available and carries art. */
    private void addHero(CatalogIndex.Record r) {
        FrameLayout hero = new FrameLayout(this);
        hero.setBackground(Sanctuary.glassPanel(this));
        hero.setFocusable(true);
        hero.setClickable(true);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(196)));
        Sanctuary.roundClip(this, img, Sanctuary.PANEL_RADIUS_DP);
        ImageCache.load(this, r.image, img);
        hero.addView(img);
        LinearLayout cap = new LinearLayout(this);
        cap.setOrientation(LinearLayout.VERTICAL);
        cap.setBackgroundColor(Sanctuary.OVERLAY);
        cap.setPadding(dp(14), dp(10), dp(14), dp(12));
        TextView name = new TextView(this);
        name.setText(r.name);
        name.setTextColor(Sanctuary.INK);
        name.setTextSize(24);
        name.setTypeface(name.getTypeface(), Typeface.BOLD);
        name.setMaxLines(1);
        cap.addView(name);
        TextView mark = new TextView(this);
        StringBuilder meta = new StringBuilder("✓ ")
                .append(LangStore.tr(this, "wall_available"));
        int y = CatalogIndex.yearOf(r);
        if (y >= 0) meta.append("  ·  ").append(y);
        if (!r.genres.isEmpty()) {
            meta.append("  ·  ").append(String.join(" · ", r.genres));
        }
        mark.setText(meta.toString());
        mark.setTextColor(Sanctuary.TEAL);
        mark.setTextSize(13);
        mark.setMaxLines(1);
        cap.addView(mark);
        hero.addView(cap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        hero.setContentDescription(r.name + " — "
                + LangStore.tr(this, "wall_available"));
        hero.setOnClickListener(v -> openShow(r));
        Sanctuary.focusPop(hero);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(6);
        sectionsBox.addView(hero, lp);
    }

    private void addGatherMoreIfNeeded() {
        if (!indexState.complete) {
            Button more = new Button(this);
            more.setText("Gather more of the catalog");
            more.setContentDescription(more.getText());
            Sanctuary.styleButton(this, more, false);
            more.setOnClickListener(v -> gatherMore());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(16);
            sectionsBox.addView(more, lp);
        }
    }

    private String homeCountryName() {
        try {
            return Locale.getDefault().getDisplayCountry(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private void buildLangChips() {
        langChipRow.removeAllViews();
        Button all = chip("All · " + gathered.size(), wallLangCode.isEmpty());
        all.setOnClickListener(v -> pickLanguage(""));
        langChipRow.addView(all);
        Set<String> spokenNames = new HashSet<>();
        for (String[] lang : UiStrings.LANGUAGES) {
            String code = lang[0];
            String name = lang[1];
            String tvName = CatalogIndex.languageNameForCode(code);
            spokenNames.add(tvName);
            int count = indexState.counts.getOrDefault(tvName, 0);
            Button b = chip(name + " · " + count, tvName.equals(wallLangCode));
            b.setOnClickListener(v -> pickLanguage(tvName));
            langChipRow.addView(b);
        }
        // A wall in a language Sandy doesn't speak still shows its
        // own chip while it stands, in its own script where known.
        if (!wallLangCode.isEmpty() && !spokenNames.contains(wallLangCode)) {
            String disp = LangRegistry.nativeNameFor(wallLangCode);
            if (disp.isEmpty()) disp = wallLangCode;
            int count = indexState.counts.getOrDefault(wallLangCode, 0);
            Button b = chip(disp + " · " + count, true);
            b.setOnClickListener(v -> pickLanguage(wallLangCode));
            langChipRow.addView(b);
        }
        Button more = chip(LangStore.tr(this, "home_more_languages") + " ▸", false);
        more.setOnClickListener(v -> startActivity(
                new Intent(this, LanguageIndexActivity.class)));
        langChipRow.addView(more);
    }

    private void pickLanguage(String tvName) {
        wallLangCode = tvName;
        wallCountry = "";
        wallGridShown = GRID_CHUNK;
        availMemo.clear();
        getPreferences(MODE_PRIVATE).edit()
                .putString("wall_lang_name", tvName)
                .putString("wall_country", "")
                .apply();
        renderWall();
    }

    private void buildCountryChips(
            LinkedHashMap<String, List<CatalogIndex.Record>> shelves, int total) {
        countryChipRow.removeAllViews();
        Button all = chip("All countries · " + total, wallCountry.isEmpty());
        all.setOnClickListener(v -> pickCountry(""));
        countryChipRow.addView(all);
        for (Map.Entry<String, List<CatalogIndex.Record>> e : shelves.entrySet()) {
            Button b = chip(e.getKey() + " · " + e.getValue().size(),
                    e.getKey().equals(wallCountry));
            String shelfName = e.getKey();
            b.setOnClickListener(v -> pickCountry(shelfName));
            countryChipRow.addView(b);
        }
    }

    private void pickCountry(String shelfName) {
        wallCountry = shelfName;
        wallGridShown = GRID_CHUNK;
        getPreferences(MODE_PRIVATE).edit()
                .putString("wall_country", shelfName).apply();
        renderWall();
    }

    private Button chip(String label, boolean selected) {
        Button b = new Button(this);
        b.setText(label);
        b.setEnabled(!selected);
        Sanctuary.styleChip(this, b, selected);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private void addSection(String title, List<CatalogIndex.Record> shows, int cap) {
        if (shows == null || shows.isEmpty()) return;
        TextView header = new TextView(this);
        header.setText(title);
        header.setTextColor(Sanctuary.GOLD_TEXT);
        header.setTextSize(17);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setPadding(0, dp(Sanctuary.SECTION_GAP_DP), 0, dp(8));
        sectionsBox.addView(header);

        // v0.17.0: every rail is a windowed PosterRail — tiles
        // recycle through a pool instead of being built whole,
        // however long the shelf runs (FLUID, James's criterion).
        final List<CatalogIndex.Record> railShows = shows;
        final int count = Math.min(cap, shows.size());
        final int stride = dp(116);
        final PosterRail rail = new PosterRail(this);
        rail.setTiles(new PosterRail.Tiles() {
            @Override public int count() { return count; }
            @Override public int stridePx() { return stride; }
            @Override public View createTile() { return createRailCard(); }
            @Override public void bindTile(View tile, int index) {
                bindRailCard((LinearLayout) tile, railShows.get(index),
                        rail, index);
            }
        });
        sectionsBox.addView(rail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * One rail tile, built once and recycled forever: poster,
     * name, rating, mark — always the same four children, so a
     * bind can set every one of them for the new record.
     */
    private LinearLayout createRailCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(2), dp(4), dp(2));
        card.setLayoutParams(new LinearLayout.LayoutParams(dp(116),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setLayoutParams(new LinearLayout.LayoutParams(dp(108), dp(152)));
        img.setBackground(Sanctuary.posterFrame(this));
        img.setDuplicateParentStateEnabled(true);
        Sanctuary.roundClip(this, img, Sanctuary.TILE_RADIUS_DP);
        card.addView(img);
        TextView name = new TextView(this);
        name.setTextSize(12);
        name.setMaxLines(2);
        card.addView(name);
        TextView rating = new TextView(this);
        rating.setTextSize(11);
        card.addView(rating);
        TextView mark = new TextView(this);
        mark.setMaxLines(1);
        card.addView(mark);
        card.setFocusable(true);
        Sanctuary.focusPop(card);
        return card;
    }

    /** The total bind: every property of the tile, every time. */
    private void bindRailCard(LinearLayout card, CatalogIndex.Record r,
                              PosterRail rail, int index) {
        ImageView img = (ImageView) card.getChildAt(0);
        ImageCache.load(this, r.image, img);
        TextView name = (TextView) card.getChildAt(1);
        name.setText(r.name);
        name.setTextColor(Sanctuary.INK);
        TextView rating = (TextView) card.getChildAt(2);
        if (r.rating > 0) {
            rating.setVisibility(View.VISIBLE);
            rating.setText("★ " + String.format(Locale.US, "%.1f", r.rating));
            rating.setTextColor(Sanctuary.GOLD);
        } else {
            rating.setVisibility(View.GONE);
            rating.setText("");
        }
        fillMark((TextView) card.getChildAt(3), r);
        applyCardState(card, r);
        card.setOnClickListener(v -> openShow(r));
        card.setOnFocusChangeListener((v, has) -> {
            Sanctuary.applyPop(v, has);
            if (has) rail.bringIntoView(index);
        });
    }

    /** A whole country's tiles as a grid — scrollable end to end,
     *  growing by chunks; proven and catalogue-only tiles alike. */
    private void addGridSection(String shelfName, List<CatalogIndex.Record> shows) {
        if (shows == null) shows = new ArrayList<>();
        TextView header = new TextView(this);
        header.setText(shelfName + " — " + shows.size() + " shows");
        header.setTextColor(Sanctuary.GOLD_TEXT);
        header.setTextSize(17);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        header.setPadding(0, dp(Sanctuary.SECTION_GAP_DP), 0, dp(8));
        sectionsBox.addView(header);

        int n = Math.min(wallGridShown, shows.size());
        for (int start = 0; start < n; start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = start; i < Math.min(start + 3, n); i++) {
                row.addView(gridCard(shows.get(i)));
            }
            for (int i = Math.min(start + 3, n); i < start + 3; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            sectionsBox.addView(row);
        }
        if (shows.size() > n) {
            Button more = new Button(this);
            more.setText("Show more — " + (shows.size() - n) + " more");
            more.setContentDescription(more.getText());
            Sanctuary.styleButton(this, more, false);
            more.setOnClickListener(v -> {
                wallGridShown += GRID_CHUNK;
                renderWall();
            });
            sectionsBox.addView(more);
        }
    }

    private void addSeeAllButton(String langName, String shelfName, int count) {
        Button b = new Button(this);
        b.setText("See all " + count + " from " + shelfName + " →");
        b.setContentDescription(b.getText());
        Sanctuary.styleButton(this, b, false);
        b.setOnClickListener(v -> {
            Intent i = new Intent(this, WallListActivity.class);
            i.putExtra("mode", "langCountry");
            i.putExtra("language", langName);
            i.putExtra("country", shelfName);
            startActivity(i);
        });
        sectionsBox.addView(b);
    }

    private void addTextLine(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Sanctuary.MUTED);
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, 0);
        sectionsBox.addView(t);
    }

    private View gridCard(CatalogIndex.Record r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(2), dp(4), dp(2));
        card.addView(posterImage(r, ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));
        card.addView(nameLine(r));
        if (r.rating > 0) card.addView(ratingLine(r));
        card.addView(markLine(r));
        applyCardState(card, r);
        card.setFocusable(true);
        Sanctuary.focusPop(card);
        card.setOnClickListener(v -> openShow(r));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.setLayoutParams(lp);
        return card;
    }

    /**
     * The Display Honesty Law on the card itself (v0.14.0): a
     * catalogue-only title renders VISIBLY distinct — the whole
     * card dimmed, its spoken description carrying the catalog-
     * only words — so no one mistakes the catalogue's knowledge
     * that a show exists for a promise that Sandy can play it.
     * The tap is never where the viewer learns the truth.
     */
    private void applyCardState(LinearLayout card, CatalogIndex.Record r) {
        int st = CardState.forShow(isAvailable(r), false);
        card.setAlpha(CardState.alphaFor(st));
        card.setContentDescription(CardState.playPromise(st)
                ? r.name
                : r.name + " — " + LangStore.tr(this,
                        CardState.labelKey(st)));
    }

    private ImageView posterImage(CatalogIndex.Record r, int w, int h) {
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setBackground(Sanctuary.posterFrame(this));
        img.setDuplicateParentStateEnabled(true);
        Sanctuary.roundClip(this, img, Sanctuary.TILE_RADIUS_DP);
        img.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        ImageCache.load(this, r.image, img);
        return img;
    }

    private TextView nameLine(CatalogIndex.Record r) {
        TextView name = new TextView(this);
        name.setText(r.name);
        name.setTextColor(Sanctuary.INK);
        name.setTextSize(12);
        name.setMaxLines(2);
        return name;
    }

    private TextView ratingLine(CatalogIndex.Record r) {
        TextView rating = new TextView(this);
        rating.setText("★ " + String.format(Locale.US, "%.1f", r.rating));
        rating.setTextColor(Sanctuary.GOLD);
        rating.setTextSize(11);
        return rating;
    }

    /** The honest mark every wall tile carries (v0.10.0): teal when a
     *  shelf of the user's own actually carries it, gold-dim when it is
     *  the catalogue alone. Never ambiguous, never absent. */
    private TextView markLine(CatalogIndex.Record r) {
        TextView t = new TextView(this);
        fillMark(t, r);
        t.setMaxLines(1);
        return t;
    }

    /** The mark, filled into any text view — the rail bind and
     *  the grid share this one truth (v0.17.0). Every property is
     *  set, so a recycled tile never wears a previous mark. */
    private void fillMark(TextView t, CatalogIndex.Record r) {
        int st = CardState.forShow(isAvailable(r), false);
        if (CardState.playPromise(st)) {
            t.setText("✓ " + LangStore.tr(this, "wall_available"));
            t.setTextColor(Sanctuary.TEAL);
            t.setTextSize(10);
            t.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        } else {
            // catalog-only is a headline on the card, not a
            // footnote (v0.14.0): bigger, brighter, unmissable
            t.setText(LangStore.tr(this, CardState.labelKey(st)));
            t.setTextColor(Sanctuary.GOLD_DIM);
            t.setTextSize(12);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
    }

    // ------------------------------------- facets (v0.11.1)

    private String facetPrefRead(String key) {
        String v = getPreferences(MODE_PRIVATE).getString(key, "");
        return "NOT_LISTED".equals(v) ? CatalogIndex.NOT_LISTED : v;
    }

    private static String facetPrefWrite(String v) {
        return CatalogIndex.NOT_LISTED.equals(v) ? "NOT_LISTED" : v;
    }

    private void saveFacetPrefs() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("facet_genre", facetPrefWrite(facets.genre))
                .putString("facet_type", facetPrefWrite(facets.type))
                .putString("facet_status", facetPrefWrite(facets.status))
                .putInt("facet_year_lo", facets.yearLo)
                .putInt("facet_year_hi", facets.yearHi)
                .putBoolean("facet_year_nl", facets.yearNotListed)
                .putInt("facet_sort", facetSort.ordinal())
                .apply();
    }

    /** The standing pick's label for one text axis. */
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
        switch (facetSort) {
            case AZ: return LangStore.tr(this, "sort_az");
            case ZA: return LangStore.tr(this, "sort_za");
            case NEWEST: return LangStore.tr(this, "sort_newest");
            case OLDEST: return LangStore.tr(this, "sort_oldest");
            default: return LangStore.tr(this, "sort_best");
        }
    }

    /** The bar itself: one button per axis, labeled with its
     *  standing pick. Options inside the dialogs are cut from the
     *  pool passed here — the wall the user is standing in. */
    private void buildFacetBar(List<CatalogIndex.Record> pool) {
        facetChipRow.removeAllViews();
        facetScroll.setVisibility(pool == null || pool.isEmpty()
                ? View.GONE : View.VISIBLE);
        if (pool == null || pool.isEmpty()) return;
        addFacetButton(LangStore.tr(this, "facet_genre") + ": " + axisLabel(facets.genre),
                v -> pickTextFacet("genre", pool));
        addFacetButton(LangStore.tr(this, "facet_year") + ": " + yearFacetLabel(),
                v -> pickYearFacet(pool));
        addFacetButton(LangStore.tr(this, "facet_type") + ": " + axisLabel(facets.type),
                v -> pickTextFacet("type", pool));
        addFacetButton(LangStore.tr(this, "facet_status") + ": " + axisLabel(facets.status),
                v -> pickTextFacet("status", pool));
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
                saveFacetPrefs();
                wallGridShown = GRID_CHUNK;
                renderWall();
            });
        }
    }

    private void addFacetButton(String label, View.OnClickListener onClick) {
        Button b = chip(label, false);
        b.setOnClickListener(onClick);
        facetChipRow.addView(b);
    }

    private void pickTextFacet(String axis, List<CatalogIndex.Record> pool) {
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
                    saveFacetPrefs();
                    wallGridShown = GRID_CHUNK;
                    d.dismiss();
                    renderWall();
                })
                .show();
    }

    private void pickYearFacet(List<CatalogIndex.Record> pool) {
        List<Integer> decades = CatalogIndex.decadesIn(pool);
        boolean noYear = CatalogIndex.hasNoYear(pool);
        List<String> labels = new ArrayList<>();
        labels.add(LangStore.tr(this, "facet_all") + " · " + pool.size());
        for (int d : decades) labels.add(d + "s · " + decadeCount(pool, d));
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
                                saveFacetPrefs();
                                wallGridShown = GRID_CHUNK;
                                renderWall();
                            } else if (noYear && which == labels.size() - 1) {
                                facets.yearLo = -1;
                                facets.yearHi = -1;
                                facets.yearNotListed = true;
                                saveFacetPrefs();
                                wallGridShown = GRID_CHUNK;
                                renderWall();
                            } else {
                                pickYearInDecade(pool, decades.get(which - 1));
                            }
                        })
                .show();
    }

    private int decadeCount(List<CatalogIndex.Record> pool, int decade) {
        int n = 0;
        for (CatalogIndex.Record r : pool) {
            int y = CatalogIndex.yearOf(r);
            if (y >= 0 && CatalogIndex.decadeOf(y) == decade) n++;
        }
        return n;
    }

    /** Second step of the year pick: the whole decade, or one year
     *  in it. Skipped when the decade holds a single year — the
     *  decade pick already says everything there is to say. */
    private void pickYearInDecade(List<CatalogIndex.Record> pool, int decade) {
        List<Integer> years = CatalogIndex.yearsIn(pool, decade);
        if (years.size() <= 1) {
            facets.yearLo = decade;
            facets.yearHi = decade + 9;
            facets.yearNotListed = false;
            saveFacetPrefs();
            wallGridShown = GRID_CHUNK;
            renderWall();
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
                    saveFacetPrefs();
                    wallGridShown = GRID_CHUNK;
                    d.dismiss();
                    renderWall();
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
                .setSingleChoiceItems(items, facetSort.ordinal(), (d, which) -> {
                    facetSort = modes[which];
                    saveFacetPrefs();
                    wallGridShown = GRID_CHUNK;
                    d.dismiss();
                    renderWall();
                })
                .show();
    }

    /** The status line while a cut stands: how much of the pool
     *  survives it, and what the cut is — never a silent narrowing. */
    private String facetSummary(List<CatalogIndex.Record> pool) {
        List<CatalogIndex.Record> cut = CatalogIndex.applyFacets(pool, facets);
        StringBuilder sb = new StringBuilder();
        sb.append(cut.size()).append(" of ").append(pool.size()).append(" shows");
        if (facets.active()) {
            sb.append(" match");
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
            sb.append(" — ").append(String.join(" · ", parts));
        }
        if (facetSort != CatalogIndex.SortMode.BEST) {
            sb.append(" · ").append(LangStore.tr(this, "facet_sort"))
                    .append(": ").append(sortLabel());
        }
        sb.append(".").append(walkStateText());
        return sb.toString();
    }

    /** The pool, cut and sorted, as one growing grid — plus the
     *  door to the same cut as a full list (WallListActivity),
     *  where the A–Z jump bar lives. */
    private void addFacetGrid(String label, List<CatalogIndex.Record> pool) {
        List<CatalogIndex.Record> cut = CatalogIndex.applyFacets(pool, facets);
        CatalogIndex.sortBy(cut, facetSort);
        if (cut.isEmpty()) {
            addTextLine("Nothing gathered matches that cut yet — loosen a "
                    + "filter above, or gather more of the catalog below.");
            return;
        }
        addGridSection(label, cut);
        if (cut.size() > 12) {
            Button b = new Button(this);
            b.setText("See the whole list — " + cut.size() + " shows →");
            b.setContentDescription(b.getText());
            Sanctuary.styleButton(this, b, false);
            b.setOnClickListener(v -> {
                Intent i = new Intent(this, WallListActivity.class);
                i.putExtra("mode", "facet");
                i.putExtra("language", wallLangCode);
                i.putExtra("country", wallCountry);
                i.putExtra("f_genre", facets.genre);
                i.putExtra("f_type", facets.type);
                i.putExtra("f_status", facets.status);
                i.putExtra("f_year_lo", facets.yearLo);
                i.putExtra("f_year_hi", facets.yearHi);
                i.putExtra("f_year_nl", facets.yearNotListed);
                i.putExtra("f_sort", facetSort.ordinal());
                startActivity(i);
            });
            sectionsBox.addView(b);
        }
    }

    // ------------------------------------------------ actor search

    /** Search the query as a PERSON (v0.11.1): TVmaze live, at
     *  query time — cast data for 90k shows is never baked. When
     *  the network doesn't answer, the screen says so plainly; the
     *  facet filters keep working offline either way. */
    private void doActorSearch() {
        String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        exec.execute(() -> {
            try {
                List<TvMaze.Person> people = TvMaze.parsePeople(
                        Net.getString(TvMaze.peopleSearchUrl(q)));
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    if (people.isEmpty()) {
                        Toast.makeText(this, "No people matched that on TVmaze.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    resultsMode = "people";
                    peopleResults = people;
                    ArrayAdapter<TvMaze.Person> ad = new ArrayAdapter<TvMaze.Person>(
                            this, android.R.layout.simple_list_item_2,
                            android.R.id.text1, people) {
                        @Override
                        public View getView(int pos, View convertView, ViewGroup parent) {
                            View v = super.getView(pos, convertView, parent);
                            TvMaze.Person p = people.get(pos);
                            TextView t1 = v.findViewById(android.R.id.text1);
                            t1.setText(p.name);
                            t1.setTextColor(Sanctuary.INK);
                            TextView t2 = v.findViewById(android.R.id.text2);
                            t2.setText(p.countryName.isEmpty() ? "Tap for their shows"
                                    : p.countryName + " — tap for their shows");
                            t2.setTextColor(Sanctuary.SUB);
                            return v;
                        }
                    };
                    resultsList.setAdapter(ad);
                    catalogScroll.setVisibility(View.GONE);
                    resultsList.setVisibility(View.VISIBLE);
                    Toast.makeText(this, people.size()
                            + " people — tap one for their shows",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, LangStore.tr(this, "actor_needs_net"),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** One person's shows, as cast credits — landed as catalogue
     *  records carrying the same honest marks as the wall tiles. */
    private void openPerson(TvMaze.Person p) {
        progress.setVisibility(View.VISIBLE);
        exec.execute(() -> {
            try {
                List<TvMaze.Show> shows = TvMaze.parseCastCredits(
                        Net.getString(TvMaze.castCreditsUrl(p.id)));
                List<CatalogIndex.Record> recs = new ArrayList<>();
                for (TvMaze.Show s : shows) recs.add(CatalogIndex.fromShow(s));
                CatalogIndex.sortShows(recs);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    if (recs.isEmpty()) {
                        Toast.makeText(this, "TVmaze lists no shows for "
                                + p.name + " yet.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    resultsMode = "credits";
                    creditResults = recs;
                    resultsList.setAdapter(new android.widget.BaseAdapter() {
                        @Override public int getCount() { return recs.size(); }
                        @Override public Object getItem(int pos) { return recs.get(pos); }
                        @Override public long getItemId(int pos) { return recs.get(pos).id; }
                        @Override
                        public View getView(int pos, View convertView, ViewGroup parent) {
                            LinearLayout ll;
                            TextView main, sub, mark;
                            if (convertView instanceof LinearLayout) {
                                ll = (LinearLayout) convertView;
                                main = (TextView) ll.getChildAt(0);
                                sub = (TextView) ll.getChildAt(1);
                                mark = (TextView) ll.getChildAt(2);
                            } else {
                                ll = new LinearLayout(HomeActivity.this);
                                ll.setOrientation(LinearLayout.VERTICAL);
                                ll.setPadding(dp(8), dp(6), dp(8), dp(6));
                                main = new TextView(HomeActivity.this);
                                main.setTextColor(Sanctuary.INK);
                                main.setTextSize(15);
                                sub = new TextView(HomeActivity.this);
                                sub.setTextColor(Sanctuary.SUB);
                                sub.setTextSize(12);
                                mark = new TextView(HomeActivity.this);
                                mark.setTextSize(11);
                                ll.addView(main);
                                ll.addView(sub);
                                ll.addView(mark);
                            }
                            CatalogIndex.Record r = recs.get(pos);
                            main.setText(r.name);
                            StringBuilder meta = new StringBuilder();
                            int y = CatalogIndex.yearOf(r);
                            if (y >= 0) meta.append(y);
                            if (!r.genres.isEmpty()) {
                                if (meta.length() > 0) meta.append(" · ");
                                meta.append(String.join(" · ", r.genres));
                            }
                            if (r.rating > 0) {
                                if (meta.length() > 0) meta.append(" · ");
                                meta.append("★ ").append(String.format(
                                        Locale.US, "%.1f", r.rating));
                            }
                            sub.setText(meta.toString());
                            if (isAvailable(r)) {
                                mark.setText("✓ " + LangStore.tr(
                                        HomeActivity.this, "wall_available"));
                                mark.setTextColor(Sanctuary.TEAL);
                            } else {
                                mark.setText(LangStore.tr(
                                        HomeActivity.this, "wall_catalog_only"));
                                mark.setTextColor(Sanctuary.SUB);
                            }
                            return ll;
                        }
                    });
                    Toast.makeText(this, "Shows with " + p.name + " — " + recs.size(),
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, LangStore.tr(this, "actor_needs_net"),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ------------------------------------------------------------- search

    private void doSearch() {
        String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        exec.execute(() -> {
            try {
                List<TvMaze.Show> results = TvMaze.parseSearch(Net.getString(TvMaze.searchUrl(q)));
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    resultsMode = "shows";
                    searchResults = results;
                    if (results.isEmpty()) {
                        Toast.makeText(this, "No shows matched that on TVmaze.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    ArrayAdapter<TvMaze.Show> ad = new ArrayAdapter<TvMaze.Show>(this,
                            android.R.layout.simple_list_item_2, android.R.id.text1, results) {
                        @Override
                        public View getView(int pos, View convertView, ViewGroup parent) {
                            View v = super.getView(pos, convertView, parent);
                            TvMaze.Show s = results.get(pos);
                            TextView t1 = v.findViewById(android.R.id.text1);
                            t1.setText(s.name);
                            t1.setTextColor(Sanctuary.INK);
                            String meta = String.join(" · ", s.genres)
                                    + (s.language.isEmpty() ? "" : "  ·  " + s.language)
                                    + (s.rating > 0 ? "  ·  ★ "
                                        + String.format(Locale.US, "%.1f", s.rating) : "");
                            TextView t2 = v.findViewById(android.R.id.text2);
                            t2.setText(meta);
                            t2.setTextColor(Sanctuary.SUB);
                            return v;
                        }
                    };
                    resultsList.setAdapter(ad);
                    catalogScroll.setVisibility(View.GONE);
                    resultsList.setVisibility(View.VISIBLE);
                    Toast.makeText(this, results.size() + " shows — tap Catalog to go back",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "Search didn't answer (" + shortErr(e) + ")",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ------------------------------------------------------------- io

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static String readFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, String content) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }
}
