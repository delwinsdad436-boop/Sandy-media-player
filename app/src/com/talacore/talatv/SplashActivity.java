package com.talacore.talatv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The boot screen (v0.4.0, James's order: "Make it have a boot screen
 * with that picture on it"). The launcher entry: the recliner-lady
 * picture — James's design, his mother in front of the TV — full-bleed
 * on the art's own dark navy, "Sandy" baked under it as a wordmark.
 *
 * No white flash: the picture is ALSO the window background (theme),
 * so the very first frame the system draws is already the boot screen.
 * On Android 12+ the platform splash uses the same background color
 * and the app icon, handing into this screen without a jolt.
 *
 * The hold is honest, not theater: the screen stays while real
 * startup work runs — the channel-guide warm-up (first install) and
 * the verdict auto-fresh trigger — with a ~1.2 s minimum brand beat,
 * and a hard ~3.5 s cap so a slow network can never trap anyone on
 * it. Then straight into the home screen.
 */
public class SplashActivity extends Activity {

    private static final long MIN_HOLD_MS = 1200;
    private static final long MAX_HOLD_MS = 3500;

    private volatile boolean navigated = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashGuard.install(this);
        // Hydra's learned doors: prime the ladder memory from
        // the memory hook before anything resolves (v0.14.0).
        HydraStore.prime(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(Sanctuary.ground());
        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.splash_art);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setContentDescription("Sandy");
        root.addView(art, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        long started = System.currentTimeMillis();
        CountDownLatch warm = new CountDownLatch(1);
        new Thread(() -> {
            try {
                // Verdict warm-up: stale checks start re-verifying now
                // (favorites first); it finishes in the background.
                FreshVerify.maybeRun(getApplicationContext(),
                        () -> ChannelStore.loadCache(getApplicationContext()), null);
                // Directory warm-up: first install only — later opens
                // already have the cached guide.
                MainActivity.warmUpDirectory(getApplicationContext());
            } catch (Exception ignored) {
                // warm-up is a head start, never a gate
            } finally {
                warm.countDown();
            }
        }, "sandy-boot").start();

        new Thread(() -> {
            try {
                warm.await(MAX_HOLD_MS, TimeUnit.MILLISECONDS);
                long elapsed = System.currentTimeMillis() - started;
                if (elapsed < MIN_HOLD_MS) {
                    Thread.sleep(MIN_HOLD_MS - elapsed);
                }
            } catch (InterruptedException ignored) {}
            runOnUiThread(this::goHome);
        }, "sandy-boot-timer").start();
    }

    private void goHome() {
        if (navigated || isFinishing()) return;
        navigated = true;
        // First run (v0.5.0): the language comes before everything —
        // a person who picks Sandy up must be able to leave English
        // in the first ten seconds, or they won't stay (James's ruling).
        Class<?> next = ChannelStore.hasChosenLanguage(this)
                ? HomeActivity.class : LanguageActivity.class;
        startActivity(new Intent(this, next));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    public void onBackPressed() {
        // The boot screen is a doorway, not a room: back does nothing
        // for the second it is up (and never dumps anyone at a dead end).
    }
}
