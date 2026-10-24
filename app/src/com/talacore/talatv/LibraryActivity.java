package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * My Library (v0.3.0) — the screen for the source pool that lives on
 * the phone itself. Explains what the index is, shows what it found
 * (files, episodes across shows, movies, unsorted — counted, not
 * asserted), and offers the two controls that matter: pick a folder
 * to include, and Rescan. Folder rows tap-to-remove.
 */
public class LibraryActivity extends Activity {

    private static final int REQ_PICK_FOLDER = 701;
    private static final int REQ_VIDEO_PERMISSION = 702;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private TextView statsView, mediaNoteView, foldersHeader, showsHeader;
    private ProgressBar progress;
    private ListView foldersList, showsList;
    private Button rescanBtn, pickBtn;

    private List<String> folders = new ArrayList<>();
    private final List<String> folderRows = new ArrayList<>();
    private final List<String> showRows = new ArrayList<>();
    private ArrayAdapter<String> folderAdapter, showAdapter;
    private boolean scanning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        int pad = dp(Sanctuary.PAGE_PAD_DP);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "lib_title"));
        title.setTextSize(20);
        Sanctuary.goldText(title);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        // The Recordings shelf (v0.5.0) — one door, right at the top:
        // what "Record what's playing" caught waits behind it.
        Button recBtn = new Button(this);
        Sanctuary.styleButton(this, recBtn, false);
        recBtn.setText(LangStore.tr(this, "lib_recordings_btn"));
        recBtn.setContentDescription(LangStore.tr(this, "lib_recordings_btn"));
        recBtn.setOnClickListener(v ->
                startActivity(new Intent(this, RecordingsActivity.class)));
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recLp.topMargin = dp(6);
        recLp.bottomMargin = dp(6);
        root.addView(recBtn, recLp);

        // My Videos (v0.9.0) — the local player's door, right at the
        // top with the other shelves: every video on the phone as a
        // tappable list that plays. (The index below explains itself;
        // this door is for watching, not managing.)
        Button videosBtn = new Button(this);
        Sanctuary.styleButton(this, videosBtn, false);
        videosBtn.setText(LangStore.tr(this, "lib_videos_btn"));
        videosBtn.setContentDescription(LangStore.tr(this, "lib_videos_btn"));
        videosBtn.setOnClickListener(v ->
                startActivity(new Intent(this, FilesActivity.class)));
        LinearLayout.LayoutParams vidLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vidLp.topMargin = dp(6);
        vidLp.bottomMargin = dp(6);
        root.addView(videosBtn, vidLp);

        // The Books shelf (v0.8.0) — the read-aloud door, right
        // beside Recordings: tap a book and Sandy reads it aloud.
        Button booksBtn = new Button(this);
        Sanctuary.styleButton(this, booksBtn, false);
        booksBtn.setText(LangStore.tr(this, "lib_books_btn"));
        booksBtn.setContentDescription(LangStore.tr(this, "lib_books_btn"));
        booksBtn.setOnClickListener(v ->
                startActivity(new Intent(this, BooksActivity.class)));
        LinearLayout.LayoutParams booksLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        booksLp.topMargin = dp(6);
        booksLp.bottomMargin = dp(6);
        root.addView(booksBtn, booksLp);

        // The dub kitchen (v0.12.0) — James's anime dub, cooked by
        // Sandy herself: pick an episode, cook the dub (subtitles
        // first, translate-then-speak, cast voices saved per show),
        // and the dubbed file lands on its own shelf below.
        Button dubBtn = new Button(this);
        Sanctuary.styleButton(this, dubBtn, false);
        dubBtn.setText(LangStore.tr(this, "lib_dub_btn"));
        dubBtn.setContentDescription(LangStore.tr(this, "lib_dub_btn"));
        dubBtn.setOnClickListener(v ->
                startActivity(new Intent(this, DubActivity.class)));
        LinearLayout.LayoutParams dubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dubLp.topMargin = dp(6);
        dubLp.bottomMargin = dp(6);
        root.addView(dubBtn, dubLp);

        Button dubShelfBtn = new Button(this);
        Sanctuary.styleButton(this, dubShelfBtn, false);
        dubShelfBtn.setText(LangStore.tr(this, "lib_dubshelf_btn"));
        dubShelfBtn.setContentDescription(LangStore.tr(this, "lib_dubshelf_btn"));
        dubShelfBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, DubActivity.class);
            i.putExtra("mode", "shelf");
            startActivity(i);
        });
        LinearLayout.LayoutParams dubShelfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dubShelfLp.topMargin = dp(6);
        dubShelfLp.bottomMargin = dp(6);
        root.addView(dubShelfBtn, dubShelfLp);

        TextView help = new TextView(this);
        help.setText("Videos already on this phone are a source too — the same "
                + "lesson Jellyfin taught: your own shelf counts. Sandy reads "
                + "their file names (show, season, episode, year) into an index, "
                + "and episode pages offer your files alongside playlists and "
                + "the Internet Archive. Nothing is copied or uploaded; the "
                + "index is just names and where each file sits.\n\n"
                + "Prepared English dub: put an audio file next to a video with "
                + "the same name plus “.dub-en.m4a” (for example "
                + "“Show S01E01.dub-en.m4a” beside “Show S01E01.mkv”) and the "
                + "player offers it as “English — prepared dub.”");
        help.setTextColor(Sanctuary.MUTED);
        help.setTextSize(13);
        root.addView(help);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        statsView = new TextView(this);
        statsView.setTextColor(Sanctuary.BODY);
        statsView.setTextSize(15);
        statsView.setPadding(0, dp(10), 0, 0);
        root.addView(statsView);

        mediaNoteView = new TextView(this);
        mediaNoteView.setTextColor(Sanctuary.GOLD_TEXT);
        mediaNoteView.setTextSize(13);
        mediaNoteView.setVisibility(View.GONE);
        root.addView(mediaNoteView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        pickBtn = new Button(this);
        pickBtn.setText("Pick a folder");
        pickBtn.setOnClickListener(v -> pickFolder());
        btnRow.addView(pickBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rescanBtn = new Button(this);
        rescanBtn.setText("Rescan");
        rescanBtn.setOnClickListener(v -> rescan(true));
        btnRow.addView(rescanBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btnRow);

        foldersHeader = sectionHeader("Folders included (tap one to remove)");
        root.addView(foldersHeader);
        foldersList = new ListView(this);
        foldersList.setBackgroundColor(Sanctuary.GROUND);
        foldersList.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        foldersList.setDividerHeight(1);
        folderAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, folderRows);
        folderAdapter.setNotifyOnChange(false);
        foldersList.setAdapter(folderAdapter);
        root.addView(foldersList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        foldersList.setOnItemClickListener((p, v, pos, id) -> confirmRemoveFolder(pos));

        showsHeader = sectionHeader("What the index found");
        root.addView(showsHeader);
        showsList = new ListView(this);
        showsList.setBackgroundColor(Sanctuary.GROUND);
        showsList.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        showsList.setDividerHeight(1);
        showAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, showRows);
        showAdapter.setNotifyOnChange(false);
        showsList.setAdapter(showAdapter);
        root.addView(showsList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));

        setContentView(root);
        folders = LibraryStore.getFolders(this);
        refreshFromIndex();
        // First visit: get the video permission sorted so the MediaStore
        // half of the scan can actually see the phone's videos.
        if (LibraryStore.scannedWhen(this) == 0 && !hasVideoPermission()) {
            requestPermissions(new String[]{videoPermission()}, REQ_VIDEO_PERMISSION);
        }
    }

    private TextView sectionHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Sanctuary.GOLD_TEXT);
        t.setTextSize(14);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------------- actions

    private String videoPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_VIDEO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasVideoPermission() {
        return checkSelfPermission(videoPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "This phone has no folder picker — the library "
                    + "will use the phone's video index only.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FOLDER || resultCode != RESULT_OK || data == null) return;
        Uri tree = data.getData();
        if (tree == null) return;
        try {
            getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { /* some providers refuse; it may still work this session */ }
        String uriStr = tree.toString();
        if (!folders.contains(uriStr)) {
            folders.add(uriStr);
            LibraryStore.setFolders(this, folders);
        }
        Toast.makeText(this, "Folder added — rescanning…", Toast.LENGTH_SHORT).show();
        rescan(false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_VIDEO_PERMISSION) {
            // granted or not, scan: the outcome is reported honestly either way
            rescan(false);
        }
    }

    private void confirmRemoveFolder(int pos) {
        if (pos < 0 || pos >= folders.size()) return;
        String uriStr = folders.get(pos);
        new AlertDialog.Builder(this)
                .setTitle("Stop including this folder?")
                .setMessage(prettyFolder(uriStr)
                        + "\n\nIts files leave the index on the next rescan. "
                        + "The files themselves are never touched.")
                .setPositiveButton("Remove", (d, w) -> {
                    folders.remove(pos);
                    LibraryStore.setFolders(this, folders);
                    rescan(false);
                })
                .setNegativeButton("Keep", null)
                .show();
    }

    private void rescan(boolean askPermissionFirst) {
        if (scanning) return;
        if (askPermissionFirst && !hasVideoPermission()) {
            requestPermissions(new String[]{videoPermission()}, REQ_VIDEO_PERMISSION);
            return;
        }
        scanning = true;
        progress.setVisibility(View.VISIBLE);
        statsView.setText("Scanning the phone's videos and your folders…");
        exec.execute(() -> {
            List<LibraryIndex.Entry> entries = LibraryStore.scan(this);
            runOnUiThread(() -> {
                scanning = false;
                progress.setVisibility(View.GONE);
                refreshFromIndex();
                Toast.makeText(this, "Index rebuilt: " + entries.size() + " files",
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    // ------------------------------------------------------------- display

    private void refreshFromIndex() {
        folders = LibraryStore.getFolders(this);
        folderRows.clear();
        if (folders.isEmpty()) {
            folderRows.add("None yet — the phone's own video index is always included. "
                    + "Pick a folder to add one (an SD card, a downloads folder…).");
        } else {
            for (String f : folders) folderRows.add(prettyFolder(f));
        }
        folderAdapter.notifyDataSetChanged();

        List<LibraryIndex.Entry> entries = LibraryStore.loadEntries(this);
        long when = LibraryStore.scannedWhen(this);
        if (when == 0) {
            statsView.setText("Not scanned yet. Tap Rescan and Sandy will index "
                    + "the videos on this phone.");
        } else {
            String stamp = new SimpleDateFormat("MMM d, h:mm a", Locale.US).format(new Date(when));
            statsView.setText(entries.size() + " video files indexed · "
                    + LibraryIndex.countEpisodes(entries) + " episodes across "
                    + LibraryIndex.episodesByShow(entries).size() + " shows · "
                    + LibraryIndex.oneOffs(entries).size() + " movies & one-offs · "
                    + LibraryIndex.countUnsorted(entries) + " unsorted files"
                    + "\nLast scan: " + stamp);
        }
        if (LibraryStore.mediaStoreBlocked(this) && when > 0) {
            mediaNoteView.setVisibility(View.VISIBLE);
            mediaNoteView.setText("Note: the phone's video index couldn't be read "
                    + "(permission not granted), so only your picked folders were "
                    + "scanned. Grant the Videos permission and Rescan to include it.");
        } else {
            mediaNoteView.setVisibility(View.GONE);
        }

        showRows.clear();
        for (Map.Entry<String, Integer> e : LibraryIndex.episodesByShow(entries).entrySet()) {
            showRows.add(e.getKey() + " — " + e.getValue()
                    + (e.getValue() == 1 ? " episode" : " episodes"));
        }
        for (LibraryIndex.Entry e : LibraryIndex.oneOffs(entries)) {
            showRows.add("▸ " + e.displayName);
        }
        int unsorted = LibraryIndex.countUnsorted(entries);
        if (unsorted > 0) {
            showRows.add("Unsorted files (names with no show/episode in them): " + unsorted);
            for (LibraryIndex.Entry e : entries) {
                if (!e.isSorted()) showRows.add("▸ " + e.fileName);
            }
        }
        if (showRows.isEmpty() && when > 0) {
            showRows.add("Nothing indexed yet — add video files or a folder, then Rescan.");
        }
        showAdapter.notifyDataSetChanged();
    }

    /** "content://com.android.externalstorage.documents/tree/primary%3AMovies" -> "Movies". */
    static String prettyFolder(String treeUri) {
        try {
            String decoded = Uri.decode(treeUri);
            int slash = decoded.lastIndexOf('/');
            String tail = slash >= 0 ? decoded.substring(slash + 1) : decoded;
            int colon = tail.lastIndexOf(':');
            if (colon >= 0 && colon < tail.length() - 1) tail = tail.substring(colon + 1);
            return tail.isEmpty() ? treeUri : tail;
        } catch (Exception e) {
            return treeUri;
        }
    }
}
