package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The reading room (v0.16.0) — the book ON SCREEN inside Sandy.
 * Native, no WebView: the one BookText intake renders the book
 * as text Sandy lays out herself.
 *
 *   - Paged mode AND continuous scroll, the reader's choice;
 *     chapter navigation wherever the format carries structure.
 *   - Large-print type scaling and three contrast themes (day,
 *     night, high-contrast) — sighted-but-low-vision symmetry
 *     with read-aloud.
 *   - Position + bookmarks kept as FILES on the memory hook
 *     (ReaderBook): leave, come back, land on the same page.
 *   - Listen hands the book to the read-aloud ladder FROM the
 *     current position (the place is written through
 *     ReaderStore, the remote opens already standing there).
 *   - Page pictures: where the Archive item's own book-reader
 *     structure was verified (public scandata, counted leaves),
 *     the scanned pages themselves, turned natively.
 *   - Make a story: any open, readable book becomes a story
 *     cartridge through the Story machinery (Addendum 3), its
 *     cast resolved SERIES-FIRST where the book belongs to a
 *     series (Addendum 5).
 */
public class ReaderRoomActivity extends Activity {

    public static final String X_BID = "rbid";       // BookStore id
    public static final String X_KEY = "rkey";       // ReaderBook key
    public static final String X_TITLE = "rtitle";
    public static final String X_AUTHOR = "rauthor";
    public static final String X_SOURCE = "rsource"; // BookSource id
    public static final String X_ITEM = "ritem";
    public static final String X_IAID = "riaid";     // for page pictures
    public static final String X_PAGES = "rpages";   // verified leaf count
    public static final String X_SERIES = "rseries"; // metadata series name
    public static final String X_LANG = "rlang";     // iso1

    private String bookId = "", key = "", title = "", author = "";
    private String source = BookSource.IA, iaId = "", lang = "en";
    private String metaSeries = "";
    private int pageCount = 0;

    private String text = "";
    private List<BookText.Chapter> chapters = new ArrayList<>();
    private List<Integer> pageStarts = new ArrayList<>();
    private int currentPage = 0;
    private int leaf = 0;
    private String mode;                       // paged / scroll / pages
    private ReaderBook.Settings settings;
    private List<ReaderBook.Mark> marks = new ArrayList<>();
    private volatile boolean destroyed = false;
    private volatile boolean loaded = false;

    private TextView titleView, statusView, pagedView, scrollText, pageLabel;
    private ScrollView scroller;
    private ImageView pageImage;
    private LinearLayout pageNav;
    private Button modeBtn, pagesBtn;
    private View rootView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        Intent in = getIntent();
        bookId = str(in.getStringExtra(X_BID));
        key = str(in.getStringExtra(X_KEY));
        title = str(in.getStringExtra(X_TITLE));
        author = str(in.getStringExtra(X_AUTHOR));
        source = str(in.getStringExtra(X_SOURCE));
        if (source.isEmpty()) source = BookSource.IA;
        iaId = str(in.getStringExtra(X_IAID));
        metaSeries = str(in.getStringExtra(X_SERIES));
        lang = str(in.getStringExtra(X_LANG));
        if (lang.isEmpty()) lang = "en";
        pageCount = in.getIntExtra(X_PAGES, 0);
        if (key.isEmpty()) key = ReaderBook.keyFor(source, bookId);

        File root = MemoryHook.root(this);
        settings = ReaderBook.loadSettings(root);
        ReaderBook.Pos pos = ReaderBook.loadPos(root, key);
        marks = ReaderBook.loadMarks(root, key);
        mode = (pos.updatedMs > 0 && pos.mode != null && !pos.mode.isEmpty())
                ? pos.mode : settings.mode;
        if (ReaderBook.MODE_PAGES.equals(mode)
                && (iaId.isEmpty() || pageCount <= 0)) {
            mode = settings.mode;
        }
        leaf = pos.leaf;

