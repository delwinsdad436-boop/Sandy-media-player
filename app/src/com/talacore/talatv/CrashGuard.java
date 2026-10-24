package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Crash-to-file (v0.8.0) — the Tala Ripper pattern, adopted after
 * Sandy 0.7.0 died in James's hands with no evidence attached: the
 * system said "this app has a bug" and the trail ended there.
 *
 * Installs a default uncaught-exception handler that writes the
 * failure to files/logs/crash-<ts>.txt — version stamp as the
 * FIRST line, so any log that reaches us names its build — then
 * chains to whatever handler Android had before (the system crash
 * dialog still appears; nothing is suppressed or hidden).
 *
 * Also the drop-box for guarded seams: library code that Sandy's
 * screens touch but must never be able to take the app down with
 * (the share stack above all) leaves its Throwable here via
 * {@link #log} instead of vanishing into a bare catch.
 *
 * The logs live in filesDir — they survive the system cache clear
 * that the crash dialog itself suggests.
 */
public final class CrashGuard {

    /** Version stamp written as the first line of every crash log. */
    public static final String VERSION_STAMP = "Sandy 0.18.0 (versionCode 24)";

    private static volatile boolean installed = false;

    private CrashGuard() {}

    /** Idempotent. Safe to call from every activity's onCreate. */
    public static synchronized void install(Context ctx) {
        if (installed || ctx == null) return;
        installed = true;
        final File logDir = new File(ctx.getApplicationContext().getFilesDir(), "logs");
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                writeCrash(logDir, "uncaught on " + thread.getName(), error);
            } catch (Throwable ignored) {
                // The handler itself must never become the crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    // ------------------------------------------------------ reading
    // v0.12.1: the logs used to be write-only — the 0.7.0 field crash
    // left its stack in files/logs on James's phone with NO way in
    // the app to see or share it, so its root cause is still
    // unestablished years later. These readers back the Settings
    // "Crash reports" view: the latest crash, in the open, shareable.

    /** Crash log files, newest first (empty when none). */
    public static java.util.List<File> listCrashes(Context ctx) {
        java.util.List<File> out = new java.util.ArrayList<>();
        try {
            File dir = new File(ctx.getApplicationContext().getFilesDir(), "logs");
            File[] files = dir.listFiles(
                    (d, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
            if (files != null) {
                java.util.Collections.addAll(out, files);
                out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The newest crash log's full text, "" when none/unreadable. */
    public static String readLatest(Context ctx) {
        java.util.List<File> files = listCrashes(ctx);
        if (files.isEmpty()) return "";
        return readFile(files.get(0));
    }

    /** One crash log's full text, "" when unreadable. */
    public static String readFile(File f) {
        if (f == null) return "";
        try {
            byte[] bytes = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int off = 0;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n;
                while (off < bytes.length
                        && (n = in.read(bytes, off, bytes.length - off)) > 0) {
                    off += n;
                }
            }
            return new String(bytes, 0, off, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Leave a caught Throwable in the log instead of swallowing it. */
    public static void log(Context ctx, String where, Throwable t) {
        if (ctx == null || t == null) return;
        try {
            File logDir = new File(ctx.getApplicationContext().getFilesDir(), "logs");
            writeCrash(logDir, "caught at " + where, t);
        } catch (Throwable ignored) {
            // Logging is best-effort by design; never throw from a guard.
        }
    }

    private static void writeCrash(File logDir, String where, Throwable t)
            throws Exception {
        if (!logDir.isDirectory() && !logDir.mkdirs()) return;
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        File f = new File(logDir, "crash-" + ts + ".txt");
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        try (FileWriter fw = new FileWriter(f, true)) {
            fw.write(VERSION_STAMP + "\n");
            fw.write(where + " at " + new Date().toString() + "\n");
            fw.write(sw.toString());
            fw.write("\n");
        }
    }
}
