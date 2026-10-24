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
 * A Sandy voice pack (v0.13.0) — ONE language's voice, a file in
 * and a file out, under the same law as the engine and diarization
 * packs: a ZIP whose first file is pack.txt —
 *
 *   sandy.voicepack=1
 *   version=1
 *   lang=rus                      (the MMS model code, ISO 639-3)
 *   tvname=Russian                (the catalogue's name)
 *   engine=mms-tts
 *   license=CC-BY-NC-4.0
 *   source=facebook/mms-tts models/rus
 *   precision=int8
 *   sample.rate=16000
 *   file model.onnx <size> <sha256>
 *   file tokens.txt <size> <sha256>
 *
 * The voice inside is Meta's MMS-TTS (a VITS model trained on the
 * language's own script), quantized to int8 with calibration from
 * the language's own text. The license rides the manifest because
 * it is a fact of the model, stated wherever the pack is described.
 *
 * Fail-closed with integrity, exactly like DiarPack: every carried
 * file listed, every listed file present at its recorded size and
 * SHA-256, the language sane, both files aboard. A failed
 * validation extracts nothing that stays.
 */
public final class VoicePack {

    public static final String MANIFEST = "pack.txt";

    public static final class Result {
        public boolean ok = false;
        public int version = 0;
        public String lang = "", tvName = "", engine = "", license = "";
        public String source = "", precision = "";
        public int sampleRate = 0;
        public long totalBytes = 0;
        public final List<String> errors = new ArrayList<>();
        public final Map<String, long[]> files = new LinkedHashMap<>();
    }

    private VoicePack() {}

    private static final class Listed {
        long size; String sha;
    }

    /** Same contract as DiarPack.validateAndExtract. */
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
        if (!"1".equals(kv.get("sandy.voicepack"))) {
            r.errors.add("not a Sandy voice pack (missing the "
                    + "sandy.voicepack=1 header)");
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
        r.lang = kv.getOrDefault("lang", "");
        if (!r.lang.matches("[a-z]{3}")) {
            r.errors.add("the pack's language is missing or not a "
                    + "three-letter model code: " + r.lang);
            return;
        }
        r.tvName = kv.getOrDefault("tvname", "");
        r.engine = kv.getOrDefault("engine", "");
        if (!"mms-tts".equals(r.engine)) {
            r.errors.add("the pack's engine is not one Sandy can "
                    + "speak (engine must be mms-tts)");
            return;
        }
        r.license = kv.getOrDefault("license", "");
        if (r.license.isEmpty()) {
            r.errors.add("the pack does not name its model's license");
            return;
        }
        r.source = kv.getOrDefault("source", "");
        r.precision = kv.getOrDefault("precision", "");
        try {
            r.sampleRate = Integer.parseInt(kv.getOrDefault("sample.rate", "0"));
        } catch (Exception e) {
            r.sampleRate = 0;
        }
        if (r.sampleRate != 16000) {
            r.errors.add("the pack's sample rate is not the MMS "
                    + "rate (16000): " + r.sampleRate);
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
        if (!listed.containsKey("model.onnx")) {
            r.errors.add("the manifest lists no voice model (model.onnx)");
        } else if (!listed.containsKey("tokens.txt")) {
            r.errors.add("the manifest lists no token table (tokens.txt)");
        }
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
