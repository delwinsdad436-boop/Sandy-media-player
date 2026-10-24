package com.talacore.talatv;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Voice coverage (v0.13.0) — the 83 catalogue languages, each
 * with THIS phone's honest state: a Sandy voice pack is in, the
 * phone's own voice can speak it, or there is no voice for it
 * yet. The system-voice column is probed live from the phone's
 * speech engine (availability is a fact of the device, never a
 * promise printed in the build). Reached from Settings ▸
 * Translation ▸ Voice packs.
 */
public final class VoiceCoverageActivity extends Activity {

    private final List<String> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private List<VoiceLangs.Row> langs = new ArrayList<>();
    private Set<String> packCodes = new HashSet<>();
    private final Set<String> systemIso1 = new HashSet<>();
    private TextToSpeech probe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(LangStore.tr(this, "voice_coverage_title"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * getResources().getDisplayMetrics().density + 0.5f);
        root.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setTextSize(15);
        head.setText(LangStore.tr(this, "voice_coverage_title"));
        root.addView(head);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        langs = VoiceLangs.load(this);
        packCodes = VoicePackStore.installedCodes(this);
        showRows();
        probeSystemVoices();
    }

    @Override
    protected void onDestroy() {
        if (probe != null) {
            try { probe.shutdown(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void showRows() {
        rows.clear();
        for (VoiceLangs.Row r : langs) {
            VoiceResolve.Result v = VoiceResolve.resolve(r, packCodes, systemIso1);
            String state;
            switch (v.voice) {
                case PACK: state = LangStore.tr(this, "voice_state_pack"); break;
                case SYSTEM: state = LangStore.tr(this, "voice_state_system"); break;
                default: state = LangStore.tr(this, "voice_state_none");
            }
            rows.add(r.tvName + " — " + state);
        }
        adapter.notifyDataSetChanged();
    }

    /** Ask the phone's own engine which languages IT can speak. */
    private void probeSystemVoices() {
        final TextToSpeech[] holder = new TextToSpeech[1];
        holder[0] = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                try {
                    for (VoiceLangs.Row r : langs) {
                        if (r.iso1.isEmpty()) continue;
                        int a = holder[0].isLanguageAvailable(
                                Locale.forLanguageTag(r.iso1));
                        if (a >= TextToSpeech.LANG_AVAILABLE) {
                            systemIso1.add(r.iso1);
                        }
                    }
                } catch (Exception ignored) {}
            }
            runOnUiThread(this::showRows);
        });
        probe = holder[0];
    }
}
