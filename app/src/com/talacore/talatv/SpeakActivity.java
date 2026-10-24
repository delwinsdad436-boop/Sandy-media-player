package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SPEAK FOR ME (v0.15.0) — the door for a person who cannot
 * speak. Type, tap SPEAK, and Sandy says it aloud in the room,
 * through the voice ladder (installed pack → phone voice → a
 * plain refusal shown BEFORE anything is promised — never a
 * silent wrong voice). The words stay on screen as the receipt
 * while and after she speaks.
 *
 * Two roads in: the typed box, and a DOCUMENT through the
 * universal BookText intake (PDF/EPUB/TXT/HTML/DOCX/ODT/RTF) —
 * nobody retypes what already exists as a file. Scanned and
 * unsupported files are named honestly (book_scanned /
 * book_unsupported), never faked.
 *
 * Quick phrases are kept as a FILE on the memory hook
 * (speak_phrases.tsv) — file in, file out.
 */
public final class SpeakActivity extends Activity {

    private static final int REQ_DOC = 71;
    private static final String PREFS = "sandy_doors";
    private static final String PHRASES_FILE = "speak_phrases.tsv";

    private VoiceSpeaker speaker;
    private final SpeakQueue queue = new SpeakQueue();
    private List<VoiceLangs.Row> langs = new ArrayList<>();
    private VoiceLangs.Row chosen;
    private List<String> phrases = new ArrayList<>();

