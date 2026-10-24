#!/usr/bin/env python3
"""Lean single-link prober for Sandy's rolling link checker.

One URL in, one verdict out. A verdict mirrors the truth table of Sandy's
in-app ChannelScan/ScanEngine (v0.18.0):

  LIVE     a real first media segment arrived (TIER_PLAYS), or a direct
           (non-playlist) stream delivered bytes.
  LISTED   the playlist door answered with a valid playlist, but no
           segment proof could be obtained.
  DEAD     terminal, non-slow failure only: HTTP 404/410/403 at the
           playlist door, DNS failure, or an SSL error (a player fails
           identically every time on those).
  UNKNOWN  everything slow or ambiguous: timeouts, connect failures,
           5xx, empty replies, 200-with-a-non-playlist body. UNKNOWN is
           never a death sentence — the next look decides again.

Method is deliberately small (James's rule: behind the scenes, light):
  * python3 stdlib + the curl binary only — nothing else to install.
  * every fetch lands in a capped temp file on disk (512 KB playlist,
    64 KB segment proof); bodies are never held whole in memory.
  * a segment counts as arrived the moment bytes land — curl is stopped
    at the cap, we never download a stream to prove it exists.
  * per-link budget is a hard wall-clock deadline shared by all phases.

CLI:  probe.py URL [USER_AGENT]   -> one JSON line on stdout.
Library: from probe import probe; probe(url, ua, budget_s) -> dict.
"""
import json
import os
import subprocess
import sys
import tempfile
import time
from urllib.parse import urljoin, urlsplit

DEFAULT_UA = ("Mozilla/5.0 (Linux; Android 13; Pixel 8) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36")
PLAYLIST_CAP = 512 * 1024
SEGMENT_CAP = 64 * 1024
PLAYLIST_SHARE_S = 10.0    # of the per-link budget
SEGMENT_SHARE_S = 15.0
MIN_PROOF_BYTES = 1        # any arrived byte is arrival; size is logged


def _fetch(url, ua, dest, cap, timeout_s):
    """curl url -> dest file, capped. Returns dict(http, ctype, exit, bytes, ms)."""
    t0 = time.time()
    cmd = ["curl", "-s", "-L", "--max-time", f"{max(timeout_s, 1):.1f}",
           "--connect-timeout", "5", "--max-filesize", str(cap),
           "-A", ua, "-o", dest,
           "-w", "%{http_code}|%{content_type}|%{exitcode}", url]
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout_s + 10)
        meta = p.stdout.decode("utf-8", errors="replace").strip()
    except Exception:
        meta = "000||err"
    parts = meta.split("|")
    code = parts[0] if len(parts) > 0 else "000"
    ctype = (parts[1] if len(parts) > 1 else "").lower()
    xcode = parts[2] if len(parts) > 2 else "?"
    try:
        nbytes = os.path.getsize(dest)
    except OSError:
        nbytes = 0
    return {"http": int(code) if code.isdigit() else 0, "ctype": ctype,
            "exit": xcode, "bytes": nbytes,
            "ms": int((time.time() - t0) * 1000)}


def _fail_word(r):
    """Map a fetch result to the short failure vocabulary of the Oct-17 sweep."""
    if r["exit"] not in ("0", "63"):  # 63 = stopped at the size cap: bytes flowed
        return {"28": "timeout", "6": "dns", "7": "connect", "35": "ssl",
                "47": "redirect_loop", "3": "bad_url", "5": "proxy",
                "52": "empty_reply", "56": "recv_reset"}.get(
                    r["exit"], f"curl_{r['exit']}")
    if r["http"] and r["http"] != 200:
        return f"http_{r['http']}"
    return ""


def _is_terminal(word):
    """Terminal, non-slow failures — the only road to DEAD."""
    return word in ("http_404", "http_410", "http_403", "dns", "ssl")


def _read_head(path, limit=65536):
    try:
        with open(path, "rb") as f:
            return f.read(limit)
    except OSError:
        return b""


def _first_uri(body, base_url):
    """First stream/segment URI in an HLS playlist body, resolved absolute."""
    is_master = b"#EXT-X-STREAM-INF" in body
    for raw in body.decode("utf-8", errors="replace").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            return urljoin(base_url, line), is_master
    return None, is_master


def probe(url, ua=DEFAULT_UA, budget_s=30.0):
    """Probe one link. Returns {verdict, fail_word, ms, bytes_proof}."""
    t0 = time.time()

    def left():
        return budget_s - (time.time() - t0)

    def done(verdict, word, proof=0):
        return {"verdict": verdict, "fail_word": word,
                "ms": int((time.time() - t0) * 1000), "bytes_proof": proof}

    if urlsplit(url).scheme.lower() not in ("http", "https"):
        return done("UNCHECKED", f"scheme_{urlsplit(url).scheme.lower()}")

    tmpdir = tempfile.mkdtemp(prefix="lc-")
    pl = os.path.join(tmpdir, "pl")
    sg = os.path.join(tmpdir, "sg")
    try:
        r = _fetch(url, ua, pl, PLAYLIST_CAP, min(PLAYLIST_SHARE_S, left()))
        word = _fail_word(r)
        if word:
            if _is_terminal(word):
                return done("DEAD", word)
            return done("UNKNOWN", word)
        body = _read_head(pl)
        ctype = r["ctype"]
        if b"#EXTM3U" not in body[:2048]:
            # Not a playlist. A media body means the URL *is* the stream:
            # the bytes that just landed are the proof.
            if r["bytes"] > 0 and (ctype.startswith(("video/", "audio/"))
                                   or "octet-stream" in ctype or "mp2t" in ctype
                                   or "mpeg" in ctype):
                return done("LIVE", "", r["bytes"])
            return done("UNKNOWN", "bad_body")
        # Playlist door answered. Chase the first segment.
        target, is_master = _first_uri(body, url)
        if not target:
            return done("LISTED", "empty_playlist")
        if is_master:
            if left() <= 1:
                return done("LISTED", "budget")
            r2 = _fetch(target, ua, sg, PLAYLIST_CAP,
                        min(PLAYLIST_SHARE_S, left()))
            w2 = _fail_word(r2)
            if w2:
                return done("LISTED", f"variant_{w2}")
            body2 = _read_head(sg)
            if b"#EXTM3U" not in body2[:2048]:
                return done("LISTED", "variant_bad_body")
            target, _ = _first_uri(body2, target)
            if not target:
                return done("LISTED", "empty_playlist")
        if left() <= 1:
            return done("LISTED", "budget")
        r3 = _fetch(target, ua, sg, SEGMENT_CAP, min(SEGMENT_SHARE_S, left()))
        if r3["bytes"] >= MIN_PROOF_BYTES and r3["http"] in (200, 206):
            return done("LIVE", "", r3["bytes"])
        w3 = _fail_word(r3) or "seg_no_bytes"
        return done("LISTED", f"seg_{w3}" if not w3.startswith("seg_") else w3)
    finally:
        for p in (pl, sg):
            try:
                os.unlink(p)
            except OSError:
                pass
        try:
            os.rmdir(tmpdir)
        except OSError:
            pass


def main(argv):
    if len(argv) < 2:
        print("usage: probe.py URL [USER_AGENT] [BUDGET_S]", file=sys.stderr)
        return 2
    url = argv[1]
    ua = argv[2] if len(argv) > 2 else DEFAULT_UA
    budget = float(argv[3]) if len(argv) > 3 else 30.0
    print(json.dumps(probe(url, ua, budget)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
