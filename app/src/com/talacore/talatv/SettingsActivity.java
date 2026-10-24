package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Settings (v0.4.0) — deliberately short. This screen is James's side
 * of the glass (he is the operator); Mom never needs it, because a
 * fresh install already carries his defaults: link checks refresh
 * themselves, working links sort first, the live buffer holds
 * 30 minutes. Each control here explains itself in plain words,
 * including the timeshift buffer's honest nature: a rolling buffer
 * on this phone, discarded when the channel changes — not a recording.
 */
public class SettingsActivity extends Activity {

    private static final int REQ_PICK_PACK = 905;
    private static final int REQ_PICK_ENGINE = 906;
    private static final int REQ_PICK_DIAR = 907;
    private static final int REQ_PICK_VOICE = 908;

    private interface BoolChange { void onChange(boolean on); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Sanctuary.ground());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "settings_title"));
        title.setTextColor(Sanctuary.INK);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Sandy is already set up the right way on a fresh install — "
                + "these are here for the operator, not for everyday watching.");
        sub.setTextColor(Sanctuary.MUTED);
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(8));
        root.addView(sub);

        // ---- language (v0.5.0)
        root.addView(header(LangStore.tr(this, "settings_language")));
        root.addView(body("Sandy speaks: "
                + LangStore.displayName(this, ChannelStore.getUiLanguage(this))
                + ". The chosen language is also what Live TV reaches for "
                + "first. Twelve languages are built in; more arrive as "
                + "packs anyone can write (below). Note: the buttons and "
                + "labels speak the chosen language in this version; the "
                + "long explanations in Settings are still English."));
        Button langBtn = new Button(this);
        Sanctuary.styleButton(this, langBtn, false);
        langBtn.setText(LangStore.tr(this, "settings_change"));
        langBtn.setContentDescription(LangStore.tr(this, "settings_language")
                + " — " + LangStore.tr(this, "settings_change"));
        langBtn.setOnClickListener(v -> {
            Intent i = new Intent(this, LanguageActivity.class);
            i.putExtra(LanguageActivity.EXTRA_FROM_SETTINGS, true);
            startActivity(i);
            // The whole app re-renders in the new language next open;
            // this screen too, so close it rather than show stale text.
            finish();
        });
        root.addView(langBtn);
        Button packBtn = new Button(this);
        Sanctuary.styleButton(this, packBtn, false);
        packBtn.setText("Import a language pack (.pack file)");
        packBtn.setOnClickListener(v -> pickPack());
        root.addView(packBtn);
        root.addView(body("A pack is one plain-text file: a header line, "
                + "the language's code and name, then one translated line "
                + "for every label Sandy has. A pack missing any line is "
                + "refused — Sandy will tell you exactly which lines — "
                + "never half-installed."));

        // ---- live buffer
        root.addView(header("Live buffer (pause & rewind)"));
        root.addView(body("While a live channel plays, Sandy keeps a rolling "
                + "buffer on this phone, so you can pause, walk away, come back "
                + "and pick up where you left off — and skip back past the "
                + "commercials. It's a buffer, not a recording — with one "
                + "family rule since 0.5.0: the LAST channel's buffer is "
                + "kept when Sandy closes, so reopening that channel can "
                + "offer your place back (older parts may have rolled off "
                + "— the channel only ever holds its own recent window). "
                + "Change the channel and the old buffer is gone. "
                + "Storage honesty: the 60-minute setting is about "
                + "1.35 GB at a typical stream rate; 30 minutes about "
                + "half that."));
        RadioGroup bufferGroup = new RadioGroup(this);
        bufferGroup.setOrientation(LinearLayout.VERTICAL);
        int current = ChannelStore.getTimeshiftMinutes(this);
        for (int minutes : TimeshiftMath.MINUTE_CHOICES) {
            RadioButton rb = new RadioButton(this);
            rb.setText("Keep about " + minutes + " minutes");
            rb.setTextColor(Sanctuary.INK);
            rb.setTextSize(16);
            rb.setId(minutes);
            rb.setPadding(0, dp(6), 0, dp(6));
            if (minutes == current) rb.setChecked(true);
            bufferGroup.addView(rb);
        }
        bufferGroup.setOnCheckedChangeListener((g, id) ->
                ChannelStore.setTimeshiftMinutes(this, id));
        root.addView(bufferGroup);

        root.addView(body(LangStore.tr(this, "set_buffer_when_full") + ":"));
        RadioGroup fillGroup = new RadioGroup(this);
        fillGroup.setOrientation(LinearLayout.VERTICAL);
        boolean hold = ChannelStore.getBufferFullHold(this);
        RadioButton holdRb = new RadioButton(this);
        holdRb.setText(LangStore.tr(this, "set_buffer_hold")
                + " — capture stops and the beginning is kept (for when "
                + "you fall asleep watching: wake up and the start of the "
                + "episode is still there; anything past the window is "
                + "not recorded)");
        holdRb.setTextColor(Sanctuary.INK);
        holdRb.setTextSize(15);
        holdRb.setId(1);
        holdRb.setChecked(hold);
        holdRb.setPadding(0, dp(6), 0, dp(6));
        fillGroup.addView(holdRb);
        RadioButton rollRb = new RadioButton(this);
        rollRb.setText(LangStore.tr(this, "set_buffer_roll")
                + " — the window slides; the oldest falls off as new "
                + "video arrives");
        rollRb.setTextColor(Sanctuary.INK);
        rollRb.setTextSize(15);
        rollRb.setId(2);
        rollRb.setChecked(!hold);
        rollRb.setPadding(0, dp(6), 0, dp(6));
        fillGroup.addView(rollRb);
        fillGroup.setOnCheckedChangeListener((g, id) ->
                ChannelStore.setBufferFullHold(this, id == 1));
        root.addView(fillGroup);

        // ---- link checks
        root.addView(header("Link checks"));
        root.addView(check("Check links again when Sandy opens",
                "Channels move around. With this on, any check older than "
                        + "6 hours quietly re-tests itself in the background — "
                        + "favorites first — so the list you see is the list "
                        + "as it stands now, not last week.",
                ChannelStore.getAutoFresh(this),
                on -> ChannelStore.setAutoFresh(this, on)));
        root.addView(check("Working links first",
                "Lists put channels proven to play at the top, and hide "
                        + "the ones proven dead. Turn off to see everything "
                        + "in plain order, dead links and all.",
                ChannelStore.getWorkingFirst(this),
                on -> ChannelStore.setWorkingFirst(this, on)));
        root.addView(check("Skip dead channels automatically",
                "When a channel fails in the player, move on to the next "
                        + "one instead of stopping.",
                ChannelStore.getAutoSkip(this),
                on -> ChannelStore.setAutoSkip(this, on)));

        // ---- translation (v0.6.0)
        root.addView(header(LangStore.tr(this, "settings_translation")));
        EnginePackStore.Status eps = EnginePackStore.anyInstalled(this);
        root.addView(body(LangStore.tr(this, "settings_engine_pack") + ": "
                + (eps.installed
                        ? eps.pair + " — " + eps.asrKind + " + " + eps.mtKind
                                + " (" + (eps.totalBytes / 1_000_000) + " MB on this phone)"
                        : "not installed")));
        root.addView(body("The translation engine — the part that hears "
                + "one language and writes another — does not ride inside "
                + "Sandy (she stays light). It comes as a pack from the "
                + "shelf (Brain ▸ Programs), one per language pair, and "
                + "installs here. Every file in a pack is checked against "
                + "the pack's own fingerprint list before anything is "
                + "kept; a pack that doesn't match is refused, with the "
                + "reason named."));
        Button engineBtn = new Button(this);
        Sanctuary.styleButton(this, engineBtn, false);
        engineBtn.setText("Install an engine pack (.pack file)");
        engineBtn.setOnClickListener(v -> pickEnginePack());
        root.addView(engineBtn);

        // ---- the voice pack (v0.12.0): diarization for dubbing
        DiarPackStore.Status dps = DiarPackStore.status(this);
        root.addView(body(LangStore.tr(this, "settings_diar_pack") + ": "
                + (dps.installed
                        ? dps.segKind + " + " + dps.embKind
                                + " (" + (dps.totalBytes / 1_000_000) + " MB on this phone)"
                        : LangStore.tr(this, "settings_diar_out"))));
        root.addView(body(LangStore.tr(this, "settings_diar_about")));
        Button diarBtn = new Button(this);
        Sanctuary.styleButton(this, diarBtn, false);
        diarBtn.setText(LangStore.tr(this, "settings_diar_import"));
        diarBtn.setOnClickListener(v -> pickDiarPack());
        root.addView(diarBtn);
        Button vbBtn = new Button(this);
        Sanctuary.styleButton(this, vbBtn, false);
        vbBtn.setText(LangStore.tr(this, "settings_voice_book"));
        vbBtn.setOnClickListener(v ->
                startActivity(new Intent(this, VoiceBookActivity.class)));
        root.addView(vbBtn);
        root.addView(body("The voice book is where a show's cast lives — "
                + "which voice plays which character — so a character "
                + "sounds like themselves in every episode. Casting is "
                + "set there, by a person, once."));

        // ---- voice packs (v0.13.0): one per language, the voices
        // themselves — the diarization pack's companions. A dub or
        // a reading speaks with an installed pack first, the
        // phone's own voice second, and says so when neither can.
        java.util.Map<String, VoicePackStore.Installed> vps =
                VoicePackStore.installed(this);
        root.addView(body(LangStore.tr(this, "settings_voice_packs") + ": "
                + (vps.isEmpty()
                        ? LangStore.tr(this, "voice_packs_none")
                        : String.format(LangStore.tr(this, "voice_packs_some"),
                                vps.size(),
                                (VoicePackStore.totalBytes(this) / 1_000_000)
                                        + " MB"))));
        root.addView(body("A voice pack is one language's voice "
                + "(Meta's MMS-TTS, an open model trained on the "
                + "language's own writing; CC-BY-NC-4.0). One file "
                + "per language, installed as wanted — the dub and "
                + "read-aloud speak with it first. The coverage "
                + "page shows, for every one of the 84 catalogue "
                + "languages, which voice this phone has for it."));
        Button voiceBtn = new Button(this);
        Sanctuary.styleButton(this, voiceBtn, false);
        voiceBtn.setText(LangStore.tr(this, "settings_voice_import"));
        voiceBtn.setOnClickListener(v -> pickVoicePack());
        root.addView(voiceBtn);
        Button covBtn = new Button(this);
        Sanctuary.styleButton(this, covBtn, false);
        covBtn.setText(LangStore.tr(this, "voice_coverage_title"));
        covBtn.setOnClickListener(v ->
                startActivity(new Intent(this, VoiceCoverageActivity.class)));
        root.addView(covBtn);

        // ---- accessibility (v0.5.0)
        root.addView(header("Accessibility"));
        root.addView(check(LangStore.tr(this, "set_rumble"),
                "A short vibration when playback starts, and a double "
                        + "pulse when a link check finishes — the go sign "
                        + "you can feel. Made for viewers who can't hear "
                        + "the TV; good for everybody. Every button and "
                        + "channel in Sandy also carries a spoken label, "
                        + "so TalkBack and refreshable Braille displays "
                        + "can read the whole app.",
                ChannelStore.getRumble(this),
                on -> ChannelStore.setRumble(this, on)));

        // ---- quality posture (v0.12.1)
        // James's shape: the viewer picks a POSTURE, not a number —
        // low-first (fastest start), balanced (the happy medium the
        // pipe sustains), ultra (highest, buffer and all). The
        // connection statement is MEASURED (PipeMeter) or honestly
        // absent — never invented. "The program can only do what
        // the program can do."
        root.addView(header(LangStore.tr(this, "set_quality")));
        root.addView(body("When you tap something to watch, Sandy "
                + "finds a working source in your language and plays "
                + "it — this is how hard she reaches for quality "
                + "while she does it. You can always open the source "
                + "list and pick by hand instead."));
        RadioGroup postureGroup = new RadioGroup(this);
        postureGroup.setOrientation(LinearLayout.VERTICAL);
        String curPosture = ChannelStore.getQualityPosture(this);
        String[][] postures = {
                {"low", "quality_low", "quality_low_desc"},
                {"balanced", "quality_balanced", "quality_balanced_desc"},
                {"ultra", "quality_ultra", "quality_ultra_desc"},
        };
        for (String[] opt : postures) {
            RadioButton rb = new RadioButton(this);
            rb.setText(LangStore.tr(this, opt[1]) + " — "
                    + LangStore.tr(this, opt[2]));
            rb.setTextColor(Sanctuary.INK);
            rb.setTextSize(15);
            rb.setTag(opt[0]);
            rb.setPadding(0, dp(6), 0, dp(6));
            rb.setChecked(opt[0].equals(curPosture));
            postureGroup.addView(rb);
        }
        postureGroup.setOnCheckedChangeListener((g, id) -> {
            RadioButton rb = g.findViewById(id);
            if (rb != null && rb.getTag() != null) {
                ChannelStore.setQualityPosture(this, rb.getTag().toString());
            }
        });
        root.addView(postureGroup);
        double bps = PipeMeter.measuredBps(this);
        if (bps > 0) {
            int cap = PipeMeter.capTierFor(bps);
            root.addView(body("Your connection right now: "
                    + PipeMeter.mbpsText(bps) + " Mbps measured — "
                    + PipeMeter.tierLabel(cap) + " will play clean; "
                    + "higher will buffer. That is the connection "
                    + "talking, not a settings problem."));
        } else {
            root.addView(body("Your connection right now: not "
                    + "measured yet — play something or refresh the "
                    + "guide, and Sandy will state it from "
                    + "measurement, not a guess."));
        }

        // ---- auto-advance (v0.14.0): the lean-back choice, made
        // once here — never a countdown in the player.
        root.addView(check(
                LangStore.tr(this, "settings_auto_advance"),
                LangStore.tr(this, "settings_auto_advance_note"),
                ChannelStore.getAutoAdvanceNext(this),
                on -> ChannelStore.setAutoAdvanceNext(this, on)));

        // ---- crash reports (v0.12.1)
        // CrashGuard has filed full stacks since 0.8.0, but nothing
        // in the app could READ them — the 0.7.0 crash's root cause
        // is still unestablished for exactly that reason. Now the
        // latest crash stands in the open here, shareable, so the
        // next field crash arrives WITH its stack.
        root.addView(header(LangStore.tr(this, "crash_reports")));
        java.util.List<java.io.File> crashFiles = CrashGuard.listCrashes(this);
        if (crashFiles.isEmpty()) {
            root.addView(body(LangStore.tr(this, "crash_none")));
        } else {
            root.addView(body(crashFiles.size()
                    + " report(s) on file. Latest:"));
            TextView crashView = new TextView(this);
            crashView.setText(CrashGuard.readLatest(this));
            crashView.setTextColor(Sanctuary.BODY);
            crashView.setTextSize(11);
            root.addView(crashView);
            Button shareBtn = new Button(this);
            Sanctuary.styleButton(this, shareBtn, false);
            shareBtn.setText(LangStore.tr(this, "crash_share"));
            shareBtn.setOnClickListener(v -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Sandy crash report");
                send.putExtra(Intent.EXTRA_TEXT, CrashGuard.readLatest(this));
                startActivity(Intent.createChooser(send,
                        LangStore.tr(this, "crash_share")));
            });
            root.addView(shareBtn);
        }

        // ---- playback record (v0.14.1)
        // Every tune, failure, stall, reconnect and verdict on a
        // playing stream is written to the playback record with
        // the facts that separate the failure families (playlist
        // result, segment host + HTTP result, position, runway,
        // heads tried). It stands in the open here for the same
        // reason crash reports do: the next field failure should
        // arrive WITH its evidence.
        root.addView(header(LangStore.tr(this, "playback_record")));
        java.util.List<java.io.File> playFiles = PlaybackRecord.listFiles(this);
        if (playFiles.isEmpty()) {
            root.addView(body(LangStore.tr(this, "playback_record_none")));
        } else {
            root.addView(body(playFiles.size()
                    + " day(s) on file. Latest:"));
            TextView playView = new TextView(this);
            playView.setText(PlaybackRecord.readLatest(this));
            playView.setTextColor(Sanctuary.BODY);
            playView.setTextSize(11);
            root.addView(playView);
            Button playShareBtn = new Button(this);
            Sanctuary.styleButton(this, playShareBtn, false);
            playShareBtn.setText(LangStore.tr(this, "crash_share"));
            playShareBtn.setOnClickListener(v -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Sandy playback record");
                send.putExtra(Intent.EXTRA_TEXT, PlaybackRecord.readLatest(this));
                startActivity(Intent.createChooser(send,
                        LangStore.tr(this, "crash_share")));
            });
            root.addView(playShareBtn);
        }

        root.addView(header("About"));
        root.addView(body("Sandy 0.14.1 — a television for the family. It hosts "
                + "nothing: it finds and plays the free channels and the "
                + "shelves you load into it. What it keeps is yours: your "
                + "favorites, your places, your recordings. Made by "
                + "James, for his mother."));
        Button creditBtn = new Button(this);
        Sanctuary.styleButton(this, creditBtn, false);
        creditBtn.setText(LangStore.tr(this, "sources_title"));
        creditBtn.setOnClickListener(v ->
                startActivity(new Intent(this, CreditActivity.class)));
        root.addView(creditBtn);

        setContentView(scroll);
    }

    private TextView header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Sanctuary.GOLD_TEXT);
        t.setTextSize(16);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Sanctuary.BODY);
        t.setTextSize(14);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    /** A labelled checkbox with its plain-words explanation under it. */
    private LinearLayout check(String label, String why, boolean initial,
                               BoolChange change) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(Sanctuary.INK);
        cb.setTextSize(16);
        cb.setPadding(0, dp(8), 0, 0);
        cb.setChecked(initial);
        cb.setOnCheckedChangeListener((b, on) -> change.onChange(on));
        box.addView(cb);
        TextView hint = new TextView(this);
        hint.setText(why);
        hint.setTextColor(Sanctuary.SUB);
        hint.setTextSize(12.5f);
        hint.setPadding(dp(32), 0, 0, dp(4));
        box.addView(hint);
        return box;
    }

    // ------------------------------------------------- the pack slot

    private void pickPack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        try {
            startActivityForResult(i, REQ_PICK_PACK);
        } catch (Exception e) {
            Toast.makeText(this, "This phone has no file picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void pickEnginePack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_ENGINE);
        } catch (Exception e) {
            Toast.makeText(this, "This phone has no file picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void pickDiarPack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_DIAR);
        } catch (Exception e) {
            Toast.makeText(this, "This phone has no file picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void pickVoicePack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_VOICE);
        } catch (Exception e) {
            Toast.makeText(this, "This phone has no file picker.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_VOICE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Toast.makeText(this, LangStore.tr(this, "settings_diar_checking"),
                    Toast.LENGTH_SHORT).show();
            VoicePackStore.install(this, data.getData(), r -> {
                if (r.ok) {
                    new AlertDialog.Builder(this)
                            .setTitle(LangStore.tr(this, "settings_diar_in_title"))
                            .setMessage(r.tvName + " (" + r.lang + ") — "
                                    + r.engine + ", " + r.license)
                            .setPositiveButton("OK", (d, w) -> recreate())
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(LangStore.tr(this, "settings_diar_refused"))
                            .setMessage(String.join("\n", r.errors))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
            return;
        }
        if (requestCode == REQ_PICK_DIAR) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Toast.makeText(this, LangStore.tr(this, "settings_diar_checking"),
                    Toast.LENGTH_SHORT).show();
            DiarPackStore.install(this, data.getData(), r -> {
                if (r.ok) {
                    new AlertDialog.Builder(this)
                            .setTitle(LangStore.tr(this, "settings_diar_in_title"))
                            .setMessage(r.segKind + " + " + r.embKind)
                            .setPositiveButton("OK", (d, w) -> recreate())
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(LangStore.tr(this, "settings_diar_refused"))
                            .setMessage(String.join("\n", r.errors))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
            return;
        }
        if (requestCode == REQ_PICK_ENGINE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Toast.makeText(this, "Checking the engine pack…",
                    Toast.LENGTH_SHORT).show();
            EnginePackStore.install(this, data.getData(), r -> {
                if (r.ok) {
                    new AlertDialog.Builder(this)
                            .setTitle("Engine pack installed")
                            .setMessage(r.pair + " — recognizer: " + r.asrKind
                                    + ", translator: " + r.mtKind + ".\n\n"
                                    + LangStore.tr(this, "tr_engine_unavailable"))
                            .setPositiveButton("OK", (d, w) -> recreate())
                            .show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("That engine pack can't be used")
                            .setMessage(String.join("\n", r.errors))
                            .setPositiveButton("OK", null)
                            .show();
                }
            });
            return;
        }
        if (requestCode != REQ_PICK_PACK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while (in != null && (n = in.read(buf)) > 0) bos.write(buf, 0, n);
            String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            LangPack.Result r = LangPack.parse(text);
            if (!r.ok) {
                // Fail-closed, in plain words, naming what's missing.
                String msg = r.error;
                if (!r.missing.isEmpty()) {
                    msg += "\n\nMissing: " + String.join(", ", r.missing);
                }
                new AlertDialog.Builder(this)
                        .setTitle("That pack can't be used")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            if (LangStore.install(this, r, text)) {
                Toast.makeText(this, "Pack installed: " + r.name
                        + " — pick it in the language list above.",
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "The pack was fine, but Sandy couldn't "
                        + "save it on this phone.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't read that file (" + e.getMessage() + ")",
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
