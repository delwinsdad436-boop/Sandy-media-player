package com.talacore.talatv;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Make a story (v0.14.0) — a child builds their own storyboard:
 * pick picture cards from Sandy's bundled set, put words on
 * them (typed, or told out loud and recorded), give the story a
 * name, and SAVE it as a story cartridge — a file on the memory
 * hook, like every kept thing in the house. Their panels, their
 * telling, their ending.
 */
public class StoryMakeActivity extends Activity {

    private final List<StoryData.CartridgePanel> panels = new ArrayList<>();
    private String cartridgeId = "";
    private File workDir;
    private EditText titleBox;
    private LinearLayout stripBox;
    private TextView editorLabel;
    private EditText captionBox;
    private Button recBtn;
    private int editing = -1;
    private MediaRecorder recorder;
    private File recTarget;
    private boolean recording = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cartridgeId = StoryData.safeId("story", System.currentTimeMillis());
        workDir = StoryFiles.cartridgeDir(this, cartridgeId);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(24, 28, 44));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_make"));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(30);
        root.addView(title);

        titleBox = new EditText(this);
        titleBox.setHint(LangStore.tr(this, "story_title_hint"));
        titleBox.setTextColor(Color.WHITE);
        titleBox.setHintTextColor(Color.rgb(150, 158, 185));
        titleBox.setTextSize(20);
        titleBox.setSingleLine(true);
        root.addView(titleBox);

        TextView pickLabel = new TextView(this);
        pickLabel.setText(LangStore.tr(this, "story_pick_panels"));
        pickLabel.setTextColor(Color.rgb(200, 206, 226));
        pickLabel.setTextSize(17);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        pl.topMargin = dp(14);
        root.addView(pickLabel, pl);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        for (StoryPanels.Panel p : StoryPanels.all()) {
            ImageButton ib = new ImageButton(this);
            ib.setImageResource(resId(StoryPanels.drawableName(p.id)));
            ib.setContentDescription(p.label);
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = dp(76);
            gl.height = dp(76);
            gl.setMargins(dp(4), dp(4), dp(4), dp(4));
            ib.setLayoutParams(gl);
            ib.setOnClickListener(v -> addPanel(p.id));
            grid.addView(ib);
        }
        root.addView(grid);

        stripBox = new LinearLayout(this);
        stripBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(16);
        root.addView(stripBox, sl);

        editorLabel = new TextView(this);
        editorLabel.setTextColor(Color.rgb(255, 224, 150));
        editorLabel.setTextSize(18);
        root.addView(editorLabel);
        captionBox = new EditText(this);
        captionBox.setHint(LangStore.tr(this, "story_caption_hint"));
        captionBox.setTextColor(Color.WHITE);
        captionBox.setHintTextColor(Color.rgb(150, 158, 185));
        captionBox.setTextSize(19);
        root.addView(captionBox);
        recBtn = new Button(this);
        recBtn.setText(LangStore.tr(this, "story_record"));
        recBtn.setTextSize(18);
        recBtn.setOnClickListener(v -> toggleRecord());
        root.addView(recBtn);

        Button saveBtn = new Button(this);
        saveBtn.setText(LangStore.tr(this, "story_save"));
        saveBtn.setTextSize(22);
        LinearLayout.LayoutParams sv = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(72));
        sv.topMargin = dp(18);
        saveBtn.setOnClickListener(v -> save());
        root.addView(saveBtn, sv);

        setContentView(scroll);
        paintStrip();
    }

    private void addPanel(String panelId) {
        StoryData.CartridgePanel p = new StoryData.CartridgePanel();
        p.panel = panelId;
        panels.add(p);
        editing = panels.size() - 1;
        paintStrip();
        paintEditor();
    }

    private void paintStrip() {
        stripBox.removeAllViews();
        for (int i = 0; i < panels.size(); i++) {
            final int idx = i;
            StoryData.CartridgePanel p = panels.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageButton thumb = new ImageButton(this);
            thumb.setImageResource(resId(StoryPanels.drawableName(p.panel)));
            thumb.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(56)));
            thumb.setOnClickListener(v -> { editing = idx; paintEditor(); paintStrip(); });
            row.addView(thumb);
            TextView cap = new TextView(this);
            cap.setText((idx + 1) + ". " + (p.caption.isEmpty() ? "…" : p.caption)
                    + (p.audio.isEmpty() ? "" : "  🎙"));
            cap.setTextColor(idx == editing ? Color.rgb(255, 224, 150) : Color.WHITE);
            cap.setTextSize(17);
            row.addView(cap, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button del = new Button(this);
            del.setText("✕");
            del.setOnClickListener(v -> {
                if (!p.audio.isEmpty()) new File(workDir, p.audio).delete();
                panels.remove(idx);
                if (editing >= panels.size()) editing = panels.size() - 1;
                paintStrip();
                paintEditor();
            });
            row.addView(del);
            stripBox.addView(row);
        }
    }

    private void paintEditor() {
        if (editing < 0 || editing >= panels.size()) {
            editorLabel.setText("");
            captionBox.setText("");
            return;
        }
        StoryPanels.Panel p = StoryPanels.byId(panels.get(editing).panel);
        editorLabel.setText((editing + 1) + ". " + (p != null ? p.label : ""));
        captionBox.setText(panels.get(editing).caption);
    }

    private void commitCaption() {
        if (editing >= 0 && editing < panels.size()) {
            panels.get(editing).caption = captionBox.getText().toString().trim();
        }
    }

    private void toggleRecord() {
        if (editing < 0) return;
        commitCaption();
        if (recording) {
            stopRecording(true);
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        try {
            String name = "p" + editing + "_" + System.currentTimeMillis() + ".m4a";
            recTarget = new File(workDir, name);
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
        recBtn.setText(LangStore.tr(this, "story_record"));
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (keep && recTarget != null && editing >= 0) {
            String old = panels.get(editing).audio;
            if (!old.isEmpty()) new File(workDir, old).delete();
            panels.get(editing).audio = recTarget.getName();
            paintStrip();
        } else if (recTarget != null) {
            recTarget.delete();
        }
        recTarget = null;
    }

    private void save() {
        commitCaption();
        if (panels.isEmpty()) {
            Toast.makeText(this, LangStore.tr(this, "story_pick_panels"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        StoryData.Cartridge c = new StoryData.Cartridge();
        c.id = cartridgeId;
        c.title = titleBox.getText().toString().trim();
        if (c.title.isEmpty()) c.title = LangStore.tr(this, "story_my_stories");
        c.createdMs = System.currentTimeMillis();
        c.panels.addAll(panels);
        StoryFiles.saveCartridge(this, c);
        Toast.makeText(this, LangStore.tr(this, "story_saved"),
                Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (recorder != null) stopRecording(false);
        super.onDestroy();
    }

    private int resId(String drawable) {
        return getResources().getIdentifier(drawable, "drawable", getPackageName());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
