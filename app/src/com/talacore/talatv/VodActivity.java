package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Movies & VOD that already live inside the loaded playlists: entries on
 * Movies/Series/VOD shelves. Movies open the source picker (the entry
 * itself plus any archive matches); series episodes are grouped by show
 * and ordered by their parsed season/episode numbers.
 *
 * v0.3.0: health badges on every row (from the persisted probe record),
 * a "Working links only" toggle (hides only probe-proven dead links),
 * and a probe before a series episode plays directly — a dead tap is
 * marked ✗ and explained instead of handed to the player.
 */
public class VodActivity extends Activity {

    private static final Pattern MARKER =
            Pattern.compile("(?i)S\\d{1,2}[\\s._\\-]*E\\d{1,3}|\\b\\d{1,2}x\\d{1,3}\\b");

    /** Rows: String = section header, Channel = entry. */
    private final List<Object> rows = new ArrayList<>();
    private final List<M3uParser.Channel> rowChannels = new ArrayList<>();
    private final List<M3uParser.Channel> allVod = new ArrayList<>();
    private final ExecutorService tapExec = Executors.newSingleThreadExecutor();

    private TextView sub;
    private TextView scanLine;
    private CheckBox workingOnlyBox;
    private BaseAdapter adapter;
    private boolean probing = false;

    // v0.17.0: the scan ticker, same pattern as the other rooms.
    private final Handler scanHandler = new Handler();
    private int scanSeenDone = -1;
    private boolean scanWasRunning = false;
    private final Runnable scanTick = new Runnable() {
        @Override public void run() {
            tickScan();
            scanHandler.postDelayed(this, 600);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // v0.17.0 THE SANCTUARY LAYOUT: the sanctuary ground.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        root.setPadding(dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP),
                dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP));

        TextView title = new TextView(this);
        title.setText("Movies & VOD — from your playlists");
        title.setTextSize(19);
        Sanctuary.goldText(title);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        sub = new TextView(this);
        sub.setTextColor(Sanctuary.MUTED);
        sub.setTextSize(12);
        root.addView(sub);

        workingOnlyBox = new CheckBox(this);
        workingOnlyBox.setText("Working links only (hide proven-dead)");
        workingOnlyBox.setTextColor(Sanctuary.BODY);
        workingOnlyBox.setChecked(ChannelStore.getWorkingFirst(this));
        workingOnlyBox.setOnCheckedChangeListener((b, checked) -> {
            ChannelStore.setWorkingFirst(this, checked);
            buildRows();
        });
        root.addView(workingOnlyBox);

        // v0.17.0 THE CHANNEL SCAN: the sweep stands on the
        // catalog shelf too — its links are the same inventory.
        Button scanBtn = new Button(this);
        scanBtn.setText(LangStore.tr(this, "scan_action"));
        scanBtn.setContentDescription(LangStore.tr(this, "scan_action"));
        Sanctuary.styleButton(this, scanBtn, false);
        scanBtn.setOnClickListener(v -> startScan());
        root.addView(scanBtn);
        scanLine = new TextView(this);
        scanLine.setTextColor(Sanctuary.SUB);
        scanLine.setTextSize(12);
        root.addView(scanLine);

