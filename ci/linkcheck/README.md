# Sandy — the rolling link checker

A background checker that keeps Sandy's channel map fresh — behind the
scenes, on a small footprint, and only where attention actually is.

## James's model, in his terms

Everything in the directory stays **available** — listed, browsable —
whether it has ever been checked or not. Availability is not
verification. A link earns checks by being wanted:

- **Declared wants never expire.** Links on the config watchlist (and,
  later, channels a viewer puts on an app tab or favorites list) are
  standing attention. They are rechecked every rotation for as long as
  they're listed.
- **Standing attention.** Links matching the household's chosen facets
  (config `chosen_facets`, default: language **English**) count as
  always-wanted. This is the stand-in for viewer attention until the
  app itself reports views; it never decays while the facet is chosen.
  Inside this class, `country_boost` (default: United States) goes first.
- **Looks expire.** A link somebody merely *looked at* (a view event
  stamps `last_viewed`) keeps getting rechecked for a while —
  `decay_days`, default **14** — and then falls out of the rotation by
  itself and is never probed again until it is looked at again. Its
  last verdict stays in the map with its date on it. Stale is a label,
  not a secret.
- **Never looked at = never probed.** Brand-new or ignored links sit
  at `UNCHECKED`. That is the design, not a gap: don't burn compute on
  channels nobody chose.

Work order inside a run: declared stalest first, then standing
stalest, then view-active stalest. A link entering attention with no
verdict yet sorts ahead of all dated ones — entering earns a prompt
first check on the next run.

## What lives here

| File | Job |
|---|---|
| `health.tsv` | The rolling map: one line per link — url, name, verdict, `checked_at`, `last_viewed`, fail word, language(s), country(ies). |
| `probe.py` | The lean prober (python3 stdlib + curl, nothing else). |
| `rotate.py` | The rotation: picks the stalest slice of the active set, probes it, rewrites the map atomically, prints a one-screen summary. `--refresh-directory` reconciles the map with a fresh iptv-org index (new links enter as UNCHECKED; vanished links are marked **GONE**, never silently dropped). |
| `linkcheck.config.json` | The knobs (below). |
| `seed_build.py` | Provenance: how the map was seeded from the 2026-10-17 sweep. One-time; do not re-run over a live map. |

The scheduled driver is `.github/workflows/linkcheck.yml`: every
2 hours it runs one rotation slice and commits the updated map back
(the commit message is the run summary — the git history is the audit
trail). **Status: the workflow is authored but not yet planted** — the
Muse GitHub App can't write workflow files, so it goes in through one
browser pass together with `build-and-test.yml`. Until then, no
scheduled runs exist; anything claiming otherwise is unproven.

## The truth table (same as Sandy's in-app ChannelScan, v0.18.0)

| Verdict | Meaning |
|---|---|
| `LIVE` | A real first media segment arrived (or a direct stream delivered bytes). The only "plays" claim. |
| `LISTED` | The playlist door answered with a valid playlist, but no segment proof — on the shelf, not proven to play. |
| `DEAD` | Terminal, non-slow failure only: HTTP 404/410/403 at the playlist door, DNS failure, SSL error. |
| `UNKNOWN` | Slow or ambiguous: timeout, connect failure, 5xx, empty reply, non-playlist body. Never a death sentence. |
| `UNCHECKED` | In the directory; nobody has wanted it checked yet. |
| `GONE` | Left the directory at the last refresh. Kept in the file for the record. |

**Vantage label:** verdicts are measured from wherever the checker
runs — GitHub's cloud once the workflow is planted. Doors differ by
network (geo gates, token doors); a link can read LISTED or UNKNOWN
from the cloud and still play on a phone, and Sandy's own on-device
scan remains the player's last word. Per-entry user-agents from the
directory are not carried in the map; the prober uses one Android UA.

## The rotation math (as configured)

- Standing set = links carrying the chosen language: **3,025** in the
  seed map (3,024 of them probeable http/https).
- Slice = **1,200** links every **2 hours** → the whole standing set
  is re-proven roughly every **6 hours**. The watchlist rides ahead
  of every slice.
- View-active links share what's left of each slice after declared +
  standing stalest; a busy household's looks can extend the standing
  cycle — the summary prints the active-set size and the oldest
  active verdict age every run, so drift is visible, never silent.
- Nothing outside the active set is probed at all. Full-map uniformity
  is deliberately *not* a goal.

## Memory discipline (the hard constraint)

- `health.tsv` is streamed line-by-line in both passes of a rotation.
  The prober's fetches go to capped temp files on disk (512 KB
  playlist, 64 KB segment proof) — bodies are never held whole, and a
  segment is abandoned the moment enough bytes prove it exists.
- Selection buffers at most 2× the slice of candidate rows; probe
  results held are the slice's, nothing more. Concurrency is 4,
  per-link budget 30 s, soft run budget 20 min.
- The one exception, named: `--refresh-directory` builds url→facet
  maps for the ~11k-link directory (a few MB of short strings) and
  frees them when it exits. It never runs inside a rotation.
- No caches, no database, no daemon. The map is one TSV; the state
  file beside it (`health.tsv.state.json`, created at runtime) is
  three lines.

## Changing what the household wants (one-line edits)

In `linkcheck.config.json`:

- Watch a channel forever: add a URL (or a name fragment) to
  `"watchlist": []` → e.g. `"watchlist": ["pluto.tv", "https://…/specific.m3u8"]`.
  Remove it and the link goes back to expiring by looks.
- Change the standing language: edit `"languages": ["English"]`
  (values are the directory's language names, e.g. `"Spanish"`).
- Change the boost country: edit `"country_boost": ["United States"]`.
- Looks last longer/shorter: edit `"decay_days": 14`.
- Bigger/smaller slices: `"slice_size": 1200`.

Commit the edit; the next scheduled run obeys it.

## Seed honesty (2026-10-23)

The map was seeded from the 2026-10-17 full sweep (11,356 links,
5,470 verified playable that night — see `LINK-HEALTH-2026-10-17.md`).
The sweep's per-link failure detail did not survive, so the seed is
conservative: playlist-verified links seed as `LIVE` (playlist-tier
proof only — the rotation re-proves standing links first, which pays
that debt down fastest), everything else seeds `UNKNOWN` with fail
word `seed_detail_lost`. Nothing was seeded DEAD on lost detail.

## Operations notes

- Once the workflow runs, **the bot owns `health.tsv`** — it commits
  the map every run. Future snapshot pushes must leave `health.tsv`
  (and `health.tsv.state.json`) out, or they'll fight the bot.
- A `GONE` link that re-enters the directory returns as `UNCHECKED`,
  fail word `back_in_directory`.
- Non-http links (rtsp/rtmp/…, 9 in the seed) are counted in the
  summary and never probed through this door.
