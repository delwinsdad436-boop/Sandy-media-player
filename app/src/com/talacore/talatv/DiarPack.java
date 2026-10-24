package com.talacore.talatv;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The diarization (voice) pack slot (v0.12.0) — James's executor
 * rule made literal: the organ that knows WHO is speaking is a
 * component Sandy carries, adopted whole from the proven open
 * models (pyannote segmentation-3.0 + a 3D-Speaker embedding
 * model), never rebuilt and never simulated. When no pack is in,
 * the dub plans single-voice and says so.
 *
 * Same discipline as the engine pack (EnginePack): a ZIP whose
 * first file is pack.txt —
 *
 *   sandy.diarpack=1
 *   version=1
 *   seg.kind=pyannote-segmentation-3.0
 *   emb.kind=eres2net-3dspeaker
 *   cluster.threshold=0.50
 *   voicebook.match=0.55
 *   voicebook.near=0.42
 *   file seg/model.onnx <size> <sha256>
 *   file emb/model.onnx <size> <sha256>
 *
 * Fail-closed with integrity: every carried file listed, every
 * listed file present at its recorded size and SHA-256, both
 * models present, the calibration numbers sane (near < match,
 * all inside (0,1)). A failed validation extracts nothing that
 * stays. The calibration rides the pack because embedding cosine
 * scales are model properties, not universal constants — the
 * numbers in the shipped pack were measured on the diarization
 * fixture in work/v120/diar and are stated in the NOTES.
 */
public final class DiarPack {

    public static final String MANIFEST = "pack.txt";

    public static final class Result {
        public boolean ok = false;
        public int version = 0;
        public String segKind = "", embKind = "";
        public double clusterThreshold = 0.5;
        public double vbMatch = VoiceBook.MATCH_MIN;
        public double vbNear = VoiceBook.NEAR_MIN;
        public long totalBytes = 0;
        public final List<String> errors = new ArrayList<>();
        public final Map<String, long[]> files = new LinkedHashMap<>();
    }

    private DiarPack() {}

    private static final class Listed {
        long size; String sha;
    }

