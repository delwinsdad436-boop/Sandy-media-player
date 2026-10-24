package com.talacore.talatv;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The book shelf (v0.8.0) — where Sandy's read-aloud finds books.
 * Mirrors LibraryStore's proven shape: the phone's media index
 * (best-effort — Android versions differ in what they expose for
 * documents), folders Mama or James picked on purpose (SAF tree
 * grants, walked with DocumentsContract), and single picked files.
 * The found set is persisted to filesDir/books.tsv so the shelf
 * survives cache clears and reboots alike.
 *
 * Books themselves are never copied or moved: the shelf holds
 * addresses (URIs), and the reader opens the book where it lives.
 */
public final class BookStore {

    private BookStore() {}

    private static final String PREFS = "talatv_books";
    private static final String KEY_FOLDERS = "folders_json";
    private static final String KEY_FILES = "files_json";
    /** Reader-side sanity cap: a book past this is refused in words. */
    public static final int MAX_BOOK_BYTES = 96 * 1024 * 1024;

    public static final class Book {
        public final String id;
        public final String title;
        /** content:// URI string or absolute file path. */
        public final String uri;
        /** epub / txt / pdf (by name; content sniffing happens at read). */
        public final String kind;

        public Book(String id, String title, String uri, String kind) {
            this.id = id;
            this.title = title;
            this.uri = uri;
            this.kind = kind;
        }
    }

    // ------------------------------------------------------------ prefs

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<String> getFolders(Context ctx) {
        return readJsonList(ctx, KEY_FOLDERS);
    }

    public static void addFolder(Context ctx, String treeUri) {
        List<String> cur = getFolders(ctx);
        if (!cur.contains(treeUri)) {
            cur.add(treeUri);
            writeJsonList(ctx, KEY_FOLDERS, cur);
        }
    }

    public static List<String> getFiles(Context ctx) {
        return readJsonList(ctx, KEY_FILES);
    }

    public static void addFile(Context ctx, String uri) {
        List<String> cur = getFiles(ctx);
        if (!cur.contains(uri)) {
            cur.add(uri);
            writeJsonList(ctx, KEY_FILES, cur);
        }
    }

