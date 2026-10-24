# V180Test harness — CI fixture audit (2026-10-23)

Every file `work/v180/V180Test.java` reads through a `user.home`-derived
path, enumerated by reading all 6,071 lines and every
`readAllBytes` / `readAllLines` / `FileInputStream` / existence-check
site. Audited against the local tree (`~/workspace/tala-tv-2026-10-16`,
the source of truth) and the repo snapshot on
`delwinsdad436-boop/Sandy-media-player` main (head `fc3e0b99` at audit
time; the repo's `V180Test.java` and `ci/run_v180test.sh` were verified
byte-identical to local by git blob SHA before patching).

## The three roots

| Root | Resolves to | In repo? |
|---|---|---|
| `yourFiles` | `~/workspace/your_files` | **No** — outside the project tree entirely |
| `v061`, `bench`, all `work/vNNN`, `app/…` paths | `~/workspace/tala-tv-2026-10-16/…` | Project tree; CI covers it, but the snapshot only carried `work/stage_deps.py` + `work/v180/` under `work/` |
| `sandy.classes` (system property) | compiled app classes | Produced by the runner itself in CI |

## Classification key

- **(a) already in repo** — ships with the snapshot (or is restored /
  produced by the existing CI steps). No action.
- **(b) text fixture** — valid UTF-8, pushed to the repo at the same
  relative path so the group runs for real in CI. Unguarded reads also
  get a CI-mode guard so a future loss degrades to a named SKIP, never
  a crash.
- **(c) binary / pack** — never pushed. In CI mode the whole group (or
  the dependent leg) becomes a loud named SKIP. Local strict behavior
  is untouched: without `-Dsandy.ci=true` a missing pack still
  crashes/fails exactly as before.

## Group by group

### CI-SKIP groups — class (c), binary packs in `~/workspace/your_files`

| Group | Files it reads | Guard today |
|---|---|---|
| `packChecks` | `your_files/sandy-engine-ja-en-onnx.pack` (221,633,548 B); `work/v061/out/packs/bad-sha.pack` (544 B); `work/v061/out/packs/missing-file.pack` (449 B); `your_files/sandy-engine-ja-en.pack` (199,807,768 B, optional leg, already `isFile()`-guarded) | First three reads are **unguarded** `FileInputStream` — the engine pack read is the observed CI crash (`V180Test.java` ~line 1204) |
| `diarPackSuite` ("diarpack") | `your_files/sandy-diarize.pack` (42,322,976 B) | `check(...)`-guarded existence → records a **failure** in CI, not a crash |
| `diarE2ESuite` ("e2e diar") | `your_files/sandy-diarize.pack`; `work/v120/diar/fixture.wav` (binary); `work/v120/diar/ref_embeddings.bin` (36,864 B binary); `work/v120/diar/toshi60.wav` (binary); `work/v120/diar/fixture_truth.json` (text — class (b), pushed, but the group still skips on its binary deps) | `check(...)`-guarded entry → records a **failure** in CI |
| `voicePackSuite` ("voicepack") | `your_files/sandy-voice-*.pack` — 52 packs, ~25 MB each (~1.3 GB total), incl. `sandy-voice-eng.pack` (tamper leg) and `sandy-diarize.pack` (wrong-door leg) | `listFiles` null-guarded, but the `packs != null` check and the count pin (52) record **failures** in CI |

### Text-fixture groups — class (b), pushed; CI guard added

