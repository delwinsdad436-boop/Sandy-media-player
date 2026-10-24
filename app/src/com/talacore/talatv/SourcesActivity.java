package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The source picker — the TeaTV moment, lawful sources only. For one
 * episode (or movie title) it gathers: matches from every playlist the
 * user has loaded, and playable files from the Internet Archive's public
 * collection. Quality-ranked best-first. If nothing matches, it says so
 * plainly and explains where sources come from — it never fakes a stream.
 */
public class SourcesActivity extends Activity {

    private static final int IA_ITEMS_TO_OPEN = 5;
    private static final int IA_MAX_SOURCES = 25;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<SourceFinder.Source> sources = new ArrayList<>();

    private String mode;
    private int showId, season, number;
    private String showName, epName, movieTitle;
    private String showLanguage = "";
    private String directUrl, directName, directUa;
    private String contentKey;
    private String displayTitle;

    private TextView titleView, statusView, emptyView;
    private ListView listView;
    private ProgressBar progress;
    private SourceAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "episode";
        showId = getIntent().getIntExtra("showId", -1);
        showName = getIntent().getStringExtra("showName");
        String sl = getIntent().getStringExtra("showLanguage");
        if (sl != null) showLanguage = sl;
        season = getIntent().getIntExtra("season", 0);
        number = getIntent().getIntExtra("number", 0);
        epName = getIntent().getStringExtra("epName");
        movieTitle = getIntent().getStringExtra("movieTitle");
        directUrl = getIntent().getStringExtra("directUrl");
        directName = getIntent().getStringExtra("directName");
        directUa = getIntent().getStringExtra("directUa");

        if ("movie".equals(mode)) {
            contentKey = ChannelStore.titleKey(movieTitle == null ? "" : movieTitle);
            displayTitle = movieTitle;
        } else {
            contentKey = ChannelStore.episodeKey(showId, season, number);
            displayTitle = String.format(Locale.US, "%s — S%02dE%02d · %s",
                    showName, season, number, epName == null ? "" : epName);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView header = new TextView(this);
        header.setText("episodeDub".equals(mode)
                ? LangStore.tr(this, "dub_voiceover_pick_source") : "Watch");
        header.setTextColor(Color.rgb(255, 200, 90));
        header.setTextSize(14);
        header.setTypeface(header.getTypeface(), Typeface.BOLD);
        root.addView(header);

        titleView = new TextView(this);
        titleView.setText(displayTitle);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        root.addView(titleView);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(170, 170, 180));
        statusView.setTextSize(12);
        statusView.setText("Looking for sources — your playlists and the "
                + "videos on this phone first, then the Internet Archive…");
        root.addView(statusView);

