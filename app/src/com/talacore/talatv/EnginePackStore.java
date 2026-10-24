package com.talacore.talatv;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Where an installed engine pack lives (v0.6.0):
 *   filesDir/engine/<pair>/asr/…, mt/…   the model files
 *   filesDir/engine/<pair>.json         the install record
 *
 * Install is one streaming pass through EnginePack's fail-closed
 * validation into a staging folder; only a fully valid pack is
 * promoted to its pair folder. A refused pack leaves nothing behind.
 */
public final class EnginePackStore {

    public static final class Status {
        public boolean installed = false;
        public String pair = "";
        public int version = 0;
        public String asrKind = "", mtKind = "";
        /** v0.6.1: the pack formats (e.g. onnx-int8), so the player
         *  can tell a runnable ONNX pack from a CT2 pack it cannot
         *  bind. Records written by 0.6.0 lack these and read "". */
        public String asrFormat = "", mtFormat = "";
        public long totalBytes = 0;
        public File dir = null;
        /** True when this pack is one the ONNX engine can bind. */
        public boolean onnx() {
            return installed && asrFormat.startsWith("onnx")
                    && mtFormat.startsWith("onnx");
        }
    }

    private EnginePackStore() {}

    private static File engineRoot(Context ctx) {
        return new File(ctx.getFilesDir(), "engine");
    }

    /** The installed pack for a pair like "ja-en", or not-installed. */
    public static Status status(Context ctx, String pair) {
        Status s = new Status();
        if (pair == null) return s;
        File rec = new File(engineRoot(ctx), pair + ".json");
        if (!rec.isFile()) return s;
        try {
            String text = new String(readAll(rec), "UTF-8");
            JSONObject o = new JSONObject(text);
            s.pair = o.optString("pair", pair);
            s.version = o.optInt("version", 0);
            s.asrKind = o.optString("asrKind", "");
            s.mtKind = o.optString("mtKind", "");
            s.asrFormat = o.optString("asrFormat", "");
            s.mtFormat = o.optString("mtFormat", "");
            s.totalBytes = o.optLong("totalBytes", 0);
            s.dir = new File(engineRoot(ctx), pair);
            s.installed = s.dir.isDirectory()
                    && new File(s.dir, "asr").isDirectory()
                    && new File(s.dir, "mt").isDirectory();
        } catch (Exception ignored) {
            s.installed = false;
        }
        return s;
    }

    /** Any installed pair at all (the player asks this, pair-agnostic). */
    public static Status anyInstalled(Context ctx) {
        File root = engineRoot(ctx);
        File[] recs = root.listFiles((d, n) -> n.endsWith(".json"));
        if (recs != null) {
            for (File rec : recs) {
                String pair = rec.getName().replace(".json", "");
                Status s = status(ctx, pair);
                if (s.installed) return s;
            }
        }
        return new Status();
    }

    public interface Callback {
        void onResult(EnginePack.Result result);
    }

    /** Validate + install from a picked file, on a worker thread. */
    public static void install(Context ctx, Uri uri, Callback cb) {
        new Thread(() -> {
            EnginePack.Result r;
            File staging = new File(engineRoot(ctx), "_incoming");
            try {
                deleteTree(staging);
                staging.mkdirs();
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                r = EnginePack.validateAndExtract(in, staging);
                if (in != null) in.close();
            } catch (Exception e) {
                r = new EnginePack.Result();
                r.errors.add("the pack could not be opened (" + e.getMessage() + ")");
            }
            if (r.ok) {
                try {
                    File root = engineRoot(ctx);
                    File dest = new File(root, r.pair);
                    deleteTree(dest);
                    if (!staging.renameTo(dest)) {
                        r.errors.add("the pack validated but could not be "
                                + "moved into place");
                        r.ok = false;
                    } else {
                        JSONObject o = new JSONObject();
                        o.put("pair", r.pair);
                        o.put("version", r.version);
                        o.put("asrKind", r.asrKind);
                        o.put("mtKind", r.mtKind);
                        o.put("asrFormat", r.asrFormat);
                        o.put("mtFormat", r.mtFormat);
                        o.put("totalBytes", r.totalBytes);
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(
                                new File(root, r.pair + ".json"));
                        fos.write(o.toString().getBytes("UTF-8"));
                        fos.close();
                    }
                } catch (Exception e) {
                    r.errors.add("the pack validated but its install record "
                            + "could not be written (" + e.getMessage() + ")");
                    r.ok = false;
                }
            }
            if (!r.ok) deleteTree(staging);
            EnginePack.Result done = r;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onResult(done));
        }, "engine-pack-install").start();
    }

    static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
