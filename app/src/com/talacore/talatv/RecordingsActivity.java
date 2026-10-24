package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Recordings shelf (v0.5.0, item 3): everything "Record what's
 * playing" has caught, listed with its real state — still recording,
 * recorded, stopped-and-kept, or failed (with the failure said
 * plainly). Tap plays through the player (positions are kept per
 * recording, like everything else — item 8). Long-press offers
 * resume / delete for the states where those mean something.
 */
public class RecordingsActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<Download> downloads = new ArrayList<>();
    private final List<String> rows = new ArrayList<>();
    private Runnable refresher;
    private boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "rec_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("What you recorded from live TV lives here, on this "
                + "phone. Tap one to watch it. Press and hold for more "
                + "choices — a finished recording can be cooked into a "
                + "translation, or pieced together into one file.");
        help.setTextColor(Color.rgb(160, 160, 170));
        help.setTextSize(13);
        root.addView(help);

        list = new ListView(this);
        list.setBackgroundColor(Color.rgb(16, 16, 20));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2,
                android.R.id.text1, rows);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < downloads.size()) play(downloads.get(pos));
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < downloads.size()) {
                options(downloads.get(pos));
                return true;
            }
            return false;
        });

        setContentView(root);
        refresh();
        refresher = new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                refresh();
                handler.postDelayed(this, 1500);
            }
        };
        handler.postDelayed(refresher, 1500);
    }

    private void refresh() {
        downloads.clear();
        rows.clear();
        DownloadManager m = Downloads.manager(this);
        if (m != null) {
            try (DownloadCursor cursor = m.getDownloadIndex().getDownloads()) {
                while (cursor.moveToNext()) downloads.add(cursor.getDownload());
            } catch (Exception ignored) { }
        }
        Collections.sort(downloads, (a, b) ->
                Long.compare(b.startTimeMs, a.startTimeMs));
        if (downloads.isEmpty()) {
            rows.add(LangStore.tr(this, "rec_empty"));
        } else {
            for (Download d : downloads) {
                String name = ChannelStore.getRecordName(this, d.request.id);
                if (name.isEmpty()) name = d.request.id;
                rows.add(name + "\n" + Downloads.stateLine(d));
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void play(Download d) { play(d, false); }

    private void play(Download d, boolean autoTranslate) {
        if (d.getBytesDownloaded() <= 0
                && d.state != Download.STATE_COMPLETED) {
            Toast.makeText(this, d.state == Download.STATE_FAILED
                    ? "That recording failed — the source refused."
                    : "Nothing caught yet — give it a moment.", Toast.LENGTH_LONG).show();
            return;
        }
        String name = ChannelStore.getRecordName(this, d.request.id);
        if (name.isEmpty()) name = d.request.id;
        M3uParser.Channel c = new M3uParser.Channel();
        c.url = d.request.uri.toString();
        c.name = name;
        c.source = "Recordings";
        ChannelStore.playQueue = Collections.singletonList(c);
        ChannelStore.playIndex = 0;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", c.url);
        i.putExtra("name", name);
        i.putExtra("ua", "Sandy/0.14.1");
        i.putExtra("ckey", "rec:" + d.request.id);
        i.putExtra("recording", true);
        if (autoTranslate) i.putExtra("autoTranslate", true);
        startActivity(i);
    }

    private void options(Download d) {
        String name = ChannelStore.getRecordName(this, d.request.id);
        if (name.isEmpty()) name = d.request.id;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean active = Downloads.isRecording(this, d.request.id);
        if (active) {
            labels.add("Stop recording — keep what it caught");
            actions.add(() -> {
                Downloads.stopKeep(this, d.request.id);
                Toast.makeText(this, "Stopped — what's caught is kept.",
                        Toast.LENGTH_SHORT).show();
                refresh();
            });
        } else if (d.state == Download.STATE_STOPPED
                || d.state == Download.STATE_FAILED) {
            labels.add("Try recording again (if the channel still offers it)");
            actions.add(() -> {
                Downloads.resume(this, d.request.id);
                refresh();
            });
        }
        if (d.getBytesDownloaded() > 0 && !active) {
            // v0.7.0: Delwin's door, on the shelf itself — the cook
            // opens in the player, where the Translate sheet lives.
            labels.add("Cook a translation…");
            actions.add(() -> play(d, true));
            // v0.13.0: the voiceover door — a recording is a held
            // video like any other; the dub kitchen takes it as a
            // recording pick (recorded TV was James's second case)
            final String rname = name;
            labels.add(LangStore.tr(this, "dub_make_voiceover"));
            actions.add(() -> {
                Intent i = new Intent(this, DubActivity.class);
                i.putExtra("pickUrl", d.request.id);
                i.putExtra("pickName", rname);
                i.putExtra("pickRecording", true);
                startActivity(i);
            });
            final String ename = name;
            labels.add("Piece it together into one file…");
            actions.add(() -> exportOneFile(d, ename));
        }
        final String fname = name;
        labels.add("Delete this recording");
        actions.add(() -> new AlertDialog.Builder(this)
                .setTitle("Delete this recording?")
                .setMessage(fname + "\n\nThe recording's video is deleted "
                        + "from this phone. This can't be undone.")
                .setPositiveButton("Delete", (dlg, w) -> {
                    Downloads.delete(this, d.request.id);
                    refresh();
                })
                .setNegativeButton("Keep", null)
                .show());
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(labels.toArray(new String[0]),
                        (dlg, which) -> actions.get(which).run())
                .show();
    }

    /**
     * v0.7.0: assemble the recording's cached pieces into ONE
     * file on this phone (files/recording-export) — the same
     * file the cooker cooks from, so the viewer can see it,
     * keep it, and copy it off with the rest of Sandy's files.
     */
    private void exportOneFile(final Download d, final String name) {
        Toast.makeText(this, "Piecing “" + name + "” together…",
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                java.io.File outDir = new java.io.File(
                        getFilesDir(), "recording-export");
                java.io.File f = RecordingExport.export(this, d, outDir);
                final String msg = "One file, ready: " + f.getName()
                        + " (" + (f.length() / 1024) + " KB)\n"
                        + "In Sandy's files, under recording-export. "
                        + "The translation cooker cooks from this file.";
                handler.post(() -> Toast.makeText(this, msg,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                final String msg = "Couldn't piece it together: "
                        + e.getMessage();
                handler.post(() -> Toast.makeText(this, msg,
                        Toast.LENGTH_LONG).show());
            }
        }, "sandy-rec-export").start();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
