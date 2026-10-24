package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prepared-dub naming (v0.4.0), pure so the harness can prove it.
 *
 * The convention: a library video named
 *     Show Name S01E01.mkv
 * can carry a prepared English dub as a sidecar audio file in the
 * same folder, same basename, with one of these suffixes:
 *     Show Name S01E01.dub-en.m4a
 *     Show Name S01E01.dub-en.mp3
 * When such a sidecar is found (lookup lives in DubFinder — MediaStore,
 * SAF trees and plain paths), the player offers "English — prepared
 * dub" in the Subtitles/Audio menu and plays it INSTEAD of the
 * original audio, merged onto the same timeline. DUB LAB (on the build
 * machine) is how a sidecar gets made: transcribe the Japanese, translate,
 * render English speech fitted to the subtitle windows, save the .m4a
 * beside the episode. One at a time, on request.
 */
public final class DubTrack {

    public static final String[] SIDECAR_SUFFIXES = {".dub-en.m4a", ".dub-en.mp3"};

    private DubTrack() {}

    /** "Show S01E01.mkv" -> "Show S01E01". Files without an extension keep their name. */
    public static String stemOf(String fileName) {
        if (fileName == null) return "";
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String base = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    /** Candidate sidecar file names for a video file name, in preference order. */
    public static List<String> sidecarNames(String videoFileName) {
        List<String> out = new ArrayList<>();
        String stem = stemOf(videoFileName);
        if (stem.isEmpty()) return out;
        for (String suffix : SIDECAR_SUFFIXES) out.add(stem + suffix);
        return out;
    }

    /** True when a file name is itself a prepared-dub sidecar. */
    public static boolean isDubSidecar(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        for (String suffix : SIDECAR_SUFFIXES) if (lower.endsWith(suffix)) return true;
        return lower.contains(".dub-") && (lower.endsWith(".m4a") || lower.endsWith(".mp3"));
    }

    // ------------------------------------------------ v0.6.0: translation

    /**
     * Prepared-dub sidecar names for ANY target language (v0.6.0):
     *     Show S01E01.dub-ja.m4a / .mp3
     * The original convention (English) is {@link #sidecarNames}.
     */
    public static List<String> dubNamesFor(String videoFileName, String lang) {
        List<String> out = new ArrayList<>();
        String stem = stemOf(videoFileName);
        if (stem.isEmpty() || lang == null || lang.isEmpty()) return out;
        out.add(stem + ".dub-" + lang + ".m4a");
        out.add(stem + ".dub-" + lang + ".mp3");
        return out;
    }

    /**
     * Cooked translated-subtitle sidecar names (v0.6.0):
     *     Show S01E01.tr-en.srt   (the playable track)
     *     Show S01E01.tr-en.json  (the full record: original lines,
     *                              speakers, uncertainty flags)
     */
    public static List<String> trSubNames(String videoFileName, String lang) {
        List<String> out = new ArrayList<>();
        String stem = stemOf(videoFileName);
        if (stem.isEmpty() || lang == null || lang.isEmpty()) return out;
        out.add(stem + ".tr-" + lang + ".srt");
        out.add(stem + ".tr-" + lang + ".vtt");
        return out;
    }

    /** The rich-record companion of a cooked subtitle sidecar. */
    public static String trJsonName(String videoFileName, String lang) {
        String stem = stemOf(videoFileName);
        if (stem.isEmpty() || lang == null || lang.isEmpty()) return "";
        return stem + ".tr-" + lang + ".json";
    }

    /** True when a file name is a cooked translation sidecar. */
    public static boolean isTrSidecar(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.contains(".tr-") && (lower.endsWith(".srt")
                || lower.endsWith(".vtt") || lower.endsWith(".json"));
    }

    /** True when a file name is a video we would look for a dub beside. */
    public static boolean isVideoName(String fileName) {
        if (fileName == null) return false;
        if (isDubSidecar(fileName)) return false;
        String lower = fileName.toLowerCase(Locale.US);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".webm") || lower.endsWith(".m4v")
                || lower.endsWith(".ts");
    }
}
