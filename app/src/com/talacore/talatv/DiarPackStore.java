package com.talacore.talatv;

import android.content.Context;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Where an installed diarization pack lives (v0.12.0):
 *   filesDir/diarize/seg/model.onnx + emb/model.onnx
 *   filesDir/diarize.json           the install record (with the
 *                                   pack's measured calibration)
 * One pack — the organ is singular, like the ear. Install follows
 * the engine pack's law: one streaming pass through DiarPack's
 * fail-closed validation into staging; only a fully valid pack is
 * promoted into place. A refused pack leaves nothing behind.
 */
public final class DiarPackStore {

    public static final class Status {
        public boolean installed = false;
        public int version = 0;
        public String segKind = "", embKind = "";
        public double clusterThreshold = 0.5;
        public double vbMatch = VoiceBook.MATCH_MIN;
        public double vbNear = VoiceBook.NEAR_MIN;
        public long totalBytes = 0;
        public File dir = null;
    }

    private DiarPackStore() {}

    private static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "diarize");
    }

    private static File record(Context ctx) {
        return new File(ctx.getFilesDir(), "diarize.json");
    }

    public static Status status(Context ctx) {
        Status s = new Status();
        File rec = record(ctx);
        if (!rec.isFile()) return s;
        try {
            JSONObject o = new JSONObject(new String(
                    EnginePackStore.readAll(rec), "UTF-8"));
            s.version = o.optInt("version", 0);
            s.segKind = o.optString("segKind", "");
            s.embKind = o.optString("embKind", "");
            s.clusterThreshold = o.optDouble("clusterThreshold", 0.5);
            s.vbMatch = o.optDouble("vbMatch", VoiceBook.MATCH_MIN);
            s.vbNear = o.optDouble("vbNear", VoiceBook.NEAR_MIN);
            s.totalBytes = o.optLong("totalBytes", 0);
            s.dir = root(ctx);
            s.installed = new File(new File(s.dir, "seg"), "model.onnx").isFile()
                    && new File(new File(s.dir, "emb"), "model.onnx").isFile();
        } catch (Exception ignored) {
            s.installed = false;
        }
        return s;
    }

    public interface Callback {
        void onResult(DiarPack.Result result);
    }

    /** Validate + install from a picked file, on a worker thread. */
    public static void install(Context ctx, Uri uri, Callback cb) {
        new Thread(() -> {
            DiarPack.Result r;
            File staging = new File(ctx.getFilesDir(), "diarize_incoming");
            try {
                EnginePackStore.deleteTree(staging);
                staging.mkdirs();
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                r = DiarPack.validateAndExtract(in, staging);
                if (in != null) in.close();
            } catch (Exception e) {
                r = new DiarPack.Result();
                r.errors.add("the pack could not be opened (" + e.getMessage() + ")");
            }
            if (r.ok) {
                try {
                    File dest = root(ctx);
                    EnginePackStore.deleteTree(dest);
                    if (!staging.renameTo(dest)) {
                        r.errors.add("the pack validated but could not be "
                                + "moved into place");
                        r.ok = false;
                    } else {
                        JSONObject o = new JSONObject();
                        o.put("version", r.version);
                        o.put("segKind", r.segKind);
                        o.put("embKind", r.embKind);
                        o.put("clusterThreshold", r.clusterThreshold);
                        o.put("vbMatch", r.vbMatch);
                        o.put("vbNear", r.vbNear);
                        o.put("totalBytes", r.totalBytes);
                        FileOutputStream fos = new FileOutputStream(record(ctx));
                        fos.write(o.toString().getBytes("UTF-8"));
                        fos.close();
                    }
                } catch (Exception e) {
                    r.errors.add("the pack validated but its install record "
                            + "could not be written (" + e.getMessage() + ")");
                    r.ok = false;
                }
            }
            if (!r.ok) EnginePackStore.deleteTree(staging);
            DiarPack.Result done = r;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onResult(done));
        }, "diar-pack-install").start();
    }
}