    /** Same contract as EnginePack.validateAndExtract. */
    public static Result validateAndExtract(InputStream in, File destDir) {
        Result r = new Result();
        Map<String, Listed> listed = new LinkedHashMap<>();
        Map<String, Boolean> seen = new HashMap<>();
        boolean manifestSeen = false;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            ZipInputStream zin = new ZipInputStream(in);
            List<File> written = new ArrayList<>();
            ZipEntry e;
            boolean first = true;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (first && !MANIFEST.equals(name)) {
                    r.errors.add("the first file in the pack must be "
                            + MANIFEST + " (the manifest), found: " + name);
                    break;
                }
                first = false;
                if (MANIFEST.equals(name)) {
                    manifestSeen = true;
                    byte[] text = readEntry(zin);
                    parseManifest(new String(text, "UTF-8"), r, listed);
                    if (!r.errors.isEmpty()) break;
                    continue;
                }
                Listed lf = listed.get(name);
                if (lf == null) {
                    r.errors.add("the pack carries a file its manifest "
                            + "does not list: " + name);
                    break;
                }
                seen.put(name, Boolean.TRUE);
                sha.reset();
                long size = 0;
                File out = null;
                FileOutputStream fos = null;
                if (destDir != null) {
                    out = new File(destDir, name);
                    if (!out.getCanonicalPath().startsWith(
                            destDir.getCanonicalPath() + File.separator)) {
                        r.errors.add("a listed path escapes the pack "
                                + "folder: " + name);
                        break;
                    }
                    if (out.getParentFile() != null) out.getParentFile().mkdirs();
                    fos = new FileOutputStream(out);
                    written.add(out);
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = zin.read(buf)) > 0) {
                    sha.update(buf, 0, n);
                    size += n;
                    if (fos != null) fos.write(buf, 0, n);
                }
                if (fos != null) fos.close();
                if (size != lf.size) {
                    r.errors.add(name + ": size is " + size
                            + " but the manifest says " + lf.size);
                    break;
                }
                String digest = hex(sha.digest());
                if (!digest.equalsIgnoreCase(lf.sha)) {
                    r.errors.add(name + ": its SHA-256 does not match "
                            + "the manifest — the file is not what the "
                            + "pack says it is");
                    break;
                }
                r.totalBytes += size;
            }
            zin.close();
            if (r.errors.isEmpty()) {
                if (!manifestSeen) {
                    r.errors.add("the pack has no " + MANIFEST + " manifest.");
                } else {
                    for (String path : listed.keySet()) {
                        if (!seen.containsKey(path)) {
                            r.errors.add("the manifest lists " + path
                                    + " but the pack does not carry it");
                        }
                    }
                }
            }
            if (!r.errors.isEmpty() && destDir != null) {
                for (File f : written) f.delete();
            }
        } catch (Exception ex) {
            r.errors.add("the pack could not be read (" + ex.getMessage() + ")");
        }
        r.ok = r.errors.isEmpty();
        return r;
    }

    private static void parseManifest(String text, Result r, Map<String, Listed> listed) {
        Map<String, String> kv = new HashMap<>();
        List<String> fileLines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("file ")) { fileLines.add(line); continue; }
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        if (!"1".equals(kv.get("sandy.diarpack"))) {
            r.errors.add("not a Sandy voice pack (missing the "
                    + "sandy.diarpack=1 header)");
            return;
        }
        try {
            r.version = Integer.parseInt(kv.getOrDefault("version", "0"));
        } catch (Exception e) {
            r.version = 0;
        }
        if (r.version < 1) {
            r.errors.add("the pack's version is missing or not a number");
            return;
        }
        r.segKind = kv.getOrDefault("seg.kind", "");
        r.embKind = kv.getOrDefault("emb.kind", "");
        if (r.segKind.isEmpty() || r.embKind.isEmpty()) {
            r.errors.add("the pack must name both its segmentation "
                    + "model (seg.kind) and its speaker model (emb.kind)");
            return;
        }
        r.clusterThreshold = parseUnit(kv.get("cluster.threshold"), 0.5, r);
        r.vbMatch = parseUnit(kv.get("voicebook.match"), VoiceBook.MATCH_MIN, r);
        r.vbNear = parseUnit(kv.get("voicebook.near"), VoiceBook.NEAR_MIN, r);
        if (!r.errors.isEmpty()) return;
        if (!(r.vbNear < r.vbMatch)) {
            r.errors.add("the pack's voicebook calibration is impossible "
                    + "(near must be below match)");
            return;
        }
        for (String line : fileLines) {
            String[] p = line.split("\\s+");
            if (p.length != 4) {
                r.errors.add("a manifest file line is malformed: " + line);
                return;
            }
            Listed lf = new Listed();
            try {
                lf.size = Long.parseLong(p[2]);
            } catch (Exception e) {
                r.errors.add("a manifest file line has a bad size: " + line);
                return;
            }
            lf.sha = p[3];
            if (!lf.sha.matches("[0-9a-fA-F]{64}") || lf.size < 0) {
                r.errors.add("a manifest file line has a bad SHA-256 "
                        + "or size: " + line);
                return;
            }
            listed.put(p[1], lf);
            r.files.put(p[1], new long[]{lf.size});
        }
        if (listed.isEmpty()) {
            r.errors.add("the manifest lists no files at all");
        } else if (!listed.containsKey("seg/model.onnx")) {
            r.errors.add("the manifest lists no segmentation model "
                    + "(seg/model.onnx)");
        } else if (!listed.containsKey("emb/model.onnx")) {
            r.errors.add("the manifest lists no speaker model "
                    + "(emb/model.onnx)");
        }
    }

    private static double parseUnit(String v, double dflt, Result r) {
        if (v == null || v.isEmpty()) return dflt;
        try {
            double d = Double.parseDouble(v);
            if (d > 0.0 && d < 1.0) return d;
        } catch (Exception ignored) {}
        r.errors.add("a calibration value is not a number inside (0,1): " + v);
        return dflt;
    }

    private static byte[] readEntry(ZipInputStream zin) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
