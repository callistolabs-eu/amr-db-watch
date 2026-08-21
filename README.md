# amr-db-archive

Record of which version of each AMR reference database was current on which date, with checksums.

This exists because the [AMR living benchmark](https://www.callistolabs.eu) measures something no
static comparison can: **how predictions change on the same genomes as the reference databases
evolve**. That axis is only measurable if the version history is captured as it happens.

## What is and is not stored

Upstream already versions the heavy data, so mirroring it would be waste:

| Source | Upstream retention | What this repo keeps |
|---|---|---|
| **AMRFinderPlus** (NCBI) | Every dated release under `database/<minor>/<yyyy-mm-dd.n>/`, back to 2022 | Manifest + full file listing with sizes; mirrors the interpretable metadata (`ReferenceGeneCatalog.txt`, `ReferenceGeneHierarchy.txt`, mutation/susceptible/suppress tables). **Skips `AMR.LIB`** (~103 MB of HMMs, never read by hand) |
| **CARD** (McMaster) | Versioned downloads | Manifest + the full `card-data.tar.bz2` (~4.4 MB — small enough that mirroring beats trusting retention) |
| **ResFinder** (DTU) | Git repository: history is intrinsic | Manifest recording the commit hash and date. The hash *is* the version |

Roughly **8 MB per full round** of new releases; at observed upstream cadence, well under
100 MB/year. A plain GitHub repository is the right home — no LFS, no object storage.

## Layout

```
index.tsv                                  append-only log: when, source, version, url
snapshots/<source>/<version>/manifest.json  version, release date, checksums, file listing
snapshots/<source>/<version>/<files>        mirrored files, where any
```

`manifest.json` is the durable artifact: source URL, release date, and a SHA-256 for every
mirrored file, so a later re-download can be proven identical to what was current that day.

## Running

```sh
scala-cli run archive.scala              # check every source
scala-cli run archive.scala -- card      # just one
```

Idempotent: a version already recorded is skipped, so it is safe to run as often as you like.
A source that fails does not stop the others — a missed check is a gap that cannot be filled
later — and the run exits non-zero so the failure is visible.

CI runs it weekly and commits only when something new appeared (`.github/workflows/archive.yml`).
Upstream cadence is much slower than weekly; the frequent check just bounds how late a release
is noticed.

## Observed upstream cadence

AMRFinderPlus database releases under 4.2, as listed by NCBI on 2026-08-21:
`2025-12-03`, `2026-01-21`, `2026-03-24`, `2026-05-15`, `2026-08-07` — about one every seven
weeks. CARD is slower (4.0.0 Dec 2024 → 4.0.1 May 2025 → 4.0.2 Aug 2026). ResFinder's database
commits are sparse (May 2025, Sep 2025, Jan 2026).
