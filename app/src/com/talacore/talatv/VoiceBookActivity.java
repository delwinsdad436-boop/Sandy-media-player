package com.talacore.talatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The voice book screen (v0.6.0) — Delwin's casting desk.
 *
 * Every show that has a book is listed; inside a book, each cast
 * entry can be named (the character), assigned one of the phone's
 * own speech voices, and given a pitch. Assignments persist in the
 * book itself (VoiceBookStore) — set once, by a person, and every
 * later episode of that show casts from it.
 *
 * Honest edges, by design: entries appear when a voiced dub is made
 * for a show. This build does not diarize (no reliable on-device
 * diarizer exists in this build environment), so new books open
 * with a single narrator entry — per-character entries arrive with
 * cooked records that carry speakers, and near-match flags from the
 * matcher are shown, never silently resolved.
 */
public class VoiceBookActivity extends Activity {

    private LinearLayout root;
    private List<String> deviceVoices = new ArrayList<>();
    private VoiceBook.Book openBook;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 16, 22));
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);
        TtsDubRenderer.availableVoiceNames(this, names -> {
            deviceVoices = names;
            if (openBook != null) showBook(openBook);
        });
        showList();
    }

    private void showList() {
        openBook = null;
        root.removeAllViews();
        root.addView(title(LangStore.tr(this, "vb_title")));
        List<VoiceBook.Book> books = VoiceBookStore.listAll(this);
        if (books.isEmpty()) {
            root.addView(body(LangStore.tr(this, "vb_empty")));
            return;
        }
        for (VoiceBook.Book b : books) {
            Button btn = new Button(this);
            String name = b.showTitle != null && !b.showTitle.isEmpty()
                    ? b.showTitle : b.showKey;
            btn.setText(name + "  (" + b.cast.size() + ")");
            btn.setContentDescription(name);
            btn.setOnClickListener(v -> showBook(b));
            root.addView(btn);
        }
    }

    private void showBook(VoiceBook.Book book) {
        openBook = book;
        root.removeAllViews();
        Button back = new Button(this);
        back.setText("← " + LangStore.tr(this, "vb_title"));
        back.setOnClickListener(v -> showList());
        root.addView(back);
        String name = book.showTitle != null && !book.showTitle.isEmpty()
                ? book.showTitle : book.showKey;
        root.addView(title(name));
        if (book.cast.isEmpty()) {
            root.addView(body(LangStore.tr(this, "vb_empty")));
            return;
        }
        for (VoiceBook.CastEntry e : book.cast) {
            root.addView(castCard(book, e));
        }
        Button add = new Button(this);
        add.setText("+ Add a cast entry");
        add.setOnClickListener(v -> {
            VoiceBook.enroll(book, "", null);
            VoiceBookStore.save(this, book);
            showBook(book);
        });
        root.addView(add);
    }

    private LinearLayout castCard(VoiceBook.Book book, VoiceBook.CastEntry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(10), 0, dp(6));

        TextView head = new TextView(this);
        String who = e.name != null && !e.name.isEmpty() ? e.name
                : (e.label != null && !e.label.isEmpty() ? e.label : "Voice");
        head.setText(who + (e.season > 0 ? "  · Season " + e.season : "")
                + (e.nearMatch
                ? "  ⚠ near match — a person should confirm this voice" : ""));
        head.setTextColor(Color.rgb(255, 200, 90));
        head.setTextSize(16);
        head.setTypeface(head.getTypeface(), Typeface.BOLD);
        card.addView(head);

        EditText nameField = new EditText(this);
        nameField.setHint("Character name");
        nameField.setText(e.name == null ? "" : e.name);
        nameField.setTextColor(Color.WHITE);
        nameField.setHintTextColor(Color.rgb(120, 120, 130));
        nameField.setSingleLine(true);
        card.addView(nameField);
        Button saveName = new Button(this);
        saveName.setText("Save name");
        saveName.setOnClickListener(v -> {
            e.name = nameField.getText().toString().trim();
            VoiceBookStore.save(this, book);
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show();
            showBook(book);
        });
        card.addView(saveName);

        Button voiceBtn = new Button(this);
        voiceBtn.setText("Voice: " + (e.ttsVoice == null || e.ttsVoice.isEmpty()
                ? "(the phone's default)" : e.ttsVoice));
        voiceBtn.setOnClickListener(v -> pickVoice(book, e));
        card.addView(voiceBtn);

        LinearLayout pitchRow = new LinearLayout(this);
        pitchRow.setOrientation(LinearLayout.HORIZONTAL);
        pitchRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView pitchLabel = new TextView(this);
        pitchLabel.setText(String.format(Locale.US, "Pitch %.1f  ", e.pitch));
        pitchLabel.setTextColor(Color.WHITE);
        pitchLabel.setTextSize(15);
        pitchRow.addView(pitchLabel);
        Button minus = new Button(this);
        minus.setText("−");
        minus.setOnClickListener(v -> {
            e.pitch = Math.max(0.5, Math.round((e.pitch - 0.1) * 10) / 10.0);
            VoiceBookStore.save(this, book);
            showBook(book);
        });
        pitchRow.addView(minus);
        Button plus = new Button(this);
        plus.setText("+");
        plus.setOnClickListener(v -> {
            e.pitch = Math.min(2.0, Math.round((e.pitch + 0.1) * 10) / 10.0);
            VoiceBookStore.save(this, book);
            showBook(book);
        });
        pitchRow.addView(plus);
        card.addView(pitchRow);
        return card;
    }

    private void pickVoice(VoiceBook.Book book, VoiceBook.CastEntry e) {
        if (deviceVoices.isEmpty()) {
            Toast.makeText(this, "This phone's speech engine listed no "
                    + "voices to choose from.", Toast.LENGTH_LONG).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        labels.add("(the phone's default)");
        labels.addAll(deviceVoices);
        new AlertDialog.Builder(this)
                .setTitle("Voice for " + (e.name != null && !e.name.isEmpty()
                        ? e.name : e.label))
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    e.ttsVoice = which == 0 ? "" : deviceVoices.get(which - 1);
                    VoiceBookStore.save(this, book);
                    showBook(book);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView title(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(22);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, 0, 0, dp(10));
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.rgb(200, 200, 208));
        t.setTextSize(14);
        t.setPadding(0, 0, 0, dp(6));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
