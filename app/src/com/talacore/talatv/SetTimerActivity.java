package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Set a timer — ONE screen, the whole frame visible (v0.12.1).
 *
 * James's field report on the shipped chain (channel dialog → date
 * dialog → time dialog → duration dialog): "the timer will pick a
 * day, but it won't pick a time… needs to be a time frame, a set
 * time frame." The chain stranded the time half four modals deep —
 * behind a channel picker holding ~12,900 entries — and no screen
 * ever showed the frame as one thing. Here the channel, the DATE,
 * the START and the END stand on one screen with a live summary
 * ("Tue Oct 20 · 8:00 PM – 9:30 PM"), and one Set button commits
 * exactly what the summary says. Both doors land here: the Timers
 * screen's "Set a timer", and the guide's tap on a future
 * programme (which arrives prefilled with the programme's own
 * channel and frame, editable before commit).
 *
 * An end at or before the start is an overnight frame: TimerFrame
 * rolls the end to the next day and the summary prints both days.
 */
public class SetTimerActivity extends Activity {

    public static final String X_NAME = "chName";
    public static final String X_URL = "chUrl";
    public static final String X_TVG = "chTvg";
    public static final String X_TITLE = "title";
    public static final String X_START = "startMs";
    public static final String X_STOP = "stopMs";

