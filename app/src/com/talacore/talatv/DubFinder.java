package com.talacore.talatv;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.util.List;

/**
 * Finds a prepared-dub sidecar for a library video (v0.4.0). The
 * naming convention lives in DubTrack; this class does the looking,
 * across the three ways a library file can sit on the phone:
 *
 *  - a plain path / file:// URI  -> a java.io.File beside it
 *  - a MediaStore content URI    -> an audio row with the sidecar's
 *                                   name in the same folder
 *  - a SAF document URI          -> the sibling document in the
 *                                   same picked folder
 *
 * Every road is best-effort and fails to null: no sidecar found simply
 * means the Subtitles/Audio menu offers the original tracks only.
 */
public final class DubFinder {

    private DubFinder() {}

    /** Sidecar URI for this video, or null when none is on the phone. */
    public static String findDub(Context ctx, LibraryIndex.Entry entry) {
        return findAny(ctx, entry, entry == null ? null
                : DubTrack.sidecarNames(entry.fileName));
    }

    /**
     * A cooked translated-subtitle sidecar (v0.6.0) for this video in
     * the given language; same three roads as the dub lookup.
     */
    public static String findTrSub(Context ctx, LibraryIndex.Entry entry, String lang) {
        return findAny(ctx, entry, entry == null ? null
                : DubTrack.trSubNames(entry.fileName, lang), true);
    }

    /** A prepared dub (v0.6.0) in any target language. */
    public static String findDubLang(Context ctx, LibraryIndex.Entry entry, String lang) {
        return findAny(ctx, entry, entry == null ? null
                : DubTrack.dubNamesFor(entry.fileName, lang));
    }

    /** The shared lookup: any of {@code names} beside this video. */
    public static String findAny(Context ctx, LibraryIndex.Entry entry, List<String> names) {
        return findAny(ctx, entry, names, false);
    }

    /**
     * @param filesTable true when the sidecar is a document (subtitle)
     *        rather than audio — the MediaStore road then queries the
     *        Files table instead of the Audio table.
     */
    public static String findAny(Context ctx, LibraryIndex.Entry entry,
                                 List<String> names, boolean filesTable) {
        if (entry == null || entry.uri == null || entry.uri.isEmpty()) return null;
        String uriStr = entry.uri;
        if (names == null || names.isEmpty()) return null;

        if (!uriStr.contains("://")) {
            return fromPath(uriStr, names);
        }
        Uri uri = Uri.parse(uriStr);
        String scheme = uri.getScheme();
        if ("file".equals(scheme)) {
            return fromPath(uri.getPath(), names);
        }
        if ("content".equals(scheme)) {
            if ("media".equals(uri.getAuthority())) {
                return fromMediaStore(ctx, uri, names, filesTable);
            }
            return fromSaf(ctx, uri, names);
        }
        return null;
    }

    private static String fromPath(String path, List<String> names) {
        if (path == null) return null;
        File video = new File(path);
        File dir = video.getParentFile();
        if (dir == null) return null;
        for (String name : names) {
            File side = new File(dir, name);
            if (side.isFile()) return Uri.fromFile(side).toString();
        }
        return null;
    }

    private static String fromMediaStore(Context ctx, Uri videoUri, List<String> names,
                                         boolean filesTable) {
        ContentResolver cr = ctx.getContentResolver();
        String relPath = null;
        try (Cursor c = cr.query(videoUri,
                new String[]{MediaStore.MediaColumns.RELATIVE_PATH}, null, null, null)) {
            if (c != null && c.moveToFirst()) relPath = c.getString(0);
        } catch (Exception ignored) {}
        if (relPath == null) return null;
        for (String name : names) {
            Uri audio = filesTable
                    ? MediaStore.Files.getContentUri("external")
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            try (Cursor c = cr.query(audio, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{relPath, name}, null)) {
                if (c != null && c.moveToFirst()) {
                    Uri found = Uri.withAppendedPath(audio, Long.toString(c.getLong(0)));
                    return found.toString();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static String fromSaf(Context ctx, Uri docUri, List<String> names) {
        try {
            String docId = DocumentsContract.getDocumentId(docUri);
            int slash = docId.lastIndexOf('/');
            if (slash < 0) return null;
            String parent = docId.substring(0, slash + 1);
            ContentResolver cr = ctx.getContentResolver();
            for (String name : names) {
                Uri sibling = DocumentsContract.buildDocumentUri(
                        docUri.getAuthority(), parent + name);
                try (Cursor c = cr.query(sibling,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) return sibling.toString();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }
}
