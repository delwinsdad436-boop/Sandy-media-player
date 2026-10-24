package com.talacore.talatv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The reading voice (v0.8.0) — a foreground service that owns the
 * phone's TTS and reads the book chunk by chunk, screen on or off,
 * until she stops it or the book ends. This is the recliner
 * requirement made literal: a book that dies when the pocket
 * closes is broken, so the reading runs at foreground-service /
 * media-session grade — notification controls, lock-screen
 * controls, a wake lock while speaking, and the place saved on
 * every chunk she hears.
 *
 * The session's shape is the pure ReadingSession state machine;
 * this class is its Android body. Failures speak: no voice for the
 * book's language, a PDF that needs its text pulled first, a
 * translator that stumbled — each is a state line (and, where a
 * voice exists to say it, a spoken line), never a silent stop.
 */
public final class ReaderService extends Service {

    private static final String CHAN = "reader";
    private static final int NOTIF_ID = 42;
    private static final int LEAD = 4; // translated chunks cooked ahead

    public static final String A_PLAY = "com.talacore.talatv.reader.PLAY";
    public static final String A_PAUSE = "com.talacore.talatv.reader.PAUSE";
    public static final String A_RESUME = "com.talacore.talatv.reader.RESUME";
    public static final String A_NEXT = "com.talacore.talatv.reader.NEXT";
    public static final String A_PREV = "com.talacore.talatv.reader.PREV";
    public static final String A_STOP = "com.talacore.talatv.reader.STOP";
    public static final String A_RATE = "com.talacore.talatv.reader.RATE";
    public static final String A_VOICE = "com.talacore.talatv.reader.VOICE";
    public static final String X_BOOK = "book";
    public static final String X_FROM = "from";
    public static final String X_MODE = "mode";
    public static final String X_RATE = "rate";
    public static final String X_VOICE = "voice";

    // ------------------------------------------------ snapshot (for the screen)

    public static final class Snapshot {
        public final ReadingSession.State state;
        public final String bookId;
        public final String title;
        public final String chapter;
        public final int chunk;
        public final int totalChunks;
        public final String mode;
        /** The last plain-words state line ("" when nothing to say). */
        public final String spoken;
        public final String bookLang;