        ListView list = new ListView(this);
        list.setBackgroundColor(Sanctuary.GROUND);
        list.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        list.setDividerHeight(1);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        List<M3uParser.Channel> channels = ChannelStore.loadCache(this);
        if (channels == null) {
            sub.setText("The channel guide hasn't been loaded yet — open Live TV once "
                    + "and this shelf fills from your playlists.");
            workingOnlyBox.setVisibility(View.GONE);
            return;
        }
        for (M3uParser.Channel c : channels) {
            if (SourceFinder.isVodEntry(c)) allVod.add(c);
        }

        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int pos) { return rows.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public boolean isEnabled(int pos) {
                return rows.get(pos) instanceof M3uParser.Channel;
            }
            @Override public int getViewTypeCount() { return 2; }
            @Override public int getItemViewType(int pos) {
                return rows.get(pos) instanceof String ? 0 : 1;
            }
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                Object row = rows.get(pos);
                if (row instanceof String) {
                    TextView tv = convertView instanceof TextView
                            ? (TextView) convertView : new TextView(VodActivity.this);
                    tv.setText((String) row);
                    tv.setTextColor(Sanctuary.GOLD_TEXT);
                    tv.setTextSize(14);
                    tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
                    tv.setPadding(dp(6), dp(12), dp(6), dp(4));
                    tv.setBackgroundColor(Sanctuary.PANEL_HI);
                    return tv;
                }
                M3uParser.Channel c = (M3uParser.Channel) row;
                LinearLayout ll;
                TextView main, subT;
                if (convertView instanceof LinearLayout) {
                    ll = (LinearLayout) convertView;
                    main = (TextView) ll.getChildAt(0);
                    subT = (TextView) ll.getChildAt(1);
                } else {
                    ll = new LinearLayout(VodActivity.this);
                    ll.setOrientation(LinearLayout.VERTICAL);
                    ll.setPadding(dp(8), dp(8), dp(8), dp(8));
                    main = new TextView(VodActivity.this);
                    main.setTextColor(Sanctuary.INK);
                    main.setTextSize(15);
                    subT = new TextView(VodActivity.this);
                    subT.setTextColor(Sanctuary.SUB);
                    subT.setTextSize(12);
                    ll.addView(main);
                    ll.addView(subT);
                }
                HealthStore.Health vh = HealthStore.get(VodActivity.this, c.url);
                main.setText(HealthStore.badgeSymbol(vh) + c.displayName());
                ll.setContentDescription(c.displayName());
                subT.setText((vh == null ? "? Not checked yet"
                        : HealthStore.badgeText(vh, System.currentTimeMillis())) + "  ·  "
                        + c.group + "  ·  " + c.source + "  ·  "
                        + EpisodeKey.qualityLabel(c.name + " " + c.url));
                return ll;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((p, v, pos, id) -> {
            M3uParser.Channel c = rowChannels.get(pos);
            if (c == null) return;
            if (EpisodeKey.parse(c.name) != null) {
                playDirect(c);
            } else {
                Intent i = new Intent(this, SourcesActivity.class);
                i.putExtra("mode", "movie");
                i.putExtra("movieTitle", SourceFinder.cleanTitleForSearch(c.name));
                i.putExtra("directUrl", c.url);
                i.putExtra("directName", c.name);
                i.putExtra("directUa", c.userAgent);
                startActivity(i);
            }
        });

        buildRows();
    }

    /** (Re)build the grouped rows from allVod, honoring the health toggle. */
    private void buildRows() {
        boolean workingOnly = workingOnlyBox != null && workingOnlyBox.isChecked();
        rows.clear();
        rowChannels.clear();

        List<M3uParser.Channel> movies = new ArrayList<>();
        Map<String, List<M3uParser.Channel>> series = new LinkedHashMap<>();
        int hiddenDead = 0;
        int scanRemoved = 0;
        for (M3uParser.Channel c : allVod) {
            // v0.17.0 THE CHANNEL SCAN: scan-dead entries are OFF
            // this shelf entirely — the counts printed below are
            // counts of what stands here, nothing else.
            if (ScanStore.verdictOf(this, c.url) == ChannelScan.V_DEAD) {
                scanRemoved++;
                continue;
            }
            if (workingOnly) {
                HealthStore.Health h = HealthStore.get(this, c.url);
                if (h != null && h.tier == LinkHealth.TIER_DEAD) {
                    hiddenDead++;
                    continue;
                }
            }
            EpisodeKey key = EpisodeKey.parse(c.name);
            if (key == null) {
                movies.add(c);
            } else {
                String showTitle = showTitleOf(c.name);
                series.computeIfAbsent(showTitle, k -> new ArrayList<>()).add(c);
            }
        }
        // Mom-first order for the flat movie list (v0.4.0): plays first,
        // connects-only and dead sink. Series keep S/E order — an
        // episode list out of order would be its own kind of broken.
        Collections.sort(movies, (a, b) -> {
            int ra = HealthStore.surfRank(HealthStore.get(this, a.url));
            int rb = HealthStore.surfRank(HealthStore.get(this, b.url));
            if (ra != rb) return Integer.compare(ra, rb);
            return a.name.compareToIgnoreCase(b.name);
        });

        int episodeTotal = 0;
        List<String> showNames = new ArrayList<>(series.keySet());
        Collections.sort(showNames, String.CASE_INSENSITIVE_ORDER);
        for (String sn : showNames) {
            List<M3uParser.Channel> eps = series.get(sn);
            Collections.sort(eps, (a, b) -> {
                EpisodeKey ka = EpisodeKey.parse(a.name);
                EpisodeKey kb = EpisodeKey.parse(b.name);
                int sa = ka == null ? 0 : ka.season, sb = kb == null ? 0 : kb.season;
                if (sa != sb) return sa - sb;
                int ea = ka == null ? 0 : ka.episode, eb = kb == null ? 0 : kb.episode;
                if (ea != eb) return ea - eb;
                return a.name.compareToIgnoreCase(b.name);
            });
            episodeTotal += eps.size();
            rows.add("SERIES — " + sn + "  (" + eps.size() + ")");
            rowChannels.add(null);
            for (M3uParser.Channel c : eps) {
                rows.add(c);
                rowChannels.add(c);
            }
        }
        if (!movies.isEmpty()) {
            rows.add("MOVIES  (" + movies.size() + ")");
            rowChannels.add(null);
            for (M3uParser.Channel c : movies) {
                rows.add(c);
                rowChannels.add(c);
            }
        }

        sub.setText(movies.size() + " movies · " + showNames.size() + " series · "
                + episodeTotal + " episodes found on your playlists' on-demand shelves. "
                + (workingOnly && hiddenDead > 0 ? hiddenDead + " dead hidden. " : "")
                + (scanRemoved > 0 ? scanRemoved + " removed by scan. " : "")
                + "The built-in directory is mostly live channels — this shelf grows "
                + "with playlists that carry VOD sections.");
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    /**
     * A series entry on a playlist is already a source — but probe it
     * before playing (v0.3.0): the player opens only when it answers.
     */
    private void playDirect(M3uParser.Channel c) {
        if (!LinkHealth.isRemote(c.url)) {
            launchDirect(c);
            return;
        }
        if (probing) return;
        probing = true;
        Toast.makeText(this, "Checking " + c.name + "…", Toast.LENGTH_SHORT).show();
        tapExec.execute(() -> {
            LinkHealth.Result r = LinkHealth.probe(c.url, c.userAgent, 6000);
            HealthStore.record(this, c.url, r);
            runOnUiThread(() -> {
                probing = false;
                if (adapter != null) adapter.notifyDataSetChanged();
                if (r.tier == LinkHealth.TIER_PLAYS) {
                    launchDirect(c);
                } else if (r.tier == LinkHealth.TIER_CONNECTS) {
                    Toast.makeText(this, "That link answers, but its video is "
                            + "unproven — trying it.", Toast.LENGTH_LONG).show();
                    launchDirect(c);
                } else {
                    Toast.makeText(this, "That link is dead (" + r.detail + ") — marked ✗",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void launchDirect(M3uParser.Channel c) {
        List<M3uParser.Channel> queue = new ArrayList<>();
        queue.add(c);
        ChannelStore.playQueue = queue;
        ChannelStore.playIndex = 0;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", c.url);
        i.putExtra("name", c.name);
        i.putExtra("ua", c.userAgent);
        i.putExtra("ckey", ChannelStore.titleKey(c.name));
        startActivity(i);
    }

    /** "Show Name S01E02 …" -> "Show Name" (marker and everything after it dropped). */
    static String showTitleOf(String entryName) {
        Matcher m = MARKER.matcher(entryName);
        if (m.find()) {
            String head = entryName.substring(0, m.start()).trim();
            head = head.replaceAll("[\\s._\\-]+$", "").trim();
            if (!head.isEmpty()) return head;
        }
        return entryName;
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateScanLine();
        scanHandler.removeCallbacks(scanTick);
        scanHandler.post(scanTick);
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

    /** As verdicts land, this shelf follows them — progressively. */
    private void tickScan() {
        boolean running = ScanEngine.isRunning();
        updateScanLine();
        int done = ScanEngine.progress()[0];
        boolean finished = scanWasRunning && !running;
        if ((running && scanSeenDone >= 0 && done - scanSeenDone >= 20)
                || finished) {
            scanSeenDone = done;
            buildRows();
        } else if (running && scanSeenDone < 0) {
            scanSeenDone = done;
        }
        scanWasRunning = running;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
