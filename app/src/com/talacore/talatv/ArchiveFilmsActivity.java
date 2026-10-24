package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARCHIVE FILMS (v0.18.0) — the two shelves the source census
 * bound, standing behind their VERIFICATION RECEIPTS:
 *
 *   · Wikimedia Commons — open films from the world's free
 *     media library (census Round 2, RESOLVES: keyless
 *     MediaWiki API → direct files on upload.wikimedia.org,
 *     verified WebM bytes; the per-file rights label rides
 *     the API, so it rides the tile).
 *   · Japanese Animated Film Classics — the National Film
 *     Archive of Japan's 1917–1945 heritage, streamed by the
 *     archive itself (census Round 2, RESOLVES: catalogue →
 *     play page → the NII viewer → HLS proven to the byte).
 *     The archive asks that its films not be copied; Sandy
 *     binds the STREAM through the archive's own door and
 *     keeps nothing.
 *
 * The receipt gate stands in front of each shelf
 * ({@link ReceiptStore#backsPlayable}): a source whose
 * receipt lacks the STREAM and BYTES tiers — or whose door
 * the world broke after binding (WORLD_CHANGED) — is shown
 * as unverified and offers no tiles. A receipt, not a
 * catalogue's say-so, is what lets a shelf promise a film.
 *
 * A tap never plays on faith: the film's doors are proven
 * through Hydra's own ladder (Commons: the rendition ladder
 * — another lawful door when one doesn't prove, pillar 2;
 * NFAJ: the auto door, then the fixed-rendition doors), and
 * only a door proven to the player's own tier opens the
 * player. When no door proves, the status line says why in
 * plain words — the refusing door named, never a bare error.
 * Commons captions ride along where the file offers them.
 */
public class ArchiveFilmsActivity extends Activity {

    private static final int MODE_COMMONS = 0;
    private static final int MODE_NFAJ = 1;

    private Button commonsBtn, nfajBtn, searchBtn;
    private EditText queryBox;
    private TextView statusView;
    private LinearLayout resultsBox;
    private int mode = MODE_COMMONS;
    private volatile boolean destroyed = false;
    private volatile int gen = 0;

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
        title.setText(LangStore.tr(this, "door_films"));
        title.setTextColor(Sanctuary.GOLD_TEXT);
        title.setTextSize(30);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        commonsBtn = new Button(this);
        nfajBtn = new Button(this);
        commonsBtn.setText("Wikimedia Commons");
        nfajBtn.setText("日本アニメーション映画古典");
        commonsBtn.setAllCaps(false);
        nfajBtn.setAllCaps(false);
        commonsBtn.setOnClickListener(v -> { mode = MODE_COMMONS; paintMode(); openShelf(); });
        nfajBtn.setOnClickListener(v -> { mode = MODE_NFAJ; paintMode(); openShelf(); });
        modeRow.addView(commonsBtn, half());
        modeRow.addView(nfajBtn, half());
        root.addView(modeRow);

        queryBox = new EditText(this);
        queryBox.setHint(LangStore.tr(this, "films_search_hint"));
        queryBox.setTextColor(Sanctuary.INK);
        queryBox.setHintTextColor(Sanctuary.SUB);
        queryBox.setTextSize(19);
        root.addView(queryBox, margins());

        searchBtn = new Button(this);
        searchBtn.setText(LangStore.tr(this, "books_search"));
        searchBtn.setTextSize(19);
        searchBtn.setOnClickListener(v -> searchCommons());
        root.addView(searchBtn, margins());

        statusView = new TextView(this);
        statusView.setTextColor(Sanctuary.BODY);
        statusView.setTextSize(16);
        root.addView(statusView, margins());

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsBox);
        setContentView(scroll);
        paintMode();
        openShelf();
    }

    private void paintMode() {
        commonsBtn.setEnabled(mode != MODE_COMMONS);
        nfajBtn.setEnabled(mode != MODE_NFAJ);
        int vis = mode == MODE_COMMONS ? View.VISIBLE : View.GONE;
        queryBox.setVisibility(vis);
        searchBtn.setVisibility(vis);
    }

    // ---------------------------------------------------- shelves

    /** Opens the chosen shelf — behind its receipt, or not at all. */
    private void openShelf() {
        final String sourceId = mode == MODE_COMMONS
                ? CommonsClient.SOURCE_ID : NfajClient.SOURCE_ID;
        resultsBox.removeAllViews();
        if (!ReceiptStore.backsPlayable(this, sourceId)) {
            // The receipt gate: this shelf may be SEEN, but it
            // may not promise a film — its proof never reached
            // the player's tiers, or the world broke its door.
            statusView.setText(LangStore.tr(this, "films_unverified"));
            return;
        }
        if (mode == MODE_COMMONS) loadCommons(null);
        else loadNfaj();
    }

    private void searchCommons() {
        String q = queryBox.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!ReceiptStore.backsPlayable(this, CommonsClient.SOURCE_ID)) {
            statusView.setText(LangStore.tr(this, "films_unverified"));
            return;
        }
        loadCommons(q);
    }

    private void loadCommons(final String query) {
        final int g = ++gen;
        resultsBox.removeAllViews();
        statusView.setText(LangStore.tr(this, "films_loading"));
        addShelfHeader(LangStore.tr(this, "films_commons"));
        new Thread(() -> {
            try {
                String json = Net.getString(query == null
                        ? CommonsClient.featuredUrl()
                        : CommonsClient.searchUrl(query));
                final List<CommonsClient.Item> items =
                        CommonsClient.parseItems(json);
                runOnUiThread(() -> {
                    if (destroyed || g != gen) return;
                    statusView.setText(items.isEmpty()
                            ? LangStore.tr(this, "films_none") : "");
                    for (CommonsClient.Item it : items) {
                        resultsBox.addView(commonsTile(it));
                    }
                });
            } catch (Exception e) {
                final String words = FailureWords.englishFor(
                        FailureWords.kindForDetail(String.valueOf(e)), 0);
                runOnUiThread(() -> {
                    if (destroyed || g != gen) return;
                    statusView.setText(words);
                });
            }
        }, "commons-shelf").start();
    }

    private void loadNfaj() {
        final int g = ++gen;
        resultsBox.removeAllViews();
        statusView.setText(LangStore.tr(this, "films_loading"));
        addShelfHeader(LangStore.tr(this, "films_nfaj"));
        TextView note = new TextView(this);
        note.setText(LangStore.tr(this, "films_nfaj_note"));
        note.setTextColor(Sanctuary.MUTED);
        note.setTextSize(14);
        resultsBox.addView(note, margins());
        new Thread(() -> {
            try {
                final List<NfajClient.Work> works = new ArrayList<>();
                // The catalogue walks its own list pages; three
                // pages or an empty page ends the walk, whichever
                // comes first — a shelf, not the whole archive.
                for (int page = 1; page <= 3; page++) {
                    List<NfajClient.Work> got = NfajClient.parseList(
                            Net.getString(NfajClient.listUrl(page)));
                    if (got.isEmpty()) break;
                    works.addAll(got);
                }
                runOnUiThread(() -> {
                    if (destroyed || g != gen) return;
                    statusView.setText(works.isEmpty()
                            ? LangStore.tr(this, "films_none") : "");
                    for (NfajClient.Work w : works) {
                        resultsBox.addView(nfajTile(w));
                    }
                });
            } catch (Exception e) {
                final String words = FailureWords.englishFor(
                        FailureWords.kindForDetail(String.valueOf(e)), 0);
                runOnUiThread(() -> {
                    if (destroyed || g != gen) return;
                    statusView.setText(words);
                });
            }
        }, "nfaj-shelf").start();
    }

    private void addShelfHeader(String text) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextColor(Sanctuary.GOLD_TEXT);
        h.setTextSize(18);
        resultsBox.addView(h, margins());
    }

    // ------------------------------------------------------- tiles

    private View commonsTile(final CommonsClient.Item it) {
        LinearLayout row = tileRow(it.thumbUrl);
        LinearLayout col = tileCol(row);
        col.addView(tileTitle(it.title));
        String meta = "Wikimedia Commons";
        if (it.height > 0) meta += " · " + it.height + "p";
        String cont = CommonsClient.containerOf(it.mime);
        if (!cont.isEmpty()) meta += " · " + cont;
        col.addView(tileLine(meta, Sanctuary.MUTED, 14));
        // The rights label rides the tile — verbatim from the API.
        if (!it.license.isEmpty()) {
            col.addView(tileLine(it.license, Sanctuary.TEAL, 14));
        }
        row.setOnClickListener(v -> playCommons(it));
        return row;
    }

    private View nfajTile(final NfajClient.Work w) {
        LinearLayout row = tileRow(w.thumbUrl);
        LinearLayout col = tileCol(row);
        String t = w.titleEn.isEmpty() ? w.titleJa : w.titleEn;
        col.addView(tileTitle(t));
        String meta = "National Film Archive of Japan";
        if (!w.year.isEmpty()) meta += " · " + w.year;
        col.addView(tileLine(meta, Sanctuary.MUTED, 14));
        if (!w.titleJa.isEmpty() && !w.titleJa.equals(t)) {
            col.addView(tileLine(w.titleJa, Sanctuary.SUB, 13));
        }
        row.setOnClickListener(v -> playNfaj(w));
        return row;
    }

    private LinearLayout tileRow(String thumbUrl) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(new ColorDrawable(Sanctuary.PANEL));
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        ImageView img = new ImageView(this);
        img.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(100)));
        if (thumbUrl != null && !thumbUrl.isEmpty()) {
            ImageCache.load(this, thumbUrl, img);
        }
        row.addView(img);
        return row;
    }

    private LinearLayout tileCol(LinearLayout row) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(12);
        row.addView(col, lp);
        return col;
    }

    private TextView tileTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Sanctuary.INK);
        t.setTextSize(18);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        return t;
    }

    private TextView tileLine(String text, int color, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(size);
        return t;
    }

    // ------------------------------------------------------- playing

    /**
     * Prove a Commons film through its rendition ladder —
     * another lawful door when one doesn't prove (pillar 2) —
     * then play the door that proved, its codec label on the
     * playing name, its captions riding where offered.
     */
    private void playCommons(final CommonsClient.Item it) {
        statusView.setText(LangStore.tr(this, "books_checking"));
        new Thread(() -> {
            String failWords = "";
            for (CommonsClient.Rendition r
                    : CommonsClient.renditionLadder(it)) {
                HydraResolver.Outcome o = HydraResolver.proveExhaustive(
                        this, r.url, Net.UA, null);
                if (o.result != null) {
                    failWords = o.result.detail == null
                            ? "" : o.result.detail;
                }
                if (o.result != null
                        && (o.result.tier == LinkHealth.TIER_PLAYS
                                || o.result.slow)) {
                    final String playUrl = o.url;
                    final String playName = it.title
                            + (r.label().isEmpty() ? ""
                                    : " — " + r.label());
                    String[] sub = commonsCaption(it);
                    final String subUrl = sub == null ? null : sub[0];
                    final String subLang = sub == null ? null : sub[1];
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        statusView.setText("");
                        Intent i = new Intent(this, PlayerActivity.class);
                        i.putExtra("url", playUrl);
                        i.putExtra("name", playName);
                        if (subUrl != null) {
                            i.putExtra("subUrl", subUrl);
                            i.putExtra("subMime", "application/x-subrip");
                            i.putExtra("subLang", subLang);
                        }
                        startActivity(i);
                    });
                    return;
                }
            }
            final String words = failWords;
            runOnUiThread(() -> {
                if (destroyed) return;
                statusView.setText(words.isEmpty()
                        ? LangStore.tr(this, "films_none") : words);
            });
        }, "commons-play").start();
    }

    /** The caption door for a Commons film, or null: the
     *  viewer's language first, else English, else the first
     *  the file offers (CommonsClient.pickCaption). */
    private String[] commonsCaption(CommonsClient.Item it) {
        try {
            String fileName = CommonsClient.fileNameOf(it.pageTitle);
            String json = Net.getString(
                    CommonsClient.captionIndexUrl(fileName));
            List<CommonsClient.Caption> caps =
                    CommonsClient.parseCaptions(json, fileName);
            String lang = ChannelStore.getUiLanguage(this);
            CommonsClient.Caption c = CommonsClient.pickCaption(
                    caps, lang == null || lang.isEmpty() ? "en" : lang);
            if (c == null) return null;
            return new String[]{c.rawUrl, c.lang};
        } catch (Exception e) {
            return null;   // captions are an offer, never a gate
        }
    }

    /**
     * Prove an NFAJ work through its stream doors — the auto
     * door the census proved, then the fixed-rendition doors
     * as alternate dialects — then play the door that proved.
     */
    private void playNfaj(final NfajClient.Work w) {
        statusView.setText(LangStore.tr(this, "books_checking"));
        new Thread(() -> {
            String failWords = "";
            for (String door : NfajClient.streamDoors(w.id)) {
                HydraResolver.Outcome o = HydraResolver.proveExhaustive(
                        this, door, Net.UA, null);
                if (o.result != null) {
                    failWords = o.result.detail == null
                            ? "" : o.result.detail;
                }
                if (o.result != null
                        && (o.result.tier == LinkHealth.TIER_PLAYS
                                || o.result.slow)) {
                    final String playUrl = o.url;
                    final String playName = (w.titleEn.isEmpty()
                            ? w.titleJa : w.titleEn)
                            + (w.year.isEmpty() ? "" : " (" + w.year + ")");
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        statusView.setText("");
                        Intent i = new Intent(this, PlayerActivity.class);
                        i.putExtra("url", playUrl);
                        i.putExtra("name", playName);
                        startActivity(i);
                    });
                    return;
                }
            }
            final String words = failWords;
            runOnUiThread(() -> {
                if (destroyed) return;
                statusView.setText(words.isEmpty()
                        ? LangStore.tr(this, "films_none") : words);
            });
        }, "nfaj-play").start();
    }

    // ------------------------------------------------------ helpers

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
