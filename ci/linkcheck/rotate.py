#!/usr/bin/env python3
"""Rotation driver for Sandy's rolling link checker.

ATTENTION-DRIVEN, WITH DECAY (James's model):
  * Everything in the directory stays in health.tsv — listed, browsable,
    available — whether it is ever checked or not. Availability is NOT
    verification: a link nobody has looked at is UNCHECKED and gets no
    routine probes.
  * A link joins the rotation when attention lands on it:
      - DECLARED attention: the config watchlist (and later, app-side
        tab/favorite membership). Declared wants NEVER expire while listed.
      - STANDING attention: links matching the config's chosen facets
        (default language=English). This is the pre-telemetry proxy for
        "the household watches this" and, like declared attention, it
        does not decay while the facet is chosen.
      - LOOKS: a view event sets last_viewed. A viewed link keeps being
        rechecked while its last view is inside decay_days — then it
        falls out of the rotation on its own. Nobody looks, nobody burns
        compute. The old verdict stays in the file, dated; age is its label.
  * Within the rotation, work order is: declared (watchlist) stalest
    first, then standing (facet) stalest — country-boost matches first
    inside that class — then view-active stalest. Never-checked members
    of the active set sort before all dated ones: entering attention
    earns a prompt first check.

Memory discipline (the hard constraint): health.tsv is streamed line by
line in both passes. The only things held are (a) the bounded selection —
at most slice_size candidate rows, (b) the probe results for that same
slice, and (c) during --refresh-directory only, the directory's url->facet
maps (~11k short strings, a few MB, freed when the mode exits).

Usage:
  rotate.py [--slice N] [--soft-minutes M] [--concurrency K]
            [--view URL]... [--views-file F] [-- health/config overrides]
  rotate.py --refresh-directory   reconcile health.tsv with a fresh
                                  iptv-org index (no probing)
State: <health>.state.json holds last_run_at (drives the decayed-out
count). health.tsv is rewritten atomically (tmp file + os.replace).
"""
import argparse
import collections
import concurrent.futures as cf
import json
import os
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from probe import probe, DEFAULT_UA  # noqa: E402

COLS = ("url", "name", "verdict", "checked_at", "last_viewed",
        "fail_word", "lang", "country")


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_iso(s):
    if not s:
        return None
    try:
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError:
        return None


def load_config(path):
    with open(path, encoding="utf-8") as f:
        cfg = json.load(f)
    cfg.setdefault("decay_days", 14)
    cfg.setdefault("slice_size", 1200)
    cfg.setdefault("soft_minutes", 20)
    cfg.setdefault("concurrency", 4)
    cfg.setdefault("per_link_budget_s", 30)
    cfg.setdefault("watchlist", [])
    facets = cfg.setdefault("chosen_facets", {})
    facets.setdefault("languages", [])
    facets.setdefault("country_boost", [])
    return cfg


class Attention:
    """Classifies one row: 0 declared, 1 standing facet, 2 view-active,
    None = not in the rotation."""

    def __init__(self, cfg, view_urls, now):
        self.watch = [w.lower() for w in cfg["watchlist"]]
        self.langs = {l.lower() for l in
                      cfg["chosen_facets"].get("languages", [])}
        self.boost = {c.lower() for c in
                      cfg["chosen_facets"].get("country_boost", [])}
        self.views = view_urls
        self.cutoff = now - timedelta(days=cfg["decay_days"])
        self.now = now

    def facet_match(self, row):
        # facet columns are ';'-joined: a link carrying the chosen
        # language among several still counts as chosen-facet.
        return any(p.strip().lower() in self.langs
                   for p in row["lang"].split(";"))

    def classify(self, row):
        url = row["url"]
        if url in self.views:  # a view lands this run: refresh the look
            row["last_viewed"] = now_iso()
        if self.watch:
            hay = (url + "\n" + row["name"]).lower()
            if any(w in hay for w in self.watch):
                return 0
        if self.facet_match(row):
            return 1
        lv = parse_iso(row["last_viewed"])
        if lv is not None and lv >= self.cutoff:
            return 2
        return None

    def boost_rank(self, row):
        return 0 if any(p.strip().lower() in self.boost
                        for p in row["country"].split(";")) else 1


