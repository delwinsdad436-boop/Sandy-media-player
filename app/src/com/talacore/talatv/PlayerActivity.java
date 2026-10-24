package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.mediarouter.media.MediaRouter;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.source.BehindLiveWindowException;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fullscreen playback. Plain-spoken failure handling is a feature here:
 * public streams die constantly, so a dead channel says so, and (with
 * auto-skip on) the player walks to the next channel instead of hanging.
 *
 * v0.2.0 additions: a Tracks button (subtitles/CC + audio selection from
 * whatever the stream actually carries — and a plain "no subtitles on
 * this channel" when it carries none), sidecar subtitle files handed in
 * by the source picker (Internet Archive items), and — for on-demand
 * titles (a content key is present) — resume positions and watched marks.
 *
 * v0.5.0: live surfing DOES keep a place now (item 8, as amended) — the
 * last channel's buffer persists across closes, and reopening it offers
 * the place back, honestly recomputed (the edge moved meanwhile). Plus:
 * ● Record (media3 download of what's playing, into the Recordings
 * shelf), stream captions labeled as Captions (CC) on live, the
 * hold-at-full latch for the kept buffer, and rumble cues.
 */
public class PlayerActivity extends Activity {

    private static final long READY_TIMEOUT_MS = 20000;
    private static final int MAX_CONSECUTIVE_FAILS = 5;

    private PlayerView playerView;
    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private LinearLayout errorBox;
    private TextView errorText;
    private TextView titleText;
    private CheckBox autoSkipBox;

    // Unified chrome (v0.4.0): title card, Subtitles/Audio button, edge
    // buttons, buffer readout and Jump-to-LIVE all live under ONE
    // show/hide regime — nothing bolted on outside the timer.
    private Button tracksButton, prevButton, nextButton, jumpLiveButton, recButton;
    private TextView leadText;
    private boolean chromeShown = true;
    private boolean liveNow = false;
    private boolean showJumpLive = false;
    private Runnable chromeHide;
    private Runnable uiTicker;

    // Live timeshift (v0.4.0)
    private String currentUrl;

    // v0.5.0: recording, the kept live place, hold-at-full
    private boolean isRecordingPlay = false;
    private boolean lastLooksHls = false;
    private ChannelStore.KeptLive keptSnapshot;
    private boolean liveOfferDone = false;
    private boolean liveOfferMode = false;
    private boolean holdLatched = false;
    private boolean forceUncached = false;
    private boolean hapticFired = false;
    private Runnable keptTicker;

    // Prepared dub (v0.4.0)
    private String dubUrl;
    private boolean dubEnabled = false;
    private boolean dubTrackApplied = false;

    // The translation layer (v0.6.0): the Translate option in the
    // chrome, its status bar (the go sign lives there), the parallel
    // original-line view, and the on-device voice renderer.
    private Button translateButton;
    // Screen-share to TV (v0.7.0): the Share button in the same
    // chrome, and the session machinery behind its sheet.
    private Button shareButton;
    private ShareCast shareCast;
    private boolean autoTranslatePending = false;
    // A recording's channel carries the LIVE stream's address;
    // sharing the recording itself means sharing its assembled
    // file (RecordingExport). Set once prepared; the sheet then
    // aims at the file, and says so until it is.
    private volatile TvShare.Plan recordingSharePlan;
    private boolean recordingSharePreparing = false;
    private LinearLayout trBar;
    private TextView trBarText;
    private Button trBarBtn;
    private TextView origLineText;
    private String trJsonUrl;
    private List<CookedSubs.Line> cookedLines;
    private boolean showOriginal = false;
    private Cooker trCooker;
    // v0.6.1: the bound ONNX engine, woken lazily on a worker thread
    // (opening the pack's sessions is seconds of work and hundreds
    // of MB — never on the UI thread, never before it's wanted).
    private OnnxCookerEngine boundEngine;
    private String engineFailWhy = "";
    private boolean engineWaking = false;
    private boolean subCooking = false;
    private boolean subGoShown = false;
    private java.io.File subSrtFile, subJsonFile;
    private TtsDubRenderer ttsRenderer;
    private boolean voiceCooking = false;
    private double voiceRenderedSec = 0;
    private java.io.File voiceOutFile;
    private boolean voiceGoShown = false;

    private LinearLayout resumeBar;
    private long pendingResumeMs = 0;
    private boolean resumeOffered = false;

    private String contentKey;      // null for live surfing
    private String subUrl, subMime, subLang;

    // The local set (v0.9.0): the My Videos shelf or a picked set of
    // files plays through THIS player — one player for everything.
    // In local mode the queue is a set of files on the phone: places
    // are kept per file (LocalFiles keys), failures speak file words
    // and never auto-skip, and a finished file walks to the next one.
    private boolean localMode = false;
    private LinearLayout skipRow;
    private Button speedButton;
    private float currentSpeed = 1.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable watchdog;
    private Runnable positionTicker;

    private List<M3uParser.Channel> queue;
    private int index;
    private int consecutiveFails = 0;
    private boolean destroyed = false;

    // One tap to the next episode (v0.14.0): the episode context
    // the source door handed over, the next episode a background
    // resolver (NextEpisodeResolver) finds and pre-proves while
    // this one plays, and the end-screen tile that offers it.
    // Default is a CHOICE — the tile waits for a tap; Settings'
    // auto-advance is the one standing exception, chosen there.
    private int epShowId = -1, epSeason, epNumber;
    private String epShowName = "", epShowLang = "";
    private volatile TvMaze.Episode nextEp;
    private volatile SourceFinder.Source nextSource;
    private volatile boolean nextSettled = false;
    // Hydra in the player (v0.14.0): a network failure walks the
    // ladder ONCE per channel before any give-up — the same
    // channel through another door is a different attempt, not
    // a retry. Failure words ride along so the error surface
    // states what the server actually said.
    private boolean hydraWalked = false;
    private boolean lastFailNetwork = false;
    private String lastFailWords = "";

    // Continuity (v0.14.1 — the fifteen-second window, Addenda
    // 1–4): a live channel that reached READY and PLAYED lives
    // under PlaybackContinuity's law, not the skip path. Its
    // failures are maintenance events (reconnect under buffer
    // cover — no strike, no cap while the source answers), the
    // Active Pinger watches its door for early death and frozen
    // windows, and every event lands in the PlaybackRecord with
    // the facts that separate the failure families.
    private boolean playedThisChannel = false;
    private volatile boolean recovering = false;
    private volatile boolean recoveryLoopRunning = false;
    private volatile int recoveryGen = 0;
    private long recoveryStartedMs = 0;
    private boolean maintenanceReprepare = false;
    private long bufferingSinceMs = 0;
    private boolean stallWordsShown = false;
    private PlaybackRecord.Facts recoveryFacts;
    private ActivePinger pinger;
    private volatile ActivePinger.Sample lastPing;
    private volatile String currentChannelName = "";
    private String lastPingClass = "";
    private int pendingLocalNext = -1;
    private Button nextEpButton;
    private LinearLayout nextOverlay;
    private TextView nextOverlayText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        contentKey = getIntent().getStringExtra("ckey");
        localMode = getIntent().getBooleanExtra("localSet", false);
        isRecordingPlay = getIntent().getBooleanExtra("recording", false);
        // The Recordings shelf's "Cook a translation…" door lands
        // here: once playback is ready, the Translate sheet opens
        // itself — the viewer asked for the cook, not for a hunt.
        autoTranslatePending = getIntent().getBooleanExtra("autoTranslate", false);
        // The kept place, snapshotted BEFORE this session's own ticker
        // can overwrite it — the offer is computed from this snapshot.
        keptSnapshot = ChannelStore.getKeptLive(this);
        epShowId = getIntent().getIntExtra("epShowId", -1);
        epSeason = getIntent().getIntExtra("epSeason", 0);
        epNumber = getIntent().getIntExtra("epNumber", 0);
        String esn = getIntent().getStringExtra("epShowName");
        if (esn != null) epShowName = esn;
        String esl = getIntent().getStringExtra("epShowLang");
        if (esl != null) epShowLang = esl;
        subUrl = getIntent().getStringExtra("subUrl");
        subMime = getIntent().getStringExtra("subMime");
        subLang = getIntent().getStringExtra("subLang");
        dubUrl = getIntent().getStringExtra("dubUrl");
        dubEnabled = dubUrl != null && !dubUrl.isEmpty()
                && ChannelStore.getDubChoice(this, contentKey);
        trJsonUrl = getIntent().getStringExtra("trJsonUrl");

        queue = ChannelStore.playQueue;
        index = ChannelStore.playIndex;
        if (queue == null || queue.isEmpty()) {
            // Process was restarted: fall back to the single tapped channel.
            M3uParser.Channel c = new M3uParser.Channel();
            c.url = getIntent().getStringExtra("url");
            c.name = getIntent().getStringExtra("name");
            c.userAgent = getIntent().getStringExtra("ua");
            if (c.url == null) { finish(); return; }
            if (c.name == null) c.name = c.url;
            if (c.userAgent == null) c.userAgent = "";
            queue = java.util.Collections.singletonList(c);
            index = 0;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        playerView = new PlayerView(this);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        // The unified chrome timer (below) owns ALL show/hide; the
        // stock controller must never run its own timeout alongside.
        playerView.setControllerShowTimeoutMs(0);
        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility ->
                        onControllerVisibility(visibility));
        root.addView(playerView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        titleText = new TextView(this);
        titleText.setTextColor(Sanctuary.INK);
        titleText.setTextSize(15);
        titleText.setShadowLayer(4, 0, 0, Color.BLACK);
        titleText.setPadding(dp(14), dp(10), dp(14), 0);
        root.addView(titleText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // tracks button (subtitles / audio) — part of the unified chrome
        tracksButton = new Button(this);
        Sanctuary.styleButton(this, tracksButton, false);
        tracksButton.setText(LangStore.tr(this, "player_subtitles_audio"));
        tracksButton.setContentDescription(LangStore.tr(this, "player_subtitles_audio"));
        tracksButton.setAlpha(0.75f);
        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tbLp.setMargins(0, dp(6), dp(10), 0);
        root.addView(tracksButton, tbLp);
        tracksButton.setOnClickListener(v -> { pokeChrome(); showTracksDialog(); });

        // prev / next channel edge buttons (surfing) — chrome too
        prevButton = edgeButton("◀");
        prevButton.setContentDescription("Previous channel");
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(dp(52), dp(72), Gravity.CENTER_VERTICAL | Gravity.START);
        root.addView(prevButton, pp);
        prevButton.setOnClickListener(v -> { pokeChrome(); step(-1); });
        nextButton = edgeButton("▶");
        nextButton.setContentDescription(LangStore.tr(this, "player_next_channel"));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(dp(52), dp(72), Gravity.CENTER_VERTICAL | Gravity.END);
        root.addView(nextButton, np);
        nextButton.setOnClickListener(v -> { pokeChrome(); step(1); });

        // v0.14.0: the Next Episode chrome button — stands under
        // the tracks button once a next episode is known; its tap
        // plays the pre-resolved source, never starts a hunt.
        nextEpButton = new Button(this);
        Sanctuary.styleButton(this, nextEpButton, false);
        nextEpButton.setText(LangStore.tr(this, "next_episode") + " ▶");
        nextEpButton.setContentDescription(LangStore.tr(this, "next_episode"));
        nextEpButton.setAlpha(0.85f);
        nextEpButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams neLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        neLp.setMargins(0, dp(54), dp(10), 0);
        root.addView(nextEpButton, neLp);
        nextEpButton.setOnClickListener(v -> onNextTapped());

        // Buffer readout (v0.4.0): measured lead + behind-live, the
        // numbers James watches climb on Netflix — shown as our own.
        leadText = new TextView(this);
        leadText.setTextColor(Sanctuary.GOLD_TEXT);
        leadText.setTextSize(14);
        leadText.setShadowLayer(4, 0, 0, Color.BLACK);
        leadText.setPadding(dp(14), 0, 0, 0);
        FrameLayout.LayoutParams leadLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        leadLp.setMargins(0, 0, 0, dp(88));
        leadText.setVisibility(View.GONE);
        root.addView(leadText, leadLp);

        jumpLiveButton = new Button(this);
        Sanctuary.styleButton(this, jumpLiveButton, false);
        jumpLiveButton.setText(LangStore.tr(this, "player_jump_live"));
        jumpLiveButton.setContentDescription(LangStore.tr(this, "player_jump_live"));
        jumpLiveButton.setAlpha(0.85f);
        FrameLayout.LayoutParams jlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        jlLp.setMargins(0, 0, dp(10), dp(84));
        jumpLiveButton.setVisibility(View.GONE);
        root.addView(jumpLiveButton, jlLp);
        jumpLiveButton.setOnClickListener(v -> {
            pokeChrome();
            if (player != null) {
                player.seekToDefaultPosition();
                player.setPlayWhenReady(true);
            }
        });

        // ● Record (v0.5.0): part of the same chrome, above Jump to LIVE.
        recButton = new Button(this);
        Sanctuary.styleButton(this, recButton, false);
        recButton.setText(LangStore.tr(this, "player_record"));
        recButton.setContentDescription(LangStore.tr(this, "player_record"));
        recButton.setAlpha(0.85f);
        FrameLayout.LayoutParams recLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        recLp.setMargins(0, 0, dp(10), dp(132));
        recButton.setVisibility(View.GONE);
        root.addView(recButton, recLp);
        recButton.setOnClickListener(v -> { pokeChrome(); toggleRecording(); });

        // Translate (v0.6.0) — in the chrome beside Subtitles/Audio,
        // under the same show/hide regime. Field note #2f: the option
        // must be VISIBLE, in the player, where the video plays.
        translateButton = new Button(this);
        Sanctuary.styleButton(this, translateButton, false);
        translateButton.setText(LangStore.tr(this, "player_translate"));
        translateButton.setContentDescription(LangStore.tr(this, "player_translate"));
        translateButton.setAlpha(0.75f);
        FrameLayout.LayoutParams trLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        trLp.setMargins(0, dp(58), dp(10), 0);
        root.addView(translateButton, trLp);
        translateButton.setOnClickListener(v -> { pokeChrome(); showTranslateDialog(); });

        // Share to TV (v0.7.0) — same chrome regime, under Translate.
        shareButton = new Button(this);
        Sanctuary.styleButton(this, shareButton, false);
        shareButton.setText(LangStore.tr(this, "player_share_tv"));
        shareButton.setContentDescription(LangStore.tr(this, "player_share_tv"));
        shareButton.setAlpha(0.75f);
        FrameLayout.LayoutParams shLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        shLp.setMargins(0, dp(110), dp(10), 0);
        root.addView(shareButton, shLp);
        shareButton.setOnClickListener(v -> { pokeChrome(); showShareDialog(); });
        // On-demand transport extras (v0.9.0): −10s / +10s and the
        // speed cycle, for anything with a real timeline — catalogue
        // titles and the local set. Live surfing keeps its own
        // arrangement (the kept buffer + Jump to LIVE), so the row
        // hides there. Same chrome regime as everything else.
        skipRow = new LinearLayout(this);
        skipRow.setOrientation(LinearLayout.HORIZONTAL);
        skipRow.setGravity(Gravity.CENTER_VERTICAL);
        Button back10 = new Button(this);
        Sanctuary.styleButton(this, back10, false);
        back10.setText("⏪ 10s");
        back10.setContentDescription("Back 10 seconds");
        back10.setAlpha(0.85f);
        back10.setOnClickListener(v -> { pokeChrome(); skipBy(-10_000); });
        skipRow.addView(back10);
        speedButton = new Button(this);
        Sanctuary.styleButton(this, speedButton, false);
        speedButton.setText(LocalFiles.speedLabel(currentSpeed));
        speedButton.setContentDescription("Playback speed");
        speedButton.setAlpha(0.85f);
        speedButton.setOnClickListener(v -> { pokeChrome(); cycleSpeed(); });
        skipRow.addView(speedButton);
        Button fwd10 = new Button(this);
        Sanctuary.styleButton(this, fwd10, false);
        fwd10.setText("10s ⏩");
        fwd10.setContentDescription("Forward 10 seconds");
        fwd10.setAlpha(0.85f);
        fwd10.setOnClickListener(v -> { pokeChrome(); skipBy(10_000); });
        skipRow.addView(fwd10);
        FrameLayout.LayoutParams skLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        skLp.setMargins(dp(10), 0, 0, dp(88));
        skipRow.setVisibility(View.GONE);
        root.addView(skipRow, skLp);
        if (localMode) {
            prevButton.setContentDescription("Previous file");
            nextButton.setContentDescription("Next file");
        }

        // v0.8.0 (field fix): the share stack is NOT initialized here.
        // In 0.7.0, ShareCast.get + MediaRouter discovery ran on every
        // player open, before a single frame — new library machinery
        // sitting on the playback pathway's front door. It now starts
        // lazily, the first time the viewer actually opens the share
        // sheet (ShareCast.remoteRoutes starts discovery itself), and
        // every touch of it from this activity is Throwable-guarded:
        // a share fault can cost the share sheet, never the player.
        try {
            shareCast = ShareCast.get(this);
        } catch (Throwable t) {
            CrashGuard.log(this, "ShareCast.get in PlayerActivity.onCreate", t);
            shareCast = null;
        }

        // The parallel original line (UTX Law 1): when the viewer asks,
        // the original-language line rides under the translation —
        // the source stays present, never silently replaced.
        origLineText = new TextView(this);
        origLineText.setTextColor(Color.rgb(180, 220, 255));
        origLineText.setTextSize(14);
        origLineText.setGravity(Gravity.CENTER);
        origLineText.setShadowLayer(4, 0, 0, Color.BLACK);
        origLineText.setPadding(dp(20), 0, dp(20), 0);
        origLineText.setVisibility(View.GONE);
        FrameLayout.LayoutParams olLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        olLp.setMargins(0, 0, 0, dp(118));
        root.addView(origLineText, olLp);

        // Translate status bar: the cook's state in plain sight —
        // cooking lead, the GO SIGN, a starved edge said out loud.
        // Like the error box it lives outside the chrome fade: a
        // viewer waiting on the go sign must not lose it to a timer.
        trBar = new LinearLayout(this);
        trBar.setOrientation(LinearLayout.HORIZONTAL);
        trBar.setGravity(Gravity.CENTER);
        trBar.setBackgroundColor(Color.argb(225, 20, 26, 20));
        trBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        trBar.setVisibility(View.GONE);
        trBarText = new TextView(this);
        trBarText.setTextColor(Sanctuary.INK);
        trBarText.setTextSize(15);
        trBar.addView(trBarText);
        trBarBtn = new Button(this);
        Sanctuary.styleButton(this, trBarBtn, false);
        trBarBtn.setVisibility(View.GONE);
        trBar.addView(trBarBtn);
        Button trClose = new Button(this);
        Sanctuary.styleButton(this, trClose, false);
        trClose.setText("✕");
        trClose.setContentDescription("Close");
        trClose.setOnClickListener(v -> trBar.setVisibility(View.GONE));
        trBar.addView(trClose);
        FrameLayout.LayoutParams trbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        trbLp.setMargins(0, 0, 0, dp(76));
        root.addView(trBar, trbLp);

        // resume bar (on-demand titles with a saved place)
        resumeBar = new LinearLayout(this);
        resumeBar.setOrientation(LinearLayout.HORIZONTAL);
        resumeBar.setGravity(Gravity.CENTER);
        resumeBar.setBackgroundColor(Color.argb(225, 20, 20, 26));
        resumeBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        resumeBar.setVisibility(View.GONE);
        TextView resumeText = new TextView(this);
        resumeText.setTextColor(Sanctuary.INK);
        resumeText.setTextSize(15);
        resumeText.setTag("label");
        resumeBar.addView(resumeText);
        Button resumeBtn = new Button(this);
        Sanctuary.styleButton(this, resumeBtn, false);
        resumeBtn.setText(LangStore.tr(this, "player_resume"));
        resumeBtn.setContentDescription(LangStore.tr(this, "player_resume"));
        resumeBtn.setOnClickListener(v -> {
            resumeBar.setVisibility(View.GONE);
            liveOfferMode = false;
            if (player != null) {
                player.seekTo(pendingResumeMs);
                player.setPlayWhenReady(true);
            }
        });
        resumeBar.addView(resumeBtn);
        Button overBtn = new Button(this);
        Sanctuary.styleButton(this, overBtn, false);
        overBtn.setText(LangStore.tr(this, "player_start_over"));
        overBtn.setTag("overBtn");
        overBtn.setOnClickListener(v -> {
            resumeBar.setVisibility(View.GONE);
            if (player != null) {
                if (liveOfferMode) {
                    // Live offer's second door is the live edge itself.
                    player.seekToDefaultPosition();
                } else {
                    String key = placeKey();
                    if (key != null) ChannelStore.savePosition(this, key, 0);
                    player.seekTo(0);
                }
                player.setPlayWhenReady(true);
            }
            liveOfferMode = false;
        });
        resumeBar.addView(overBtn);
        FrameLayout.LayoutParams rbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rbLp.setMargins(0, 0, 0, dp(28));
        root.addView(resumeBar, rbLp);

        // error box
        errorBox = new LinearLayout(this);
        errorBox.setOrientation(LinearLayout.VERTICAL);
        errorBox.setGravity(Gravity.CENTER);
        errorBox.setBackgroundColor(Color.argb(220, 20, 20, 26));
        errorBox.setPadding(dp(24), dp(24), dp(24), dp(24));
        errorBox.setVisibility(View.GONE);
        errorText = new TextView(this);
        errorText.setTextColor(Sanctuary.INK);
        errorText.setTextSize(17);
        errorText.setGravity(Gravity.CENTER);
        errorBox.addView(errorText);
        autoSkipBox = new CheckBox(this);
        autoSkipBox.setText("Keep skipping dead channels automatically");
        autoSkipBox.setTextColor(Color.rgb(200, 200, 210));
        autoSkipBox.setChecked(ChannelStore.getAutoSkip(this));
        autoSkipBox.setOnCheckedChangeListener((b, checked) -> ChannelStore.setAutoSkip(this, checked));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = dp(14);
        errorBox.addView(autoSkipBox, cbLp);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        Button nextBtn2 = new Button(this);
        Sanctuary.styleButton(this, nextBtn2, false);
        nextBtn2.setText(localMode ? "Next file"
                : LangStore.tr(this, "player_next_channel"));
        nextBtn2.setOnClickListener(v -> { errorBox.setVisibility(View.GONE); step(1); });
        btnRow.addView(nextBtn2);
        Button backBtn = new Button(this);
        Sanctuary.styleButton(this, backBtn, false);
        backBtn.setText(LangStore.tr(this, "player_back_channels"));
        backBtn.setOnClickListener(v -> finish());
        btnRow.addView(backBtn);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(14);
        errorBox.addView(btnRow, brLp);
        FrameLayout.LayoutParams ebLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(errorBox, ebLp);

        // v0.14.0: the Next Episode end screen — one big tile,
        // shown when an episode (or a file in a local set) ends
        // and a next one stands ready. It never counts down and
        // never fires itself; a hand decides.
        nextOverlay = new LinearLayout(this);
        nextOverlay.setOrientation(LinearLayout.VERTICAL);
        nextOverlay.setGravity(Gravity.CENTER);
        nextOverlay.setBackgroundColor(Color.argb(225, 14, 14, 20));
        nextOverlay.setPadding(dp(28), dp(28), dp(28), dp(28));
        nextOverlay.setVisibility(View.GONE);
        TextView nextHead = new TextView(this);
        nextHead.setText(LangStore.tr(this, "next_episode"));
        nextHead.setTextColor(Sanctuary.GOLD_TEXT);
        nextHead.setTextSize(15);
        nextHead.setGravity(Gravity.CENTER);
        nextOverlay.addView(nextHead);
        nextOverlayText = new TextView(this);
        nextOverlayText.setTextColor(Sanctuary.INK);
        nextOverlayText.setTextSize(24);
        nextOverlayText.setGravity(Gravity.CENTER);
        nextOverlay.addView(nextOverlayText);
        Button nextPlayBtn = new Button(this);
        Sanctuary.styleButton(this, nextPlayBtn, false);
        nextPlayBtn.setText(LangStore.tr(this, "reader_play") + " ▶");
        nextPlayBtn.setTextSize(20);
        LinearLayout.LayoutParams npb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        npb.topMargin = dp(20);
        nextPlayBtn.setLayoutParams(npb);
        nextPlayBtn.setOnClickListener(v -> onNextTapped());
        nextOverlay.addView(nextPlayBtn);
        Button nextNotNow = new Button(this);
        Sanctuary.styleButton(this, nextNotNow, false);
        nextNotNow.setText(LangStore.tr(this, "player_back_channels"));
        LinearLayout.LayoutParams nnb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nnb.topMargin = dp(8);
        nextNotNow.setLayoutParams(nnb);
        nextNotNow.setOnClickListener(v -> hideNextOverlay());
        nextOverlay.addView(nextNotNow);
        root.addView(nextOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        trackSelector = new DefaultTrackSelector(this);
        applyPosture();
        // Timeshift load control (v0.4.0; runway numbers v0.14.1):
        // retain a back-buffer as deep as the setting allows
        // (rewind comes from here + the disk cache), and buffer
        // ahead eagerly — the lead the readout shows is built on
        // purpose, not by luck. The forward cap is RAM-sane
        // (10 min) whatever the setting; the DISK cache holds the
        // full setting's worth for re-reads. See TimeshiftCache.
        // v0.14.1 (the Reconnect Doctrine): the minimum buffer is
        // the RUNWAY — one full Hydra walk (30 s) the loader
        // defends, so recovery happens under cover; resuming
        // after a rebuffer waits for 10 s in hand, not 4.
        int capMs = ChannelStore.getTimeshiftMinutes(this) * 60_000;
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBackBuffer(capMs, true)
                .setBufferDurationsMs(TimeshiftMath.RUNWAY_MIN_BUFFER_MS,
                        Math.min(capMs, TimeshiftMath.MAX_AHEAD_MS),
                        TimeshiftMath.START_BUFFER_MS,
                        TimeshiftMath.REBUFFER_RESUME_MS)
                .build();
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    consecutiveFails = 0;
                    cancelWatchdog();
                    recordHealth(true, "played OK");
                    bufferingSinceMs = 0;
                    if (!playedThisChannel) {
                        // v0.14.1: this channel has PLAYED — from
                        // here its failures are continuity events,
                        // never tune-in failures (see onPlayerError).
                        playedThisChannel = true;
                        PlaybackRecord.Facts rf = playbackFacts();
                        rf.note = "reached READY";
                        PlaybackRecord.write(PlayerActivity.this,
                                PlaybackRecord.EV_READY, rf);
                    }
                    if (recovering) {
                        // Maintenance that ends in READY was
                        // invisible by design: no strike, no error
                        // flash — just the record saying it happened.
                        recovering = false;
                        recoveryGen++;
                        PlaybackRecord.Facts rf = playbackFacts();
                        rf.decision = "RECOVERED";
                        rf.note = "maintenance took "
                                + (System.currentTimeMillis()
                                        - recoveryStartedMs) + " ms";
                        PlaybackRecord.write(PlayerActivity.this,
                                PlaybackRecord.EV_RECOVERED, rf);
                    }
                    if (!hapticFired) {
                        // The rumble cue: playback STARTING is a thing
                        // you should be able to feel (v0.5.0, item 7).
                        hapticFired = true;
                        Haptics.pulse(PlayerActivity.this);
                    }
                    maybeOfferResume();
                    maybeOfferLocalResume();
                    maybeOfferLiveResume();
                    maybeSelectDubTrack();
                    if (autoTranslatePending) {
                        autoTranslatePending = false;
                        handler.post(PlayerActivity.this::showTranslateDialog);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    // v0.14.1: the stall watchdog's raw material —
                    // a playing stream that sits in BUFFERING is
                    // timed from here (see updateBufferReadout);
                    // the startup watchdog never re-arms after
                    // READY, which is how stalls used to hang
                    // forever on a frozen frame.
                    if (playedThisChannel && bufferingSinceMs == 0) {
                        bufferingSinceMs = System.currentTimeMillis();
                    }
                } else if (state == Player.STATE_ENDED) {
                    if (contentKey != null) {
                        ChannelStore.setWatched(PlayerActivity.this, contentKey, true);
                        ChannelStore.savePosition(PlayerActivity.this, contentKey, 0);
                    }
                    if (localMode) {
                        // A finished file clears its place, then the
                        // set offers the next file (v0.14.0): the
                        // walk now stands under the same choice as
                        // episodes — auto-advance (Settings) walks
                        // on its own; else the next file is shown
                        // big and waits for the hand.
                        String key = placeKey();
                        if (key != null) {
                            ChannelStore.savePosition(PlayerActivity.this, key, 0);
                        }
                        int nxt = LocalFiles.nextIndex(index,
                                queue == null ? 0 : queue.size());
                        if (nxt >= 0 && !destroyed) {
                            if (ChannelStore.getAutoAdvanceNext(
                                    PlayerActivity.this)) {
                                index = nxt;
                                errorBox.setVisibility(View.GONE);
                                resumeBar.setVisibility(View.GONE);
                                playCurrent();
                            } else {
                                pendingLocalNext = nxt;
                                showNextOverlay(queue.get(nxt).name);
                            }
                        }
                    } else if (epShowId > 0) {
                        // v0.14.0: an episode ended. Auto-advance ON
                        // and a pre-resolved source in hand → roll
                        // straight in; otherwise the big Next tile
                        // waits — a choice, never a countdown.
                        // Display Honesty Law: the tile renders
                        // ONLY when the next episode's source stands
                        // RESOLVED — a known-but-unproven next
                        // shows no tile at all, rather than a
                        // promise the tap would betray.
                        if (nextSource != null) {
                            if (ChannelStore.getAutoAdvanceNext(
                                    PlayerActivity.this)) {
                                playNextEpisode();
                            } else {
                                showNextOverlay(NextEpisode.label(nextEp));
                            }
                        } else if (nextEp == null && nextSettled) {
                            Toast.makeText(PlayerActivity.this,
                                    LangStore.tr(PlayerActivity.this,
                                            "next_none"),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // Unified chrome: playing arms the fade; paused keeps
                // every control on screen, no exceptions.
                if (isPlaying) {
                    rescheduleChromeHide();
                } else {
                    cancelChromeHide();
                }
            }
            @Override
            public void onPlayerError(PlaybackException error) {
                // Failure words first (v0.14.0): what did the
                // server actually say? A bare "HTTP error" is
                // banned in this house — the surface states the
                // result, and Hydra gets its walk when the wire
                // is what failed.
                lastFailWords = wordsOfError(error);
                lastFailNetwork = isNetworkError(error);
                if (!localMode && liveNow && !isRecordingPlay
                        && (playedThisChannel || recovering)) {
                    // v0.14.1: this stream was PLAYING (or is a
                    // maintenance re-prepare in flight). The
                    // continuity law owns it — reconnect first,
                    // verdict only as PlaybackContinuity allows.
                    // The old road from here (stop → probe the
                    // PLAYLIST → count a strike → auto-skip) is
                    // what kicked live channels at ~15 s: it
                    // could not tell a refused segment from a
                    // dead channel, and it never once tried to
                    // play the channel again.
                    int family = PlaybackContinuity.classifyError(
                            isWindowStuckError(error));
                    PlaybackRecord.Facts f = playbackFacts();
                    f.family = PlaybackContinuity.familyName(family);
                    f.segmentResult = errorResultText(error);
                    f.note = "player error after playing: "
                            + error.getClass().getSimpleName()
                            + (lastFailWords.isEmpty()
                                    ? "" : " — " + lastFailWords);
                    PlaybackRecord.write(PlayerActivity.this,
                            PlaybackRecord.EV_ERROR, f);
                    if (recovering) {
                        if (!recoveryLoopRunning) {
                            spawnRecoveryLoop(recoveryGen, family);
                        }
                    } else {
                        startRecovery(family);
                    }
                    return;
                }
                handleFailure(localMode
                        ? "This file didn't play."
                        : "This channel didn't answer.");
            }
        });

        playCurrent();
        startPositionTicker();
        startKeptTicker();
        startUiTicker();

        // v0.14.0: while this episode plays, quietly find the
        // next one and prove a source for it (slow-link rules
        // inside the resolver) — so the Next tap PLAYS.
        if (epShowId > 0) {
            new Thread(() -> NextEpisodeResolver.resolve(
                    getApplicationContext(), epShowId, epShowName,
                    epShowLang, epSeason, epNumber,
                    new NextEpisodeResolver.Callback() {
                        @Override
                        public void onNextFound(TvMaze.Episode next) {
                            nextEp = next;
                            handler.post(() -> {
                                if (!destroyed) applyChromeVisibility();
                            });
                        }
                        @Override
                        public void onResolved(TvMaze.Episode next,
                                               SourceFinder.Source source) {
                            nextEp = next;
                            nextSource = source;
                            nextSettled = true;
                            handler.post(() -> {
                                if (!destroyed) applyChromeVisibility();
                            });
                        }
                        @Override
                        public void onNoNext(TvMaze.Episode next) {
                            if (next != null && nextEp == null) nextEp = next;
                            nextSettled = true;
                        }
                    }), "next-episode-resolve").start();
        }
    }

    // ------------------------------------------------- unified chrome

    /**
     * ONE show/hide regime for everything on top of the picture (the
     * v0.4.0 fix for James's exact complaint: the title card and the
     * top-right button used to live outside the controls' fade and
     * never hide). A tap toggles everything; ~3.5 s untouched during
     * playback fades everything to a clean picture; paused keeps it
     * all visible. The PlayerView controller and every overlay here
     * move together, driven by the controller's own visibility signal
     * plus this timer — no view keeps a private arrangement.
     */
    private void onControllerVisibility(int visibility) {
        boolean show = visibility == View.VISIBLE;
        if (show != chromeShown) {
            chromeShown = show;
            applyChromeVisibility();
        }
        if (show) rescheduleChromeHide();
        else cancelChromeHide();
    }

    private void pokeChrome() {
        if (!chromeShown) {
            chromeShown = true;
            playerView.showController();   // listener re-syncs the rest
            applyChromeVisibility();
        }
        rescheduleChromeHide();
    }

    private void rescheduleChromeHide() {
        if (chromeHide == null) {
            chromeHide = () -> {
                if (destroyed) return;
                chromeShown = false;
                playerView.hideController();
                applyChromeVisibility();
            };
        }
        handler.removeCallbacks(chromeHide);
        if (player != null && (player.isPlaying() || player.getPlayWhenReady())) {
            handler.postDelayed(chromeHide, 3500);
        }
    }

    private void cancelChromeHide() {
        if (chromeHide != null) handler.removeCallbacks(chromeHide);
    }

    private void applyChromeVisibility() {
        int v = chromeShown ? View.VISIBLE : View.GONE;
        titleText.setVisibility(v);
        tracksButton.setVisibility(v);
        if (translateButton != null) translateButton.setVisibility(v);
        if (shareButton != null) shareButton.setVisibility(v);
        prevButton.setVisibility(v);
        nextButton.setVisibility(v);
        if (nextEpButton != null) {
            // Display Honesty Law (v0.14.0): the Next control is
            // a tile, and a tile is a promise — it renders ONLY
            // when a next episode with a RESOLVED source exists.
            // A known-but-unproven next shows nothing here.
            nextEpButton.setVisibility(chromeShown && nextSource != null
                    ? View.VISIBLE : View.GONE);
        }
        leadText.setVisibility(chromeShown && liveNow ? View.VISIBLE : View.GONE);
        jumpLiveButton.setVisibility(chromeShown && showJumpLive
                ? View.VISIBLE : View.GONE);
        recButton.setVisibility(chromeShown && liveNow && !isRecordingPlay
                ? View.VISIBLE : View.GONE);
        if (skipRow != null) {
            skipRow.setVisibility(chromeShown && (contentKey != null || localMode)
                    ? View.VISIBLE : View.GONE);
        }
    }

    // --------------------------------------- on-demand transport extras

    private void skipBy(long deltaMs) {
        if (player == null) return;
        long dur = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (target < 0) target = 0;
        if (dur != C.TIME_UNSET && dur > 0 && target > dur) target = dur;
        player.seekTo(target);
    }

    private void cycleSpeed() {
        if (player == null) return;
        currentSpeed = LocalFiles.nextSpeed(currentSpeed);
        player.setPlaybackSpeed(currentSpeed);
        speedButton.setText(LocalFiles.speedLabel(currentSpeed));
    }

    /**
     * The key this playback keeps its place under: the catalogue
     * content key when there is one; in the local set, the file's own
     * LocalFiles key; live surfing keeps no per-item key (its place is
     * the kept-live record instead).
     */
    private String placeKey() {
        if (contentKey != null) return contentKey;
        if (localMode && queue != null && index >= 0 && index < queue.size()) {
            return LocalFiles.keyFor(queue.get(index).url);
        }
        return null;
    }

    // ------------------------------------------------- buffer readout

    /** 500 ms UI ticker: measured lead / behind-live for live playback. */
    private void startUiTicker() {
        uiTicker = new Runnable() {
            @Override
            public void run() {
                if (destroyed || player == null) return;
                updateBufferReadout();
                updateTranslateUi();
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(uiTicker, 500);
    }

    private void updateBufferReadout() {
        if (!liveNow || player == null) return;
        // Hold-at-full (v0.5.0, amendment 2): when the kept buffer
        // reaches its cap in Hold mode, capture stops and the front
        // is preserved — checked here on the 500 ms beat.
        if (!holdLatched && !isRecordingPlay
                && ChannelStore.getBufferFullHold(this)
                && TimeshiftCache.keptFull(this, ChannelStore.getTimeshiftMinutes(this))) {
            latchHold();
        }
        // The mid-play stall watchdog (v0.14.1): a stream that
        // PLAYED and then sits in BUFFERING past the trigger,
        // with no error ever spoken, used to hang on a frozen
        // frame forever — the startup watchdog dies at READY.
        // Now the stall is a continuity event like any other.
        if (playedThisChannel && !recovering && !isRecordingPlay
                && bufferingSinceMs > 0 && player.getPlayWhenReady()
                && player.getPlaybackState() == Player.STATE_BUFFERING
                && System.currentTimeMillis() - bufferingSinceMs
                        > PlaybackContinuity.STALL_TRIGGER_MS) {
            PlaybackRecord.Facts sf = playbackFacts();
            sf.family = PlaybackContinuity.familyName(
                    PlaybackContinuity.NO_ANSWER);
            sf.note = "stall: buffering "
                    + (System.currentTimeMillis() - bufferingSinceMs)
                    + " ms with no error spoken";
            PlaybackRecord.write(this, PlaybackRecord.EV_STALL, sf);
            startRecovery(PlaybackContinuity.NO_ANSWER);
        }
        long pos = player.getCurrentPosition();
        long lead = TimeshiftMath.leadMs(player.getBufferedPosition(), pos);
        long behind = TimeshiftMath.behindLiveMs(player.getDuration(), pos);
        StringBuilder sb = new StringBuilder();
        if (recovering) {
            // The one state a maintenance event may show: plain
            // words while the runway runs low (Addendum 2).
            sb.append(LangStore.tr(this, "player_reconnecting"))
                    .append("   ·   ");
        }
        sb.append("ahead: ").append(TimeshiftMath.formatClock(lead));
        boolean jump = behind > 5000;
        if (jump) {
            sb.append("   ·   −").append(TimeshiftMath.formatClock(behind))
                    .append(" behind live");
        }
        leadText.setText(sb.toString());
        if (jump != showJumpLive) {
            showJumpLive = jump;
            applyChromeVisibility();
        }
    }

    private Button edgeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(20);
        b.setAlpha(0.55f);
        // v0.17.0: player chrome wears the sanctuary glass —
        // the video itself stays on true black, untouched.
        b.setBackground(Sanctuary.glassButton(this, false));
        b.setTextColor(Sanctuary.INK);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------ next episode (v0.14.0)

    private void showNextOverlay(String name) {
        if (nextOverlay == null || destroyed) return;
        nextOverlayText.setText(String.format(
                LangStore.tr(this, "next_episode_fmt"), name));
        nextOverlay.setVisibility(View.VISIBLE);
        chromeShown = true;
        applyChromeVisibility();
    }

    private void hideNextOverlay() {
        if (nextOverlay != null) nextOverlay.setVisibility(View.GONE);
    }

    /** The Next tap, from the chrome button or the end tile. */
    private void onNextTapped() {
        pokeChrome();
        hideNextOverlay();
        if (localMode && pendingLocalNext >= 0) {
            index = pendingLocalNext;
            pendingLocalNext = -1;
            errorBox.setVisibility(View.GONE);
            resumeBar.setVisibility(View.GONE);
            playCurrent();
            return;
        }
        if (nextEp != null && nextSource != null) {
            playNextEpisode();
            return;
        }
        if (nextEp != null) {
            // The episode is known but no source stood proven —
            // one tap still lands the viewer at the next episode,
            // on its own source door, with the full picker and its
            // honest accounting (never a dead end).
            Intent i = new Intent(this, SourcesActivity.class);
            i.putExtra("mode", "episode");
            i.putExtra("showId", epShowId);
            i.putExtra("showName", epShowName);
            i.putExtra("showLanguage", epShowLang);
            i.putExtra("season", nextEp.season);
            i.putExtra("number", nextEp.number);
            i.putExtra("epName", nextEp.name == null ? "" : nextEp.name);
            startActivity(i);
            finish();
            return;
        }
        Toast.makeText(this, LangStore.tr(this,
                nextSettled ? "next_none" : "next_finding"),
                Toast.LENGTH_LONG).show();
    }

    /** Launch the pre-resolved next episode the way the source
     *  door launches an episode — same queue shape, same posture
     *  hand-off, the episode context advanced by one. */
    private void playNextEpisode() {
        SourceFinder.Source s = nextSource;
        TvMaze.Episode nx = nextEp;
        if (s == null || nx == null) { onNextTapped(); return; }
        String title = String.format(Locale.US, "%s — S%02dE%02d · %s",
                epShowName, nx.season, nx.number,
                nx.name == null ? "" : nx.name);
        M3uParser.Channel ch = new M3uParser.Channel();
        ch.name = title;
        ch.url = s.url;
        ch.userAgent = s.userAgent == null ? "" : s.userAgent;
        ch.source = s.kind;
        List<M3uParser.Channel> q = new ArrayList<>();
        q.add(ch);
        ChannelStore.playQueue = q;
        ChannelStore.playIndex = 0;
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("url", s.url);
        i.putExtra("name", title);
        i.putExtra("ua", ch.userAgent);
        i.putExtra("ckey", ChannelStore.episodeKey(
                epShowId, nx.season, nx.number));
        AutoPlay.Posture p = AutoPlay.parsePosture(
                ChannelStore.getQualityPosture(this));
        i.putExtra("posture", AutoPlay.posturePref(p));
        i.putExtra("postureNote", "Playing " + PipeMeter.tierLabel(s.qualityTier)
                + " — " + AutoPlay.postureLabel(p));
        if (s.subUrl != null && !s.subUrl.isEmpty()) {
            i.putExtra("subUrl", s.subUrl);
            i.putExtra("subMime", s.subMime);
            i.putExtra("subLang", s.subLang);
        }
        i.putExtra("epShowId", epShowId);
        i.putExtra("epSeason", nx.season);
        i.putExtra("epNumber", nx.number);
        i.putExtra("epName", nx.name == null ? "" : nx.name);
        i.putExtra("epShowName", epShowName);
        i.putExtra("epShowLang", epShowLang);
        startActivity(i);
        finish();
    }

    private void step(int delta) {
        if (queue == null || queue.isEmpty()) return;
        if (localMode) {
            // The opened set is a list of files, not a dial: it has
            // a first and a last, and the ends say so.
            int target = delta > 0
                    ? LocalFiles.nextIndex(index, queue.size())
                    : LocalFiles.prevIndex(index, queue.size());
            if (target < 0) {
                Toast.makeText(this, delta > 0
                        ? "That's the last file in this set."
                        : "That's the first file in this set.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            index = target;
        } else {
            index = (index + delta + queue.size()) % queue.size();
        }
        errorBox.setVisibility(View.GONE);
        resumeBar.setVisibility(View.GONE);
        playCurrent();
    }

    private void playCurrent() {
        M3uParser.Channel c = queue.get(index);
        titleText.setText(c.displayName());
        currentChannelName = c.displayName();
        cancelWatchdog();
        dubTrackApplied = false;
        hapticFired = false;
        hydraWalked = false;
        // v0.14.1 continuity bookkeeping: a fresh tune is a new
        // stream for the law — it has not PLAYED yet — and any
        // tune that isn't maintenance's own re-prepare ends the
        // maintenance (the recovery thread's generation dies).
        playedThisChannel = false;
        bufferingSinceMs = 0;
        if (!maintenanceReprepare) {
            recovering = false;
            recoveryGen++;
        }
        maintenanceReprepare = false;

        // v0.3.0: My Library files play too — content:// (MediaStore /
        // picked folders) and file paths are local, not streams, and go
        // through the platform data source instead of the HTTP one.
        boolean isLocal = c.url != null && (c.url.startsWith("content://")
                || c.url.startsWith("file://") || c.url.startsWith("/"));
        if (c.url == null || (!isLocal
                && !(c.url.startsWith("http://") || c.url.startsWith("https://")))) {
            handleFailure("This channel's stream isn't a kind Sandy can play (" +
                    (c.url == null ? "no address" : schemeOf(c.url)) + ").");
            return;
        }
        // "Live" for the buffer/readout: surfing (no content key) over
        // the network. On-demand titles keep their own progress UI.
        liveNow = contentKey == null && !isLocal;
        if (liveNow && currentUrl != null && !currentUrl.equals(c.url)) {
            // The buffer belongs to the channel it was built from:
            // changing channels discards it (a buffer, not a recording).
            // Closing the player no longer does — the LAST channel's
            // buffer is the kept place (v0.5.0, item 8 as amended).
            player.stop();
            TimeshiftCache.wipe(this);
            holdLatched = false;
            forceUncached = false;
        }
        currentUrl = c.url;
        applyChromeVisibility();
        refreshRecButton();
        if (liveNow) {
            PlaybackRecord.Facts tf = playbackFacts();
            tf.note = "tune-in";
            PlaybackRecord.write(this, PlaybackRecord.EV_TUNE, tf);
        }

        String ua = (c.userAgent == null || c.userAgent.isEmpty())
                ? "Sandy/0.14.1" : c.userAgent;
        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true);

        // Sidecar subtitles ride only with the on-demand title they
        // came from — plus, from 0.6.1, a sidecar this phone just
        // cooked itself (its .tr- name is the marker), which must be
        // attachable even on a title reached without a content key.
        List<MediaItem.SubtitleConfiguration> subs = Collections.emptyList();
        if (subUrl != null && !subUrl.isEmpty()
                && (contentKey != null || cookedSubAttached())) {
            String mime = "text/vtt".equals(subMime)
                    ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;
            MediaItem.SubtitleConfiguration cfg =
                    new MediaItem.SubtitleConfiguration.Builder(Uri.parse(subUrl))
                            .setMimeType(mime)
                            .setLanguage(subLang == null || subLang.isEmpty()
                                    ? "und" : subLang)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build();
            subs = Collections.singletonList(cfg);
        }

        MediaSource source;
        if (isLocal) {
            // The context factory's data source resolves content:// and
            // file:// itself; the container is sniffed from the file, so
            // no HLS forcing and no extension guessing.
            MediaItem localItem = new MediaItem.Builder()
                    .setUri(c.url).setSubtitleConfigurations(subs).build();
            source = new DefaultMediaSourceFactory(this).createMediaSource(localItem);
            if (dubEnabled && dubUrl != null && !dubUrl.isEmpty()) {
                // Prepared dub (v0.4.0): the sidecar audio rides the
                // SAME timeline via a merged source, played instead of
                // the original audio (selection in maybeSelectDubTrack).
                MediaItem dubItem = new MediaItem.Builder().setUri(dubUrl).build();
                MediaSource dubSource = new DefaultMediaSourceFactory(this)
                        .createMediaSource(dubItem);
                source = new MergingMediaSource(source, dubSource);
            }
            player.setMediaSource(source);
            player.prepare();
            // In the local set, a file with a kept place waits for
            // its resume offer (made at READY, with the real
            // duration in hand — see maybeOfferLocalResume).
            boolean willOfferResumeLocal = localMode && !resumeOffered
                    && LocalFiles.ResumePolicy.shouldOffer(
                            ChannelStore.getPosition(this, LocalFiles.keyFor(c.url)), -1);
            player.setPlayWhenReady(!willOfferResumeLocal);
            armWatchdog(c.name);
            restartPinger();   // local: stops any pinger, starts none
            return;
        }
        String lower = c.url.toLowerCase();
        boolean looksHls = lower.contains(".m3u8");
        boolean extensionless = !lower.substring(lower.lastIndexOf('/') + 1).contains(".")
                || lower.contains(".php") || lower.contains("play?");
        lastLooksHls = looksHls || extensionless;
        if (lastLooksHls) {
            // force HLS interpretation: many guide entries hide the .m3u8
            // behind a script or redirect. Live surfing routes through
            // the timeshift cache (segments land on disk; playlists are
            // never served stale — see TimeshiftCache). A recording
            // plays through the recordings cache instead; a latched
            // hold plays plain live (capture stopped, front kept).
            MediaItem hlsItem = new MediaItem.Builder()
                    .setUri(c.url).setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setSubtitleConfigurations(subs).build();
            DataSource.Factory liveDs;
            if (isRecordingPlay) {
                liveDs = Downloads.playbackFactory(this);
            } else if (liveNow && !forceUncached) {
                liveDs = TimeshiftCache.liveFactory(this, ua,
                        ChannelStore.getTimeshiftMinutes(this));
            } else {
                liveDs = dsFactory;
            }
            source = new HlsMediaSource.Factory(liveDs).createMediaSource(hlsItem);
        } else {
            MediaItem item = new MediaItem.Builder()
                    .setUri(c.url).setSubtitleConfigurations(subs).build();
            DataSource.Factory vodDs;
            if (isRecordingPlay) {
                vodDs = Downloads.playbackFactory(this);
            } else if (liveNow && !forceUncached) {
                vodDs = TimeshiftCache.liveFactory(this, ua,
                        ChannelStore.getTimeshiftMinutes(this));
            } else {
                vodDs = dsFactory;
            }
            source = new DefaultMediaSourceFactory(vodDs).createMediaSource(item);
        }
        player.setMediaSource(source);
        player.prepare();
        // If a resume offer is pending for this title, wait for the tap.
        boolean willOfferResume = contentKey != null && !resumeOffered
                && ChannelStore.getPosition(this, contentKey) > 10000;
        // …or the kept live place is waiting on this channel (v0.5.0).
        boolean willOfferLive = contentKey == null && liveNow && !liveOfferDone
                && keptSnapshot != null && keptSnapshot.url.equals(c.url);
        player.setPlayWhenReady(!(willOfferResume || willOfferLive));
        armWatchdog(c.displayName());
        restartPinger();
    }

    private void maybeOfferResume() {
        if (contentKey == null || resumeOffered || destroyed || player == null) return;
        long saved = ChannelStore.getPosition(this, contentKey);
        if (saved <= 10000) return;
        resumeOffered = true;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || saved < duration - 30000) {
            pendingResumeMs = saved;
            TextView label = (TextView) resumeBar.findViewWithTag("label");
            label.setText("You left off at " + ShowActivity.formatMs(saved) + ".  ");
            resumeBar.setVisibility(View.VISIBLE);
        } else {
            // The saved place is effectively the end — just play.
            player.setPlayWhenReady(true);
        }
    }

    /**
     * The local set's resume offer (v0.9.0): the same promise the
     * catalogue titles get — you left off somewhere, here it is
     * back — keyed per file. The decision waits for READY so the
     * real duration judges the saved place (ResumePolicy): a place
     * within the end margin is finished, not an offer, and clears.
     */
    private void maybeOfferLocalResume() {
        if (!localMode || resumeOffered || destroyed || player == null) return;
        String key = placeKey();
        if (key == null) return;
        resumeOffered = true;
        long saved = ChannelStore.getPosition(this, key);
        long duration = player.getDuration();
        long dur = duration == C.TIME_UNSET ? -1 : duration;
        if (!LocalFiles.ResumePolicy.shouldOffer(saved, dur)) {
            if (LocalFiles.ResumePolicy.isFinished(saved, dur)) {
                ChannelStore.savePosition(this, key, 0);
            }
            player.setPlayWhenReady(true);
            return;
        }
        pendingResumeMs = LocalFiles.ResumePolicy.clamp(saved, dur);
        TextView label = (TextView) resumeBar.findViewWithTag("label");
        label.setText("You left off at "
                + LocalFiles.formatClock(pendingResumeMs) + ".  ");
        resumeBar.setVisibility(View.VISIBLE);
    }

    /**
     * The kept live place (v0.5.0, item 8 as amended): reopening the
     * LAST live channel offers the place back. The math is honest —
     * the place fell further behind by the time away (LiveResumeMath),
     * and if it fell past the window the channel still offers, that
     * is said out loud and playback just goes live. The held case is
     * labeled too: the buffer filled, held its beginning, and the
     * rest rolled past unrecorded.
     */
    private void maybeOfferLiveResume() {
        if (contentKey != null || !liveNow || liveOfferDone || destroyed
                || player == null || keptSnapshot == null) return;
        if (currentUrl == null || !keptSnapshot.url.equals(currentUrl)) return;
        liveOfferDone = true;
        long window = player.getDuration();
        LiveResumeMath.Result r = LiveResumeMath.resolve(
                keptSnapshot.behindMs, keptSnapshot.savedAtMs,
                System.currentTimeMillis(),
                window == C.TIME_UNSET ? -1 : window);
        if (r.rolledOff) {
            Toast.makeText(this, "The part you were watching has rolled "
                    + "past — this channel only keeps its recent window. "
                    + "Starting live.", Toast.LENGTH_LONG).show();
            player.setPlayWhenReady(true);
            return;
        }
        if (r.behindMs <= 10000) {
            // Practically live already — no ceremony.
            player.setPlayWhenReady(true);
            return;
        }
        liveOfferMode = true;
        pendingResumeMs = r.targetPositionMs;
        TextView label = (TextView) resumeBar.findViewWithTag("label");
        String text = LangStore.tr(this, "kept_resume") + " — −"
                + TimeshiftMath.formatClock(r.behindMs) + " behind live.  ";
        if (keptSnapshot.held) {
            text += "The buffer had filled and held its beginning; "
                    + "the rest rolled past unrecorded.  ";
        }
        label.setText(text);
        Button overBtn = (Button) resumeBar.findViewWithTag("overBtn");
        if (overBtn != null) overBtn.setText(LangStore.tr(this, "player_go_live"));
        resumeBar.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------- record

    private void refreshRecButton() {
        if (recButton == null || currentUrl == null) return;
        boolean rec = liveNow && Downloads.isRecording(this, currentUrl);
        recButton.setText(LangStore.tr(this,
                rec ? "player_stop_recording" : "player_record"));
        recButton.setContentDescription(recButton.getText());
    }

    /** ● Record / ■ Stop — record what's playing (item 3). */
    private void toggleRecording() {
        if (!liveNow || currentUrl == null) return;
        M3uParser.Channel c = queue.get(index);
        if (Downloads.isRecording(this, currentUrl)) {
            Downloads.stopKeep(this, currentUrl);
            Toast.makeText(this, "Recording stopped — what's caught waits "
                    + "in My Library ▸ Recordings.", Toast.LENGTH_LONG).show();
        } else {
            ChannelStore.putRecordMeta(this, currentUrl, c.displayName());
            boolean ok = Downloads.start(this, currentUrl, lastLooksHls);
            if (ok) {
                Haptics.pulse(this);
                Toast.makeText(this, "● Recording " + c.displayName()
                        + " — it will wait in My Library ▸ Recordings.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "That channel refused the recording "
                        + "before it even started.", Toast.LENGTH_LONG).show();
            }
        }
        refreshRecButton();
    }

    // ------------------------------------------------- hold-at-full

    /**
     * The kept buffer filled (Hold mode): stop capturing, keep the
     * front. Playback continues live and uncached — the viewer (often
     * asleep) loses nothing; the held window waits in the cache for
     * the reopen offer. The kept state is marked HELD so the reopen
     * copy can say what happened, honestly.
     */
    private void latchHold() {
        holdLatched = true;
        forceUncached = true;
        ChannelStore.KeptLive k = ChannelStore.getKeptLive(this);
        if (k != null && currentUrl != null && currentUrl.equals(k.url)) {
            k.held = true;
            ChannelStore.setKeptLive(this, k);
        }
        Toast.makeText(this, LangStore.tr(this, "kept_held")
                + " — you're watching live now; the beginning is saved.",
                Toast.LENGTH_LONG).show();
        resumeBar.setVisibility(View.GONE);
        liveOfferMode = false;
        if (player != null) {
            playCurrent();
            player.setPlayWhenReady(true);
        }
    }

    // ------------------------------------------------- the kept place

    /** The kept-live heartbeat: while a live channel plays, its place is the kept place. */
    private void startKeptTicker() {
        keptTicker = new Runnable() {
            @Override
            public void run() {
                if (destroyed || player == null) return;
                saveKept();
                handler.postDelayed(this, 5000);
            }
        };
        handler.postDelayed(keptTicker, 5000);
    }

    private void saveKept() {
        if (!liveNow || isRecordingPlay || player == null
                || queue == null || index < 0 || index >= queue.size()) return;
        M3uParser.Channel c = queue.get(index);
        if (c.url == null || !LinkHealth.isRemote(c.url)) return;
        long dur = player.getDuration();
        long pos = player.getCurrentPosition();
        if (pos < 0) return;
        ChannelStore.KeptLive k = new ChannelStore.KeptLive();
        k.url = c.url;
        k.name = c.name;
        k.userAgent = c.userAgent == null ? "" : c.userAgent;
        k.positionMs = pos;
        k.behindMs = dur == C.TIME_UNSET ? 0
                : LiveResumeMath.behindMs(dur, pos);
        k.savedAtMs = System.currentTimeMillis();
        k.held = holdLatched;
        ChannelStore.setKeptLive(this, k);
    }

    // ------------------------------------------------------------- dub

    /**
     * With the dub merged in, the merged timeline carries the video's
     * own audio group(s) first and the dub's audio group last — pick
     * the last one. Runs once per source, at READY. v1 honesty: both
     * tracks start together on one timeline; if the prepared file was
     * cut slightly off, the drift is whatever the file carries — the
     * lab proof measures sync at production time, not here.
     */
    private void maybeSelectDubTrack() {
        if (!dubEnabled || dubTrackApplied || player == null || trackSelector == null) return;
        Tracks tracks = player.getCurrentTracks();
        Tracks.Group dubGroup = null;
        int audioGroups = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) {
                audioGroups++;
                dubGroup = g;
            }
        }
        if (audioGroups >= 2 && dubGroup != null && dubGroup.length > 0
                && dubGroup.isTrackSupported(0)) {
            trackSelector.setParameters(trackSelector.getParameters()
                    .buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(
                            dubGroup.getMediaTrackGroup(), 0))
                    .build());
            dubTrackApplied = true;
        }
    }

    /** Switch between the prepared dub and the original audio, in place. */
    private void setDub(boolean enabled) {
        if (dubUrl == null || enabled == dubEnabled || player == null) return;
        dubEnabled = enabled;
        ChannelStore.setDubChoice(this, contentKey, enabled);
        long keepPos = player.getCurrentPosition();
        boolean keepPlay = player.getPlayWhenReady();
        playCurrent();
        if (keepPos > 0) player.seekTo(keepPos);
        player.setPlayWhenReady(keepPlay);
        Toast.makeText(this, enabled
                ? "Audio: English — prepared dub"
                : "Audio: original soundtrack", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------- screen share (v0.7.0)

    /**
     * THE SHARE SHEET (James's order: "put the screenshare in
     * there"). Same honesty rules as Translate: the state is the
     * sheet's message, every row works or says why it can't, and
     * no surface is the only place a state lives (actions toast
     * too). The three ways on offer:
     *   Send to <TV>   — the TV takes the stream itself (remote
     *                    playback). Needs: same Wi-Fi, a receiver
     *                    that speaks it.
     *   Watching page  — Sandy serves a page + the media on this
     *                    Wi-Fi. Needs: same Wi-Fi, any TV browser.
     *   Mirror         — the phone's own screen mirroring, via
     *                    the system Cast settings door.
     */
    private void showShareDialog() {
        if (player == null || queue == null || queue.isEmpty()
                || index < 0 || index >= queue.size()) {
            Toast.makeText(this, "Nothing is playing right now — "
                    + "there's nothing to share yet.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            showShareDialogInner();
        } catch (Exception e) {
            Toast.makeText(this, "Share hit a problem on this title: "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showShareDialogInner() {
        if (shareCast == null) {
            Toast.makeText(this, LangStore.tr(this, "share_unavailable"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        M3uParser.Channel c = queue.get(index);
        final TvShare.Plan plan = recordingSharePlan != null
                ? recordingSharePlan : TvShare.planFor(c.url);
        final String title = c.displayName();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        StringBuilder msg = new StringBuilder();

        if (shareCast != null && shareCast.castActive()) {
            msg.append("Playing on ").append(shareCast.activeRouteName())
                    .append(" now — this phone is the remote.\n\n");
        }
        if (shareCast != null && shareCast.pageLive()) {
            String w = shareCast.watchUrl();
            msg.append("The watching page is LIVE. On the TV, open "
                    + "its browser and go to:\n\n")
                    .append(w == null
                            ? "(the address went missing — stop the "
                              + "page below and share it again)" : w)
                    .append("\n\nThe TV and this phone must be on "
                            + "the same Wi-Fi. The address changes "
                            + "each time you share.\n\n");
        }
        if (!shareCast.anythingLive()) {
            msg.append("Throw this picture onto the TV. Each way "
                    + "below says what it needs.\n\n");
        }
        if (isRecordingPlay && recordingSharePlan == null) {
            msg.append("This is a recording. The ways below would "
                    + "send the LIVE channel it came from — to share "
                    + "the recording itself, Sandy first pieces it "
                    + "into one file.\n\n");
            labels.add(recordingSharePreparing
                    ? "Piecing the recording together…"
                    : "Prepare this recording to share");
            actions.add(this::prepareRecordingShare);
        }

        List<MediaRouter.RouteInfo> routes = shareCast.remoteRoutes();
        for (MediaRouter.RouteInfo r : routes) {
            final MediaRouter.RouteInfo route = r;
            labels.add("Send to " + r.getName());
            actions.add(() -> flingTo(route, plan, title));
        }
        if (routes.isEmpty()) {
            msg.append("No TV has answered yet. A TV or box that "
                    + "takes sent video (Android remote playback) "
                    + "appears here on its own after a few seconds "
                    + "on the same Wi-Fi. The watching page below "
                    + "works with any TV that has a browser.\n");
        }

        if (shareCast.pageLive()) {
            labels.add("Show a code the TV can scan");
            actions.add(this::showShareQr);
            labels.add("Stop the watching page");
            actions.add(() -> {
                shareCast.stopPageServer();
                Toast.makeText(this, "The watching page is closed.",
                        Toast.LENGTH_LONG).show();
            });
        } else {
            labels.add("Share a watching page on this Wi-Fi");
            actions.add(() -> startWatchPage(plan, title));
        }
        labels.add("Mirror the whole screen…");
        actions.add(() -> {
            boolean ok = ShareCast.openCastSettings(this);
            Toast.makeText(this, ok
                    ? "Pick your TV in the phone's Cast settings — "
                      + "everything on this screen will show on the TV."
                    : "This phone doesn't expose its Cast settings "
                      + "from here. Look for “Cast” or “Screen "
                      + "mirroring” under Settings ▸ Connected devices.",
                    Toast.LENGTH_LONG).show();
        });
        if (shareCast.castActive()) {
            labels.add("Stop sending to the TV");
            actions.add(() -> {
                shareCast.stopCast();
                Toast.makeText(this, "Stopped — the picture is back "
                        + "to this phone only.", Toast.LENGTH_LONG).show();
            });
        }

        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "player_share_tv"))
                .setMessage(msg.toString())
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Start the watching page, then reopen the sheet so the
     *  address is right there in front of the viewer. */
    private void startWatchPage(TvShare.Plan plan, String title) {
        if (shareCast == null) {
            Toast.makeText(this, LangStore.tr(this, "share_unavailable"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String ip = shareCast.localIpv4();
        if (ip == null || TvShare.isLoopback(ip)) {
            Toast.makeText(this, "This phone doesn't have a Wi-Fi "
                    + "address right now — the watching page needs "
                    + "the phone and the TV on the same Wi-Fi.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            shareCast.ensurePageServer(plan, title);
        } catch (Exception e) {
            Toast.makeText(this, "The watching page wouldn't start: "
                    + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "The watching page is live — its "
                + "address is in the Share sheet.", Toast.LENGTH_LONG).show();
        showShareDialog();
    }

    /** Assemble the playing recording into its shareable file,
     *  then reopen the sheet aimed at the file. */
    private void prepareRecordingShare() {
        if (recordingSharePreparing) {
            Toast.makeText(this, "Already piecing it together…",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentUrl == null) return;
        recordingSharePreparing = true;
        final String url = currentUrl;
        Toast.makeText(this, "Piecing the recording together "
                + "into one file…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                androidx.media3.exoplayer.offline.Download d =
                        Downloads.get(PlayerActivity.this, url);
                if (d == null) {
                    throw new Exception("the recording isn't in the "
                            + "Recordings shelf anymore");
                }
                java.io.File f = RecordingExport.export(
                        PlayerActivity.this, d,
                        new java.io.File(getFilesDir(), "recording-export"));
                recordingSharePlan = TvShare.planFor(f.getAbsolutePath());
                handler.post(() -> {
                    recordingSharePreparing = false;
                    Toast.makeText(this, "The recording is one file "
                            + "now — the Share sheet aims at it.",
                            Toast.LENGTH_LONG).show();
                    showShareDialog();
                });
            } catch (Exception e) {
                final String why = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                handler.post(() -> {
                    recordingSharePreparing = false;
                    Toast.makeText(this, "Couldn't piece the recording "
                            + "together: " + why, Toast.LENGTH_LONG).show();
                });
            }
        }, "sandy-rec-share-prep").start();
    }

    /** The watching-page address as a scannable code (QrCode). */
    private void showShareQr() {
        String url = shareCast.watchUrl();
        if (url == null) {
            Toast.makeText(this, "The watching page has no address "
                    + "right now.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean[][] m = QrCode.encode(url);
        if (m == null) {
            Toast.makeText(this, "That address is too long to make "
                    + "a code from — type it on the TV instead; "
                    + "it's in the Share sheet.", Toast.LENGTH_LONG).show();
            return;
        }
        int size = m.length, quiet = 4, scale = 12;
        int px = (size + quiet * 2) * scale;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        cv.drawColor(Color.WHITE);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setColor(Color.BLACK);
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (m[r][c]) {
                    cv.drawRect((c + quiet) * scale, (r + quiet) * scale,
                            (c + quiet + 1) * scale, (r + quiet + 1) * scale,
                            paint);
                }
            }
        }
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setPadding(dp(24), dp(24), dp(24), dp(8));
        iv.setAdjustViewBounds(true);
        iv.setContentDescription("Code for the watching page address");
        new AlertDialog.Builder(this)
                .setTitle("Point the TV at this code")
                .setMessage("If the TV can't scan it, type this "
                        + "address into its browser:\n\n" + url)
                .setView(iv)
                .setPositiveButton("Close", null)
                .show();
    }

    /** Fling this title at a remote-playback route. */
    private void flingTo(MediaRouter.RouteInfo route, TvShare.Plan plan,
                         String title) {
        if (shareCast == null) {
            Toast.makeText(this, LangStore.tr(this, "share_unavailable"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        String url = TvShare.flingUrlFor(plan, null);
        if (url == null && plan.kind == TvShare.Kind.FILE) {
            // the receiver plays the page server's media address
            try {
                String ip = shareCast.localIpv4();
                if (ip == null || TvShare.isLoopback(ip)) {
                    Toast.makeText(this, "Sending a file to the TV "
                            + "goes through the watching page, and "
                            + "this phone has no Wi-Fi address right "
                            + "now.", Toast.LENGTH_LONG).show();
                    return;
                }
                shareCast.ensurePageServer(plan, title);
                url = shareCast.mediaUrl();
            } catch (Exception e) {
                Toast.makeText(this, "The watching page wouldn't "
                        + "start, so there's nothing to send: "
                        + e.getMessage(), Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (url == null) {
            Toast.makeText(this, "This title can't be sent to a TV "
                    + "from here — it isn't a stream or a file the "
                    + "TV can take.", Toast.LENGTH_LONG).show();
            return;
        }
        long pos = player != null ? player.getCurrentPosition() : 0;
        shareCast.fling(route, url, TvShare.mimeFor(plan.url), title, pos,
                spoken -> Toast.makeText(this, spoken, Toast.LENGTH_LONG).show());
        if (player != null) player.pause();
        Toast.makeText(this, "Sending to " + route.getName() + "…",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------- translation (v0.6.0)

    /** The viewer's language: translations aim at the app language. */
    private String targetLang() {
        String code = ChannelStore.getUiLanguage(this);
        return code == null || code.isEmpty() ? "en" : code;
    }

    /** True when the attached sidecar subtitle IS a cooked translation. */
    private boolean cookedSubAttached() {
        return subUrl != null && DubTrack.isTrSidecar(subUrl);
    }

    /**
     * THE TRANSLATE OPTION (field note #2f). What it offers depends
     * on what honestly exists for this title, in this order:
     *   1. a prepared dub / cooked subtitle already here -> play it now
     *   2. a cooked record here -> the voice can be made on this phone
     *   3. otherwise the cooker gates speak: engine pack missing, or
     *      pack in but no engine bound in this build — said plainly.
     */
    private void showTranslateDialog() {
        if (player == null) {
            // no silent return anywhere in this flow (0.6.0's field
            // defect): even "nothing playing" is a spoken state
            speakState("Nothing is playing right now — there's "
                    + "nothing to translate yet.", null, null);
            return;
        }
        try {
            showTranslateDialogInner();
        } catch (Exception e) {
            // a crash in this flow must still land on words
            speakState("Translate hit a problem on this title: "
                    + e.getMessage(), null, null);
        }
    }

    private void showTranslateDialogInner() {
        String lang = targetLang();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (cookedSubAttached()) {
            labels.add(LangStore.tr(this, "tr_subtitles_on") + "  ✓");
            actions.add(this::enableCookedSubtitles);
        }
        if (dubUrl != null && !dubUrl.isEmpty()) {
            labels.add("Audio: " + lang + " — prepared dub" + (dubEnabled ? "  ✓" : ""));
            actions.add(() -> setDub(true));
        }
        if (trJsonUrl != null && !trJsonUrl.isEmpty()) {
            labels.add(LangStore.tr(this, "tr_make_voice"));
            actions.add(this::startVoiceRender);
            labels.add(LangStore.tr(this, "tr_show_original")
                    + (showOriginal ? "  ✓" : ""));
            actions.add(() -> {
                showOriginal = !showOriginal;
                if (showOriginal) loadCookedLines();
                if (!showOriginal) origLineText.setVisibility(View.GONE);
            });
        }

        // The gate decides BEFORE the sheet is built, so the sheet
        // itself carries the current state as its message — in
        // 0.6.0 the sheet had no state text at all, and when the
        // single status surface failed to present, the tap read
        // as a dead row (field-reported, screenshot-confirmed).
        EnginePackStore.Status st = EnginePackStore.anyInstalled(this);
        boolean packIn = st != null && st.installed;
        boolean packOnnx = packIn && st.onnx();
        String wakingNote = null;
        if (packOnnx && boundEngine == null && engineFailWhy.isEmpty()) {
            ensureEngine(st.dir);
            if (engineWaking) {
                wakingNote = "The translation engine is waking up — "
                        + "it takes a few seconds to open. Close this "
                        + "and tap Translate again in a moment.";
            }
        }
        final TranslateGate.Decision decision = wakingNote != null ? null
                : TranslateGate.decide(
                        cookedSubAttached() || (dubUrl != null && !dubUrl.isEmpty()),
                        streamHasLangTrack(), packIn, packOnnx,
                        boundEngine != null, engineFailWhy,
                        liveNow, isRecordingPlay, recordingSettled(),
                        isLocalSource());
        final String stateText = wakingNote != null ? wakingNote
                : stateText(decision, lang);

        if (decision != null && decision.recordDoor()) {
            boolean rec = currentUrl != null
                    && Downloads.isRecording(this, currentUrl);
            labels.add(rec
                    ? "■ Stop recording — the translation cooks from the recording"
                    : "● Record this — the translation cooks from the recording");
        } else {
            labels.add("Cook a new translation…");
        }
        actions.add(() -> onCookRow(decision, lang, stateText));

        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "player_translate"))
                .setMessage(stateText)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** The cook row ACTS, for every gate outcome — no exceptions. */
    private void onCookRow(TranslateGate.Decision decision, String lang,
                           String stateText) {
        try {
            if (decision == null) {           // engine still waking
                speakState(stateText, null, null);
                return;
            }
            switch (decision.outcome) {
                case LIVE_EDGE:
                    // Delwin's record-first door: say it, then walk
                    // through it (toggleRecording speaks its own
                    // toasts as well).
                    speakState(stateText, null, null);
                    toggleRecording();
                    return;
                case COOK:
                    startCook(lang);
                    return;
                case EXISTING:
                case STREAM_HAS_LANG: {
                    // a re-cook request: re-decide without the
                    // "already here" facts and act on what remains
                    EnginePackStore.Status st = EnginePackStore.anyInstalled(this);
                    boolean packIn = st != null && st.installed;
                    TranslateGate.Decision d2 = TranslateGate.decide(
                            false, false, packIn, packIn && st.onnx(),
                            boundEngine != null, engineFailWhy,
                            liveNow, isRecordingPlay, recordingSettled(),
                            isLocalSource());
                    if (d2.outcome == TranslateGate.Outcome.COOK) {
                        startCook(lang);
                    } else if (d2.recordDoor()) {
                        speakState(stateText(d2, lang), null, null);
                        toggleRecording();
                    } else {
                        speakState(stateText(d2, lang), null, null);
                    }
                    return;
                }
                default:
                    speakState(stateText, null, null);
            }
        } catch (Exception e) {
            speakState("Translate hit a problem on this title: "
                    + e.getMessage(), null, null);
        }
    }

    /** Every Translate state text, in one place. */
    private String stateText(TranslateGate.Decision d, String lang) {
        switch (d.outcome) {
            case EXISTING:
                return "A translation for this title is already here — "
                        + "it's in the list above. Tapping “Cook a new "
                        + "translation…” makes a fresh one anyway.";
            case STREAM_HAS_LANG:
                return "Good news — this stream already carries "
                        + "subtitles in your language. Turn them on "
                        + "under Subtitles / Audio; there's nothing "
                        + "to cook.";
            case NEEDS_PACK:
                return LangStore.tr(this, "tr_needs_pack");
            case PACK_UNBOUND:
                return "An engine pack is installed, but it's the "
                        + "older CTranslate2 kind — this build's "
                        + "engine runs the ONNX pack. Get "
                        + "sandy-engine-ja-en-onnx.pack from the shelf "
                        + "(Brain ▸ Programs) and install it in "
                        + "Settings ▸ Translation.";
            case ENGINE_FAILED:
                return "The translation engine would not start on "
                        + "this phone: " + d.detail;
            case LIVE_EDGE:
                return "Live can't be translated from the edge — "
                        + "the words are gone before they could be "
                        + "cooked. Record it instead (● Record, in "
                        + "the list below): the translation cooks "
                        + "from the recording, and the dubbed "
                        + "version will be waiting when you come to "
                        + "watch it. It doesn't have to be real-time "
                        + "to be yours.";
            case RECORDING_NEXT:
                return "This recording is still being made — its "
                        + "pieces aren't all caught yet, and a "
                        + "translation cooks from the whole thing. "
                        + "Stop the recording first (in My Library "
                        + "▸ Recordings), then come back and cook "
                        + "it here.";
            case STREAM_COOK:
                return "This title streams from the network. This "
                        + "phone's cooker cooks local files; network "
                        + "titles are cooked once, on the shelf, and "
                        + "arrive as a prepared translation — if this "
                        + "title has one, it appears in the list above.";
            case COOK:
            default:
                if (isRecordingPlay) {
                    return "Ready to cook a translation from this "
                            + "recording. Sandy first pieces the "
                            + "recording together into one file it "
                            + "can work from, then cooks — subtitles "
                            + "and, from the cooked record, the "
                            + "voice, like any other cooked title.";
                }
                return "Ready to cook a translation of this title "
                        + "on this phone. Tap “Cook a new "
                        + "translation…” — it cooks a few minutes "
                        + "ahead and tells you when there's enough "
                        + "to start watching.";
        }
    }

    /**
     * v0.7.0: a recording can be cooked once it is SETTLED —
     * stopped or finished catching, with bytes in hand. While
     * it is still being made, its spans aren't complete and the
     * gate keeps the honest RECORDING_NEXT.
     */
    private boolean recordingSettled() {
        if (!isRecordingPlay || currentUrl == null) return false;
        try {
            androidx.media3.exoplayer.offline.Download d =
                    Downloads.get(this, currentUrl);
            if (d == null || d.getBytesDownloaded() <= 0) return false;
            return d.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
                    || d.state == androidx.media3.exoplayer.offline.Download.STATE_STOPPED
                    || d.state == androidx.media3.exoplayer.offline.Download.STATE_FAILED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Say a Translate state on BOTH spoken surfaces: the status
     *  bar and a toast. 0.6.0 bet everything on the bar alone. */
    private void speakState(String msg, String btnLabel, Runnable btnAction) {
        showTrBar(msg, btnLabel, btnAction);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Wake the engine on a worker thread (idempotent). */
    private void ensureEngine(final java.io.File packDir) {
        if (boundEngine != null || engineWaking || !engineFailWhy.isEmpty()) return;
        engineWaking = true;
        new Thread(() -> {
            try {
                boundEngine = OnnxCookerEngine.open(packDir);
            } catch (Exception e) {
                engineFailWhy = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                engineWaking = false;
            }
        }, "sandy-engine-wake").start();
    }

    /** True when the playing stream itself carries a text track in
     *  the viewer's language (the player knows — the 0.6.0 sheet
     *  didn't ask). */
    private boolean streamHasLangTrack() {
        if (player == null) return false;
        String want = targetLang();
        try {
            Tracks tracks = player.getCurrentTracks();
            for (Tracks.Group g : tracks.getGroups()) {
                if (g.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < g.length; i++) {
                    Format f = g.getTrackFormat(i);
                    if (f.language != null && langMatches(f.language, want)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean langMatches(String trackLang, String want) {
        String a = trackLang.toLowerCase(Locale.US);
        int cut = a.length();
        int dash = a.indexOf('-');
        if (dash >= 0) cut = Math.min(cut, dash);
        int under = a.indexOf('_');
        if (under >= 0) cut = Math.min(cut, under);
        a = a.substring(0, cut);
        // ISO 639-2/3 -> 639-1 for the baked UI languages
        switch (a) {
            case "eng": a = "en"; break;
            case "jpn": a = "ja"; break;
            case "spa": a = "es"; break;
            case "fra": case "fre": a = "fr"; break;
            case "deu": case "ger": a = "de"; break;
            case "ita": a = "it"; break;
            case "por": a = "pt"; break;
            case "rus": a = "ru"; break;
            case "kor": a = "ko"; break;
            case "zho": case "chi": a = "zh"; break;
            case "ara": a = "ar"; break;
            case "hin": a = "hi"; break;
            default: break;
        }
        return a.equals(want);
    }

    /** A source the on-phone cooker can open: a local file. */
    private boolean isLocalSource() {
        return currentUrl != null && (currentUrl.startsWith("file://")
                || currentUrl.startsWith("content://")
                || currentUrl.startsWith("/"));
    }

    /**
     * Cook this title's translation on the phone: decode its audio
     * to PCM, run the bound engine chunk by chunk (the Cooker FSM
     * hears every chunk), write the cooked record beside the other
     * cooked files, and offer it through the bar.
     */
    private void startCook(String lang) {
        if (boundEngine == null) {
            speakState("The translation engine is not awake yet — "
                    + "tap Translate again in a few seconds.", null, null);
            return;
        }
        if (subCooking) {
            speakState("A translation is already cooking for this "
                    + "title — its progress is in the bar.", null, null);
            return;
        }
        long durSec = player == null ? 0
                : Math.max(0, player.getDuration() / 1000);
        trCooker = new Cooker(durSec);
        Cooker.State s = trCooker.begin(false, Cooker.PackStatus.INSTALLED,
                boundEngine);
        if (s != Cooker.State.COOKING) {
            speakState("The cooker would not start: " + s, null, null);
            return;
        }
        subCooking = true;
        subGoShown = false;
        showTrBar(LangStore.tr(this, "tr_cooking"), null, null);
        final String url = currentUrl;
        final String showKey = contentKey != null ? contentKey
                : DubTrack.stemOf(queue.get(index).name);
        final OnnxCookerEngine engine = boundEngine;
        final java.io.File dir = new java.io.File(getFilesDir(), "cooked");
        if (!dir.isDirectory()) dir.mkdirs();
        final String safe = showKey.replaceAll("[^A-Za-z0-9._-]", "_");
        subSrtFile = new java.io.File(dir, safe + ".tr-" + lang + ".srt");
        subJsonFile = new java.io.File(dir, safe + ".tr-" + lang + ".json");
        final boolean fromRecording = isRecordingPlay;
        new Thread(() -> {
            try {
                java.io.File tmpDir = new java.io.File(getCacheDir(), "cookpcm");
                if (!tmpDir.isDirectory()) tmpDir.mkdirs();
                String decodeUrl = url;
                if (fromRecording) {
                    // v0.7.0: the recording's spans become ONE
                    // file first (RecordingExport), and the cooker
                    // cooks from that file like any other. A hole
                    // or a scramble fails HERE, in words.
                    handler.post(() -> showTrBar("Piecing the "
                            + "recording together into one file…",
                            null, null));
                    androidx.media3.exoplayer.offline.Download d =
                            Downloads.get(PlayerActivity.this, url);
                    if (d == null) {
                        throw new Exception("the recording isn't in "
                                + "the Recordings shelf anymore");
                    }
                    java.io.File outDir = new java.io.File(
                            getFilesDir(), "recording-export");
                    java.io.File assembled = RecordingExport.export(
                            PlayerActivity.this, d, outDir);
                    decodeUrl = assembled.getAbsolutePath();
                }
                PcmSource src = AudioPcmDecoder.decode(this, decodeUrl, tmpDir);
                List<CookedSubs.Line> lines = engine.cook(src,
                        (idx, chunkSec, wallSec, soFar) -> handler.post(() -> {
                            if (trCooker != null) {
                                trCooker.onChunkCooked(chunkSec, wallSec);
                            }
                            // partial record on disk per chunk, so
                            // the GO sign can attach what's cooked
                            // and playback rides the lead
                            if (!soFar.isEmpty()) {
                                try {
                                    writeCookedFiles(lang, soFar);
                                    cookedLines = soFar;
                                } catch (Exception ignored) {}
                            }
                        }));
                handler.post(() -> {
                    subCooking = false;
                    if (lines.isEmpty()) {
                        speakState("The cook finished, but it heard "
                                + "no speech it could translate in "
                                + "this title.", null, null);
                        return;
                    }
                    try {
                        writeCookedFiles(lang, lines);
                    } catch (Exception ignored) {}
                    cookedLines = lines;
                    boolean riding = trCooker != null
                            && trCooker.state() == Cooker.State.RIDING;
                    speakState("Translation cooked: " + lines.size()
                            + " lines, originals kept alongside. "
                            + "Tap Watch to put them on.",
                            "Watch", () -> {
                                trBar.setVisibility(View.GONE);
                                attachCookedSubs();
                            });
                    if (riding) attachCookedSubs();  // refresh to the full record
                    Haptics.pulse(PlayerActivity.this);
                });
            } catch (Exception e) {
                final String why = e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage();
                handler.post(() -> {
                    subCooking = false;
                    if (trCooker != null) trCooker.onFail(why);
                    speakState("The cook failed: " + why, null, null);
                });
            }
        }, "sandy-cooker").start();
    }

    /** Write the cooked record (SRT + the JSON with originals) and
     *  point the player's sidecar fields at it. */
    private void writeCookedFiles(String lang, List<CookedSubs.Line> lines)
            throws Exception {
        java.nio.file.Files.write(subSrtFile.toPath(),
                CookedSubs.writeSrt(lines).getBytes("UTF-8"));
        java.nio.file.Files.write(subJsonFile.toPath(),
                CookedSubs.writeJson(lang, lines).getBytes("UTF-8"));
        subUrl = Uri.fromFile(subSrtFile).toString();
        subMime = "application/x-subrip";
        subLang = lang;
        trJsonUrl = Uri.fromFile(subJsonFile).toString();
    }

    /** Put the just-cooked subtitles on the playing title, keeping
     *  the viewer's place (the setDub pattern). */
    private void attachCookedSubs() {
        if (player == null || subUrl == null) return;
        long keepPos = player.getCurrentPosition();
        boolean keepPlay = player.getPlayWhenReady();
        playCurrent();
        if (keepPos > 0) player.seekTo(keepPos);
        player.setPlayWhenReady(keepPlay);
        enableCookedSubtitles();
        if (trCooker != null) trCooker.onPlayTranslated();
    }

    private void showTrBar(String text, String btnLabel, Runnable btnAction) {
        trBarText.setText(text);
        if (btnLabel != null && btnAction != null) {
            trBarBtn.setText(btnLabel);
            trBarBtn.setContentDescription(btnLabel);
            trBarBtn.setOnClickListener(v -> btnAction.run());
            trBarBtn.setVisibility(View.VISIBLE);
        } else {
            trBarBtn.setVisibility(View.GONE);
        }
        trBar.setVisibility(View.VISIBLE);
    }

    /**
     * The quality POSTURE in the player (v0.12.1): LOW caps the
     * video size so playback starts fast and stays smooth;
     * BALANCED lets media3's adaptive selection ride the measured
     * pipe (it steps down before it stalls); ULTRA forces the
     * highest variant and SAYS when the pipe will make it buffer —
     * the pipe is stated as the reason, never dressed up as a
     * settings problem. The posture note from the source pick is
     * toasted so the viewer sees what shaped the picture.
     */
    private void applyPosture() {
        try {
            String pref = getIntent().getStringExtra("posture");
            AutoPlay.Posture p = pref != null
                    ? AutoPlay.parsePosture(pref)
                    : AutoPlay.parsePosture(
                            ChannelStore.getQualityPosture(this));
            DefaultTrackSelector.Parameters.Builder b =
                    trackSelector.getParameters().buildUpon();
            if (p == AutoPlay.Posture.LOW) {
                b.setMaxVideoSize(854, 480);
            } else if (p == AutoPlay.Posture.ULTRA) {
                b.setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
                b.setForceHighestSupportedBitrate(true);
            }
            trackSelector.setParameters(b.build());
            String note = getIntent().getStringExtra("postureNote");
            if (note != null && !note.isEmpty()) {
                Toast.makeText(this, note, Toast.LENGTH_LONG).show();
            }
            if (p == AutoPlay.Posture.ULTRA) {
                double bps = PipeMeter.measuredBps(this);
                if (PipeMeter.pipeClass(bps) == 1) {
                    Toast.makeText(this, "Ultra on a slow connection "
                            + "(" + PipeMeter.mbpsText(bps) + " Mbps "
                            + "measured) — getting your top quality; "
                            + "expect buffering. The connection is the "
                            + "limit, not the settings.",
                            Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            CrashGuard.log(this, "PlayerActivity.applyPosture", e);
        }
    }

    /** Turn the cooked subtitle track on (it renders as real text). */
    private void enableCookedSubtitles() {
        if (trackSelector == null || player == null) return;
        Tracks tracks = player.getCurrentTracks();
        Tracks.Group chosen = null;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT || g.length == 0) continue;
            chosen = g;
            Format f = g.getTrackFormat(0);
            if (subLang != null && subLang.equals(f.language)) break;
        }
        if (chosen != null) {
            trackSelector.setParameters(trackSelector.getParameters()
                    .buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(
                            chosen.getMediaTrackGroup(), 0))
                    .build());
            Toast.makeText(this, LangStore.tr(this, "tr_subtitles_on"),
                    Toast.LENGTH_SHORT).show();
            if (trJsonUrl != null && !trJsonUrl.isEmpty()) loadCookedLines();
        } else {
            Toast.makeText(this, "The cooked subtitle track is not "
                    + "loaded on this title.", Toast.LENGTH_LONG).show();
        }
    }

    /** Read the cooked record (original lines ride along — Law 1). */
    private void loadCookedLines() {
        if (cookedLines != null || trJsonUrl == null) return;
        new Thread(() -> {
            try {
                String text;
                if (trJsonUrl.startsWith("content://") || trJsonUrl.startsWith("file://")) {
                    try (java.io.InputStream in = getContentResolver()
                            .openInputStream(Uri.parse(trJsonUrl))) {
                        text = new String(readAll(in), "UTF-8");
                    }
                } else {
                    text = new String(java.nio.file.Files.readAllBytes(
                            java.nio.file.Paths.get(trJsonUrl)), "UTF-8");
                }
                final List<CookedSubs.Line> lines = CookedSubs.parseJson(text);
                handler.post(() -> cookedLines = lines);
            } catch (Exception ignored) {}
        }, "cooked-lines-load").start();
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /**
     * Make it speak: the phone's own speech engine renders the
     * cooked lines into a dub, cast from the show's voice book.
     * Progress is cooked seconds; the GO SIGN (lead >= threshold,
     * LeadMath) offers the voice while the rest keeps cooking.
     */
    private void startVoiceRender() {
        if (voiceCooking) return;
        loadCookedLines();
        if (cookedLines == null) {
            // The record may still be loading; the render starts on
            // the next tap once it lands — said plainly, not spun.
            Toast.makeText(this, "The cooked record is still loading — "
                    + "try again in a moment.", Toast.LENGTH_SHORT).show();
            return;
        }
        String lang = targetLang();
        String showKey = contentKey != null ? contentKey
                : DubTrack.stemOf(queue.get(index).name);
        VoiceBook.Book book = VoiceBookStore.load(this, showKey);
        if (book.showTitle.isEmpty()) {
            book.showTitle = queue.get(index).displayName();
            if (book.cast.isEmpty()) {
                // First dub for this show: the book opens with one
                // narrator entry; per-character entries arrive with
                // diarized records (see NOTES — not faked here).
                VoiceBook.CastEntry lead = VoiceBook.enroll(book, "", null);
                lead.name = "";
                VoiceBookStore.save(this, book);
            }
        }
        java.io.File dir = new java.io.File(getFilesDir(), "cooked");
        if (!dir.isDirectory()) dir.mkdirs();
        String safe = showKey.replaceAll("[^A-Za-z0-9._-]", "_");
        voiceOutFile = new java.io.File(dir, safe + ".dub-" + lang + ".wav");
        voiceCooking = true;
        voiceRenderedSec = 0;
        voiceGoShown = false;
        showTrBar(LangStore.tr(this, "tr_cooking"), null, null);
        ttsRenderer = new TtsDubRenderer(this);
        final long startWall = System.currentTimeMillis();
        ttsRenderer.render(cookedLines, book, lang, voiceOutFile,
                new TtsDubRenderer.Callback() {
                    @Override public void onProgress(double cookedSec) {
                        handler.post(() -> voiceRenderedSec = cookedSec);
                    }
                    @Override public void onDone(java.io.File wav, TtsDubRenderer.Report rep) {
                        handler.post(() -> {
                            voiceCooking = false;
                            voiceRenderedSec = rep.spokenSec;
                            dubUrl = Uri.fromFile(wav).toString();
                            // Offer the finished voice through the
                            // same door as any prepared dub.
                            showTrBar(LangStore.tr(PlayerActivity.this, "tr_voice_ready"),
                                    "Play", () -> {
                                        trBar.setVisibility(View.GONE);
                                        setDub(true);
                                    });
                            Haptics.pulse(PlayerActivity.this);
                        });
                    }
                    @Override public void onError(String why) {
                        handler.post(() -> {
                            voiceCooking = false;
                            showTrBar("The voice could not be made: " + why, null, null);
                        });
                    }
                });
    }

    /** Per-tick translation UI: the voice cook's lead + the go sign,
     *  and the parallel original line under the translation. */
    private void updateTranslateUi() {
        if (destroyed || player == null) return;
        if (subCooking && trCooker != null) {
            double played = player.getCurrentPosition() / 1000.0;
            trCooker.onTick(played);
            Cooker.State cs = trCooker.state();
            String txt;
            if (cs == Cooker.State.STARVED) {
                txt = LangStore.tr(this, "tr_starved");
            } else if (trCooker.goLit()) {
                txt = LangStore.tr(this, "tr_ready")
                        + "  +" + LeadMath.formatLead(trCooker.leadSec());
                if (!subGoShown) {
                    subGoShown = true;
                    Haptics.pulse(this);
                    // the GO sign: enough is cooked to start
                    // watching; the rest keeps cooking
                    showTrBar(txt, "Watch", () -> {
                        trBar.setVisibility(View.GONE);
                        attachCookedSubs();
                    });
                }
            } else {
                txt = LangStore.tr(this, "tr_cooking")
                        + "  +" + LeadMath.formatLead(trCooker.leadSec());
            }
            trBarText.setText(txt);
            if (trBar.getVisibility() != View.VISIBLE) {
                trBar.setVisibility(View.VISIBLE);
            }
        }
        if (voiceCooking) {
            double played = player.getCurrentPosition() / 1000.0;
            double dur = Math.max(0, player.getDuration() / 1000.0);
            double lead = LeadMath.leadSec(voiceRenderedSec, played);
            String txt = LangStore.tr(this, "tr_cooking")
                    + "  +" + LeadMath.formatLead(lead);
            if (!voiceGoShown && dur > 0
                    && LeadMath.isGo(voiceRenderedSec, played, dur)) {
                voiceGoShown = true;
                Haptics.pulse(this);
                txt = LangStore.tr(this, "tr_ready");
            }
            if (lead < 0) {
                txt = LangStore.tr(this, "tr_starved");
            }
            trBarText.setText(txt);
            if (trBar.getVisibility() != View.VISIBLE) {
                trBar.setVisibility(View.VISIBLE);
            }
        }
        if (showOriginal && cookedLines != null) {
            long pos = player.getCurrentPosition();
            String orig = null;
            for (CookedSubs.Line l : cookedLines) {
                if (pos >= l.startMs && pos < l.endMs) { orig = l.orig; break; }
            }
            if (orig != null && !orig.isEmpty()) {
                origLineText.setText(LangStore.tr(this, "tr_show_original")
                        + ": " + orig);
                origLineText.setVisibility(View.VISIBLE);
            } else {
                origLineText.setVisibility(View.GONE);
            }
        }
    }

    // ------------------------------------------------------------- tracks

    private void showTracksDialog() {
        if (player == null || trackSelector == null) return;
        Tracks tracks = player.getCurrentTracks();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        // Prepared dub (v0.4.0): a sidecar beside the library file is
        // offered by name, at the top, as a soundtrack choice.
        if (dubUrl != null && !dubUrl.isEmpty()) {
            labels.add("Audio: English — prepared dub" + (dubEnabled ? "  ✓" : ""));
            actions.add(() -> setDub(true));
            labels.add("Audio: Original soundtrack" + (!dubEnabled ? "  ✓" : ""));
            actions.add(() -> setDub(false));
        }

        boolean anyText = false;
        int audioTracks = 0;
        // On live, stream text tracks ARE the closed captions — say
        // so (v0.5.0, item 6). The tracks themselves render as real
        // text through the player's subtitle view, as before.
        final String textKind = liveNow
                ? LangStore.tr(this, "cc_label") : "Subtitles";
        // Which audio group is the dub's (the merged source's last one)?
        int audioGroupTotal = 0;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() == C.TRACK_TYPE_AUDIO) audioGroupTotal++;
        }
        int audioGroupSeen = 0;
        // Subtitles first: an Off entry, then every text track the stream carries.
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                if (!anyText) {
                    anyText = true;
                    labels.add(textKind + ": Off");
                    actions.add(() -> {
                        trackSelector.setParameters(trackSelector.getParameters()
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build());
                        Toast.makeText(this, "Subtitles off", Toast.LENGTH_SHORT).show();
                    });
                }
                Format f = g.getTrackFormat(i);
                String name = textKind + ": " + trackName(f)
                        + (g.isTrackSelected(i) ? "  ✓" : "");
                final Tracks.Group fg = g;
                final int fi = i;
                labels.add(name);
                actions.add(() -> {
                    trackSelector.setParameters(trackSelector.getParameters()
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(new TrackSelectionOverride(
                                    fg.getMediaTrackGroup(), fi))
                            .build());
                    Toast.makeText(this, textKind + ": " + trackName(f),
                            Toast.LENGTH_SHORT).show();
                });
            }
        }
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            audioGroupSeen++;
            boolean isDubGroup = dubEnabled && audioGroupTotal >= 2
                    && audioGroupSeen == audioGroupTotal;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                audioTracks++;
                Format f = g.getTrackFormat(i);
                String extra = f.bitrate > 0 ? "  ·  " + (f.bitrate / 1000) + " kbps" : "";
                String name = "Audio: " + (isDubGroup
                        ? "English — prepared dub" : trackName(f)) + extra
                        + (g.isTrackSelected(i) ? "  ✓" : "");
                final Tracks.Group fg = g;
                final int fi = i;
                labels.add(name);
                actions.add(() -> {
                    trackSelector.setParameters(trackSelector.getParameters()
                            .buildUpon()
                            .setOverrideForType(new TrackSelectionOverride(
                                    fg.getMediaTrackGroup(), fi))
                            .build());
                    Toast.makeText(this, "Audio: " + trackName(f),
                            Toast.LENGTH_SHORT).show();
                });
            }
        }

        if (!anyText && audioTracks <= 1 && dubUrl == null) {
            String msg = "No subtitles on this channel.";
            if (audioTracks <= 1) {
                msg += "\n\nOnly one audio track, too — there's nothing to switch. "
                        + "Subtitles and extra audio only exist when the stream itself "
                        + "carries them.";
            }
            new AlertDialog.Builder(this)
                    .setTitle(queue.get(index).name)
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(LangStore.tr(this, "player_subtitles_audio"))
                .setItems(labels.toArray(new String[0]),
                        (d, which) -> actions.get(which).run())
                .show();
    }

    private static String trackName(Format f) {
        if (f.label != null && !f.label.isEmpty()) return f.label;
        if (f.language != null && !f.language.isEmpty()
                && !"und".equals(f.language)) return f.language;
        if (f.id != null && !f.id.isEmpty()) return f.id;
        return "track";
    }

    // ------------------------------------------------- resume / watched state

    private void startPositionTicker() {
        if (placeKey() == null) return;
        positionTicker = new Runnable() {
            @Override
            public void run() {
                if (destroyed || player == null) return;
                savePlace();
                handler.postDelayed(this, 5000);
            }
        };
        handler.postDelayed(positionTicker, 5000);
    }

    private void savePlace() {
        String key = placeKey();
        if (key == null || player == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (pos <= 0) return;
        if (dur != C.TIME_UNSET && dur > 0 && pos >= dur * 0.85) {
            ChannelStore.setWatched(this, key, true);
            ChannelStore.savePosition(this, key, 0);
        } else {
            ChannelStore.savePosition(this, key, pos);
        }
    }

    @Override
    protected void onPause() {
        savePlace();
        saveKept();
        super.onPause();
    }

    // ------------------------------------------------- continuity (v0.14.1)
    // The Reconnect Doctrine in code: a playing stream's first
    // answer to trouble is RECONNECT — an exhaustive Hydra walk
    // (proven to the segment tier), a re-prepare on whichever
    // door answers, resume at the live edge. Recovery happens
    // under buffer cover where the buffer reaches; where it
    // doesn't, the frozen frame + "Reconnecting…" is the honest
    // hold — on the SAME channel, never a silent jump. Verdicts
    // come only from PlaybackContinuity.decide, and the viewer's
    // words name what happened (the source ended / the video
    // stopped / no answer), never a mystery skip.

    /** Is this error the window refusing to advance? media3's
     *  stuck-playlist exception, or the playhead falling out of
     *  the live window entirely. */
    private static boolean isWindowStuckError(PlaybackException error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof HlsPlaylistTracker.PlaylistStuckException
                    || t instanceof BehindLiveWindowException) return true;
            t = t.getCause();
        }
        return false;
    }

    /** The HTTP result inside a player error, for the record:
     *  "HTTP 403 (refused)", or the failure's own class name. */
    private static String errorResultText(PlaybackException error) {
        Throwable t = error;
        Throwable deepest = error;
        while (t != null) {
            if (t instanceof androidx.media3.datasource.HttpDataSource
                    .InvalidResponseCodeException) {
                int code = ((androidx.media3.datasource.HttpDataSource
                        .InvalidResponseCodeException) t).responseCode;
                return "HTTP " + code + " ("
                        + FailureWords.kindForHttp(code) + ")";
            }
            deepest = t;
            t = t.getCause();
        }
        return deepest == null ? "player error"
                : deepest.getClass().getSimpleName();
    }

    /**
     * The record's facts, gathered on the main thread: channel,
     * playlist address, the pinger's latest segment evidence,
     * position and runway at this moment. (Worker-thread writers
     * use {@link #pingFacts} instead — the player is main-thread
     * property.)
     */
    private PlaybackRecord.Facts playbackFacts() {
        PlaybackRecord.Facts f = new PlaybackRecord.Facts();
        f.channel = currentChannelName;
        f.playlistUrl = currentUrl == null ? "" : currentUrl;
        ActivePinger.Sample s = lastPing;
        if (s != null && s.playlistUrl.equals(f.playlistUrl)) {
            f.playlistResult = s.describe();
            f.segmentUrl = s.segmentUrl;
            f.segmentHost = s.segmentHost;
            f.segmentResult = s.segmentHttp > 0
                    ? "HTTP " + s.segmentHttp : "";
        }
        if (f.segmentHost.isEmpty()) {
            String h = Hydra.hostOf(f.playlistUrl);
            f.segmentHost = h == null ? "" : h;
        }
        if (player != null) {
            f.positionMs = player.getCurrentPosition();
            f.bufferedMs = Math.max(0, player.getBufferedPosition()
                    - player.getCurrentPosition());
        }
        return f;
    }

    /** Facts from a pinger sample alone (safe on its thread). */
    private PlaybackRecord.Facts pingFacts(ActivePinger.Sample s) {
        PlaybackRecord.Facts f = new PlaybackRecord.Facts();
        f.channel = currentChannelName;
        f.playlistUrl = s.playlistUrl;
        f.playlistResult = s.describe();
        f.segmentUrl = s.segmentUrl;
        f.segmentHost = s.segmentHost;
        f.segmentResult = s.segmentHttp > 0 ? "HTTP " + s.segmentHttp : "";
        return f;
    }

    /** Enter maintenance for the playing channel. Main thread.
     *  The player is NOT stopped on entry: whatever runway it
     *  still holds is the cover the walk happens under, and if
     *  the player heals itself (READY), the READY handler closes
     *  the maintenance as recovered. */
    private void startRecovery(int family) {
        if (destroyed || recovering) return;
        recovering = true;
        stallWordsShown = false;
        recoveryStartedMs = System.currentTimeMillis();
        recoveryFacts = playbackFacts();
        final int gen = ++recoveryGen;
        cancelWatchdog();
        if (player == null || !player.isPlaying()) {
            // The picture is already frozen: say the plain word.
            // (A pinger-triggered start while the picture still
            // plays stays quiet — maintenance under full cover
            // is invisible by design.)
            Toast.makeText(this, LangStore.tr(this, "player_reconnecting"),
                    Toast.LENGTH_SHORT).show();
        }
        spawnRecoveryLoop(gen, family);
    }

    private void spawnRecoveryLoop(int gen, int family) {
        if (recoveryLoopRunning) return;
        recoveryLoopRunning = true;
        new Thread(() -> recoveryLoop(gen, family),
                "continuity-recovery").start();
    }

    /**
     * The maintenance loop: walk the ladder exhaustively; a door
     * proven to the segment tier (or answering slowly — slow is
     * not dead) means re-prepare and resume; otherwise classify
     * from the full evidence and let the law decide. Cycles are
     * uncapped while any layer answers; the loop dies with its
     * generation (channel change, verdict, destroy).
     */
    private void recoveryLoop(int gen, int family0) {
        int family = family0;
        long backoff = PlaybackContinuity.BACKOFF_START_MS;
        try {
            while (!destroyed && recovering && gen == recoveryGen) {
                if (queue == null || index < 0 || index >= queue.size()) {
                    return;
                }
                final M3uParser.Channel c = queue.get(index);
                HydraResolver.Outcome o = HydraResolver.proveExhaustive(
                        getApplicationContext(), c.url, c.userAgent, null);
                if (destroyed || !recovering || gen != recoveryGen) return;
                PlaybackRecord.Facts f = recoveryFacts == null
                        ? new PlaybackRecord.Facts() : copyFacts(recoveryFacts);
                f.headsTried = PlaybackRecord.headsTriedText(o.tried);
                if (o.result != null
                        && (o.result.tier == LinkHealth.TIER_PLAYS
                                || o.result.slow)) {
                    // a door serves the video: re-prepare on it
                    final String door = o.url;
                    f.decision = "RECONNECT";
                    f.family = PlaybackContinuity.familyName(family);
                    f.note = door.equals(c.url)
                            ? "the door answered again — re-preparing"
                            : "another door answered — re-preparing there";
                    PlaybackRecord.write(getApplicationContext(),
                            PlaybackRecord.EV_RECONNECT, f);
                    handler.post(() -> {
                        if (destroyed || gen != recoveryGen) return;
                        if (!door.equals(c.url)) c.url = door;
                        maintenanceReprepare = true;
                        playCurrent();
                        if (player != null) player.setPlayWhenReady(true);
                    });
                    return;
                }
                Hydra.Tried worst = Hydra.mostInformative(o.tried);
                int bestHttp = worst != null ? worst.httpCode
                        : (o.result != null ? o.result.httpCode : 0);
                family = PlaybackContinuity.classifyWalk(o.bestTier, bestHttp);
                long elapsed = System.currentTimeMillis() - recoveryStartedMs;
                int decision = PlaybackContinuity.decide(family, true, elapsed);
                f.family = PlaybackContinuity.familyName(family);
                f.decision = decision == PlaybackContinuity.ADVANCE
                        ? "ADVANCE" : decision == PlaybackContinuity.HOLD_STALL
                                ? "HOLD_STALL" : "RECONNECT";
                f.note = "maintenance cycle: best tier " + o.bestTier
                        + ", " + elapsed + " ms in";
                PlaybackRecord.write(getApplicationContext(),
                        PlaybackRecord.EV_RECONNECT, f);
                if (decision == PlaybackContinuity.ADVANCE) {
                    final int fam = family;
                    final HydraResolver.Outcome out = o;
                    handler.post(() -> verdictAdvance(fam, out));
                    return;
                }
                if (decision == PlaybackContinuity.HOLD_STALL
                        && !stallWordsShown) {
                    stallWordsShown = true;
                    handler.post(() -> {
                        if (!destroyed) {
                            Toast.makeText(PlayerActivity.this,
                                    LangStore.tr(PlayerActivity.this,
                                            "player_stream_stalled"),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    return;
                }
                backoff = Math.min(backoff + 2000,
                        PlaybackContinuity.BACKOFF_MAX_MS);
            }
        } finally {
            // A newer generation's loop owns the flag now; only
            // the current generation may clear it.
            if (gen == recoveryGen) recoveryLoopRunning = false;
        }
    }

    private static PlaybackRecord.Facts copyFacts(PlaybackRecord.Facts f) {
        PlaybackRecord.Facts out = new PlaybackRecord.Facts();
        out.channel = f.channel;
        out.playlistUrl = f.playlistUrl;
        out.playlistResult = f.playlistResult;
        out.segmentUrl = f.segmentUrl;
        out.segmentHost = f.segmentHost;
        out.segmentResult = f.segmentResult;
        out.positionMs = f.positionMs;
        out.bufferedMs = f.bufferedMs;
        return out;
    }

    /**
     * The verdict, when the law finally allows one: the words
     * name what ended, the health record files the walked truth,
     * and the advance (when the viewer left auto-skip on) is a
     * verdict, not a strike — consecutiveFails is untouched.
     */
    private void verdictAdvance(int family, HydraResolver.Outcome o) {
        if (destroyed) return;
        recovering = false;
        recoveryGen++;
        PlaybackRecord.Facts f = recoveryFacts == null
                ? playbackFacts() : copyFacts(recoveryFacts);
        f.headsTried = PlaybackRecord.headsTriedText(o.tried);
        f.family = PlaybackContinuity.familyName(family);
        f.decision = "ADVANCE";
        String words;
        if (family == PlaybackContinuity.PLAYLIST_GONE) {
            words = LangStore.tr(this, "player_stream_ended");
            f.note = "the playlist is terminally gone on every door";
            recordHealthTier(LinkHealth.TIER_DEAD, "stream ended mid-play");
        } else if (family == PlaybackContinuity.SEGMENT_REFUSED) {
            words = "This channel's list still answers, but its video "
                    + "stopped coming — the source stopped sending it.";
            f.note = "segments refused on every door; the list answers";
            recordHealthTier(LinkHealth.TIER_CONNECTS,
                    "played, then the video stopped");
        } else {
            words = FailureWords.englishFor(o.result);
            f.note = "no answer anywhere on the ladder";
            recordHealthTier(LinkHealth.TIER_DEAD, "no answer after playing");
        }
        PlaybackRecord.write(this, PlaybackRecord.EV_VERDICT, f);
        if (player != null) player.stop();
        boolean canSkip = ChannelStore.getAutoSkip(this)
                && queue != null && queue.size() > 1;
        if (canSkip) {
            Toast.makeText(this, words + " Moving on…",
                    Toast.LENGTH_LONG).show();
            handler.postDelayed(() -> { if (!destroyed) step(1); }, 1200);
        } else {
            errorText.setText(words + "\n\n" + queue.get(index).name);
            errorBox.setVisibility(View.VISIBLE);
        }
    }

    // ------------------------------------------------ the Active Pinger

    private void restartPinger() {
        stopPinger();
        if (!liveNow || isRecordingPlay || destroyed) return;
        if (queue == null || index < 0 || index >= queue.size()) return;
        M3uParser.Channel c = queue.get(index);
        if (c == null || !LinkHealth.isRemote(c.url)) return;
        String ua = (c.userAgent == null || c.userAgent.isEmpty())
                ? "Sandy/0.14.1" : c.userAgent;
        pinger = new ActivePinger(c.url, ua, pingerListener);
        pinger.start();
    }

    private void stopPinger() {
        if (pinger != null) {
            pinger.stop();
            pinger = null;
        }
    }

    /** A door the pinger proved terminally dead loses its
     *  learned preference — in memory and on the memory hook. */
    private void forgetDoorOf(String url) {
        String host = Hydra.hostOf(url);
        if (host == null) return;
        Net.forgetLearned(host);
        HydraStore.forget(getApplicationContext(), host);
    }

    private final ActivePinger.Listener pingerListener =
            new ActivePinger.Listener() {
                @Override
                public void onPingSample(ActivePinger.Sample s) {
                    lastPing = s;
                    String cls = s.ok ? "ok"
                            : s.playlistHttp > 0 ? "http" + s.playlistHttp
                                    : s.failureKind;
                    if (!cls.equals(lastPingClass)) {
                        // record state CHANGES, not the heartbeat
                        lastPingClass = cls;
                        PlaybackRecord.write(getApplicationContext(),
                                PlaybackRecord.EV_PING, pingFacts(s));
                    }
                }

                @Override
                public void onPlaylistTerminal(ActivePinger.Sample s) {
                    forgetDoorOf(s.playlistUrl);
                    if (!playedThisChannel || !liveNow
                            || isRecordingPlay || destroyed) return;
                    PlaybackRecord.Facts f = pingFacts(s);
                    f.family = PlaybackContinuity.familyName(
                            PlaybackContinuity.PLAYLIST_GONE);
                    f.note = "the pinger: this door is terminally gone "
                            + "— maintenance starts under full runway";
                    PlaybackRecord.write(getApplicationContext(),
                            PlaybackRecord.EV_ERROR, f);
                    handler.post(() -> startRecovery(
                            PlaybackContinuity.PLAYLIST_GONE));
                }

                @Override
                public void onWindowStuck(ActivePinger.Sample s) {
                    PlaybackRecord.Facts f = pingFacts(s);
                    f.family = PlaybackContinuity.familyName(
                            PlaybackContinuity.PLAYLIST_STUCK);
                    f.note = "the pinger: the window stopped advancing "
                            + "(media sequence and first segment unchanged)";
                    PlaybackRecord.write(getApplicationContext(),
                            PlaybackRecord.EV_STALL, f);
                    if (!playedThisChannel || !liveNow
                            || isRecordingPlay || destroyed) return;
                    handler.post(() -> startRecovery(
                            PlaybackContinuity.PLAYLIST_STUCK));
                }
            };

    // ------------------------------------------------------------- failures

    private static String schemeOf(String url) {
        int i = url.indexOf("://");
        return i > 0 ? url.substring(0, i) : "unknown";
    }

    private void armWatchdog(String channelName) {
        watchdog = () -> {
            if (destroyed) return;
            // v0.14.1: while maintenance owns the channel, the
            // startup watchdog stands down — the recovery loop
            // is the watcher now.
            if (recovering) return;
            if (player != null && player.getPlaybackState() != Player.STATE_READY
                    && player.getPlaybackState() != Player.STATE_ENDED) {
                // a stall is a network outcome: Hydra gets its
                // walk, and the words say "didn't answer in time"
                lastFailNetwork = !localMode;
                lastFailWords = localMode ? "" : FailureWords.englishFor(
                        FailureWords.TIMEOUT, 0);
                handleFailure(localMode
                        ? "This file didn't start in time."
                        : "This channel didn't answer in time.");
            }
        };
        handler.postDelayed(watchdog, READY_TIMEOUT_MS);
    }

    /** The plain words for a playback error (v0.14.0): the HTTP
     *  status the server spoke, the cleartext bar, the timeout —
     *  never a bare "HTTP error". "" when the error carries no
     *  such word (a decode failure is a different sentence, and
     *  the caller's own message already says it). */
    private static String wordsOfError(PlaybackException error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof androidx.media3.datasource.HttpDataSource
                    .InvalidResponseCodeException) {
                int code = ((androidx.media3.datasource.HttpDataSource
                        .InvalidResponseCodeException) t).responseCode;
                return FailureWords.englishFor(
                        FailureWords.kindForHttp(code), code);
            }
            if (t instanceof androidx.media3.datasource.HttpDataSource
                    .CleartextNotPermittedException) {
                return FailureWords.englishFor(FailureWords.CLEARTEXT, 0);
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return FailureWords.englishFor(FailureWords.TIMEOUT, 0);
            }
            if (t instanceof java.net.UnknownHostException) {
                return FailureWords.englishFor(FailureWords.ADDRESS, 0);
            }
            t = t.getCause();
        }
        return "";
    }

    /** Did the wire fail (vs. the phone failing to decode)? */
    private static boolean isNetworkError(PlaybackException error) {
        Throwable t = error;
        while (t != null) {
            if (t instanceof java.io.IOException) return true;
            t = t.getCause();
        }
        return false;
    }

    private void cancelWatchdog() {
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = null;
    }

    /**
     * Health from real plays (v0.3.0): a stream that reached READY is
     * verified working; one that failed or timed out is marked dead —
     * both feed the same badges the probes write, so the lists learn
     * from every play, not just from Verify runs.
     */
    private void recordHealth(boolean ok, String detail) {
        recordHealthTier(ok ? LinkHealth.TIER_PLAYS : LinkHealth.TIER_DEAD,
                detail);
    }

    /**
     * File a health verdict at an explicit tier. v0.14.1: mid-play
     * events file the truth at their own tier — a channel that
     * played and then had its segments refused files CONNECTS
     * ("played, then the video stopped"), NOT the DEAD that the
     * old handleFailure stamped on it before any walk ran; a
     * walked source-ended verdict files DEAD, in those words.
     */
    private void recordHealthTier(int tier, String detail) {
        if (queue == null || queue.isEmpty() || index < 0 || index >= queue.size()) return;
        String url = queue.get(index).url;
        if (!LinkHealth.isRemote(url)) return;
        LinkHealth.Result r = new LinkHealth.Result();
        r.checkedAt = System.currentTimeMillis();
        // A real play outranks any probe: READY means the video itself
        // answered (top tier); a failure means dead, whatever an
        // earlier probe believed.
        r.finish(tier, true, detail);
        HealthStore.record(this, url, r);
    }

    private void handleFailure(String message) {
        if (destroyed) return;
        cancelWatchdog();
        // The DEAD filed here is a TUNE-IN truth only: since
        // v0.14.1 a failure after the channel PLAYED never
        // reaches this method (onPlayerError routes it to the
        // continuity law) — the old code filed "didn't play" on
        // channels that had just played fine for fifteen
        // seconds, and the health record lied ever after.
        recordHealth(false, "didn't play");
        if (localMode) {
            // A file is not a channel: no auto-skip, no health marks —
            // it says what happened and waits for the viewer.
            if (player != null) player.stop();
            errorText.setText(message + " It may be damaged, or a kind of "
                    + "video this phone can't decode.\n\n" + queue.get(index).name);
            errorBox.setVisibility(View.VISIBLE);
            return;
        }
        // Hydra's walk (v0.14.0, the Connection Law): before any
        // give-up, a NETWORK failure tries the same channel
        // through the ladder's other doors — the manner may be
        // what's dead while the channel lives behind another
        // one. Once per channel; the viewer hears the simple
        // words, never the machinery.
        if (!hydraWalked && lastFailNetwork && queue != null
                && index >= 0 && index < queue.size()
                && LinkHealth.isRemote(queue.get(index).url)) {
            hydraWalked = true;
            final int idx = index;
            final String url = queue.get(idx).url;
            final String ua = queue.get(idx).userAgent;
            if (player != null) player.stop();
            Toast.makeText(this, LangStore.tr(this, "hydra_trying"),
                    Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                HydraResolver.Outcome o = HydraResolver.prove(
                        getApplicationContext(), url, ua, null);
                handler.post(() -> {
                    if (destroyed || idx != index) return;
                    if (o.ok() && !o.url.equals(url)) {
                        // another door answered: play THAT door
                        queue.get(idx).url = o.url;
                        consecutiveFails = 0;
                        lastFailNetwork = false;
                        lastFailWords = "";
                        playCurrent();
                    } else if (o.ok()) {
                        finishFailure(message);
                    } else {
                        finishFailure(message + " "
                                + FailureWords.englishFor(o.result));
                    }
                });
            }, "hydra-player-walk").start();
            return;
        }
        finishFailure(message
                + (lastFailWords.isEmpty() ? "" : " " + lastFailWords));
    }

    /** The give-up path a failure reaches when Hydra's ladder is
     *  spent (or never applied): auto-skip or the error box, as
     *  before — with the failure's own words in the sentence. */
    private void finishFailure(String message) {
        if (destroyed) return;
        if (player != null) player.stop();
        consecutiveFails++;
        boolean canSkip = ChannelStore.getAutoSkip(this)
                && consecutiveFails <= MAX_CONSECUTIVE_FAILS
                && queue != null && queue.size() > 1;
        if (canSkip) {
            Toast.makeText(this, message + " Skipping…", Toast.LENGTH_SHORT).show();
            handler.postDelayed(() -> { if (!destroyed) step(1); }, 1200);
        } else {
            errorText.setText(message + "\n\n" + queue.get(index).name);
            errorBox.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // v0.14.1: the pinger and the maintenance loop die with
        // the player — their generation bump ends any in-flight
        // walk's claim on this screen.
        recovering = false;
        recoveryGen++;
        stopPinger();
        // The share belongs to what was on this screen: leaving
        // the player ends the fling and closes the watching page.
        if (shareCast != null) shareCast.stopAll();
        if (ttsRenderer != null) ttsRenderer.cancel();
        if (boundEngine != null) {
            boundEngine.cancel();
            boundEngine.close();
            boundEngine = null;
        }
        savePlace();
        saveKept();
        cancelWatchdog();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
        // v0.5.0 (item 8 as amended): the timeshift buffer is NOT
        // wiped here. The last live channel's buffer is the kept
        // place — it survives the close so the place can be offered
        // back on reopen. It is wiped when the channel CHANGES
        // (playCurrent), which is where the "buffer, not a recording"
        // boundary now honestly sits: one channel, the last one.
        super.onDestroy();
    }
}
