package com.talacore.talatv;

import java.util.ArrayList;
import java.util.List;

/**
 * Fragment -> sentence-unit merge (v0.6.0). A faithful port of the
 * lab recipe's merge (work/dub-lab: lab_common.merge_units, copied
 * from cook_driver.py), so app-cooked units are shaped exactly like
 * the units the cook-rate and phone-bench numbers were measured on:
 *
 *   close the current unit when it ends a sentence (。！？…) and
 *   has run at least 3 s, or when the span would exceed ~12 s, or
 *   when the gap to the next fragment exceeds 2 s.
 *
 * Guard-flagged fragments mark their unit: uncertainty survives the
 * merge and is shown, never averaged away.
 *
 * Pure logic; the harness checks it against the bench's real
 * segment files and the bench's own merged unit counts.
 */
public final class UnitMerge {

    public static final class Unit {
        public double start, end;
        public String srcText = "";
        /** Any fragment in this unit was guard-flagged. */
        public boolean flagged = false;
        /** Diarization speaker key; "" until a diarizer names one. */
        public String speaker = "";
        public double duration() { return Math.max(0.0, end - start); }
    }

    private static final String SENT_END = "。！？…";

    private UnitMerge() {}

    public static List<Unit> merge(List<HallucinationGuard.Segment> segs,
                                   HallucinationGuard.Result guard) {
        List<Unit> units = new ArrayList<>();
        Unit cur = null;
        boolean curFlagged = false;
        for (int i = 0; i < segs.size(); i++) {
            HallucinationGuard.Segment s = segs.get(i);
            boolean fl = guard != null && guard.isFlagged(i);
            if (cur == null) {
                cur = new Unit();
                cur.start = s.start; cur.end = s.end; cur.srcText = s.text;
                curFlagged = fl;
                continue;
            }
            double span = s.end - cur.start;
            double gap = s.start - cur.end;
            String trimmed = cur.srcText == null ? "" : cur.srcText.replaceAll("\\s+$", "");
            boolean endsSentence = !trimmed.isEmpty()
                    && SENT_END.indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0;
            if ((endsSentence && (cur.end - cur.start) >= 3.0) || span > 12.0 || gap > 2.0) {
                cur.flagged = curFlagged;
                units.add(cur);
                cur = new Unit();
                cur.start = s.start; cur.end = s.end; cur.srcText = s.text;
                curFlagged = fl;
            } else {
                cur.end = s.end;
                cur.srcText += s.text;
                curFlagged = curFlagged || fl;
            }
        }
        if (cur != null) {
            cur.flagged = curFlagged;
            units.add(cur);
        }
        return units;
    }
}
