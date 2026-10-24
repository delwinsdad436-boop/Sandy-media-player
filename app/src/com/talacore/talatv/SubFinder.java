package com.talacore.talatv;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finding the subtitle files a video already carries (v0.12.0) —
 * the harvest's eyes. Same three roads as DubFinder, because
 * subtitles live wherever the video lives: a plain folder path
 * (scan the folder for any subtitle sibling), the media files
 * table (exact sidecar names), and the picked folder tree.
 * A subtitle the finder cannot see is a subtitle the flow will
 * honestly call missing — the ear road then applies.
 */
public final class SubFinder {

    public static final class Found {
        /** content URI string, or an absolute path on the plain road. */
        public String uri = "";
        public String name = "";
        /** Language declared by the file's name; "" when unnamed. */
        public String lang = "";
    }

    private SubFinder() {}

    public static List<Found> find(Context ctx, String videoUrl, String videoName) {
        Map<String, Found> byName = new LinkedHashMap<>();
        String stem = DubTrack.stemOf(videoName == null ? "" : videoName);
        // Road 1: a plain path — scan the folder for subtitle siblings
        if (videoUrl != null && (videoUrl.startsWith("/")
                || videoUrl.startsWith("file://"))) {
            String path = videoUrl.startsWith("file://")
                    ? Uri.parse(videoUrl).getPath() : videoUrl;
            File video = new File(path == null ? "" : path);
            File dir = video.getParentFile();
            if (dir != null && dir.isDirectory()) {
                File[] kids = dir.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        String n = k.getName();
                        if (!SubHarvest.isSubtitleName(n)) continue;
                        if (!n.toLowerCase(java.util.Locale.US).startsWith(
                                stem.toLowerCase(java.util.Locale.US))) continue;
                        if (n.toLowerCase(java.util.Locale.US).contains(".tr-")) continue;
                        Found f = new Found();
                        f.uri = k.getAbsolutePath();
                        f.name = n;
                        f.lang = SubHarvest.langFromName(n, stem);
                        byName.put(n.toLowerCase(java.util.Locale.US), f);
                    }
                }
            }
        }
        // Roads 2+3: exact candidate names through DubFinder's own
        // roads (the media files table, then the picked folder
        // tree) — the same machinery that finds prepared dubs.
        if (videoUrl != null && !videoUrl.isEmpty()) {
            LibraryIndex.Entry entry = new LibraryIndex.Entry();
            entry.uri = videoUrl;
            entry.fileName = videoName == null ? "" : videoName;
            for (String name : SubHarvest.candidateNames(videoName)) {
                String key = name.toLowerCase(java.util.Locale.US);
                if (byName.containsKey(key)) continue;
                String hit = DubFinder.findAny(ctx, entry,
                        java.util.Collections.singletonList(name), true);
                if (hit != null) {
                    Found f = new Found();
                    f.uri = hit;
                    f.name = name;
                    f.lang = SubHarvest.langFromName(name, stem);
                    byName.put(key, f);
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    /** Read a found subtitle's text (plain path or content URI). */
    public static String readText(Context ctx, Found f) {
        try {
            java.io.InputStream in;
            if (f.uri.startsWith("/")) {
                in = new java.io.FileInputStream(new File(f.uri));
            } else {
                in = ctx.getContentResolver().openInputStream(Uri.parse(f.uri));
            }
            if (in == null) return null;
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return new String(bos.toByteArray(), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
