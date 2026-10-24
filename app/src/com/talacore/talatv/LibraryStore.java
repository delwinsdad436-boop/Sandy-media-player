package com.talacore.talatv;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The Android half of My Library (v0.3.0): finds the video files on the
 * device — everything MediaStore knows about, plus every folder the
 * user picks (Storage Access Framework, permission persisted) walked
 * recursively — and hands the names to LibraryIndex for parsing.
 * The index is persisted as a TSV in internal storage, same pattern as
 * the channel cache: names + URIs + parsed fields, nothing copied,
 * nothing uploaded. The scan itself only proves anything on a real
 * device; the parsing it feeds is JVM-tested.
 */
public final class LibraryStore {

    private static final String PREFS = "talatv_library";
    private static final String KEY_FOLDERS = "folders_json";
    private static final String KEY_SCANNED_WHEN = "scanned_when";
    private static final String KEY_MEDIA_BLOCKED = "mediastore_blocked";
    private static final String INDEX_FILE = "library.tsv";
    private static final int MAX_DEPTH = 14;

    private LibraryStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------- picked folders

    public static List<String> getFolders(Context ctx) {
        List<String> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_FOLDERS, null);
        if (raw == null) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    public static void setFolders(Context ctx, List<String> folders) {
        try {
            JSONArray arr = new JSONArray();
            for (String f : folders) arr.put(f);
            prefs(ctx).edit().putString(KEY_FOLDERS, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static long scannedWhen(Context ctx) {
        return prefs(ctx).getLong(KEY_SCANNED_WHEN, 0);
    }

    /** True when the last scan could not read MediaStore (permission not granted). */
    public static boolean mediaStoreBlocked(Context ctx) {
        return prefs(ctx).getBoolean(KEY_MEDIA_BLOCKED, false);
    }

    // ------------------------------------------------------------- scanning

    /**
     * Full rescan (background thread): MediaStore + every picked folder.
     * Returns the parsed entries and persists them. A SecurityException
     * from MediaStore is recorded, not hidden — the folder half still runs.
     */
    public static List<LibraryIndex.Entry> scan(Context ctx) {
        List<LibraryIndex.Entry> found = new ArrayList<>();
        boolean blocked = false;

        try {
            Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME};
            try (Cursor cur = ctx.getContentResolver().query(
                    collection, proj, null, null, null)) {
                if (cur != null) {
                    int idCol = cur.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int nameCol = cur.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    while (cur.moveToNext()) {
                        String name = cur.getString(nameCol);
                        if (name == null || !LibraryIndex.isVideoFile(name)) continue;
                        Uri uri = ContentUris.withAppendedId(collection, cur.getLong(idCol));
                        found.add(LibraryIndex.fromFile(name, uri.toString()));
                    }
                }
            }
        } catch (SecurityException se) {
            blocked = true;
        } catch (Exception ignored) {
            // a broken provider shouldn't sink the folder scan
        }

        for (String treeUri : getFolders(ctx)) {
            try {
                Uri tree = Uri.parse(treeUri);
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                walkTree(ctx, tree, rootId, 0, found);
            } catch (Exception ignored) {
                // a revoked/dead folder is skipped; the screen says folders
                // can be re-picked, and Rescan drops nothing from the index
                // that another source still provides
            }
        }

        List<LibraryIndex.Entry> entries = LibraryIndex.dedupeByUri(found);
        saveIndex(ctx, entries);
        prefs(ctx).edit()
                .putLong(KEY_SCANNED_WHEN, System.currentTimeMillis())
                .putBoolean(KEY_MEDIA_BLOCKED, blocked)
                .apply();
        return entries;
    }

    private static void walkTree(Context ctx, Uri tree, String docId, int depth,
                                 List<LibraryIndex.Entry> out) {
        if (depth > MAX_DEPTH) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] proj = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cur = ctx.getContentResolver().query(childrenUri, proj, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                if (id == null) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(ctx, tree, id, depth + 1, out);
                } else if (name != null && (LibraryIndex.isVideoFile(name)
                        || (mime != null && mime.startsWith("video/")))) {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    out.add(LibraryIndex.fromFile(name, docUri.toString()));
                }
            }
        } catch (Exception ignored) {
            // unreadable branch of the tree: skip it, keep walking the rest
        }
    }

    // ---------------------------------------------------------- persistence

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static void saveIndex(Context ctx, List<LibraryIndex.Entry> entries) {
        File f = new File(ctx.getFilesDir(), INDEX_FILE);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            for (LibraryIndex.Entry e : entries) {
                w.write(enc(e.uri));         w.write('\t');
                w.write(enc(e.fileName));    w.write('\t');
                w.write(enc(e.displayName)); w.write('\t');
                w.write(enc(e.showName));    w.write('\t');
                w.write(enc(e.episodeTitle)); w.write('\t');
                w.write(String.valueOf(e.season));  w.write('\t');
                w.write(String.valueOf(e.episode)); w.write('\t');
                w.write(String.valueOf(e.year));
                w.write('\n');
            }
        } catch (Exception ignored) { }
    }

    /** The persisted index; empty list when never scanned (not an error). */
    public static List<LibraryIndex.Entry> loadEntries(Context ctx) {
        List<LibraryIndex.Entry> out = new ArrayList<>();
        File f = new File(ctx.getFilesDir(), INDEX_FILE);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                if (p.length < 8) continue;
                LibraryIndex.Entry e = new LibraryIndex.Entry();
                e.uri = dec(p[0]);
                e.fileName = dec(p[1]);
                e.displayName = dec(p[2]);
                e.showName = dec(p[3]);
                e.episodeTitle = dec(p[4]);
                e.season = parseInt(p[5]);
                e.episode = parseInt(p[6]);
                e.year = parseInt(p[7]);
                out.add(e);
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The library in channel shape for SourceFinder; empty when unscanned. */
    public static List<M3uParser.Channel> loadChannels(Context ctx) {
        return LibraryIndex.toChannels(loadEntries(ctx));
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }
}
