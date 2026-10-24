package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The country door, standing on its own (v0.10.0): every country the
 * gathered index holds, biggest first, with its show count and how
 * many languages speak from it. Tapping one opens that country's
 * whole shelf — all its languages together, scrollable to the end.
 * Countries appear here only when the walk has actually gathered
 * their shows; nothing is promised that isn't on the phone.
 */
public class CountryIndexActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final List<String> countries = new ArrayList<>();
    private final Map<String, Integer> counts = new HashMap<>();
    private final Map<String, Set<String>> langs = new HashMap<>();

    private ListView listView;
    private ProgressBar progress;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Countries");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(170, 170, 180));
        statusView.setTextSize(12);
        statusView.setText("Every country in the gathered catalog — "
                + "tap one to scroll its shows, in all its languages.");
        root.addView(statusView);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        listView = new ListView(this);
        listView.setBackgroundColor(Color.rgb(16, 16, 20));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listView.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(this, WallListActivity.class);
            i.putExtra("mode", "country");
            i.putExtra("country", countries.get(pos));
            startActivity(i);
        });
        setContentView(root);

        exec.execute(() -> {
            Map<Integer, CatalogIndex.Record> gathered =
                    CatalogStore.loadGathered(this);
            for (CatalogIndex.Record r : gathered.values()) {
                String shelf = CatalogIndex.shelfOf(r);
                counts.merge(shelf, 1, Integer::sum);
                langs.computeIfAbsent(shelf, k -> new HashSet<>())
                        .add(r.language.isEmpty() ? "—" : r.language);
            }
            List<String> names = new ArrayList<>(counts.keySet());
            names.sort((a, b) -> {
                int byCount = counts.get(b) - counts.get(a);
                return byCount != 0 ? byCount : a.compareToIgnoreCase(b);
            });
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                countries.clear();
                countries.addAll(names);
                ArrayAdapter<String> ad = new ArrayAdapter<String>(this,
                        android.R.layout.simple_list_item_2, android.R.id.text1,
                        countries) {
                    @Override
                    public View getView(int pos, View convertView, ViewGroup parent) {
                        View v = super.getView(pos, convertView, parent);
                        String c = countries.get(pos);
                        TextView t1 = v.findViewById(android.R.id.text1);
                        t1.setText(c);
                        t1.setTextColor(Color.WHITE);
                        TextView t2 = v.findViewById(android.R.id.text2);
                        Set<String> ls = langs.get(c);
                        t2.setText(counts.get(c) + " shows · "
                                + (ls == null ? 0 : ls.size())
                                + (ls != null && ls.size() == 1 ? " language" : " languages"));
                        t2.setTextColor(Color.rgb(150, 150, 160));
                        return v;
                    }
                    @Override public int getCount() { return countries.size(); }
                };
                listView.setAdapter(ad);
                if (countries.isEmpty()) {
                    statusView.setText("No countries gathered yet — the catalog "
                            + "index walk hasn't brought any shows in. Open the "
                            + "Catalog and let it gather (a connection is needed).");
                }
            });
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
