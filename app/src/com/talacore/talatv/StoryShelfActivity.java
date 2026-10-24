package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Story shelf (v0.14.0; the FOUNTAIN wired in v0.16.0) —
 * three shelves in one room:
 *
 *   THE CURATED SHELF — the hand-picked tales of the curation
 *   file, exactly as before.
 *
 *   THE STORY FOUNTAIN — whole children's BOOKS resolved live
 *   from the BookSource fountain: membership in a named
 *   children's collection at the source (the Archive's
 *   International Children's Digital Library, "iacl") + open
 *   access verified from the item's own file list + a readable
 *   sibling that actually resolves. Curation moved to the
 *   collection gate; abundance comes from the fountain. A tile
 *   renders ONLY for a book that resolved — printed counts are
 *   verified stories only (Display Honesty).
 *
 *   MADE STORIES — cartridges a reader made from a book with
 *   Make a Story, labeled with their provenance.
 *
 * If nothing can resolve (no network, an empty hook), the room
 * says so in plain words — "Story shelf needs the network to
 * fill" — never a bare, lying room.
 */
public class StoryShelfActivity extends Activity {

    private LinearLayout shelfBox, fountainBox, madeBox;
    private TextView statusView;
    private volatile boolean destroyed = false;
    private volatile boolean netFailed = false;
    private volatile int shownTotal = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Sanctuary.ground());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_shelf"));
        title.setTextSize(30);
        Sanctuary.goldText(title);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText(LangStore.tr(this, "story_loading"));
        statusView.setTextColor(Sanctuary.BODY);
        statusView.setTextSize(18);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(14);
        root.addView(statusView, sl);

        shelfBox = new LinearLayout(this);
        shelfBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(shelfBox);

        root.addView(sectionHeader(LangStore.tr(this, "story_fountain")));
        TextView fNote = new TextView(this);
        fNote.setText(LangStore.tr(this, "story_fountain_note"));
        fNote.setTextColor(Sanctuary.MUTED);
        fNote.setTextSize(15);
        root.addView(fNote);
        fountainBox = new LinearLayout(this);
        fountainBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(fountainBox);

        root.addView(sectionHeader(LangStore.tr(this, "story_made")));
        madeBox = new LinearLayout(this);
        madeBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(madeBox);
        setContentView(scroll);

        showMade();
        List<StoryData.Row> curated = StoryFiles.curation(this);
        List<StoryData.Row> cachedFountain = StoryFiles.fountain(this);
        new Thread(() -> {
            verifyRows(curated, shelfBox);
            verifyRows(cachedFountain, fountainBox);
            fillFountain();
            runOnUiThread(() -> {
                if (destroyed) return;
                if (shownTotal == 0) {
                    statusView.setVisibility(View.VISIBLE);
                    statusView.setText(LangStore.tr(this,
                            netFailed ? "story_shelf_network"
                                    : "story_shelf_empty"));
                }
            });
        }, "story-shelf-verify").start();
    }

    private TextView sectionHeader(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(Sanctuary.GOLD_TEXT);
        h.setTextSize(23);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        h.setLayoutParams(lp);
        return h;
    }

    /** Verify, then surface: tiles only for resolved books. */
    private void verifyRows(List<StoryData.Row> rows,
                            LinearLayout box) {
        List<VoiceLangs.Row> voiceRows = VoiceLangs.load(this);
        for (StoryData.Row row : rows) {
            if (destroyed) return;
            boolean resolved;
            if (StoryFiles.bookHeld(this, row.id)) {
                resolved = true;
            } else {
                try {
                    StoryFetch.fetchText(this, row);
                    resolved = true;
                } catch (Exception e) {
                    resolved = false;   // no tile — the law
                }
            }
            if (!resolved) continue;
            shownTotal++;
            VoiceLangs.Row vr = VoiceLangs.byIso1(voiceRows, row.lang);
            final String tongue = vr != null ? vr.tvName : row.lang;
            runOnUiThread(() -> {
                if (destroyed) return;
                addTile(row, tongue, box);
                statusView.setVisibility(View.GONE);
            });
        }
    }

    /**
     * The fountain fill: one page of the children's collection,
     * each candidate settled by the BookSource gate (collection
     * membership + OPEN + resolvable sibling) before it may
     * stand as a story. Verified rows are cached to the fountain
     * file, so the shelf deepens across opens and stands offline
     * for books already held.
     */
    private void fillFountain() {
        List<StoryData.Row> known = StoryFiles.fountain(this);
        java.util.Set<String> knownIds = new java.util.HashSet<>();
        for (StoryData.Row r : known) knownIds.add(r.id);
        List<BookSource.Hit> hits;
        try {
            hits = BookSource.parseIaSearch(
                    Net.getString(BookSource.iaChildrenUrl(30, 1)));
        } catch (Exception e) {
            netFailed = true;
            return;
        }
        int added = 0;
        List<StoryData.Row> grown = new ArrayList<>(known);
        for (BookSource.Hit h : hits) {
            if (destroyed || added >= 12) break;
            String rowId = "fc_" + h.itemId;
            if (knownIds.contains(rowId)) continue;
            try {
                BookSource.applyIaMetadata(h,
                        Net.getString(IaClient.metadataUrl(h.itemId)));
            } catch (Exception e) {
                continue;   // unresolved — not eligible, no tile
            }
            if (!BookSource.storyEligible(h)) continue;
            StoryData.Row row = new StoryData.Row();
            row.id = rowId;
            row.title = h.title;
            row.author = h.author;
            row.lang = iso1Of(h.language);
            row.taleGroup = "";
            row.iaId = h.itemId;
            row.source = BookSource.IA;
            row.illustrated = h.pageImages;
            try {
                StoryFetch.fetchText(this, row);
            } catch (Exception e) {
                continue;   // the sibling did not resolve — no tile
            }
            grown.add(row);
            knownIds.add(rowId);
            added++;
            shownTotal++;
            VoiceLangs.Row vr = VoiceLangs.byIso1(
                    VoiceLangs.load(this), row.lang);
            final String tongue = vr != null ? vr.tvName : row.lang;
            runOnUiThread(() -> {
                if (destroyed) return;
                addTile(row, tongue, fountainBox);
                statusView.setVisibility(View.GONE);
            });
            StoryFiles.saveFountain(this, grown);
        }
        if (added > 0) StoryFiles.saveFountain(this, grown);
    }

    /** MARC code (the Archive's tongue) → ISO-1 (the shelf's). */
    private static String iso1Of(String marc) {
        if (marc == null) return "en";
        switch (marc.trim().toLowerCase(java.util.Locale.US)) {
            case "eng": return "en";
            case "spa": return "es";
            case "fre": case "fra": return "fr";
            case "ger": case "deu": return "de";
            case "ita": return "it";
            case "por": return "pt";
            case "rus": return "ru";
            case "jpn": return "ja";
            case "chi": case "zho": return "zh";
            case "kor": return "ko";
            case "ara": return "ar";
            case "hin": return "hi";
            case "per": case "fas": return "fa";
            default: return "en";
        }
    }

    private void addTile(StoryData.Row row, String tongue,
                         LinearLayout box) {
        Button b = new Button(this);
        b.setText(row.title + "\n" + row.author + " · " + tongue + "  ●");
        b.setTextSize(21);
        b.setAllCaps(false);
        b.setBackground(Sanctuary.glassButton(this, false));
        b.setTextColor(Sanctuary.INK);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(16), 0, dp(16), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(92));
        lp.topMargin = dp(12);
        b.setOnClickListener(v -> {
            Intent i = new Intent(this, StoryTellActivity.class);
            i.putExtra(StoryTellActivity.X_BOOK, row.id);
            startActivity(i);
        });
        b.setOnLongClickListener(v -> {
            // Read the story book on screen, in the reading room.
            try {
                java.io.File f = StoryFiles.bookTextFile(this, row.id);
                String buri = f.getAbsolutePath();
                BookStore.addFile(this, buri);
                BookStore.Book book = BookStore.byId(this,
                        BookStore.idFor(buri));
                if (book == null) return true;
                Intent i = new Intent(this, ReaderRoomActivity.class);
                i.putExtra(ReaderRoomActivity.X_BID, book.id);
                i.putExtra(ReaderRoomActivity.X_KEY,
                        ReaderBook.keyFor("story", row.id));
                i.putExtra(ReaderRoomActivity.X_TITLE, row.title);
                i.putExtra(ReaderRoomActivity.X_AUTHOR, row.author);
                i.putExtra(ReaderRoomActivity.X_SOURCE, row.source);
                i.putExtra(ReaderRoomActivity.X_LANG, row.lang);
                startActivity(i);
            } catch (Exception ignored) {}
            return true;
        });
        box.addView(b, lp);
    }

    /** Made stories: cartridges made from books, provenance first. */
    private void showMade() {
        for (StoryData.Cartridge c : StoryFiles.cartridges(this)) {
            if (c.bookId == null || c.bookId.isEmpty()) continue;
            shownTotal++;
            Button b = new Button(this);
            String label = c.title + "\n"
                    + LangStore.tr(this, "story_made_from") + " "
                    + c.fromBook + " (" + c.fromSource + ")";
            b.setText(label);
            b.setTextSize(19);
            b.setAllCaps(false);
            b.setBackground(Sanctuary.glassButton(this, false));
            b.setTextColor(Sanctuary.INK);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(92));
            lp.topMargin = dp(12);
            final String cid = c.id;
            b.setOnClickListener(v -> {
                Intent i = new Intent(this, StoryPlayActivity.class);
                i.putExtra(StoryPlayActivity.X_CARTRIDGE, cid);
                startActivity(i);
            });
            madeBox.addView(b, lp);
        }
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
