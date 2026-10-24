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
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Family voices (v0.14.0) — a grown-up (or a child, through the
 * gate) records their OWN voice reading a story. The recording
 * casts them as THEMSELVES in the book's voice book — "Grandma"
 * reads as Grandma — and is saved as a file on the memory hook:
 * when the telling reaches a passage they read, their voice
 * plays, here in the house, in place of any made voice. A voice
 * kept as a file persists, copies, and hands down.
 *
 * (The ORIGINALS of family voices are destined for the family's
 * own TALA monolith, under the family's keys; Sandy holds the
 * household reading copies — see the 0.14.0 NOTES.)
 *
 * The flow is kept dead simple: the gate, pick a book, say who
 * you are, read the passage on the screen, save. Passage by
 * passage, at kitchen-table pace.
 */
public class StoryRecordActivity extends Activity {

    private static final int REQ_GATE = 44;

    private LinearLayout root;
    private StoryData.Row row;
    private List<BookChunker.Chunk> chunks = new ArrayList<>();
    private StoryCast.Book cast;
    private int chunk = 0;

    private EditText nameBox;
    private TextView passageView, countView, statusView;
    private Button recBtn;
    private MediaRecorder recorder;
    private MediaPlayer preview;
    private File recTarget;
    private boolean recording = false;
    private boolean gated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 28, 44));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        setContentView(root);
        Intent gate = new Intent(this, GrownUpGateActivity.class);
        startActivityForResult(gate, REQ_GATE);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_GATE) {
            if (result != RESULT_OK) { finish(); return; }
            gated = true;
            showBookPicker();
        }
    }

    private void showBookPicker() {
        root.removeAllViews();
        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_pick_book"));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(26);
        root.addView(title);
        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(200, 206, 226));
        statusView.setTextSize(16);
        root.addView(statusView);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        for (StoryData.Row r : StoryFiles.curation(this)) {
            Button b = new Button(this);
            b.setText(r.title);
            b.setTextSize(20);
            b.setAllCaps(false);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(14), 0, dp(14), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
            lp.topMargin = dp(10);
            b.setOnClickListener(v -> openBook(r));
            list.addView(b, lp);
        }
    }

    private void openBook(StoryData.Row r) {
        row = r;
        statusView.setText(LangStore.tr(this, "story_loading"));
        new Thread(() -> {
            try {
                String text = StoryFetch.fetchText(this, r);
                List<BookChunker.Chunk> ch = BookChunker.chunk(text, null);
                StoryCast.Book c = StoryFiles.loadCast(this, r);
                runOnUiThread(() -> {
                    chunks = ch;
                    cast = c;
                    chunk = 0;
                    showRecorder();
                });
            } catch (Exception e) {
                runOnUiThread(() -> statusView.setText(
                        LangStore.tr(this, "story_fetch_fail")));
            }
        }).start();
    }

    private void showRecorder() {
        root.removeAllViews();
        TextView title = new TextView(this);
        title.setText(String.format(
                LangStore.tr(this, "story_record_voice_for"), row.title));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(24);
        root.addView(title);

        TextView here = new TextView(this);
        here.setText(LangStore.tr(this, "story_your_voice_here"));
        here.setTextColor(Color.rgb(200, 206, 226));
        here.setTextSize(15);
        root.addView(here);

        TextView whoLabel = new TextView(this);
        whoLabel.setText(LangStore.tr(this, "story_who_q"));
        whoLabel.setTextColor(Color.WHITE);
        whoLabel.setTextSize(17);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wl.topMargin = dp(14);
        root.addView(whoLabel, wl);
        nameBox = new EditText(this);
        nameBox.setHint(LangStore.tr(this, "story_name_hint"));
        nameBox.setTextColor(Color.WHITE);
        nameBox.setHintTextColor(Color.rgb(150, 158, 185));
        nameBox.setTextSize(19);
        nameBox.setSingleLine(true);
        root.addView(nameBox);

        countView = new TextView(this);
        countView.setTextColor(Color.rgb(170, 178, 200));
        countView.setTextSize(14);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cl.topMargin = dp(12);
        root.addView(countView, cl);

        ScrollView ps = new ScrollView(this);
        passageView = new TextView(this);
        passageView.setTextColor(Color.WHITE);
        passageView.setTextSize(23);
        passageView.setLineSpacing(1.25f, 1f);
        ps.addView(passageView);
        root.addView(ps, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = navButton(LangStore.tr(this, "reader_back"), v -> step(-1));
        recBtn = navButton(LangStore.tr(this, "story_record"), v -> toggleRecord());
        Button play = navButton(LangStore.tr(this, "reader_play"), v -> playPreview());
        Button next = navButton(LangStore.tr(this, "reader_forward"), v -> step(1));
        controls.addView(prev);
        controls.addView(recBtn);
        controls.addView(play);
        controls.addView(next);
        root.addView(controls);
        showPassage();
    }

    private Button navButton(String label, android.view.View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setMinHeight(dp(60));
        b.setOnClickListener(on);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void showPassage() {
        if (chunks.isEmpty()) return;
        chunk = Math.max(0, Math.min(chunk, chunks.size() - 1));
        passageView.setText(chunks.get(chunk).text);
        Map<Integer, String[]> idx = StoryFiles.voiceIndex(this, row.id);
        String[] rec = idx.get(chunk);
        countView.setText(String.format(LangStore.tr(this, "story_passage_of"),
                chunk + 1, chunks.size())
                + (rec != null ? "  ·  🎙 " + rec[0] : ""));
    }

    private void step(int dir) {
        if (recording) stopRecording(false);
        chunk += dir;
        showPassage();
    }

    private void toggleRecord() {
        if (recording) {
            stopRecording(true);
            return;
        }
        String who = nameBox.getText().toString().trim();
        if (who.isEmpty()) {
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
            recTarget = new File(StoryFiles.bookVoicesDir(this, row.id),
                    "c" + chunk + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setOutputFile(recTarget.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recBtn.setText(LangStore.tr(this, "story_stop_rec"));
        } catch (Exception e) {
            stopRecording(false);
        }
    }

    private void stopRecording(boolean keep) {
        recording = false;
        if (recBtn != null) recBtn.setText(LangStore.tr(this, "story_record"));
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (keep && recTarget != null && recTarget.isFile()) {
            String who = nameBox.getText().toString().trim();
            Map<Integer, String[]> idx = StoryFiles.voiceIndex(this, row.id);
            idx.put(chunk, new String[]{who, recTarget.getName()});
            StoryFiles.saveVoiceIndex(this, row.id, idx);
            StoryCast.Entry e = StoryCast.enrollFamily(cast, who);
            e.recordings.put(chunk, recTarget.getName());
            StoryFiles.saveCast(this, cast);
            Toast.makeText(this, LangStore.tr(this, "story_saved"),
                    Toast.LENGTH_LONG).show();
            showPassage();
        } else if (recTarget != null) {
            recTarget.delete();
        }
        recTarget = null;
    }

    private void playPreview() {
        Map<Integer, String[]> idx = StoryFiles.voiceIndex(this, row.id);
        String[] rec = idx.get(chunk);
        if (rec == null) return;
        File f = new File(StoryFiles.bookVoicesDir(this, row.id), rec[1]);
        if (!f.isFile()) return;
        stopPreview();
        try {
            preview = new MediaPlayer();
            preview.setDataSource(f.getAbsolutePath());
            preview.setOnCompletionListener(mp -> stopPreview());
            preview.prepare();
            preview.start();
        } catch (Exception e) {
            stopPreview();
        }
    }

    private void stopPreview() {
        if (preview != null) {
            try { preview.stop(); } catch (Exception ignored) {}
            try { preview.release(); } catch (Exception ignored) {}
            preview = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) stopRecording(false);
        stopPreview();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
