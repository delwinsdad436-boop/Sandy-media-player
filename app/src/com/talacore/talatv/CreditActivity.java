package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Sources & credit (v0.13.0) — James's order: "leave links in
 * there for people to eventually address the program or these
 * places that house these IPTV places where we're pulling from."
 * One plain page: the real front doors of the houses Sandy pulls
 * from, each named for what it gives her, each opening the place
 * itself in the browser. Every URL below was verified live the
 * day this page was built (a dead credit link is worse than
 * none); the harness pins them verbatim.
 *
 * The foot of the page carries the responsibility line, in the
 * house voice, per James's doctrine stated 2026-10-18: the
 * maker's duty is an honest tool and real precautions; what a
 * person loads, watches, and shares is their own choice and
 * their own responsibility.
 *
 * v0.17.0 (THE SANCTUARY LAYOUT): the page stands in the
 * sanctuary register, and under the intro stands the subtraction
 * equation from the design panels — Sandy's design intent,
 * stated as a design element: what she is is what's left when
 * friction, cost and surveillance are taken away.
 */
public final class CreditActivity extends Activity {

    /** The front doors, in page order — verified live 2026-10-18. */
    public static final String[] LINKS = {
        "https://drive.google.com/drive/folders/1xocvSY7tb8tky1D6oogkApVkh9d-ULmZ",
        "https://archive.org",
        "https://iptv-org.github.io/",
        "https://www.tvmaze.com",
        "https://i.mjh.nz",
        "https://epg.iptvx.one",
        "https://www.gutenberg.org",
        "https://openlibrary.org",
        "https://librivox.org",
    };

    /** The string key naming what each house gives her. */
    public static final String[] NAMES = {
        "sources_brain", "sources_archive", "sources_iptvorg",
        "sources_tvmaze", "sources_mjh", "sources_iptvx",
        "sources_gutenberg", "sources_openlibrary", "sources_librivox",
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(LangStore.tr(this, "sources_title"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Sanctuary.ground());
        int pad = (int) (Sanctuary.PAGE_PAD_DP
                * getResources().getDisplayMetrics().density + 0.5f);
        root.setPadding(pad, pad, pad, pad);

        TextView intro = new TextView(this);
        intro.setTextSize(15);
        intro.setTextColor(Sanctuary.BODY);
        intro.setText(LangStore.tr(this, "sources_intro"));
        root.addView(intro);

        // The subtraction equation — a design element on the
        // About surface, in smoked glass, gold on the dark.
        LinearLayout equationPanel = new LinearLayout(this);
        equationPanel.setOrientation(LinearLayout.VERTICAL);
        equationPanel.setBackground(Sanctuary.glassPanel(this));
        int ep = (int) (14 * getResources().getDisplayMetrics().density + 0.5f);
        equationPanel.setPadding(ep, ep, ep, ep);
        TextView equation = new TextView(this);
        equation.setText(LangStore.tr(this, "credit_equation"));
        equation.setTextColor(Sanctuary.GOLD_TEXT);
        equation.setTextSize(17);
        equation.setTypeface(equation.getTypeface(), Typeface.BOLD);
        equation.setGravity(Gravity.CENTER);
        equationPanel.addView(equation);
        LinearLayout.LayoutParams eqLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        eqLp.topMargin = ep;
        eqLp.bottomMargin = ep;
        root.addView(equationPanel, eqLp);

        List<String> rows = new ArrayList<>();
        for (String key : NAMES) rows.add(LangStore.tr(this, key));
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_1, rows) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                View v = super.getView(pos, convertView, parent);
                ((TextView) v).setTextColor(Sanctuary.INK);
                return v;
            }
        };
        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setBackgroundColor(Sanctuary.GROUND);
        list.setDivider(new ColorDrawable(Sanctuary.DIVIDER));
        list.setDividerHeight(1);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos < 0 || pos >= LINKS.length) return;
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(LINKS[pos]));
            startActivity(i);
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        TextView foot = new TextView(this);
        foot.setTextSize(14);
        foot.setTextColor(Sanctuary.MUTED);
        foot.setText(LangStore.tr(this, "sources_responsibility"));
        root.addView(foot);

        setContentView(root);
    }
}
