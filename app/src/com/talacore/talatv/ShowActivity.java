package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One show's page: poster, plain-language facts, seasons, and the episode
 * list in order — with watched ✓ and resume markers per episode. Tapping
 * an episode goes to the source picker; nothing plays from here directly,
 * because picking the source is the user's call (the TeaTV rule).
 */
public class ShowActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private int showId;
    private TvMaze.Show show;
    private List<TvMaze.Episode> allEpisodes = new ArrayList<>();
    private List<TvMaze.Episode> shownEpisodes = new ArrayList<>();
    private List<Integer> seasons = new ArrayList<>();

    private TextView nameView, metaView, summaryView, statusView;
    private TextView catalogBanner;
    private ImageView posterView;
    private Button watchlistBtn;
    private Spinner seasonSpinner;
    private ListView episodeList;
    private ProgressBar progress;
    private EpisodeAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showId = getIntent().getIntExtra("showId", -1);
        if (showId < 0) { finish(); return; }
        String preName = getIntent().getStringExtra("showName");
        String preImage = getIntent().getStringExtra("showImage");

        // v0.17.0 THE SANCTUARY LAYOUT: the sanctuary ground.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        root.setPadding(dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP),
                dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        posterView.setBackground(Sanctuary.posterFrame(this));
        Sanctuary.roundClip(this, posterView, Sanctuary.TILE_RADIUS_DP);
        header.addView(posterView, new LinearLayout.LayoutParams(dp(104), dp(146)));
        if (preImage != null) ImageCache.load(this, preImage, posterView);

        LinearLayout headText = new LinearLayout(this);
        headText.setOrientation(LinearLayout.VERTICAL);
        headText.setPadding(dp(12), 0, 0, 0);
        nameView = new TextView(this);
        nameView.setText(preName == null ? "" : preName);
        nameView.setTextColor(Sanctuary.INK);
        nameView.setTextSize(20);
        nameView.setTypeface(nameView.getTypeface(), Typeface.BOLD);
        headText.addView(nameView);
        metaView = new TextView(this);
        metaView.setTextColor(Sanctuary.MUTED);
        metaView.setTextSize(13);
        headText.addView(metaView);
        watchlistBtn = new Button(this);
        Sanctuary.styleButton(this, watchlistBtn, false);
        watchlistBtn.setOnClickListener(v -> toggleWatchlist());
        headText.addView(watchlistBtn);
        header.addView(headText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        summaryView = new TextView(this);
        summaryView.setTextColor(Sanctuary.BODY);
        summaryView.setTextSize(13);
        summaryView.setMaxLines(5);
        summaryView.setPadding(0, dp(8), 0, 0);
        root.addView(summaryView);

        // Display Honesty Law (v0.14.0): when this show is
        // catalogue-only — the catalogue knows it exists, but no
        // playable copy stands on the shelves — the page says so
        // at the top, in plain words, BEFORE any episode tap.
        // The tap is never where the viewer learns the truth.
        catalogBanner = new TextView(this);
        catalogBanner.setText(LangStore.tr(this, "wall_catalog_only")
                + " — " + LangStore.tr(this, "show_catalog_only_note"));
        catalogBanner.setTextColor(Sanctuary.GOLD_TEXT);
        catalogBanner.setTextSize(14);
        catalogBanner.setPadding(dp(10), dp(8), dp(10), dp(8));
        // Smoked glass for the honesty banner (v0.17.0) — the
        // words carry the warning; the room stays the room.
        catalogBanner.setBackground(Sanctuary.glassPanel(this));
        catalogBanner.setVisibility(View.GONE);
        root.addView(catalogBanner);

        seasonSpinner = new Spinner(this);
        root.addView(seasonSpinner);
        seasonSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                rebuildEpisodes();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        progress.setIndeterminateTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        episodeList = new ListView(this);
        episodeList.setBackgroundColor(Sanctuary.GROUND);
        episodeList.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        episodeList.setDividerHeight(1);
        adapter = new EpisodeAdapter();
        episodeList.setAdapter(adapter);
        root.addView(episodeList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        episodeList.setOnItemClickListener((p, v, pos, id) -> {
            TvMaze.Episode e = shownEpisodes.get(pos);
            Intent i = new Intent(this, SourcesActivity.class);
            i.putExtra("mode", "episode");
            i.putExtra("showId", showId);
            i.putExtra("showName", show.name);
            i.putExtra("showLanguage", show.language);
            i.putExtra("season", e.season);
            i.putExtra("number", e.number);
            i.putExtra("epName", e.name);
            startActivity(i);
        });
        // v0.13.0: the voiceover door on any episode of any show —
        // picking a source then HOLDS it (the record leg) and
        // opens the dub kitchen with it already in hand
        episodeList.setOnItemLongClickListener((p, v, pos, id) -> {
            TvMaze.Episode e = shownEpisodes.get(pos);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(e.name)
                    .setItems(new CharSequence[]{
                            LangStore.tr(this, "auto_see_sources"),
                            LangStore.tr(this, "dub_make_voiceover")},
                            (d, which) -> {
                                Intent i = new Intent(this, SourcesActivity.class);
                                i.putExtra("mode", which == 0 ? "episode" : "episodeDub");
                                i.putExtra("showId", showId);
                                i.putExtra("showName", show.name);
                                i.putExtra("showLanguage", show.language);
                                i.putExtra("season", e.season);
                                i.putExtra("number", e.number);
                                i.putExtra("epName", e.name);
                                startActivity(i);
                            })
                    .show();
            return true;
        });

        statusView = new TextView(this);
        statusView.setTextColor(Sanctuary.MUTED);
        statusView.setTextSize(12);
        root.addView(statusView);

        setContentView(root);
        updateWatchlistBtn();
        loadShow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.notifyDataSetChanged(); // watched/resume marks may have changed
        updateWatchlistBtn();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateWatchlistBtn() {
        if (watchlistBtn == null) return;
        boolean in = ChannelStore.isWatchlisted(this, showId);
        watchlistBtn.setText(in ? "✓ In My Shows — tap to remove" : "+ My Shows");
    }

    private void toggleWatchlist() {
        if (show == null) return;
        boolean in = ChannelStore.isWatchlisted(this, showId);
        ChannelStore.setWatchlisted(this, show, !in);
        updateWatchlistBtn();
        Toast.makeText(this, !in ? "Added to My Shows" : "Removed from My Shows",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- loading

    private File cacheFile(String name) {
        File dir = new File(getFilesDir(), "catalog");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    private void loadShow() {
        statusView.setText("Loading show details…");
        exec.execute(() -> {
            try {
                String showJson = readFile(cacheFile("show_" + showId + ".json"));
                if (showJson == null) {
                    showJson = Net.getString(TvMaze.showUrl(showId));
                    writeFile(cacheFile("show_" + showId + ".json"), showJson);
                }
                String epsJson = readFile(cacheFile("eps_" + showId + ".json"));
                if (epsJson == null) {
                    epsJson = Net.getString(TvMaze.episodesUrl(showId));
                    writeFile(cacheFile("eps_" + showId + ".json"), epsJson);
                }
                TvMaze.Show s = TvMaze.parseShow(showJson);
                List<TvMaze.Episode> eps = TvMaze.parseEpisodes(epsJson);
                // shelf truth for the honesty banner: a loaded
                // shelf carries it, or the Archive has proven it
                boolean onShelves = false;
                try {
                    onShelves = CatalogStore.shelf(this).carries(s.name)
                            || ChannelStore.isArchiveProven(this, showId);
                } catch (Exception ignored) {}
                final boolean catalogOnly = !onShelves;
                runOnUiThread(() -> {
                    catalogBanner.setVisibility(catalogOnly
                            ? View.VISIBLE : View.GONE);
                    progress.setVisibility(View.GONE);
                    show = s;
                    allEpisodes = eps;
                    nameView.setText(s.name);
                    ImageCache.load(this, s.imageMedium, posterView);
                    String meta = String.join(" · ", s.genres)
                            + (s.language.isEmpty() ? "" : "  ·  " + s.language)
                            + (s.countryName.isEmpty() ? "" : "  ·  " + s.countryName)
                            + (s.rating > 0 ? "  ·  ★ "
                                + String.format(Locale.US, "%.1f", s.rating) : "")
                            + (s.status.isEmpty() ? "" : "  ·  " + s.status)
                            + (s.premiered.isEmpty() ? "" : "  ·  since " + s.premiered);
                    metaView.setText(meta);
                    summaryView.setText(s.summaryText);
                    seasons = new ArrayList<>();
                    for (TvMaze.Episode e : eps) {
                        if (!seasons.contains(e.season)) seasons.add(e.season);
                    }
                    Collections.sort(seasons);
                    List<String> labels = new ArrayList<>();
                    for (int sn : seasons) labels.add("Season " + sn);
                    seasonSpinner.setAdapter(new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_dropdown_item, labels));
                    statusView.setText(eps.size() + " episodes across "
                            + seasons.size() + " seasons — tap an episode to pick a source.");
                    rebuildEpisodes();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    statusView.setTextColor(Sanctuary.WARN);
                    statusView.setText("Couldn't load this show (" + shortErr(e) + ").");
                });
            }
        });
    }

    private void rebuildEpisodes() {
        shownEpisodes = new ArrayList<>();
        if (seasons.isEmpty()) { adapter.notifyDataSetChanged(); return; }
        int pos = seasonSpinner.getSelectedItemPosition();
        int season = seasons.get(Math.max(0, Math.min(pos, seasons.size() - 1)));
        for (TvMaze.Episode e : allEpisodes) {
            if (e.season == season) shownEpisodes.add(e);
        }
        Collections.sort(shownEpisodes, (a, b) -> a.number - b.number);
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------- adapter

    private final class EpisodeAdapter extends BaseAdapter {
        @Override public int getCount() { return shownEpisodes.size(); }
        @Override public Object getItem(int pos) { return shownEpisodes.get(pos); }
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
                ll = new LinearLayout(ShowActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setPadding(dp(8), dp(8), dp(8), dp(8));
                main = new TextView(ShowActivity.this);
                main.setTextColor(Sanctuary.INK);
                main.setTextSize(15);
                sub = new TextView(ShowActivity.this);
                sub.setTextColor(Sanctuary.SUB);
                sub.setTextSize(12);
                ll.addView(main);
                ll.addView(sub);
            }
            TvMaze.Episode e = shownEpisodes.get(pos);
            main.setText(String.format(Locale.US, "S%02dE%02d · %s", e.season, e.number, e.name));
            StringBuilder meta = new StringBuilder();
            if (!e.airdate.isEmpty()) meta.append(e.airdate);
            if (e.runtime > 0) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append(e.runtime).append(" min");
            }
            String key = ChannelStore.episodeKey(showId, e.season, e.number);
            if (ChannelStore.isWatched(ShowActivity.this, key)) {
                if (meta.length() > 0) meta.append("  ·  ");
                meta.append("✓ watched");
            } else {
                long posMs = ChannelStore.getPosition(ShowActivity.this, key);
                if (posMs > 10000) {
                    if (meta.length() > 0) meta.append("  ·  ");
                    meta.append("▶ resume at ").append(formatMs(posMs));
                }
            }
            sub.setText(meta.toString());
            return ll;
        }
    }

    static String formatMs(long ms) {
        long totalSec = ms / 1000;
        long m = totalSec / 60;
        long s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    private static String readFile(File f) {
        if (!f.exists()) return null;
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
