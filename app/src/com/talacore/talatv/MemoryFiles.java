package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * The memory hook's file formats (v0.11.0) — pure text shapes for
 * the files behind the ONE hook (MemoryHook). James's rule: file in,
 * file out. Everything Sandy keeps that a person might want to see,
 * move, or back up is a file in one place, in formats readable with
 * no tool but eyes:
 *
 *   kept_places.tsv — where she left off, everywhere:
 *     kind TAB key TAB position TAB updated-ms TAB extra
 *     kind "pos"  : on-demand/local resume points (key = content key,
 *                   position = ms)
 *     kind "live" : the kept live place (key = "live", position = ms,
 *                   extra = the kept-place JSON, carried opaquely)
 *     kind "book" : a book's place (key = book id, position = chunk,
 *                   extra = the bookmark JSON, carried opaquely)
 *     kind "#seeded" : migration markers (key = the seeded kind)
 *   recordings.tsv — recordings metadata:
 *     id TAB title TAB channel TAB aired-start TAB aired-stop
 *        TAB origin TAB state
 *     (the bytes and the download bookkeeping stay machinery —
 *     media3's own stores; this file is the shelf's memory of what
 *     a recording IS)
 *   timers.tsv — see TimerModel.
 *   guide/*.tsv — see GuideStore.
 *
 * Free-text fields are URL-encoded (TimerModel.enc/dec), so a title
 * with a tab or a newline in it can never break a row.
 */
public final class MemoryFiles {

    private MemoryFiles() {}

    // ------------------------------------------------- kept places

    public static final class KeptPlace {
        public String kind = "";
        public String key = "";
        public long position = 0;
        public long updatedMs = 0;
        public String extra = "";

        public KeptPlace() {}
        public KeptPlace(String kind, String key, long position,
                         long updatedMs, String extra) {
            this.kind = kind; this.key = key; this.position = position;
            this.updatedMs = updatedMs; this.extra = extra == null ? "" : extra;
        }
    }

    public static String keptPlacesToText(List<KeptPlace> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy kept places - kind, key, position, updated, extra.\n");
        sb.append("# Behind the memory hook (files/memory/kept_places.tsv).\n");
        for (KeptPlace r : rows) {
            sb.append(TimerModel.enc(r.kind)).append('\t')
              .append(TimerModel.enc(r.key)).append('\t')
              .append(r.position).append('\t')
              .append(r.updatedMs).append('\t')
              .append(TimerModel.enc(r.extra)).append('\n');
        }
        return sb.toString();
    }

    public static List<KeptPlace> keptPlacesFromText(String content) {
        List<KeptPlace> out = new ArrayList<>();
        if (content == null) return out;
        for (String line : content.split("\n")) {
            // No trim before the split: the last field may be an
            // empty encoded value whose separator must survive.
            String probe = line.trim();
            if (probe.isEmpty() || probe.startsWith("#")) continue;
            String s = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
            String[] c = s.split("\t", -1);
            if (c.length < 5) continue;
            try {
                KeptPlace r = new KeptPlace();
                r.kind = TimerModel.dec(c[0]);
                r.key = TimerModel.dec(c[1]);
                r.position = Long.parseLong(c[2]);
                r.updatedMs = Long.parseLong(c[3]);
                r.extra = TimerModel.dec(c[4]);
                if (!r.kind.isEmpty()) out.add(r);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** The row for (kind,key), or null. */
    public static KeptPlace findKept(List<KeptPlace> rows, String kind, String key) {
        for (KeptPlace r : rows) {
            if (r.kind.equals(kind) && r.key.equals(key)) return r;
        }
        return null;
    }

    /** Insert or replace the (kind,key) row; position<=0 removes a "pos" row. */
    public static List<KeptPlace> upsertKept(List<KeptPlace> rows, KeptPlace row) {
        List<KeptPlace> out = new ArrayList<>();
        for (KeptPlace r : rows) {
            if (!(r.kind.equals(row.kind) && r.key.equals(row.key))) out.add(r);
        }
        if (!("pos".equals(row.kind) && row.position <= 0)) out.add(row);
        return out;
    }

    // ------------------------------------------------- recordings

    public static final class RecordingMeta {
        public String id = "";        // the download's request-data id (url)
        public String title = "";
        public String channel = "";
        public long airedStartMs = 0;
        public long airedStopMs = 0;
        public String origin = "";    // "now" or "timer"
        public String state = "";     // RECORDED / PARTIAL_LATE / ... or ""
    }

    public static String recordingsToText(List<RecordingMeta> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sandy recordings metadata - id, title, channel,\n");
        sb.append("# aired start/stop, origin, state. Behind the memory hook.\n");
        for (RecordingMeta r : rows) {
            sb.append(TimerModel.enc(r.id)).append('\t')
              .append(TimerModel.enc(r.title)).append('\t')
              .append(TimerModel.enc(r.channel)).append('\t')
              .append(r.airedStartMs).append('\t')
              .append(r.airedStopMs).append('\t')
              .append(TimerModel.enc(r.origin)).append('\t')
              .append(TimerModel.enc(r.state)).append('\n');
        }
        return sb.toString();
    }

    public static List<RecordingMeta> recordingsFromText(String content) {
        List<RecordingMeta> out = new ArrayList<>();
        if (content == null) return out;
        for (String line : content.split("\n")) {
            String probe = line.trim();
            if (probe.isEmpty() || probe.startsWith("#")) continue;
            String s = line.endsWith("\r")
                    ? line.substring(0, line.length() - 1) : line;
            String[] c = s.split("\t", -1);
            if (c.length < 7) continue;
            try {
                RecordingMeta r = new RecordingMeta();
                r.id = TimerModel.dec(c[0]);
                r.title = TimerModel.dec(c[1]);
                r.channel = TimerModel.dec(c[2]);
                r.airedStartMs = Long.parseLong(c[3]);
                r.airedStopMs = Long.parseLong(c[4]);
                r.origin = TimerModel.dec(c[5]);
                r.state = TimerModel.dec(c[6]);
                if (!r.id.isEmpty()) out.add(r);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static RecordingMeta findRecording(List<RecordingMeta> rows, String id) {
        for (RecordingMeta r : rows) if (r.id.equals(id)) return r;
        return null;
    }

    public static List<RecordingMeta> upsertRecording(List<RecordingMeta> rows,
                                                      RecordingMeta row) {
        List<RecordingMeta> out = new ArrayList<>();
        for (RecordingMeta r : rows) if (!r.id.equals(row.id)) out.add(r);
        out.add(row);
        return out;
    }

    public static List<RecordingMeta> removeRecording(List<RecordingMeta> rows,
                                                      String id) {
        List<RecordingMeta> out = new ArrayList<>();
        for (RecordingMeta r : rows) if (!r.id.equals(id)) out.add(r);
        return out;
    }
}
