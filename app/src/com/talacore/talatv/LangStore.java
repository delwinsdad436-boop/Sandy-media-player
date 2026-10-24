package com.talacore.talatv;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Installed language packs + the one string lookup every screen uses
 * (v0.5.0). Resolution order for a key: the current language's
 * installed pack (if a holder installed one — their pack wins, it's
 * their shelf) -> the baked table for that language -> English.
 *
 * Packs live in filesDir/langpacks/<code>.pack, imported through
 * Settings and validated fail-closed by LangPack before they land.
 */
public final class LangStore {

    private LangStore() {}

    /** The string lookup: S.tr(ctx, "live_title"). */
    public static String tr(Context ctx, String key) {
        String code = ChannelStore.getUiLanguage(ctx);
        if (code != null && !code.isEmpty() && !"en".equals(code)) {
            LangPack.Result pack = loadPack(ctx, code);
            if (pack != null && pack.ok) {
                String v = pack.strings.get(key);
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return UiStrings.lookup(code == null || code.isEmpty() ? "en" : code, key);
    }

    private static File packDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "langpacks");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File packFile(Context ctx, String code) {
        return new File(packDir(ctx), code + ".pack");
    }

    /** Load + validate an installed pack; null when absent or invalid. */
    public static LangPack.Result loadPack(Context ctx, String code) {
        File f = packFile(ctx, code);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            LangPack.Result r = LangPack.parse(new String(buf, 0, off, StandardCharsets.UTF_8));
            return r.ok && r.code.equals(code) ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Install a validated pack. Only called with parse-ok results. */
    public static boolean install(Context ctx, LangPack.Result pack, String text) {
        try (java.io.FileOutputStream out =
                     new java.io.FileOutputStream(packFile(ctx, pack.code))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** code -> display name for every installed (valid) pack. */
    public static List<String[]> installedPacks(Context ctx) {
        List<String[]> out = new ArrayList<>();
        File[] files = packDir(ctx).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".pack")) continue;
            String code = f.getName().substring(0, f.getName().length() - 5);
            LangPack.Result r = loadPack(ctx, code);
            if (r != null) out.add(new String[]{r.code, r.name});
        }
        return out;
    }

    /** Picker rows: the baked majors first, then installed packs. */
    public static List<String[]> pickerLanguages(Context ctx) {
        List<String[]> out = new ArrayList<>();
        for (String[] l : UiStrings.LANGUAGES) out.add(l);
        for (String[] p : installedPacks(ctx)) {
            boolean baked = false;
            for (String[] l : UiStrings.LANGUAGES) {
                if (l[0].equals(p[0])) { baked = true; break; }
            }
            if (!baked) out.add(p);
        }
        return out;
    }

    /** Display name for a language code (picker/Settings rows). */
    public static String displayName(Context ctx, String code) {
        if (code == null || code.isEmpty()) return "English";
        for (String[] l : pickerLanguages(ctx)) {
            if (l[0].equals(code)) return l[1];
        }
        return code;
    }
}
