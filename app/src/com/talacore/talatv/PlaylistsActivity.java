package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Dispatcharr move: bring your own playlists. Add any M3U URL, it is
 * fetched and counted on the spot, then merged into the one guide.
 * The built-in free-to-air directory can be refreshed but not removed.
 */
public class PlaylistsActivity extends Activity {

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private List<ChannelStore.Playlist> playlists;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private EditText nameBox;
    private EditText urlBox;
    private TextView statusView;
    private ProgressBar progress;
    private boolean changed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlists = ChannelStore.getPlaylists(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Playlists");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Every playlist below merges into one channel guide. " +
                "Tap one of your own to remove it. The built-in directory stays.");
        help.setTextColor(Color.rgb(160, 160, 170));
        help.setTextSize(13);
        root.addView(help);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setOnItemClickListener((parent, view, position, id) -> {
            ChannelStore.Playlist pl = playlists.get(position);
            if (pl.builtin) {
                Toast.makeText(this, "The built-in directory can't be removed — Refresh rebuilds it.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Remove playlist")
                    .setMessage("Remove \"" + pl.name + "\"?\n" + pl.url +
                            "\n\nIts channels leave the guide on the next refresh.")
                    .setPositiveButton("Remove", (d, w) -> {
                        playlists.remove(position);
                        ChannelStore.savePlaylists(this, playlists);
                        changed = true;
                        rebuildRows();
                    })
                    .setNegativeButton("Keep", null)
                    .show();
        });

        TextView addLabel = new TextView(this);
        addLabel.setText("Add a playlist by URL (M3U):");
        addLabel.setTextColor(Color.WHITE);
        addLabel.setTextSize(15);
        root.addView(addLabel);

        nameBox = new EditText(this);
        nameBox.setHint("Name (optional)");
        nameBox.setSingleLine(true);
        nameBox.setTextColor(Color.WHITE);
        nameBox.setHintTextColor(Color.rgb(140, 140, 150));
        root.addView(nameBox);

        urlBox = new EditText(this);
        urlBox.setHint("https://…/playlist.m3u");
        urlBox.setSingleLine(true);
        urlBox.setTextColor(Color.WHITE);
        urlBox.setHintTextColor(Color.rgb(140, 140, 150));
        root.addView(urlBox);

        Button addBtn = new Button(this);
        addBtn.setText("Add playlist");
        addBtn.setOnClickListener(v -> tryAdd());
        root.addView(addBtn);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(android.view.View.GONE);
        root.addView(progress);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(170, 170, 180));
        statusView.setTextSize(13);
        root.addView(statusView);

        Button doneBtn = new Button(this);
        doneBtn.setText("Done");
        doneBtn.setOnClickListener(v -> finish());
        root.addView(doneBtn);

        setContentView(root);
        rebuildRows();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void rebuildRows() {
        rows.clear();
        for (ChannelStore.Playlist pl : playlists) {
            rows.add(pl.name + (pl.builtin ? "  (built-in)" : "") + "\n" + pl.url);
        }
        adapter.notifyDataSetChanged();
    }

    private void tryAdd() {
        String url = urlBox.getText().toString().trim();
        String name = nameBox.getText().toString().trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            statusView.setTextColor(Color.rgb(230, 140, 140));
            statusView.setText("That URL doesn't look right — it should start with http:// or https://");
            return;
        }
        for (ChannelStore.Playlist pl : playlists) {
            if (pl.url.equals(url)) {
                statusView.setTextColor(Color.rgb(230, 200, 120));
                statusView.setText("That playlist is already in the guide.");
                return;
            }
        }
        progress.setVisibility(android.view.View.VISIBLE);
        statusView.setTextColor(Color.rgb(170, 170, 180));
        statusView.setText("Fetching it to count the channels…");
        final String fUrl = url;
        final String fName = name.isEmpty() ? hostOf(url) : name;
        exec.execute(() -> {
            try {
                byte[] bytes = download(fUrl);
                List<M3uParser.Channel> parsed = M3uParser.parse(
                        new ByteArrayInputStream(bytes), fName);
                runOnUiThread(() -> {
                    progress.setVisibility(android.view.View.GONE);
                    if (parsed.isEmpty()) {
                        statusView.setTextColor(Color.rgb(230, 140, 140));
                        statusView.setText("That URL answered, but no channels were found in it. Not added.");
                        return;
                    }
                    playlists.add(new ChannelStore.Playlist(fName, fUrl, false));
                    ChannelStore.savePlaylists(this, playlists);
                    changed = true;
                    rebuildRows();
                    nameBox.setText("");
                    urlBox.setText("");
                    statusView.setTextColor(Color.rgb(150, 200, 150));
                    statusView.setText("Added \"" + fName + "\" — " + parsed.size() +
                            " channels. The guide refreshes when you go back.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(android.view.View.GONE);
                    statusView.setTextColor(Color.rgb(230, 140, 140));
                    String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    statusView.setText("Couldn't fetch that playlist (" + m + "). Not added.");
                });
            }
        });
    }

    private static String hostOf(String url) {
        try {
            String h = new URL(url).getHost();
            return h == null || h.isEmpty() ? url : h;
        } catch (Exception e) {
            return url;
        }
    }

    private byte[] download(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", Net.UA);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public void finish() {
        if (changed) setResult(RESULT_OK, new Intent());
        super.finish();
    }
}
