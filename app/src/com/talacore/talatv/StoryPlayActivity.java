package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * My Stories (v0.14.0) — the children's own storyboards, played
 * back panel by panel: the picture big, their words under it,
 * their recorded telling where they made one (else the phone's
 * voice reads their words). Sharing a cartridge leaves ONLY
 * through the grown-up gate, hand to hand, as a file — the
 * room's plain line stands on the share: there is no public
 * shelf yet, and nothing is posted anywhere from here.
 */
public class StoryPlayActivity extends Activity {

    public static final String X_CARTRIDGE = "cartridge";
    private static final int REQ_SHARE = 43;

    private StoryData.Cartridge cartridge;
    private int panel = 0;
    private MediaPlayer player;
    private TextToSpeech tts;
    private File pendingZip;

    private ImageView picView;
    private TextView captionView, countView;
    private Button playBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String cid = getIntent().getStringExtra(X_CARTRIDGE);
        if (cid == null || cid.isEmpty()) {
            showList();
            return;
        }
        for (StoryData.Cartridge c : StoryFiles.cartridges(this)) {
            if (c.id.equals(cid)) cartridge = c;
        }
        if (cartridge == null || cartridge.panels.isEmpty()) {
            showList();
            return;
        }
        showPlayer();
    }

    // ------------------------------------------------------------ list

    private void showList() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(24, 28, 44));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText(LangStore.tr(this, "story_my_stories"));
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(30);
        root.addView(title);

        List<StoryData.Cartridge> carts = StoryFiles.cartridges(this);
        if (carts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(LangStore.tr(this, "story_make_empty"));
            empty.setTextColor(Color.rgb(200, 206, 226));
            empty.setTextSize(19);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(18);
            root.addView(empty, lp);
        }
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (StoryData.Cartridge c : carts) {
            Button b = new Button(this);
            b.setText(c.title + "\n" + df.format(new Date(c.createdMs)));
            b.setTextSize(21);
            b.setAllCaps(false);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(92));
            lp.topMargin = dp(12);
            b.setOnClickListener(v -> {
                Intent i = new Intent(this, StoryPlayActivity.class);
                i.putExtra(X_CARTRIDGE, c.id);
                startActivity(i);
            });
            root.addView(b, lp);
        }
        setContentView(scroll);
    }

    // ------------------------------------------------------------ play

    private void showPlayer() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(24, 28, 44));
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(cartridge.title);
        title.setTextColor(Color.rgb(255, 224, 150));
        title.setTextSize(26);
        root.addView(title);

        if (cartridge.bookId != null && !cartridge.bookId.isEmpty()) {
            // A story made from a book (v0.16.0): provenance
            // labeled, and the telling itself one tap away.
            TextView prov = new TextView(this);
            prov.setText(LangStore.tr(this, "story_made_from") + " "
                    + cartridge.fromBook + " (" + cartridge.fromSource + ")");
            prov.setTextColor(Color.rgb(170, 178, 205));
            prov.setTextSize(16);
            root.addView(prov);
            Button tell = new Button(this);
            tell.setText(LangStore.tr(this, "story_tell_it"));
            tell.setTextSize(19);
            tell.setAllCaps(false);
            tell.setOnClickListener(v -> {
                Intent i = new Intent(this, StoryTellActivity.class);
                i.putExtra(StoryTellActivity.X_BOOK, cartridge.bookId);
                startActivity(i);
            });
            root.addView(tell);
        }

        picView = new ImageView(this);
        picView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picView.setBackgroundColor(Color.rgb(14, 16, 26));
        root.addView(picView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));

        captionView = new TextView(this);
        captionView.setTextColor(Color.WHITE);
        captionView.setTextSize(24);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cl.topMargin = dp(12);
        root.addView(captionView, cl);

        countView = new TextView(this);
        countView.setTextColor(Color.rgb(170, 178, 200));
        countView.setTextSize(14);
        root.addView(countView);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button prev = bigButton(LangStore.tr(this, "reader_back"), v -> step(-1));
        playBtn = bigButton(LangStore.tr(this, "story_play_story"), v -> tellPanel());
        Button next = bigButton(LangStore.tr(this, "reader_forward"), v -> step(1));
        controls.addView(prev, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(playBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(next, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(controls);

        Button share = new Button(this);
        share.setText(LangStore.tr(this, "story_share"));
        share.setTextSize(17);
        share.setAllCaps(false);
        share.setOnClickListener(v -> {
            Intent gate = new Intent(this, GrownUpGateActivity.class);
            gate.putExtra(GrownUpGateActivity.X_WHY,
                    LangStore.tr(this, "story_gate_for_share"));
            startActivityForResult(gate, REQ_SHARE);
        });
        LinearLayout.LayoutParams sh = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sh.topMargin = dp(10);
        root.addView(share, sh);

        tts = new TextToSpeech(this, status -> {});
        setContentView(root);
        showPanel(0);
    }

    private Button bigButton(String label, View.OnClickListener on) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setAllCaps(false);
        b.setMinHeight(dp(64));
        b.setOnClickListener(on);
        return b;
    }

    private void showPanel(int i) {
        panel = Math.max(0, Math.min(i, cartridge.panels.size() - 1));
        StoryData.CartridgePanel p = cartridge.panels.get(panel);
        picView.setImageResource(
                resId(StoryPanels.drawableName(p.panel)));
        captionView.setText(p.caption);
        countView.setText(String.format(
                LangStore.tr(this, "story_panel_of"),
                panel + 1, cartridge.panels.size()));
    }

    private void step(int dir) {
        stopTelling();
        showPanel(panel + dir);
    }

    private void tellPanel() {
        StoryData.CartridgePanel p = cartridge.panels.get(panel);
        File dir = StoryFiles.cartridgeDir(this, cartridge.id);
        if (!p.audio.isEmpty() && new File(dir, p.audio).isFile()) {
            stopTelling();
            try {
                player = new MediaPlayer();
                player.setDataSource(new File(dir, p.audio).getAbsolutePath());
                player.setOnCompletionListener(mp -> stopTelling());
                player.prepare();
                player.start();
            } catch (Exception e) {
                stopTelling();
            }
            return;
        }
        if (!p.caption.isEmpty() && tts != null) {
            String ui = ChannelStore.getUiLanguage(this);
            try {
                tts.setLanguage(ui == null || ui.isEmpty()
                        ? Locale.getDefault() : Locale.forLanguageTag(ui));
            } catch (Exception ignored) {}
            tts.speak(p.caption, TextToSpeech.QUEUE_FLUSH, new Bundle(), "panel");
        }
    }

    private void stopTelling() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
    }

    // ------------------------------------------------------------ share

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_SHARE && result == RESULT_OK && cartridge != null) {
            shareCartridge();
        }
    }

    /** The cartridge leaves as ONE file (a zip of its folder),
     *  hand to hand through the phone's own share sheet. */
    private void shareCartridge() {
        try {
            File dir = StoryFiles.cartridgeDir(this, cartridge.id);
            File zip = new File(getCacheDir(), cartridge.id + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        zos.putNextEntry(new ZipEntry(f.getName()));
                        try (FileInputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                        zos.closeEntry();
                    }
                }
            }
            pendingZip = zip;
            Uri uri = FileProvider.getUriForFile(
                    this, "com.talacore.talatv.files", zip);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, cartridge.title);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Toast.makeText(this, LangStore.tr(this, "story_share_note"),
                    Toast.LENGTH_LONG).show();
            startActivity(Intent.createChooser(send,
                    LangStore.tr(this, "story_share")));
        } catch (Exception e) {
            Toast.makeText(this, LangStore.tr(this, "share_unavailable"),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        stopTelling();
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private int resId(String drawable) {
        return getResources().getIdentifier(drawable, "drawable", getPackageName());
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
