package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The TV Guide (v0.11.0; restructured v0.12.1): what's on now,
 * what's next, and the day's schedule for Sandy's Live TV channels
 * — in the phone's own local time, from hosted XMLTV filed through
 * the memory hook.
 *
 * Coverage is what it is, and it is printed on the face: the header
 * says how many of her channels carry guide data and when the data
 * was fetched and through when it runs; a channel with none says
 * "No guide for this channel" on its own row. Nothing is invented
 * to fill a gap.
 *
 * v0.12.1 restructure (James's field report: "the TV Guide is not
 * coming up"): the shipped screen parsed the whole channel cache
 * on the main thread, then — also on the main thread — linear-
 * scanned the 1,345-row guide map once per channel (~17M compares
 * for a full cache) before the first row could paint. On a phone
 * that reads as a guide that never opens, and courts the system
 * "keeps stopping" dialog. Now: the shell paints immediately;
 * channels, freshness state and guide data load on the executor;
 * bridge lookups are the GuideStore's O(1) index; binding touches
 * only values already in memory. And EVERY path lands somewhere
 * visible — data, cached data with its freshness line, or an
 * honest empty state. The screen itself is never the failure, and
 * a failure while opening is filed to the crash log (Settings ▸
 * Crash reports) instead of taking the app down.
 *
 * A future programme can be set to record right from its row: the
 * tap lands on the set-timer screen with the programme's channel
 * and frame prefilled (TimerFrame visible before commit).
 */
public class GuideActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<M3uParser.Channel> channels = new ArrayList<>();
    private final List<String> rowTitles = new ArrayList<>();
    private final List<String> rowSub = new ArrayList<>();

    private TextView headerView;
    private TextView freshnessView;
    private ListView listView;
    private ProgressBar progress;
    private ArrayAdapter<String> adapter;

    private GuideModel.GuideData data;
    private Map<String, GuideStore.SourceState> stateCache = new HashMap<>();
    private volatile boolean channelsLoaded = false;
    private volatile boolean loadFailed = false;
    private boolean dayMode = false;
    private M3uParser.Channel dayChannel;
    private List<GuideModel.Programme> dayRows = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Exception e) {
            CrashGuard.log(this, "GuideActivity.onCreate", e);
            Toast.makeText(this, "The guide couldn't open — the failure "
                    + "was filed in Settings ▸ Crash reports.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        refresh(false);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        root.setPadding(dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP), dp(Sanctuary.PAGE_PAD_DP));

        headerView = new TextView(this);
        headerView.setText(LangStore.tr(this, "guide_title"));
        headerView.setTextSize(19);
        Sanctuary.goldText(headerView);
        headerView.setTypeface(headerView.getTypeface(), Typeface.BOLD);
        root.addView(headerView);

        freshnessView = new TextView(this);
        freshnessView.setTextColor(Sanctuary.MUTED);
        freshnessView.setTextSize(12);
        root.addView(freshnessView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button timersBtn = new Button(this);
        timersBtn.setText(LangStore.tr(this, "timers_btn"));
        Sanctuary.styleButton(this, timersBtn, false);
        timersBtn.setOnClickListener(v -> startActivity(
                new Intent(this, TimersActivity.class)));
        buttons.addView(timersBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refreshBtn = new Button(this);
        refreshBtn.setText(LangStore.tr(this, "refresh"));
        Sanctuary.styleButton(this, refreshBtn, false);
        refreshBtn.setOnClickListener(v -> refresh(true));
        buttons.addView(refreshBtn, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setProgressTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        progress.setIndeterminateTintList(ColorStateList.valueOf(Sanctuary.GOLD));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        listView = new ListView(this);
        listView.setBackgroundColor(Sanctuary.GROUND);
        listView.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        listView.setDividerHeight(1);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1,
                rowTitles) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                TextView t1 = v.findViewById(android.R.id.text1);
                t1.setText(rowTitles.get(pos));
                t1.setTextColor(Sanctuary.INK);
                TextView t2 = v.findViewById(android.R.id.text2);
                t2.setText(pos < rowSub.size() ? rowSub.get(pos) : "");
                t2.setTextColor(Sanctuary.SUB);
                return v;
            }
            @Override public int getCount() { return rowTitles.size(); }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> onRowTap(pos));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            if (!dayMode) {
                int idx = rowIndexToChannel(pos);
                if (idx >= 0) {
                    synchronized (channels) { play(channels.get(idx)); }
                    return true;
                }
            }
            return false;
        });
        setContentView(root);
        freshnessView.setText(LangStore.tr(this, "guide_loading"));
    }

    // ------------------------------------------------------------ data

    /** All loading happens here, off the UI thread. */
    private void loadChannelsOffThread() {
        Set<String> favs = ChannelStore.getFavorites(this);
        List<M3uParser.Channel> all = ChannelStore.loadCache(this);
        if (all == null) all = new ArrayList<>();
        List<M3uParser.Channel> fav = new ArrayList<>();
        List<M3uParser.Channel> rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (M3uParser.Channel c : all) {
            if (c.url == null || c.url.isEmpty() || !seen.add(c.url)) continue;
            (favs.contains(c.url) ? fav : rest).add(c);
        }
        synchronized (channels) {
            channels.clear();
            channels.addAll(fav);
            channels.addAll(rest);
        }
        channelsLoaded = true;
    }

    /** Fetch what's stale (or everything, when forced), then rebind. */
    private void refresh(boolean force) {
        progress.setVisibility(View.VISIBLE);
        exec.execute(() -> {
            GuideStore.FetchResult fres = null;
            try {
                if (!channelsLoaded) loadChannelsOffThread();
                Set<String> baseIds = new HashSet<>();
                synchronized (channels) {
                    for (M3uParser.Channel c : channels) {
                        baseIds.add(GuideStore.baseId(c.tvgId));
                    }
                }
                List<String> files = GuideStore.filesFor(this, baseIds);
                Map<String, GuideStore.SourceState> st = GuideStore.state(this);
                long now = System.currentTimeMillis();
                List<String> want = new ArrayList<>();
                for (String f : files) {
                    if (force || GuideStore.stale(st.get(f), now)) want.add(f);
                }
                if (!want.isEmpty()) fres = GuideStore.fetch(this, want);
                stateCache = GuideStore.state(this);
                data = GuideStore.data(this);
                loadFailed = false;
            } catch (Exception e) {
                // The screen must still land somewhere honest: what
                // is already filed (cache) if anything, else the
                // empty state — with the failure on the crash log.
                CrashGuard.log(this, "GuideActivity.refresh", e);
                try {
                    stateCache = GuideStore.state(this);
                    data = GuideStore.data(this);
                } catch (Exception ignored) { }
                loadFailed = true;
            }
            final GuideStore.FetchResult res = fres;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                bindChannels();
                if (force && res != null) {
                    Toast.makeText(this, LangStore.tr(this, "guide_updated")
                            + " — " + res.filesOk + " sources, "
                            + res.programmes + " programmes"
                            + (res.filesFailed > 0
                                    ? " (" + res.filesFailed + " failed)" : ""),
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // ------------------------------------------------------------ rows

    private void bindChannels() {
        dayMode = false;
        headerView.setText(LangStore.tr(this, "guide_title"));
        rowTitles.clear();
        rowSub.clear();
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();

        List<M3uParser.Channel> chans;
        synchronized (channels) { chans = new ArrayList<>(channels); }

        if (chans.isEmpty()) {
            freshnessView.setText(LangStore.tr(this, "guide_no_channels"));
            rowTitles.add(LangStore.tr(this, "guide_no_channels"));
            rowSub.add("");
            adapter.notifyDataSetChanged();
            return;
        }

        int covered = 0;
        if (data != null) {
            for (M3uParser.Channel c : chans) {
                if (data.hasGuide(GuideStore.baseId(c.tvgId))) covered++;
            }
        }
        long oldest = Long.MAX_VALUE;
        for (GuideStore.SourceState ss : stateCache.values()) {
            if (ss.fetchedAtMs > 0 && ss.fetchedAtMs < oldest) {
                oldest = ss.fetchedAtMs;
            }
        }
        StringBuilder fresh = new StringBuilder();
        fresh.append(covered).append(" / ").append(chans.size()).append(' ')
             .append(LangStore.tr(this, "guide_covers"));
        if (oldest != Long.MAX_VALUE) {
            fresh.append(" · ").append(LangStore.tr(this, "guide_updated"))
                 .append(' ').append(GuideModel.formatTime(oldest, zone));
        }
        if (data != null && data.throughMs > 0) {
            fresh.append(" · data through ")
                 .append(GuideModel.formatDay(data.throughMs, zone));
        }
        if (loadFailed) {
            fresh.append(" · ").append(LangStore.tr(this, "guide_load_failed"));
        }
        freshnessView.setText(fresh.toString());

        if (covered == 0) {
            rowTitles.add(LangStore.tr(this, "guide_empty"));
            rowSub.add("");
            // The channel rows still follow — no guide data is a
            // fact about the data, never a reason to hide the
            // channels themselves (a tap plays, as before).
        }
        for (M3uParser.Channel c : chans) {
            rowTitles.add(c.displayName());
            String base = GuideStore.baseId(c.tvgId);
            if (data == null || !data.hasGuide(base)) {
                rowSub.add(LangStore.tr(this, "guide_no_guide"));
                continue;
            }
            GuideModel.Programme n = data.now(base, now);
            GuideModel.Programme x = data.next(base, now);
            if (n == null && x == null) {
                rowSub.add(LangStore.tr(this, "guide_no_info"));
            } else {
                StringBuilder sb = new StringBuilder();
                if (n != null) {
                    sb.append("▶ ").append(LangStore.tr(this, "guide_now"))
                      .append(": ").append(n.title)
                      .append(" (–").append(GuideModel.formatTime(n.stopMs, zone))
                      .append(')');
                }
                if (x != null) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(LangStore.tr(this, "guide_next")).append(": ")
                      .append(x.title).append(' ')
                      .append(GuideModel.formatTime(x.startMs, zone));
                }
                rowSub.add(sb.toString());
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void bindDay(M3uParser.Channel c) {
        dayMode = true;
        dayChannel = c;
        headerView.setText(c.displayName());
        rowTitles.clear();
        rowSub.clear();
        TimeZone zone = TimeZone.getDefault();
        java.util.Calendar cal = java.util.Calendar.getInstance(zone);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1);
        long dayLen = cal.getTimeInMillis() - dayStart; // 23h/25h on DST days
        freshnessView.setText(GuideModel.formatDay(dayStart, zone)
                + " — " + LangStore.tr(this, "guide_title"));
        dayRows = data == null ? new ArrayList<>()
                : data.day(GuideStore.baseId(c.tvgId), dayStart, dayLen);
        long now = System.currentTimeMillis();
        for (GuideModel.Programme p : dayRows) {
            String mark = p.covers(now) ? "▶ " : (p.startMs > now ? "⏰ " : "");
            rowTitles.add(mark + GuideModel.formatTime(p.startMs, zone)
                    + "–" + GuideModel.formatTime(p.stopMs, zone)
                    + "  " + p.title);
            rowSub.add(p.desc.isEmpty() ? p.category : p.desc);
        }
        if (dayRows.isEmpty()) {
            rowTitles.add(LangStore.tr(this, "guide_no_info"));
            rowSub.add("");
        }
        adapter.notifyDataSetChanged();
    }

    private void onRowTap(int pos) {
        if (dayMode) {
            if (pos >= dayRows.size()) return;
            GuideModel.Programme p = dayRows.get(pos);
            long now = System.currentTimeMillis();
            if (p.covers(now)) {
                play(dayChannel);
            } else if (p.startMs > now) {
                confirmRecord(p);
            } else {
                Toast.makeText(this, "That one already aired.",
                        Toast.LENGTH_SHORT).show();
            }
            return;
        }
        // Channel rows are offset by the empty-state banner row when
        // nothing is covered.
        int idx = rowIndexToChannel(pos);
        if (idx < 0) return;
        M3uParser.Channel c;
        synchronized (channels) {
            if (idx >= channels.size()) return;
            c = channels.get(idx);
        }
        String base = GuideStore.baseId(c.tvgId);
        if (data != null && data.hasGuide(base)) {
            bindDay(c);
        } else {
            play(c);
        }
    }

    /** Row position → channel index, accounting for the banner row
     *  bindChannels adds when no channel is covered (and for the
     *  no-channels state, where no row is a channel). */
    private int rowIndexToChannel(int pos) {
        List<M3uParser.Channel> chans;
        synchronized (channels) { chans = new ArrayList<>(channels); }
        if (chans.isEmpty()) return -1;
        boolean banner = data == null || coveredCount(chans) == 0;
        int idx = banner ? pos - 1 : pos;
        return idx >= 0 && idx < chans.size() ? idx : -1;
    }

    private int coveredCount(List<M3uParser.Channel> chans) {
        int n = 0;
        if (data != null) {
            for (M3uParser.Channel c : chans) {
                if (data.hasGuide(GuideStore.baseId(c.tvgId))) n++;
            }
        }
        return n;
    }

    /** A future programme lands on the set-timer screen with its
     *  channel and frame prefilled — the frame is VISIBLE (and
     *  editable) before anything is committed (v0.12.1). */
    private void confirmRecord(GuideModel.Programme p) {
        Intent i = new Intent(this, SetTimerActivity.class);
        i.putExtra(SetTimerActivity.X_NAME, dayChannel.displayName());
        i.putExtra(SetTimerActivity.X_URL, dayChannel.url);
        i.putExtra(SetTimerActivity.X_TVG, GuideStore.baseId(dayChannel.tvgId));
        i.putExtra(SetTimerActivity.X_TITLE, p.title);
        i.putExtra(SetTimerActivity.X_START, p.startMs);
        i.putExtra(SetTimerActivity.X_STOP, p.stopMs);
        startActivity(i);
    }

    private void play(M3uParser.Channel c) {
        synchronized (channels) {
            ChannelStore.playQueue = new ArrayList<>(channels);
        }
        ChannelStore.playIndex = Math.max(0, ChannelStore.playQueue.indexOf(c));
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", c.url);
        i.putExtra("name", c.displayName());
        i.putExtra("ua", c.userAgent);
        startActivity(i);
    }

    @Override
    public void onBackPressed() {
        if (dayMode) {
            bindChannels();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimerReconcile.run(getApplicationContext());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
