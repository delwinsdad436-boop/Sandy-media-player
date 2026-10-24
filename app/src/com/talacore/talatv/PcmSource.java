package com.talacore.talatv;

/**
 * A source of 16 kHz mono PCM for the cooker (v0.6.1). The desktop
 * JVM proof reads WAV files (WavPcm); on the phone, media files are
 * decoded to PCM by AudioPcmDecoder. The cooker only ever sees this
 * interface, so the engine binding is identical on both.
 */
public interface PcmSource {

    /** Total duration in seconds. */
    double durationSec();

    /**
     * Samples in [fromSec, fromSec + durSec) as floats in -1..1 at
     * 16 kHz mono. Short at end of stream; empty past it.
     */
    float[] read(double fromSec, double durSec) throws Exception;
}
