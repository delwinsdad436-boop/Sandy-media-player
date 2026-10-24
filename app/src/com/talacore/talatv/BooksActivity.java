package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The book shelf (v0.8.0) — the Read-to-me door inside My Library.
 * Tap a book and Sandy reads it aloud (ReaderActivity + the
 * ReaderService voice). Books arrive three ways: already on the
 * phone (media index), a folder picked on purpose, or a single
 * picked file. Nothing is copied; the shelf holds addresses.
 *
 * Since v0.14.0 every class on the shelf reads at home (PDF text
 * layer included — {@link BookText} is the one intake). A scanned-
 * image PDF or an unsupported file class is named plainly when
 * opened — no silent failures anywhere, no errands elsewhere.
 */
public final class BooksActivity extends Activity {

    private static final int REQ_FOLDER = 71;
    private static final int REQ_FILE = 72;

    private ListView list;
    private TextView emptyText;
    private List<BookStore.Book> books = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "books_title"));
        title.setTextSize(26);
        root.addView(title);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button rescan = new Button(this);
        rescan.setText(LangStore.tr(this, "books_rescan"));
        rescan.setOnClickListener(v -> scan(true));
        row.addView(rescan);
        Button addFolder = new Button(this);
        addFolder.setText(LangStore.tr(this, "books_add_folder"));
        addFolder.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            startActivityForResult(i, REQ_FOLDER);
        });
        row.addView(addFolder);
        Button addFile = new Button(this);
        addFile.setText(LangStore.tr(this, "books_add_file"));
        addFile.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/epub+zip", "text/plain", "application/pdf"});
            startActivityForResult(i, REQ_FILE);
        });
        row.addView(addFile);
        root.addView(row);

        emptyText = new TextView(this);
        emptyText.setText(LangStore.tr(this, "books_empty"));
        emptyText.setTextSize(18);
        emptyText.setPadding(0, 32, 0, 0);
        root.addView(emptyText);

        list = new ListView(this);
        list.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        list.setOnItemClickListener((parent, view, pos, id) -> {
            BookStore.Book b = books.get(pos);
            Intent i = new Intent(this, ReaderActivity.class);
            i.putExtra(ReaderActivity.X_BOOK, b.id);
            i.putExtra(ReaderActivity.X_AUTOSTART, true);
            startActivity(i);
        });
        root.addView(list);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        books = BookStore.list(this);
        if (books.isEmpty()) scan(false);
        else show();
    }

    private void scan(boolean announce) {
        new Thread(() -> {
            List<BookStore.Book> found = BookStore.rescan(this);
            runOnUiThread(() -> {
                books = found;
                show();
            });
        }).start();
    }

    private void show() {
        emptyText.setVisibility(books.isEmpty() ? View.VISIBLE : View.GONE);
        list.setAdapter(new ArrayAdapter<BookStore.Book>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, books) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                View v = super.getView(pos, convert, parent);
                BookStore.Book b = books.get(pos);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setText(b.title);
                t1.setTextSize(20);
                t2.setText(subLine(b));
                t2.setTextSize(15);
                return v;
            }
        });
    }

    private static String kindWord(String kind) {
        if (BookText.KIND_EPUB.equals(kind)) return "EPUB";
        if (BookText.KIND_PDF.equals(kind)) return "PDF";
        if (BookText.KIND_DOCX.equals(kind)) return "Word";
        if (BookText.KIND_ODT.equals(kind)) return "OpenDocument";
        if (BookText.KIND_HTML.equals(kind)) return "HTML";
        if (BookText.KIND_RTF.equals(kind)) return "RTF";
        return "Text";
    }

    private String subLine(BookStore.Book b) {
        ReaderStore.Bookmark bm = ReaderStore.bookmark(this, b.id);
        String kindWord = kindWord(b.kind);
        if (bm.finished) {
            return kindWord + " · " + LangStore.tr(this, "reader_finished");
        }
        if (bm.chunk > 0 && bm.totalChunks > 0) {
            int pct = (int) Math.round(100.0 * (bm.chunk + 1) / bm.totalChunks);
            String s = kindWord + " · Part " + (bm.chunk + 1) + " of "
                    + bm.totalChunks + " · " + pct + "%";
            if (!bm.chapter.isEmpty()) s += " · " + bm.chapter;
            return s;
        }
        return kindWord;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}
        if (req == REQ_FOLDER) {
            BookStore.addFolder(this, uri.toString());
        } else if (req == REQ_FILE) {
            BookStore.addFile(this, uri.toString());
        }
        scan(true);
    }
}
