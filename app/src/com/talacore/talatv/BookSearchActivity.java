package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Find a book (v0.16.0) — the Reader door's front room. One
 * search runs over every landed BookSource (Internet Archive,
 * Project Gutenberg, Open Library, LibriVox), each hit wearing
 * its own source's name, each availability state resolved from
 * evidence BEFORE a Read promise renders (Display Honesty):
 * Gutenberg and LibriVox hits resolve from their own payload;
 * Archive and Open Library hits resolve through the item's
 * /metadata file list, card by card, in the open.
 *
 * Read (OPEN only) downloads the best sibling through Hydra,
 * keeps it as a file, registers it on the book shelf, and opens
 * the reading room. Listen on a LibriVox hit plays its openly
 * reachable sections in the player.
 */
public class BookSearchActivity extends Activity {

    private EditText queryBox;
    private EditText langBox;
    private Button titleBtn, authorBtn, searchBtn;
    private TextView statusView;
    private LinearLayout resultsBox;
    private boolean byAuthor = false;
    private volatile boolean destroyed = false;
    private volatile int searchGen = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(24, 28, 44));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "books_find"));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(30);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        queryBox = new EditText(this);
        queryBox.setHint(LangStore.tr(this, "books_search_hint"));
        queryBox.setTextColor(Color.WHITE);
        queryBox.setHintTextColor(Color.rgb(150, 156, 180));
        queryBox.setTextSize(20);
        root.addView(queryBox, margins());

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        titleBtn = new Button(this);
        authorBtn = new Button(this);
        titleBtn.setText(LangStore.tr(this, "books_by_title"));
        authorBtn.setText(LangStore.tr(this, "books_by_author"));
        titleBtn.setOnClickListener(v -> { byAuthor = false; paintMode(); });
        authorBtn.setOnClickListener(v -> { byAuthor = true; paintMode(); });
        modeRow.addView(titleBtn, half());
        modeRow.addView(authorBtn, half());
        root.addView(modeRow);
        paintMode();

        langBox = new EditText(this);
        langBox.setHint(LangStore.tr(this, "books_language_hint"));
        langBox.setTextColor(Color.WHITE);
        langBox.setHintTextColor(Color.rgb(150, 156, 180));
        langBox.setTextSize(17);
        root.addView(langBox, margins());

        searchBtn = new Button(this);
        searchBtn.setText(LangStore.tr(this, "books_search"));
        searchBtn.setTextSize(20);
        searchBtn.setOnClickListener(v -> search());
        root.addView(searchBtn, margins());

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(200, 206, 226));
        statusView.setTextSize(17);
        root.addView(statusView, margins());

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);
        setContentView(scroll);
    }

    private void paintMode() {
        titleBtn.setEnabled(byAuthor);
        authorBtn.setEnabled(!byAuthor);
    }

    private void search() {
        final String q = queryBox.getText().toString().trim();
        if (q.isEmpty()) return;
        final String langName = langBox.getText().toString().trim();
        final boolean author = byAuthor;
        final int gen = ++searchGen;
        resultsBox.removeAllViews();
        statusView.setText(LangStore.tr(this, "books_searching"));
        searchBtn.setEnabled(false);
        new Thread(() -> {
            List<BookSource.Hit> all = new ArrayList<>();
            List<Exception> errors = new ArrayList<>();
            // Internet Archive (texts).
            try {
                String marc = langName.isEmpty() ? ""
                        : IaClient.iaLanguageCode(langName);
                all.addAll(BookSource.parseIaSearch(Net.getString(
                        BookSource.iaSearchUrl(q, author,
                                marc.isEmpty() && !langName.isEmpty()
                                        ? langName : marc, 20, 1))));
            } catch (Exception e) { errors.add(e); }
            // Project Gutenberg (Gutendex).
            try {
                String iso = langName.isEmpty() ? "" : isoOf(langName);
                all.addAll(BookSource.parseGutSearch(Net.getString(
                        BookSource.gutSearchUrl(q, iso))));
            } catch (Exception e) { errors.add(e); }
            // Open Library.
            try {
                String iso = langName.isEmpty() ? "" : isoOf(langName);
                all.addAll(BookSource.parseOlSearch(Net.getString(
                        BookSource.olSearchUrl(q, author, iso))));
            } catch (Exception e) { errors.add(e); }
            // LibriVox (audiobooks are found by title).
            try {
                all.addAll(BookSource.parseLvSearch(Net.getString(
                        BookSource.lvSearchUrl(q))));
            } catch (Exception e) { errors.add(e); }
            final List<BookSource.Hit> hits = all;
            runOnUiThread(() -> {
                if (destroyed || gen != searchGen) return;
                searchBtn.setEnabled(true);
                if (hits.isEmpty()) {
                    statusView.setText(errors.isEmpty()
                            ? LangStore.tr(this, "books_none")
                            : LangStore.tr(this, "books_none") + "\n"
                                    + words(errors.get(0)));
                    return;
                }
                statusView.setText("");
                for (BookSource.Hit h : hits) addCard(h, gen);
                resolveCards(hits, gen);
            });
        }, "book-search").start();
    }

    /** Language name (or code) → ISO-1 for Gutenberg/Open Library. */
    private String isoOf(String langName) {
        String marc = IaClient.iaLanguageCode(langName);
        String iso = MARC_TO_ISO.get(marc);
        if (iso != null) return iso;
        String low = langName.trim().toLowerCase(java.util.Locale.US);
        return low.length() == 2 ? low : "";
    }

    private static final java.util.Map<String, String> MARC_TO_ISO =
            new java.util.HashMap<>();
    static {
        String[][] pairs = {
                {"eng", "en"}, {"spa", "es"}, {"fre", "fr"}, {"fra", "fr"},
                {"ger", "de"}, {"deu", "de"}, {"por", "pt"}, {"ita", "it"},
                {"rus", "ru"}, {"ara", "ar"}, {"hin", "hi"}, {"chi", "zh"},
                {"zho", "zh"}, {"jpn", "ja"}, {"kor", "ko"}, {"per", "fa"},
                {"fas", "fa"}, {"dut", "nl"}, {"nld", "nl"}, {"swe", "sv"},
                {"dan", "da"}, {"nor", "no"}, {"fin", "fi"}, {"pol", "pl"},
                {"cze", "cs"}, {"ces", "cs"}, {"gre", "el"}, {"ell", "el"},
                {"tur", "tr"}, {"heb", "he"}, {"lat", "la"}, {"cat", "ca"},
        };
        for (String[] pr : pairs) MARC_TO_ISO.put(pr[0], pr[1]);
    }

    /** Plain failure words: Net's Hydra exceptions already carry
     *  them as their message (FailureWords); anything else gets
     *  the honest generic line, never a bare "HTTP error". */
    private String words(Exception e) {
        String m = e == null ? "" : e.getMessage();
        return (m == null || m.trim().isEmpty())
                ? LangStore.tr(this, "reader_failed") : m;
    }

    // ------------------------------------------------------------ cards

    private void addCard(final BookSource.Hit h, final int gen) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackgroundColor(Color.rgb(34, 40, 62));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(10);
        card.setLayoutParams(cl);

        TextView t = new TextView(this);
        t.setText(h.title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        card.addView(t);

        TextView meta = new TextView(this);
        StringBuilder mb = new StringBuilder();
        if (!h.author.isEmpty()) mb.append(h.author);
        if (!h.year.isEmpty()) {
            if (mb.length() > 0) mb.append(" · ");
            mb.append(h.year);
        }
        if (BookSource.LV.equals(h.source)) {
            if (mb.length() > 0) mb.append(" · ");
            mb.append(LangStore.tr(this, "books_audiobook"));
            if (h.audioSections > 0) {
                mb.append(" · ").append(h.audioSections).append(' ')
                        .append(LangStore.tr(this, "books_sections"));
            }
        }
        mb.append("\n").append(BookSource.sourceName(h.source));
        meta.setText(mb.toString());
        meta.setTextColor(Color.rgb(170, 178, 205));
        meta.setTextSize(15);
        card.addView(meta);

        TextView state = new TextView(this);
        state.setTextSize(16);
        state.setTag("state");
        card.addView(state);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(actions);
        card.setTag(h);
        paintCard(h, card, state, actions, gen);
        resultsBox.addView(card);
    }

    private void paintCard(final BookSource.Hit h, LinearLayout card,
                           TextView state, LinearLayout actions, int gen) {
        actions.removeAllViews();
        if (BookSource.LV.equals(h.source)) {
            state.setText(LangStore.tr(this, "books_open"));
            state.setTextColor(Color.rgb(150, 230, 160));
            Button listen = new Button(this);
            listen.setText(LangStore.tr(this, "books_listen"));
            listen.setOnClickListener(v -> listenAudiobook(h));
            actions.addView(listen);
            return;
        }
        if (h.state == BookSource.OPEN) {
            state.setText(LangStore.tr(this, "books_open"));
            state.setTextColor(Color.rgb(150, 230, 160));
            Button read = new Button(this);
            read.setText(LangStore.tr(this, "books_read"));
            read.setOnClickListener(v -> openBook(h));
            actions.addView(read);
        } else if (h.state == BookSource.WALLED) {
            // Named, never promised: no Read renders here.
            state.setText(LangStore.tr(this, "books_walled"));
            state.setTextColor(Color.rgb(255, 190, 90));
        } else {
            state.setText(h.siblingUrl.isEmpty()
                    ? LangStore.tr(this, "books_checking")
                    : LangStore.tr(this, "books_unknown"));
            state.setTextColor(Color.rgb(170, 178, 205));
        }
    }

    /** Resolve UNKNOWN Archive/Open Library cards from metadata. */
    private void resolveCards(final List<BookSource.Hit> hits, int gen) {
        new Thread(() -> {
            for (final BookSource.Hit h : hits) {
                if (destroyed || gen != searchGen) return;
                if (BookSource.IA.equals(h.source)) {
                    resolveIa(h, h.itemId);
                } else if (BookSource.OL.equals(h.source)
                        && !h.readVia.isEmpty()
                        && h.state == BookSource.UNKNOWN) {
                    resolveIa(h, h.readVia);
                } else {
                    continue;
                }
                runOnUiThread(() -> {
                    if (destroyed || gen != searchGen) return;
                    refreshCard(h);
                });
            }
        }, "book-resolve").start();
    }

    private void resolveIa(BookSource.Hit h, String iaId) {
        try {
            BookSource.applyIaMetadata(h,
                    Net.getString(IaClient.metadataUrl(iaId)));
            if (h.pageImages && h.pageCount == 0) {
                try {
                    String scandata = Net.getString("https://archive.org"
                            + "/download/" + iaId + "/" + iaId
                            + "_scandata.xml");
                    h.pageCount = BookSource.scandataLeafCount(scandata);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            // stays UNKNOWN — no promise was made
        }
    }

    private void refreshCard(BookSource.Hit h) {
        for (int i = 0; i < resultsBox.getChildCount(); i++) {
            View card = resultsBox.getChildAt(i);
            if (card.getTag() == h && card instanceof LinearLayout) {
                LinearLayout cl = (LinearLayout) card;
                for (int j = 0; j < cl.getChildCount(); j++) {
                    View v = cl.getChildAt(j);
                    if (v instanceof TextView && "state".equals(v.getTag())) {
                        LinearLayout actions =
                                (LinearLayout) cl.getChildAt(j + 1);
                        paintCard(h, cl, (TextView) v, actions, searchGen);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ actions

    /** Download the sibling, keep it as a file, open the room. */
    private void openBook(final BookSource.Hit h) {
        statusView.setText(LangStore.tr(this, "reader_loading"));
        new Thread(() -> {
            try {
                byte[] data = Net.getBytes(h.siblingUrl);
                if (data == null || data.length == 0) {
                    throw new Exception("empty");
                }
                String key = ReaderBook.keyFor(h.source, h.itemId);
                String ext = h.siblingKind.isEmpty() ? "txt" : h.siblingKind;
                File dir = new File(getFilesDir(), "reader_books");
                dir.mkdirs();
                File f = new File(dir, key + "." + ext);
                java.nio.file.Files.write(f.toPath(), data);
                String buri = f.getAbsolutePath();
                BookStore.addFile(this, buri);
                BookStore.Book book = BookStore.byId(this,
                        BookStore.idFor(buri));
                if (book == null) throw new Exception("not shelved");
                Intent i = new Intent(this, ReaderRoomActivity.class);
                i.putExtra(ReaderRoomActivity.X_BID, book.id);
                i.putExtra(ReaderRoomActivity.X_KEY, key);
                i.putExtra(ReaderRoomActivity.X_TITLE, h.title);
                i.putExtra(ReaderRoomActivity.X_AUTHOR, h.author);
                i.putExtra(ReaderRoomActivity.X_SOURCE, h.source);
                i.putExtra(ReaderRoomActivity.X_ITEM, h.itemId);
                i.putExtra(ReaderRoomActivity.X_IAID,
                        BookSource.IA.equals(h.source) ? h.itemId
                                : h.readVia);
                i.putExtra(ReaderRoomActivity.X_PAGES,
                        h.pageImages ? h.pageCount : 0);
                i.putExtra(ReaderRoomActivity.X_SERIES, h.series);
                String iso = BookSource.IA.equals(h.source)
                        ? MARC_TO_ISO.getOrDefault(h.language, "")
                        : h.language;
                i.putExtra(ReaderRoomActivity.X_LANG,
                        iso == null || iso.isEmpty() ? "en" : iso);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusView.setText("");
                    startActivity(i);
                });
            } catch (Exception e) {
                final String failWords = words(e);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusView.setText(LangStore.tr(this, "reader_failed")
                            + "\n" + failWords);
                });
            }
        }, "book-open").start();
    }

    /** A LibriVox audiobook: pick a section, play it in the player. */
    private void listenAudiobook(final BookSource.Hit h) {
        statusView.setText(LangStore.tr(this, "books_searching"));
        new Thread(() -> {
            try {
                String json = Net.getString(
                        BookSource.lvSectionsUrl(h.itemId.substring(2)));
                final List<String[]> sections =
                        BookSource.parseLvSections(json);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusView.setText("");
                    if (sections.isEmpty()) {
                        Toast.makeText(this, LangStore.tr(this,
                                "reader_no_librivox"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    String[] labels = new String[sections.size()];
                    for (int i = 0; i < sections.size(); i++) {
                        labels[i] = sections.get(i)[0];
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(h.title)
                            .setItems(labels, (d, which) -> {
                                Intent i = new Intent(this,
                                        PlayerActivity.class);
                                i.putExtra("url", sections.get(which)[1]);
                                i.putExtra("name", h.title + " — "
                                        + sections.get(which)[0]);
                                startActivity(i);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusView.setText(words(e));
                });
            }
        }, "librivox-sections").start();
    }

    // ------------------------------------------------------------ helpers

    private LinearLayout.LayoutParams margins() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private LinearLayout.LayoutParams half() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
