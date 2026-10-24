package com.talacore.talatv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Whisper detokenization (v0.6.1): token id -> text.
 *
 * The pack carries asr/vocab.tsv (id \t token), exported at
 * pack-build time from the model's own tokenizer.json. Whisper's
 * tokens are byte-level BPE strings: each character in a token
 * string stands for one byte under the GPT-2 byte<->unicode map.
 * Decoding concatenates the token strings, maps every character
 * back to its byte, and reads the bytes as UTF-8. Special tokens
 * (id >= 50257: end-of-text, language and task markers, timestamps)
 * never render as text.
 */
public final class WhisperVocab {

    private final String[] tokens;
    private static final Map<Character, Integer> CHAR_TO_BYTE = buildCharToByte();

    public WhisperVocab(File vocabTsv) throws Exception {
        Map<Integer, String> byId = new HashMap<>();
        int max = -1;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(vocabTsv), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                int id = Integer.parseInt(line.substring(0, tab));
                byId.put(id, line.substring(tab + 1));
                if (id > max) max = id;
            }
        }
        tokens = new String[max + 1];
        for (Map.Entry<Integer, String> e : byId.entrySet()) {
            tokens[e.getKey()] = e.getValue();
        }
    }

    public String decode(int[] ids) {
        StringBuilder joined = new StringBuilder();
        for (int id : ids) {
            if (id < 0 || id >= 50257 || id >= tokens.length) continue;
            String t = tokens[id];
            if (t != null) joined.append(t);
        }
        byte[] bytes = new byte[joined.length()];
        int n = 0;
        for (int i = 0; i < joined.length(); i++) {
            Integer b = CHAR_TO_BYTE.get(joined.charAt(i));
            if (b != null) bytes[n++] = (byte) b.intValue();
        }
        return new String(bytes, 0, n, StandardCharsets.UTF_8);
    }

    /** The GPT-2 bytes_to_unicode table, inverted. */
    private static Map<Character, Integer> buildCharToByte() {
        Map<Character, Integer> map = new HashMap<>();
        java.util.List<Integer> bs = new java.util.ArrayList<>();
        for (int i = 33; i < 127; i++) bs.add(i);
        for (int i = 161; i < 173; i++) bs.add(i);
        for (int i = 174; i < 256; i++) bs.add(i);
        java.util.List<Integer> cs = new java.util.ArrayList<>(bs);
        int n = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        for (int i = 0; i < bs.size(); i++) {
            map.put((char) cs.get(i).intValue(), bs.get(i));
        }
        return map;
    }
}