        Snapshot(ReadingSession.State s, String b, String t, String ch, int c,
                 int total, String m, String sp, String lang) {
            state = s; bookId = b; title = t; chapter = ch; chunk = c;
            totalChunks = total; mode = m; spoken = sp; bookLang = lang;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(
            ReadingSession.State.IDLE, "", "", "", 0, 0,
            ReaderStore.MODE_ORIGINAL, "", "");
    private static final CopyOnWriteArrayList<Runnable> LISTENERS =
            new CopyOnWriteArrayList<>();

    public static Snapshot snapshot() { return snapshot; }

    public static void addListener(Runnable r) {
        if (r != null && !LISTENERS.contains(r)) LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) { LISTENERS.remove(r); }

    private static void postSnapshot(Snapshot s) {
        snapshot = s;
        for (Runnable r : LISTENERS) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------ command helpers

    public static void send(Context ctx, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    public static Intent playIntent(Context ctx, String bookId, int fromChunk,
                                    String mode) {
        return new Intent(ctx, ReaderService.class).setAction(A_PLAY)
                .putExtra(X_BOOK, bookId).putExtra(X_FROM, fromChunk)
                .putExtra(X_MODE, mode);
    }

    public static Intent cmd(Context ctx, String action) {
        return new Intent(ctx, ReaderService.class).setAction(action);
    }

    // ------------------------------------------------ service body

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService mtWorker = Executors.newSingleThreadExecutor();
    private final ReadingSession session = new ReadingSession();
    private final Map<Integer, String> translated = new ConcurrentHashMap<>();

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private MediaSession mediaSession;
    private PowerManager.WakeLock wakeLock;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean focusHeld = false;

    private BookStore.Book book;
    private List<BookChunker.Chunk> chunks;
    private int totalChars = 0;
    private String bookLang = BookLang.OTHER;
    private String mode = ReaderStore.MODE_ORIGINAL;
    private BookTranslator translator;
    private String spokenLine = "";
    private boolean loadFailed = false;
    private String pendingSay; // a line to speak once TTS is ready, then stop

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHAN, "Book reader", NotificationManager.IMPORTANCE_LOW));
        }
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        mediaSession = new MediaSession(this, "SandyReader");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { doResume(); }
            @Override public void onPause() { doPause(); }
            @Override public void onSkipToNext() { doStep(1); }
            @Override public void onSkipToPrevious() { doStep(-1); }
            @Override public void onStop() { doStop(); }
        });
        mediaSession.setActive(true);
        tts = new TextToSpeech(this, status -> main.post(() -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                applyVoiceAndRate();
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {
                        main.post(() -> onChunkStart(id));
                    }
                    @Override public void onDone(String id) {
                        main.post(() -> onChunkDone(id));
                    }
                    @Override public void onError(String id) {
                        main.post(() -> onChunkError(id));
                    }
                });
                if (pendingSay != null) {
                    String say = pendingSay;
                    pendingSay = null;
                    speakOneOff(say);
                } else if (session.state() == ReadingSession.State.WAITING
                        && chunks != null && !loadFailed) {
                    // The book landed before the voice did: set the
                    // language now that there's a voice to set it on.
                    if (configureTtsLanguage()) speakCurrent();
                }
            } else {
                spokenLine = "The reading voice couldn't start on this phone.";
                publish();
                stopSelf();
            }
        }));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground("Opening the book…");
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String a = intent.getAction();
        switch (a) {
            case A_PLAY: {
                String bookId = intent.getStringExtra(X_BOOK);
                int from = intent.getIntExtra(X_FROM, 0);
                String m = intent.getStringExtra(X_MODE);
                startBook(bookId, from, m == null ? ReaderStore.MODE_ORIGINAL : m);
                break;
            }
            case A_PAUSE: doPause(); break;
            case A_RESUME: doResume(); break;
            case A_NEXT: doStep(1); break;
            case A_PREV: doStep(-1); break;
            case A_STOP: doStop(); break;
            case A_RATE: {
                float r = intent.getFloatExtra(X_RATE, ReaderStore.rate(this));
                ReaderStore.setRate(this, r);
                if (ttsReady) tts.setSpeechRate(ReaderStore.rate(this));
                break;
            }
            case A_VOICE: {
                String v = intent.getStringExtra(X_VOICE);
                ReaderStore.setVoiceName(this, v == null ? "" : v);
                applyVoiceAndRate();
                break;
            }
            default: break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Throwable ignored) {}
        try { if (translator != null) translator.close(); } catch (Throwable ignored) {}
        releaseWake();
        abandonFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        worker.shutdownNow();
        mtWorker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------ book loading

    private void startBook(String bookId, int fromChunk, String wantMode) {
        if (tts != null) tts.stop();
        session.stop();
        translated.clear();
        loadFailed = false;
        spokenLine = "";
        mode = wantMode;
        book = bookId == null ? null : BookStore.byId(this, bookId);
        if (book == null) {
            spokenLine = "That book isn't on the shelf anymore.";
            session.start(1, 0);
            session.starve();
            publish();
            sayThenStop(spokenLine);
            return;
        }
        publish();
        worker.execute(() -> {
            byte[] bytes = BookStore.readAll(this, book);
            BookText.Result res = bytes == null ? null
                    : BookText.extract(bytes, book.title);
            main.post(() -> onBookLoaded(res, fromChunk));
        });
    }

    private void onBookLoaded(BookText.Result res, int fromChunk) {
        if (res == null || !res.ok) {
            String reason = res == null ? BookText.NO_TEXT_FOUND : res.failReason;
            if (BookText.SCANNED_NO_TEXT.equals(reason)) {
                spokenLine = LangStore.tr(this, "book_scanned");
            } else if (BookText.UNSUPPORTED_KIND.equals(reason)) {
                spokenLine = LangStore.tr(this, "book_unsupported");
            } else {
                spokenLine = "That book's words couldn't be read on this phone.";
            }
            loadFailed = true;
            session.start(1, 0);
            session.starve();
            publish();
            sayThenStop(spokenLine);
            return;
        }
        chunks = BookChunker.chunk(res.text, res.chapters);
        totalChars = res.text.length();
        bookLang = BookLang.detect(res.text);
        if (chunks.isEmpty()) {
            spokenLine = "That book's words couldn't be read on this phone.";
            loadFailed = true;
            session.start(1, 0);
            session.starve();
            publish();
            sayThenStop(spokenLine);
            return;
        }
        // Translated mode is ja->en only, and only with the pack.
        if (ReaderStore.MODE_TRANSLATED.equals(mode)) {
            if (!BookLang.JA.equals(bookLang)) {
                spokenLine = "That book isn't Japanese, so it's read as written.";
                mode = ReaderStore.MODE_ORIGINAL;
            } else if (!BookTranslator.available(this)) {
                spokenLine = "Reading in English needs the translation engine pack.";
                mode = ReaderStore.MODE_ORIGINAL;
            } else {
                try {
                    if (translator != null) translator.close();
                    translator = BookTranslator.open(this);
                    translator.beginBook();
                } catch (Throwable t) {
                    CrashGuard.log(this, "BookTranslator.open", t);
                    spokenLine = "The translation engine wouldn't start — reading as written.";
                    mode = ReaderStore.MODE_ORIGINAL;
                    translator = null;
                }
            }
        }
        if (!ttsReady) {
            // Park in WAITING; the TTS-ready callback picks the
            // session up and speakCurrent() starts the first chunk.
            session.start(chunks.size(), fromChunk);
            session.starve();
            savePlace();
            publish();
            return;
        }
        if (!configureTtsLanguage()) return; // line set + stop inside
        session.start(chunks.size(), fromChunk);
        savePlace();
        publish();
        speakCurrent();
    }

    // ---- v0.13.0: the pack voice for read-aloud. When a Sandy
    // voice pack covers the reading language, chunks are spoken
    // by the pack (OnnxVoiceSynth -> AudioTrack) through the same
    // session machinery; the phone's voice is the fallback, and a
    // pack failure is spoken before the fallback takes over.
    private String packCodeWanted = "";
    private OnnxVoiceSynth packSynth;
    private android.media.AudioTrack packTrack;
    private volatile int packGen = 0;

    private VoiceLangs.Row readingRow() {
        String iso1;
        if (ReaderStore.MODE_TRANSLATED.equals(mode)) iso1 = "en";
        else if (BookLang.JA.equals(bookLang)) iso1 = "ja";
        else if (BookLang.KO.equals(bookLang)) iso1 = "ko";
        else if (BookLang.ZH.equals(bookLang)) iso1 = "zh";
        else {
            String ui = ChannelStore.getUiLanguage(this);
            iso1 = ui == null || ui.isEmpty() ? "en" : ui;
        }
        return VoiceLangs.byIso1(VoiceLangs.load(this), iso1);
    }

    /** Set the TTS language for the current mode/book. False = failed (spoken). */
    private boolean configureTtsLanguage() {
        // v0.13.0: a pack that covers the reading language speaks
        // first; the TTS configuration below still runs so the
        // fallback voice is ready — but a missing TTS language no
        // longer stops a reading the pack can carry.
        VoiceLangs.Row row = readingRow();
        packCodeWanted = row != null
                && VoicePackStore.installedCodes(this).contains(row.packCode)
                ? row.packCode : "";
        Locale loc;
        if (ReaderStore.MODE_TRANSLATED.equals(mode)) {
            loc = new Locale("en");
        } else if (BookLang.JA.equals(bookLang)) {
            loc = Locale.JAPANESE;
        } else if (BookLang.KO.equals(bookLang)) {
            loc = Locale.KOREAN;
        } else if (BookLang.ZH.equals(bookLang)) {
            loc = Locale.SIMPLIFIED_CHINESE;
        } else {
            String ui = ChannelStore.getUiLanguage(this);
            loc = ui == null || ui.isEmpty() ? Locale.getDefault()
                    : Locale.forLanguageTag(ui);
        }
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (!packCodeWanted.isEmpty()) {
                // the pack carries this language even though the
                // phone's engine can't — read on with the pack
                try { tts.setLanguage(Locale.getDefault()); } catch (Throwable ignored) {}
                return true;
            }
            spokenLine = "This phone has no reading voice for that language.";
            publish();
            // Say so in the default voice if we can, then stop.
            try { tts.setLanguage(Locale.getDefault()); } catch (Throwable ignored) {}
            sayThenStop(spokenLine);
            return false;
        }
        applyVoiceAndRate();
        return true;
    }

    private void applyVoiceAndRate() {
        if (!ttsReady || tts == null) return;
        try {
            tts.setSpeechRate(ReaderStore.rate(this));
            String want = ReaderStore.voiceName(this);
            if (want != null && !want.isEmpty()) {
                for (Voice v : tts.getVoices()) {
                    if (want.equals(v.getName())) { tts.setVoice(v); break; }
                }
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------ speaking flow

    private void speakCurrent() {
        if (chunks == null || session.state() == ReadingSession.State.IDLE
                || session.state() == ReadingSession.State.STOPPED
                || session.state() == ReadingSession.State.FINISHED) return;
        int i = session.currentChunk();
        if (i >= chunks.size()) { finishBook(); return; }
        if (!ttsReady) { session.starve(); publish(); return; }
        if (ReaderStore.MODE_TRANSLATED.equals(mode) && translator != null) {
            String t = translated.get(i);
            if (t == null) {
                session.starve();
                publish();
                translateAhead(i);
                return;
            }
            if (t.isEmpty()) {
                // a loop the guard dropped: step past it, count stays spoken
                spokenLine = droppedLine();
                session.chunkStarted(i);
                advance();
                return;
            }
            speakText(t, i);
            return;
        }
        speakText(chunks.get(i).text, i);
    }

    private void speakText(String text, int i) {
        if (!packCodeWanted.isEmpty()) { speakPackText(text, i); return; }
        speakTextTts(text, i);
    }

    private void speakTextTts(String text, int i) {
        grabWake();
        grabFocus();
        session.chunkStarted(i);
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "c" + i);
        if (r == TextToSpeech.ERROR) {
            spokenLine = "The reading voice stumbled — tap play to try again.";
            session.starve();
        }
        publish();
    }

    /** Speak one chunk with the voice pack: synthesize on a
     *  worker, play through an AudioTrack, and let the marker
     *  advance the session exactly as a finished utterance does.
     *  Any failure speaks the fallback line and hands the rest of
     *  the book to the phone's voice. */
    private void speakPackText(String text, int i) {
        grabWake();
        grabFocus();
        session.chunkStarted(i);
        savePlace();
        if (ReaderStore.MODE_TRANSLATED.equals(mode)) translateAhead(i + 1);
        publish();
        final int gen = ++packGen;
        final String code = packCodeWanted;
        new Thread(() -> {
            try {
                if (packSynth == null) {
                    packSynth = OnnxVoiceSynth.open(
                            VoicePackStore.packDir(this, code));
                }
                float[] pcm = packSynth.synth(text,
                        1.0 / Math.max(0.25, ReaderStore.rate(this)));
                if (gen != packGen) return;
                if (pcm.length == 0) throw new Exception("the pack said nothing");
                short[] s16 = floatToShort(pcm);
                android.media.AudioTrack track = new android.media.AudioTrack.Builder()
                        .setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new android.media.AudioFormat.Builder()
                                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(OnnxVoiceSynth.SAMPLE_RATE)
                                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(s16.length * 2,
                                android.media.AudioTrack.getMinBufferSize(
                                        OnnxVoiceSynth.SAMPLE_RATE,
                                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                                        android.media.AudioFormat.ENCODING_PCM_16BIT)))
                        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                        .build();
                track.write(s16, 0, s16.length);
                if (gen != packGen) { track.release(); return; }
                stopPackTrack();
                packTrack = track;
                track.play();
                // completion by polling the play head on this
                // worker thread (no Looper assumptions): when the
                // head passes the last frame, the chunk is done —
                // the same signal a finished utterance gives
                while (gen == packGen
                        && track.getPlayState()
                                == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    if (track.getPlaybackHeadPosition() >= s16.length) break;
                    try { Thread.sleep(90); } catch (InterruptedException e) {
                        return;
                    }
                }
                boolean finished = gen == packGen
                        && track.getPlaybackHeadPosition() >= s16.length;
                stopPackTrack();
                if (finished) {
                    main.post(() -> {
                        if (gen == packGen) onChunkDone("c" + i);
                    });
                }
            } catch (Exception e) {
                if (gen != packGen) return;
                main.post(() -> packFallback(text, i));
            }
        }, "reader-pack").start();
    }

    private static short[] floatToShort(float[] in) {
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i++) {
            float v = Math.max(-1f, Math.min(1f, in[i]));
            out[i] = (short) Math.round(v * 32767f);
        }
        return out;
    }

    private void stopPackTrack() {
        if (packTrack != null) {
            try { packTrack.pause(); packTrack.flush(); packTrack.release(); }
            catch (Throwable ignored) {}
            packTrack = null;
        }
    }

    /** The pack failed mid-book: say so, then the phone's voice
     *  reads this chunk and the rest. */
    private void packFallback(String text, int i) {
        spokenLine = LangStore.tr(this, "reader_voice_pack_fail");
        packCodeWanted = "";
        if (packSynth != null) {
            try { packSynth.close(); } catch (Throwable ignored) {}
            packSynth = null;
        }
        publish();
        speakTextTts(text, i);
    }

    private void speakOneOff(String line) {
        try {
            tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "line");
        } catch (Throwable ignored) {}
        main.postDelayed(this::stopSelf, 3500);
    }

    private void sayThenStop(String line) {
        if (ttsReady) speakOneOff(line);
        else {
            pendingSay = line;
            main.postDelayed(() -> { if (!ttsReady) stopSelf(); }, 6000);
        }
    }

    private void onChunkStart(String id) {
        Integer i = chunkOf(id);
        if (i == null) return;
        session.chunkStarted(i);
        savePlace();
        publish();
        if (ReaderStore.MODE_TRANSLATED.equals(mode)) translateAhead(i + 1);
    }

    private void onChunkDone(String id) {
        Integer i = chunkOf(id);
        if (i == null) return;
        if (session.state() == ReadingSession.State.PAUSED
                || session.state() == ReadingSession.State.STOPPED) return;
        advance();
    }

    private void onChunkError(String id) {
        if (chunkOf(id) == null) return;
        spokenLine = "The reading voice stumbled — tap play to try again.";
        session.starve();
        releaseWake();
        publish();
    }

    private void advance() {
        int next = session.currentChunk() + 1;
        if (chunks == null || next >= chunks.size()) { finishBook(); return; }
        session.seek(next);
        speakCurrent();
    }

    private void finishBook() {
        session.finish();
        if (book != null) {
            ReaderStore.markFinished(this, book.id, true);
            savePlace();
        }
        spokenLine = LangStore.tr(this, "reader_finished");
        releaseWake();
        abandonFocus();
        publish();
        try {
            if (ttsReady) tts.speak(spokenLine, TextToSpeech.QUEUE_ADD, null, "end");
        } catch (Throwable ignored) {}
        main.postDelayed(this::stopSelf, 2500);
    }

    private Integer chunkOf(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith("c")) return null;
        try { return Integer.parseInt(utteranceId.substring(1)); }
        catch (NumberFormatException e) { return null; }
    }

    private String droppedLine() {
        int d = translator == null ? 0 : translator.droppedLoops();
        return d > 0 ? d + (d == 1 ? " piece" : " pieces")
                + " repeated and " + (d == 1 ? "was" : "were") + " skipped." : "";
    }

    // ------------------------------------------------ translation lead

    private void translateAhead(int from) {
        if (translator == null || chunks == null) return;
        mtWorker.execute(() -> {
            for (int i = from; i < Math.min(from + LEAD, chunks.size()); i++) {
                if (translated.containsKey(i)) continue;
                try {
                    String t = translator.translateChunk(chunks.get(i).text);
                    translated.put(i, t == null ? "" : t);
                } catch (Throwable e) {
                    CrashGuard.log(this, "BookTranslator chunk " + i, e);
                    translated.put(i, "");
                    main.post(() -> {
                        spokenLine = "The translator stumbled — tap play to try again.";
                        session.starve();
                        publish();
                    });
                    return;
                }
                final int doneIdx = i;
                main.post(() -> {
                    if (session.state() == ReadingSession.State.WAITING
                            && session.currentChunk() == doneIdx) speakCurrent();
                    publish();
                });
            }
        });
    }

    // ------------------------------------------------ controls

    private void doPause() {
        if (tts != null) tts.stop();
        packGen++;
        stopPackTrack();
        if (session.pause()) {
            savePlace();
            releaseWake();
        }
        publish();
    }

    private void doResume() {
        if (loadFailed) { stopSelf(); return; }
        if (session.resume() || session.state() == ReadingSession.State.WAITING
                || session.isSpeaking()) {
            speakCurrent();
        }
        publish();
    }

    private void doStep(int delta) {
        if (chunks == null || !session.isActive()) return;
        if (tts != null) tts.stop();
        packGen++;
        stopPackTrack();
        int to = Math.max(0, Math.min(session.currentChunk() + delta, chunks.size() - 1));
        session.seek(to);
        savePlace();
        if (session.isSpeaking() || session.state() == ReadingSession.State.WAITING) {
            speakCurrent();
        }
        publish();
    }

    private void doStop() {
        if (tts != null) tts.stop();
        packGen++;
        stopPackTrack();
        session.stop();
        savePlace();
        releaseWake();
        abandonFocus();
        publish();
        stopSelf();
    }

    // ------------------------------------------------ place + system

    private void savePlace() {
        if (book == null || chunks == null) return;
        int i = session.currentChunk();
        ReaderStore.save(this, book.id, i,
                i < chunks.size() ? chunks.get(i).startChar : 0,
                chunks.size(),
                BookChunker.chapterAt(chunks, i), mode);
    }

    private void grabWake() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sandy:reader");
        wakeLock.setReferenceCounted(false);
        try { wakeLock.acquire(6 * 60 * 60 * 1000L); } catch (Throwable ignored) {}
    }

    private void releaseWake() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
        catch (Throwable ignored) {}
        wakeLock = null;
    }

    private void grabFocus() {
        if (audioManager == null || focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(fc -> {
                            if (fc == AudioManager.AUDIOFOCUS_LOSS) main.post(this::doPause);
                        })
                        .build();
                focusHeld = audioManager.requestAudioFocus(focusRequest)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                //noinspection deprecation
                focusHeld = audioManager.requestAudioFocus(fc -> {
                            if (fc == AudioManager.AUDIOFOCUS_LOSS) main.post(this::doPause);
                        }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Throwable ignored) {}
    }

    private void abandonFocus() {
        if (audioManager == null || !focusHeld) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            } else {
                //noinspection deprecation
                audioManager.abandonAudioFocus(null);
            }
        } catch (Throwable ignored) {}
        focusHeld = false;
    }

    // ------------------------------------------------ notification

    private void publish() {
        ReadingSession.State st = session.state();
        String chapter = chunks == null ? ""
                : BookChunker.chapterAt(chunks, session.currentChunk());
        postSnapshot(new Snapshot(st, book == null ? "" : book.id,
                book == null ? "" : book.title, chapter,
                session.currentChunk(),
                chunks == null ? 0 : chunks.size(), mode,
                spokenLine, bookLang));
        if (st != ReadingSession.State.IDLE) goForeground(stateLine());
        updateMediaState();
    }

    private String stateLine() {
        if (!spokenLine.isEmpty()) return spokenLine;
        switch (session.state()) {
            case SPEAKING: return "Reading…";
            case PAUSED: return "Paused — your place is kept.";
            case WAITING: return "Getting the words ready…";
            case FINISHED: return LangStore.tr(this, "reader_finished");
            default: return "Stopped — your place is kept.";
        }
    }

    private void goForeground(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHAN)
                : new Notification.Builder(this);
        Intent open = new Intent(this, ReaderActivity.class)
                .putExtra(ReaderActivity.X_BOOK, book == null ? "" : book.id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setContentTitle(book == null ? "Sandy reader" : book.title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(content)
                .setOngoing(session.isActive());
        if (mediaSession != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(mediaSession.getSessionToken())
                    .setShowActionsInCompactView(0, 1));
        }
        boolean speaking = session.isSpeaking();
        b.addAction(new Notification.Action.Builder(
                        speaking ? android.R.drawable.ic_media_pause
                                : android.R.drawable.ic_media_play,
                        speaking ? "Pause" : "Play",
                        svcIntent(speaking ? A_PAUSE : A_RESUME, 2)).build());
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next",
                svcIntent(A_NEXT, 3)).build());
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Stop",
                svcIntent(A_STOP, 4)).build());
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private PendingIntent svcIntent(String action, int req) {
        Intent i = new Intent(this, ReaderService.class).setAction(action);
        return PendingIntent.getService(this, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateMediaState() {
        if (mediaSession == null) return;
        int st = session.isSpeaking() ? PlaybackState.STATE_PLAYING
                : session.state() == ReadingSession.State.PAUSED
                ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_STOP)
                .setState(st, session.currentChunk(), 1.0f)
                .build());
        if (book != null) {
            mediaSession.setMetadata(new android.media.MediaMetadata.Builder()
                    .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, book.title)
                    .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                            chunks == null ? "" : BookChunker.chapterAt(chunks, session.currentChunk()))
                    .build());
        }
    }
}
