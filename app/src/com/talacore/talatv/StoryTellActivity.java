package com.talacore.talatv;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The telling (v0.14.0) — a storybook read aloud in Sandy's
 * story room, with the storyboard over it:
 *
 *   - the book is cast ONCE (StoryCast): narrator + a voice per
 *     character, persistent across chapters, from the shelf file;
 *   - voices resolve by the house ladder (v0.13.0): an installed
 *     voice pack speaks first, then the phone's voice, and when
 *     neither covers the tongue the room says so BEFORE anything
 *     is read — no silent substitute, ever;
 *   - where a passage is covered by a FAMILY recording (Grandma
 *     read this part herself), the recording plays and no
 *     synthesized voice speaks that passage;
 *   - the storyboard advances with the chunks: the edition's own
 *     illustrations where the Archive exposes them, else Sandy's
 *     bundled picture cards (a scene from the passage's words +
 *     the speakers' cards, and a character's card never changes
 *     inside a book);
 *   - bilingual hearing, where the shelf holds the same tale in
 *     two tongues: the passage in the first tongue, then in the
 *     second — a per-book toggle, and a plain gap where the
 *     shelf holds only one;
 *   - bedtime shape: no autoplay (the play button is a hand's),
 *     no countdowns; with Bedtime armed at the door, the light
 *     dims and the pace slows as the telling goes on.
 *
 * The speech engine lives here, in the room (the grown-ups'
 * reader keeps its own service): pack voices are synthesized
 * per segment (OnnxVoiceSynth -> AudioTrack), phone voices ride
 * the phone's engine with the cast's pitch and rate.
 */
public class StoryTellActivity extends Activity {

    public static final String X_BOOK = "book";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private StoryData.Row row;
    private StoryCast.Book cast;
    private List<BookChunker.Chunk> chunks = new ArrayList<>();
    private final Map<Integer, List<StoryCast.Segment>> segCache = new HashMap<>();
    private Map<Integer, String[]> voiceIndex = new HashMap<>();

    private StoryData.Row pairRow;
    private StoryCast.Book pairCast;
    private List<BookChunker.Chunk> pairChunks;
    private boolean bilingualOn = false;

    private List<String> illustrations = new ArrayList<>();
    private String prevScene = "";

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private VoiceResolve.Result voice;       // for the book's tongue
    private VoiceResolve.Result pairVoice;   // for the pair's tongue
    private final Map<String, OnnxVoiceSynth> synths = new HashMap<>();
    private AudioTrack packTrack;
    private MediaPlayer recPlayer;

    private boolean ready = false;
    private boolean playing = false;
    private boolean destroyed = false;
    private int gen = 0;
    private int chunk = 0;
    private int segPos = 0;
    private boolean pairHalf = false;   // speaking the pair tongue now
    private boolean packFailed = false;

    private long sessionStartMs = 0;
    private double paceFactor = 1.0;

    private ImageView illusView, sceneView, charView1, charView2;
    private TextView picNote, speakerView, textView, passageView, statusView;
    private Button playBtn, bilingualBtn;
    private TextView bilingualNote;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String bookId = getIntent().getStringExtra(X_BOOK);
        for (StoryData.Row r : StoryFiles.allRows(this)) {
            if (r.id.equals(bookId)) row = r;
        }
        buildUi();
        if (row == null) {
            statusView.setText(LangStore.tr(this, "story_fetch_fail"));
            return;
        }
        List<StoryData.Row> pairs = StoryData.pairOptions(
                StoryFiles.allRows(this), row);
        // the pair tongue: the house's own language first
        String ui = ChannelStore.getUiLanguage(this);
        pairRow = StoryData.bilingualPair(StoryFiles.allRows(this), row,
                ui == null || ui.isEmpty() ? "en" : ui);
        if (pairRow == null && !pairs.isEmpty()) pairRow = pairs.get(0);
        bilingualOn = pairRow != null && StoryFiles.bilingual(this, row.id);
        paintBilingual();

        tts = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady) {
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        main.post(() -> onSegmentDone(id));
                    }
                    @Override public void onError(String id) {
                        main.post(() -> onSegmentDone(id));
                    }
                });
            }
            main.post(this::maybeReady);
        });

        statusView.setText(LangStore.tr(this, "story_loading"));
        exec.execute(() -> {
            try {
                String text = StoryFetch.fetchText(this, row);
                List<BookChunker.Chunk> ch = BookChunker.chunk(text, null);
                StoryCast.Book c = StoryFiles.loadCast(this, row);
                List<String> texts = new ArrayList<>();
                for (BookChunker.Chunk k : ch) texts.add(k.text);
                StoryCast.collectCast(c, texts);
                StoryFiles.assignCards(c);
                StoryFiles.saveCast(this, c);
                List<String> illus = StoryFetch.illustrations(this, row);
                Map<Integer, String[]> vi = StoryFiles.voiceIndex(this, row.id);
                main.post(() -> {
                    if (destroyed) return;
                    chunks = ch;
                    cast = c;
                    illustrations = illus;
                    voiceIndex = vi;
                    chunk = Math.min(StoryFiles.storyPlace(this, row.id),
                            Math.max(0, chunks.size() - 1));
                    maybeReady();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!destroyed) {
                        statusView.setText(LangStore.tr(this, "story_fetch_fail"));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 28, 44));
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        FrameLayout panelBox = new FrameLayout(this);
        panelBox.setBackgroundColor(Color.rgb(14, 16, 26));
        root.addView(panelBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(228)));
        illusView = new ImageView(this);
        illusView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        illusView.setVisibility(View.GONE);
        panelBox.addView(illusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        sceneView = new ImageView(this);
        sceneView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        panelBox.addView(sceneView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        charView1 = cardView(panelBox, Gravity.BOTTOM | Gravity.START);
        charView2 = cardView(panelBox, Gravity.BOTTOM | Gravity.END);

        picNote = new TextView(this);
        picNote.setTextColor(Color.rgb(150, 158, 185));
        picNote.setTextSize(12);
        root.addView(picNote);

        speakerView = new TextView(this);
        speakerView.setTextColor(Color.rgb(255, 224, 150));
        speakerView.setTextSize(20);
        LinearLayout.LayoutParams spl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spl.topMargin = dp(8);
        root.addView(speakerView, spl);

        ScrollView textScroll = new ScrollView(this);
        textView = new TextView(this);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(23);
        textView.setLineSpacing(1.25f, 1f);
        textScroll.addView(textView);
        root.addView(textScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        passageView = new TextView(this);
        passageView.setTextColor(Color.rgb(170, 178, 200));
        passageView.setTextSize(14);
        root.addView(passageView);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = bigButton(LangStore.tr(this, "reader_back"), v -> step(-1));
        playBtn = bigButton(LangStore.tr(this, "reader_play"), v -> togglePlay());
        Button next = bigButton(LangStore.tr(this, "reader_forward"), v -> step(1));
        controls.addView(prev, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(playBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(next, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(controls);

        bilingualBtn = new Button(this);
        bilingualBtn.setTextSize(16);
        bilingualBtn.setAllCaps(false);
        bilingualBtn.setOnClickListener(v -> {
            bilingualOn = !bilingualOn;
            StoryFiles.setBilingual(this, row.id, bilingualOn);
            if (bilingualOn) ensurePairLoaded();
            paintBilingual();
        });
        root.addView(bilingualBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        bilingualNote = new TextView(this);
        bilingualNote.setTextColor(Color.rgb(170, 178, 200));
        bilingualNote.setTextSize(13);
        root.addView(bilingualNote);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(200, 206, 226));
        statusView.setTextSize(15);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        stl.topMargin = dp(8);
        root.addView(statusView, stl);

        setContentView(root);
    }

    private ImageView cardView(FrameLayout box, int gravity) {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(92), dp(92), gravity);
        lp.setMargins(dp(8), 0, dp(8), dp(8));
        box.addView(v, lp);
        return v;
    }

    private Button bigButton(String label, View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(19);
        b.setAllCaps(false);
        b.setMinHeight(dp(64));
        b.setOnClickListener(on);
        return b;
    }

    private void paintBilingual() {
        if (bilingualBtn == null) return;
        if (pairRow == null) {
            bilingualBtn.setVisibility(View.GONE);
            bilingualNote.setVisibility(View.GONE);
            return;
        }
        bilingualBtn.setVisibility(View.VISIBLE);
        bilingualBtn.setText(LangStore.tr(this, "story_bilingual")
                + (bilingualOn ? "  ✓" : ""));
        if (bilingualOn) {
            List<VoiceLangs.Row> vrows = VoiceLangs.load(this);
            VoiceLangs.Row a = VoiceLangs.byIso1(vrows, row.lang);
            VoiceLangs.Row b = VoiceLangs.byIso1(vrows, pairRow.lang);
            bilingualNote.setText(String.format(
                    LangStore.tr(this, "story_bilingual_note"),
                    a != null ? a.tvName : row.lang,
                    b != null ? b.tvName : pairRow.lang));
            bilingualNote.setVisibility(View.VISIBLE);
        } else {
            bilingualNote.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------ load

    private void maybeReady() {
        if (ready || destroyed || cast == null || !ttsReady) return;
        ready = true;
        voice = resolveVoice(row.lang);
        if (pairRow != null) pairVoice = resolveVoice(pairRow.lang);
        paintVoiceStatus();
        showChunk(chunk);
        playBtn.setText(LangStore.tr(this, "reader_play"));
    }

    private VoiceResolve.Result resolveVoice(String iso1) {
        VoiceLangs.Row vr = VoiceLangs.byIso1(VoiceLangs.load(this), iso1);
        Set<String> system = new HashSet<>();
        if (vr != null && !vr.iso1.isEmpty() && ttsReady) {
            try {
                int avail = tts.isLanguageAvailable(new Locale(vr.iso1));
                if (avail >= TextToSpeech.LANG_AVAILABLE) system.add(vr.iso1);
            } catch (Exception ignored) {}
        }
        return VoiceResolve.resolve(vr, VoicePackStore.installedCodes(this), system);
    }

    private void paintVoiceStatus() {
        if (voice == null) return;
        VoiceLangs.Row vr = VoiceLangs.byIso1(VoiceLangs.load(this), row.lang);
        String tongue = vr != null ? vr.tvName : row.lang;
        switch (voice.voice) {
            case PACK:
                statusView.setText(String.format(
                        LangStore.tr(this, "dub_voice_pack"), tongue));
                break;
            case SYSTEM:
                statusView.setText(String.format(
                        LangStore.tr(this, "dub_voice_system"), tongue));
                break;
            default:
                statusView.setText(String.format(
                        LangStore.tr(this, "story_no_voice"), tongue));
                break;
        }
    }

    private boolean voiceUsable(VoiceResolve.Result r) {
        return r != null && r.voice != VoiceResolve.Voice.NONE;
    }

    private void ensurePairLoaded() {
        if (pairRow == null || pairChunks != null) return;
        exec.execute(() -> {
            try {
                String text = StoryFetch.fetchText(this, pairRow);
                List<BookChunker.Chunk> ch = BookChunker.chunk(text, null);
                StoryCast.Book c = StoryFiles.loadCast(this, pairRow);
                List<String> texts = new ArrayList<>();
                for (BookChunker.Chunk k : ch) texts.add(k.text);
                StoryCast.collectCast(c, texts);
                StoryFiles.assignCards(c);
                StoryFiles.saveCast(this, c);
                main.post(() -> {
                    if (destroyed) return;
                    pairChunks = ch;
                    pairCast = c;
                });
            } catch (Exception ignored) {
                main.post(() -> {
                    if (!destroyed) {
                        statusView.setText(LangStore.tr(this, "story_fetch_fail"));
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ show

    private List<StoryCast.Segment> segmentsOf(int i) {
        List<StoryCast.Segment> segs = segCache.get(i);
        if (segs == null) {
            segs = StoryCast.attribute(cast, chunks.get(i).text);
            segCache.put(i, segs);
        }
        return segs;
    }

    private void showChunk(int i) {
        if (chunks.isEmpty()) return;
        chunk = Math.max(0, Math.min(i, chunks.size() - 1));
        StoryFiles.setStoryPlace(this, row.id, chunk);
        List<StoryCast.Segment> segs = segmentsOf(chunk);
        textView.setText(chunks.get(chunk).text);
        speakerView.setText(segs.isEmpty() ? "" : speakerName(segs.get(0).speaker));
        passageView.setText(String.format(
                LangStore.tr(this, "story_passage_of"), chunk + 1, chunks.size()));
        paintPanel(segs);
    }

    private String speakerName(String speaker) {
        if (speaker == null || speaker.equals(StoryCast.NARRATOR)) {
            List<VoiceLangs.Row> vrows = VoiceLangs.load(this);
            return "";
        }
        return speaker;
    }

    private void paintPanel(List<StoryCast.Segment> segs) {
        if (!illustrations.isEmpty()) {
            int idx = Math.min(illustrations.size() - 1,
                    chunk * illustrations.size() / Math.max(1, chunks.size()));
            illusView.setVisibility(View.VISIBLE);
            sceneView.setVisibility(View.GONE);
            charView1.setVisibility(View.GONE);
            charView2.setVisibility(View.GONE);
            ImageCache.load(this, illustrations.get(idx), illusView);
            picNote.setText(LangStore.tr(this, "story_illustrated_note"));
            return;
        }
        illusView.setVisibility(View.GONE);
        sceneView.setVisibility(View.VISIBLE);
        List<String> cards = new ArrayList<>();
        StringBuilder passage = new StringBuilder();
        for (StoryCast.Segment s : segs) {
            passage.append(s.text).append(' ');
            StoryCast.Entry e = cast.byName(s.speaker);
            if (e != null && !e.cardId.isEmpty()) cards.add(e.cardId);
        }
        StoryPanels.Choice choice = StoryPanels.choose(
                prevScene, passage.toString(), cards);
        prevScene = choice.sceneId;
        sceneView.setImageResource(resId(StoryPanels.drawableName(choice.sceneId)));
        ImageView[] views = {charView1, charView2};
        for (int k = 0; k < views.length; k++) {
            if (k < choice.charIds.size()) {
                views[k].setVisibility(View.VISIBLE);
                views[k].setImageResource(
                        resId(StoryPanels.drawableName(choice.charIds.get(k))));
            } else {
                views[k].setVisibility(View.GONE);
            }
        }
        picNote.setText(LangStore.tr(this, "story_cards_note"));
    }

    private int resId(String drawable) {
        return getResources().getIdentifier(drawable, "drawable", getPackageName());
    }

    // ------------------------------------------------------------ play

    private void togglePlay() {
        if (!ready) return;
        if (playing) {
            pauseTelling();
        } else {
            if (!voiceUsable(voice)) {
                paintVoiceStatus();   // the gap, said before any reading
                return;
            }
            playing = true;
            if (sessionStartMs == 0) sessionStartMs = System.currentTimeMillis();
            playBtn.setText(LangStore.tr(this, "reader_pause"));
            speakCurrent();
        }
    }

    private void pauseTelling() {
        playing = false;
        gen++;
        stopSpeech();
        playBtn.setText(LangStore.tr(this, "reader_play"));
    }

    private void stopSpeech() {
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        if (packTrack != null) {
            try { packTrack.stop(); packTrack.release(); } catch (Exception ignored) {}
            packTrack = null;
        }
        if (recPlayer != null) {
            try { recPlayer.stop(); recPlayer.release(); } catch (Exception ignored) {}
            recPlayer = null;
        }
    }

    private void step(int dir) {
        boolean was = playing;
        pauseTelling();
        showChunk(chunk + dir);
        if (was) togglePlay();
    }

    /** Speak the current chunk from its first segment. */
    private void speakCurrent() {
        if (!playing || destroyed) return;
        pairHalf = false;
        String[] rec = voiceIndex.get(chunk);
        if (rec != null && rec.length >= 2) {
            File f = new File(StoryFiles.bookVoicesDir(this, row.id), rec[1]);
            if (f.isFile()) {
                speakerView.setText(rec[0]);
                playRecording(f, () -> advance());
                return;
            }
        }
        segPos = 0;
        speakSegment();
    }

    private void speakSegment() {
        if (!playing || destroyed) return;
        StoryCast.Book book = pairHalf ? pairCast : cast;
        List<BookChunker.Chunk> bookChunks = pairHalf ? pairChunks : chunks;
        int bookChunk = pairHalf ? pairIndex() : chunk;
        if (book == null || bookChunks == null || bookChunk >= bookChunks.size()) {
            onChunkSpoken();
            return;
        }
        List<StoryCast.Segment> segs = pairHalf
                ? StoryCast.attribute(book, bookChunks.get(bookChunk).text)
                : segmentsOf(chunk);
        if (segPos >= segs.size()) { onChunkSpoken(); return; }
        StoryCast.Segment seg = segs.get(segPos);
        speakerView.setText(speakerName(seg.speaker));
        StoryCast.Entry entry = book.byName(seg.speaker);
        if (entry == null) entry = book.narrator();
        VoiceResolve.Result vr = pairHalf ? pairVoice : voice;
        double pace = paceFactor * windPace();
        if (vr != null && vr.voice == VoiceResolve.Voice.PACK && !packFailed) {
            speakPack(seg.text, entry, vr.packCode, pace);
        } else {
            speakTts(seg.text, entry, pairHalf ? pairRow.lang : row.lang, pace);
        }
    }

    private int pairIndex() {
        if (pairChunks == null || pairChunks.isEmpty()) return 0;
        return Math.min(pairChunks.size() - 1,
                chunk * pairChunks.size() / Math.max(1, chunks.size()));
    }

    private void speakTts(String text, StoryCast.Entry entry, String lang,
                          double pace) {
        if (tts == null || !ttsReady) { onSegmentDone(""); return; }
        try {
            Locale loc = new Locale(lang == null ? "en" : lang);
            tts.setLanguage(loc);
            tts.setPitch((float) entry.pitch);
            tts.setSpeechRate((float) Math.max(0.3, entry.rate * pace));
        } catch (Exception ignored) {}
        String id = "s" + chunk + "_" + (pairHalf ? "p" : "a") + segPos;
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
        if (r == TextToSpeech.ERROR) onSegmentDone(id);
    }

    private void speakPack(String text, StoryCast.Entry entry, String code,
                           double pace) {
        final int myGen = ++gen;
        final double rate = Math.max(0.3, entry.rate * pace);
        final double pitch = entry.pitch;
        exec.execute(() -> {
            try {
                OnnxVoiceSynth synth = synths.get(code);
                if (synth == null) {
                    synth = OnnxVoiceSynth.open(VoicePackStore.packDir(this, code));
                    synths.put(code, synth);
                }
                float[] pcm = synth.synth(text, 1.0 / rate);
                pcm = OnnxVoiceSynth.pitchShift(pcm, pitch);
                if (myGen != gen || destroyed) return;
                if (pcm.length == 0) throw new Exception("the pack said nothing");
                short[] s16 = new short[pcm.length];
                for (int i = 0; i < pcm.length; i++) {
                    float v = Math.max(-1f, Math.min(1f, pcm[i]));
                    s16[i] = (short) (v * 32767);
                }
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(OnnxVoiceSynth.SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(s16.length * 2,
                                AudioTrack.getMinBufferSize(
                                        OnnxVoiceSynth.SAMPLE_RATE,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT)))
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();
                track.write(s16, 0, s16.length);
                if (myGen != gen) { track.release(); return; }
                if (packTrack != null) {
                    try { packTrack.stop(); packTrack.release(); }
                    catch (Exception ignored) {}
                }
                packTrack = track;
                track.play();
                while (myGen == gen && !destroyed
                        && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    if (track.getPlaybackHeadPosition() >= s16.length) break;
                    try { Thread.sleep(90); }
                    catch (InterruptedException e) { return; }
                }
                boolean finished = myGen == gen && !destroyed
                        && track.getPlaybackHeadPosition() >= s16.length;
                if (finished) main.post(() -> onSegmentDone("pack"));
            } catch (Exception e) {
                if (myGen != gen || destroyed) return;
                main.post(() -> {
                    // the pack stumbled: say so, and the phone
                    // voice carries the rest of the book
                    packFailed = true;
                    statusView.setText(LangStore.tr(this, "reader_voice_pack_fail"));
                    speakSegment();
                });
            }
        });
    }

    private void playRecording(File f, Runnable onDone) {
        stopSpeech();
        try {
            recPlayer = new MediaPlayer();
            recPlayer.setDataSource(f.getAbsolutePath());
            recPlayer.setOnCompletionListener(mp -> {
                if (recPlayer != null) {
                    recPlayer.release();
                    recPlayer = null;
                }
                if (playing && !destroyed) onDone.run();
            });
            recPlayer.setOnErrorListener((mp, w, e) -> {
                if (recPlayer != null) {
                    recPlayer.release();
                    recPlayer = null;
                }
                if (playing && !destroyed) onDone.run();
                return true;
            });
            recPlayer.prepare();
            recPlayer.start();
        } catch (Exception e) {
            if (playing && !destroyed) onDone.run();
        }
    }

    private void onSegmentDone(String id) {
        if (!playing || destroyed) return;
        segPos++;
        speakSegment();
    }

    /** One half (one tongue) of the current chunk is fully spoken. */
    private void onChunkSpoken() {
        if (bilingualOn && !pairHalf && pairChunks != null
                && voiceUsable(pairVoice)) {
            pairHalf = true;
            segPos = 0;
            int pi = pairIndex();
            textView.setText(pairChunks.get(pi).text);
            speakSegment();
            return;
        }
        advance();
    }

    private void advance() {
        if (chunk + 1 >= chunks.size()) {
            pauseTelling();
            statusView.setText(LangStore.tr(this, "reader_finished"));
            showChunk(0);
            return;
        }
        showChunk(chunk + 1);
        if (playing) speakCurrent();
    }

    // ------------------------------------------------------------ bedtime

    /** The wind-down: as a bedtime telling goes on, the pace
     *  eases in steps. Measured from the session's own start —
     *  no promised numbers, just the shape. */
    private double windPace() {
        if (!StoryFiles.bedtime(this) || sessionStartMs == 0) return 1.0;
        long mins = (System.currentTimeMillis() - sessionStartMs) / 60_000;
        int steps = (int) Math.min(5, mins / 2);
        double pace = 1.0 - 0.04 * steps;
        if (steps > 0) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = Math.max(0.2f, 0.85f - 0.13f * steps);
            getWindow().setAttributes(lp);
        }
        return pace;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        gen++;
        stopSpeech();
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) {}
        }
        for (OnnxVoiceSynth s : synths.values()) {
            try { s.close(); } catch (Exception ignored) {}
        }
        exec.shutdownNow();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
