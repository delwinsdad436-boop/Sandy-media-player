package com.talacore.talatv;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The dub kitchen's pick shelf (v0.12.0): the same three doors My
 * Videos merges (Downloads folders, the phone's video index, My
 * Library's picked folders), through LocalFiles' own merge — the
 * dub picks from exactly the shelf the viewer already knows.
 * (FilesActivity keeps its private copies; this seam exists so the
 * two screens can never drift on what "an episode Sandy has"
 * means: same queries, same merge.)
 */
public final class DubPickSources {

    private DubPickSources() {}

    public static List<LocalFiles.Row> merged(Context ctx) {
        List<LocalFiles.Row> downloads = queryDownloads();
        List<LocalFiles.Row> device = hasVideoPermission(ctx)
                ? queryDeviceVideos(ctx) : new ArrayList<>();
        List<LocalFiles.Row> folder = new ArrayList<>();
        try {
            for (LibraryIndex.Entry e : LibraryStore.loadEntries(ctx)) {
                if (e.uri == null || e.uri.isEmpty()) continue;
                LocalFiles.Row r = new LocalFiles.Row();
                r.uri = e.uri;
                r.title = e.fileName == null || e.fileName.isEmpty()
                        ? e.displayName : e.fileName;
                r.origin = e.uri.startsWith("content://")
                        ? LocalFiles.Origin.FOLDER : LocalFiles.Origin.DEVICE;
                folder.add(r);
            }
        } catch (Throwable ignored) {}
        return LocalFiles.mergeShelves(downloads, device, folder);
    }

    private static String videoPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? android.Manifest.permission.READ_MEDIA_VIDEO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private static boolean hasVideoPermission(Context ctx) {
        return ctx.checkSelfPermission(videoPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private static List<LocalFiles.Row> queryDeviceVideos(Context ctx) {
        List<LocalFiles.Row> out = new ArrayList<>();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
        };
        try (Cursor cur = ctx.getContentResolver().query(collection, proj, null, null,
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
        } catch (Throwable ignored) {}
        return out;
    }

    private static List<LocalFiles.Row> queryDownloads() {
        List<LocalFiles.Row> out = new ArrayList<>();
        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
        };
        for (File dir : dirs) {
            if (dir == null) continue;
            File[] files = null;
            try { files = dir.listFiles(); } catch (Throwable ignored) {}
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

    /** A picked file's display name (for the SAF door). */
    public static String displayName(Context ctx, String uriString) {
        try {
            Uri uri = Uri.parse(uriString);
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {}
        String tail = uriString;
        int slash = tail.lastIndexOf('/');
        if (slash >= 0) tail = tail.substring(slash + 1);
        return tail;
    }
}
