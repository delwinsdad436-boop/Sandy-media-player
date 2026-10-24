package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/**
 * The language picker (v0.5.0, Delwin #4: "you should be able to
 * choose the language when you install it"). Shown on first run —
 * before anything else, because James's ruling is that a person who
 * picks Sandy up and can't get out of English won't stay — and again
 * from Settings whenever the phone changes hands.
 *
 * Rows are the twelve baked majors (native names) plus any installed
 * packs. Picking sets the UI language: Sandy's chrome speaks it, and
 * the Live TV language facet defaults to it.
 */
public class LanguageActivity extends Activity {

    public static final String EXTRA_FROM_SETTINGS = "fromSettings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean fromSettings = getIntent().getBooleanExtra(EXTRA_FROM_SETTINGS, false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(16, 16, 20));
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "lang_choose_title"));
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Sandy speaks your language — pick it once; you can "
                + "change it any time in Settings.");
        sub.setTextColor(Color.rgb(160, 160, 170));
        sub.setTextSize(13);
        sub.setPadding(0, dp(4), 0, dp(8));
        root.addView(sub);

        List<String[]> langs = LangStore.pickerLanguages(this);
        String current = ChannelStore.getUiLanguage(this);
        ListView list = new ListView(this);
        list.setBackgroundColor(Color.rgb(16, 16, 20));
        ArrayAdapter<String[]> ad = new ArrayAdapter<String[]>(this,
                android.R.layout.simple_list_item_1, langs) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                TextView t = (TextView) v.findViewById(android.R.id.text1);
                String[] l = langs.get(pos);
                t.setText(l[1] + (l[0].equals(current) ? "  ✓" : ""));
                t.setTextColor(Color.WHITE);
                t.setTextSize(19);
                t.setPadding(dp(8), dp(12), dp(8), dp(12));
                t.setContentDescription(l[1]);
                return v;
            }
            @Override public int getCount() { return langs.size(); }
        };
        list.setAdapter(ad);
        list.setOnItemClickListener((p, v, pos, id) -> {
            ChannelStore.setUiLanguage(this, langs.get(pos)[0]);
            if (fromSettings) {
                finish();
            } else {
                startActivity(new Intent(this, HomeActivity.class));
                finish();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