def iter_rows(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            parts += [""] * (len(COLS) - len(parts))
            yield dict(zip(COLS, parts[:len(COLS)]))


def write_row(f, row):
    f.write("\t".join(row[c].replace("\t", " ").replace("\n", " ")
                      for c in COLS) + "\n")


def select_slice(health_path, cfg, view_urls, slice_n, state):
    """Pass 1 (streaming): pick the stalest slice of the active set.

    Returns (candidates, stats). candidates are row dicts, at most
    slice_n, in work order. The selection buffer never exceeds
    2 x slice_n rows (it prunes back to slice_n whenever it fills), so
    memory tracks the slice — never the map."""
    att = Attention(cfg, view_urls, datetime.now(timezone.utc))
    last_run = parse_iso(state.get("last_run_at", ""))
    decay_span = timedelta(days=cfg["decay_days"])
    now = datetime.now(timezone.utc)
    buf = []  # (key, row) candidates, pruned to the stalest slice_n
    stats = collections.Counter()
    class_sizes = collections.Counter()
    for row in iter_rows(health_path):
        stats["rows"] += 1
        if row["verdict"] == "GONE":
            stats["gone"] += 1
            continue
        if urlsplit(row["url"]).scheme.lower() not in ("http", "https"):
            stats["non_http"] += 1
            continue
        cls = att.classify(row)
        # decay accounting: a view-driven row (classify() already
        # excluded declared and facet-standing rows) whose membership
        # was still alive at the previous run and has lapsed by now.
        if cls is None:
            lv = parse_iso(row["last_viewed"])
            if (lv is not None and last_run is not None
                    and lv + decay_span > last_run
                    and lv + decay_span <= now):
                stats["decayed_this_run"] += 1
            if row["verdict"] == "UNCHECKED":
                stats["unchecked_inactive"] += 1
            continue
        class_sizes[cls] += 1
        if slice_n:
            checked = row["checked_at"] or ""  # never-checked sorts first
            key = (cls, att.boost_rank(row) if cls == 1 else 0, checked,
                   row["url"])
            buf.append((key, row))
            if len(buf) >= 2 * slice_n:
                buf.sort(key=lambda kr: kr[0])
                del buf[slice_n:]
    buf.sort(key=lambda kr: kr[0])
    chosen = [row for _key, row in buf[:slice_n]]
    stats["active_set"] = sum(class_sizes.values())
    stats["active_watchlist"] = class_sizes[0]
    stats["active_facet"] = class_sizes[1]
    stats["active_views"] = class_sizes[2]
    return chosen, stats


def apply_results(health_path, results, view_urls):
    """Pass 2 (streaming): rewrite health.tsv atomically with new
    verdicts and any view stamps applied."""
    stamp = now_iso()
    fd, tmp = tempfile.mkstemp(prefix=".health-", dir=os.path.dirname(
        os.path.abspath(health_path)))
    updated = 0
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        with open(health_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if not line.strip():
                    continue
                if line.startswith("#"):
                    out.write(line if line.endswith("\n") else line + "\n")
                    continue
                parts = line.rstrip("\n").split("\t")
                parts += [""] * (len(COLS) - len(parts))
                row = dict(zip(COLS, parts[:len(COLS)]))
                if row["url"] in view_urls and row["verdict"] != "GONE":
                    row["last_viewed"] = stamp
                r = results.get(row["url"])
                if r:
                    row["verdict"] = r["verdict"]
                    row["fail_word"] = r["fail_word"]
                    row["checked_at"] = stamp
                    updated += 1
                write_row(out, row)
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, health_path)
    return updated


def run_rotation(args, cfg):
    health_path = args.health
    state_path = health_path + ".state.json"
    state = {}
    if os.path.exists(state_path):
        try:
            with open(state_path, encoding="utf-8") as f:
                state = json.load(f)
        except Exception:
            state = {}
    view_urls = set(args.view or [])
    if args.views_file:
        with open(args.views_file, encoding="utf-8") as f:
            view_urls |= {l.strip() for l in f if l.strip()}
    slice_n = args.slice if args.slice is not None else cfg["slice_size"]
    soft_deadline = time.time() + (args.soft_minutes
                                   if args.soft_minutes is not None
                                   else cfg["soft_minutes"]) * 60
    t0 = time.time()
    candidates, stats = select_slice(health_path, cfg, view_urls,
                                      slice_n, state)
    counts = collections.Counter()
    results = {}
    probed = 0
    stopped_soft = False

    def work(row):
        return row["url"], probe(row["url"], DEFAULT_UA,
                                 cfg["per_link_budget_s"])

    if candidates:
        with cf.ThreadPoolExecutor(
                max_workers=cfg["concurrency"]) as ex:
            futs = {ex.submit(work, row): row for row in candidates}
            for fut in cf.as_completed(futs):
                url, r = fut.result()
                results[url] = r
                counts[r["verdict"]] += 1
                probed += 1
                if time.time() > soft_deadline and probed < len(candidates):
                    stopped_soft = True
                    for f2 in futs:
                        f2.cancel()
                    break
    updated = apply_results(health_path, results, view_urls)
    # oldest verdict still standing inside the active set after the run
    att = Attention(cfg, set(), datetime.now(timezone.utc))
    oldest = None
    for row in iter_rows(health_path):
        if row["verdict"] in ("GONE", "UNCHECKED"):
            continue
        if att.classify(row) is None:
            continue
        ca = row["checked_at"]
        if ca and (oldest is None or ca < oldest):
            oldest = ca
    age_txt = "none"
    if oldest:
        delta = datetime.now(timezone.utc) - parse_iso(oldest)
        age_txt = f"{delta.days}d{delta.seconds // 3600}h (since {oldest})"
    lines = [
        f"linkcheck rotation {now_iso()}",
        f"map rows: {stats['rows']}  (gone {stats['gone']}, "
        f"non-http {stats['non_http']}, never-checked & inactive "
        f"{stats['unchecked_inactive']})",
        f"active set: {stats['active_set']}  (watchlist "
        f"{stats['active_watchlist']}, facet-standing "
        f"{stats['active_facet']}, view-active {stats['active_views']})",
        f"decayed out this run: {stats['decayed_this_run']}",
        f"probed this run: {probed} of {len(candidates)} selected"
        + ("  [stopped on soft time budget]" if stopped_soft else ""),
        "verdicts: " + (", ".join(f"{k} {v}" for k, v in
                                  sorted(counts.items())) or "none"),
        f"rows updated: {updated}",
        f"oldest active verdict age: {age_txt}",
        f"elapsed: {time.time() - t0:.0f}s",
    ]
    summary = "\n".join(lines)
    print(summary)
    if args.summary_file:
        with open(args.summary_file, "w", encoding="utf-8") as f:
            f.write(summary + "\n")
    with open(state_path, "w", encoding="utf-8") as f:
        json.dump({"last_run_at": now_iso()}, f)
    return 0


# ---------------- directory refresh (no probing) ----------------

def _fetch_to(url, dest):
    p = subprocess.run(["curl", "-s", "-L", "--max-time", "120", "-o", dest,
                        "-w", "%{http_code}", url],
                       capture_output=True, text=True)
    return p.stdout.strip() == "200"


def _parse_index(path):
    """Stream an iptv-org index: yields (url, name, group)."""
    attrs_pending = None
    import re
    attr_re = re.compile(r'([a-zA-Z0-9_-]+)="([^"]*)"')
    with open(path, encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("#EXTINF"):
                attrs = dict(attr_re.findall(line))
                attrs_pending = (line.rsplit(",", 1)[-1].strip(),
                                 attrs.get("group-title", ""))
            elif line and not line.startswith("#") and attrs_pending:
                yield line, attrs_pending[0], attrs_pending[1]
                attrs_pending = None


def run_refresh(args, cfg):
    health_path = args.health
    tmpdir = tempfile.mkdtemp(prefix="lc-refresh-")
    cat = os.path.join(tmpdir, "index.m3u")
    lan = os.path.join(tmpdir, "language.m3u")
    cou = os.path.join(tmpdir, "country.m3u")
    d = cfg.get("directory", {})
    for url, dest, label in ((d.get("index"), cat, "index"),
                             (d.get("language"), lan, "language"),
                             (d.get("country"), cou, "country")):
        if not url or not _fetch_to(url, dest):
            print(f"refresh ABORTED: could not fetch {label} index ({url})")
            return 1
    def facet_map(path):
        m = {}
        for url, _n, g in _parse_index(path):
            if g:
                m.setdefault(url, set()).add(g)
        return {u: ";".join(sorted(gs)) for u, gs in m.items()}

    lang_of, country_of = facet_map(lan), facet_map(cou)
    directory = {}
    for url, name, _g in _parse_index(cat):
        directory.setdefault(url, name)
    seen = set()
    kept = new = gone = 0
    fd, tmp = tempfile.mkstemp(prefix=".health-", dir=os.path.dirname(
        os.path.abspath(health_path)))
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        with open(health_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("#") or not line.strip():
                    out.write(line if line.endswith("\n") else line + "\n")
                    continue
                parts = line.rstrip("\n").split("\t")
                parts += [""] * (len(COLS) - len(parts))
                row = dict(zip(COLS, parts[:len(COLS)]))
                if row["url"] in directory:
                    seen.add(row["url"])
                    row["name"] = directory[row["url"]] or row["name"]
                    row["lang"] = lang_of.get(row["url"], row["lang"])
                    row["country"] = country_of.get(row["url"],
                                                     row["country"])
                    if row["verdict"] == "GONE":
                        row["verdict"] = "UNCHECKED"
                        row["fail_word"] = "back_in_directory"
                    kept += 1
                else:
                    if row["verdict"] != "GONE":
                        row["verdict"] = "GONE"
                        row["fail_word"] = "left_directory"
                        gone += 1
                write_row(out, row)
        for url, name in directory.items():
            if url not in seen:
                write_row(out, {"url": url, "name": name,
                                "verdict": "UNCHECKED", "checked_at": "",
                                "last_viewed": "",
                                "fail_word": "new_in_directory",
                                "lang": lang_of.get(url, ""),
                                "country": country_of.get(url, "")})
                new += 1
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, health_path)
    summary = (f"linkcheck directory refresh {now_iso()}: kept {kept}, "
               f"new {new} (UNCHECKED), newly gone {gone}, "
               f"directory size {len(directory)}")
    print(summary)
    if args.summary_file:
        with open(args.summary_file, "w", encoding="utf-8") as f:
            f.write(summary + "\n")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="Sandy rolling link checker")
    ap.add_argument("--health", default=os.path.join(HERE, "health.tsv"))
    ap.add_argument("--config", default=os.path.join(
        HERE, "linkcheck.config.json"))
    ap.add_argument("--slice", type=int, default=None)
    ap.add_argument("--soft-minutes", type=float, default=None)
    ap.add_argument("--view", action="append", default=[],
                    help="record a view event for URL (repeatable)")
    ap.add_argument("--views-file", default=None)
    ap.add_argument("--summary-file", default=None)
    ap.add_argument("--refresh-directory", action="store_true")
    args = ap.parse_args(argv)
    cfg = load_config(args.config)
    if args.refresh_directory:
        return run_refresh(args, cfg)
    return run_rotation(args, cfg)


if __name__ == "__main__":
    sys.exit(main())
