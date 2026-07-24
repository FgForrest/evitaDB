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
| **Reviewed through** (last upstream commit inspected) | `ba92f4978cb3f4c2f0d6e26342040fc59fe25508` — "[Gradle Release Plugin] 1.6.16" (2026-07-23) |
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

### Review 2 — `2863e96d` → `ba92f497`

9 upstream commits triaged. **Two functional changes incorporated** (#840 the O(N²) union/xor fix,
#831 an ART-traversal allocation cut); two more were already satisfied by pre-existing evita
divergences; **one real change to kept classes (#837) was deliberately deferred** — see its row.

| upstream | kind | touches | disposition |
|---|---|---|---|
| `c7bd6849` fix: ReverseIntIteratorFlyweight short overflow (>32768 containers) (#836) | code | `ReverseIntIteratorFlyweight` (+`buffer/*`, test) | **Already satisfied.** Our `ReverseIntIteratorFlyweight.pos` is already an `int` and the `>Short.MAX_VALUE` regression already ships as `TestReverseIntIteratorFlyweightManyContainers` (the fix originated here — authored by J. Novotný — and was upstreamed). No-op. |
| `593d65a1` fix: static orNot must not mutate input x1 (ior→or) (#833) | code | `RoaringBitmap` (+`buffer/*`, test) | **Already satisfied.** #833 swaps the static `orNot`'s `ior(RunContainer.rangeOfOnes(…))` for `or(…)` so it stops mutating `x1`. Our static `orNot` already clones before the in-place `ior` (`getContainerAtIndex(pos1).clone().ior(…)`) for the same reason (copy-on-write: never corrupt the input or a co-owner) — a stronger guarantee that subsumes the fix. No-op. |
| `f98b5dd3` fix the reverse iterators + update gradle (#837) | code | `ReverseIntIteratorFlyweight`, `RoaringBitmap`, `Util`, `ArrayContainer`, `BitmapContainer`, `ImmutableBitmapDataProvider` (+`buffer/*`, gradle, tests) | **DEFERRED — real, un-ported upstream change to kept classes (not N/A).** Widens the reverse iterators to `PeekableIntIterator` (adds `advanceIfNeeded`/`peekNext`) and drops the dead `length` parameter from `Util.reverseUntil`. evita has **no** caller of `getReverseIntIterator()` (only the vendored test suite exercises reverse iteration), so this is an unused API enhancement, not a correctness fix. Deferred to keep this sync focused on the perf fix; port it if evita ever adopts reverse peekable iteration. |
| `b40a7734` Release v1.6.15 | build | `gradle.properties` | N/A. |
| `941c09a8` Remove Stars section from README | docs | `README.md` | N/A. |
| `46d5e104` Reuse ART shuttle stack entries instead of allocating per node (#831) | code | `art/AbstractShuttle` | **Incorporated.** ART traversal (`select` / `rankLong` / ordered iteration, all used by `PersistentLongRoaringBitmap`) allocated a `NodeEntry` on every node visited. Ported the `useEntry(depth, node)` slot-reuse helper — it resets exactly the five `NodeEntry` fields a fresh entry would have (verified field parity) — replacing all 5 `new NodeEntry()` push sites. Behaviour unchanged (upstream reports −36…−65 % traversal allocation). |
| `e140aee9` Declare junit as a dependency of jmh (#839) | build | `build.gradle.kts` | N/A. |
| `ef131a71` Avoid O(N^2) operations in some cases (#840) | code | `RoaringArray`, `RoaringBitmap` (+`buffer/*`, jmh) | **Incorporated (COW-adapted).** In-place `or`/`xor`/`naivelazyor` did a per-key `insertNewKeyValueAt`/`removeAtIndex` shift that is O(N²) when the operands' keys interleave; upstream collapses the remaining suffix into a single-pass `mergeBulk` at the first structural divergence. Ported — **but as a `PersistentRoaringBitmap` method, not a `RoaringArray` one** (see divergence note below): source-only chunks are borrowed by structural sharing (not cloned), a shared receiver container is cloned before the in-place overlap op, and the parallel `shared[]` flag array is rebuilt in lockstep. Added `RoaringArray.adopt(keys, values, size)` to install the rebuilt arrays and clear `frozen`. Instance `lazyor` is **left on the per-key path**, matching upstream (PR #840 did not touch it). Covered by `MergeBulkCopyOnWriteTest` (interleaved keys × `clone()` COW peer, incl. the xor cancelled-pair drop and a 2000-key large-interleaved case). |
| `ba92f497` Release v1.6.16 | build | `gradle.properties` | N/A. |

**Net result:** incorporated #840 (O(N²) union/xor fix) and #831 (ART allocation cut); #833 and #836
were already satisfied by pre-existing evita divergences; #837 deferred (recorded above, not N/A); the
rest are build/docs. Effective upstream version coverage: **v1.6.16**.

#### Copy-on-write divergence introduced by the #840 port (preserve on re-sync)

Upstream added `mergeBulk` as a package-private method on **`RoaringArray`**. In the vendored copy the
port lives on **`PersistentRoaringBitmap.mergeBulk(...)`** instead, because the copy-on-write `shared[]`
flag array is owned by `PersistentRoaringBitmap` (RoaringArray "does not track container sharing
itself", per its class comment) and the merge needs both operands' flags to borrow-vs-clone correctly.
`RoaringArray` only gained a small `adopt(keys, values, size)` sink to receive the rebuilt arrays. When
a future sync touches upstream `RoaringArray.mergeBulk`, apply the change to
`PersistentRoaringBitmap.mergeBulk` here. The three op selectors (`MERGE_OR`/`MERGE_XOR`/
`MERGE_LAZY_OR`) are likewise private constants on `PersistentRoaringBitmap`, not `RoaringArray`.
