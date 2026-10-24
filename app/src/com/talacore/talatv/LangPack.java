package com.talacore.talatv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The language-pack slot (v0.5.0; James's pack architecture: "we make
 * those for the larger languages... any sub languages below the main
 * grouping would just have to come in and we'd write 'em — or somebody
 * could put Sandy in their AI and have it written for them").
 *
 * A pack is ONE plain-text file, documented in the NOTES:
 *
 *   # comments start with #
 *   sandy.langpack=1
 *   code=tl
 *   name=Tagalog
 *   home_catalog=Katalogo
 *   ... one line for EVERY key in UiStrings.KEYS ...
 *
 * Validation is fail-closed, exactly as ordered: the header must be
 * present, the code and name must be sane, and EVERY required key
 * must be present with a non-empty value. An incomplete pack is
 * rejected with the missing keys named — never half-installed.
 *
 * Pure logic (no Android imports); the harness drives it with
 * complete, incomplete, and malformed fixtures.
 */
public final class LangPack {

    public static final class Result {
        public boolean ok = false;
        public String code = "";
        public String name = "";
        public final Map<String, String> strings = new HashMap<>();
        public final List<String> missing = new ArrayList<>();
        public String error = "";
    }

    private LangPack() {}

    public static Result parse(String text) {
        Result r = new Result();
        if (text == null) {
            r.error = "The file was empty.";
            return r;
        }
        Map<String, String> kv = new HashMap<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        if (!"1".equals(kv.get("sandy.langpack"))) {
            r.error = "Not a Sandy language pack (missing the sandy.langpack=1 header).";
            return r;
        }
        String code = kv.getOrDefault("code", "");
        if (!code.matches("[a-z]{2,3}(-[A-Za-z0-9]{2,8})?")) {
            r.error = "The pack's language code (\"code=\") is missing or not a "
                    + "short language code like tl or pt-BR.";
            return r;
        }
        String name = kv.getOrDefault("name", "");
        if (name.isEmpty()) {
            r.error = "The pack has no display name (\"name=\").";
            return r;
        }
        r.code = code;
        r.name = name;
        for (String key : UiStrings.KEYS) {
            String v = kv.get(key);
            if (v == null || v.isEmpty()) r.missing.add(key);
            else r.strings.put(key, v);
        }
        if (!r.missing.isEmpty()) {
            r.error = "The pack is incomplete — missing " + r.missing.size()
                    + " of " + UiStrings.KEYS.length + " required lines.";
            return r;
        }
        r.ok = true;
        return r;
    }

    /** Serialize a complete pack (used for the sample pack in NOTES/tests). */
    public static String write(String code, String name, Map<String, String> strings) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy language pack\n");
        sb.append("sandy.langpack=1\n");
        sb.append("code=").append(code).append('\n');
        sb.append("name=").append(name).append('\n');
        for (String key : UiStrings.KEYS) {
            sb.append(key).append('=').append(strings.getOrDefault(key, "")).append('\n');
        }
        return sb.toString();
    }
}
