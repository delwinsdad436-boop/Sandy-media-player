package com.talacore.talatv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * LISTEN FOR ME (v0.15.0) — the ears door. Speech becomes big,
 * immediate text on screen (the recognition service's live
 * partials, shown as they form), and every utterance can be
 * handled three ways: issued as TEXT, RECORDED (the sound and
 * the transcript kept together as files on the memory hook), or
 * handed to the Translator.
 *
 * The state surface is honest: listening / heard / unsure / no
 * recognizer — unsure is shown as unsure (the best guess, labeled
 * a guess), never polished into certainty. Whose ear produced
 * the words is part of the record: the phone's listening SERVICE
 * (EarCapture — may use the network; the service's own reality)
 * or Sandy's own on-device whisper from an installed engine
 * pack, which backs the RECORD road for the pack pair's source
 * language (today: Japanese in ja-en).
 */
public final class ListenActivity extends Activity {

    private static final String PREFS = "sandy_doors";
    private static final int REQ_MIC = 7;

    private EarCapture ear;
    private ListenStore.WavRecorder recorder;
    private List<VoiceLangs.Row> langs = new ArrayList<>();
    private VoiceLangs.Row chosen;
    private boolean recordMode = false;
    private String lastText = "";
    private String lastPartial = "";

    private Button langBtn;
    private TextView stateLine;
    private TextView bigText;
    private TextView noteLine;
    private Button listenBtn;
    private Button toTranslatorBtn;
    private boolean listening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(LangStore.tr(this, "door_listen"));
        langs = VoiceLangs.load(this);
        String saved = prefs().getString("listen_lang", "");
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
        title.setText(LangStore.tr(this, "door_listen"));
        title.setTextSize(24);
        root.addView(title);

        TextView langLabel = new TextView(this);
        langLabel.setText(LangStore.tr(this, "speak_language"));
        langLabel.setTextSize(15);
        root.addView(langLabel, topMargin(dp(12)));
        langBtn = new Button(this);
        langBtn.setAllCaps(false);
        langBtn.setTextSize(19);
        langBtn.setOnClickListener(v -> pickLanguage());
        root.addView(langBtn);

        stateLine = new TextView(this);
        stateLine.setTextSize(17);
        root.addView(stateLine, topMargin(dp(10)));

        bigText = new TextView(this);
        bigText.setTextSize(30);
        root.addView(bigText, topMargin(dp(8)));

        noteLine = new TextView(this);
        noteLine.setTextSize(14);
        root.addView(noteLine, topMargin(dp(4)));

        CheckBox recordBox = new CheckBox(this);
        recordBox.setText(LangStore.tr(this, "listen_record"));
        recordBox.setTextSize(17);
        recordBox.setOnCheckedChangeListener((b, on) -> recordMode = on);
        root.addView(recordBox, topMargin(dp(12)));

        listenBtn = new Button(this);
        listenBtn.setText(LangStore.tr(this, "listen_start"));
        listenBtn.setTextSize(22);
        listenBtn.setMinHeight(dp(72));
        listenBtn.setOnClickListener(v -> {
            if (listening) stopListening();
            else startListening();
        });
        root.addView(listenBtn, topMargin(dp(10)));

        toTranslatorBtn = new Button(this);
        toTranslatorBtn.setText(LangStore.tr(this, "listen_to_translator"));
        toTranslatorBtn.setAllCaps(false);
        toTranslatorBtn.setTextSize(18);
        toTranslatorBtn.setEnabled(false);
        toTranslatorBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, TranslatorActivity.class);
            i.putExtra(TranslatorActivity.X_SEED_TEXT, lastText);
            i.putExtra(TranslatorActivity.X_SEED_LANG,
                    chosen == null ? "" : chosen.iso1);
            startActivity(i);
        });
        root.addView(toTranslatorBtn, topMargin(dp(10)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        ear = new EarCapture(this);
        ear.setListener(new EarCapture.Listener() {
            @Override public void onListening() {
                stateLine.setText(LangStore.tr(ListenActivity.this,
                        "listen_state_listening"));
            }
            @Override public void onPartial(String text) {
                lastPartial = text;
                bigText.setText(text);
            }
            @Override public void onFinal(String text, ListenRecord.State state) {
                onHeard(text, state);
            }
        });
        refreshLanguageUi();
    }

    @Override
    protected void onDestroy() {
        if (ear != null) ear.stop();
        if (recorder != null) recorder.stop();
        super.onDestroy();
    }

    // ------------------------------------------------ language

    private void refreshLanguageUi() {
        if (chosen == null) return;
        langBtn.setText(chosen.tvName);
        if (!EarCapture.present(this)) {
            stateLine.setText(LangStore.tr(this, "listen_state_none"));
        }
    }

    private void pickLanguage() {
        Set<String> pairs = TextMt.installedPairs(this);
        boolean present = EarCapture.present(this);
        List<String> labels = new ArrayList<>();
        for (VoiceLangs.Row r : langs) {
            StringBuilder s = new StringBuilder(r.tvName);
            if (!present) {
                s.append(" — ").append(LangStore.tr(this, "listen_state_none"));
            } else if (DoorCoverage.whisperCovers(r.iso1, pairs)) {
                s.append(" — ").append(LangStore.tr(this, "listen_whisper_note"));
            }
            labels.add(s.toString());
        }
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "speak_language"))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    chosen = langs.get(which);
                    prefs().edit().putString("listen_lang", chosen.iso1).apply();
                    refreshLanguageUi();
                })
                .show();
    }

    // ------------------------------------------------ listening

    private void startListening() {
        if (chosen == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_MIC);
            stateLine.setText(LangStore.tr(this, "listen_no_mic"));
            return;
        }
        if (!EarCapture.present(this)) {
            stateLine.setText(LangStore.tr(this, "listen_state_none"));
            return;
        }
        lastPartial = "";
        bigText.setText("");
        noteLine.setText("");
        if (recordMode) {
            recorder = new ListenStore.WavRecorder();
            recorder.start(); // sound kept alongside, whatever the ear does
        }
        if (ear.start(chosen.iso1)) {
            listening = true;
            listenBtn.setText(LangStore.tr(this, "listen_stop"));
            stateLine.setText(LangStore.tr(this, "listen_state_listening"));
        } else {
            if (recorder != null) { recorder.stop(); recorder = null; }
            stateLine.setText(LangStore.tr(this, "listen_state_none"));
        }
    }

    private void stopListening() {
        ear.stop();
        listening = false;
        listenBtn.setText(LangStore.tr(this, "listen_start"));
    }

    private void onHeard(String text, ListenRecord.State state) {
        listening = false;
        listenBtn.setText(LangStore.tr(this, "listen_start"));
        short[] pcm = null;
        if (recorder != null) {
            pcm = recorder.stop();
            recorder = null;
        }
        final short[] sound = pcm;
        String words = text == null ? "" : text;
        if (words.isEmpty() && state == ListenRecord.State.HEARD) {
            state = ListenRecord.State.UNSURE;
        }
        // show the take as it stands (best guess stays a guess)
        if (!words.isEmpty()) {
            bigText.setText(words);
            lastText = words;
            toTranslatorBtn.setEnabled(true);
        } else if (!lastPartial.isEmpty()) {
            bigText.setText(lastPartial);
        }
        switch (state) {
            case HEARD:
                stateLine.setText(LangStore.tr(this, "listen_state_heard"));
                noteLine.setText(LangStore.tr(this, "listen_system_note"));
                break;
            case UNSURE:
                stateLine.setText(LangStore.tr(this, "listen_state_unsure"));
                noteLine.setText(LangStore.tr(this, "listen_unsure_note"));
                break;
            case NO_RECOGNIZER:
                stateLine.setText(LangStore.tr(this, "listen_state_none"));
                noteLine.setText("");
                break;
            default:
                break;
        }
        if (recordMode && sound != null) {
            keepRecord(words, state, sound);
        }
    }

    /**
     * The RECORD road: decide the transcript's true source, then
     * keep sound + words together. When Sandy's whisper covers
     * this language (installed pack pair source), it re-hears the
     * saved sound on-device and its words — labeled — win; the
     * system ear's text stands otherwise, and an unsure take is
     * kept as an unsure take.
     */
    private void keepRecord(final String sysWords,
                            final ListenRecord.State sysState,
                            final short[] sound) {
        final String iso = chosen == null ? "" : chosen.iso1;
        String pair = null;
        for (String p : TextMt.installedPairs(this)) {
            if (p.startsWith(iso + "-")) { pair = p; break; }
        }
        final String whisperPair = pair;
        new Thread(() -> {
            ListenRecord.Record r = new ListenRecord.Record();
            r.id = ListenStore.newId();
            r.iso1 = iso;
            r.createdMs = System.currentTimeMillis();
            r.state = sysState;
            r.source = sysWords.isEmpty()
                    ? ListenRecord.Source.NONE : ListenRecord.Source.SYSTEM_EAR;
            r.text = sysWords;
            if (whisperPair != null) {
                String heard = ListenStore.transcribeWithWhisper(
                        this, sound, whisperPair);
                if (heard != null && !heard.isEmpty()) {
                    r.text = heard;
                    r.source = ListenRecord.Source.SANDY_WHISPER;
                    r.state = ListenRecord.State.HEARD;
                }
            }
            ListenRecord.Record saved = ListenStore.save(this, r, sound);
            final ListenRecord.Record done = saved;
            runOnUiThread(() -> {
                if (done == null) return;
                stateLine.setText(LangStore.tr(this, "listen_saved"));
                if (done.source == ListenRecord.Source.SANDY_WHISPER) {
                    noteLine.setText(LangStore.tr(this, "listen_whisper_note"));
                    bigText.setText(done.text);
                    lastText = done.text;
                    toTranslatorBtn.setEnabled(true);
                } else if (done.source == ListenRecord.Source.SYSTEM_EAR) {
                    noteLine.setText(LangStore.tr(this, "listen_system_note"));
                }
            });
        }, "listen-keep").start();
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
}
