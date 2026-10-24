package com.talacore.talatv;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My Videos (v0.9.0) — the local video player, as a citizen of Sandy.
 *
 * James's order (2026-10-18): "I want everything we've talked about
 * for THE APP" — one app, whole. The audit found the one real gap:
 * Sandy's library INDEX knew the phone's videos, but its rows were
 * text on an index screen — a file you could see, you could not
 * simply PLAY. This screen closes that: every video on the phone is
 * a row with a big tap target, and tapping plays it in the one
 * PlayerActivity, with the whole shelf as the opened set (next /
 * previous walk it), a resume place kept per file, and the full
 * player treatment (±10s, speed, fullscreen).
 *
 * The shelf merges three doors, merged and deduped by the pure
 * LocalFiles model (Downloads first, then the phone's own videos,
 * then My Library's picked folders). Nothing is copied; rows are
 * pointers. A "Pick a video file…" door (SAF) plays a single file —
 * or several at once — immediately, indexed or not.
 */
public class FilesActivity extends Activity {

    private static final int REQ_PICK_FILES = 901;
    private static final int REQ_VIDEO_PERMISSION = 902;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private TextView statsView, noteView;
    private ProgressBar progress;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private List<LocalFiles.Row> shelf = new ArrayList<>();
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "files_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Every video on this phone, in one list. Tap one and "
                + "it plays — the list becomes the set, so Next and "
                + "Previous walk it, and Sandy remembers where you left "
                + "off in each file. Nothing is copied or uploaded.");
        help.setTextColor(Color.rgb(160, 160, 170));
        help.setTextSize(13);
        help.setPadding(0, dp(6), 0, 0);
        root.addView(help);

        Button pickBtn = new Button(this);
        pickBtn.setText("Pick a video file…");
        pickBtn.setContentDescription("Pick a video file");
        pickBtn.setOnClickListener(v -> pickFiles());
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pickLp.topMargin = dp(8);
        pickLp.bottomMargin = dp(4);
        root.addView(pickBtn, pickLp);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        statsView = new TextView(this);
        statsView.setTextColor(Color.rgb(220, 220, 228));
        statsView.setTextSize(15);
        statsView.setPadding(0, dp(8), 0, 0);
        root.addView(statsView);

        noteView = new TextView(this);
        noteView.setTextColor(Color.rgb(230, 200, 120));
        noteView.setTextSize(13);
        noteView.setVisibility(View.GONE);
        root.addView(noteView);

        list = new ListView(this);
        list.setBackgroundColor(Color.rgb(16, 16, 20));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        adapter.setNotifyOnChange(false);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        list.setOnItemClickListener((p, v, pos, id) -> playAt(pos));
        // v0.13.0: the voiceover door — any held video can be
        // cooked, not just anime: a wedding speech in a language
        // you don't know is the case James named
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= shelf.size()) return false;
            LocalFiles.Row r = shelf.get(pos);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(r.title)
                    .setItems(new CharSequence[]{
                            LangStore.tr(this, "auto_offer_play"),
                            LangStore.tr(this, "dub_make_voiceover")},
                            (d, which) -> {
                                if (which == 0) {
                                    playAt(pos);
                                } else {
                                    Intent i = new Intent(this, DubActivity.class);
                                    i.putExtra("pickUrl", r.uri);
                                    i.putExtra("pickName", r.title);
                                    i.putExtra("pickRecording", false);
                                    startActivity(i);
                                }
                            })
                    .show();
            return true;
        });

        setContentView(root);
        if (!hasVideoPermission()) {
            requestPermissions(new String[]{videoPermission()}, REQ_VIDEO_PERMISSION);
        } else {
            load();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------------------------------------------------------- permission

    private String videoPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_VIDEO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasVideoPermission() {
        return checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_VIDEO_PERMISSION) load();
    }

    // ---------------------------------------------------------- loading

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        statsView.setText("Looking for the videos on this phone…");
        exec.execute(() -> {
            List<LocalFiles.Row> downloads = queryDownloads();
            List<LocalFiles.Row> device = hasVideoPermission()
                    ? queryDeviceVideos() : new ArrayList<>();
            List<LocalFiles.Row> folder = folderRows();
            List<LocalFiles.Row> merged =
                    LocalFiles.mergeShelves(downloads, device, folder);
            boolean blocked = !hasVideoPermission();
            runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                shelf = merged;
                render(blocked);
            });
        });
    }

    /** This phone's own video index, asked directly (fresh every open). */
    private List<LocalFiles.Row> queryDeviceVideos() {
        List<LocalFiles.Row> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
        };
        try (Cursor cur = getContentResolver().query(collection, proj, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cur == null) return out;
            int idCol = cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int nameCol = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
            int durCol = cur.getColumnIndex(MediaStore.Video.Media.DURATION);
            int sizeCol = cur.getColumnIndex(MediaStore.Video.Media.SIZE);
            while (cur.moveToNext()) {
                String name = cur.getString(nameCol);
                if (name == null || !LibraryIndex.isVideoFile(name)) continue;
                LocalFiles.Row r = new LocalFiles.Row();
                r.uri = ContentUris.withAppendedId(collection, cur.getLong(idCol)).toString();
                r.title = name;
                r.durationMs = durCol >= 0 ? cur.getLong(durCol) : -1;
                r.sizeBytes = sizeCol >= 0 ? cur.getLong(sizeCol) : -1;
                r.origin = LocalFiles.Origin.DEVICE;
                out.add(r);
            }
        } catch (Throwable t) {
            CrashGuard.log(this, "FilesActivity device video query", t);
        }
        return out;
    }

    /** The public Downloads folders — where fetched files land. */
    private List<LocalFiles.Row> queryDownloads() {
        List<LocalFiles.Row> out = new ArrayList<>();
        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        };
        for (File dir : dirs) {
            if (dir == null) continue;
            File[] files = null;
            try { files = dir.listFiles(); } catch (Throwable ignored) { }
            if (files == null) continue;
            for (File f : files) {
                if (f == null || !f.isFile() || !LibraryIndex.isVideoFile(f.getName())) continue;
                LocalFiles.Row r = new LocalFiles.Row();
                r.uri = f.getAbsolutePath();
                r.title = f.getName();
                r.sizeBytes = f.length();
                r.origin = LocalFiles.Origin.DOWNLOADS;
                out.add(r);
            }
        }
        return out;
    }

    /** My Library's indexed picked-folder files (the index's own scan). */
    private List<LocalFiles.Row> folderRows() {
        List<LocalFiles.Row> out = new ArrayList<>();
        try {
            for (LibraryIndex.Entry e : LibraryStore.loadEntries(this)) {
                if (e.uri == null || e.uri.isEmpty()) continue;
                LocalFiles.Row r = new LocalFiles.Row();
                r.uri = e.uri;
                r.title = e.fileName == null || e.fileName.isEmpty()
                        ? e.displayName : e.fileName;
                r.origin = e.uri.startsWith("content://")
                        ? LocalFiles.Origin.FOLDER : LocalFiles.Origin.DEVICE;
                out.add(r);
            }
        } catch (Throwable t) {
            CrashGuard.log(this, "FilesActivity library rows", t);
        }
        return out;
    }

    private void render(boolean permissionBlocked) {
        rows.clear();
        for (LocalFiles.Row r : shelf) {
            StringBuilder b = new StringBuilder("▶ ").append(r.title);
            String meta = rowMeta(r);
            if (!meta.isEmpty()) b.append("\n").append(meta);
            rows.add(b.toString());
        }
        adapter.notifyDataSetChanged();
        if (shelf.isEmpty()) {
            statsView.setText(permissionBlocked
                    ? "Sandy can't see this phone's videos yet — the Videos "
                    + "permission wasn't granted. The “Pick a video file…” "
                    + "door above still works without it."
                    : "No videos found on this phone yet. Put video files in "
                    + "the Downloads or Movies folder, add a folder in My "
                    + "Library, or pick a file above.");
        } else {
            statsView.setText(shelf.size()
                    + (shelf.size() == 1 ? " video" : " videos") + " — tap one to play");
        }
        noteView.setVisibility(permissionBlocked && !shelf.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (permissionBlocked && !shelf.isEmpty()) {
            noteView.setText("Note: the phone's own video index is hidden "
                    + "(permission not granted) — these are Downloads and "
                    + "picked-folder files. Grant the Videos permission and "
                    + "reopen this screen for the rest.");
        }
    }

    private String rowMeta(LocalFiles.Row r) {
        StringBuilder b = new StringBuilder();
        long saved = ChannelStore.getPosition(this, LocalFiles.keyFor(r));
        if (LocalFiles.ResumePolicy.shouldOffer(saved, r.durationMs)) {
            b.append("⏸ left off at ").append(LocalFiles.formatClock(saved));
        }
        if (r.durationMs > 0) {
            if (b.length() > 0) b.append("  ·  ");
            b.append(LocalFiles.formatClock(r.durationMs));
        }
        String size = LocalFiles.formatSize(r.sizeBytes);
        if (!size.isEmpty()) {
            if (b.length() > 0) b.append("  ·  ");
            b.append(size);
        }
        if (b.length() > 0) b.append("  ·  ");
        b.append(r.origin == LocalFiles.Origin.DOWNLOADS ? "Downloads"
                : r.origin == LocalFiles.Origin.FOLDER ? "My Library folder"
                : "On this phone");
        return b.toString();
    }

    // ---------------------------------------------------------- playing

    private void playAt(int pos) {
        if (pos < 0 || pos >= shelf.size()) return;
        List<M3uParser.Channel> queue = new ArrayList<>();
        for (LocalFiles.Row r : shelf) {
            M3uParser.Channel c = new M3uParser.Channel();
            c.name = r.title;
            c.url = r.uri;
            c.source = "My Videos";
            queue.add(c);
        }
        ChannelStore.playQueue = queue;
        ChannelStore.playIndex = pos;
        Intent i = new Intent(this, PlayerActivity.class);
        LocalFiles.Row tapped = shelf.get(pos);
        i.putExtra("url", tapped.uri);
        i.putExtra("name", tapped.title);
        i.putExtra("localSet", true);
        startActivity(i);
    }

    // ---------------------------------------------------------- the file pickers

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("video/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_FILES);
        } catch (Exception e) {
            Toast.makeText(this, "This phone has no file picker — the list "
                    + "and My Library's folders still work.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILES || resultCode != RESULT_OK || data == null) return;
        List<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int k = 0; k < data.getClipData().getItemCount(); k++) {
                Uri u = data.getClipData().getItemAt(k).getUri();
                if (u != null) picked.add(u);
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;
        List<M3uParser.Channel> queue = new ArrayList<>();
        for (Uri u : picked) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { /* may still play this session */ }
            M3uParser.Channel c = new M3uParser.Channel();
            c.name = displayNameFor(u);
            c.url = u.toString();
            c.source = "My Videos";
            queue.add(c);
        }
        ChannelStore.playQueue = queue;
        ChannelStore.playIndex = 0;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", queue.get(0).url);
        i.putExtra("name", queue.get(0).name);
        i.putExtra("localSet", true);
        startActivity(i);
    }

    /** Best plain name for a picked document URI. */
    private String displayNameFor(Uri u) {
        String name = null;
        try (Cursor cur = getContentResolver().query(u,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cur != null && cur.moveToFirst()) name = cur.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) {
            String tail = u.getLastPathSegment();
            name = tail == null || tail.isEmpty() ? "Video" : tail;
        }
        return name;
    }
}
