package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * The VCR timer book (v0.11.0): every timer in the queue file —
 * what's set, what's recording, and how each finished one ended,
 * stated plainly. Two ways in, as ordered: from the TV Guide (tap a
 * future programme), or BY HAND here — any channel, any start, any
 * length, no guide required. The manual door matters: most of
 * Sandy's channels carry no guide data, and a timer must not depend
 * on the guide existing.
 */
public class TimersActivity extends Activity {

    private final List<TimerModel.Timer> rows = new ArrayList<>();
    private final List<String> rowTitles = new ArrayList<>();
    private final List<String> rowSub = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "timers_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText(LangStore.tr(this, "timer_manual_intro"));
        intro.setTextColor(Color.rgb(170, 170, 180));
        intro.setTextSize(12);
        root.addView(intro);

        Button setBtn = new Button(this);
        setBtn.setText(LangStore.tr(this, "timer_set_btn"));
        // v0.12.1: the set flow is ONE screen with the whole frame
        // visible (SetTimerActivity) — the old four-dialog chain
        // stranded the time half behind a ~12,900-entry channel
        // picker (James's field report, 2026-10-18).
        setBtn.setOnClickListener(v -> startActivity(
                new android.content.Intent(this, SetTimerActivity.class)));
        root.addView(setBtn);

        emptyView = new TextView(this);
        emptyView.setText(LangStore.tr(this, "timers_empty"));
        emptyView.setTextColor(Color.rgb(170, 170, 180));
        emptyView.setTextSize(13);
        root.addView(emptyView);

        ListView listView = new ListView(this);
        listView.setBackgroundColor(Color.rgb(16, 16, 20));
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
                t1.setTextColor(Color.WHITE);
                TextView t2 = v.findViewById(android.R.id.text2);
                t2.setText(pos < rowSub.size() ? rowSub.get(pos) : "");
                t2.setTextColor(Color.rgb(150, 150, 160));
                return v;
            }
            @Override public int getCount() { return rowTitles.size(); }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> {
            TimerModel.Timer t = rows.get(pos);
            if (t.state == TimerModel.State.SET) confirmCancel(t);
        });
        setContentView(root);
        bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TimerReconcile.run(getApplicationContext());
        bind();
    }

    private void bind() {
        rows.clear();
        rows.addAll(TimerStore.all(this));
        rows.sort((a, b) -> {
            boolean aLive = a.state == TimerModel.State.SET
                    || a.state == TimerModel.State.RECORDING;
            boolean bLive = b.state == TimerModel.State.SET
                    || b.state == TimerModel.State.RECORDING;
            if (aLive != bLive) return aLive ? -1 : 1;
            return aLive ? Long.compare(a.startMs, b.startMs)
                    : Long.compare(b.startMs, a.startMs);
        });
        rowTitles.clear();
        rowSub.clear();
        TimeZone zone = TimeZone.getDefault();
        for (TimerModel.Timer t : rows) {
            rowTitles.add(TimerUi.label(t));
            rowSub.add(t.channelName + " · "
                    + GuideModel.formatDay(t.startMs, zone) + " "
                    + GuideModel.formatTime(t.startMs, zone) + "–"
                    + GuideModel.formatTime(t.stopMs, zone) + " · "
                    + TimerUi.stateText(this, t));
        }
        emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void confirmCancel(TimerModel.Timer t) {
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "timer_cancel"))
                .setMessage("“" + TimerUi.label(t) + "” · " + t.channelName)
                .setPositiveButton(LangStore.tr(this, "timer_cancel"),
                        (d, w) -> {
                            t.state = TimerModel.State.CANCELLED;
                            TimerStore.update(this, t);
                            TimerArm.disarm(this, t.id);
                            bind();
                        })
                .setNegativeButton(android.R.string.cancel, (d, w) -> { })
                .show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
