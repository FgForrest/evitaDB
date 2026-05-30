# Count-augmented B+ tree backing for `UnorderedLookup` / `ChainIndex` — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** Replace the dual-`int[]` backing of `io.evitadb.index.array.UnorderedLookup` (the mutable delegate behind `TransactionalUnorderedIntArray`, used by `ChainIndex`) with a **count-augmented (order-statistic) `int` B+ tree using implicit positions**, so that a single insert/remove touches only a leaf + its ancestors instead of reallocating two full-length arrays and renumbering a suffix. This removes the `O(N²)` CPU **and** the `O(N²)`-bytes / humongous-allocation GC wall that currently stalls high-cardinality chains — in **both** the warm-up (non-transactional) and transactional phases — **without degrading read performance**, which is the overriding constraint.

**Architecture:** `TransactionalUnorderedIntArray` is retained as the public façade (its API is consumed across `ChainIndex`/`ChainIndexChanges`). Its internals change: the committed mutable delegate becomes a new in-place, count-augmented `int` B+ tree (working name **`UnorderedLookupTree`**); the transactional diff (`UnorderedIntArrayChanges`) is replaced by a path-copying copy-on-write layer mirroring the `TransactionalIntBPlusTree` model shipped in part A of #760. `UnorderedLookup` is retained **only as the immutable flattened snapshot DTO** (its dual `int[]` shape is the ideal read representation) that `getArray()` / `getPositions()` / `getRecordIds()` produce — and that `SortedRecordsSupplier` consumes — once per change, cached. **Reads never traverse the tree element-by-element.**

**Tech Stack:** Java 17, evitaDB STM (`TransactionalLayerProducer` / `VoidTransactionMemoryProducer`), RoaringBitmap, JUnit 5 (functional + long-running generational tests). Build via Maven with toolchains (OpenJDK 17).

**Relationship to #760 part A:** This is a sibling of `docs/plans/2026-05-30-bplustree-inverted-range-index.md` (the `InvertedIndex`/`RangeIndex` swap). It reuses the same STM patterns and the same hard-won bug fixes (composite-producer layer sweep, delete-cleanup). It is **independent in scope** — different data structure (a *position-indexed sequence*, not a *key-ordered map*), different consumer (`ChainIndex` sort providers). On-disk storage format is unchanged.

---

## Background — read before starting