        listView = new ListView(this);
        listView.setBackgroundColor(Color.rgb(16, 16, 20));
        adapter = new SourceAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            // v0.13.0: in voiceover mode a tap does not play — it
            // HOLDS the source (the record leg of the cook) and
            // opens the dub kitchen holding it
            if ("episodeDub".equals(mode)) holdForDub(sources.get(pos));
            else play(sources.get(pos));
        });

        emptyView = new TextView(this);
        emptyView.setTextColor(Color.rgb(220, 220, 228));
        emptyView.setTextSize(15);
        emptyView.setPadding(0, dp(20), 0, 0);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        setContentView(root);
        gather();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- gather

    private void gather() {
        exec.execute(() -> {
            List<SourceFinder.Source> found = new ArrayList<>();
            Set<String> seenUrls = new HashSet<>();
            StringBuilder notes = new StringBuilder();

            // Measured per-source accounting — this is what the dead-end
            // screen reports when nothing is found, so James can see what
            // was actually checked instead of taking emptiness on faith.
            int playlistEntries = 0, playlistCount = 0, libraryFiles = 0;
            boolean guideLoaded = false;
            String iaOutcome = "wasn't reached";

            // v0.10.0: the names this title is looked up under — its
            // own name first, then the alternates TVmaze itself lists.
            // Matching runs in the title's own language world; no name
            // is ever a translation we invented.
            List<TitleNames.Name> nameSet = new ArrayList<>();
            nameSet.add(new TitleNames.Name(showName == null ? "" : showName, ""));
            if (!"movie".equals(mode) && showId > 0) {
                nameSet = TitleNames.buildSet(showName, loadAkas(showId));
                if (nameSet.size() > 1) {
                    StringBuilder nb = new StringBuilder("Also looked under: ");
                    for (int i = 1; i < nameSet.size(); i++) {
                        if (i > 1) nb.append(", ");
                        nb.append(nameSet.get(i).name);
                    }
                    notes.append(nb.append(". "));
                }
            }
            List<String> matchNames = TitleNames.namesOf(nameSet);
            String iaCode = IaClient.iaLanguageCode(showLanguage);

            // the exact playlist entry the user tapped (Movies & VOD shelf)
            if (directUrl != null && !directUrl.isEmpty()) {
                M3uParser.Channel dc = new M3uParser.Channel();
                dc.name = directName == null ? displayTitle : directName;
                dc.url = directUrl;
                dc.userAgent = directUa == null ? "" : directUa;
                dc.source = "Your playlists";
                found.add(SourceFinder.fromChannel(dc));
                seenUrls.add(directUrl);
            }

            // (a) loaded playlists
            playlistCount = ChannelStore.getPlaylists(this).size();
            List<M3uParser.Channel> channels = ChannelStore.loadCache(this);
            if (channels != null) {
                guideLoaded = true;
                playlistEntries = channels.size();
                List<SourceFinder.Source> pm = new ArrayList<>();
                if ("movie".equals(mode)) {
                    pm = SourceFinder.findTitle(channels,
                            SourceFinder.cleanTitleForSearch(movieTitle));
                } else {
                    for (String nm : matchNames) {
                        pm.addAll(SourceFinder.findEpisode(
                                channels, nm, season, number, epName));
                    }
                }
                for (SourceFinder.Source s : pm) {
                    if (seenUrls.add(s.url)) found.add(s);
                }
            }

            // (b) My Library — the video files on this phone (v0.3.0)
            List<M3uParser.Channel> libChannels = LibraryStore.loadChannels(this);
            libraryFiles = libChannels.size();
            if (!libChannels.isEmpty()) {
                List<SourceFinder.Source> lm = new ArrayList<>();
                if ("movie".equals(mode)) {
                    lm = SourceFinder.findTitle(libChannels,
                            SourceFinder.cleanTitleForSearch(movieTitle));
                } else {
                    for (String nm : matchNames) {
                        lm.addAll(SourceFinder.findEpisode(
                                libChannels, nm, season, number, epName));
                    }
                }
                for (SourceFinder.Source s : lm) {
                    if (seenUrls.add(s.url)) found.add(s);
                }
            }

            // (c) Internet Archive — episode-aware and ranked (v0.3.0)
            int iaAdded = 0;
            boolean iaAnswered = false;
            if ("movie".equals(mode)) {
                String cleaned = SourceFinder.cleanTitleForSearch(movieTitle);
                try {
                    List<IaClient.Item> ranked = IaClient.rankItems(
                            IaClient.parseSearch(Net.getString(IaClient.searchUrl(cleaned))),
                            cleaned, "", 0, 0, IaClient.yearOf(cleaned));
                    iaAnswered = true;
                    int opened = 0;
                    for (IaClient.Item item : ranked) {
                        if (opened >= IA_ITEMS_TO_OPEN || found.size() >= IA_MAX_SOURCES) break;
                        opened++;
                        iaAdded += addArchiveItem(found, seenUrls, item, null);
                    }
                } catch (Exception e) {
                    iaAnswered = false;
                }
            } else {
                List<IaClient.Item> pool = new ArrayList<>();
                Set<String> seenIds = new HashSet<>();
                if (epName != null && !epName.trim().isEmpty()) {
                    try {
                        for (IaClient.Item it : IaClient.parseSearch(
                                Net.getString(IaClient.episodeSearchUrl(showName, epName)))) {
                            if (seenIds.add(it.identifier)) pool.add(it);
                        }
                        iaAnswered = true;
                    } catch (Exception ignored) { }
                }
                List<IaClient.Item> ranked = IaClient.rankItems(
                        pool, showName, epName, season, number, 0);
                if (ranked.isEmpty() || ranked.get(0).score < 50) {
                    // the precision shot missed — cast the scoped wider net
                    try {
                        for (IaClient.Item it : IaClient.parseSearch(
                                Net.getString(IaClient.showScopedSearchUrl(showName)))) {
                            if (seenIds.add(it.identifier)) pool.add(it);
                        }
                        iaAnswered = true;
                        ranked = IaClient.rankItems(pool, showName, epName, season, number, 0);
                    } catch (Exception ignored) { }
                }
                // v0.10.0 — the language leg: the Archive's own shelves
                // in the show's language. Items IA tags with the show's
                // language join the pool and float first; untagged
                // items are never promoted on a guess.
                if (!iaCode.isEmpty()) {
                    try {
                        String url = (epName != null && !epName.trim().isEmpty())
                                ? IaClient.episodeLangSearchUrl(showName, epName, iaCode)
                                : IaClient.showLangSearchUrl(showName, iaCode);
                        for (IaClient.Item it : IaClient.parseSearch(Net.getString(url))) {
                            if (seenIds.add(it.identifier)) pool.add(it);
                        }
                        iaAnswered = true;
                        ranked = IaClient.preferLanguage(
                                IaClient.rankItems(pool, showName, epName, season, number, 0),
                                iaCode);
                        notes.append("The Archive was also searched in ")
                                .append(showLanguage).append("-tagged items. ");
                    } catch (Exception ignored) { }
                }
                // v0.10.0 — still weak? Retry under the alternate name
                // the world knows (TVmaze's own AKA record).
                String fallbackName = TitleNames.archiveFallbackName(nameSet);
                if ((ranked.isEmpty() || ranked.get(0).score < 50)
                        && fallbackName != null
                        && epName != null && !epName.trim().isEmpty()) {
                    try {
                        List<IaClient.Item> poolF = IaClient.parseSearch(
                                Net.getString(IaClient.episodeSearchUrl(fallbackName, epName)));
                        List<IaClient.Item> rankedF = IaClient.rankItems(
                                poolF, fallbackName, epName, season, number, 0);
                        Set<String> have = new HashSet<>();
                        for (IaClient.Item it : ranked) have.add(it.identifier);
                        for (IaClient.Item it : rankedF) {
                            if (have.add(it.identifier)) ranked.add(it);
                        }
                        ranked.sort((a, b) -> b.score - a.score);
                        iaAnswered = true;
                    } catch (Exception ignored) { }
                }
                int opened = 0;
                for (IaClient.Item item : ranked) {
                    if (opened >= IA_ITEMS_TO_OPEN || found.size() >= IA_MAX_SOURCES) break;
                    opened++;
                    iaAdded += addArchiveItem(found, seenUrls, item, item);
                }
            }
            iaOutcome = iaAdded > 0 ? "found " + iaAdded
                    : iaAnswered ? "searched — no match" : "didn't answer (connection?)";
            // v0.10.0: a proven Archive find is remembered — the
            // catalogue tile may now honestly say "on your shelves".
            if (iaAdded > 0 && showId > 0) {
                ChannelStore.markArchiveProven(this, showId);
            }

            SourceFinder.sortByQuality(found);
            final int fPlaylistEntries = playlistEntries, fPlaylistCount = playlistCount;
            final int fLibraryFiles = libraryFiles;
            final boolean fGuideLoaded = guideLoaded;
            final String fIaOutcome = iaOutcome;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                sources.clear();
                sources.addAll(found);
                adapter.notifyDataSetChanged();
                if (found.isEmpty()) {
                    listView.setVisibility(View.GONE);
                    emptyView.setVisibility(View.VISIBLE);
                    emptyView.setText(deadEndText(fPlaylistEntries, fPlaylistCount,
                            fLibraryFiles, fGuideLoaded, fIaOutcome));
                    statusView.setText(notes.toString().trim());
                } else {
                    int nPl = 0, nLib = 0, nIa = 0;
                    for (SourceFinder.Source s : found) {
                        if ("My Library".equals(s.kind)) nLib++;
                        else if ("Internet Archive".equals(s.kind)) nIa++;
                        else nPl++;
                    }
                    statusView.setText(found.size() + " source"
                            + (found.size() == 1 ? "" : "s")
                            + ", best quality first. (Playlists " + nPl
                            + " · My Library " + nLib
                            + " · Internet Archive " + nIa + ") Tap one to play. "
                            + notes.toString().trim());
                    gradeEpisodeSources();
                    appendTierLine();
                    maybeAutoPlay();
                }
            });
        });
    }

    private static final long AKA_TTL_MS = 7L * 24L * 3600L * 1000L;

    /**
     * TVmaze's alternate names for this show, from a week-long cache
     * (AKAs change rarely). Any failure answers empty — the original
     * name still gets its full search, nothing is blocked on this.
     */
    private List<TvMaze.Aka> loadAkas(int id) {
        java.io.File f = new java.io.File(
                CatalogStore.dir(this), "aka_" + id + ".json");
        try {
            if (f.exists()
                    && System.currentTimeMillis() - f.lastModified() < AKA_TTL_MS) {
                String cached = CatalogStore.readFile(f);
                if (cached != null) return TvMaze.parseAkas(cached);
            }
            String json = Net.getString(TvMaze.akasUrl(id));
            List<TvMaze.Aka> akas = TvMaze.parseAkas(json);
            CatalogStore.writeFile(f, json);
            return akas;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Open one archive item and add its playable files. In episode mode
     * (episodeItem != null) only files that pass the episode filter are
     * added — an item is never a license to offer its whole contents.
     * Returns how many sources were added.
     */
    private int addArchiveItem(List<SourceFinder.Source> found, Set<String> seenUrls,
                               IaClient.Item item, IaClient.Item episodeItem) {
        int added = 0;
        try {
            List<IaClient.FileEntry> files = IaClient.parseMetadata(
                    Net.getString(IaClient.metadataUrl(item.identifier)),
                    item.identifier);
            IaClient.FileEntry firstSub = null;
            List<IaClient.FileEntry> videos = new ArrayList<>();
            for (IaClient.FileEntry f : files) {
                if (f.subtitle && firstSub == null) firstSub = f;
                if (f.video) videos.add(f);
            }
            List<IaClient.FileEntry> keep = videos;
            if (episodeItem != null) {
                boolean matched = IaClient.itemMatchesEpisode(
                        episodeItem, epName, season, number);
                keep = IaClient.filterEpisodeFiles(files, season, number, epName, matched);
            }
            for (IaClient.FileEntry v : keep) {
                if (!seenUrls.add(v.url)) continue;
                found.add(SourceFinder.fromArchiveFile(item, v, firstSub));
                added++;
            }
        } catch (Exception ignored) {
            // one dead archive item shouldn't sink the others
        }
        return added;
    }

    /**
     * The dead-end explanation (v0.3.0): when no shelf has the title,
     * say WHY, show the measured per-source accounting of what was
     * checked, and name the two moves that would fix it.
     */
    private String deadEndText(int playlistEntries, int playlistCount, int libraryFiles,
                               boolean guideLoaded, String iaOutcome) {
        String what = "movie".equals(mode) ? displayTitle
                : String.format(Locale.US, "%s S%02dE%02d%s", showName, season, number,
                        epName == null || epName.isEmpty() ? "" : " · " + epName);
        StringBuilder b = new StringBuilder();
        b.append("No source for ").append(what).append(" yet.\n\n");
        b.append("Why: it's not on the free shelves this app checks, and none "
                + "of your own shelves carry it either.\n\n");
        b.append("Checked:\n");
        if (guideLoaded) {
            b.append("• Your playlists — ").append(playlistEntries)
                    .append(" entries across ").append(playlistCount)
                    .append(playlistCount == 1 ? " list" : " lists").append(": no match\n");
        } else {
            b.append("• Your playlists — the guide hasn't been loaded yet "
                    + "(open Live TV once), so they couldn't be checked\n");
        }
        b.append("• My Library (videos on this phone) — ").append(libraryFiles)
                .append(libraryFiles == 1 ? " file" : " files").append(" indexed: no match\n");
        b.append("• Internet Archive — ").append(iaOutcome).append("\n\n");
        b.append("What would fix it: add a playlist that carries it "
                + "(Live TV → Playlists), or put the video file on this phone "
                + "and index it (Catalog → My Library → Rescan). "
                + "Sandy never fakes a stream.");
        return b.toString();
    }

    // ------------------------------------------------------------- play

    /**
     * A tapped source goes through the health layer first (v0.3.0):
     * probe it, and only hand the player a link that answers. A dead
     * source is marked ✗, the user is told, and with auto-skip on the
     * next source down the list is tried — the TeaTV tap should never
     * end at a corpse when another source is sitting right there.
     */
    // ------------------------------------------------- episode tiers
    // v0.14.0 (Addenda 9–11): Archive candidates for THIS episode are
    // graded by EpisodeMatch evidence and carry their tier in the
    // row. CLOSE (the maybe pen) is shown for the viewer's own eyes
    // and is NEVER auto-played; MOSTLY is badged "Middle ground";
    // the playable promise belongs to RESOLVED alone.

    private final java.util.Map<String, Integer> epTiers =
            new java.util.HashMap<>();

    private void gradeEpisodeSources() {
        epTiers.clear();
        if (!"episode".equals(mode) && !"episodeDub".equals(mode)) return;
        EpisodeMatch.EpisodeRef ep = new EpisodeMatch.EpisodeRef(
                season, number, epName == null ? "" : epName, "", 0);
        for (SourceFinder.Source s : sources) {
            if (!"Internet Archive".equals(s.kind)) continue;
            EpisodeMatch.Parsed p = EpisodeMatch.parse(s.label, showName);
            EpisodeMatch.Scored sc = EpisodeMatch.score(p, ep);
            int tier;
            if (sc.conflict) tier = EpisodeMatch.TIER_CLOSE;
            else if (sc.score >= 60) tier = EpisodeMatch.TIER_RESOLVED;
            else if (sc.score >= 35) tier = EpisodeMatch.TIER_MOSTLY;
            else tier = EpisodeMatch.TIER_CLOSE;
            epTiers.put(s.url, tier);
        }
    }

    private void appendTierLine() {
        if (epTiers.isEmpty()) return;
        int best = 0, mid = 0, maybe = 0;
        for (int t : epTiers.values()) {
            if (t == EpisodeMatch.TIER_RESOLVED) best++;
            else if (t == EpisodeMatch.TIER_MOSTLY) mid++;
            else maybe++;
        }
        StringBuilder line = new StringBuilder();
        line.append(" — ").append(LangStore.tr(this, "tier_best"))
                .append(" ").append(best)
                .append(" · ").append(LangStore.tr(this, "tier_middle"))
                .append(" ").append(mid)
                .append(" · ").append(LangStore.tr(this, "tier_maybe"))
                .append(" ").append(maybe).append(".");
        if (maybe > 0) {
            line.append(" ").append(LangStore.tr(this, "ep_maybe_note"));
        }
        statusView.setText(statusView.getText().toString() + line);
    }

    // Auto-play (v0.12.1, James's doctrine): a tap on a tile/episode
    // means PLAY — Sandy resolves and plays the best WORKING source
    // in the viewer's already-chosen language, under the quality
    // posture from Settings. Auto-pick is the default road, not a
    // wall: the source list stays fully reachable, and when nothing
    // working meets the posture's floor the viewer is OFFERED the
    // best working one — never served below-floor silently, never
    // dead-ended without the offer.
    private boolean autoPlayDone = false;

    private void maybeAutoPlay() {
        if (autoPlayDone || sources.isEmpty()) return;
        autoPlayDone = true;
        AutoPlay.Posture posture =
                AutoPlay.parsePosture(ChannelStore.getQualityPosture(this));
        String facet = UiStrings.facetFor(ChannelStore.getUiLanguage(this));
        String marc = IaClient.iaLanguageCode(facet);
        java.util.Map<String, Integer> health = new java.util.HashMap<>();
        java.util.List<AutoPlay.Cand> cands = new java.util.ArrayList<>();
        // Addendum 11: the maybe pen is never auto-played, and when
        // a best-choice (RESOLVED) candidate stands, the middle
        // ground waits for the viewer's own tap.
        boolean anyResolved = false;
        for (SourceFinder.Source s : sources) {
            Integer t = epTiers.get(s.url);
            if (t != null && t == EpisodeMatch.TIER_RESOLVED) anyResolved = true;
        }
        for (SourceFinder.Source s : sources) {
            Integer t = epTiers.get(s.url);
            if (t != null && t == EpisodeMatch.TIER_CLOSE) continue;
            if (t != null && anyResolved && t == EpisodeMatch.TIER_MOSTLY) {
                continue;
            }
            HealthStore.Health h = HealthStore.get(this, s.url);
            health.put(s.url, h == null ? AutoPlay.HEALTH_UNKNOWN : h.tier);
            cands.add(AutoPlay.candidates(
                    java.util.Collections.singletonList(s), health,
                    facet, marc).get(0));
        }
        double bps = PipeMeter.measuredBps(this);
        int cap = PipeMeter.capTierFor(bps);
        AutoPlay.Pick pick = AutoPlay.pick(cands, posture, cap);
        if (pick == null || pick.source == null) {
            // Everything on file is proven dead (or nothing found):
            // the list and its honest status line are the answer —
            // no auto-play, no invention.
            return;
        }
        SourceFinder.Source chosen = null;
        for (SourceFinder.Source s : sources) {
            if (s.url.equals(pick.source.url)) { chosen = s; break; }
        }
        if (chosen == null) return;
        final SourceFinder.Source target = chosen;
        if (pick.offer) {
            // Below the floor: say so plainly and OFFER.
            String body = String.format(
                    LangStore.tr(this, "auto_offer_body"),
                    PipeMeter.tierLabel(target.qualityTier),
                    PipeMeter.tierLabel(AutoPlay.FLOOR_TIER));
            new AlertDialog.Builder(this)
                    .setTitle(LangStore.tr(this, "auto_offer_title"))
                    .setMessage(body)
                    .setPositiveButton(LangStore.tr(this, "auto_offer_play"),
                            (d, w) -> play(target))
                    .setNegativeButton(LangStore.tr(this, "auto_see_sources"),
                            (d, w) -> { })
                    .show();
        } else {
            Toast.makeText(this, String.format(
                    LangStore.tr(this, "auto_playing"),
                    PipeMeter.tierLabel(target.qualityTier),
                    AutoPlay.postureLabel(posture)),
                    Toast.LENGTH_LONG).show();
            play(target);
        }
    }

    /**
     * v0.13.0: hold a source for the voiceover cook — a remote
     * source starts landing through the recordings door (the same
     * DownloadRequest the timer uses; its id is the URL), a local
     * source is already held. Either way the dub kitchen opens
     * with the pick in hand; if the copy is still landing, the
     * kitchen says so and waits (DubActivity's still-saving plan).
     */
    private void holdForDub(SourceFinder.Source s) {
        Intent i = new Intent(this, DubActivity.class);
        String name = String.format(Locale.US, "%s S%02dE%02d",
                showName == null ? "" : showName, season, number);
        i.putExtra("pickName", name);
        if (LinkHealth.isRemote(s.url)) {
            boolean hls = s.url.contains(".m3u8") || "hls".equals(s.kind);
            Downloads.start(this, s.url, hls);
            i.putExtra("pickUrl", s.url);
            i.putExtra("pickRecording", true);
        } else {
            i.putExtra("pickUrl", s.url);
            i.putExtra("pickRecording", false);
        }
        startActivity(i);
        finish();
    }

    /** The same source, reached through the door that answered
     *  (Hydra's winning head) — everything else about it stands. */
    private static SourceFinder.Source copyWithUrl(
            SourceFinder.Source s, String url) {
        SourceFinder.Source c = new SourceFinder.Source();
        c.kind = s.kind;
        c.label = s.label;
        c.detail = s.detail;
        c.url = url;
        c.userAgent = s.userAgent;
        c.qualityLabel = s.qualityLabel;
        c.qualityTier = s.qualityTier;
        c.size = s.size;
        c.subUrl = s.subUrl;
        c.subMime = s.subMime;
        c.subLang = s.subLang;
        c.langFacet = s.langFacet;
        c.langMarc = s.langMarc;
        return c;
    }

    private void play(SourceFinder.Source s) {
        if (!LinkHealth.isRemote(s.url)) {
            launchSource(s);
            return;
        }
        progress.setVisibility(View.VISIBLE);
        statusView.setText("Checking that source…");
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            // Hydra proves the source (v0.14.0, the Connection
            // Law): the published manner is proven patiently
            // (SG-1: slow retries in place, the word "dead" is
            // never used while retries remain) — and when that
            // manner is what's dead, the LADDER advances to
            // another door (the Archive's canonical door, the
            // other scheme) instead of ending the attempt. The
            // door that answers is the door that plays.
            HydraResolver.Outcome o = HydraResolver.prove(this, s.url,
                    s.userAgent,
                    (headNo, headCount) -> runOnUiThread(() ->
                            statusView.setText(LangStore.tr(this,
                                    "hydra_trying"))));
            LinkHealth.Result r = o.result;
            final SourceFinder.Source playSource =
                    o.url.equals(s.url) ? s : copyWithUrl(s, o.url);
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (adapter != null) adapter.notifyDataSetChanged();
                if (r.tier == LinkHealth.TIER_PLAYS) {
                    launchSource(playSource);
                } else if (r.tier == LinkHealth.TIER_CONNECTS) {
                    // The playlist answered but the video is unproven —
                    // the player is the ground truth; try it, honestly.
                    android.widget.Toast.makeText(this,
                            "That source answers, but its video is unproven — trying it.",
                            android.widget.Toast.LENGTH_LONG).show();
                    launchSource(playSource);
                } else if (r.slow) {
                    // Not dead — slow. Nothing is marked; the honest
                    // sentence is what happened, and a re-tap (James's
                    // own fix on 2026-10-18) is one tap away.
                    statusView.setTextColor(Color.rgb(230, 200, 140));
                    statusView.setText("That source didn't answer in time — "
                            + "it may just be slow. It is not marked dead; "
                            + "tap it again to retry.");
                    android.widget.Toast.makeText(this,
                            "Didn't answer in time (" + r.detail + ") — "
                                    + "not dead, just slow. Tap again to retry.",
                            android.widget.Toast.LENGTH_LONG).show();
                } else {
                    // Failure words (v0.14.0): never a bare
                    // "HTTP error" — the surface states what the
                    // server actually said, in plain words.
                    statusView.setTextColor(Color.rgb(230, 140, 140));
                    statusView.setText("That source is dead. "
                            + FailureWords.englishFor(r) + " Marked ✗.");
                    android.widget.Toast.makeText(this,
                            "That source is dead. " + FailureWords.englishFor(r),
                            android.widget.Toast.LENGTH_LONG).show();
                    if (ChannelStore.getAutoSkip(this)) {
                        int idx = sources.indexOf(s);
                        if (idx >= 0 && idx + 1 < sources.size()) {
                            statusView.setText("Trying the next source…");
                            play(sources.get(idx + 1));
                        }
                    }
                }
            });
        });
    }

    private void launchSource(SourceFinder.Source s) {
        M3uParser.Channel ch = new M3uParser.Channel();
        ch.name = displayTitle;
        ch.url = s.url;
        ch.userAgent = s.userAgent == null ? "" : s.userAgent;
        ch.source = s.kind;
        List<M3uParser.Channel> queue = new ArrayList<>();
        queue.add(ch);
        ChannelStore.playQueue = queue;
        ChannelStore.playIndex = 0;

        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", s.url);
        i.putExtra("name", displayTitle);
        i.putExtra("ua", ch.userAgent);
        i.putExtra("ckey", contentKey);
        // v0.14.0: the episode context rides along so the player
        // can pre-resolve the NEXT episode while this one plays
        // (one tap to the next episode). Movies carry no next.
        if ("episode".equals(mode) || "episodeDub".equals(mode)) {
            i.putExtra("epShowId", showId);
            i.putExtra("epSeason", season);
            i.putExtra("epNumber", number);
            i.putExtra("epName", epName == null ? "" : epName);
            i.putExtra("epShowName", showName == null ? "" : showName);
            i.putExtra("epShowLang", showLanguage == null ? "" : showLanguage);
        }
        // Posture note (v0.12.1): the player states when the
        // posture materially shaped the pick ("Playing 480p —
        // Low First") and applies the posture to HLS variants.
        {
            AutoPlay.Posture p = AutoPlay.parsePosture(
                    ChannelStore.getQualityPosture(this));
            i.putExtra("posture", AutoPlay.posturePref(p));
            i.putExtra("postureNote", "Playing "
                    + PipeMeter.tierLabel(s.qualityTier) + " — "
                    + AutoPlay.postureLabel(p));
        }
        // Prepared dub (v0.4.0): a library file with a .dub-en sidecar
        // beside it hands the dub to the player.
        if ("My Library".equals(s.kind)) {
            String dub = findDubFor(s.url);
            if (dub != null) i.putExtra("dubUrl", dub);
            // v0.6.0: cooked translation sidecars ride too — the
            // translated subtitle becomes the title's subtitle track
            // (when nothing else already provides one), its rich
            // record goes along for the parallel view + voice render,
            // and a prepared dub in the viewer's own language wins
            // over the English default.
            String lang = ChannelStore.getUiLanguage(this);
            if (lang == null || lang.isEmpty()) lang = "en";
            String[] tr = findTrFor(s.url, lang);
            if (tr != null) {
                if (s.subUrl == null || s.subUrl.isEmpty()) {
                    i.putExtra("subUrl", tr[0]);
                    i.putExtra("subMime", tr[0].endsWith(".vtt")
                            ? "text/vtt" : "application/x-subrip");
                    i.putExtra("subLang", lang);
                }
                if (tr[1] != null) i.putExtra("trJsonUrl", tr[1]);
            }
            if (!"en".equals(lang)) {
                String dubLang = findDubLangFor(s.url, lang);
                if (dubLang != null) i.putExtra("dubUrl", dubLang);
            }
        }
        if (s.subUrl != null && !s.subUrl.isEmpty()) {
            i.putExtra("subUrl", s.subUrl);
            i.putExtra("subMime", s.subMime);
            i.putExtra("subLang", s.subLang);
        }
        startActivity(i);
    }

    /** Dub sidecar lookup for a library source URL; null when none. */
    private String findDubFor(String url) {
        try {
            for (LibraryIndex.Entry e : LibraryStore.loadEntries(this)) {
                if (e.uri != null && e.uri.equals(url)) {
                    return DubFinder.findDub(this, e);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Prepared dub in a specific language for a library source URL. */
    private String findDubLangFor(String url, String lang) {
        try {
            for (LibraryIndex.Entry e : LibraryStore.loadEntries(this)) {
                if (e.uri != null && e.uri.equals(url)) {
                    return DubFinder.findDubLang(this, e, lang);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Cooked translation sidecars for a library source URL:
     * {playableSrtOrVtt, richJsonOrNull}, or null when neither exists.
     */
    private String[] findTrFor(String url, String lang) {
        try {
            for (LibraryIndex.Entry e : LibraryStore.loadEntries(this)) {
                if (e.uri != null && e.uri.equals(url)) {
                    String sub = DubFinder.findTrSub(this, e, lang);
                    if (sub == null) return null;
                    String json = DubFinder.findAny(this, e,
                            java.util.Collections.singletonList(
                                    DubTrack.trJsonName(e.fileName, lang)), true);
                    return new String[]{sub, json};
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------- adapter

    private final class SourceAdapter extends BaseAdapter {
        @Override public int getCount() { return sources.size(); }
        @Override public Object getItem(int pos) { return sources.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View convertView, ViewGroup parent) {
            LinearLayout ll;
            TextView main, sub;
            if (convertView instanceof LinearLayout) {
                ll = (LinearLayout) convertView;
                main = (TextView) ll.getChildAt(0);
                sub = (TextView) ll.getChildAt(1);
            } else {
                ll = new LinearLayout(SourcesActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(dp(8), dp(9), dp(8), dp(9));
                main = new TextView(SourcesActivity.this);
                main.setTextColor(Color.WHITE);
                main.setTextSize(15);
                sub = new TextView(SourcesActivity.this);
                sub.setTextColor(Color.rgb(150, 150, 160));
                sub.setTextSize(12);
                ll.addView(main);
                ll.addView(sub);
            }
            SourceFinder.Source s = sources.get(pos);
            main.setText("[" + s.qualityLabel + "]  " + s.label);
            StringBuilder d = new StringBuilder();
            // Episode-identity tier badge (Addendum 11): doubt is
            // badged, never hidden; RESOLVED rows need no badge —
            // they are the promise class.
            Integer tier = epTiers.get(s.url);
            if (tier != null && tier != EpisodeMatch.TIER_RESOLVED) {
                d.append(LangStore.tr(SourcesActivity.this,
                        EpisodeMatch.tierKey(tier))).append(" · ");
            }
            // Health badge from the probe record
            HealthStore.Health hh = HealthStore.get(SourcesActivity.this, s.url);
            d.append(hh == null ? "? Not checked yet"
                    : HealthStore.badgeText(hh, System.currentTimeMillis())).append(" · ");
            d.append(s.kind);
            if (s.detail != null && !s.detail.isEmpty()) d.append(" · ").append(s.detail);
            if (s.size > 0) d.append(" · ").append(humanSize(s.size));
            if (s.subUrl != null && !s.subUrl.isEmpty()) {
                d.append(" · + subtitles");
                if (s.subLang != null && !s.subLang.isEmpty()) {
                    d.append(" (").append(s.subLang).append(")");
                }
            }
            sub.setText(d.toString());
            return ll;
        }
    }

    static String humanSize(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1e9);
        }
        if (bytes >= 1_000_000L) {
            return String.format(Locale.US, "%.0f MB", bytes / 1e6);
        }
        return bytes + " B";
    }
}