    private static List<String> readJsonList(Context ctx, String key) {
        List<String> out = new ArrayList<>();
        try {
            String raw = prefs(ctx).getString(key, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {}
        return out;
    }

    private static void writeJsonList(Context ctx, String key, List<String> vals) {
        try {
            JSONArray arr = new JSONArray();
            for (String v : vals) arr.put(v);
            prefs(ctx).edit().putString(key, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ shelf

    /** Stable id from the book's address — the bookmark key. */
    public static String idFor(String uri) {
        int h = uri == null ? 0 : uri.hashCode();
        return "b" + Integer.toHexString(h & 0x7fffffff);
    }

    public static String kindForName(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".epub")) return BookText.KIND_EPUB;
        if (n.endsWith(".pdf")) return BookText.KIND_PDF;
        return BookText.KIND_TXT;
    }

    static String titleForName(String name) {
        if (name == null || name.isEmpty()) return "A book";
        int slash = name.lastIndexOf('/');
        String t = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = t.lastIndexOf('.');
        if (dot > 0) t = t.substring(0, dot);
        return t.replace('_', ' ').trim();
    }

    /** The persisted shelf (empty until the first scan). */
    public static List<Book> list(Context ctx) {
        List<Book> out = new ArrayList<>();
        File f = shelfFile(ctx);
        if (!f.isFile()) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            String text = new String(buf, 0, off, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                out.add(new Book(p[0], dec(p[1]), dec(p[2]), p[3]));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static Book byId(Context ctx, String id) {
        for (Book b : list(ctx)) if (b.id.equals(id)) return b;
        return null;
    }

    private static File shelfFile(Context ctx) {
        return new File(ctx.getFilesDir(), "books.tsv");
    }

    /** Full rescan: media index + picked folders + picked files. */
    public static List<Book> rescan(Context ctx) {
        Map<String, Book> found = new LinkedHashMap<>();
        scanMediaStore(ctx, found);
        for (String treeUri : getFolders(ctx)) {
            try {
                Uri tree = Uri.parse(treeUri);
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                walkTree(ctx, tree, rootId, 0, found);
            } catch (Exception ignored) {
                // A revoked folder is skipped; the shelf keeps the rest.
            }
        }
        for (String uriStr : getFiles(ctx)) {
            String title = displayName(ctx, Uri.parse(uriStr));
            if (title == null) title = titleForName(uriStr);
            found.put(uriStr, new Book(idFor(uriStr), title, uriStr,
                    kindForName(title)));
        }
        List<Book> out = new ArrayList<>(found.values());
        persist(ctx, out);
        return out;
    }

    private static void persist(Context ctx, List<Book> books) {
        StringBuilder sb = new StringBuilder();
        for (Book b : books) {
            sb.append(b.id).append('\t')
              .append(enc(b.title)).append('\t')
              .append(enc(b.uri)).append('\t')
              .append(b.kind).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(shelfFile(ctx))) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static void scanMediaStore(Context ctx, Map<String, Book> found) {
        try {
            Uri base = MediaStore.Files.getContentUri("external");
            String[] proj = {MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME};
            String sel = MediaStore.Files.FileColumns.MIME_TYPE + " IN (?,?)"
                    + " OR " + MediaStore.Files.FileColumns.DISPLAY_NAME
                    + " LIKE '%.epub'";
            String[] args = {"application/epub+zip", "text/plain"};
            try (Cursor c = ctx.getContentResolver().query(base, proj, sel, args, null)) {
                if (c == null) return;
                int idCol = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
                int nameCol = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                while (c.moveToNext()) {
                    long id = c.getLong(idCol);
                    String name = nameCol >= 0 ? c.getString(nameCol) : null;
                    if (name == null) continue;
                    String kind = kindForName(name);
                    if (BookText.KIND_TXT.equals(kind) && !name.toLowerCase().endsWith(".txt")
                            && !name.toLowerCase().endsWith(".text")) continue;
                    Uri uri = Uri.withAppendedPath(base, String.valueOf(id));
                    found.put(uri.toString(), new Book(idFor(uri.toString()),
                            titleForName(name), uri.toString(), kind));
                }
            }
        } catch (Throwable ignored) {
            // The index is a courtesy; picked folders are the sure road.
        }
    }

    private static void walkTree(Context ctx, Uri tree, String docId, int depth,
                                 Map<String, Book> out) {
        if (depth > 6) return;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor c = ctx.getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(ctx, tree, id, depth + 1, out);
                } else if (name != null && isBookName(name)) {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    out.put(docUri.toString(), new Book(idFor(docUri.toString()),
                            titleForName(name), docUri.toString(), kindForName(name)));
                }
            }
        } catch (Exception ignored) {}
    }

    private static boolean isBookName(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".epub") || n.endsWith(".txt")
                || n.endsWith(".text") || n.endsWith(".pdf");
    }

    /** Human name for a content URI, or null when the store won't say. */
    public static String displayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String v = c.getString(0);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ------------------------------------------------------------ reading

    /** Open the book where it lives. Null on any failure. */
    public static InputStream open(Context ctx, Book b) {
        try {
            if (b.uri.startsWith("content://")) {
                return ctx.getContentResolver().openInputStream(Uri.parse(b.uri));
            }
            return new FileInputStream(new File(b.uri));
        } catch (Exception e) {
            return null;
        }
    }

    /** Read the whole book (capped). Null when unreadable or too big. */
    public static byte[] readAll(Context ctx, Book b) {
        try (InputStream in = open(ctx, b)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > MAX_BOOK_BYTES) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // TSV field escaping — the same URL-codec shape ChannelStore
    // uses for its caches, kept local so the shelf stands alone.
    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static String dec(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    /** ContentResolver kept referenced for callers that resolve names. */
    static ContentResolver resolver(Context ctx) {
        return ctx.getContentResolver();
    }
}
