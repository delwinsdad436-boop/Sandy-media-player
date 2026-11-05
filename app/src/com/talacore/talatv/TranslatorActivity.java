package com.talacore.talatv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * THE TRANSLATOR (v0.15.0) — two people, no shared language, one
 * phone passed between them. Turn-based by design (James's spec):
 * one side speaks or types → an honest working pause → the HEARD
 * words and the RENDERED words appear SIDE BY SIDE and stay
 * there (the verification rule: the text is the receipt) → the
 * rendering is spoken in the other side's language when the
 * output mode asks for a voice.
 *
 * Coverage is derived, never boasted (TranslatorCore over the
 * installed engine pairs): a pair with no real path is refused
 * BEFORE any words are captured, in plain words, and a route
 * through English is disclosed as a route through English —
 * intermediate text included. The conversation can be kept as
 * a file on the memory hook.
 */
public final class TranslatorActivity extends Activity {

    public static final String X_SEED_TEXT = "seed_text";
    public static final String X_SEED_LANG = "seed_lang";
    private static final String PREFS = "sandy_doors";
    private static final int REQ_MIC = 7;

    private final TranslatorCore.Conversation conv =
            new TranslatorCore.Conversation();
    private TranslatorCore.OutMode outMode = TranslatorCore.OutMode.BOTH;
    private final Map<String, TextMt> mtCache = new HashMap<>();
    private VoiceSpeaker speaker;
    private EarCapture ear;
    private List<VoiceLangs.Row> langs = new ArrayList<>();
    private boolean capturing = false;

    // The cycle guard (v0.20.0, item 11): every capture, turn and
    // spoken rendering passes through its phases — IDLE, CAPTURING,
    // WORKING, SPEAKING — so the ear can never be open while Sandy
    // is working or speaking, one take can yield at most one turn,
    // and her own rendered voice returning through the mic is
    // rejected as an echo, never recorded as a turn.
    private final TranslatorCycleGuard guard = new TranslatorCycleGuard();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** The guard seq owned by the capture now in flight (0 = none). */
    private int captureSeq = 0;

    private Button sideABtn, sideBBtn;
    private TextView routeLine;
    private TextView turnLine;
    private TextView heardView, renderedView, viaView;
    private TextView historyView;
    private TextView stateLine;
    private EditText typeBox;
    private Button speakBtn, useWordsBtn;
    private Button modeVoiceBtn, modeTextBtn, modeBothBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(LangStore.tr(this, "door_translator"));
        langs = VoiceLangs.load(this);
        conv.sideA = prefs().getString("tr_a", "en");
        conv.sideB = prefs().getString("tr_b", "");
        if (conv.sideB.isEmpty() || conv.sideB.equals(conv.sideA)) {
            conv.sideB = firstOtherThan(conv.sideA);
        }

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "door_translator"));
        title.setTextSize(24);
        root.addView(title);

        // the two sides
        LinearLayout sides = new LinearLayout(this);
        sides.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colA = sideColumn(LangStore.tr(this, "translator_side_a"), true);
        LinearLayout colB = sideColumn(LangStore.tr(this, "translator_side_b"), false);
        sides.addView(colA, weight());
        sides.addView(colB, weight());
        root.addView(sides, topMargin(dp(10)));

        Button swapBtn = new Button(this);
        swapBtn.setText(LangStore.tr(this, "translator_swap"));
        swapBtn.setAllCaps(false);
        swapBtn.setOnClickListener(v -> {
            conv.swapSides();
            persistSides();
            refreshSides();
        });
        root.addView(swapBtn, topMargin(dp(6)));

        routeLine = new TextView(this);
        routeLine.setTextSize(15);
        root.addView(routeLine, topMargin(dp(6)));

        turnLine = new TextView(this);
        turnLine.setTextSize(19);
        root.addView(turnLine, topMargin(dp(12)));

        // output mode: text ALWAYS shows; the mode governs voice
        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modeVoiceBtn = modeButton(LangStore.tr(this, "translator_mode_voice"),
                TranslatorCore.OutMode.VOICE);
        modeTextBtn = modeButton(LangStore.tr(this, "translator_mode_text"),
                TranslatorCore.OutMode.TEXT);
        modeBothBtn = modeButton(LangStore.tr(this, "translator_mode_both"),
                TranslatorCore.OutMode.BOTH);
        modes.addView(modeVoiceBtn, weight());
        modes.addView(modeTextBtn, weight());
        modes.addView(modeBothBtn, weight());
        root.addView(modes, topMargin(dp(8)));

        speakBtn = new Button(this);
        speakBtn.setText(LangStore.tr(this, "translator_speak_turn"));
        speakBtn.setTextSize(22);
        speakBtn.setMinHeight(dp(72));
        speakBtn.setOnClickListener(v -> startCapture());
        root.addView(speakBtn, topMargin(dp(10)));

        typeBox = new EditText(this);
        typeBox.setHint(LangStore.tr(this, "translator_type_hint"));
        typeBox.setTextSize(19);
        typeBox.setMinLines(2);
        root.addView(typeBox, topMargin(dp(8)));
        useWordsBtn = new Button(this);
        useWordsBtn.setText(LangStore.tr(this, "translator_use_words"));
        useWordsBtn.setAllCaps(false);
        useWordsBtn.setTextSize(17);
        useWordsBtn.setOnClickListener(v -> {
            String t = typeBox.getText().toString().trim();
            if (t.isEmpty()) return;
            // Typed turns take the same WORKING/SPEAKING discipline
            // as spoken ones: they also produce spoken output, so
            // they may not start while a capture, a turn or the
            // voice is live.
            if (!guard.tryBeginWork()) {
                stateLine.setText(LangStore.tr(this,
                        "translator_wait_voice"));
                return;
            }
            runTurn(t);
        });
        root.addView(useWordsBtn);

        stateLine = new TextView(this);
        stateLine.setTextSize(16);
        root.addView(stateLine, topMargin(dp(8)));

        // heard | rendered — side by side, always together
        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout heardCol = new LinearLayout(this);
        heardCol.setOrientation(LinearLayout.VERTICAL);
        TextView heardLabel = new TextView(this);
        heardLabel.setText(LangStore.tr(this, "translator_heard"));
        heardLabel.setTextSize(14);
        heardView = new TextView(this);
        heardView.setTextSize(21);
        heardCol.addView(heardLabel);
        heardCol.addView(heardView);
        LinearLayout rendCol = new LinearLayout(this);
        rendCol.setOrientation(LinearLayout.VERTICAL);
        TextView rendLabel = new TextView(this);
        rendLabel.setText(LangStore.tr(this, "translator_rendered"));
        rendLabel.setTextSize(14);
        renderedView = new TextView(this);
        renderedView.setTextSize(21);
        rendCol.addView(rendLabel);
        rendCol.addView(renderedView);
        pair.addView(heardCol, weight());
        pair.addView(rendCol, weight());
        root.addView(pair, topMargin(dp(12)));

        viaView = new TextView(this);
        viaView.setTextSize(14);
        root.addView(viaView, topMargin(dp(4)));

        historyView = new TextView(this);
        historyView.setTextSize(15);
        root.addView(historyView, topMargin(dp(14)));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        Button saveBtn = new Button(this);
        saveBtn.setText(LangStore.tr(this, "translator_save"));
        saveBtn.setAllCaps(false);
        saveBtn.setOnClickListener(v -> saveConversation());
        Button newBtn = new Button(this);
        newBtn.setText(LangStore.tr(this, "translator_new"));
        newBtn.setAllCaps(false);
        newBtn.setOnClickListener(v -> {
            conv.reset();
            guard.fail();
            capturing = false;
            captureSeq = 0;
            heardView.setText("");
            renderedView.setText("");
            viaView.setText("");
            historyView.setText("");
            stateLine.setText("");
            refreshSides();
        });
        foot.addView(saveBtn, weight());
        foot.addView(newBtn, weight());
        root.addView(foot, topMargin(dp(12)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        speaker = new VoiceSpeaker(this);
        // This activity is the ONLY owner of this VoiceSpeaker
        // instance (Speak For Me holds its own instance and its
        // own listener), and nothing else sets a listener on it —
        // verified in source. Registering one is what gives the
        // door knowledge of when its own spoken output ends: the
        // drain resolves the guard's SPEAKING phase. Callbacks
        // touch no views, so a late callback after destroy is safe.
        speaker.setListener(new VoiceSpeaker.Listener() {
            @Override public void onItemStart(SpeakQueue.Item item) {
                // The receipt (heard + rendered) is already on
                // screen from finishTurn; nothing to add here.
            }
            @Override public void onQueueDone() {
                settleSpeech();
            }
            @Override public void onPackFailed(String packCode) {
                // Not terminal by itself: VoiceSpeaker hands the
                // item to the phone's voice next. Resolve only if
                // the queue has in fact drained.
                settleSpeechWhenDrained();
            }
            @Override public void onItemFailed(SpeakQueue.Item item) {
                // Same: the failure dispatch continues into next()
                // after this callback — the item may be followed by
                // another, or by the drain (onQueueDone). Resolve
                // only once the voice is truly done, checked after
                // the in-flight dispatch settles.
                settleSpeechWhenDrained();
            }
        });
        speaker.warmUp(this::refreshSides);
        ear = new EarCapture(this);
        ear.setListener(new EarCapture.Listener() {
            @Override public void onListening() {
                if (!capturing) return;
                stateLine.setText(LangStore.tr(TranslatorActivity.this,
                        "listen_state_listening"));
            }
            @Override public void onPartial(String text) {
                if (!capturing) return;
                heardView.setText(text);
            }
            @Override public void onFinal(String text, ListenRecord.State state) {
                capturing = false;
                // One take, at most one turn: the guard accepts a
                // final exactly once, only for the live capture's
                // seq. A re-delivered, stale or stopped-take final
                // changes nothing — not even the state line.
                if (!guard.acceptFinal(captureSeq)) return;
                captureSeq = 0;
                if (state == ListenRecord.State.HEARD
                        && text != null && !text.trim().isEmpty()) {
                    String heard = text.trim();
                    // ECHO: the "next speaker" saying, word for
                    // word, exactly what Sandy just spoke aloud is
                    // Sandy's own voice coming back through the
                    // mic. Never a turn, the floor never passes,
                    // the cycle returns to IDLE — and it is said
                    // out loud on the state line, never silent.
                    if (guard.isEcho(heard)) {
                        guard.fail();
                        stateLine.setText(LangStore.tr(
                                TranslatorActivity.this,
                                "translator_heard_own_voice"));
                        return;
                    }
                    if (!guard.captureFinished()) {
                        guard.fail();
                        return;
                    }
                    runTurn(heard);
                } else {
                    guard.fail();
                    stateLine.setText(LangStore.tr(TranslatorActivity.this,
                            "translator_not_heard"));
                }
            }
        });

        // words handed over from Listen For Me: they become the
        // current side's typed turn, visible before anything runs
        String seed = getIntent().getStringExtra(X_SEED_TEXT);
        String seedLang = getIntent().getStringExtra(X_SEED_LANG);
        if (seed != null && !seed.trim().isEmpty()) {
            if (seedLang != null && !seedLang.isEmpty()) {
                if (conv.nextSide() == 'A') conv.sideA = seedLang;
                else conv.sideB = seedLang;
            }
            typeBox.setText(seed);
        }
        refreshSides();
    }

    /** Resolve the guard's SPEAKING phase: the voice is done.
     *  VoiceSpeaker delivers its callbacks on the main thread on
     *  every path (system completions and pack completions are
     *  posted to main inside VoiceSpeaker; the synchronous failure
     *  branches are called from finishTurn on the UI thread), and
     *  this marshals to main anyway, so a callback from any thread
     *  resolves the phase safely. Idempotent via the guard: a
     *  duplicate drain signal is a no-op. */
    private void settleSpeech() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            guard.speechFinished();
        } else {
            mainHandler.post(guard::speechFinished);
        }
    }

    /** For failure callbacks (onItemFailed / onPackFailed), which
     *  VoiceSpeaker fires in the middle of its failure dispatch —
     *  the queue continues into next() after they return, either
     *  to another item or to the drain. Defer one main-thread
     *  beat, then resolve SPEAKING only if the speaker reports
     *  nothing left to say, so a multi-item queue keeps its
     *  SPEAKING phase while later items are still spoken. */
    private void settleSpeechWhenDrained() {
        mainHandler.post(() -> {
            if (guard.phase() == TranslatorCycleGuard.Phase.SPEAKING
                    && (speaker == null || !speaker.isSpeaking())) {
                guard.speechFinished();
            }
        });
    }

    @Override
    protected void onDestroy() {
        guard.fail();
        capturing = false;
        captureSeq = 0;
        if (ear != null) ear.stop();
        if (speaker != null) speaker.shutdown();
        for (TextMt mt : mtCache.values()) {
            try { mt.close(); } catch (Exception ignored) {}
        }
        mtCache.clear();
        super.onDestroy();
    }

    // ------------------------------------------------ sides & route

    private LinearLayout sideColumn(String label, boolean isA) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView lab = new TextView(this);
        lab.setText(label);
        lab.setTextSize(14);
        col.addView(lab);
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setOnClickListener(v -> pickSide(isA));
        col.addView(b);
        if (isA) sideABtn = b; else sideBBtn = b;
        return col;
    }

    private Button modeButton(String label, TranslatorCore.OutMode m) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(v -> {
            outMode = m;
            refreshModes();
        });
        return b;
    }

    private void refreshModes() {
        modeVoiceBtn.setEnabled(outMode != TranslatorCore.OutMode.VOICE);
        modeTextBtn.setEnabled(outMode != TranslatorCore.OutMode.TEXT);
        modeBothBtn.setEnabled(outMode != TranslatorCore.OutMode.BOTH);
    }

    private String firstOtherThan(String iso) {
        for (VoiceLangs.Row r : langs) {
            if (!r.iso1.isEmpty() && !r.iso1.equals(iso)) return r.iso1;
        }
        return "en";
    }

    private String langName(String iso) {
        VoiceLangs.Row r = VoiceLangs.byIso1(langs, iso);
        return r == null ? iso : r.tvName;
    }

    private void persistSides() {
        prefs().edit().putString("tr_a", conv.sideA)
                .putString("tr_b", conv.sideB).apply();
    }

    private void pickSide(boolean isA) {
        Set<String> pairs = TextMt.installedPairs(this);
        List<VoiceLangs.Row> pickable = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (VoiceLangs.Row r : langs) {
            if (r.iso1.isEmpty()) continue;
            pickable.add(r);
            labels.add(r.tvName);
        }
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, isA
                        ? "translator_side_a" : "translator_side_b"))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String iso = pickable.get(which).iso1;
                    if (isA) conv.sideA = iso; else conv.sideB = iso;
                    persistSides();
                    refreshSides();
                })
                .show();
    }

    /** The route truth for the CURRENT direction, refreshed
     *  whenever sides or the turn change. NONE disables capture:
     *  the refusal lands before a word is taken. */
    private TranslatorCore.Route currentRoute() {
        return DoorCoverage.route(conv.fromIso(), conv.toIso(),
                TextMt.installedPairs(this));
    }

    private void refreshSides() {
        sideABtn.setText(langName(conv.sideA));
        sideBBtn.setText(langName(conv.sideB));
        turnLine.setText(LangStore.tr(this, conv.nextSide() == 'A'
                ? "translator_turn_a" : "translator_turn_b"));
        refreshModes();
        TranslatorCore.Route route = currentRoute();
        boolean canCapture = route.ok();
        speakBtn.setEnabled(canCapture);
        useWordsBtn.setEnabled(canCapture);
        typeBox.setEnabled(canCapture);
        switch (route.kind) {
            case NONE:
                routeLine.setText(String.format(
                        LangStore.tr(this, "translator_cant"),
                        langName(conv.fromIso()), langName(conv.toIso())));
                break;
            case PIVOT:
                routeLine.setText(LangStore.tr(this, "translator_pivot"));
                break;
            case IDENTITY:
                routeLine.setText(langName(conv.fromIso())
                        + " → " + langName(conv.toIso()));
                break;
            default:
                routeLine.setText(langName(conv.fromIso())
                        + " → " + langName(conv.toIso()));
                break;
        }
    }

    // ------------------------------------------------ the turn

    private void startCapture() {
        if (capturing) {
            // Tapping again stops the take. The stopped take's
            // late final, if the service still delivers one, is
            // refused by the guard: fail() invalidated its seq.
            ear.stop();
            capturing = false;
            captureSeq = 0;
            guard.fail();
            return;
        }
        // A capture may start ONLY from IDLE: never while a turn
        // is working and never while Sandy's own voice is still
        // speaking. Refusal is said in plain words, and the ear
        // is not started.
        if (!guard.tryBeginCapture()) {
            stateLine.setText(LangStore.tr(this,
                    "translator_wait_voice"));
            return;
        }
        if (!currentRoute().ok()) {
            guard.fail();
            refreshSides();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_MIC);
            stateLine.setText(LangStore.tr(this, "listen_no_mic"));
            guard.fail();
            return;
        }
        heardView.setText("");
        renderedView.setText("");
        viaView.setText("");
        if (ear.start(conv.fromIso())) {
            capturing = true;
            captureSeq = guard.currentCaptureSeq();
        } else {
            guard.fail();
            stateLine.setText(LangStore.tr(this, "listen_state_none"));
        }
    }

    /**
     * One full turn: heard words → (the honest pause) → rendered
     * words beside them → spoken in the other language when the
     * mode asks. Runs the MT on a worker; the state machine
     * (TranslatorCore.Conversation) records the turn only when a
     * real route carried it.
     */
    private void runTurn(final String heard) {
        // Callers have already entered the cycle: the ear path via
        // captureFinished() (CAPTURING -> WORKING), the typed path
        // via tryBeginWork() (IDLE -> WORKING).
        final TranslatorCore.Route route = currentRoute();
        if (!route.ok()) { guard.fail(); refreshSides(); return; }
        final String from = conv.fromIso();
        final String to = conv.toIso();
        stateLine.setText(LangStore.tr(this, "translator_working"));
        heardView.setText(heard);
        renderedView.setText("");
        viaView.setText("");
        new Thread(() -> {
            String rendered = "";
            String via = "";
            String fail = "";
            try {
                switch (route.kind) {
                    case IDENTITY:
                        rendered = heard;
                        break;
                    case DIRECT:
                        rendered = mt(route.pair).translate(heard);
                        break;
                    case PIVOT:
                        via = mt(route.legIn).translate(heard);
                        if (via == null || via.trim().isEmpty()) {
                            throw new Exception("the first step said nothing");
                        }
                        rendered = mt(route.legOut).translate(via);
                        break;
                    default:
                        break;
                }
                if (rendered == null) rendered = "";
                if (rendered.trim().isEmpty()
                        && route.kind != TranslatorCore.RouteKind.IDENTITY) {
                    fail = LangStore.tr(this, "translator_not_heard");
                }
            } catch (Exception e) {
                fail = e.getMessage() == null ? "" : e.getMessage();
                if (fail.isEmpty()) {
                    fail = LangStore.tr(this, "translator_not_heard");
                }
            }
            final String fRendered = rendered;
            final String fVia = via;
            final String fFail = fail;
            runOnUiThread(() -> finishTurn(heard, fRendered, fVia, fFail,
                    route, from, to));
        }, "translator-turn").start();
    }

    private void finishTurn(String heard, String rendered, String via,
                            String fail, TranslatorCore.Route route,
                            String from, String to) {
        if (!fail.isEmpty()) {
            guard.fail();
            stateLine.setText(fail);
            return;
        }
        TranslatorCore.Turn turn =
                conv.addTurn(heard, rendered, via, outMode, route);
        if (turn == null) { guard.fail(); refreshSides(); return; }
        heardView.setText(turn.heard);
        renderedView.setText(turn.rendered);
        viaView.setText(turn.via.isEmpty() ? ""
                : "via English: " + turn.via);
        stateLine.setText("");
        typeBox.setText("");
        appendHistory(turn);
        // Speak the rendering in the OTHER side's language — under
        // the guard's phase discipline. willSpeak is true only
        // when the mode asks for a voice AND a voice was actually
        // resolved: the Voice.NONE case means no speech happens,
        // so the cycle goes WORKING -> IDLE, never SPEAKING.
        boolean willSpeak = false;
        SpeakQueue speakQ = null;
        if (TranslatorCore.speaks(outMode)) {
            VoiceLangs.Row row = VoiceLangs.byIso1(langs, to);
            VoiceResolve.Result v = SpeakQueue.decide(row,
                    VoicePackStore.installedCodes(this), speaker.systemIso1());
            if (v.voice == VoiceResolve.Voice.NONE) {
                // no voice: the text stands (it always stands);
                // the missing voice is said, not hidden
                stateLine.setText(String.format(
                        LangStore.tr(this, "translator_voice_missing"),
                        langName(to)));
            } else {
                speakQ = new SpeakQueue();
                speakQ.enqueue(turn.rendered, to);
                willSpeak = true;
            }
        }
        // The transition is recorded BEFORE speak() is called, so
        // a queue that drains or fails synchronously inside
        // speak() resolves SPEAKING -> IDLE through the listener,
        // in order. It also records the turn's heard + rendered
        // text as the guard's echo signature. If the transition is
        // refused the cycle no longer owns this turn (it was reset
        // or failed while the MT worker ran) — the text stands,
        // but nothing is spoken unguarded.
        boolean inCycle = guard.turnRendered(willSpeak,
                turn.heard, turn.rendered);
        if (willSpeak && inCycle) {
            speaker.speak(speakQ);
        }
        refreshSides();
    }

    private void appendHistory(TranslatorCore.Turn t) {
        StringBuilder sb = new StringBuilder(historyView.getText());
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(t.side).append(" · ").append(langName(t.fromIso))
                .append(" → ").append(langName(t.toIso)).append('\n');
        sb.append(LangStore.tr(this, "translator_heard"))
                .append(": ").append(t.heard).append('\n');
        if (!t.via.isEmpty()) {
            sb.append("via English: ").append(t.via).append('\n');
        }
        sb.append(LangStore.tr(this, "translator_rendered"))
                .append(": ").append(t.rendered);
        historyView.setText(sb.toString());
    }

    private TextMt mt(String pair) throws Exception {
        TextMt m = mtCache.get(pair);
        if (m == null) {
            m = TextMt.open(this, pair);
            mtCache.put(pair, m);
        }
        return m;
    }

    // ------------------------------------------------ file out

    private void saveConversation() {
        if (conv.turns.isEmpty()) {
            stateLine.setText(LangStore.tr(this, "translator_not_heard"));
            return;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        try {
            MemoryHook.write(this, "translator_" + stamp + ".txt",
                    TranslatorCore.transcriptBody(conv));
            stateLine.setText(LangStore.tr(this, "translator_saved"));
        } catch (Exception e) {
            stateLine.setText(LangStore.tr(this, "translator_not_heard"));
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
}
