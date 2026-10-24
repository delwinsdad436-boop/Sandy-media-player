package com.talacore.talatv;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * The rumble hookups (v0.5.0, item 7): short vibration cues on the
 * events a viewer should be able to FEEL — playback starting, a
 * verification run finishing, a recording starting. James's ruling:
 * built for deaf viewers, good for everybody ("It's more engaging
 * for the experience"). One Settings toggle, default ON; phones
 * without a vibrator simply stay silent.
 */
public final class Haptics {

    private Haptics() {}

    /** One short pulse (~60 ms). */
    public static void pulse(Context ctx) {
        buzz(ctx, new long[]{0, 60}, 40);
    }

    /** Two pulses — the "done / go" shape (verification complete). */
    public static void goSign(Context ctx) {
        buzz(ctx, new long[]{0, 70, 90, 70}, 60);
    }

    private static void buzz(Context ctx, long[] pattern, int amplitudeHint) {
        try {
            if (!ChannelStore.getRumble(ctx)) return;
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 26) {
                int[] amps = new int[pattern.length];
                for (int i = 0; i < pattern.length; i++) {
                    amps[i] = i % 2 == 1 ? 160 : 0;
                }
                v.vibrate(VibrationEffect.createWaveform(pattern, amps, -1));
            } else {
                //noinspection deprecation
                v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {
            // a cue must never crash the thing it announces
        }
    }
}
