# Upstream Sync Ledger

This module vendors a subset of [RoaringBitmap](https://github.com/RoaringBitmap/RoaringBitmap)
(Apache-2.0). This file is the **single source of truth** for which upstream commit our
sources correspond to and which upstream changes have been reviewed and (where relevant)
incorporated. Update it on every sync — see the `roaring-bitmap-sync` skill for the procedure.

## Coordinates

| Field | Value |
|---|---|
| Upstream repo | https://github.com/RoaringBitmap/RoaringBitmap |
| Upstream default branch | `master` |
| **Base commit** (fork point our sources derive from) | `952f8ce7aef1510eff07a3f325a5f8093151d993` — release **v1.6.12** (2026-02-26) |
| **Reviewed through** (last upstream commit inspected) | `2863e96d6715113dd32b9f2582bf962fdb57bbe6` — "Update AGENTS.md" (2026-06-11) |
| Vendored via fork | github.com/novoj/RoaringBitmap @ `f27cd538` (= v1.6.12 + CopyOnWriteRoaringBitmapV2 prototype) |

> The **base commit** is the upstream point our vendored `.java` sources branched from. The
> **reviewed-through commit** is how far up `master` we have walked and triaged. When the two
> differ, every commit between them has a disposition row below. After a sync that incorporates
> all relevant changes, advance "reviewed through" to the new tip.

## Vendored-subset exclusions

Upstream changes confined to these are **never applicable** (skip without further analysis):

- `org.roaringbitmap.buffer.*` — Mutable/Immutable/Mappeable/CopyOnWriteRoaringBitmap (not vendored).
- `org.roaringbitmap.insights.*` — bitmap insights (not vendored).
- `FrozenRoaringBitmap`, `FastRankRoaringBitmap` — not vendored.
- `longlong/Roaring64NavigableMap` — **not vendored**. Our 64-bit class
  `PersistentLongRoaringBitmap` derives from the ART-based `Roaring64Bitmap`, **not** from
  `Roaring64NavigableMap`. The two share no internal machinery; NavigableMap-only fixes
  (its `sortedHighs` / `firstHighNotValid` / `ensureCumulatives` / `ensureOne` cumulative-high
  cache) do not apply.
- Build/release/docs files: `gradle.properties`, `build.gradle.kts`, `README.md`, `AGENTS.md`,
  `jmh/`, `fuzz-tests/`, `examples/`, `bsi/`, benchmarks.

## Class name mapping (upstream → vendored)

| upstream | vendored |
|---|---|
| `org.roaringbitmap.RoaringBitmap` (+ folded `CopyOnWriteRoaringBitmapV2`) | `io.evitadb.roaringbitmap.PersistentRoaringBitmap` |
| `org.roaringbitmap.longlong.Roaring64Bitmap` | `io.evitadb.roaringbitmap.`**`PersistentLongRoaringBitmap`** (moved up to root — see reshaping) |
| `org.roaringbitmap.*` (everything else kept) | `io.evitadb.roaringbitmap.*` (package rename only) |
| `org.roaringbitmap.longlong.*` (internal 64-bit support) | `io.evitadb.roaringbitmap.longlong.*` (kept, but package **not exported**) |
| `org.roaringbitmap.art.*` | `io.evitadb.roaringbitmap.art.*` (kept, package **not exported**) |

## JPMS encapsulation reshaping (evita-specific divergence — preserve on re-sync)

The module exports **only** `io.evitadb.roaringbitmap`. To make that package the logical public
API surface without scattering the densely package-private-coupled core, the following evitaDB
modifications were applied. When replaying upstream changes, **keep** these:

- **`ArraysShim` dropped.** Upstream's JDK8 shim was replaced by native `java.util.Arrays.equals`/
  `Arrays.mismatch` (available on the release-17 target). If upstream touches `ArraysShim`, map the
  call to `java.util.Arrays`.
- **64-bit API hoisted to root.** `PersistentLongRoaringBitmap` and the 64-bit API interfaces
  (`LongIterator`, `PeekableLongIterator`, `LongConsumer`, `ImmutableLongBitmapDataProvider`,
  `LongBitmapDataProvider`) live in `io.evitadb.roaringbitmap`, not `longlong`. Upstream changes to
  `Roaring64Bitmap` land in root `PersistentLongRoaringBitmap.java`; changes to the remaining
  `longlong` support classes (`HighLowContainer`, `LongUtils`, `IntegerUtil`, `ContainerWithIndex`,
  `LongConsumerRelativeRangeAdapter`, `RoaringIntPacking`) stay in the (non-exported) `longlong` pkg.
- **Visibility demotions.** Root classes that are neither public API nor referenced cross-package
  were demoted `public` → package-private (e.g. `RoaringArray`, the batch-iterator impls, appenders,
  `ContainerPointer`, adapters). `RoaringIntPacking` was *promoted* to `public` (test-only helper in
  the hidden `longlong` pkg). If an upstream edit re-adds `public` to a demoted class, re-drop it.
- **`TestRoaring64Bitmap` lives in the root test package** (white-box test follows its class to root).
- **Still public-but-internal (TODO Part 2):** the `Container` hierarchy, `Util`, `CharIterator`,
  `PeekableCharIterator`, plus `FastAggregation`/`BitSetUtil`/the iterator flyweights remain `public`
  in the exported package. Hiding them needs either relocation into a non-exported `internal` pkg
  (with member promotion) or confirmation they're unused by evita; deferred to the Part 2 migration.

## Sync log

### Review 1 — base v1.6.12 (`952f8ce7`) → `2863e96d`

8 upstream commits triaged. **No functional change incorporated**: the two code commits target
classes we dropped or paths we already satisfy.

| upstream | kind | touches | disposition |
|---|---|---|---|
| `423c1e8b` Preallocate container array when converting between bitmap types (#827) | code | `RoaringBitmap`, `buffer/*` | **No-op.** Behavioral optimization (hunk 3) pre-sizes the array in the `RoaringBitmap(ImmutableRoaringBitmap)` constructor — a dropped buffer conversion path. Hunks 1+2 route `bitmapOfRange` through a new package-private `RoaringBitmap(int)` ctor; our `bitmapOfRange` already pre-sizes via `new RoaringArray(hbLast-hbStart+1)` and `append()` grows `shared[]` per call. Nothing to port. |
| `408d6bfe` Release v1.6.13 | build | `gradle.properties` | N/A — no version file vendored. |
| `6a70976f` Fix minHigh, add synchronized on cache-writing (#829) | code | `Roaring64NavigableMap` (+ its tests) | **N/A.** Fix lives entirely in NavigableMap's cumulative-high cache (`minHigh`/`firstHighNotValid`/`ensureOne`). Our `PersistentLongRoaringBitmap` is ART-based and has none of it (verified by grep). |
| `560c6a01` Release v1.6.14 | build | `gradle.properties` | N/A. |
| `458c6e4b` Add Apache Flink to README | docs | `README.md` | N/A. |
| `a3231dc6` Add Apache Paimon to README | docs | `README.md` | N/A. |
| `e830303f` AGENTS.md | docs | `AGENTS.md` | N/A. |
| `2863e96d` Update AGENTS.md | docs | `AGENTS.md` | N/A. |

**Net result:** vendored subset remains byte-for-behavior current with upstream `master` through
`2863e96d`. Effective upstream version coverage: **v1.6.14**.
