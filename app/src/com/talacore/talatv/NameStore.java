package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Where the channel-name map lives on the phone (v0.5.0): a refreshed
 * copy in filesDir wins; otherwise the bundled snapshot asset seeds
 * it. Refresh pulls iptv-org's two API files and rebuilds the map
 * with the same rule the asset was built with (ChannelNames.buildMap)
 * — best-effort: a failed refresh keeps whatever map is already here.
 */
public final class NameStore {

    private static final String MAP_FILE = "channels_map.tsv";
    private static final String API_CHANNELS = "https://iptv-org.github.io/api/channels.json";
    private static final String API_LOGOS = "https://iptv-org.github.io/api/logos.json";

    private static volatile boolean loaded = false;

    private NameStore() {}

    public static synchronized void ensureLoaded(Context ctx) {
        if (loaded) return;
        loaded = true;
        Context app = ctx.getApplicationContext();
        try {
            File f = new File(app.getFilesDir(), MAP_FILE);
            String text = null;
            if (f.exists()) {
                text = readAll(new FileInputStream(f));
            } else {
                try (InputStream in = app.getAssets().open(MAP_FILE)) {
                    text = readAll(in);
                }
            }
            if (text != null) ChannelNames.setMap(ChannelNames.parseMapTsv(text));
        } catch (Exception ignored) {
            // no map: display names fall back to cleaned playlist names
        }
    }

    /** Best-effort refresh from the live database; true when it landed. */
    public static synchronized boolean refresh(Context ctx) {
        try {
            String channels = Net.getString(API_CHANNELS);
            String logos = Net.getString(API_LOGOS);
            Map<String, String[]> fresh = ChannelNames.buildMap(channels, logos);
            if (fresh.isEmpty()) return false;
            ChannelNames.setMap(fresh);
            loaded = true;
            File f = new File(ctx.getApplicationContext().getFilesDir(), MAP_FILE);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(ChannelNames.toMapTsv(fresh).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
