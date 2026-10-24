package com.talacore.talatv;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Random;

/**
 * The grown-up gate (v0.14.0) — a PLAIN check at the story
 * room's thresholds: leaving the room, sharing a child's story,
 * recording or managing family voices and journals. Two parts:
 * hold the big button down (small hands let go), then answer a
 * grown-up question (GateQuestions — numbers carry it in any
 * language). It is not a lock and doesn't pretend to be one; it
 * keeps a child from wandering out or sending something by
 * accident, which is the job James gave it.
 *
 * Callers launch it for a result; RESULT_OK = a grown-up's hand
 * was here. Extra "why" = a line naming what the gate guards.
 */
public class GrownUpGateActivity extends Activity {

    public static final String X_WHY = "why";

    private static final long HOLD_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar holdBar;
    private TextView questionView, statusView;
    private LinearLayout optionsBox;
    private Button holdBtn;
    private long holdStart = 0;
    private boolean holding = false;
    private boolean asked = false;
    private GateQuestions.Question question;
    private final Random rnd = new Random();

    private final Runnable holdTick = new Runnable() {
        @Override public void run() {
            if (!holding) return;
            long el = System.currentTimeMillis() - holdStart;
            holdBar.setProgress((int) Math.min(100, el * 100 / HOLD_MS));
            if (el >= HOLD_MS) {
                holding = false;
                showQuestion();
            } else {
                handler.postDelayed(this, 40);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 28, 44));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_gate_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        String why = getIntent().getStringExtra(X_WHY);
        if (why != null && !why.isEmpty()) {
            TextView whyView = new TextView(this);
            whyView.setText(why);
            whyView.setTextColor(Color.rgb(190, 198, 220));
            whyView.setTextSize(17);
            whyView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            root.addView(whyView, lp);
        }

        holdBtn = new Button(this);
        holdBtn.setText(LangStore.tr(this, "story_gate_hold"));
        holdBtn.setTextSize(22);
        LinearLayout.LayoutParams hb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
        hb.topMargin = dp(28);
        root.addView(holdBtn, hb);
        holdBtn.setOnTouchListener((v, ev) -> {
            if (asked) return false;
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                holding = true;
                holdStart = System.currentTimeMillis();
                handler.post(holdTick);
                return true;
            }
            if (ev.getAction() == MotionEvent.ACTION_UP
                    || ev.getAction() == MotionEvent.ACTION_CANCEL) {
                holding = false;
                holdBar.setProgress(0);
                return true;
            }
            return false;
        });

        holdBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        holdBar.setMax(100);
        LinearLayout.LayoutParams pb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        pb.topMargin = dp(10);
        root.addView(holdBar, pb);

        questionView = new TextView(this);
        questionView.setTextColor(Color.WHITE);
        questionView.setTextSize(26);
        questionView.setGravity(Gravity.CENTER);
        questionView.setVisibility(View.GONE);
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        ql.topMargin = dp(28);
        root.addView(questionView, ql);

        optionsBox = new LinearLayout(this);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        optionsBox.setVisibility(View.GONE);
        root.addView(optionsBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(255, 190, 120));
        statusView.setTextSize(18);
        statusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(14);
        root.addView(statusView, sl);

        setContentView(root);
    }

    private void showQuestion() {
        asked = true;
        holdBtn.setVisibility(View.GONE);
        holdBar.setVisibility(View.GONE);
        question = GateQuestions.generate(rnd);
        questionView.setText(question.kind == GateQuestions.KIND_BIGGEST
                ? LangStore.tr(this, "story_gate_biggest")
                : String.format(LangStore.tr(this, "story_gate_sum"),
                        question.a, question.b));
        questionView.setVisibility(View.VISIBLE);
        optionsBox.removeAllViews();
        for (int i = 0; i < question.options.size(); i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(String.valueOf(question.options.get(i)));
            b.setTextSize(26);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(76));
            lp.topMargin = dp(10);
            b.setOnClickListener(v -> {
                if (GateQuestions.isCorrect(question, idx)) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    statusView.setText(LangStore.tr(this, "story_gate_wrong"));
                    showQuestion();
                }
            });
            optionsBox.addView(b, lp);
        }
        optionsBox.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        holding = false;
        handler.removeCallbacks(holdTick);
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
