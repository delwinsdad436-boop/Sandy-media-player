package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The reading screen (v0.8.0) — built for the recliner: one huge
 * play/pause, back/forward a part, speed, a voice pick in plain
 * words, and her place stated in words ("Pick up where you left
 * off — Chapter 3, part 12"). Zero configuration: opening a book
 * from the shelf starts it at her place; this screen is the remote
 * for the ReaderService voice, which keeps reading with the
 * screen off.
 *
 * Translated reading appears only when it's real: the book is
 * Japanese (kana-detected) AND the ja-en engine pack is installed.
 * Otherwise the original is read as written, and if she asks why
 * there's no English door, the state line says why in one line.
 */
public final class ReaderActivity extends Activity {

    public static final String X_BOOK = "book";
    public static final String X_AUTOSTART = "autostart";

    private String bookId = "";
    private BookStore.Book book;
    private TextView titleText, placeText, stateText, speedText;
    private Button playBtn, voiceBtn, modeBtn;
    private LinearLayout resumeBox;
    private TextView resumeText;

    private volatile String bookLang = null; // detected on a side thread
    private List<Voice> voices = new ArrayList<>();
    private TextToSpeech voiceProbe;
    private final Runnable snapshotListener = () -> runOnUiThread(this::refresh);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        bookId = getIntent().getStringExtra(X_BOOK);
        if (bookId == null) bookId = "";
        book = BookStore.byId(this, bookId);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);

        titleText = new TextView(this);
        titleText.setTextSize(26);
        root.addView(titleText);

        placeText = new TextView(this);
        placeText.setTextSize(19);
        placeText.setPadding(0, 8, 0, 0);
        root.addView(placeText);

        stateText = new TextView(this);
        stateText.setTextSize(17);
        stateText.setPadding(0, 8, 0, 8);
        root.addView(stateText);

        // ---- resume offer, in words
        resumeBox = new LinearLayout(this);
        resumeBox.setOrientation(LinearLayout.VERTICAL);
        resumeText = new TextView(this);
        resumeText.setTextSize(20);
        resumeBox.addView(resumeText);
        Button startOver = new Button(this);
        startOver.setText(LangStore.tr(this, "reader_start_over"));
        startOver.setOnClickListener(v -> {
            ReaderStore.resetPlace(this, bookId);
            ReaderStore.markFinished(this, bookId, false);
            startReading(0, storedMode());
        });
        resumeBox.addView(startOver);
        root.addView(resumeBox);

        // ---- the big button
        playBtn = new Button(this);
        playBtn.setTextSize(30);
        LinearLayout.LayoutParams big = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        playBtn.setLayoutParams(big);
        playBtn.setOnClickListener(v -> onPlayPause());
        root.addView(playBtn);

        // ---- back / forward a part
        LinearLayout stepRow = new LinearLayout(this);
        stepRow.setOrientation(LinearLayout.HORIZONTAL);
        Button back = new Button(this);
        back.setText("⏮  " + LangStore.tr(this, "reader_back"));
        back.setTextSize(19);
        back.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        back.setOnClickListener(v ->
                ReaderService.send(this, ReaderService.cmd(this, ReaderService.A_PREV)));
        stepRow.addView(back);
        Button fwd = new Button(this);
        fwd.setText(LangStore.tr(this, "reader_forward") + "  ⏭");
        fwd.setTextSize(19);
        fwd.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        fwd.setOnClickListener(v ->
                ReaderService.send(this, ReaderService.cmd(this, ReaderService.A_NEXT)));
        stepRow.addView(fwd);
        root.addView(stepRow);

        // ---- speed
        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView speedLabel = new TextView(this);
        speedLabel.setText(LangStore.tr(this, "reader_speed"));
        speedLabel.setTextSize(19);
        speedRow.addView(speedLabel);
        Button slower = new Button(this);
        slower.setText("−");
        slower.setTextSize(22);
        slower.setOnClickListener(v -> stepRate(-0.25f));
        speedRow.addView(slower);
        speedText = new TextView(this);
        speedText.setTextSize(19);
        speedText.setPadding(12, 0, 12, 0);
        speedRow.addView(speedText);
        Button faster = new Button(this);
        faster.setText("+");
        faster.setTextSize(22);
        faster.setOnClickListener(v -> stepRate(0.25f));
        speedRow.addView(faster);
        root.addView(speedRow);

        // ---- voice pick (plain labels)
        voiceBtn = new Button(this);
        voiceBtn.setTextSize(18);
        voiceBtn.setOnClickListener(v -> cycleVoice());
        root.addView(voiceBtn);

        // ---- read it on screen instead (v0.16.0, the Reader room)
        if (book != null) {
            Button screenBtn = new Button(this);
            screenBtn.setText(LangStore.tr(this, "story_read_screen"));
            screenBtn.setTextSize(18);
            screenBtn.setOnClickListener(v -> {
                Intent i = new Intent(this, ReaderRoomActivity.class);
                i.putExtra(ReaderRoomActivity.X_BID, bookId);
                i.putExtra(ReaderRoomActivity.X_KEY,
                        ReaderBook.keyFor("local", bookId));
                i.putExtra(ReaderRoomActivity.X_TITLE, book.title);
                i.putExtra(ReaderRoomActivity.X_SOURCE, "local");
                startActivity(i);
            });
            root.addView(screenBtn);
        }

        // ---- translated reading (only when real)
        modeBtn = new Button(this);
        modeBtn.setTextSize(18);
        modeBtn.setOnClickListener(v -> toggleMode());
        root.addView(modeBtn);

        setContentView(root);

        titleText.setText(book == null ? "" : book.title);
        showRate();
        probeVoices();
        detectLang();

        if (getIntent().getBooleanExtra(X_AUTOSTART, false) && book != null) {
            ReaderStore.Bookmark bm = ReaderStore.bookmark(this, bookId);
            int from = bm.finished ? 0 : bm.chunk;
            if (bm.finished) ReaderStore.markFinished(this, bookId, false);
            startReading(from, bm.mode);
        }
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ReaderService.addListener(snapshotListener);
        refresh();
    }

    @Override
    protected void onPause() {
        ReaderService.removeListener(snapshotListener);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (voiceProbe != null) {
            try { voiceProbe.shutdown(); } catch (Exception ignored) {}
            voiceProbe = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------ actions

    private String storedMode() {
        return ReaderStore.bookmark(this, bookId).mode;
    }

    private void startReading(int fromChunk, String mode) {
        ReaderService.send(this, ReaderService.playIntent(this, bookId, fromChunk, mode));
    }

    private void onPlayPause() {
        ReaderService.Snapshot s = ReaderService.snapshot();
        boolean mine = bookId.equals(s.bookId);
        if (mine && s.state == ReadingSession.State.SPEAKING) {
            ReaderService.send(this, ReaderService.cmd(this, ReaderService.A_PAUSE));
        } else if (mine && (s.state == ReadingSession.State.PAUSED
                || s.state == ReadingSession.State.WAITING)) {
            ReaderService.send(this, ReaderService.cmd(this, ReaderService.A_RESUME));
        } else {
            ReaderStore.Bookmark bm = ReaderStore.bookmark(this, bookId);
            startReading(bm.finished ? 0 : bm.chunk, bm.mode);
        }
    }

    private void stepRate(float d) {
        float r = Math.max(0.5f, Math.min(2.0f, ReaderStore.rate(this) + d));
        ReaderStore.setRate(this, r);
        Intent i = ReaderService.cmd(this, ReaderService.A_RATE);
        i.putExtra(ReaderService.X_RATE, r);
        ReaderService.send(this, i);
        showRate();
    }

    private void showRate() {
        float r = ReaderStore.rate(this);
        speedText.setText((r == Math.floor(r) ? String.valueOf((int) r)
                : String.valueOf(r)) + "×");
    }

    private void toggleMode() {
        String cur = storedMode();
        String next = ReaderStore.MODE_TRANSLATED.equals(cur)
                ? ReaderStore.MODE_ORIGINAL : ReaderStore.MODE_TRANSLATED;
        ReaderStore.Bookmark bm = ReaderStore.bookmark(this, bookId);
        ReaderService.Snapshot s = ReaderService.snapshot();
        int at = bookId.equals(s.bookId) && s.totalChunks > 0 ? s.chunk : bm.chunk;
        // Persist the mode with her place, then (re)start there in it.
        ReaderStore.save(this, bookId, at, bm.charOffset, bm.totalChunks,
                bm.chapter, next);
        startReading(at, next);
    }

    // ------------------------------------------------ voices

    /** Enumerate the phone's voices with a short-lived probe TTS. */
    private void probeVoices() {
        voiceBtn.setText(LangStore.tr(this, "reader_voice") + ": …");
        voiceProbe = new TextToSpeech(this, status -> runOnUiThread(() -> {
            if (status == TextToSpeech.SUCCESS && voiceProbe != null) {
                try {
                    List<Voice> vs = new ArrayList<>(voiceProbe.getVoices());
                    vs.sort((a, b) -> plainVoiceLabel(a).compareTo(plainVoiceLabel(b)));
                    voices = vs;
                } catch (Exception ignored) {}
            }
            showVoice();
        }));
    }

    /** Plain-words label: the language, and which voice when several share it. */
    static String plainVoiceLabel(Voice v) {
        Locale loc = v.getLocale();
        String base = loc == null ? "Voice"
                : loc.getDisplayName(Locale.getDefault());
        return base + " · " + v.getName();
    }

    private void showVoice() {
        String want = ReaderStore.voiceName(this);
        String label = null;
        for (Voice v : voices) {
            if (v.getName().equals(want)) { label = plainVoiceLabel(v); break; }
        }
        voiceBtn.setText(LangStore.tr(this, "reader_voice") + ": "
                + (label == null ? "Phone's own voice" : label));
        voiceBtn.setEnabled(!voices.isEmpty());
    }

    private void cycleVoice() {
        if (voices.isEmpty()) return;
        String want = ReaderStore.voiceName(this);
        int idx = -1;
        for (int i = 0; i < voices.size(); i++) {
            if (voices.get(i).getName().equals(want)) { idx = i; break; }
        }
        Voice next = voices.get((idx + 1) % voices.size());
        ReaderStore.setVoiceName(this, next.getName());
        Intent i = ReaderService.cmd(this, ReaderService.A_VOICE);
        i.putExtra(ReaderService.X_VOICE, next.getName());
        ReaderService.send(this, i);
        showVoice();
    }

    // ------------------------------------------------ book facts

    /** Detect the book's language off-thread (for the translate door). */
    private void detectLang() {
        if (book == null) return;
        new Thread(() -> {
            try {
                byte[] bytes = BookStore.readAll(this, book);
                if (bytes == null) return;
                BookText.Result r = BookText.extract(bytes, book.title);
                if (r.ok) {
                    bookLang = BookLang.detect(r.text);
                    runOnUiThread(this::refresh);
                }
            } catch (Throwable ignored) {}
        }).start();
    }

    // ------------------------------------------------ rendering

    private void refresh() {
        if (book == null) {
            titleText.setText("");
            playBtn.setText(LangStore.tr(this, "reader_play"));
            return;
        }
        ReaderService.Snapshot s = ReaderService.snapshot();
        boolean mine = bookId.equals(s.bookId);
        ReaderStore.Bookmark bm = ReaderStore.bookmark(this, bookId);

        if (mine && s.totalChunks > 0) {
            String place = "";
            if (!s.chapter.isEmpty()) place = s.chapter + " · ";
            place += "Part " + (s.chunk + 1) + " of " + s.totalChunks;
            placeText.setText(place);
            stateText.setText(s.spoken.isEmpty() ? stateWord(s.state) : s.spoken);
            resumeBox.setVisibility(View.GONE);
        } else {
            if (bm.chunk > 0 && !bm.finished && bm.totalChunks > 0) {
                String offer = LangStore.tr(this, "reader_pick_up");
                if (!bm.chapter.isEmpty()) offer += " — " + bm.chapter;
                offer += " · Part " + (bm.chunk + 1) + " of " + bm.totalChunks;
                resumeText.setText(offer);
                resumeBox.setVisibility(View.VISIBLE);
                placeText.setText("");
            } else {
                resumeBox.setVisibility(View.GONE);
                placeText.setText(bm.finished
                        ? LangStore.tr(this, "reader_finished") : "");
            }
            stateText.setText(mine ? s.spoken : "");
        }

        playBtn.setText(mine && s.state == ReadingSession.State.SPEAKING
                ? "⏸  " + LangStore.tr(this, "reader_pause")
                : "▶  " + LangStore.tr(this, "reader_play"));

        // Translated door: shown only when it can be real — a
        // Japanese book; the pack decides whether it's a button or
        // an honest line (the button explains itself when tapped
        // without the pack? No — no dead doors: without the pack it
        // stays a line of text, not a button).
        if (BookLang.JA.equals(bookLang)) {
            if (BookTranslator.available(this)) {
                modeBtn.setVisibility(View.VISIBLE);
                boolean tran = ReaderStore.MODE_TRANSLATED.equals(
                        mine ? s.mode : bm.mode);
                modeBtn.setText(tran
                        ? "Reading in English — tap for the original Japanese"
                        : LangStore.tr(this, "reader_read_in_english"));
                modeBtn.setEnabled(true);
            } else {
                modeBtn.setVisibility(View.VISIBLE);
                modeBtn.setText("Reading in English needs the translation "
                        + "engine pack (Settings adds it).");
                modeBtn.setEnabled(false);
            }
        } else {
            modeBtn.setVisibility(View.GONE);
        }
        showVoice();
    }

    private String stateWord(ReadingSession.State st) {
        switch (st) {
            case SPEAKING: return "Reading…";
            case PAUSED: return "Paused — your place is kept.";
            case WAITING: return "Getting the words ready…";
            case FINISHED: return LangStore.tr(this, "reader_finished");
            default: return "";
        }
    }
}
