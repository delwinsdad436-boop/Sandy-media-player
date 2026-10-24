package com.talacore.talatv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Voice journals (v0.14.0) — recorded letters and tellings left
 * for the children, and for the children's children. A journal
 * is data: a voice kept as a file on the memory hook, so it
 * persists, copies, and hands down. Playback is open inside the
 * story room; RECORDING and DELETING pass the grown-up gate,
 * the way everything that changes the family's record does.
 *
 * (The ORIGINALS of journals are destined for the family's own
 * TALA monolith under the family's keys; Sandy holds the
 * household copies — see the 0.14.0 NOTES.)
 */
public class JournalsActivity extends Activity {

    private static final int REQ_GATE_RECORD = 45;
    private static final int REQ_GATE_DELETE = 46;

    private LinearLayout root;
    private MediaPlayer player;
    private MediaRecorder recorder;
    private File recTarget;
    private String pendingId = "";
    private boolean recording = false;

    private EditText nameBox;
    private Button recBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(24, 28, 44));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        showList();
    }

    private void showList() {
        stopPlayer();
        root.removeAllViews();
        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_journals"));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(30);
        root.addView(title);

        Button newBtn = new Button(this);
        newBtn.setText(LangStore.tr(this, "story_journals_record"));
        newBtn.setTextSize(21);
        newBtn.setAllCaps(false);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
        nl.topMargin = dp(12);
        newBtn.setOnClickListener(v -> {
            Intent gate = new Intent(this, GrownUpGateActivity.class);
            startActivityForResult(gate, REQ_GATE_RECORD);
        });
        root.addView(newBtn, nl);

        List<StoryData.Journal> journals = StoryFiles.journals(this);
        if (journals.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(LangStore.tr(this, "story_journal_empty"));
            empty.setTextColor(Color.rgb(200, 206, 226));
            empty.setTextSize(18);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(18);
            root.addView(empty, lp);
        }
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        // newest first
        List<StoryData.Journal> sorted = new ArrayList<>(journals);
        sorted.sort((a, b) -> Long.compare(b.dateMs, a.dateMs));
        for (StoryData.Journal j : sorted) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            rl.topMargin = dp(10);
            Button play = new Button(this);
            play.setText("▶  " + j.name + " · " + df.format(new Date(j.dateMs)));
            play.setTextSize(19);
            play.setAllCaps(false);
            play.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            play.setPadding(dp(14), 0, dp(14), 0);
            play.setMinHeight(dp(72));
            play.setOnClickListener(v -> play(j));
            row.addView(play, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText(LangStore.tr(this, "story_delete"));
            del.setTextSize(15);
            del.setOnClickListener(v -> {
                pendingId = j.id;
                Intent gate = new Intent(this, GrownUpGateActivity.class);
                startActivityForResult(gate, REQ_GATE_DELETE);
            });
            row.addView(del);
            root.addView(row, rl);
        }
    }

    private void play(StoryData.Journal j) {
        stopPlayer();
        File f = new File(StoryFiles.journalsDir(this), j.file);
        if (!f.isFile()) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayer());
            player.setOnErrorListener((mp, w, e) -> {
                stopPlayer();
                return true;
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            stopPlayer();
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    // ------------------------------------------------------------ record

    private void showRecorder() {
        root.removeAllViews();
        TextView whoLabel = new TextView(this);
        whoLabel.setText(LangStore.tr(this, "story_who_q"));
        whoLabel.setTextColor(Color.WHITE);
        whoLabel.setTextSize(20);
        root.addView(whoLabel);
        nameBox = new EditText(this);
        nameBox.setHint(LangStore.tr(this, "story_name_hint"));
        nameBox.setTextColor(Color.WHITE);
        nameBox.setHintTextColor(Color.rgb(150, 158, 185));
        nameBox.setTextSize(20);
        nameBox.setSingleLine(true);
        root.addView(nameBox);
        recBtn = new Button(this);
        recBtn.setText(LangStore.tr(this, "story_record"));
        recBtn.setTextSize(22);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        rl.topMargin = dp(20);
        recBtn.setOnClickListener(v -> toggleRecord());
        root.addView(recBtn, rl);
        Button back = new Button(this);
        back.setText(LangStore.tr(this, "reader_back"));
        back.setTextSize(17);
        back.setOnClickListener(v -> {
            if (recording) stopRecording(false);
            showList();
        });
        root.addView(back);
    }

    private void toggleRecord() {
        if (recording) {
            stopRecording(true);
            return;
        }
        if (nameBox.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, LangStore.tr(this, "story_who_q"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        try {
            String id = "j" + System.currentTimeMillis();
            recTarget = new File(StoryFiles.journalsDir(this), id + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(recTarget.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recBtn.setText(LangStore.tr(this, "story_stop_rec"));
            // stash the pending id on the file name
            pendingId = id;
        } catch (Exception e) {
            stopRecording(false);
        }
    }

    private void stopRecording(boolean keep) {
        recording = false;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (keep && recTarget != null && recTarget.isFile()) {
            List<StoryData.Journal> js = StoryFiles.journals(this);
            StoryData.Journal j = new StoryData.Journal();
            j.id = pendingId;
            j.name = nameBox.getText().toString().trim();
            j.dateMs = System.currentTimeMillis();
            j.file = recTarget.getName();
            js.add(j);
            StoryFiles.saveJournals(this, js);
            Toast.makeText(this, LangStore.tr(this, "story_saved"),
                    Toast.LENGTH_LONG).show();
            showList();
        } else if (recTarget != null) {
            recTarget.delete();
        }
        recTarget = null;
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK) return;
        if (req == REQ_GATE_RECORD) {
            showRecorder();
        } else if (req == REQ_GATE_DELETE && !pendingId.isEmpty()) {
            List<StoryData.Journal> js = StoryFiles.journals(this);
            List<StoryData.Journal> kept = new ArrayList<>();
            for (StoryData.Journal j : js) {
                if (j.id.equals(pendingId)) {
                    new File(StoryFiles.journalsDir(this), j.file).delete();
                } else {
                    kept.add(j);
                }
            }
            StoryFiles.saveJournals(this, kept);
            pendingId = "";
            showList();
        }
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) stopRecording(false);
        stopPlayer();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