    private M3uParser.Channel channel;
    private long startMs, stopMs;
    private EditText titleInput;
    private TextView channelView, summaryView;
    private Button dateBtn, startBtn, endBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildUi();
        } catch (Exception e) {
            CrashGuard.log(this, "SetTimerActivity.onCreate", e);
            Toast.makeText(this, "The timer screen couldn't open — "
                    + "the failure was filed in Settings ▸ Crash reports.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "timer_set_btn"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        channelView = new TextView(this);
        channelView.setTextColor(Color.rgb(200, 200, 210));
        channelView.setTextSize(15);
        channelView.setPadding(0, dp(12), 0, dp(4));
        root.addView(channelView);
        Button pickBtn = new Button(this);
        pickBtn.setText(LangStore.tr(this, "timer_channel"));
        pickBtn.setOnClickListener(v -> pickChannel());
        root.addView(pickBtn);

        dateBtn = frameButton(root, LangStore.tr(this, "timer_date"),
                v -> pickDate());
        startBtn = frameButton(root, LangStore.tr(this, "timer_start"),
                v -> pickTime(true));
        endBtn = frameButton(root, LangStore.tr(this, "timer_end"),
                v -> pickTime(false));

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"30 min", "1 h", "1½ h", "2 h", "3 h"};
        long[] mins = {30, 60, 90, 120, 180};
        for (int i = 0; i < labels.length; i++) {
            final long m = mins[i];
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setOnClickListener(v -> {
                stopMs = TimerFrame.stopFromDuration(startMs, m);
                refresh();
            });
            chips.addView(b, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        root.addView(chips);

        titleInput = new EditText(this);
        titleInput.setHint("Programme name (optional)");
        titleInput.setTextColor(Color.WHITE);
        titleInput.setHintTextColor(Color.rgb(150, 150, 160));
        root.addView(titleInput);

        summaryView = new TextView(this);
        summaryView.setTextColor(Color.rgb(150, 220, 150));
        summaryView.setTextSize(15);
        summaryView.setPadding(0, dp(12), 0, dp(12));
        root.addView(summaryView);

        Button setBtn = new Button(this);
        setBtn.setText(LangStore.tr(this, "timer_set_btn"));
        setBtn.setOnClickListener(v -> commit());
        root.addView(setBtn);

        setContentView(root);
        initFrame();
    }

    private Button frameButton(LinearLayout root, String label,
                               android.view.View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextColor(Color.rgb(170, 170, 180));
        lab.setTextSize(14);
        row.addView(lab, new LinearLayout.LayoutParams(
                dp(64), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button b = new Button(this);
        b.setOnClickListener(onClick);
        row.addView(b, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);
        return b;
    }

    private void initFrame() {
        Bundle ex = getIntent().getExtras();
        long now = System.currentTimeMillis();
        if (ex != null && ex.containsKey(X_URL)) {
            channel = new M3uParser.Channel();
            channel.name = ex.getString(X_NAME, "");
            channel.url = ex.getString(X_URL, "");
            channel.tvgId = ex.getString(X_TVG, "");
            startMs = ex.getLong(X_START, now + 3600_000L);
            stopMs = ex.getLong(X_STOP, startMs + 3600_000L);
            titleInput.setText(ex.getString(X_TITLE, ""));
        } else {
            // Default: starts at the next whole hour, one hour long.
            Calendar c = Calendar.getInstance();
            c.add(Calendar.HOUR_OF_DAY, 1);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            startMs = c.getTimeInMillis();
            stopMs = startMs + 3600_000L;
        }
        if (stopMs <= startMs) stopMs = startMs + 3600_000L;
        refresh();
    }

    private void refresh() {
        TimeZone zone = TimeZone.getDefault();
        channelView.setText(channel == null
                ? LangStore.tr(this, "timer_channel") + ": —"
                : LangStore.tr(this, "timer_channel") + ": "
                        + channel.displayName());
        dateBtn.setText(GuideModel.formatDay(startMs, zone));
        startBtn.setText(GuideModel.formatTime(startMs, zone));
        endBtn.setText(GuideModel.formatTime(stopMs, zone)
                + (TimerFrame.spansMidnight(startMs, stopMs, zone)
                        ? " (" + GuideModel.formatDay(stopMs, zone) + ")" : ""));
        summaryView.setText("● " + TimerFrame.summary(startMs, stopMs, zone));
    }

    // ------------------------------------------------------------ picks

    private void pickChannel() {
        Set<String> favs = ChannelStore.getFavorites(this);
        List<M3uParser.Channel> all = ChannelStore.loadCache(this);
        if (all == null) all = new ArrayList<>();
        List<M3uParser.Channel> chans = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<M3uParser.Channel> rest = new ArrayList<>();
        for (M3uParser.Channel c : all) {
            if (c.url == null || c.url.isEmpty() || !seen.add(c.url)) continue;
            (favs.contains(c.url) ? chans : rest).add(c);
        }
        chans.addAll(rest);
        if (chans.isEmpty()) {
            Toast.makeText(this, "No channels loaded yet — open Live TV first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(this);
        search.setHint(LangStore.tr(this, "timer_search_channels"));
        box.addView(search);
        ListView lv = new ListView(this);
        box.addView(lv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        List<M3uParser.Channel> shown = new ArrayList<>(chans);
        List<String> names = new ArrayList<>();
        for (M3uParser.Channel c : shown) names.add(c.displayName());
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, names);
        lv.setAdapter(ad);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "timer_channel"))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, (d, w) -> { })
                .create();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                String q = s.toString().trim().toLowerCase();
                shown.clear();
                names.clear();
                for (M3uParser.Channel ch : chans) {
                    if (q.isEmpty() || ch.displayName().toLowerCase().contains(q)
                            || (ch.name != null && ch.name.toLowerCase().contains(q))) {
                        shown.add(ch);
                        names.add(ch.displayName());
                    }
                }
                ad.notifyDataSetChanged();
            }
        });
        lv.setOnItemClickListener((p, v, pos, id) -> {
            channel = shown.get(pos);
            dlg.dismiss();
            refresh();
        });
        dlg.show();
    }

    private void pickDate() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startMs);
        new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar day = Calendar.getInstance();
            day.set(y, m, d, 0, 0, 0);
            day.set(Calendar.MILLISECOND, 0);
            Calendar s = Calendar.getInstance();
            s.setTimeInMillis(startMs);
            long dur = stopMs - startMs;
            s.set(y, m, d);
            startMs = s.getTimeInMillis();
            stopMs = startMs + dur;
            refresh();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pickTime(boolean start) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(start ? startMs : stopMs);
        new TimePickerDialog(this, (v, h, min) -> {
            if (start) {
                long dur = stopMs - startMs;
                Calendar s = Calendar.getInstance();
                s.setTimeInMillis(startMs);
                s.set(Calendar.HOUR_OF_DAY, h);
                s.set(Calendar.MINUTE, min);
                s.set(Calendar.SECOND, 0);
                s.set(Calendar.MILLISECOND, 0);
                startMs = s.getTimeInMillis();
                stopMs = startMs + dur;
            } else {
                Calendar day = Calendar.getInstance();
                day.setTimeInMillis(startMs);
                day.set(Calendar.HOUR_OF_DAY, 0);
                day.set(Calendar.MINUTE, 0);
                day.set(Calendar.SECOND, 0);
                day.set(Calendar.MILLISECOND, 0);
                long[] frame = TimerFrame.compose(day.getTimeInMillis(),
                        hourOf(startMs), minuteOf(startMs), h, min,
                        TimeZone.getDefault());
                stopMs = frame[1];
            }
            refresh();
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show();
    }

    private int hourOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    private int minuteOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.MINUTE);
    }

    // ----------------------------------------------------------- commit

    private void commit() {
        if (channel == null) {
            Toast.makeText(this, "Pick a channel first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (stopMs <= System.currentTimeMillis()) {
            Toast.makeText(this, "That time is already past — pick a "
                    + "future time.", Toast.LENGTH_LONG).show();
            return;
        }
        String t = titleInput.getText().toString().trim();
        TimerUi.commit(this, TimerUi.build(channel.displayName(), channel.url,
                GuideStore.baseId(channel.tvgId), t, startMs, stopMs),
                this::finish);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
