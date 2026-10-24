package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The playback record (v0.14.1, Addendum 1) — the error record
 * for streams, kept beside the crash logs in files/logs as
 * play-&lt;date&gt;.txt, one line per event. The fifteen-second
 * window could not be separated into its families from the old
 * record, because the old record kept almost nothing. This one
 * keeps, for every tune / failure / maintenance event:
 *
 *   the playlist address + the last reload's result,
 *   the last segment's host + address + HTTP result/class,
 *   the play position and buffered-ahead (the runway) at the
 *   moment of failure, which Hydra heads were tried and what
 *   each answered, the family the evidence classified the
 *   event into, and the decision the continuity law reached.
 *
 * Enough that the NEXT field report separates source-ended
 * from segments-refused from stuck from our-side, from the
 * record alone — no phone-side archaeology required.
 * Surfaced in Settings beside Crash reports (latest + share).
 */
public final class PlaybackRecord {

    public static final String EV_TUNE = "TUNE";
    public static final String EV_READY = "READY";
    public static final String EV_PING = "PING";
    public static final String EV_ERROR = "ERROR";
    public static final String EV_STALL = "STALL";
    public static final String EV_RECONNECT = "RECONNECT";
    public static final String EV_RECOVERED = "RECOVERED";
    public static final String EV_VERDICT = "VERDICT";

    private PlaybackRecord() {}

    /** The facts one event carries. Empty string = not known. */
    public static final class Facts {
        public String channel = "";
        public String playlistUrl = "";
        public String playlistResult = "";
        public String segmentUrl = "";
        public String segmentHost = "";
        public String segmentResult = "";
        public long positionMs = -1;
        public long bufferedMs = -1;
        public String headsTried = "";
        public String family = "";
        public String decision = "";
        public String note = "";
    }

    /** "DIRECT http=200; IA_CANONICAL http=0/noanswer; SCHEME http=403/refused". */
    public static String headsTriedText(List<Hydra.Tried> tried) {
        if (tried == null || tried.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Hydra.Tried t : tried) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(mannerName(t.manner)).append(" http=").append(t.httpCode);
            if (!t.kind.isEmpty()) sb.append('/').append(t.kind);
        }
        return sb.toString();
    }

    public static String mannerName(int manner) {
        switch (manner) {
            case Hydra.M_IA_CANONICAL: return "IA_CANONICAL";
            case Hydra.M_SCHEME: return "SCHEME";
            default: return "DIRECT";
        }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /** One record line. Pure — the harness pins the shape. */
    public static String formatEntry(long tsMs, String event, Facts f) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date(tsMs));
        StringBuilder sb = new StringBuilder();
        sb.append(ts).append('\t').append(clean(event));
        sb.append("\tchannel=").append(clean(f.channel));
        sb.append("\tplaylist=").append(clean(f.playlistUrl));
        sb.append("\tplaylistResult=").append(clean(f.playlistResult));
        sb.append("\tsegmentHost=").append(clean(f.segmentHost));
        sb.append("\tsegment=").append(clean(f.segmentUrl));
        sb.append("\tsegmentResult=").append(clean(f.segmentResult));
        sb.append("\tpositionMs=").append(f.positionMs);
        sb.append("\tbufferedMs=").append(f.bufferedMs);
        sb.append("\theads=").append(clean(f.headsTried));
        sb.append("\tfamily=").append(clean(f.family));
        sb.append("\tdecision=").append(clean(f.decision));
        sb.append("\tnote=").append(clean(f.note));
        return sb.toString();
    }

    /** Append one event to today's record file. Best-effort. */
    public static void write(Context ctx, String event, Facts f) {
        if (ctx == null) return;
        try {
            File dir = new File(ctx.getApplicationContext()
                    .getFilesDir(), "logs");
            writeTo(dir, System.currentTimeMillis(), event, f);
        } catch (Throwable ignored) {
            // The record must never become the failure.
        }
    }

    /** The writer itself, split out so the harness drives it. */
    public static void writeTo(File dir, long tsMs, String event, Facts f)
            throws Exception {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date(tsMs));
        File file = new File(dir, "play-" + day + ".txt");
        try (FileWriter fw = new FileWriter(file, true)) {
            fw.write(CrashGuard.VERSION_STAMP + " | "
                    + formatEntry(tsMs, event, f) + "\n");
        }
    }

    /** Record files, newest first (empty when none). */
    public static java.util.List<File> listFiles(Context ctx) {
        java.util.List<File> out = new java.util.ArrayList<>();
        try {
            File dir = new File(ctx.getApplicationContext()
                    .getFilesDir(), "logs");
            File[] files = dir.listFiles(
                    (d, name) -> name.startsWith("play-") && name.endsWith(".txt"));
            if (files != null) {
                java.util.Collections.addAll(out, files);
                out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The newest record file's full text, "" when none. */
    public static String readLatest(Context ctx) {
        java.util.List<File> files = listFiles(ctx);
        if (files.isEmpty()) return "";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    files.get(0).toPath());
            String all = new String(bytes,
                    java.nio.charset.StandardCharsets.UTF_8);
            // the newest day's tail is what a field report needs;
            // a whole day of pinging would bury it
            return all.length() > 20000
                    ? all.substring(all.length() - 20000) : all;
        } catch (Exception e) {
            return "";
        }
    }
}
