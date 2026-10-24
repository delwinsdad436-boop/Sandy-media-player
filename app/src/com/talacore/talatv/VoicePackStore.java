package com.talacore.talatv;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Where installed voice packs live (v0.13.0):
 *   filesDir/voices/<code>/model.onnx + tokens.txt   one per language
 *   filesDir/voices.json                             the index
 * Many packs — a shelf of voices, not a single organ — each
 * installed under the pack law: one streaming pass through
 * VoicePack's fail-closed validation into staging; only a fully
 * valid pack is promoted into place. A refused pack leaves
 * nothing behind, and a pack whose language is not in the voice
 * table (VoiceLangs) is refused at the door: Sandy does not keep
 * a voice she cannot name.
 */
public final class VoicePackStore {

    public static final class Installed {
        public String code = "", tvName = "", license = "", precision = "";
        public int version = 0;
        public long totalBytes = 0;
        public File dir = null;
    }

    private VoicePackStore() {}

    private static File root(Context ctx) {
        return new File(ctx.getFilesDir(), "voices");
    }

    private static File record(Context ctx) {
        return new File(ctx.getFilesDir(), "voices.json");
    }

    public static File packDir(Context ctx, String code) {
        return new File(root(ctx), code);
    }

    /** The installed shelf, keyed by pack code. */
    public static Map<String, Installed> installed(Context ctx) {
        Map<String, Installed> out = new LinkedHashMap<>();
        File rec = record(ctx);
        if (!rec.isFile()) return out;
        try {
            JSONObject o = new JSONObject(new String(
                    EnginePackStore.readAll(rec), "UTF-8"));
            JSONArray arr = o.optJSONArray("packs");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                Installed in = new Installed();
                in.code = p.optString("lang", "");
                in.tvName = p.optString("tvname", "");
                in.license = p.optString("license", "");
                in.precision = p.optString("precision", "");
                in.version = p.optInt("version", 0);
                in.totalBytes = p.optLong("totalBytes", 0);
                in.dir = packDir(ctx, in.code);
                if (!in.code.isEmpty()
                        && new File(in.dir, "model.onnx").isFile()
                        && new File(in.dir, "tokens.txt").isFile()) {
                    out.put(in.code, in);
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** The pack codes the resolver feeds on. */
    public static Set<String> installedCodes(Context ctx) {
        return new TreeSet<>(installed(ctx).keySet());
    }

    public static long totalBytes(Context ctx) {
        long t = 0;
        for (Installed in : installed(ctx).values()) t += in.totalBytes;
        return t;
    }

    public interface Callback {
        void onResult(VoicePack.Result result);
    }

    /** Validate + install from a picked file, on a worker thread. */
    public static void install(Context ctx, Uri uri, Callback cb) {
        new Thread(() -> {
            VoicePack.Result r;
            File staging = new File(ctx.getFilesDir(), "voice_incoming");
            try {
                EnginePackStore.deleteTree(staging);
                staging.mkdirs();
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                r = VoicePack.validateAndExtract(in, staging);
                if (in != null) in.close();
            } catch (Exception e) {
                r = new VoicePack.Result();
                r.errors.add("the pack could not be opened (" + e.getMessage() + ")");
            }
            if (r.ok) {
                // the door rule: the pack's language must be one
                // the voice table names with this very code
                VoiceLangs.Row row = VoiceLangs.byPackCode(
                        VoiceLangs.load(ctx), r.lang);
                if (row == null) {
                    r.errors.add("this pack's language (" + r.lang
                            + ") is not in Sandy's voice table — "
                            + "the pack is refused");
                    r.ok = false;
                }
            }
            if (r.ok) {
                try {
                    File dest = packDir(ctx, r.lang);
                    EnginePackStore.deleteTree(dest);
                    dest.getParentFile().mkdirs();
                    if (!staging.renameTo(dest)) {
                        r.errors.add("the pack validated but could not be "
                                + "moved into place");
                        r.ok = false;
                    } else {
                        writeRecord(ctx, r);
                    }
                } catch (Exception e) {
                    r.errors.add("the pack validated but its install record "
                            + "could not be written (" + e.getMessage() + ")");
                    r.ok = false;
                }
            }
            if (!r.ok) EnginePackStore.deleteTree(staging);
            VoicePack.Result done = r;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> cb.onResult(done));
        }, "voice-pack-install").start();
    }

    private static void writeRecord(Context ctx, VoicePack.Result r) throws Exception {
        Map<String, Installed> cur = installed(ctx);
        Installed in = new Installed();
        in.code = r.lang;
        in.tvName = r.tvName;
        in.license = r.license;
        in.precision = r.precision;
        in.version = r.version;
        in.totalBytes = r.totalBytes;
        cur.put(in.code, in);
        JSONArray arr = new JSONArray();
        for (Installed x : cur.values()) {
            JSONObject p = new JSONObject();
            p.put("lang", x.code);
            p.put("tvname", x.tvName);
            p.put("license", x.license);
            p.put("precision", x.precision);
            p.put("version", x.version);
            p.put("totalBytes", x.totalBytes);
            arr.put(p);
        }
        JSONObject o = new JSONObject();
        o.put("packs", arr);
        FileOutputStream fos = new FileOutputStream(record(ctx));
        fos.write(o.toString().getBytes("UTF-8"));
        fos.close();
    }
}