| Group | Files (all verified valid UTF-8) | Guard today |
|---|---|---|
| `guardRegression` | `work/dub-lab/phone-bench/asr_base_vad0.json` (9,912 B) — the only bench file read; bench `*.wav` files do not exist / are not read, other `asr_*.json` are not read | **Unguarded** `readAllBytes` (would crash) |
| `e2eRecords` ("e2e") | `work/v061/out/e2e_slice.tr-en.json` (7,090 B), `e2e_slice.tr-en.srt` (2,972 B), `e2e_toshishun.tr-en.json` (8,092 B), `e2e_toshishun.tr-en.srt` (3,687 B) | `check(...)`-guarded existence → failures in CI |
| `catalogIndex` | `work/v100/fixtures/shows_page200.json` (279,076 B) | **Unguarded** `readAllBytes` (would crash) |
| `titleNames` | `work/v100/fixtures/akas_27436.json` (925 B), `akas_919.json` (740 B) | **Unguarded** |
| `farsi` | `work/v101/fixtures/shows_persian.json` (31,139 B), `work/v101/research_persian.json` (412 B) | **Unguarded** |
| `xmltv` | `work/v110/fixtures/guide_bridge.txt` (55 B), `guide_slice.xml` (12,952 B) | **Unguarded** |
| `facetSuite` | `work/v100/fixtures/shows_page200.json` (shared with catalogIndex) | **Unguarded** |
| `sortSuite` | `work/v100/fixtures/shows_page200.json` (shared) | **Unguarded** |
| `peopleSuite` | `work/v111/fixtures/people_search_cranston.json` (800 B), `castcredits_14245.json` (18,743 B) | **Unguarded** |
| `guideMapSuite` | `work/v111/data/guide_map_measured_111.json` (1,980 B) — plus `app/assets/guide_map.tsv`, class (a) | **Unguarded** |
| `voiceFrontendSuite` | `work/v130/fixtures/tokens-eng.txt`, `tokens-rus.txt` (the only two of the 47 `tokens-*.txt` the harness reads; all 47 were pushed — same tiny text family, 188 KB total) | **Unguarded** via `OnnxVoiceSynth.loadTokens` |
| `liveGroupsSuite` | `work/v020/index.country.m3u` (2,996,215 B text) — pushed. Plus `app/assets/guide_map.tsv`, class (a) | **Unguarded** |

### Special case — `liveGroupsSuite` and `channels_map.tsv`

`liveGroupsSuite` also reads `app/assets/channels_map.tsv`
**unguarded**. That file exists **neither in the repo nor in the local
app tree** (only forensic copies survive, under
`work/v080/forensics070/apk06*/assets/`). It cannot be pushed — there
is no current copy to push. The CI guard therefore sits at that read:
in CI the suite runs its real checks over `guide_map.tsv` and the
pushed `index.country.m3u`, then SKIPs (named `liveGroups`) at the
`channels_map.tsv` leg instead of crashing. Locally, strict mode is
unchanged — the same read still throws, as it does today with the
unpatched harness (the file has been absent locally since after the
2026-10-21 clean run; that is a pre-existing local fixture loss, not
something this patch causes or repairs).

### Already in repo — class (a), no action

- `app/assets/lang_registry.tsv` (`langRegistry`), `app/assets/guide_map.tsv`
  (`guideMapSuite`, `liveGroupsSuite`), `app/assets/voice_langs.tsv`
  (`voiceLangsSuite` via `shippedVoiceRows`), `app/assets/story_curation.tsv`
  (`storyDataSuite`), `app/assets/source_receipts_seed.tsv` (`receiptsSuite`)
  — all present in the repo's `app/assets`.
- `app/AndroidManifest.xml`, `app/src/**/*.java` (`srcText` users:
  `sanctuarySuite`, `channelScanSuite`, `nfajSuite`, …),
  `app/res/drawable/story_*.xml` existence checks (`storyPanelsSuite`),
  `app/res/drawable/ic_installer.png` (`installerFaceSuite`; CI restores
  PNGs from `ci/assets-b64` via the existing `ci/restore_binaries.sh`).
- Compiled-class reads via `sandy.classes` (`classBytes`,
  `collectSources` over class dirs, crash/honesty wiring suites) —
  produced by `ci/run_v180test.sh` itself before the run.
- Self-created temp files (`java.io.tmpdir`, `Files.createTempDirectory`)
  — not fixtures.

### Read by nothing in V180Test (noted so they stay unpushed)

`work/v061/out/`'s other ASR/MT JSONs and `mel_*.bin`;
`work/dub-lab/phone-bench/`'s other JSONs and scripts;
`work/v111/data/guide_map_0110_backup.tsv`;
`work/v120/diar/fixture.en.srt`, `gen_fixture.py`, `fix/*.wav`;
`work/v020/`'s other indexes and JSONs. No `.pack`, `.wav`, `.bin`,
`.pyc` or build output is pushed by this work — text only.

## What CI will do after this change

- **Run for real:** guardRegression, e2eRecords, catalogIndex,
  titleNames, farsi, xmltv, facetSuite, sortSuite, peopleSuite,
  guideMapSuite, voiceFrontendSuite, and liveGroupsSuite up to its
  `channels_map.tsv` leg — over the pushed fixtures.
- **SKIP (named, counted, printed):** `pack`, `diarpack`, `e2e diar`,
  `voicepack` (binary packs that never travel), and the tail of
  `liveGroups` (`channels_map.tsv` unavailable anywhere).
- The pass bar is unchanged: exit 0 and `V180Test: N checks, 0 failures`.
  Skips are not checks and never count as failures; the final
  `CI SKIPS: n group(s): …` line names every skipped group in the log.
