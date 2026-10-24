package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The dub kitchen (v0.12.0) — James's order, built into Sandy
 * herself: RECORD it → COOK the dub → THEN WATCH it. Not real-time,
 * never promised as such; a cooked dub is a FILE in the library.
 *
 * The flow on this screen:
 *   PICK an episode Sandy already has (My Videos' shelf or the
 *   Recordings shelf) or pick a video file;
 *   ANALYZE — the subtitle tracks that travel with the file are
 *   found and read (SubFinder/SubHarvest), the packs' states are
 *   read, and DubGate states the plan as one spoken sentence;
 *   CHOOSE the language to hear (and pin the subtitle language
 *   when a file doesn't declare it — Sandy asks, never guesses);
 *   COOK on this phone, stage by spoken stage: subtitles first
 *   (translate-then-speak), the ear only as the fallback; the
 *   voice pack witnesses who speaks each line; the VoiceBook
 *   casts every character ONCE — per show, per season — and the
 *   casting is saved before the first line is spoken, so episode
 *   two inherits episode one;
 *   DONE: the dubbed track, the translated subtitle file and the
 *   full record sit on the Dubbed-episodes shelf, with every
 *   disclosure attached (near-match voices, re-spoken lines,
 *   lines that ran long, lines the translator garbled silent) —
 *   and one tap plays the episode with its dub.
 *
 * The executor is Sandy: every organ runs on-device through the
 * adopted packs. Nothing here phones home, and nothing fakes an
 * organ that isn't in: a missing pack is a spoken plan change,
 * never a simulated result.
 */
public final class DubActivity extends Activity {

    /** The 13 spoken languages a dub can aim at (code, name). */
    static final String[][] HEARD = {
        {"en", "English"}, {"es", "Spanish"}, {"fr", "French"},
        {"de", "German"}, {"pt", "Portuguese"}, {"it", "Italian"},
        {"ru", "Russian"}, {"ar", "Arabic"}, {"hi", "Hindi"},
        {"zh", "Chinese"}, {"ja", "Japanese"}, {"ko", "Korean"},
        {"fa", "Persian"},
    };

    private static final int REQ_PICK_VIDEO = 4712;
    private static final int REQ_PICK_SUB = 4713;

    private static final class Pick {
        String url = "";
        String name = "";
        boolean recording = false;
    }

    private ExecutorService exec;

    // v0.13.0: the voice table + this target's resolved voice
    private List<VoiceLangs.Row> voiceRows = new ArrayList<>();
    private String targetTv = "English";
    private VoiceResolve.Result voice;
    private final java.util.Set<String> systemProbeOk = new java.util.HashSet<>();
    private final java.util.Set<String> systemProbeAsked = new java.util.HashSet<>();
    private android.speech.tts.TextToSpeech voiceProbe;
    private boolean voiceProbeReady = false;
    private Runnable settleRecheck;

    private TextView statusView;
    private ListView list;
    private ArrayAdapter<String> adapter;
    private final List<String> rows = new ArrayList<>();
    private ProgressBar progress;

    private boolean shelfMode = false;
    private List<DubShelf.Entry> shelfEntries = new ArrayList<>();
    private List<Pick> picks = new ArrayList<>();

    // analysis state for the chosen pick
    private Pick chosen;
    private List<SubHarvest.Track> tracks = new ArrayList<>();
    private List<SubFinder.Found> foundSubs = new ArrayList<>();
    private int trackIdx = -1;              // chosen subtitle track; -1 = none
    private String pinnedSubLang = null;    // viewer's pin for an unnamed track
    private String targetLang = "en";
    private DubGate.Decision plan;

    private LinearLayout planBox;
    private TextView planView;
    private TextView analyzeView;
    private LinearLayout subLangBox;
    private LinearLayout targetBox;
    private Button cookBtn;
    private Button cancelBtn;
    private LinearLayout doneBox;
    private TextView doneView;
    private Button watchBtn;
    private DubShelf.Entry lastCooked;

    private volatile boolean cooking = false;
    private volatile boolean cancelFlag = false;
    private TtsDubRenderer activeRenderer;
    private OnnxCookerEngine activeEngine;

    // ------------------------------------------------------------ life

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        exec = Executors.newSingleThreadExecutor();
        shelfMode = "shelf".equals(getIntent().getStringExtra("mode"));
        setTitle(LangStore.tr(this, shelfMode ? "dub_shelf_door" : "title_dub"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        root.addView(statusView);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(list, lp);

        planBox = new LinearLayout(this);
        planBox.setOrientation(LinearLayout.VERTICAL);
        planBox.setVisibility(View.GONE);
        root.addView(planBox);

        analyzeView = new TextView(this);
        analyzeView.setTextSize(15);
        planBox.addView(analyzeView);

        subLangBox = new LinearLayout(this);
        subLangBox.setOrientation(LinearLayout.VERTICAL);
        planBox.addView(subLangBox);

        targetBox = new LinearLayout(this);
        targetBox.setOrientation(LinearLayout.VERTICAL);
        planBox.addView(targetBox);

        planView = new TextView(this);
        planView.setTextSize(16);
        planBox.addView(planView);

        cookBtn = new Button(this);
        cookBtn.setText(LangStore.tr(this, "dub_cook_btn"));
        cookBtn.setOnClickListener(v -> startCook());
        planBox.addView(cookBtn);

        cancelBtn = new Button(this);
        cancelBtn.setText(LangStore.tr(this, "dub_cancel"));
        cancelBtn.setVisibility(View.GONE);
        cancelBtn.setOnClickListener(v -> {
            cancelFlag = true;
            if (activeRenderer != null) activeRenderer.cancel();
            if (activeEngine != null) activeEngine.cancel();
        });
        planBox.addView(cancelBtn);

        doneBox = new LinearLayout(this);
        doneBox.setOrientation(LinearLayout.VERTICAL);
        doneBox.setVisibility(View.GONE);
        root.addView(doneBox);
        doneView = new TextView(this);
        doneView.setTextSize(15);
        doneBox.addView(doneView);
        watchBtn = new Button(this);
        watchBtn.setText(LangStore.tr(this, "dub_watch_btn"));
        watchBtn.setOnClickListener(v -> {
            if (lastCooked != null) watch(lastCooked);
        });
        doneBox.addView(watchBtn);
        Button castBtn = new Button(this);
        castBtn.setText(LangStore.tr(this, "dub_cast_btn"));
        castBtn.setOnClickListener(v ->
                startActivity(new Intent(this, VoiceBookActivity.class)));
        doneBox.addView(castBtn);

        setContentView(root);

        list.setOnItemClickListener((parent, view, pos, id) -> onRow(pos));
        list.setOnItemLongClickListener((parent, view, pos, id) -> onRowLong(pos));

        voiceRows = VoiceLangs.load(this);
        settleRecheck = () -> {
            if (!cooking && chosen != null) refreshPlan();
        };
        // v0.13.0: a preselected pick (the "Make a voiceover…"
        // doors on the episode page, the recordings shelf and My
        // Videos) opens the kitchen already holding its video
        String preUrl = getIntent().getStringExtra("pickUrl");
        if (!shelfMode && preUrl != null && !preUrl.isEmpty()) {
            Pick p = new Pick();
            p.url = preUrl;
            p.name = getIntent().getStringExtra("pickName");
            if (p.name == null || p.name.isEmpty()) p.name = preUrl;
            p.recording = getIntent().getBooleanExtra("pickRecording", false);
            analyze(p);
        } else if (shelfMode) loadShelf(); else loadPicks();
    }

    @Override
    protected void onDestroy() {
        cancelFlag = true;
        statusView.removeCallbacks(settleRecheck);
        if (voiceProbe != null) {
            try { voiceProbe.shutdown(); } catch (Exception ignored) {}
        }
        if (exec != null) exec.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String tr(String key) { return LangStore.tr(this, key); }

    // ------------------------------------------------------------ pick list

    private void loadPicks() {
        statusView.setText(tr("dub_pick_hint"));
        rows.clear();
        picks.clear();
        adapter.notifyDataSetChanged();
        exec.execute(() -> {
            List<Pick> out = new ArrayList<>();
            // My Videos' three doors, merged the shelf's own way
            try {
                List<LocalFiles.Row> merged = DubPickSources.merged(this);
                for (LocalFiles.Row r : merged) {
                    Pick p = new Pick();
                    p.url = r.uri;
                    p.name = r.title;
                    out.add(p);
                }
            } catch (Throwable ignored) {}
            // settled recordings
            try {
                androidx.media3.exoplayer.offline.DownloadManager m =
                        Downloads.manager(this);
                if (m != null) {
                    try (androidx.media3.exoplayer.offline.DownloadCursor cursor =
                                 m.getDownloadIndex().getDownloads()) {
                        while (cursor.moveToNext()) {
                            androidx.media3.exoplayer.offline.Download d =
                                    cursor.getDownload();
                            boolean settled = d.state
                                    == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
                                    || (d.state
                                    == androidx.media3.exoplayer.offline.Download.STATE_STOPPED
                                    && d.getBytesDownloaded() > 0);
                            if (!settled) continue;
                            Pick p = new Pick();
                            p.url = d.request.id;
                            String name = ChannelStore.getRecordName(this, d.request.id);
                            p.name = name.isEmpty() ? d.request.id : name;
                            p.recording = true;
                            out.add(p);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            runOnUiThread(() -> {
                picks = out;
                rows.clear();
                rows.add("＋ " + tr("dub_pick_file"));
                for (Pick p : picks) {
                    rows.add("🎬 " + p.name + (p.recording
                            ? "\n" + tr("dub_from_recording") : ""));
                }
                if (picks.isEmpty()) statusView.setText(tr("dub_pick_empty"));
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void loadShelf() {
        statusView.setText(tr("dub_shelf_hint"));
        exec.execute(() -> {
            List<DubShelf.Entry> es = DubShelf.list(this);
            runOnUiThread(() -> {
                shelfEntries = es;
                rows.clear();
                if (es.isEmpty()) rows.add(tr("dub_shelf_empty"));
                for (DubShelf.Entry e : es) rows.add("▶ " + e.displayName()
                        + "\n" + e.lineCount + " " + tr("dub_stat_lines")
                        + (e.castCount > 0 ? " · " + e.castCount + " "
                        + tr("dub_stat_cast") : ""));
                adapter.notifyDataSetChanged();
            });
        });
    }

    private void onRow(int pos) {
        if (shelfMode) {
            if (pos < shelfEntries.size()) watch(shelfEntries.get(pos));
            return;
        }
        if (cooking) return;
        if (pos == 0) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            startActivityForResult(i, REQ_PICK_VIDEO);
            return;
        }
        if (pos - 1 < picks.size()) analyze(picks.get(pos - 1));
    }

    private boolean onRowLong(int pos) {
        if (!shelfMode || pos >= shelfEntries.size()) return false;
        DubShelf.Entry e = shelfEntries.get(pos);
        new AlertDialog.Builder(this)
                .setTitle(e.displayName())
                .setItems(new CharSequence[]{tr("dub_watch_btn"), tr("dub_delete")},
                        (d, which) -> {
                            if (which == 0) watch(e);
                            else deleteDub(e);
                        })
                .show();
        return true;
    }

    private void deleteDub(DubShelf.Entry e) {
        exec.execute(() -> {
            for (String p : new String[]{e.dubPath, e.srtPath, e.jsonPath}) {
                if (p != null && !p.isEmpty()) new File(p).delete();
            }
            DubShelf.remove(this, e.id);
            runOnUiThread(this::loadShelf);
        });
    }

    private void watch(DubShelf.Entry e) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", e.videoUrl);
        i.putExtra("name", e.showTitle);
        i.putExtra("ckey", e.showKey);
        i.putExtra("dubUrl", Uri.fromFile(new File(e.dubPath)).toString());
        i.putExtra("subUrl", Uri.fromFile(new File(e.srtPath)).toString());
        i.putExtra("subMime", "application/x-subrip");
        i.putExtra("subLang", e.targetLang);
        i.putExtra("trJsonUrl", Uri.fromFile(new File(e.jsonPath)).toString());
        if (e.videoUrl.startsWith("content://") || e.videoUrl.startsWith("/")) {
            i.putExtra("localSet", true);
        }
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {}
        if (req == REQ_PICK_VIDEO) {
            Pick p = new Pick();
            p.url = uri.toString();
            p.name = DubPickSources.displayName(this, p.url);
            analyze(p);
        } else if (req == REQ_PICK_SUB) {
            adoptPickedSub(uri);
        }
    }

    // ------------------------------------------------------------ analysis

    /** Find + read the subtitle tracks, then state the plan. */
    private void analyze(Pick p) {
        chosen = p;
        tracks = new ArrayList<>();
        foundSubs = new ArrayList<>();
        trackIdx = -1;
        pinnedSubLang = null;
        doneBox.setVisibility(View.GONE);
        planBox.setVisibility(View.VISIBLE);
        analyzeView.setText(tr("dub_state_reading"));
        planView.setText("");
        statusView.setText("🎬 " + p.name);
        String uiLang = ChannelStore.getUiLanguage(this);
        targetLang = "en";
        targetTv = "English";
        for (String[] h : HEARD) if (h[0].equals(uiLang)) targetLang = uiLang;
        VoiceLangs.Row uiRow = VoiceLangs.byIso1(voiceRows, targetLang);
        if (uiRow != null) targetTv = uiRow.tvName;
        exec.execute(() -> {
            List<SubFinder.Found> found = SubFinder.find(this, p.url, p.name);
            List<SubHarvest.Track> parsed = new ArrayList<>();
            List<SubFinder.Found> kept = new ArrayList<>();
            for (SubFinder.Found f : found) {
                String text = SubFinder.readText(this, f);
                if (text == null) continue;
                SubHarvest.Track t = SubHarvest.parse(f.name, text);
                if (t.lines.size() < 3) continue;   // a stub is not a track
                t.lang = f.lang;
                parsed.add(t);
                kept.add(f);
            }
            runOnUiThread(() -> {
                foundSubs = kept;
                tracks = parsed;
                trackIdx = bestTrack();
                refreshPlan();
            });
        });
    }

    /** The track the plan starts from: one already in, or routable
     *  to, the hearing language; else the longest track; else the
     *  unnamed one (the viewer pins its language). */
    private int bestTrack() {
        if (tracks.isEmpty()) return -1;
        Set<String> pairs = TextMt.installedPairs(this);
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < tracks.size(); i++) {
            SubHarvest.Track t = tracks.get(i);
            int score = t.lines.size();
            if (!t.lang.isEmpty()) {
                DubGate.Route r = DubGate.route(t.lang, targetLang, pairs);
                if (r == DubGate.Route.PASSTHROUGH) score += 100000;
                else if (r == DubGate.Route.DIRECT) score += 50000;
            }
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return best;
    }

    /** Offer the other found tracks + a hand-picked subtitle file. */
    private void adoptPickedSub(Uri uri) {
        try {
            String name = DubPickSources.displayName(this, uri.toString());
            SubFinder.Found f = new SubFinder.Found();
            f.uri = uri.toString();
            f.name = name;
            f.lang = SubHarvest.langFromName(name,
                    DubTrack.stemOf(chosen == null ? "" : chosen.name));
            String text = SubFinder.readText(this, f);
            if (text == null) {
                Toast.makeText(this, tr("dub_sub_unreadable"), Toast.LENGTH_LONG).show();
                return;
            }
            SubHarvest.Track t = SubHarvest.parse(name, text);
            if (t.lines.size() < 3) {
                Toast.makeText(this, tr("dub_sub_unreadable"), Toast.LENGTH_LONG).show();
                return;
            }
            t.lang = f.lang;
            foundSubs.add(f);
            tracks.add(t);
            trackIdx = tracks.size() - 1;
            pinnedSubLang = null;
            refreshPlan();
        } catch (Throwable t) {
            Toast.makeText(this, tr("dub_sub_unreadable"), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshPlan() {
        if (chosen == null) return;
        // ---- the analysis sentence
        StringBuilder a = new StringBuilder();
        if (trackIdx >= 0) {
            SubHarvest.Track t = tracks.get(trackIdx);
            a.append("📄 ").append(tr("dub_subs_found")).append(": ")
             .append(foundSubs.get(trackIdx).name)
             .append(" · ").append(t.lines.size()).append(" ").append(tr("dub_stat_lines"))
             .append(" · ").append(t.lang.isEmpty()
                     ? tr("dub_sub_lang_undeclared") : langName(t.lang));
            if (t.namedLines > 0) {
                a.append(" · ").append(t.namedLines).append(" ")
                 .append(tr("dub_sub_named"));
            }
            if (tracks.size() > 1) {
                a.append("\n").append(tr("dub_sub_others")).append(" ")
                 .append(tracks.size() - 1);
            }
        } else {
            a.append("📄 ").append(tr("dub_subs_none"));
        }
        DiarPackStore.Status diar = DiarPackStore.status(this);
        a.append("\n").append(diar.installed
                ? "🗣 " + tr("dub_voicepack_in")
                : "🗣 " + tr("dub_voicepack_out"));
        analyzeView.setText(a.toString());

        buildSubLangChips();
        buildTargetChips();
        plan = computePlan();

        // v0.13.0: a recording pick whose copy is still landing
        // cannot cook yet — said plainly, re-checked while it lands
        statusView.removeCallbacks(settleRecheck);
        if (chosen.recording && !recordingSettled(chosen)) {
            planView.setText("⏳ " + tr("dub_still_saving"));
            cookBtn.setEnabled(false);
            statusView.postDelayed(settleRecheck, 4000);
            return;
        }

        // v0.13.0: the voice disclosure — who will speak the
        // target language on THIS phone, resolved before any cook
        voice = resolveTargetVoice();
        StringBuilder pt = new StringBuilder(planText(plan));
        if (voice.voice == VoiceResolve.Voice.PACK) {
            pt.append("\n🗣 ").append(String.format(
                    tr("dub_voice_pack"), targetTv));
        } else if (voice.voice == VoiceResolve.Voice.SYSTEM) {
            pt.append("\n🗣 ").append(String.format(
                    tr("dub_voice_system"), targetTv));
        } else {
            pt.append("\n🔇 ").append(String.format(
                    tr("dub_voice_none"), targetTv));
        }
        planView.setText(pt.toString());
        cookBtn.setEnabled(plan.cooks()
                && voice.voice != VoiceResolve.Voice.NONE);
    }

    /** Settled = the recording's bytes are all here (or kept). */
    private boolean recordingSettled(Pick p) {
        try {
            androidx.media3.exoplayer.offline.Download d =
                    Downloads.get(this, p.url);
            if (d == null) return false;
            return d.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
                    || (d.state == androidx.media3.exoplayer.offline.Download.STATE_STOPPED
                            && d.getBytesDownloaded() > 0);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Resolve the target's voice; probe the phone's engine when
     *  the answer isn't known yet (the probe's return refreshes
     *  the plan). */
    private VoiceResolve.Result resolveTargetVoice() {
        VoiceLangs.Row row = VoiceLangs.byIso1(voiceRows, targetLang);
        if (row == null) return new VoiceResolve.Result();
        VoiceResolve.Result r = VoiceResolve.resolve(row,
                VoicePackStore.installedCodes(this), systemProbeOk);
        if (r.voice == VoiceResolve.Voice.NONE
                && !systemProbeAsked.contains(row.iso1)) {
            probeSystemVoice(row.iso1);
        }
        return r;
    }

    private void probeSystemVoice(String iso1) {
        if (voiceProbe != null && voiceProbeReady) {
            runProbe(iso1);
            return;
        }
        if (voiceProbe != null) return;   // init already in flight
        final android.speech.tts.TextToSpeech[] holder =
                new android.speech.tts.TextToSpeech[1];
        holder[0] = new android.speech.tts.TextToSpeech(this, status -> {
            voiceProbeReady = status == android.speech.tts.TextToSpeech.SUCCESS;
            if (voiceProbeReady) runProbe(iso1);
        });
        voiceProbe = holder[0];
    }

    private void runProbe(String iso1) {
        try {
            int a = voiceProbe.isLanguageAvailable(
                    java.util.Locale.forLanguageTag(iso1));
            if (a >= android.speech.tts.TextToSpeech.LANG_AVAILABLE) {
                systemProbeOk.add(iso1);
            }
        } catch (Throwable ignored) {}
        // mark "asked, not available" too, so we don't re-probe in
        // a loop: a sentinel with the language's own tag negated
        systemProbeAsked.add(iso1);
        runOnUiThread(() -> { if (chosen != null && !cooking) refreshPlan(); });
    }

    private DubGate.Decision computePlan() {
        Set<String> pairs = TextMt.installedPairs(this);
        String subLang = null;
        boolean named = false;
        if (trackIdx >= 0) {
            SubHarvest.Track t = tracks.get(trackIdx);
            subLang = t.lang.isEmpty() ? (pinnedSubLang == null ? "" : pinnedSubLang)
                    : t.lang;
            named = t.namedLines > 0;
        }
        EnginePackStore.Status engine = EnginePackStore.anyInstalled(this);
        String asrPair = engine != null && engine.onnx() ? engine.pair : null;
        return DubGate.decide(subLang, named, targetLang, pairs,
                DiarPackStore.status(this).installed, asrPair);
    }

    private String planText(DubGate.Decision d) {
        switch (d.plan) {
            case SUB_CAST: return "🧑‍🤝‍🧑 " + tr("dub_plan_cast");
            case SUB_NAMED: return "🧑‍🤝‍🧑 " + tr("dub_plan_named");
            case SUB_SOLO: return "🧑 " + tr("dub_plan_solo_sub");
            case ASR_CAST: return "🧑‍🤝‍🧑 " + tr("dub_plan_asr_cast");
            case ASR_SOLO: return "🧑 " + tr("dub_plan_asr_solo");
            case SUB_LANG_UNKNOWN: return "❓ " + tr("dub_sub_lang_q");
            case NEEDS_MT: return "🧩 " + tr("dub_need_mt").replace("%s", d.detail);
            case NEEDS_ENGINE: return "👂 " + tr("dub_need_engine");
            default: return "";
        }
    }

    private void buildSubLangChips() {
        subLangBox.removeAllViews();
        if (trackIdx < 0) {
            Button pick = new Button(this);
            pick.setText("＋ " + tr("dub_pick_sub"));
            pick.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(i, REQ_PICK_SUB);
            });
            subLangBox.addView(pick);
            return;
        }
        SubHarvest.Track t = tracks.get(trackIdx);
        if (!t.lang.isEmpty()) return;
        TextView q = new TextView(this);
        q.setText(tr("dub_sub_lang_q"));
        subLangBox.addView(q);
        // v0.13.0: the pin is any catalogue language, not 13
        Button pin = new Button(this);
        pin.setText(pinnedSubLang == null
                ? tr("dub_sub_lang_q") : langName(pinnedSubLang));
        pin.setOnClickListener(v -> pickSubLanguage());
        subLangBox.addView(pin);
        Button none = new Button(this);
        none.setText(tr("dub_sub_unusable"));
        none.setOnClickListener(v -> {
            // the viewer says this track is no use: plan the ear road
            trackIdx = -1;
            pinnedSubLang = null;
            refreshPlan();
        });
        subLangBox.addView(none);
        Button pick = new Button(this);
        pick.setText("＋ " + tr("dub_pick_sub"));
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_SUB);
        });
        subLangBox.addView(pick);
    }

    private void buildTargetChips() {
        targetBox.removeAllViews();
        TextView q = new TextView(this);
        q.setText(tr("dub_target_q"));
        targetBox.addView(q);
        // v0.13.0: the target is ANY of the 84 catalogue languages,
        // not the 13 of the first dub — one button naming the
        // current choice, a list to pick from (the coverage state
        // of the chosen one is stated in the plan, probed live)
        Button cur = new Button(this);
        java.util.Set<String> packs = VoicePackStore.installedCodes(this);
        VoiceLangs.Row curRow = VoiceLangs.byIso1(voiceRows, targetLang);
        String badge = curRow != null && packs.contains(curRow.packCode)
                ? " · " + tr("voice_state_pack") : "";
        cur.setText("● " + targetTv + badge);
        cur.setOnClickListener(v -> pickTargetLanguage());
        targetBox.addView(cur);
        if (tracks.size() > 1) {
            Button other = new Button(this);
            other.setText(tr("dub_sub_switch"));
            other.setOnClickListener(v -> {
                String[] names = new String[tracks.size()];
                for (int i = 0; i < tracks.size(); i++) {
                    names[i] = foundSubs.get(i).name;
                }
                new AlertDialog.Builder(this)
                        .setTitle(tr("dub_subs_found"))
                        .setItems(names, (d, which) -> {
                            trackIdx = which;
                            pinnedSubLang = null;
                            refreshPlan();
                        }).show();
            });
            targetBox.addView(other);
        }
    }

    /** The 83-language list, for target or subtitle pin. */
    private void pickTargetLanguage() {
        String[] names = new String[voiceRows.size()];
        java.util.Set<String> packs = VoicePackStore.installedCodes(this);
        for (int i = 0; i < voiceRows.size(); i++) {
            VoiceLangs.Row r = voiceRows.get(i);
            names[i] = r.tvName + (!r.packCode.isEmpty() && packs.contains(r.packCode)
                    ? " · " + tr("voice_state_pack") : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(tr("dub_target_q"))
                .setItems(names, (d, which) -> {
                    VoiceLangs.Row r = voiceRows.get(which);
                    targetLang = r.iso1;
                    targetTv = r.tvName;
                    int bt = bestTrack();
                    if (bt >= 0) trackIdx = bt;
                    refreshPlan();
                }).show();
    }

    private void pickSubLanguage() {
        String[] names = new String[voiceRows.size()];
        for (int i = 0; i < voiceRows.size(); i++) {
            names[i] = voiceRows.get(i).tvName;
        }
        new AlertDialog.Builder(this)
                .setTitle(tr("dub_sub_lang_q"))
                .setItems(names, (d, which) -> {
                    pinnedSubLang = voiceRows.get(which).iso1;
                    refreshPlan();
                }).show();
    }

    String langName(String code) {
        for (String[] h : HEARD) if (h[0].equals(code)) return h[1];
        VoiceLangs.Row r = VoiceLangs.byIso1(voiceRows, code);
        if (r != null) return r.tvName;
        return code == null ? "" : code.toUpperCase(java.util.Locale.US);
    }

    // ------------------------------------------------------------ the cook

    /** Show/episode identity from a file name (EpisodeKey's rules). */
    static final class ShowId {
        String key = "", title = "";
        int season = 0, episode = 0;
    }

    static ShowId showOf(String fileName) {
        ShowId id = new ShowId();
        String name = fileName == null ? "" : fileName;
        String stem = DubTrack.stemOf(name);
        int[] span = EpisodeKey.parseSpan(name);
        String title = stem;
        if (span != null) {
            id.season = span[0];
            id.episode = span[1];
            if (span[2] > 0 && span[2] <= stem.length()) {
                String before = stem.substring(0, span[2])
                        .replaceAll("[._\\-\\s]+$", "").replace('_', ' ')
                        .replace('.', ' ').trim();
                if (!before.isEmpty()) title = before;
            }
            if (id.season == 0 && id.episode > 0) id.season = 1;
        }
        id.title = title;
        id.key = "show:" + EpisodeKey.normalize(title);
        return id;
    }

    private void startCook() {
        if (cooking || chosen == null || plan == null || !plan.cooks()) return;
        cooking = true;
        cancelFlag = false;
        cookBtn.setVisibility(View.GONE);
        cancelBtn.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        doneBox.setVisibility(View.GONE);
        final Pick pick = chosen;
        final DubGate.Decision thePlan = plan;
        final int tIdx = trackIdx;
        final String tgt = targetLang;
        final String pin = pinnedSubLang;
        final VoiceResolve.Result theVoice = voice;
        new Thread(() -> cook(pick, thePlan, tIdx, tgt, pin, theVoice),
                "dub-cook").start();
    }

    private void stage(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private void checkCancel() throws Exception {
        if (cancelFlag) throw new Exception(tr("dub_cancelled"));
    }

    private void cook(Pick pick, DubGate.Decision thePlan, int tIdx,
                      String tgt, String pin, VoiceResolve.Result theVoice) {
        long t0 = System.currentTimeMillis();
        try {
            ShowId ident = showOf(pick.name);
            stage(tr("dub_state_reading"));
            File tmpDir = new File(getCacheDir(), "dub-cook");
            tmpDir.mkdirs();
            String decodeUrl = pick.url;
            if (pick.recording) {
                androidx.media3.exoplayer.offline.Download d =
                        Downloads.get(this, pick.url);
                if (d == null) throw new Exception(tr("dub_recording_gone"));
                File outDir = new File(getFilesDir(), "recording-export");
                decodeUrl = RecordingExport.export(this, d, outDir).getAbsolutePath();
            }
            PcmSource src = AudioPcmDecoder.decode(this, decodeUrl, tmpDir);
            checkCancel();

            // ---- the lines: subtitle road first, ear as fallback
            List<CookedSubs.Line> lines = new ArrayList<>();
            String srcLang;
            int loopsDropped = 0;
            if (thePlan.fromSubs()) {
                SubHarvest.Track t = tracks.get(tIdx);
                srcLang = !t.lang.isEmpty() ? t.lang : (pin == null ? "" : pin);
                for (CookedSubs.Line l : t.lines) {
                    CookedSubs.Line c = new CookedSubs.Line();
                    c.startMs = l.startMs;
                    c.endMs = l.endMs;
                    c.orig = l.text;
                    c.text = l.text;
                    c.speaker = l.speaker == null ? "" : l.speaker;
                    lines.add(c);
                }
                if (!srcLang.equals(tgt)) {
                    stage(tr("dub_st_translate"));
                    try (TextMt mt = TextMt.open(this, srcLang + "-" + tgt)) {
                        for (CookedSubs.Line c : lines) {
                            checkCancel();
                            c.text = mt.translate(c.orig);
                        }
                        loopsDropped = mt.droppedLoops();
                    }
                }
            } else {
                stage(tr("dub_st_listen"));
                EnginePackStore.Status ep = EnginePackStore.anyInstalled(this);
                activeEngine = OnnxCookerEngine.open(ep.dir);
                List<CookedSubs.Line> heard = activeEngine.cook(src,
                        (idx, chunkSec, wallSec, soFar) -> stage(
                                tr("dub_st_listen") + " " + soFar.size()
                                        + " " + tr("dub_stat_lines")));
                try { activeEngine.close(); } catch (Throwable ignored) {}
                activeEngine = null;
                srcLang = ep.pair.split("-")[0];
                lines.addAll(heard);
                if (!"en".equals(tgt)) {
                    stage(tr("dub_st_translate"));
                    try (TextMt mt = TextMt.open(this, "en-" + tgt)) {
                        for (CookedSubs.Line c : lines) {
                            checkCancel();
                            String en = c.text;
                            c.text = mt.translate(en);
                        }
                        loopsDropped = mt.droppedLoops();
                    }
                }
            }
            if (lines.isEmpty()) throw new Exception(tr("dub_no_lines"));
            checkCancel();

            // ---- who speaks: casting, saved before a word is spoken
            VoiceBook.Book book = null;
            int nearCount = 0;
            int castCount = 0;
            if (thePlan.plan == DubGate.Plan.SUB_CAST
                    || thePlan.plan == DubGate.Plan.ASR_CAST) {
                stage(tr("dub_st_voices"));
                int[] stats = attributeByEar(src, lines, ident);
                book = VoiceBookStore.load(this, ident.key);
                nearCount = stats[0];
                castCount = stats[1];
            } else if (thePlan.plan == DubGate.Plan.SUB_NAMED) {
                stage(tr("dub_st_cast"));
                book = VoiceBookStore.load(this, ident.key);
                if (book.showTitle == null || book.showTitle.isEmpty()) {
                    book.showTitle = ident.title;
                }
                Map<String, VoiceBook.CastEntry> byName = new LinkedHashMap<>();
                for (VoiceBook.CastEntry e : book.cast) {
                    if (e.name != null && !e.name.isEmpty()) byName.put(e.name, e);
                }
                for (CookedSubs.Line c : lines) {
                    if (c.speaker == null || c.speaker.isEmpty()) continue;
                    String nm = c.speaker;
                    VoiceBook.CastEntry e = byName.get(nm);
                    if (e == null) {
                        e = VoiceBook.enroll(book, "name:" + nm, null, 0);
                        e.name = nm;
                        byName.put(nm, e);
                    }
                    c.speaker = e.speakerKey;
                }
                VoiceBookStore.save(this, book);
                castCount = byName.size();
            }
            checkCancel();

            // ---- give them voices
            stage(tr("dub_st_render"));
            File outDir = new File(new File(getFilesDir(), "dubbed"),
                    ident.key.replaceAll("[^A-Za-z0-9._-]", "_"));
            outDir.mkdirs();
            String stem = DubTrack.stemOf(pick.name);
            File wav = new File(outDir, stem + ".dub-" + tgt + ".wav");
            final TtsDubRenderer.Report[] repBox = new TtsDubRenderer.Report[1];
            final String[] errBox = new String[1];
            CountDownLatch latch = new CountDownLatch(1);
            activeRenderer = new TtsDubRenderer(this);
            TtsDubRenderer.Callback renderCb = new TtsDubRenderer.Callback() {
                @Override public void onProgress(double cookedSec) {
                    stage(tr("dub_st_render") + " " + (int) cookedSec + "s");
                }
                @Override public void onDone(File f, TtsDubRenderer.Report r) {
                    repBox[0] = r;
                    latch.countDown();
                }
                @Override public void onError(String why) {
                    errBox[0] = why;
                    latch.countDown();
                }
            };
            // the voice the plan disclosed is the voice that
            // speaks: the pack when one is in, the phone's own
            // otherwise (NONE never reaches here — the cook
            // button stays off, per the disclosure law)
            if (theVoice != null
                    && theVoice.voice == VoiceResolve.Voice.PACK) {
                activeRenderer.renderPack(lines, book, theVoice.packCode,
                        wav, renderCb);
            } else {
                activeRenderer.render(lines, book, tgt, wav, renderCb);
            }
            latch.await();
            activeRenderer = null;
            if (errBox[0] != null) throw new Exception(errBox[0]);
            TtsDubRenderer.Report rep = repBox[0];
            if (rep != null && rep.lineFit != null) {
                for (int i = 0; i < lines.size() && i < rep.lineFit.length; i++) {
                    lines.get(i).fit = rep.lineFit[i];
                }
            }

            // ---- the cooked files + the shelf record
            File srt = new File(outDir, stem + ".tr-" + tgt + ".srt");
            File json = new File(outDir, stem + ".tr-" + tgt + ".json");
            java.nio.file.Files.write(srt.toPath(),
                    CookedSubs.writeSrt(lines).getBytes("UTF-8"));
            java.nio.file.Files.write(json.toPath(),
                    CookedSubs.writeJson(tgt, lines).getBytes("UTF-8"));
            DubShelf.Entry e = new DubShelf.Entry();
            e.id = ident.key + "|" + stem + "|" + tgt;
            e.cookedAtSec = System.currentTimeMillis() / 1000;
            e.showKey = ident.key;
            e.showTitle = ident.title;
            e.season = ident.season;
            e.episode = ident.episode;
            e.targetLang = tgt;
            e.videoUrl = pick.url;
            e.dubPath = wav.getAbsolutePath();
            e.srtPath = srt.getAbsolutePath();
            e.jsonPath = json.getAbsolutePath();
            e.lineCount = lines.size();
            e.castCount = castCount;
            e.nearCount = nearCount;
            DubShelf.add(this, e);

            long wallSec = (System.currentTimeMillis() - t0) / 1000;
            StringBuilder done = new StringBuilder();
            done.append("✅ ").append(tr("dub_done_title")).append(" — ")
                .append(e.displayName()).append("\n")
                .append(lines.size()).append(" ").append(tr("dub_stat_lines"));
            if (castCount > 0) done.append(" · ").append(castCount)
                    .append(" ").append(tr("dub_stat_cast"));
            if (nearCount > 0) done.append("\n⚠ ").append(nearCount)
                    .append(" ").append(tr("dub_stat_near"));
            if (rep != null && rep.refits > 0) done.append("\n").append(rep.refits)
                    .append(" ").append(tr("dub_stat_refit"));
            if (rep != null && rep.overran > 0) done.append("\n").append(rep.overran)
                    .append(" ").append(tr("dub_stat_over"));
            if (loopsDropped > 0) done.append("\n").append(loopsDropped)
                    .append(" ").append(tr("dub_stat_loops"));
            if (thePlan.plan == DubGate.Plan.SUB_SOLO
                    || thePlan.plan == DubGate.Plan.ASR_SOLO) {
                done.append("\n").append(tr("dub_solo_note"));
            }
            done.append("\n⏱ ").append(wallSec).append("s");
            lastCooked = e;
            String doneText = done.toString();
            runOnUiThread(() -> {
                cooking = false;
                cancelBtn.setVisibility(View.GONE);
                cookBtn.setVisibility(View.VISIBLE);
                progress.setVisibility(View.GONE);
                statusView.setText(tr("dub_done_title"));
                doneView.setText(doneText);
                doneBox.setVisibility(View.VISIBLE);
            });
        } catch (Exception ex) {
            String why = ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage();
            runOnUiThread(() -> {
                cooking = false;
                cancelBtn.setVisibility(View.GONE);
                cookBtn.setVisibility(View.VISIBLE);
                progress.setVisibility(View.GONE);
                statusView.setText(tr("dub_failed") + why);
                Toast.makeText(this, tr("dub_failed") + why,
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * The witness at work: segment the title, embed each solo
     * voice observation, cluster the voices, assign each line its
     * dominant cluster, and settle every cluster into the show's
     * VoiceBook — matched voices fold in, near voices FORK flagged
     * (never silently swapped), new voices enroll. The book is
     * saved before returning, so the casting exists even if the
     * render is stopped afterwards.
     * Returns {nearCount, castCount}.
     */
    private int[] attributeByEar(PcmSource src, List<CookedSubs.Line> lines,
                                 ShowId ident) throws Exception {
        DiarPackStore.Status pack = DiarPackStore.status(this);
        VoiceBook.Book book = VoiceBookStore.load(this, ident.key);
        if (book.showTitle == null || book.showTitle.isEmpty()) {
            book.showTitle = ident.title;
        }
        // the book adopts the pack's measured calibration for its
        // prints (cosine scales belong to the embedding model)
        book.matchMin = pack.vbMatch;
        book.nearMin = pack.vbNear;

        int near = 0;
        try (DiarOnnx diar = DiarOnnx.open(pack.dir)) {
            float[][][] logits = diar.segment(src,
                    (doneW, totalW) -> stage(tr("dub_st_voices")
                            + " " + doneW + "/" + totalW));
            boolean[][][] act = DiarCore.decode(logits);
            List<DiarCore.Observation> obs = DiarCore.events(DiarCore.observations(act));
            for (DiarCore.Observation ob : obs) {
                checkCancel();
                float[] audio = DiarCore.observationAudio(ob,
                        (s, d) -> src.read(s, d));
                if (audio.length >= DiarCore.SAMPLE_RATE / 4) {
                    ob.embedding = diar.embed(audio);
                }
            }
            List<DiarCore.Cluster> clusters =
                    DiarCore.cluster(obs, pack.clusterThreshold);
            // stable keys by how much each cluster speaks
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) order.add(i);
            order.sort((x, y) -> Double.compare(
                    clusters.get(y).totalActiveSec, clusters.get(x).totalActiveSec));
            Map<Integer, VoiceBook.CastEntry> entryByCluster = new LinkedHashMap<>();
            int season = ident.episode > 0 ? Math.max(1, ident.season) : 0;
            for (int ci : order) {
                DiarCore.Cluster c = clusters.get(ci);
                float[] centroid = c.centroid;
                VoiceBook.Match m = VoiceBook.match(book, centroid, season);
                VoiceBook.CastEntry e;
                if (m.kind == VoiceBook.MatchKind.MATCH) {
                    e = m.entry;
                    VoiceBook.foldVoiceprint(e, centroid, 0.2);
                } else {
                    e = VoiceBook.enroll(book,
                            "dub-spk-" + (book.cast.size() + 1), centroid,
                            m.kind == VoiceBook.MatchKind.NEAR ? season : 0);
                    if (m.kind == VoiceBook.MatchKind.NEAR) {
                        e.nearMatch = true;
                        near++;
                    }
                }
                entryByCluster.put(ci, e);
            }
            // subtitle files that NAME their speakers teach the
            // clusters their names: the dominant name among a
            // cluster's lines names its (still nameless) entry
            Map<Integer, Map<String, Integer>> nameVotes = new LinkedHashMap<>();
            String[] lineNames = new String[lines.size()];
            int[] lineCluster = new int[lines.size()];
            for (int li = 0; li < lines.size(); li++) {
                CookedSubs.Line line = lines.get(li);
                lineNames[li] = line.speaker == null ? "" : line.speaker;
                lineCluster[li] = DiarCore.assignLine(clusters,
                        line.startMs / 1000.0, line.endMs / 1000.0);
                if (lineCluster[li] >= 0 && !lineNames[li].isEmpty()) {
                    nameVotes.computeIfAbsent(lineCluster[li],
                            k -> new LinkedHashMap<>())
                            .merge(lineNames[li], 1, Integer::sum);
                }
            }
            for (Map.Entry<Integer, Map<String, Integer>> v : nameVotes.entrySet()) {
                VoiceBook.CastEntry e = entryByCluster.get(v.getKey());
                if (e == null || e.name == null || !e.name.isEmpty()) continue;
                String bestName = "";
                int bestCount = 0;
                for (Map.Entry<String, Integer> nv : v.getValue().entrySet()) {
                    if (nv.getValue() > bestCount) {
                        bestCount = nv.getValue();
                        bestName = nv.getKey();
                    }
                }
                if (!bestName.isEmpty()) e.name = bestName;
            }
            VoiceBookStore.save(this, book);
            for (int li = 0; li < lines.size(); li++) {
                int ci = lineCluster[li];
                if (ci >= 0) {
                    VoiceBook.CastEntry e = entryByCluster.get(ci);
                    if (e != null) {
                        lines.get(li).speaker = e.speakerKey;
                        if (e.nearMatch) lines.get(li).flagged = true;
                    }
                }
            }
            return new int[]{near, entryByCluster.size()};
        }
    }
}
