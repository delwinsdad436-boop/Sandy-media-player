#!/usr/bin/env python3
"""One-time seed builder for ci/linkcheck/health.tsv (already run once,
2026-10-23; kept in the repo as provenance for where the seed came from).

Seed sources — the surviving outputs of the 2026-10-17 full sweep
(LINK-HEALTH-2026-10-17.md):
  * directory: work/v020/index.category.m3u (12,064 EXTINF lines,
    11,356 unique URLs — the same snapshot lineage the sweep probed)
  * facets:    work/v020/index.language.m3u / index.country.m3u
    (url -> group-title joins, exactly how Sandy builds its facets)
  * verified:  the sweep's verified-working playlist,
    iptv-verified-working-2026-10-17.m3u (5,470 entries)

CONSERVATIVE MAPPING (house rule — never overstate a verdict):
  * a URL in the verified playlist seeds LIVE, checked_at = sweep end.
    The sweep's "ok" bar was playlist-tier only; under the new truth
    table those links may really be LISTED. The rotation re-probes
    facet-standing links first precisely so this debt is paid down
    fastest where it matters — seed labels say so in the health header.
  * every other directory URL seeds UNKNOWN with fail_word
    "seed_detail_lost": the sweep DID attempt each of them that night,
    but its per-link failure detail (results.jsonl) did not survive —
    only the aggregate report did. Nothing seeds DEAD on lost detail.
  * last_viewed seeds empty everywhere: no view telemetry existed.
    Standing attention (chosen facets) is the only seed-time membership.

Inputs stream; the url->lang / url->country dicts are the one bounded
exception (~11k short strings), freed at exit. Re-running this script
would RESET the rolling map to the Oct-17 seed — don't, unless the map
itself is lost. Day-to-day directory changes belong to
rotate.py --refresh-directory.
"""
import os
import re
import sys

BASE = "/home/hatch/workspace/tala-tv-2026-10-16"
CAT = os.path.join(BASE, "work/v020/index.category.m3u")
LAN = os.path.join(BASE, "work/v020/index.language.m3u")
COU = os.path.join(BASE, "work/v020/index.country.m3u")
VERIFIED = "/home/hatch/workspace/goals/sandy-tv-app/files/iptv-verified-working-2026-10-17.m3u"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "health.tsv")
SEED_TS = "2026-10-17T10:41:00Z"  # sweep window end, 05:41 CDT
ATTR_RE = re.compile(r'([a-zA-Z0-9_-]+)="([^"]*)"')

HEADER = """\
# Sandy rolling link map — one row per directory link.
# Columns: url \t name \t verdict \t checked_at \t last_viewed \t fail_word \t lang \t country
# Verdicts mirror Sandy's ChannelScan truth: LIVE = a segment arrived;
# LISTED = playlist answered, no segment proof; DEAD = terminal failure
# only; UNKNOWN = slow/ambiguous; UNCHECKED = never probed;
# GONE = left the directory (kept for the record, never silently dropped).
# Checked from GitHub's cloud once the workflow runs — a phone's doors
# may differ. Availability is not verification: UNCHECKED links are
# still in the directory Sandy browses.
# SEED (2026-10-23): rows come from the 2026-10-17 sweep. LIVE = in the
# sweep's verified-working playlist (playlist-tier proof only — the
# rotation re-proves standing links first). UNKNOWN/seed_detail_lost =
# probed that night, per-link failure detail not retained, so nothing
# was seeded DEAD. All seed checked_at = 2026-10-17T10:41:00Z.
"""


def parse_index(path):
    pending = None
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("#EXTINF"):
                attrs = dict(ATTR_RE.findall(line))
                pending = (line.rsplit(",", 1)[-1].strip(),
                           attrs.get("group-title", ""))
            elif line and not line.startswith("#") and pending:
                yield line, pending[0], pending[1]
                pending = None


def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ").replace("\r", " ")


def facet_map(path):
    """url -> ';'-joined sorted facet values. The directory multi-lists:
    1,079 URLs carry more than one language, so a single-value join
    would lie about facet membership. The facet columns keep them all."""
    m = {}
    for url, _n, g in parse_index(path):
        if g:
            m.setdefault(url, set()).add(g)
    return {u: ";".join(sorted(gs)) for u, gs in m.items()}


def main():
    lang_of = facet_map(LAN)
    country_of = facet_map(COU)
    verified = set()
    for url, _n, _g in parse_index(VERIFIED):
        verified.add(url)
    seen = set()
    rows = 0
    live = 0
    with open(OUT, "w", encoding="utf-8") as out:
        out.write(HEADER)
        for url, name, _g in parse_index(CAT):
            if url in seen:
                continue
            seen.add(url)
            if url in verified:
                verdict, word = "LIVE", ""
                live += 1
            else:
                verdict, word = "UNKNOWN", "seed_detail_lost"
            out.write("\t".join([
                clean(url), clean(name), verdict, SEED_TS, "", word,
                clean(lang_of.get(url, "")),
                clean(country_of.get(url, ""))]) + "\n")
            rows += 1
        # verified URLs absent from the category snapshot still belong
        for url in sorted(verified - seen):
            out.write("\t".join([
                clean(url), "", "LIVE", SEED_TS, "", "",
                clean(lang_of.get(url, "")),
                clean(country_of.get(url, ""))]) + "\n")
            rows += 1
            live += 1
    print(f"seed -> {OUT}: {rows} rows ({live} LIVE, {rows - live} UNKNOWN)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