    private EditText box;
    private TextView voiceLine;
    private TextView stateLine;
    private TextView receiptView;
    private Button langBtn;
    private LinearLayout phraseBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(LangStore.tr(this, "door_speak"));
        langs = VoiceLangs.load(this);
        String saved = prefs().getString("speak_lang", "");
        chosen = VoiceLangs.byIso1(langs, saved);
        if (chosen == null) {
            String ui = ChannelStore.getUiLanguage(this);
            chosen = VoiceLangs.byIso1(langs, ui == null || ui.isEmpty() ? "en" : ui);
        }
        if (chosen == null && !langs.isEmpty()) chosen = langs.get(0);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "door_speak"));
        title.setTextSize(24);
        root.addView(title);

        // language + its honest coverage state, before speaking
        TextView langLabel = new TextView(this);
        langLabel.setText(LangStore.tr(this, "speak_language"));
        langLabel.setTextSize(15);
        root.addView(langLabel, topMargin(dp(12)));
        langBtn = new Button(this);
        langBtn.setAllCaps(false);
        langBtn.setTextSize(19);
        langBtn.setOnClickListener(v -> pickLanguage());
        root.addView(langBtn);
        voiceLine = new TextView(this);
        voiceLine.setTextSize(15);
        root.addView(voiceLine, topMargin(dp(4)));

        box = new EditText(this);
        box.setHint(LangStore.tr(this, "speak_hint"));
        box.setTextSize(22);
        box.setMinLines(4);
        box.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        root.addView(box, topMargin(dp(12)));

        Button sayBtn = new Button(this);
        sayBtn.setText(LangStore.tr(this, "speak_say"));
        sayBtn.setTextSize(26);
        sayBtn.setMinHeight(dp(80));
        sayBtn.setOnClickListener(v -> speakBox());
        root.addView(sayBtn, topMargin(dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button clearBtn = smallButton(LangStore.tr(this, "speak_clear"), v -> {
            box.setText("");
            stateLine.setText("");
        });
        Button repeatBtn = smallButton(LangStore.tr(this, "speak_repeat"), v -> repeatLast());
        Button stopBtn = smallButton(LangStore.tr(this, "speak_stop"), v -> {
            speaker.stop();
            queue.clear();
            stateLine.setText("");
        });
        row.addView(clearBtn, weight());
        row.addView(repeatBtn, weight());
        row.addView(stopBtn, weight());
        root.addView(row, topMargin(dp(8)));

        Button docBtn = new Button(this);
        docBtn.setText(LangStore.tr(this, "speak_doc"));
        docBtn.setAllCaps(false);
        docBtn.setTextSize(18);
        docBtn.setMinHeight(dp(60));
        docBtn.setOnClickListener(v -> pickDocument());
        root.addView(docBtn, topMargin(dp(10)));

        stateLine = new TextView(this);
        stateLine.setTextSize(16);
        root.addView(stateLine, topMargin(dp(10)));

        TextView lastLabel = new TextView(this);
        lastLabel.setText(LangStore.tr(this, "speak_last"));
        lastLabel.setTextSize(15);
        root.addView(lastLabel, topMargin(dp(12)));
        receiptView = new TextView(this);
        receiptView.setTextSize(20);
        root.addView(receiptView);

        TextView phrasesLabel = new TextView(this);
        phrasesLabel.setText(LangStore.tr(this, "speak_phrases"));
        phrasesLabel.setTextSize(15);
        root.addView(phrasesLabel, topMargin(dp(14)));
        phraseBox = new LinearLayout(this);
        phraseBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(phraseBox);
        Button addPhraseBtn = smallButton(LangStore.tr(this, "speak_phrase_add"), v -> {
            String t = box.getText().toString().trim();
            if (t.isEmpty()) {
                stateLine.setText(LangStore.tr(this, "speak_type_first"));
                return;
            }
            phrases = QuickPhrases.add(phrases, t);
            savePhrases();
            showPhrases();
        });
        root.addView(addPhraseBtn, topMargin(dp(6)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        speaker = new VoiceSpeaker(this);
        speaker.setListener(new VoiceSpeaker.Listener() {
            @Override public void onItemStart(SpeakQueue.Item item) {
                stateLine.setText(LangStore.tr(SpeakActivity.this, "speak_speaking"));
                receiptView.setText(item.text);
            }
            @Override public void onQueueDone() {
                stateLine.setText("");
                SpeakQueue.Item last = queue.lastSpoken();
                if (last != null) receiptView.setText(last.text);
            }
            @Override public void onPackFailed(String packCode) {
                stateLine.setText(LangStore.tr(SpeakActivity.this,
                        "reader_voice_pack_fail"));
            }
            @Override public void onItemFailed(SpeakQueue.Item item) {
                stateLine.setText(voiceStateText());
            }
        });
        speaker.warmUp(this::refreshLanguageUi);
        loadPhrases();
        showPhrases();
        refreshLanguageUi();

        // Handed words from Listen ("take these words to the
        // Translator" is the other door; Speak also accepts a
        // hand-off extra when some other room sends text here).
        String handed = getIntent().getStringExtra("speak_text");
        if (handed != null && !handed.trim().isEmpty()) {
            box.setText(handed);
        }
    }

    @Override
    protected void onDestroy() {
        if (speaker != null) speaker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------ language

    private void refreshLanguageUi() {
        if (chosen == null) return;
        langBtn.setText(chosen.tvName);
        voiceLine.setText(voiceStateText());
    }

    /** The coverage line for the chosen language — the promise,
     *  made from the ladder BEFORE speaking. */
    private String voiceStateText() {
        if (chosen == null) return "";
        VoiceResolve.Result r = SpeakQueue.decide(chosen,
                VoicePackStore.installedCodes(this), speaker.systemIso1());
        switch (r.voice) {
            case PACK:
                return String.format(LangStore.tr(this, "dub_voice_pack"),
                        chosen.tvName);
            case SYSTEM:
                return String.format(LangStore.tr(this, "dub_voice_system"),
                        chosen.tvName);
            default:
                return String.format(LangStore.tr(this, "dub_voice_none"),
                        chosen.tvName);
        }
    }

    private void pickLanguage() {
        List<String> labels = new ArrayList<>();
        for (VoiceLangs.Row r : langs) {
            VoiceResolve.Result v = SpeakQueue.decide(r,
                    VoicePackStore.installedCodes(this), speaker.systemIso1());
            String state = v.voice == VoiceResolve.Voice.PACK
                    ? LangStore.tr(this, "voice_state_pack")
                    : v.voice == VoiceResolve.Voice.SYSTEM
                    ? LangStore.tr(this, "voice_state_system")
                    : LangStore.tr(this, "voice_state_none");
            labels.add(r.tvName + " — " + state);
        }
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "speak_language"))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    chosen = langs.get(which);
                    prefs().edit().putString("speak_lang", chosen.iso1).apply();
                    refreshLanguageUi();
                })
                .show();
    }

    // ------------------------------------------------ speaking

    /** The refusal runs BEFORE the queue accepts anything: with
     *  no voice for the chosen language, nothing is promised. */
    private boolean voiceReady() {
        VoiceResolve.Result r = SpeakQueue.decide(chosen,
                VoicePackStore.installedCodes(this), speaker.systemIso1());
        if (r.voice == VoiceResolve.Voice.NONE) {
            stateLine.setText(voiceStateText());
            return false;
        }
        return true;
    }

    private void speakBox() {
        String t = box.getText().toString().trim();
        if (t.isEmpty()) {
            stateLine.setText(LangStore.tr(this, "speak_type_first"));
            return;
        }
        if (!voiceReady()) return;
        queue.enqueue(t, chosen.iso1);
        speaker.speak(queue);
    }

    private void repeatLast() {
        if (!voiceReady()) return;
        if (queue.repeatLast()) {
            speaker.speak(queue);
        } else {
            stateLine.setText(LangStore.tr(this, "speak_type_first"));
        }
    }

    // ------------------------------------------------ phrases

    private void loadPhrases() {
        try {
            String body = MemoryHook.read(this, PHRASES_FILE);
            phrases = QuickPhrases.parse(body == null ? "" : body);
        } catch (Exception e) {
            phrases = new ArrayList<>();
        }
    }

    private void savePhrases() {
        try {
            MemoryHook.write(this, PHRASES_FILE, QuickPhrases.serialize(phrases));
        } catch (Exception ignored) {}
    }

    private void showPhrases() {
        phraseBox.removeAllViews();
        for (String p : phrases) {
            Button b = new Button(this);
            b.setText(p);
            b.setAllCaps(false);
            b.setTextSize(18);
            b.setMinHeight(dp(56));
            // a tap speaks the phrase at once — that is its job
            b.setOnClickListener(v -> {
                if (!voiceReady()) return;
                queue.enqueue(p, chosen.iso1);
                speaker.speak(queue);
            });
            b.setOnLongClickListener(v -> {
                phrases = QuickPhrases.remove(phrases, p);
                savePhrases();
                showPhrases();
                stateLine.setText(LangStore.tr(this, "speak_phrase_remove") + ": " + p);
                return true;
            });
            phraseBox.addView(b, topMargin(dp(4)));
        }
    }

    // ------------------------------------------------ document road

    private void pickDocument() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_DOC);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_DOC || result != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = displayName(uri);
        stateLine.setText(LangStore.tr(this, "translator_working"));
        new Thread(() -> {
            BookText.Result r;
            try {
                byte[] bytes = readAll(uri);
                r = BookText.extract(bytes, name);
            } catch (Exception e) {
                r = new BookText.Result();
                r.ok = false;
                r.failReason = BookText.NO_TEXT_FOUND;
            }
            final BookText.Result res = r;
            runOnUiThread(() -> onDocumentText(res));
        }, "speak-doc").start();
    }

    private void onDocumentText(BookText.Result r) {
        if (r == null || !r.ok) {
            String reason = r == null ? "" : r.failReason;
            if (BookText.SCANNED_NO_TEXT.equals(reason)) {
                stateLine.setText(LangStore.tr(this, "book_scanned"));
            } else if (BookText.UNSUPPORTED_KIND.equals(reason)) {
                stateLine.setText(LangStore.tr(this, "book_unsupported"));
            } else {
                stateLine.setText(LangStore.tr(this, "speak_doc_failed"));
            }
            return;
        }
        box.setText(r.text.length() > 4000
                ? r.text.substring(0, 4000) : r.text);
        receiptView.setText("");
        stateLine.setText("");
        if (!voiceReady()) return;
        queue.enqueueDocument(r.text, chosen.iso1);
        speaker.speak(queue);
    }

    private String displayName(Uri uri) {
        String name = "document";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) name = n;
                }
            }
        } catch (Exception ignored) {}
        return name;
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------ helpers

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams topMargin(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = m;
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private Button smallButton(String label, android.view.View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setOnClickListener(on);
        return b;
    }
}
