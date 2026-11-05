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
 * programme.
 *
 * v0.20.0 (James's field ruling): the guide is no longer the
 * scheduling mechanism — "just have it where people can set the
 * time in whatever channel they want to record and it'll record
 * as long as the timer set… in the background of the phone." So
 * HAND ENTRY IS DURATION-FIRST: pick a channel, a date, a start
 * time, then HOW LONG (big duration choices; stop = start +
 * duration), with "choose an end time" kept as the alternative
 * for anyone who wants the stop picker itself. The guide now
 * arrives with ONLY a channel prefilled (X_NAME/X_URL/X_TVG, no
 * title/start/stop). An intent that still carries a programme
 * frame (X_START present) is honoured and editable as before —
 * that is the prefill case, and the only one.
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
    /** True only when the intent carries a programme frame (X_START). */
    private boolean programmePrefill;
    /** The hand-entry length pick; stopMs = startMs + this, in minutes. */
    private long chosenDurationMin = 60;
    private final List<Button> durationBtns = new ArrayList<>();
    private final List<Long> durationMins = new ArrayList<>();
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
        Bundle ex0 = getIntent().getExtras();
        programmePrefill = ex0 != null && ex0.containsKey(X_START);
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

        TextView bgNote = new TextView(this);
        bgNote.setText(LangStore.tr(this, "timer_background_note"));
        bgNote.setTextColor(Color.rgb(170, 170, 180));
        bgNote.setTextSize(13);
        bgNote.setPadding(0, dp(6), 0, 0);
        root.addView(bgNote);

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
        if (!programmePrefill) {
            // Duration-first hand entry: HOW LONG is the main
            // question and the end follows from it (stop = start +
            // duration). The end-time picker stays, captioned as
            // the alternative, for anyone who wants it directly.
            TextView durLabel = new TextView(this);
            durLabel.setText(LangStore.tr(this, "timer_duration_label"));
            durLabel.setTextColor(Color.rgb(170, 170, 180));
            durLabel.setTextSize(14);
            durLabel.setPadding(0, dp(10), 0, dp(4));
            root.addView(durLabel);
            root.addView(buildDurationChips(true));
            TextView endCaption = new TextView(this);
            endCaption.setText(LangStore.tr(this, "timer_choose_end_time"));
            endCaption.setTextColor(Color.rgb(170, 170, 180));
            endCaption.setTextSize(14);
            endCaption.setPadding(0, dp(10), 0, 0);
            root.addView(endCaption);
            endBtn = frameButton(root, LangStore.tr(this, "timer_end"),
                    v -> pickTime(false));
        } else {
            endBtn = frameButton(root, LangStore.tr(this, "timer_end"),
                    v -> pickTime(false));
            root.addView(buildDurationChips(false));
        }

        titleInput = new EditText(this);
        titleInput.setHint(LangStore.tr(this, "timer_name_hint"));
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

    /** The duration choices: 30 min, 1, 2, 3, 4 hours. Picking one
     *  sets the length; the end time is derived (start + length),
     *  never picked separately unless the end picker is used. */
    private LinearLayout buildDurationChips(boolean big) {
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String[] keys = {"timer_dur_30", "timer_dur_1h", "timer_dur_2h",
                "timer_dur_3h", "timer_dur_4h"};
        long[] mins = {30, 60, 120, 180, 240};
        for (int i = 0; i < keys.length; i++) {
            final long m = mins[i];
            Button b = new Button(this);
            b.setText(LangStore.tr(this, keys[i]));
            if (big) b.setTextSize(17);
            b.setOnClickListener(v -> {
                chosenDurationMin = m;
                stopMs = TimerFrame.stopFromDuration(startMs, m);
                refresh();
            });
            durationBtns.add(b);
            durationMins.add(m);
            chips.addView(b, new LinearLayout.LayoutParams(
                    0, big ? dp(56) : ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return chips;
    }

    private void initFrame() {
        Bundle ex = getIntent().getExtras();
        long now = System.currentTimeMillis();
        if (ex != null && ex.containsKey(X_URL)) {
            // A channel may ride in alone (the guide's "record this
            // channel" door) — that is a prefill of the CHANNEL
            // only, never of the frame.
            channel = new M3uParser.Channel();
            channel.name = ex.getString(X_NAME, "");
            channel.url = ex.getString(X_URL, "");
            channel.tvgId = ex.getString(X_TVG, "");
        }
        if (programmePrefill) {
            startMs = ex.getLong(X_START, now + 3600_000L);
            stopMs = ex.getLong(X_STOP, startMs + 3600_000L);
            titleInput.setText(ex.getString(X_TITLE, ""));
        } else {
            // Hand entry default: starts at the next whole hour,
            // one hour long — the length pick takes it from there.
            Calendar c = Calendar.getInstance();
            c.add(Calendar.HOUR_OF_DAY, 1);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            startMs = c.getTimeInMillis();
            stopMs = TimerFrame.stopFromDuration(startMs, chosenDurationMin);
        }
        if (stopMs <= startMs) stopMs = startMs + 3600_000L;
        chosenDurationMin = TimerFrame.durationMinutes(startMs, stopMs);
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
        // The whole frame as ONE thing before commit: channel,
        // date, start, end, length — plus the pads line, so what
        // actually gets taped (1 min early to 2 min past) is on
        // the face too, not discovered later.
        StringBuilder sb = new StringBuilder();
        sb.append("● ");
        if (channel != null) sb.append(channel.displayName()).append(" · ");
        sb.append(TimerFrame.summary(startMs, stopMs, zone));
        sb.append(" · ").append(LangStore.tr(this, "timer_length_label"))
          .append(": ").append(lengthText(
                  TimerFrame.durationMinutes(startMs, stopMs)));
        sb.append("\n").append(LangStore.tr(this, "timer_pads_note"));
        summaryView.setText(sb.toString());
        long curMin = TimerFrame.durationMinutes(startMs, stopMs);
        for (int i = 0; i < durationBtns.size(); i++) {
            boolean sel = durationMins.get(i) == curMin;
            Button b = durationBtns.get(i);
            b.setTextColor(sel ? Color.rgb(150, 220, 150) : Color.WHITE);
            b.setTypeface(b.getTypeface(),
                    sel ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    /** "30 min", "1 h", "1 h 30 min", "4 h" — the frame's length. */
    private String lengthText(long mins) {
        if (mins < 60) return mins + " min";
        long h = mins / 60, m = mins % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
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
                // Choosing an end time directly re-derives the
                // length from the frame, so the summary and any
                // matching duration chip stay truthful.
                chosenDurationMin = TimerFrame.durationMinutes(startMs, stopMs);
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
