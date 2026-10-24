package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The Story door (v0.14.0) — a room in Sandy's house where
 * children are guests. Big tiles, big type, calm colors; entry
 * is free, and LEAVING passes the grown-up gate (the back
 * button included), so a child can't wander out of the room by
 * accident and nothing leaves the room unapproved.
 */
public class StoryActivity extends Activity {

    private static final int REQ_EXIT = 41;

    private Button bedtimeBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 28, 44));
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_title"));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(34);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        root.addView(tile(LangStore.tr(this, "story_shelf"), v ->
                startActivity(new Intent(this, StoryShelfActivity.class))));
        root.addView(tile(LangStore.tr(this, "story_make"), v ->
                startActivity(new Intent(this, StoryMakeActivity.class))));
        root.addView(tile(LangStore.tr(this, "story_my_stories"), v ->
                startActivity(new Intent(this, StoryPlayActivity.class))));
        root.addView(tile(LangStore.tr(this, "story_family_voices"), v ->
                startActivity(new Intent(this, StoryRecordActivity.class))));
        root.addView(tile(LangStore.tr(this, "story_journals"), v ->
                startActivity(new Intent(this, JournalsActivity.class))));

        bedtimeBtn = tile("", v -> {
            boolean on = !StoryFiles.bedtime(this);
            StoryFiles.setBedtime(this, on);
            paintBedtime();
        });
        root.addView(bedtimeBtn);
        paintBedtime();

        Button exit = new Button(this);
        exit.setText(LangStore.tr(this, "story_exit"));
        exit.setTextSize(16);
        exit.setTextColor(Color.rgb(170, 178, 200));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(22);
        exit.setOnClickListener(v -> askToLeave());
        root.addView(exit, lp);

        setContentView(root);
    }

    private Button tile(String label, android.view.View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(24);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(84));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        b.setOnClickListener(on);
        return b;
    }

    private void paintBedtime() {
        boolean on = StoryFiles.bedtime(this);
        bedtimeBtn.setText(LangStore.tr(this, "story_bedtime")
                + (on ? "  ✓" : ""));
        if (on) {
            bedtimeBtn.setContentDescription(
                    LangStore.tr(this, "story_bedtime_on"));
        }
    }

    private void askToLeave() {
        Intent i = new Intent(this, GrownUpGateActivity.class);
        startActivityForResult(i, REQ_EXIT);
    }

    @Override
    public void onBackPressed() {
        askToLeave();
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_EXIT && result == RESULT_OK) {
            // leave without re-entering the gate
            super.onBackPressed();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
