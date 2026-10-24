package com.talacore.talatv;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Fetching a curated tale (v0.14.0) — through the Internet
 * Archive's own front door, the way everything in the house is
 * fetched. A curation row resolves: its archive id first; if
 * that door doesn't answer (or carries no readable text), a
 * search under the row's own terms, best public item with a
 * readable text. The text, once fetched, is a FILE on the
 * memory hook (StoryFiles) — fetched once, kept, re-read from
 * the shelf without the network.
 *
 * Illustrations: the edition's own pictures where the Archive
 * exposes them — loose image files in the item's own listing.
 * Never scraped, never invented; when there are none, the
 * storyteller's picture cards carry the telling (StoryPanels).
 *
 * Blocking: call on a worker thread.
 */
public final class StoryFetch {

    private StoryFetch() {}

    /** The item a row resolved to ("" when unresolved). */
    public static String resolveItemId(StoryData.Row row) throws Exception {
        if (row.iaId != null && !row.iaId.isEmpty()) {
            try {
                List<IaClient.FileEntry> files = IaClient.parseMetadata(
                        Net.getString(IaClient.metadataUrl(row.iaId)), row.iaId);
                if (pickText(files) != null) return row.iaId;
            } catch (Exception ignored) {}
        }
        String terms = row.search != null && !row.search.isEmpty()
                ? row.search : row.title;
        List<IaClient.Item> items = IaClient.parseSearch(
                Net.getString(IaClient.searchUrl(terms)));
        // prefer items whose language the Archive itself declares
        // to be the row's tongue; untagged items keep their rank.
        List<IaClient.Item> ranked = IaClient.rankItems(
                items, terms, "", 0, 0, 0);
        String marc = IaClient.iaLanguageCode(
                tvNameForIso1(row.lang));
        if (!marc.isEmpty()) ranked = IaClient.preferLanguage(ranked, marc);
        for (IaClient.Item it : ranked) {
            try {
                List<IaClient.FileEntry> files = IaClient.parseMetadata(
                        Net.getString(IaClient.metadataUrl(it.identifier)),
                        it.identifier);
                if (pickText(files) != null) return it.identifier;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static String tvNameForIso1(String iso1) {
        // IaClient.iaLanguageCode speaks catalogue names; the
        // common children's-shelf tongues map directly.
        switch (iso1 == null ? "" : iso1) {
            case "en": return "English";
            case "es": return "Spanish";
            case "de": return "German";
            case "fr": return "French";
            case "it": return "Italian";
            case "pt": return "Portuguese";
            case "ru": return "Russian";
            default: return "";
        }
    }

    /** The readable text file in an item, or null. Plain text
     *  first (the djvu text is the book itself), then an EPUB the
     *  house's own BookText can open. */
    public static IaClient.FileEntry pickText(List<IaClient.FileEntry> files) {
        IaClient.FileEntry djvu = null, plain = null, epub = null;
        for (IaClient.FileEntry f : files) {
            String n = f.name.toLowerCase(java.util.Locale.US);
            if (f.subtitle) continue;
            if (n.endsWith("_djvu.txt")) djvu = f;
            else if (n.endsWith(".txt") && !n.contains("scandata")
                    && plain == null) plain = f;
            else if (n.endsWith(".epub") && epub == null) epub = f;
        }
        return djvu != null ? djvu : (plain != null ? plain : epub);
    }

    /**
     * Fetch (or recall) the row's text. Returns the text; saves
     * it to the shelf file on the way. Throws on no readable
     * text anywhere the row points.
     */
    public static String fetchText(Context ctx, StoryData.Row row)
            throws Exception {
        if (StoryFiles.bookHeld(ctx, row.id)) {
            return StoryFiles.bookText(ctx, row.id);
        }
        if (row.directUrl != null && !row.directUrl.isEmpty()) {
            // A BookSource row that reads from a direct public
            // file (Project Gutenberg): one hole — the same
            // Hydra fetch, the same BookText intake.
            byte[] data = Net.getBytes(row.directUrl);
            if (data == null || data.length == 0) {
                throw new Exception("the text didn't arrive");
            }
            BookText.Result r = BookText.extract(data, row.directUrl);
            if (!r.ok || r.text == null || r.text.trim().length() < 200) {
                throw new Exception("the book couldn't be opened");
            }
            StoryFiles.saveBook(ctx, row, r.text);
            return r.text;
        }
        String itemId = resolveItemId(row);
        if (itemId.isEmpty()) throw new Exception("no readable item");
        List<IaClient.FileEntry> files = IaClient.parseMetadata(
                Net.getString(IaClient.metadataUrl(itemId)), itemId);
        IaClient.FileEntry text = pickText(files);
        if (text == null) throw new Exception("item carries no text");
        byte[] data = Net.getBytes(text.url);
        if (data == null || data.length == 0) {
            throw new Exception("the text didn't arrive");
        }
        String body;
        if (text.name.toLowerCase(java.util.Locale.US).endsWith(".epub")) {
            BookText.Result r = BookText.extract(data, text.name);
            if (!r.ok) throw new Exception("the book couldn't be opened");
            body = r.text;
        } else {
            body = BookText.decodeText(data);
        }
        if (body == null || body.trim().length() < 200) {
            throw new Exception("the text was empty");
        }
        StoryFiles.saveBook(ctx, row, body);
        return body;
    }

    /** The edition's own illustration files, in shelf order. */
    public static List<String> illustrations(Context ctx, StoryData.Row row) {
        List<String> out = new ArrayList<>();
        try {
            String itemId = row.iaId;
            if (itemId == null || itemId.isEmpty() || !StoryFiles.bookHeld(ctx, row.id)) {
                itemId = resolveItemId(row);
            }
            if (itemId == null || itemId.isEmpty()) return out;
            List<IaClient.FileEntry> files = IaClient.parseMetadata(
                    Net.getString(IaClient.metadataUrl(itemId)), itemId);
            List<IaClient.FileEntry> imgs = new ArrayList<>();
            for (IaClient.FileEntry f : files) {
                String n = f.name.toLowerCase(java.util.Locale.US);
                if (f.video || f.subtitle) continue;
                if (!(n.endsWith(".jpg") || n.endsWith(".jpeg")
                        || n.endsWith(".png"))) continue;
                if (n.contains("scandata") || n.contains("thumb")
                        || n.contains("__ia_thumb")) continue;
                imgs.add(f);
            }
            imgs.sort((a, b) -> a.name.compareTo(b.name));
            for (IaClient.FileEntry f : imgs) out.add(f.url);
        } catch (Exception ignored) {}
        return out;
    }
}