Study, in order: `documentation/developer/stm/{overview,core-interfaces,layer-lifecycle,data-structures,rules-and-invariants,testing,debugging}.md`. Reference code: `TransactionalIntBPlusTree.java` (primitive-key path-copying template + COW layer + the composite-producer/delete-cleanup fixes from #760 part A), `UnorderedLookup.java` (current delegate — the thing being replaced), `TransactionalUnorderedIntArray.java` (the façade to preserve), `UnorderedIntArrayChanges.java` (current diff layer — being replaced), `ChainIndex.java` + `ChainIndexChanges.java` (the only consumers), `SortedRecordsSupplier.java` (the read boundary).

**Project rules (CLAUDE.md / code-style.md):** tabs for indent; `final` locals; explicit types (no `var`); `@Nonnull`/`@Nullable`; Markdown JavaDoc (no HTML); **no streams in perf-critical loops** — allocation-optimized loops instead; **never box primitives** (no `AbstractList<Integer>.sort`, no `Objects.hash` on primitives, no `Integer` keys); pre-size `StringBuilder`; defensive design — unreachable branches throw `GenericEvitaInternalError`. **Never run git stash/reset/checkout/commit/push without Johnny's permission.** This plan commits per task on the feature branch — pre-authorized for *this* branch; **do not push**, **do not** run destructive git.

**Build/test commands** (from repo root):
- Compile engine: `mvn -q -pl evita_engine -am test-compile`
- One functional test class: `mvn -q -pl evita_test/evita_functional_tests -am test -Dtest=<Class>` (append `#<method>` for one method)
- Long-running (generational) tests: module `evita_test/evita_long_running_tests`, run via `-P longRunning`. **Beware** the module hardcodes `<skipTests>true</skipTests>` (not `${skipTests}`) — `-DskipTests=false` is ignored; the `longRunning` profile is mandatory, and `-Dsurefire.failIfNoSpecifiedTests=false` will make a *skipped* run exit 0 (a false-positive trap — verify tests actually ran).

---

## The problem — why the current structure stalls

`UnorderedLookup` maintains a **bidirectional bijection** between a record-id set and a *position permutation*, as two parallel `int[]` arrays indexed by the same `i`:

- `recordIds[i]` — record ids in **ascending sorted** order (binary-searchable),
- `positions[i]` — the position of `recordIds[i]` in the logical (sort) order.

`ChainIndex` stores chains as `TransactionalMap<Integer, TransactionalUnorderedIntArray>`; `isConsistent()` targets a **single chain** (`numberOfChains <= 1`), so the steady state is one chain holding all `N` records.

### CPU: positions are dense absolute integers ⇒ `O(N)` per write ⇒ `O(N²)` load

| op (façade → delegate) | current cost | cause |
|---|---|---|
| `indexOf` → `findPosition(id)` | `O(log N)` ✅ | binary search on sorted `recordIds` |
| `getRecordIds()` | `O(1)` | returns sorted array |
| `getArray()`/`getPositions()` | `O(N)` memoized | full rebuild |
| `get` → `getRecordAt(pos)` | **`O(N)`** ❌ | linear scan of `positions` |
| `add`/`addOnIndex` → `addRecord` | **`O(N)`** ❌ | renumber every position ≥ insert point + 2 array splices |
| `remove` → `removeRecord` | **`O(N)`** ❌ | decrement every position > removed + 2 array splices |

Because positions are stored as an explicit dense `0..N-1` numbering, every insert/delete renumbers a suffix. Loading one chain to `N` is `O(N²)`.

### GC: `O(N²)` bytes routed through G1's worst path (the dominant pain)

Three stacked allocation sources:

1. **Dual full-array realloc per write.** `addRecord` calls `ArrayUtils.insertIntIntoArrayOnIndex` *twice* (recordIds + positions), each a fresh `int[N+1]` + copy; `removeRecord` symmetrically. `memoizedUnorderedArray` (another `int[N]`) is nulled and rebuilt. Loading one chain to `N` allocates and immediately garbages:
   `Σ_{k=1}^{N} 2·4k ≈ 4N²` bytes — **~400 TB at N=10M**.
2. **Autoboxing in the sort.** The constructor / `appendRecords` / `removeRange` sort via `new IntArrayWrapper(...).sort(Comparator.comparing(o -> unorderedArray[o]))` over an `AbstractList<Integer>` — **`O(N log N)` `Integer` boxings** per rebuild. Every `ChainIndexChanges.getUnorderedLookup()` global rebuild pays this.
3. **Boxed map keys.** `chains` is `TransactionalMap<Integer,…>` — every chain-head PK boxed (`O(#chains)`, minor).

**The humongous angle (the sharpest point).** Under G1 the humongous threshold is half the region size: with `-Xmx80g` region=32 MB ⇒ threshold 16 MB ⇒ an `int[]` is humongous at **≥4M elements**; with a 5 GB heap region≈4 MB ⇒ threshold≈2 MB ⇒ humongous at **≥0.5M elements**. So at every scale we test, `recordIds`/`positions`/`memoized` are **humongous objects**: never TLAB-allocated (always slow path), never moved/compacted (pinned), each allocation can trigger a concurrent cycle, and they fragment the old gen. The array design is therefore `O(N²)` bytes through the *worst* allocation path G1 has.

---

## The consumers — and the read boundary that must not move

`UnorderedLookup` exists to feed exactly one consumer family: `ChainIndex` sort providers. In `ChainIndexChanges` the `SortedRecordsSupplier` (and its `Reference…` variant) is built from **flat primitive arrays + a bitmap, handed by reference**:

```java
new SortedRecordsSupplier(id, unorderedLookup.getArray(), unorderedLookup.getPositions(), recordIds, …)
//                            int[] permutation      int[] positions          Bitmap
```

`SortedRecordsSupplier` (`implements SortedRecordsProvider`) holds `@Getter int[] sortedRecordIds`, `int[] recordPositions`, `Bitmap allRecords`. Once built, **queries run against those flat arrays** — tight, cache-line-friendly, `O(1)`-indexed loops (`O(M log N)` to order `M` matched records). The supplier is cached in `ChainIndexChanges` and reset only on a predecessor change (`reset()`), via `getAscendingOrderRecordsSupplier()` / `getDescendingOrderRecordsSupplier()` (the descending case uses `ArrayUtils.reverse(getArray())` + `invert(getPositions())`).

**This yields the overriding design invariant (reads are king):**

> **INV-READ — flat-array snapshot at the supplier boundary.** The mutable tree is a *write accumulator + cheap flattener*. At the supplier boundary it is flattened **once** (`O(N)`, cached) into the same contiguous `int[]` layout the supplier consumes today (`sortedRecordIds`, `recordPositions`, `allRecords`). Queries then operate on arrays **exactly as now** — byte-for-byte identical hot loop. The tree is **never** walked element-by-element to answer a query. Reading *through* the tree (`O(M log N)` pointer-chasing descents) is explicitly forbidden.

Under INV-READ, steady-state read performance cannot regress (same arrays, same loops); it improves marginally at *build* time because we delete the boxing (source 2) and the humongous temporaries (sources 1 & 3).

---

## Design decision & rationale

**Chosen structure:** a **count-augmented (order-statistic) `int` B+ tree with implicit positions**, plus a `recordId → leaf` secondary index and a `RoaringBitmap` of present ids. Behind the retained `TransactionalUnorderedIntArray` façade:

- **committed state** = `UnorderedLookupTree`, mutated **in place** (warm-up / non-transactional path);
- **transactional state** = a **path-copying COW layer** (mirrors `TransactionalIntBPlusTree`), entered only inside a transaction;
- **read snapshot** = a flattened `UnorderedLookup` (dual `int[]`) / the three flat arrays, produced on demand and cached by `ChainIndexChanges`.

The tree stores the sequence ordered by **logical position**; every internal node carries the **element count of its subtree**. The secondary `recordId → leaf` index supports `rank`/`delete` by value; the `RoaringBitmap` exposes the sorted id set for `getRecordIds()`.

### Why implicit positions (two independent arguments)

1. **CPU:** positions derived from subtree counts mean an insert/delete re-stamps counts only along the root→leaf path — `O(log N)` — and *no suffix renumber*. (Your "only sibling PKs in the container / sibling containers need updating on split·steal·merge" intuition is exactly this — and it is **only** true under implicit positioning.)
2. **GC under COW:** with **absolute** positions, an insert shifts the label of every later element, so every node holding a shifted element must be copied ⇒ `O(N)` node allocations per insert — the same wall, one layer down. With **implicit** positions only the path's nodes are re-stamped ⇒ `O(log N)` node allocations. Implicit positioning is what bounds COW allocation to the path.

### Why in-place (committed) + COW (transactional) dispatch

`UnorderedLookup` is the *non-transactional* delegate; warm-up bulk load runs **outside any transaction**. In-place leaf mutation shifts within a fixed-capacity block and allocates **nothing until a split** ⇒ amortized `≈1/B` node allocations per insert. COW is paid **only** when transactional isolation actually requires it. This mirrors the `transactionalLayer`-flag read/write dispatch already built for `InvertedIndex`/`RangeIndex` in part A.

### Why small fixed-capacity primitive `int` nodes

A node holds `int[B]` (B≈64 ⇒ 256 bytes): TLAB-allocated, dies young, cleaned in minor GC. **The humongous path disappears from the write path entirely** — turning ~400 TB of humongous churn into tens of MB of young-gen traffic. Build on the non-boxing `int` lineage so there is zero autoboxing anywhere; keep the secondary index `int`-keyed (never `HashMap<Integer,…>`).

### Why **not** sqrt-decomposition

A tiered vector (`O(√N)`) is allocation-friendly and was attractive as a "simplest warm-up fix". It is **rejected** because both phases must be write-performant, and the **transactional** write path makes internal `findPosition`/`indexOf` calls (today `O(log N)`) that sqrt would regress to `O(√N)`. Since reads are flattened to arrays (INV-READ), the structure's internal random-access complexity is the discriminator — and the tree's uniform `O(log N)` wins. Sqrt buys nothing on reads and loses on transactional writes.

### Two-phase behaviour (read-safe by construction)

- **Warm-up:** pure load, no sort queries ⇒ supplier never built during load ⇒ flatten cost paid only at the first post-load query. Writes hit the in-place tree (small `int` blocks, no humongous).
- **Transactional:** a commit invalidates the cached supplier; the next query flattens **once** (merge committed-tree + COW overlay → contiguous `int[]`, `O(N)` — same cadence as today's `getMergedArray`, minus boxing), caches it; all subsequent reads run at full array speed until the next change.

*Future headroom (not in scope):* because reads always go through a cached flat snapshot, the transactional flatten can later be made **incremental** (patch only changed runs) with no change to the query hot path.

---

## Hard invariants this plan MUST enforce

| # | Invariant |
|---|---|
| **INV-READ** | Flat-`int[]` snapshot at the supplier boundary; tree never traversed per-read-element (see above). |
| **INV-IMPLICIT** | Positions are implicit (subtree counts); no absolute position is ever stored in a node or the secondary index. |
| **INV-DISPATCH** | In-place mutation when committed (non-transactional); path-copying COW only inside a transaction. |
| **INV-NOHUGE** | No `O(N)`-sized array on the write path; nodes are small fixed-capacity `int[B]`. The only `O(N)` array is the cached read snapshot. |
| **INV-NOBOX** | Zero primitive boxing anywhere in the structure or its iteration. |

### STM invariants (inherited from #760 part A — apply verbatim)

The tree nodes are `TransactionalLayerProducer`s; `TransactionalUnorderedIntArray` is the composing producer. Reuse the part-A learnings:

- **INV-1** stable unique `getId()` via `TransactionalObjectVersion.SEQUENCE.nextId()`; the façade's transactional contract unchanged.
- **Composite-producer layer sweep** — the bug class from part A (a `VoidTransactionMemoryProducer` value whose `getTransactionalMemoryLayerIfExists(...) == null` orphans its children's layers): the new tree's `removeLayer` must recurse the whole node graph (size/root/node/values), and discard-on-delete must not guard on a non-null layer. **Confirm the part-A fixes are present in `TransactionalIntBPlusTree` before cloning its layer model.**
- **Delete-cleanup** — deleting an entry whose value was modified in the same transaction must release the value's diff layer (no `StaleTransactionMemoryException`).
- `verifyLayerWasFullySwept()` must pass after every transactional test.

---

## Complexity & allocation — target vs. today

| op | today (CPU) | proposed (CPU) | today (alloc/op) | proposed (alloc/op) |
|---|---|---|---|---|
| insert (`add`/`addOnIndex`) | `O(N)` | `O(log N)` | 2×`int[N]` (humongous) | in-place: ~`1/B` node; COW: `O(log N)` small nodes |
| remove | `O(N)` | `O(log N)` | 2×`int[N]` (humongous) | as above |
| `indexOf`/`findPosition` | `O(log N)` | `O(log N)` | 0 | 0 |
| `get`/`getRecordAt` | `O(N)` | `O(log N)` | 0 | 0 |
| `getLastRecordId` / `getLength` | `O(1)`/`O(1)` | `O(1)` | 0 | 0 |
| `getRecordIds()` | `O(1)` | `O(1)` (bitmap) | 0 | 0 |
| flatten for supplier | `O(N log N)` + boxing | `O(N)` walk + primitive argsort | `int[N]` + `N`×`Integer` | one `int[N]`, no boxing |
| **bulk load to N** | **`O(N²)` / ~400 TB bytes** | **`O(N log N)` / tens of MB** | — | — |

Query-time reads (the hot path): **identical** flat-array loops in both columns (INV-READ).

---

## API contract to preserve

`TransactionalUnorderedIntArray` public surface (all must keep identical semantics): `getPositions()`, `getRecordIds()` (`Bitmap`), `get(index)`, `getLastRecordId()`, `getArray()`, `getSubArray(start,end)`, `add(prev,rec)`, `addOnIndex(index,rec)`, `addAll(prev,recs…)`, `appendAll(recs…)`, `remove(rec)`, `removeAll(recs…)`, `removeRange(start,end)`, `getLength()`, `isEmpty()`, `indexOf(rec)`, `contains(rec)`, `iterator()` (`PrimitiveIterator.OfInt`), `hashCode`/`equals`/`toString`, plus the `TransactionalLayerProducer`/`createLayer()` contract. `ChainIndex.getUnorderedLookup()` keeps returning an `UnorderedLookup` snapshot (now produced by flattening). No consumer signature changes.

---

## Phase 0 — Safety net & baseline

**Task 0.1 — Establish a green baseline (read-only).** Run the existing `UnorderedLookupTest`, `ChainIndexTest` (functional) and `LongRunningChainIndexTest` (generational, `-P longRunning`) on the current code; record pass/fail and timings. Do not change code.

**Task 0.2 — Characterization tests (lock current behavior).** Before touching internals, add focused tests over `TransactionalUnorderedIntArray` covering every public method on a non-trivial sequence, both committed and inside a transaction (`assertStateAfterCommit`), **including**: insert-at-head (`previousRecordId == MIN_VALUE`), insert-after-known, `addOnIndex`, `appendAll`, `removeRange`, `get`/`indexOf`/`getLastRecordId`, and `getArray()`/`getPositions()`/`getRecordIds()` alignment (the three must stay mutually consistent — `getArray()[positions[i]] == recordIds[i]`). These tests must stay green through every later phase.

**Task 0.3 — GC/throughput micro-benchmark harness.** Add a `@Tag(SLOW)` warm-up-style bench (sibling to `EvitaWarmUpInsertionTest`) that drives one chain to a large `N` with insert/remove churn and reports throughput + allocation (via `-Xlog:gc*` / `-verbose:gc` and `com.sun.management` allocated-bytes if available). This produces the before/after numbers proving the win. Capture the "before" run.

---

## Phase 1 — `UnorderedLookupTree`: in-place count-augmented `int` B+ tree (non-transactional core)

**Task 1.1 — Failing tests first.** Port the `UnorderedLookup` semantics suite to `UnorderedLookupTree` (rank/select/insert-after/insert-at/delete/last/size/flatten), plus order-statistic-specific cases (select after random insert/delete sequences vs. an `ArrayList<Integer>` oracle).

**Task 1.2 — Implement the tree (in-place only).** Nodes = fixed-capacity `int[B]`; internal nodes carry subtree counts. Implement `select(k)`, `rank(id)` (via `recordId → leaf` index + count sum to root), `insertAfter(id,new)`, `insertAt(k,·)`, `delete(id)`, `getLast`, `size`, maintained `RoaringBitmap` of present ids. **Enforce INV-IMPLICIT / INV-NOHUGE / INV-NOBOX.** Add an **append/bulk-load fast path** (fill leaves to capacity before splitting → ~100% fill, `N/B` leaf allocations) to mirror `appendAll`/`appendRecords` and avoid rightmost-split half-empty-leaf churn.

**Task 1.3 — Flatten.** Implement `toSnapshot()` producing the contiguous `int[] permutation` (in-order walk), `int[] sortedRecordIds` + aligned `int[] positions` (one primitive argsort — **no boxing**), and the `Bitmap`. This is the only `O(N)` array the structure produces. Add equivalence tests: `toSnapshot()` of the tree == the `UnorderedLookup` built from the same operations.

**Done:** all Phase-1 tests green; engine `test-compile` clean; bench shows linear (not quadratic) in-place insert and no humongous allocations on the write path.

---

## Phase 2 — Transactional COW layer (mirror `TransactionalIntBPlusTree`)

**Task 2.1 — STM test matrix first (failing).** Apply the part-A matrix: modify-then-delete in one txn (delete-cleanup), composite-producer sweep (`verifyLayerWasFullySwept`), interleaved insert/remove with commit, abort/rollback leaves committed state intact, concurrent layers isolated. Include the `previousRecordId == MIN_VALUE` head-insert and `removeRange` under transaction.

**Task 2.2 — Implement COW dispatch.** Add the path-copying layer with the `transactionalLayer`-flag read/write dispatch (INV-DISPATCH). Carry the composite-producer `removeLayer` recursion and delete-cleanup fixes verbatim from `TransactionalIntBPlusTree`. **Confirm those fixes exist in the source before cloning.**

**Done:** STM matrix green; `verifyLayerWasFullySwept()` passes; no `StaleTransactionMemoryException`.

---

## Phase 3 — Swap `TransactionalUnorderedIntArray` internals

**Task 3.1 — Replace delegate + diff.** Swap the `UnorderedLookup lookup` field for `UnorderedLookupTree`; replace `UnorderedIntArrayChanges` with the COW layer. Keep every façade method's signature and semantics (see API contract). `getArray()`/`getPositions()`/`getRecordIds()` delegate to `toSnapshot()`. Retain `UnorderedLookup` as the immutable snapshot DTO consumed by `SortedRecordsSupplier`; drop/deprecate its mutators (or keep them solely for snapshot construction).

**Task 3.2 — Characterization + STM suites green.** Phase-0 characterization, `UnorderedLookupTest`, and the new STM suites must pass unchanged against the new internals.

**Done:** façade behavior identical; `mvn -pl evita_engine -am test-compile` clean.

---

## Phase 4 — `ChainIndex` integration & read-path verification

**Task 4.1 — `ChainIndexChanges` flatten path.** Ensure `getUnorderedLookup()` and the ascending/descending supplier builders consume `toSnapshot()` output; verify the descending path (`ArrayUtils.reverse` + `invert(positions)`) still produces identical arrays. **Assert INV-READ:** the supplier is built from flat arrays, never from per-element tree reads. Confirm cache/reset cadence is unchanged (rebuild only on predecessor change).

**Task 4.2 — Downstream regression.** Run `ChainIndexTest` and every functional test that orders by a predecessor/chain attribute (sort-by-reference-attribute, `ReferenceSortedRecordsProvider`). Reads must be byte-identical; add an assertion that a sorted query result over a fixed dataset matches the pre-change result exactly.

**Done:** all chain-ordering functional tests green.

---

## Phase 5 — Generational, integration & performance validation

**Task 5.1 — Generational long-running.** Run `LongRunningChainIndexTest` (`-P longRunning`), extended with a high-cardinality single-chain generation (drive `N` large) to exercise the order-statistic paths and STM sweep across generations. Verify the actually-ran (not skipped) trap.

**Task 5.2 — Perf + GC proof.** Re-run the Phase-0.3 bench. Required outcomes: insert/remove throughput is **linear** in op count (no `O(N²)`); allocated bytes per op are bounded by `O(log N)` small nodes (COW) / amortized `O(1)` (in-place); **no humongous allocations on the write path** (confirm via GC logs); **read/query latency unchanged** vs. baseline on the same sorted-query workload. Record before/after.

**Task 5.3 — Full targeted suite + engine build.** `mvn -pl evita_engine -am test-compile` + the chain/sort functional classes + the generational test, all green.

---

## Done criteria

- All Phase 0–5 tasks green; `verifyLayerWasFullySwept()` passes on every transactional test.
- INV-READ, INV-IMPLICIT, INV-DISPATCH, INV-NOHUGE, INV-NOBOX all hold (assertions / review).
- Bench proves: linear writes, no humongous on write path, **read latency not degraded** (the king constraint).
- `TransactionalUnorderedIntArray` public API and `ChainIndex.getUnorderedLookup()` return type unchanged; on-disk format untouched.
- No streams in perf-critical loops; no boxing; tabs; JavaDoc on all new types/methods.

---

## Risk register

| Risk | Mitigation |
|---|---|
| Reading through the tree sneaks in and degrades query latency | INV-READ is a hard gate; Phase 4 asserts the supplier is built only from flat arrays; bench compares read latency to baseline. |
| Absolute positions creep into a node/secondary index | INV-IMPLICIT; code review + a test that an insert in the middle does **not** rewrite later elements' stored state (only path counts). |
| COW layer regresses to `O(N)` allocation | INV-DISPATCH + INV-IMPLICIT; STM bench asserts `O(log N)` allocations/op inside a transaction. |
| Composite-producer / delete-cleanup STM bug recurs (part-A class) | Clone the *fixed* `TransactionalIntBPlusTree` layer model; Phase-2 matrix + `verifyLayerWasFullySwept`. |
| Append/sequential load causes half-empty-leaf churn | Dedicated bulk-load fast path (Task 1.2); bench the append pattern specifically. |
| Long-running module silently skips (false green) | Use `-P longRunning`; verify tests actually ran (not the `failIfNoSpecifiedTests` trap). |
| Snapshot flatten becomes a per-read cost | Cache cadence unchanged (rebuild only on predecessor change); warm-up pays nothing until first query; future incremental-flatten noted as headroom. |

---

## Out of scope

- StoragePart granularity / on-disk serialization changes (#760 part B).
- Incremental (patch-only) transactional flatten — noted as future headroom; parity is met without it.
- Changing `SortedRecordsSupplier` / `SortedRecordsProvider` (read consumer) — unchanged by design.
