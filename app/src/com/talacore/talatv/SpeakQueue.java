package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * SPEAK FOR ME's queue (v0.15.0) — the words a person hands Sandy
 * to say out loud, in order. Pure model, no Android: the door
 * (SpeakActivity) and the Translator both fill it; VoiceSpeaker
 * drains it through the voice ladder.
 *
 * The ladder decision lives here as data, delegating to
 * VoiceResolve (v0.13.0): PACK (an installed Sandy voice pack) →
 * SYSTEM (the phone's own voice) → NONE. NONE is a refusal the
 * caller must speak BEFORE anything is promised — never a silent
 * wrong-language or glitchy substitute (the Max Headroom floor).
 *
 * Long texts are cut into speakable blocks with the house cutter
 * (BookTranslator.blocks, ~480 chars on sentence ends) so a whole
 * typed paragraph or a document chapter is a run of utterances,
 * not one impossible breath.
 */
public final class SpeakQueue {

    public static final class Item {
        public final String text;
        public final String iso1;
        public Item(String text, String iso1) {
            this.text = text == null ? "" : text;
            this.iso1 = iso1 == null ? "" : iso1;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int cursor = 0;
    /** The last item actually handed to a voice (the receipt). */
    private Item lastSpoken;

    /** Enqueue one message, cut into blocks when it runs long. */
    public void enqueue(String text, String iso1) {
        if (text == null) return;
        String t = text.trim();
        if (t.isEmpty()) return;
        for (String block : BookTranslator.blocks(t)) {
            String b = block.trim();
            if (!b.isEmpty()) items.add(new Item(b, iso1));
        }
    }

    /** Enqueue document text: many blocks, one language. */
    public void enqueueDocument(String text, String iso1) {
        enqueue(text, iso1);
    }

    public int size() { return items.size(); }

    public boolean isEmpty() { return cursor >= items.size(); }

    /** The next item to speak, or null when the queue is drained. */
    public Item peek() {
        return isEmpty() ? null : items.get(cursor);
    }

    /** Mark the peeked item spoken and move on. */
    public Item advance() {
        Item it = peek();
        if (it != null) {
            lastSpoken = it;
            cursor++;
        }
        return it;
    }

    /** The receipt: the last words Sandy actually said. */
    public Item lastSpoken() { return lastSpoken; }

    /** Repeat = speak the last thing again (re-queued at the head). */
    public boolean repeatLast() {
        if (lastSpoken == null) return false;
        items.add(cursor, lastSpoken);
        return true;
    }

    public void clear() {
        items.clear();
        cursor = 0;
    }

    /** Every item still unspoken, in order (the harness reads this). */
    public List<Item> pending() {
        List<Item> out = new ArrayList<>();
        for (int i = cursor; i < items.size(); i++) out.add(items.get(i));
        return out;
    }

    /**
     * The ladder decision for one language, made from live sets:
     * installed pack codes and the phone-voice ISO set, both probed
     * by the caller. NONE means refuse — the door says so before
     * promising a sound.
     */
    public static VoiceResolve.Result decide(VoiceLangs.Row row,
                                             Set<String> installedPackCodes,
                                             Set<String> systemIso1) {
        return VoiceResolve.resolve(row, installedPackCodes, systemIso1);
    }

    public static boolean canSpeak(VoiceLangs.Row row,
                                   Set<String> installedPackCodes,
                                   Set<String> systemIso1) {
        return decide(row, installedPackCodes, systemIso1).voice
                != VoiceResolve.Voice.NONE;
    }
}
