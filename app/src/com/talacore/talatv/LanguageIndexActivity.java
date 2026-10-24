package com.talacore.talatv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every language the catalogue can SHOW (v0.11.0) — the door past
 * the 13 languages Sandy speaks. The list is the LangRegistry:
 * derived from the walked index (seed counts refreshed by what the
 * walk has gathered on this phone), not a baked list, so a language
 * TVmaze adds appears here the first time the walk touches it.
 *
 * Each row tells the truth twice: how much of the whole index the
 * language holds, and how much of it is on this phone so far. Thin
 * languages stand thin — one show reads "1", in the open. Tapping a
 * language opens its wall (HomeActivity), with its country shelves
 * and its titles in their own names, exactly like a spoken one.
 */
public class LanguageIndexActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<LangRegistry.Entry> entries = new ArrayList<>();
    private final List<String> rowTitles = new ArrayList<>();
    private final List<String> rowSub = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "lang_index_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView note = new TextView(this);
        note.setText(LangStore.tr(this, "lang_index_note"));
        note.setTextColor(Color.rgb(170, 170, 180));
        note.setTextSize(12);
        root.addView(note);

        progress = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

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
            HomeActivity.pendingWallLanguage = entries.get(pos).tvName;
            finish();
        });
        setContentView(root);

        exec.execute(() -> {
            List<LangRegistry.Entry> loaded = LangRegistry.load(this);
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                entries.clear();
                entries.addAll(loaded);
                rowTitles.clear();
                rowSub.clear();
                for (LangRegistry.Entry e : entries) {
                    String name = e.displayName();
                    if (!e.tvName.equals(name)) name += " · " + e.tvName;
                    if (e.spoken()) name += " ◆";
                    rowTitles.add(name);
                    rowSub.add(e.indexCount > 0
                            ? e.indexCount + " in the index · "
                                    + e.gatheredCount + " on this phone"
                            : e.gatheredCount + " on this phone");
                }
                adapter.notifyDataSetChanged();
            });
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