        buildLayout();
        applyTheme();
        statusView.setText(LangStore.tr(this, "reader_loading"));
        final int startOffset = pos.charOffset;
        new Thread(() -> loadBook(startOffset), "reader-load").start();
    }

    // ------------------------------------------------------------ layout

    private void buildLayout() {
        LinearLayout rootL = new LinearLayout(this);
        rootL.setOrientation(LinearLayout.VERTICAL);
        rootL.setPadding(dp(14), dp(12), dp(14), dp(12));
        rootView = rootL;

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(22);
        titleView.setTypeface(titleView.getTypeface(), Typeface.BOLD);
        rootL.addView(titleView);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        rootL.addView(statusView);

        LinearLayout row1 = buttonRow(rootL);
        row1.addView(btn(LangStore.tr(this, "reader_contents"),
                v -> showContents()), quarter());
        row1.addView(btn(LangStore.tr(this, "reader_bookmarks"),
                v -> showBookmarks()), quarter());
        row1.addView(btn(LangStore.tr(this, "reader_bookmark"),
                v -> toggleBookmark()), quarter());
        row1.addView(btn(LangStore.tr(this, "reader_listen"),
                v -> listenHandoff()), quarter());

        LinearLayout row2 = buttonRow(rootL);
        row2.addView(btn(LangStore.tr(this, "reader_smaller"),
                v -> bumpType(-2)), fifth());
        row2.addView(btn(LangStore.tr(this, "reader_bigger"),
                v -> bumpType(2)), fifth());
        row2.addView(btn(LangStore.tr(this, "reader_theme"),
                v -> cycleTheme()), fifth());
        modeBtn = btn("", v -> toggleMode());
        row2.addView(modeBtn, fifth());
        pagesBtn = btn(LangStore.tr(this, "reader_pages"),
                v -> togglePages());
        pagesBtn.setVisibility(iaId.isEmpty() || pageCount <= 0
                ? View.GONE : View.VISIBLE);
        row2.addView(pagesBtn, fifth());

        LinearLayout row3 = buttonRow(rootL);
        row3.addView(btn(LangStore.tr(this, "reader_make_story"),
                v -> makeStory()), third());
        row3.addView(btn(LangStore.tr(this, "reader_group_series"),
                v -> groupSeries()), third());
        row3.addView(btn(LangStore.tr(this, "reader_librivox"),
                v -> librivox()), third());

        FrameLayoutHolder body = new FrameLayoutHolder(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bl.topMargin = dp(8);
        rootL.addView(body, bl);

        scroller = new ScrollView(this);
        scrollText = new TextView(this);
        scrollText.setPadding(dp(6), dp(6), dp(6), dp(24));
        scroller.addView(scrollText);
        body.addView(scroller);

        pagedView = new TextView(this);
        pagedView.setPadding(dp(6), dp(6), dp(6), dp(6));
        pagedView.setOnClickListener(v -> turnPage(1));
        body.addView(pagedView);

        pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageImage.setAdjustViewBounds(true);
        body.addView(pageImage);

        pageNav = new LinearLayout(this);
        pageNav.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = btn("◀", v -> nav(-1));
        Button next = btn("▶", v -> nav(1));
        pageLabel = new TextView(this);
        pageLabel.setGravity(android.view.Gravity.CENTER);
        pageLabel.setTextSize(16);
        pageNav.addView(prev, quarter());
        pageNav.addView(pageLabel, half());
        pageNav.addView(next, quarter());
        rootL.addView(pageNav);
        setContentView(rootL);
        paintModeButton();
    }

    /** FrameLayout without the import weight at every call site. */
    private static final class FrameLayoutHolder
            extends android.widget.FrameLayout {
        FrameLayoutHolder(android.content.Context c) { super(c); }
    }

    private LinearLayout buttonRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(row, lp);
        return row;
    }

    private Button btn(String label, View.OnClickListener fn) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        // v0.17.0: the room's chrome wears smoked glass. The
        // READING surface keeps the reader's own chosen theme —
        // applyTheme owns it and is untouched by design.
        b.setBackground(Sanctuary.glassButton(this, false));
        b.setTextColor(Sanctuary.INK);
        b.setOnClickListener(fn);
        return b;
    }

    private void paintModeButton() {
        if (modeBtn != null) {
            modeBtn.setText(LangStore.tr(this,
                    ReaderBook.MODE_PAGED.equals(mode)
                            ? "reader_mode_scroll" : "reader_mode_paged"));
        }
        if (pagesBtn != null) {
            pagesBtn.setText(LangStore.tr(this,
                    ReaderBook.MODE_PAGES.equals(mode)
                            ? "reader_text" : "reader_pages"));
        }
    }

    private void applyTheme() {
        int bg, fg;
        if (ReaderBook.THEME_NIGHT.equals(settings.theme)) {
            bg = Color.rgb(10, 10, 14); fg = Color.rgb(216, 210, 192);
        } else if (ReaderBook.THEME_CONTRAST.equals(settings.theme)) {
            bg = Color.BLACK; fg = Color.WHITE;
        } else {
            bg = Color.rgb(250, 247, 238); fg = Color.rgb(24, 24, 24);
        }
        rootView.setBackgroundColor(bg);
        titleView.setTextColor(fg);
        statusView.setTextColor(fg);
        pagedView.setTextColor(fg);
        scrollText.setTextColor(fg);
        pageLabel.setTextColor(fg);
        pagedView.setTextSize(settings.textSp);
        scrollText.setTextSize(settings.textSp);
    }

    // ------------------------------------------------------------ loading

    private void loadBook(final int startOffset) {
        try {
            BookStore.Book book = BookStore.byId(this, bookId);
            if (book == null) throw new Exception("no book");
            byte[] bytes = BookStore.readAll(this, book);
            BookText.Result r = BookText.extract(bytes,
                    book.uri == null || book.uri.isEmpty()
                            ? book.title : book.uri);
            if (!r.ok || r.text == null || r.text.isEmpty()) {
                final boolean scanned = BookText.SCANNED_NO_TEXT
                        .equals(r.failReason);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    if (scanned && !iaId.isEmpty() && pageCount > 0) {
                        // Pictures of pages, and the page structure
                        // was verified: the pages themselves serve.
                        mode = ReaderBook.MODE_PAGES;
                        loaded = true;
                        showMode();
                        showLeaf();
                        statusView.setText("");
                    } else {
                        statusView.setText(LangStore.tr(this,
                                scanned ? "reader_scanned"
                                        : "reader_no_text"));
                    }
                });
                return;
            }
            text = r.text;
            chapters = r.chapters != null ? r.chapters : new ArrayList<>();
            loaded = true;
            runOnUiThread(() -> {
                if (destroyed) return;
                statusView.setText("");
                repaginate();
                showMode();
                applyPosition(startOffset);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                if (destroyed) return;
                statusView.setText(LangStore.tr(this, "reader_failed"));
            });
        }
    }

    private void repaginate() {
        pageStarts = ReaderBook.pageStarts(text,
                ReaderBook.charsPerPage(settings.textSp));
        if (currentPage >= pageStarts.size()) {
            currentPage = pageStarts.size() - 1;
        }
        if (currentPage < 0) currentPage = 0;
    }

    private List<BookText.Chapter> chapters() {
        if (!chapters.isEmpty()) return chapters;
        List<BookText.Chapter> one = new ArrayList<>();
        if (!text.isEmpty()) one.add(new BookText.Chapter(title, 0));
        return one;
    }

    // ------------------------------------------------------------ display

    private void showMode() {
        boolean paged = ReaderBook.MODE_PAGED.equals(mode);
        boolean pages = ReaderBook.MODE_PAGES.equals(mode);
        pagedView.setVisibility(paged ? View.VISIBLE : View.GONE);
        scroller.setVisibility(!paged && !pages
                ? View.VISIBLE : View.GONE);
        pageImage.setVisibility(pages ? View.VISIBLE : View.GONE);
        pageNav.setVisibility(paged || pages
                ? View.VISIBLE : View.GONE);
        if (paged) showPage();
        if (pages) showLeaf();
        paintModeButton();
    }

    private void showPage() {
        if (pageStarts.isEmpty()) return;
        int start = pageStarts.get(currentPage);
        int end = currentPage + 1 < pageStarts.size()
                ? pageStarts.get(currentPage + 1) : text.length();
        pagedView.setText(text.substring(start, Math.min(end,
                text.length())));
        pageLabel.setText((currentPage + 1) + " / " + pageStarts.size());
    }

    private void applyPosition(int charOffset) {
        if (ReaderBook.MODE_PAGED.equals(mode)) {
            currentPage = ReaderBook.pageAtOffset(pageStarts, charOffset);
            showPage();
        } else if (ReaderBook.MODE_SCROLL.equals(mode)) {
            scroller.postDelayed(() -> {
                if (destroyed || scrollText.getLayout() == null) return;
                int line = scrollText.getLayout().getLineForOffset(
                        Math.min(charOffset, Math.max(0,
                                scrollText.getText().length() - 1)));
                scroller.scrollTo(0,
                        scrollText.getLayout().getLineTop(line));
            }, 200);
            scrollText.setText(text);
        }
    }

    private void showLeaf() {
        if (iaId.isEmpty() || pageCount <= 0) return;
        leaf = Math.max(0, Math.min(pageCount - 1, leaf));
        pageLabel.setText(LangStore.tr(this, "reader_page") + " "
                + (leaf + 1) + " / " + pageCount);
        statusView.setText(LangStore.tr(this, "reader_loading"));
        final int want = leaf;
        new Thread(() -> {
            try {
                byte[] img = Net.getBytes(
                        BookSource.iaPageUrl(iaId, want));
                final Bitmap bmp = img == null ? null
                        : BitmapFactory.decodeByteArray(img, 0, img.length);
                runOnUiThread(() -> {
                    if (destroyed || want != leaf) return;
                    statusView.setText("");
                    if (bmp != null) pageImage.setImageBitmap(bmp);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    statusView.setText(LangStore.tr(this, "reader_failed"));
                });
            }
        }, "reader-page").start();
    }

    private int currentCharOffset() {
        if (ReaderBook.MODE_PAGES.equals(mode)) return 0;
        if (ReaderBook.MODE_PAGED.equals(mode)) {
            return pageStarts.isEmpty() ? 0 : pageStarts.get(currentPage);
        }
        if (scrollText.getLayout() != null) {
            int line = scrollText.getLayout().getLineForVertical(
                    scroller.getScrollY());
            return scrollText.getLayout().getLineStart(line);
        }
        return 0;
    }

    private void savePos() {
        if (!loaded) return;
        ReaderBook.Pos p = new ReaderBook.Pos();
        p.mode = mode;
        p.leaf = leaf;
        p.charOffset = ReaderBook.MODE_PAGES.equals(mode)
                ? 0 : currentCharOffset();
        p.updatedMs = System.currentTimeMillis();
        ReaderBook.savePos(MemoryHook.root(this), key, p);
    }

    // ------------------------------------------------------------ controls

    private void nav(int dir) {
        if (ReaderBook.MODE_PAGES.equals(mode)) {
            leaf = Math.max(0, Math.min(pageCount - 1, leaf + dir));
            showLeaf();
        } else {
            turnPage(dir);
        }
        savePos();
    }

    private void turnPage(int dir) {
        currentPage = Math.max(0, Math.min(pageStarts.size() - 1,
                currentPage + dir));
        showPage();
    }

    private void bumpType(int delta) {
        settings.textSp = Math.max(ReaderBook.MIN_SP,
                Math.min(ReaderBook.MAX_SP, settings.textSp + delta));
        ReaderBook.saveSettings(MemoryHook.root(this), settings);
        applyTheme();
        int keep = currentCharOffset();
        repaginate();
        if (ReaderBook.MODE_PAGED.equals(mode)) {
            currentPage = ReaderBook.pageAtOffset(pageStarts, keep);
            showPage();
        }
        savePos();
    }

    private void cycleTheme() {
        settings.theme = ReaderBook.nextTheme(settings.theme);
        ReaderBook.saveSettings(MemoryHook.root(this), settings);
        applyTheme();
    }

    private void toggleMode() {
        int keep = currentCharOffset();
        mode = ReaderBook.MODE_PAGED.equals(mode)
                ? ReaderBook.MODE_SCROLL : ReaderBook.MODE_PAGED;
        settings.mode = mode;
        ReaderBook.saveSettings(MemoryHook.root(this), settings);
        showMode();
        if (ReaderBook.MODE_SCROLL.equals(mode)) {
            scrollText.setText(text);
            applyPosition(keep);
        } else {
            currentPage = ReaderBook.pageAtOffset(pageStarts, keep);
            showPage();
        }
        savePos();
    }

    private void togglePages() {
        if (ReaderBook.MODE_PAGES.equals(mode)) {
            mode = settings.mode;
        } else {
            mode = ReaderBook.MODE_PAGES;
            showLeaf();
        }
        showMode();
        savePos();
    }

    private void jumpTo(int charOffset) {
        if (ReaderBook.MODE_PAGES.equals(mode)) {
            mode = settings.mode;
            showMode();
        }
        if (ReaderBook.MODE_PAGED.equals(mode)) {
            currentPage = ReaderBook.pageAtOffset(pageStarts, charOffset);
            showPage();
        } else {
            applyPosition(charOffset);
        }
        savePos();
    }

    private void showContents() {
        List<BookText.Chapter> chs = chapters();
        String[] labels = new String[chs.size()];
        for (int i = 0; i < chs.size(); i++) labels[i] = chs.get(i).title;
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "reader_contents"))
                .setItems(labels, (d, w) -> jumpTo(chs.get(w).charOffset))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleBookmark() {
        int at = currentCharOffset();
        boolean exists = false;
        for (ReaderBook.Mark m : marks) {
            if (m.charOffset == at) { exists = true; break; }
        }
        if (exists) {
            marks.removeIf(m -> m.charOffset == at);
        } else {
            String label = LangStore.tr(this, "reader_page") + " "
                    + (ReaderBook.MODE_PAGED.equals(mode)
                            ? (currentPage + 1) : "");
            for (BookText.Chapter c : chapters()) {
                if (c.charOffset <= at) label = c.title;
            }
            marks = ReaderBook.withMark(marks, at, label.trim());
        }
        ReaderBook.saveMarks(MemoryHook.root(this), key, marks);
    }

    private void showBookmarks() {
        if (marks.isEmpty()) {
            Toast.makeText(this, LangStore.tr(this, "reader_bookmarks"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[marks.size()];
        for (int i = 0; i < marks.size(); i++) {
            labels[i] = marks.get(i).label.isEmpty()
                    ? String.valueOf(marks.get(i).charOffset)
                    : marks.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "reader_bookmarks"))
                .setItems(labels, (d, w) -> jumpTo(marks.get(w).charOffset))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ listen

    /**
     * The read-aloud handoff: the place is written where the
     * reader ladder keeps it (chunk index from the CURRENT char
     * offset — BookChunker tracks offsets for exactly this), and
     * the reader remote opens already standing there.
     */
    private void listenHandoff() {
        if (!loaded || text.isEmpty()) return;
        int at = currentCharOffset();
        List<BookChunker.Chunk> chunks =
                BookChunker.chunk(text, chapters());
        if (chunks.isEmpty()) return;
        int idx = BookChunker.chunkIndexAt(chunks, at);
        ReaderStore.save(this, bookId, idx, chunks.get(idx).startChar,
                chunks.size(), BookChunker.chapterAt(chunks, idx),
                ReaderStore.MODE_ORIGINAL);
        savePos();
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.X_BOOK, bookId);
        i.putExtra(ReaderActivity.X_AUTOSTART, true);
        startActivity(i);
    }

    /** A verified LibriVox recording of THIS book, where one exists. */
    private void librivox() {
        Toast.makeText(this, LangStore.tr(this, "books_searching"),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String foundId = "";
            List<String[]> sections = new ArrayList<>();
            try {
                String json = Net.getString(
                        BookSource.lvSearchUrl(title));
                if (BookSource.lvMatches(json, title, author)) {
                    for (BookSource.Hit h : BookSource.parseLvSearch(json)) {
                        if (BookSource.normTitle(h.title).equals(
                                BookSource.normTitle(title))) {
                            foundId = h.itemId.substring(2);
                            break;
                        }
                    }
                }
                if (!foundId.isEmpty()) {
                    sections = BookSource.parseLvSections(
                            Net.getString(BookSource.lvSectionsUrl(foundId)));
                }
            } catch (Exception ignored) {}
            final List<String[]> secs = sections;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (secs.isEmpty()) {
                    Toast.makeText(this, LangStore.tr(this,
                            "reader_no_librivox"), Toast.LENGTH_LONG).show();
                    return;
                }
                String[] labels = new String[secs.size()];
                for (int i = 0; i < secs.size(); i++) labels[i] = secs.get(i)[0];
                new AlertDialog.Builder(this)
                        .setTitle(LangStore.tr(this, "reader_librivox"))
                        .setItems(labels, (d, w) -> {
                            Intent i = new Intent(this, PlayerActivity.class);
                            i.putExtra("url", secs.get(w)[1]);
                            i.putExtra("name", title + " — " + secs.get(w)[0]);
                            startActivity(i);
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }, "reader-librivox").start();
    }

    // ------------------------------------------------------------ story-fy

    /**
     * MAKE A STORY OF ANY BOOK (Addendum 3). This room only ever
     * stands on an open, readable book (BookText resolved it end
     * to end), so eligibility is already proven; a walled or
     * unresolvable book never reaches this button. The telling
     * is cast through the book's VoiceBook — resolved
     * SERIES-FIRST and enrolled back into the series book where
     * the book belongs to one (Addendums 4 + 5) — and the result
     * is a story cartridge on the memory hook, labeled with its
     * provenance, shelved with the made stories in Story Time.
     */
    private void makeStory() {
        if (!loaded || text.isEmpty()) return;
        Toast.makeText(this, LangStore.tr(this, "books_searching"),
                Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String rowId = "made_" + key;
                StoryData.Row row = new StoryData.Row();
                row.id = rowId;
                row.title = title;
                row.author = author;
                row.lang = lang;
                row.taleGroup = "";
                row.iaId = BookSource.IA.equals(source) ? iaId : "";
                row.source = source;
                row.illustrated = pageCount > 0;
                StoryFiles.saveBook(this, row, text);
                StoryFiles.addMade(this, row);

                // The cast: book VoiceBook, series tier first.
                StoryCast.Book bookCast = StoryFiles.loadCast(this, row);
                File root = MemoryHook.root(this);
                String skey = SeriesBook.seriesKeyFor(root, key);
                if (skey.isEmpty() && !metaSeries.isEmpty()) {
                    skey = SeriesBook.group(root, key, metaSeries,
                            "metadata");
                }
                StoryCast.Book resolved = bookCast;
                StoryCast.Book seriesBook = null;
                if (!skey.isEmpty()) {
                    String sname = metaSeries;
                    for (SeriesBook.Group g : SeriesBook.loadIndex(root)) {
                        if (g.key.equals(skey)) sname = g.name;
                    }
                    seriesBook = SeriesBook.loadCast(root, skey, sname, lang);
                    resolved = SeriesBook.resolve(seriesBook, bookCast);
                }
                List<String> segments = chapterSegments();
                StoryCast.collectCast(resolved, segments);
                StoryFiles.assignCards(resolved);
                StoryFiles.saveCast(this, resolved);
                if (seriesBook != null) {
                    SeriesBook.saveCast(root,
                            SeriesBook.enrollInto(seriesBook, resolved));
                }

                // The cartridge: one panel per chapter; the card
                // is the chapter's most-heard voice, the caption
                // the chapter's own name.
                StoryData.Cartridge cart = buildCartridge(
                        rowId + "_" + Long.toString(
                                System.currentTimeMillis(), 36),
                        title, System.currentTimeMillis(), rowId,
                        title, BookSource.sourceName(source),
                        resolved, text, chapters());
                StoryFiles.saveCartridge(this, cart);
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, LangStore.tr(this,
                            "reader_story_made"), Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed) return;
                    Toast.makeText(this, LangStore.tr(this,
                            "reader_story_failed"), Toast.LENGTH_LONG).show();
                });
            }
        }, "reader-make-story").start();
    }

    /**
     * The made-story cartridge for a book (pure): one panel per
     * chapter, the panel card the chapter's most-heard voice
     * (the narrator's card when the chapter is all narration),
     * the caption the chapter's own name; provenance stamped —
     * "Made from <book> (<source>)".
     */
    public static StoryData.Cartridge buildCartridge(
            String cartId, String title, long createdMs, String bookRowId,
            String fromBook, String fromSource, StoryCast.Book cast,
            String text, List<BookText.Chapter> chs) {
        StoryData.Cartridge cart = new StoryData.Cartridge();
        cart.id = cartId;
        cart.title = title;
        cart.createdMs = createdMs;
        cart.bookId = bookRowId;
        cart.fromBook = fromBook;
        cart.fromSource = fromSource;
        if (chs != null) {
            for (int ci = 0; ci < chs.size(); ci++) {
                int from = chs.get(ci).charOffset;
                int to = ci + 1 < chs.size()
                        ? chs.get(ci + 1).charOffset : text.length();
                String slice = text.substring(
                        Math.min(from, text.length()),
                        Math.min(to, text.length()));
                String speaker = dominantSpeakerOf(cast, slice);
                StoryData.CartridgePanel p = new StoryData.CartridgePanel();
                p.panel = StoryCast.NARRATOR.equals(speaker)
                        ? StoryPanels.NARRATOR_CARD
                        : StoryPanels.cardFor(bookRowId, speaker);
                p.caption = chs.get(ci).title;
                cart.panels.add(p);
            }
        }
        if (cart.panels.isEmpty()) {
            StoryData.CartridgePanel p = new StoryData.CartridgePanel();
            p.panel = StoryPanels.NARRATOR_CARD;
            p.caption = title;
            cart.panels.add(p);
        }
        return cart;
    }

    /** Chapter-sized slices for casting (bounded when headless). */
    private List<String> chapterSegments() {
        List<String> out = new ArrayList<>();
        List<BookText.Chapter> chs = chapters();
        if (chs.size() > 1) {
            for (int i = 0; i < chs.size(); i++) {
                int from = chs.get(i).charOffset;
                int to = i + 1 < chs.size()
                        ? chs.get(i + 1).charOffset : text.length();
                out.add(text.substring(Math.min(from, text.length()),
                        Math.min(to, text.length())));
            }
        } else {
            for (int at = 0; at < text.length(); at += 60000) {
                out.add(text.substring(at,
                        Math.min(text.length(), at + 60000)));
            }
            if (out.isEmpty()) out.add(text);
        }
        return out;
    }

    /** The voice heard most in a slice (bounded sample). */
    private String dominantSpeaker(StoryCast.Book cast, String slice) {
        return dominantSpeakerOf(cast, slice);
    }

    public static String dominantSpeakerOf(StoryCast.Book cast, String slice) {
        String sample = slice.length() > 6000
                ? slice.substring(0, 6000) : slice;
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        String best = StoryCast.NARRATOR;
        int bestN = 0;
        for (StoryCast.Segment seg : StoryCast.attribute(cast, sample)) {
            int n = counts.getOrDefault(seg.speaker, 0) + seg.text.length();
            counts.put(seg.speaker, n);
            if (n > bestN && !StoryCast.NARRATOR.equals(seg.speaker)) {
                bestN = n;
                best = seg.speaker;
            }
        }
        return best;
    }

    /** Group this book into a series BY HAND (Addendum 5). */
    private void groupSeries() {
        File root = MemoryHook.root(this);
        String current = "";
        String skey = SeriesBook.seriesKeyFor(root, key);
        for (SeriesBook.Group g : SeriesBook.loadIndex(root)) {
            if (g.key.equals(skey)) current = g.name;
        }
        if (current.isEmpty()) current = metaSeries;
        final EditText box = new EditText(this);
        box.setText(current);
        box.setHint(LangStore.tr(this, "reader_series_hint"));
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "reader_series"))
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = box.getText().toString().trim();
                    if (!name.isEmpty()) {
                        SeriesBook.group(MemoryHook.root(this), key,
                                name, "hand");
                        metaSeries = name;
                        Toast.makeText(this, LangStore.tr(this,
                                "reader_series_set"), Toast.LENGTH_LONG)
                                .show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ helpers

    private LinearLayout.LayoutParams quarter() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams half() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
    }

    private LinearLayout.LayoutParams third() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fifth() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    @Override
    protected void onPause() {
        savePos();
        super.onPause();
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
