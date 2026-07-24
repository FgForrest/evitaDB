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
| **Reviewed through** (last upstream commit inspected) | `ba92f4978cb3f4c2f0d6e26342040fc59fe25508` — the post-release bump of `gradle.properties` to the next development version `1.6.16` (2026-07-23). It is `master`'s tip and the only commit after the `1.6.15` tag; **there is no 1.6.16 release.** |
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

9 upstream commits triaged. **Three functional changes incorporated** (#840 the O(N²) union/xor fix,
#831 an ART-traversal allocation cut, #837 the reverse peekable iterators); two more were already
satisfied by pre-existing evita divergences; the rest are build/docs. Porting #837 also surfaced a
live correctness bug in the reverse array cursor — see its row.

| upstream | kind | touches | disposition |
|---|---|---|---|
| `c7bd6849` fix: ReverseIntIteratorFlyweight short overflow (>32768 containers) (#836) | code | `ReverseIntIteratorFlyweight` (+`buffer/*`, test) | **Already satisfied.** Our `ReverseIntIteratorFlyweight.pos` is already an `int` and the `>Short.MAX_VALUE` regression already ships as `TestReverseIntIteratorFlyweightManyContainers` (the fix originated here — authored by J. Novotný — and was upstreamed). No-op. |
| `593d65a1` fix: static orNot must not mutate input x1 (ior→or) (#833) | code | `RoaringBitmap` (+`buffer/*`, test) | **Already satisfied.** #833 swaps the static `orNot`'s `ior(RunContainer.rangeOfOnes(…))` for `or(…)` so it stops mutating `x1`. Our static `orNot` already clones before the in-place `ior` (`getContainerAtIndex(pos1).clone().ior(…)`) for the same reason (copy-on-write: never corrupt the input or a co-owner) — a stronger guarantee that subsumes the fix. No-op. |
| `f98b5dd3` fix the reverse iterators + update gradle (#837) | code | `ReverseIntIteratorFlyweight`, `RoaringBitmap`, `Util`, `ArrayContainer`, `BitmapContainer`, `ImmutableBitmapDataProvider` (+`buffer/*`, gradle, tests) | **Incorporated.** Widens the reverse iterators to `PeekableIntIterator`: `RoaringReverseIntIterator` and `ReverseIntIteratorFlyweight` gained `advanceIfNeeded(int)`/`peekNext()`, and `ImmutableBitmapDataProvider.getReverseIntIterator()` narrowed its return type accordingly (source-compatible — the only callers assign to `IntIterator` or consume the iterator directly). `Util.reverseUntil` lost its unused `length` parameter. The two `BitmapContainer`/`ArrayContainer` signature hunks were already satisfied: all three reverse char cursors here already implement `PeekableCharIterator`. **Ported despite evita having no caller of `getReverseIntIterator()`, and that turned out to matter** — see the reverse-cursor fix below. |
| `b40a7734` post-release bump to next dev version 1.6.15 | build | `gradle.properties` | N/A. |
| `941c09a8` Remove Stars section from README | docs | `README.md` | N/A. |
| `46d5e104` Reuse ART shuttle stack entries instead of allocating per node (#831) | code | `art/AbstractShuttle` | **Incorporated.** ART traversal (`select` / `rankLong` / ordered iteration, all used by `PersistentLongRoaringBitmap`) allocated a `NodeEntry` on every node visited. Ported the `useEntry(depth, node)` slot-reuse helper — it resets exactly the five `NodeEntry` fields a fresh entry would have (verified field parity) — replacing all 5 `new NodeEntry()` push sites. Behaviour unchanged (upstream reports −36…−65 % traversal allocation). |
| `e140aee9` Declare junit as a dependency of jmh (#839) | build | `build.gradle.kts` | N/A. |
| `ef131a71` Avoid O(N^2) operations in some cases (#840) | code | `RoaringArray`, `RoaringBitmap` (+`buffer/*`, jmh) | **Incorporated (COW-adapted).** In-place `or`/`xor`/`naivelazyor` did a per-key `insertNewKeyValueAt`/`removeAtIndex` shift that is O(N²) when the operands' keys interleave; upstream collapses the remaining suffix into a single-pass `mergeBulk` at the first structural divergence. Ported — **but as a `PersistentRoaringBitmap` method, not a `RoaringArray` one** (see divergence note below): source-only chunks are borrowed by structural sharing (not cloned), a shared receiver container is cloned before the in-place overlap op, and the parallel `shared[]` flag array is rebuilt in lockstep. Added `RoaringArray.adopt(keys, values, size)` to install the rebuilt arrays and clear `frozen`. Instance `lazyor` is **left on the per-key path**, matching upstream (PR #840 did not touch it). Covered by `MergeBulkCopyOnWriteTest` (interleaved keys × `clone()` COW peer, incl. the xor cancelled-pair drop and a 2000-key large-interleaved case). |
| `ba92f497` post-release bump to next dev version 1.6.16 | build | `gradle.properties` | N/A. Sets `version = 1.6.16` after the `1.6.15` release; no 1.6.16 artifact exists. |

**Net result:** incorporated #840 (O(N²) union/xor fix), #831 (ART allocation cut) and #837 (reverse
peekable iterators, plus the reverse-cursor fix it surfaced); #833 and #836 were already satisfied by
pre-existing evita divergences; the rest are build/docs. Effective upstream version coverage:
**v1.6.15** — the newest actual release, whose tag `1.6.15` points at `ef131a71` (#840, incorporated).
The reviewed-through commit sits one purely-editorial commit beyond it.

#### Reverse-cursor exhaustion fix surfaced by the #837 port (evita divergence — preserve on re-sync)

`PeekableCharIterator.advanceIfNeeded` is direction-aware: after the call a reverse cursor must be
either exhausted or positioned at a value `<=` the bound. The three reverse cursors did not agree on
the case where a container holds **no** value at or below the bound:

- `ReverseBitmapContainerCharIterator` — correct here (it sets `position = -1`). Note this is already
  an evita divergence: upstream neither exhausts nor reads `bitmap[0]`, so it can both park above the
  bound and drop a value stored in word 0.
- `ReverseRunContainerCharIterator` — correct (walks `pos` down to `-1`).
- `ReverseArrayContainerCharIterator` — **was wrong.** `Util.reverseUntil` saturates at index `0`
  rather than reporting "no match", so the cursor stayed at index 0 with `hasNext() == true` and
  `peekNext()` **above** the requested bound. Fixed by re-testing `content[0]` and exhausting instead.

This was not merely a latent flaw in the newly-ported API: `PersistentLongRoaringBitmap`'s
`getReverseLongIterator()` has always been a `PeekableLongIterator` and forwards its seek straight
down to these cursors, so a reverse seek that had to cross out of an array-backed chunk returned a
value greater than the requested bound. Pinned by `ReverseAdvanceIfNeededContractTest`, which asserts
the post-condition per container shape (upstream's own tests only ever probe values that are present
in the bitmap, which is why neither upstream nor Review 1 caught it).

Deliberately **not** changed: `advanceIfNeeded` (both directions, every shape) assumes a monotonic
probe sequence, and probing backwards can re-emit already-consumed values. That is upstream's
documented intersection idiom, shared by the forward cursors, and no caller violates it.

#### Flyweight `clone()` independence fix (evita divergence — preserve on re-sync)

`IntIteratorFlyweight` and `ReverseIntIteratorFlyweight` cache one reusable char cursor per container
shape (`arrIter` / `bitmapIter` / `runIter`) so that stepping between chunks allocates nothing.
Upstream's `clone()` delegates to `Object.clone()`, a **shallow** copy — the fork and its origin end
up holding the *same* three cursor objects. The explicit `x.iter = this.iter.clone()` repairs only
the currently-active cursor, so the pair stays independent exactly until one side crosses a chunk
boundary and `nextContainer()` re-wraps a cached cursor the other is still reading through. The
victim then emits a value assembled from one chunk's low bits and another chunk's high bits — a value
not present in the bitmap at all.

Both are fixed here by building the fork from the no-arg constructor (which allocates its own cached
cursors) and copying only the position state — `roaringBitmap`, `pos`, `hs`, plus a deep copy of the
active cursor. The three cached fields are `final`, so a post-`super.clone()` reassignment would not
compile in any case. Costs three allocations per `clone()` and nothing on `next()`, the path the
flyweight exists to optimise. Covered by `IteratorCloneIndependenceTest`.

The per-bitmap `RoaringIntIterator` / `RoaringReverseIntIterator` inner classes are **not** affected —
they allocate a fresh char cursor per chunk, so `super.clone()` plus `iter.clone()` is sufficient
there. Both are covered by the same test to stop the two families from drifting apart again.

If upstream ever fixes this, drop the divergence; until then re-apply it after any re-sync of these
two files.

#### `orNot` installs rebuilt arrays through `adopt` (evita divergence — preserve on re-sync)

`orNot` rebuilds `keys`/`values` wholesale and previously assigned the three `RoaringArray` fields
directly, which left the array-level `frozen` guard set even though the installed arrays are freshly
allocated and privately owned. That was safe — every reader of `frozen` copies defensively, so the
only cost was a redundant `Arrays.copyOf` on the next structural write — but it made `orNot` the one
rebuild path not going through the `adopt(keys, values, size)` sink. It now uses `adopt`, matching
`mergeBulk`.

#### Copy-on-write divergence introduced by the #840 port (preserve on re-sync)

Upstream added `mergeBulk` as a package-private method on **`RoaringArray`**. In the vendored copy the
port lives on **`PersistentRoaringBitmap.mergeBulk(...)`** instead, because the copy-on-write `shared[]`
flag array is owned by `PersistentRoaringBitmap` (RoaringArray "does not track container sharing
itself", per its class comment) and the merge needs both operands' flags to borrow-vs-clone correctly.
`RoaringArray` only gained a small `adopt(keys, values, size)` sink to receive the rebuilt arrays. When
a future sync touches upstream `RoaringArray.mergeBulk`, apply the change to
`PersistentRoaringBitmap.mergeBulk` here. The three op selectors (`MERGE_OR`/`MERGE_XOR`/
`MERGE_LAZY_OR`) are likewise private constants on `PersistentRoaringBitmap`, not `RoaringArray`.

#### Static `or`/`xor`/`andNot`: precise result flags, deliberately coarse operand flags (evita divergence — preserve on re-sync)

Upstream's static binary operations carry a chunk that exists in only one operand into the result with
`appendCopy`, i.e. an eager `Container.clone()`. The vendored copy carries it **by reference** and
records the co-ownership in the `shared[]` flag array instead, which is the whole point of the
persistent reshaping.

That bookkeeping used to be a blunt over-approximation on both sides: `markAllShared(x1)` /
`markAllShared(x2)` plus an all-`true` result array (`newAllSharedResult`). It was safe — a spurious
`true` only ever buys an extra clone, never corruption — but a `shared` flag is a promise to clone
before the next in-place write, so promising on a chunk nobody co-owns means paying for a clone
(8 KiB for a bitmap chunk) that buys nothing.

**The result side is now tracked per slot.** A chunk present in both operands is recombined into a
private container (`c1.or(c2)` and friends allocate; they never hand back an operand) and is left
unflagged; a chunk lent from a single operand is flagged, via `flagLentLast` / `appendLentRange`.
`newAllSharedResult` is gone. The result is reachable only by the caller until it is published, so
per-slot tracking is unconditionally safe there.

**The operand side is deliberately left as the wholesale `markAllShared` fill**, and that is now
documented on the method so it is not "optimised" later. `TransactionalBitmap` is `@ThreadSafe` and
hands the same live `PersistentRoaringBitmap` to every concurrent read-only query thread, so one
bitmap can be an operand of two queries at once while `shared[]` is written without synchronisation.
A full fill is idempotent: an update lost to a racing `ensureSharedCapacity` reallocation is
re-established by the next caller, and the value it converges on is the conservative one. Sparse
per-slot writes have no such convergence, and the value a lost sparse write leaves behind is the
*unsafe* one — an unflagged slot over a container the result aliases, which is exactly the
precondition for silent cross-bitmap corruption. Narrowing this side was measured at −32 % allocation
on the diff-layer shape and is therefore worth revisiting, but only behind two things it does not have
today: a hard `shared.length >= size` invariant (so `ensureSharedCapacity` can never reallocate a
published bitmap out from under a concurrent writer) and a decision about publishing those writes.

`andNot` still never marks its subtrahend, since no container is ever carried out of it. Sizing note:
the result flag array is allocated once at the operation's upper bound (`length1 + length2`, or
`length1` for `andNot`) and never grown — a `shared[]` longer than the container array is legal, only
shorter is not. That over-allocates by up to `length1 + length2 - resultSize` bytes versus the old
exactly-sized array; measured at +64 bytes per `or` on a 64-chunk operand pair, against a 24 % cut
where the result is written.

In the same spirit, `runOptimize()` and `removeRunCompression()` now clear the flag of any slot whose
container they re-encoded: the replacement was allocated for this bitmap alone, so the co-ownership
the slot recorded belonged to the container that was just dropped from it.

Measured A/B (two JVMs, thread-allocation counters, bit-for-bit reproducible), 64-chunk operands:

| shape | allocation | best-of-9 wall clock |
|---|---|---|
| `or(a, b)` then writes into the result | 217,095,792 → 164,417,392 bytes/cycle (**−24.3 %**) | 12.2 → 10.3 ms (best of 5 runs × 9) |
| diff-layer `andNot(or(baseline, insertions), removals)` then writes into the deltas | 325,415,688 → 325,428,488 bytes/cycle (**+0.004 %**) | unchanged |

The second row is the honest counterpart of the first: that shape's cost sits entirely in the operand
flags, which this change deliberately does not touch.

The precondition — that the out-of-place container operators never return an operand or anything
aliasing one — is pinned by `ContainerBinaryOpFreshnessTest` across all nine shape pairs in both
operand orders (identity *and* a scribble-the-result check, which catches a distinct object wrapping
an operand's backing array). The flags are pinned by `SharedFlagPrecisionTest` and by
`SharedContainerLockstepFuzzTest`, whose alias-graph check now drives the static `xor` and `andNot`
producers too; deliberately dropping a lent chunk's flag makes that fuzz fail at seed 0, step 26.

**Not applicable upstream.** `shared[]` has no upstream counterpart — upstream `RoaringBitmap` results
are freely mutable by contract, which is exactly why it clones on carry-over instead of sharing. There
is nothing to report or contribute here.
