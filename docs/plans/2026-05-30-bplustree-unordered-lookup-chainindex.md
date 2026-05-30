# Two-tree (order-key coupled) B+ backing for `UnorderedLookup` / `ChainIndex` — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** Replace the dual-`int[]` backing of `io.evitadb.index.array.UnorderedLookup` (the mutable delegate behind `TransactionalUnorderedIntArray`, used by `ChainIndex`) with **two coordinated, count-augmented B+ trees coupled by a stable container order-key**, so that a single insert/remove touches only a root→leaf path in each tree instead of reallocating two full-length arrays and renumbering a suffix. This removes the `O(N²)` CPU **and** the `O(N²)`-bytes / humongous-allocation GC wall that currently stalls high-cardinality chains — in **both** the warm-up (non-transactional) and transactional phases — and additionally makes **commits of edits to a large chain `O(edits·log N)`** (structural sharing) instead of `O(N)` rebuild-from-array, **without degrading read performance**, which is the overriding constraint.

**Architecture (two trees + order-key):** `TransactionalUnorderedIntArray` is retained as the public façade (its API is consumed across `ChainIndex`/`ChainIndexChanges`) and becomes the **composite producer** that coordinates two child `TransactionalLayerProducer` trees:

1. **Value index** — a no-boxing primitive **`int`(recordId) → `long`(order-key) B+ tree** (new tree flavour). Keyed by record id (ascending), so it doubles as the source of `getRecordIds()` for free. Answers *"which container holds this record id?"*.
2. **Position tree** — a **count-augmented (order-statistic), `long`(order-key)-keyed B+ tree** whose **leaves are containers** (each holds up to `B` record ids in logical order) and whose internal nodes carry both the separator order-keys *and* the **record-id count of each child subtree**. Answers *"what is at position k?"* (count descent) and *"what is the prefix count before this container?"* (order-key descent).

Both trees mutate **in place** when committed (warm-up / non-transactional) and **path-copy** inside a transaction (`transactionalLayer`-flag dispatch, mirroring `TransactionalIntBPlusTree` + the part-A STM fixes). The previous `UnorderedIntArrayChanges` diff overlay is **removed** — transactional isolation now comes from the path-copying node layers of the two trees, composed by the façade. `UnorderedLookup` is retained **only as the immutable flattened snapshot DTO** that `getArray()` / `getPositions()` / `getRecordIds()` produce — and that `SortedRecordsSupplier` consumes — once per change, cached. **Reads never traverse either tree element-by-element.**

**Tech Stack:** Java 17, evitaDB STM (`TransactionalLayerProducer` / `VoidTransactionMemoryProducer`), JUnit 5 (functional + long-running generational tests), `com.carrotsearch.hppc` for no-boxing primitive maps in the in-place path. Build via Maven with toolchains (OpenJDK 17).

**Relationship to #760 part A:** Sibling of `docs/plans/2026-05-30-bplustree-inverted-range-index.md` (the `InvertedIndex`/`RangeIndex` swap). It reuses the same STM patterns and the same hard-won bug fixes (composite-producer layer sweep, delete-cleanup). On-disk storage format is unchanged.

---

## Background — read before starting

Study, in order: `documentation/developer/stm/{overview,core-interfaces,layer-lifecycle,data-structures,rules-and-invariants,testing,debugging}.md`. Reference code: `TransactionalIntBPlusTree.java` (primitive-key path-copying template + COW layer + the composite-producer/delete-cleanup fixes from #760 part A — **the structural template for both new trees**), `TransactionalLongBPlusTree.java` (the `long`-key flavour — closest sibling for the order-key-keyed trees), `UnorderedLookup.java` (current delegate — becomes snapshot-only DTO), `TransactionalUnorderedIntArray.java` (the façade to preserve — becomes the composite producer), `UnorderedIntArrayChanges.java` (current diff overlay — **removed**), `ChainIndex.java` + `ChainIndexChanges.java` (the only consumers), `SortedRecordsSupplier.java` (the read boundary).

**Project rules (CLAUDE.md / code-style.md):** tabs for indent; `final` locals; explicit types (no `var`); `@Nonnull`/`@Nullable`; Markdown JavaDoc (no HTML); **no streams in perf-critical loops**; **never box primitives** (the value index is a primitive `int→long` tree, never `TransactionalIntBPlusTree<Long>`); pre-size `StringBuilder`; defensive design — unreachable branches throw `GenericEvitaInternalError`. **Never run git stash/reset/checkout/commit/push without Johnny's permission.** This plan commits per task on the feature branch — pre-authorized for *this* branch (Johnny confirmed); **do not push**, **do not** run destructive git.

**Build/test commands** (from repo root):
- Compile engine: `mvn -q -pl evita_engine test-compile`; **install before running functional tests** (`mvn -q -pl evita_engine install -DskipTests`) so the test module sees fresh classes — do **not** use `-am` (re-triggers protoc gRPC errors).
- One functional test class: `mvn -q -pl evita_test/evita_functional_tests test -Dtest=<Class> -Dtest.tag.policy=warn -Dsurefire.failIfNoSpecifiedTests=false`.
- Long-running (generational) tests: module `evita_test/evita_long_running_tests`, run via `-P longRunning`. **Beware** the module hardcodes `<skipTests>true</skipTests>` — the `longRunning` profile is mandatory, and `-Dsurefire.failIfNoSpecifiedTests=false` will make a *skipped* run exit 0 (a false-positive trap — verify tests actually ran).

---

## The problem — why the current structure stalls

`UnorderedLookup` maintains a **bidirectional bijection** between a record-id set and a *position permutation*, as two parallel `int[]` arrays indexed by the same `i`:

- `recordIds[i]` — record ids in **ascending sorted** order (binary-searchable),
- `positions[i]` — the position of `recordIds[i]` in the logical (sort) order.

`ChainIndex` stores chains as `TransactionalMap<Integer, TransactionalUnorderedIntArray>`; `isConsistent()` targets a **single chain** (`numberOfChains <= 1`), so the steady state is one chain holding all `N` records.

### CPU: positions are dense absolute integers ⇒ `O(N)` per write ⇒ `O(N²)` load

Because positions are stored as an explicit dense `0..N-1` numbering, every insert/delete renumbers a suffix (`addRecord`/`removeRecord` are `O(N)`; `getRecordAt` is an `O(N)` scan). Loading one chain to `N` is `O(N²)`.

### GC: `O(N²)` bytes routed through G1's worst path (the dominant pain)

`addRecord` calls `ArrayUtils.insertIntIntoArrayOnIndex` *twice* (recordIds + positions), each a fresh `int[N+1]`; loading one chain to `N` allocates and garbages `Σ 2·4k ≈ 4N²` bytes — **~400 TB at N=10M**. The constructor / `appendRecords` / `removeRange` also sort via an `AbstractList<Integer>` (`O(N log N)` `Integer` boxings). Under G1 the humongous threshold is half the region size, so at every tested scale `recordIds`/`positions`/`memoized` are **humongous objects**: never TLAB-allocated, never compacted (pinned), each allocation can trigger a concurrent cycle. The array design is `O(N²)` bytes through the *worst* allocation path G1 has.

### Commit: rebuild-from-array per transaction

`ChainIndex.chains` is a `TransactionalMap<Integer, TransactionalUnorderedIntArray>` whose reconstruction function is `TransactionalUnorderedIntArray::new(int[])`. Every commit touching a chain rebuilds the whole value from a fresh `int[]` (`UnorderedIntArrayChanges.getMergedArray()`, also humongous at scale) — `O(N)` + boxing per commit even when a transaction changed a handful of records.

---

## The consumers — and the read boundary that must not move

`UnorderedLookup` exists to feed exactly one consumer family: `ChainIndex` sort providers. In `ChainIndexChanges` the `SortedRecordsSupplier` (and its `Reference…` variant) is built from **flat primitive arrays + a bitmap, handed by reference**:

```java
new SortedRecordsSupplier(id, unorderedLookup.getArray(), unorderedLookup.getPositions(), recordIds, …)
//                            int[] permutation      int[] positions          Bitmap
```

`SortedRecordsSupplier` (`implements SortedRecordsProvider`) holds `@Getter int[] sortedRecordIds`, `int[] recordPositions`, `Bitmap allRecords`. Once built, **queries run against those flat arrays** — tight, cache-line-friendly, `O(1)`-indexed loops. The supplier is cached in `ChainIndexChanges` and reset only on a predecessor change (`reset()`); the descending case uses `ArrayUtils.reverse(getArray())` + `invert(getPositions())`.

**This yields the overriding design invariant (reads are king):**

> **INV-READ — flat-array snapshot at the supplier boundary.** The two trees are a *write accumulator + cheap flattener*. At the supplier boundary they are flattened **once** (`O(N)`, cached) into the same contiguous `int[]` layout the supplier consumes today (`sortedRecordIds`, `recordPositions`, `allRecords`). Queries then operate on arrays **exactly as now** — byte-for-byte identical hot loop. Neither tree is **ever** walked element-by-element to answer a query. Reading *through* a tree (`O(M log N)` pointer-chasing descents) is explicitly forbidden.

Under INV-READ, steady-state read performance cannot regress (same arrays, same loops); it improves marginally at *build* time because we delete the boxing and the humongous temporaries.

---

## Design decision & rationale — two trees coupled by an order-key

To answer fast writes you need **two views of the same data**: a *by-value* view ("where is record id 12345?") and a *by-position* view ("what is at slot 7?", "how many records precede this?"). The old array kept both as two parallel arrays and paid `O(N)` per write to keep absolute positions dense. The chosen structure keeps the two views as two B+ trees and makes positions **implicit** so a write touches only one path per tree.

### The two trees and the order-key coupling

- **Order-key (`long`):** a stable, widely-spaced `long` that identifies a **container's slot** in the logical order. Order-keys are monotonically increasing in logical order, so *order-key order ≡ logical order*. Record ids inside a container share the container's order-key; their within-container order is the container's array offset. Order-keys are minted **only when a container splits** (a fresh key in the gap between neighbours), so order-key churn is amortized by a factor of `B` versus per-record keys.

- **Value index** = primitive `int`(recordId) → `long`(order-key) B+ tree. `get(recordId)` → the order-key of the record's container. Keyed by record id ascending ⇒ an in-order key walk yields `getRecordIds()` **already sorted**, no extra sort/bitmap.

- **Position tree** = count-augmented `long`(order-key)-keyed B+ tree. Leaves are **containers** (`int[B]` of record ids in logical order); internal nodes store separator order-keys **and** the record-id count of each child subtree. Two descents:
  - **by order-key** (for `findPosition`): summing the child counts to the *left* of the descent path yields the **prefix count** (records before the container); add the in-container offset (found by an `≤ B` scan of the container).
  - **by position** (for `getRecordAt` / `select`): choose the child whose cumulative count brackets the target — classic order-statistic select.

### Operation mapping

| façade op | mechanism | cost |
|---|---|---|
| `findPosition(id)` | value index `get` → order-key; position-tree order-key descent → prefix; `≤ B` scan for offset | `O(log N)` |
| `getRecordAt(pos)` / `get` | position-tree count descent → container → `recordIds[offset]` | `O(log N)` |
| `getLastRecordId` / `getLength` | rightmost container / root subtree count | `O(log N)` / `O(1)` |
| `add(prev,id)` | value index `get(prev)` → order-key → container; insert after; counts++ along cursor; split mints order-key + re-stamps moved ids in value index | `O(log N)` |
| `addOnIndex(k,id)` | position-tree count descent → container; insert; `put(id, containerOrderKey)` in value index | `O(log N)` |
| `remove(id)` | value index `get` → order-key → container; remove; counts−− along cursor; steal/merge re-stamps moved ids; value index `remove(id)` | `O(log N)` |
| `getRecordIds()` | value-index in-order key walk (already ascending) | `O(N)` walk |
| `getArray()` | position-tree in-order leaf walk (order-key order ≡ logical order) | `O(N)` walk |
| `getPositions()` | one flatten pass + primitive `int→int` map (no boxing) | `O(N)` |
| **bulk load to N** | sequential append (or bottom-up bulk build) | `O(N log N)` / tens of MB |
| **commit of `e` edits** | path-copy `e` paths in each tree (structural sharing) | `O(e·log N)` |

Within-container inserts **do not** touch sibling record ids in the value index (their order-key is unchanged; the offset is recomputed on demand). Only **container split / steal / merge** re-stamps the order-keys of the `≤ B` records that physically moved — this is the bounded, lazy-at-commit update Johnny's original sketch called for.

### Why implicit positions (two independent arguments)

1. **CPU:** positions derived from subtree counts mean an insert/delete re-stamps counts only along one root→leaf path — `O(log N)` — and *no suffix renumber*.
2. **GC under COW:** with **absolute** positions an insert shifts every later label ⇒ every node holding a shifted element must be copied ⇒ `O(N)` node allocations per insert. With **implicit** positions only the path's nodes are re-stamped ⇒ `O(log N)` node allocations. Implicit positioning is what bounds COW allocation to the path.

### Why in-place (committed) + path-copying (transactional) dispatch

Warm-up bulk load runs **outside any transaction**: in-place leaf mutation shifts within a fixed-capacity block and allocates **nothing until a split** ⇒ amortized `≈1/B` node allocations per insert. Path-copying is paid **only** inside a transaction, and only along the touched paths. Mirrors the `transactionalLayer`-flag read/write dispatch from part A.

### Why two key-ordered trees rather than one position-ordered tree + parent pointers

A position-ordered tree cannot be descended *by value*, so `findPosition`/`remove(id)` would need a `recordId → node` secondary index — and node identities are exactly what path-copying churns, so that index cannot hold node references across COW versions without parent-pointer materialization (which breaks structural sharing) or a per-version `int→node` map (heavy, off-pattern). The order-key indirection sidesteps this entirely: **both** trees are ordinary **key-ordered** B+ trees (descend by key, cursor model, no parent pointers — exactly like `TransactionalIntBPlusTree`), and the value index hands you the key with which to descend the position tree. Full structural sharing, `O(log N)` everything.

### Why a dedicated primitive `int→long` value-index tree (no boxing)

`TransactionalIntBPlusTree<Long>` would box every order-key value (`N` boxed `Long`s — heap + GC churn on the perf-critical structure). Johnny's call: build a **separate primitive `int`-key / `long`-value B+ tree flavour** (copy-paste of the `int`-key template with a `long[]` value block and copy-pasted test suite) rather than box. Zero autoboxing anywhere in either tree.

### Why **not** sqrt-decomposition / the old diff overlay

Sqrt (`O(√N)`) regresses the transactional internal `findPosition`/`indexOf` calls. The old `UnorderedIntArrayChanges` diff overlay works but rebuilds the value from an `int[]` at every commit (`O(N)` + humongous) and is a second, bespoke consistency mechanism alongside the producer model — off-pattern and the source of the part-A composite-producer bugs. The two-tree producer design is uniform STM and gets `O(e·log N)` commits.

### Two-phase behaviour (read-safe by construction)

- **Warm-up:** pure load, no sort queries ⇒ supplier never built during load ⇒ flatten cost paid only at the first post-load query. Writes hit the in-place trees (small blocks, no humongous).
- **Transactional:** a commit invalidates the cached supplier; the next query flattens **once** (`O(N)` in-order walk, no boxing), caches it; all subsequent reads run at full array speed until the next change.

*Future headroom (not in scope):* the flatten can later be made **incremental** (patch only changed runs).

---

## Hard invariants this plan MUST enforce

| # | Invariant |
|---|---|
| **INV-READ** | Flat-`int[]` snapshot at the supplier boundary; neither tree traversed per-read-element. |
| **INV-IMPLICIT** | Positions are implicit (subtree counts). The order-key is a **routing key, not a position** — no absolute position is ever stored in a node or in the value index. |
| **INV-DISPATCH** | In-place mutation when committed (non-transactional); path-copying inside a transaction — for **both** trees. |
| **INV-NOHUGE** | No `O(N)`-sized array on the write path; nodes are small fixed-capacity blocks. The only `O(N)` arrays are the cached read snapshot. |
| **INV-NOBOX** | Zero primitive boxing anywhere in either tree or its iteration; the value index is a primitive `int→long` tree. |
| **INV-COUPLE** | Order-key coherence: every present record id has exactly one value-index entry whose order-key routes (in the position tree) to the container actually holding it. Split/steal/merge re-stamp **all** moved record ids within the same operation. Order-key gap exhaustion is handled by re-spacing — **never** silently dropped (defensive-design rule). |

### STM invariants (inherited from #760 part A — apply verbatim)

The tree nodes are `TransactionalLayerProducer`s; `TransactionalUnorderedIntArray` is the composite producer over the two trees. Reuse the part-A learnings:

- **INV-1** stable unique `getId()` via `TransactionalObjectVersion.SEQUENCE.nextId()`; the façade's transactional contract unchanged.
- **Composite-producer layer sweep** — the bug class from part A: the composite producer's `removeLayer` must recurse the **whole node graph of both trees** (size/root/node/values), and discard-on-delete must not guard on a non-null layer. **The part-A fix `removeLayerRecursively` is confirmed present in `TransactionalIntBPlusTree` (lines 792–818) — clone it for both trees.**
- **Delete-cleanup** — deleting an entry whose value was modified in the same transaction must release the value's diff layer (no `StaleTransactionMemoryException`).
- `verifyLayerWasFullySwept()` must pass after every transactional test.

---

## API contract to preserve

`TransactionalUnorderedIntArray` public surface (all must keep identical semantics): `getPositions()`, `getRecordIds()` (`Bitmap`), `get(index)`, `getLastRecordId()`, `getArray()`, `getSubArray(start,end)`, `add(prev,rec)`, `addOnIndex(index,rec)`, `addAll(prev,recs…)`, `appendAll(recs…)`, `remove(rec)`, `removeAll(recs…)`, `removeRange(start,end)`, `getLength()`, `isEmpty()`, `indexOf(rec)`, `contains(rec)`, `iterator()` (`PrimitiveIterator.OfInt`), `hashCode`/`equals`/`toString`, plus the `TransactionalLayerProducer`/`createLayer()` contract. `ChainIndex.getUnorderedLookup()` keeps returning an `UnorderedLookup` snapshot. No consumer signature changes — **but** the `TransactionalMap` reconstruction in `ChainIndex` changes from `int[]`-based to passing the committed (shared) trees (Phase 4).

---

## Phase 0 — Safety net & baseline ✅ (done)

- **0.1** Green baseline of `UnorderedLookupTest` / `ChainIndexTest` recorded.
- **0.2** Characterization: `UnorderedLookupTreeTest` locks the order-statistic semantics against an `ArrayList` oracle **and** a per-op equivalence check against the array `UnorderedLookup` (2×20k randomized ops, split/collapse paths). Green.
- **0.3** Chain warm-up load test added (`EvitaWarmUpInsertionTest#shouldGenerateLoadOfChainDataInWarmUpPhase`) — the acceptance/perf gate; infeasible against the current `O(N²)` delegate by design.

---

## Phase 1 — Position tree: count-augmented, order-key-keyed B+ tree ✅ (in-place core done; re-key pending)

**Done:** `UnorderedLookupTree` — in-place count-augmented tree with `O(log N)` insert/remove/select, no-boxing flatten, oracle-verified.

**1.4 — Re-key by order-key (pending).** Evolve the in-place tree so internal nodes route by a `long` order-key separator (not raw position), leaves become containers carrying their order-key, and the descent supports **both** by-order-key and by-position. Add order-key minting on split (gap midpoint) and the `≤ B` re-stamp hook. Keep the `ArrayList`/array-delegate oracle green; add order-key-coherence assertions (INV-COUPLE) and a gap-exhaustion / local re-spacing test.

**1.5 — Bulk-load fast path.** Bottom-up build (fill containers to capacity, assign spaced order-keys) so warm-up load and commit-merge are `O(N)` with ~100% fill.

---

## Phase 2 — Value index: primitive `int→long` B+ tree (new flavour)

**Task 2.1 — Failing tests first.** Copy `TransactionalIntBPlusTree`'s test suite, retargeted to `int`-key / `long`-value semantics (insert/get/remove/iterate-ascending, split/merge, STM matrix).

**Task 2.2 — Implement `TransactionalIntToLongBPlusTree`** (working name) — clone the `int`-key template with a primitive `long[]` value block. **No boxing.** Carry the part-A `removeLayerRecursively` sweep + delete-cleanup verbatim.

**Done:** value-index suite green; `verifyLayerWasFullySwept()` passes.

---

## Phase 3 — Transactional path-copying for the position tree + compose the façade

**Task 3.1 — STM matrix first (failing).** Part-A matrix for the position tree: modify-then-delete in one txn (delete-cleanup), composite-producer sweep (`verifyLayerWasFullySwept`), interleaved insert/remove with commit, abort leaves committed state intact, concurrent layers isolated. Include head-insert (`previousRecordId == MIN_VALUE`) and `removeRange` under transaction.

**Task 3.2 — Path-copying node lifecycle for the position tree.** Make its nodes `TransactionalLayerProducer`s with the `transactionalLayer`-flag dispatch (INV-DISPATCH), cloning the template's `createLayer`/`removeLayer`(recursive)/`createCopyWithMergedTransactionalMemory` and cursor-based path-copy.

**Task 3.3 — `TransactionalUnorderedIntArray` becomes the composite producer.** Hold the two trees as fields; route every façade method through them; coordinate the order-key on split/steal/merge (INV-COUPLE). Remove `UnorderedIntArrayChanges` and `TransactionalIntArrayIterator`'s dependence on it. `getArray()`/`getPositions()`/`getRecordIds()` delegate to the flatten. `createCopyWithMergedTransactionalMemory` returns a new façade wrapping the two committed (shared) trees. Retain `UnorderedLookup` as the snapshot DTO only.

**Done:** STM matrix green; `verifyLayerWasFullySwept()` passes; no `StaleTransactionMemoryException`; Phase-0 characterization + `UnorderedLookupTest` green against the new internals.

---

## Phase 4 — `ChainIndex` integration & read-path verification

**Task 4.1 — `TransactionalMap` reconstruction.** Change `ChainIndex.chains` reconstruction from `TransactionalUnorderedIntArray::new(int[])` to a path that passes the committed shared trees (no `int[]` round-trip), so commits are `O(e·log N)`. Verify `getUnorderedLookup()` and the ascending/descending supplier builders consume the flatten; the descending path (`ArrayUtils.reverse` + `invert(positions)`) still yields identical arrays. **Assert INV-READ.** Cache/reset cadence unchanged (rebuild only on predecessor change).

**Task 4.2 — Downstream regression.** Run `ChainIndexTest` and every functional test that orders by a predecessor/chain attribute (`EntityByChainOrderingFunctionalTest`, sort-by-reference-attribute, `ReferenceSortedRecordsProvider`). Reads must be byte-identical; assert a fixed sorted-query result matches the pre-change result exactly.

**Done:** all chain-ordering functional tests green.

---

## Phase 5 — Generational, integration & performance validation

**Task 5.1 — Generational long-running.** Run `LongRunningChainIndexTest` (`-P longRunning`), extended with a high-cardinality single-chain generation to exercise the order-statistic + order-key paths and STM sweep across generations. Verify the actually-ran (not skipped) trap.

**Task 5.2 — Perf + GC proof.** Run `EvitaWarmUpInsertionTest#shouldGenerateLoadOfChainDataInWarmUpPhase`. Required: insert/remove throughput **linear** in op count (no `O(N²)`); allocated bytes/op bounded by `O(log N)` small nodes (txn) / amortized `O(1)` (in-place); **no humongous allocations on the write path** (GC logs); commit of a few edits to a large chain is `O(e·log N)`; **read/query latency unchanged** vs. baseline. Record before/after.

**Task 5.3 — Full targeted suite + engine build.** `mvn -pl evita_engine -am test-compile` + the chain/sort functional classes + the generational test, all green.

---

## Done criteria

- All Phase 0–5 tasks green; `verifyLayerWasFullySwept()` passes on every transactional test.
- INV-READ, INV-IMPLICIT, INV-DISPATCH, INV-NOHUGE, INV-NOBOX, INV-COUPLE all hold (assertions / review).
- Bench proves: linear writes, no humongous on write path, `O(e·log N)` commits, **read latency not degraded** (the king constraint).
- `TransactionalUnorderedIntArray` public API and `ChainIndex.getUnorderedLookup()` return type unchanged; on-disk format untouched.
- No streams in perf-critical loops; no boxing; tabs; JavaDoc on all new types/methods.

---

## Risk register

| Risk | Mitigation |
|---|---|
| Reading through a tree degrades query latency | INV-READ hard gate; Phase 4 asserts the supplier is built only from flat arrays; bench compares read latency to baseline. |
| Order-key incoherence (value index points at the wrong container) | INV-COUPLE; split/steal/merge re-stamp moved ids atomically; oracle + per-op equivalence asserts `getArray()[positions[i]]==recordIds[i]` every op. |
| Order-key gap exhaustion | wide `long` spacing; local re-spacing on exhaustion, lazy global re-spacing at commit; explicit test; never silently dropped. |
| Absolute positions creep into a node/value index | INV-IMPLICIT; review + a test that a mid-insert does not rewrite later elements' stored state (only path counts + moved-id order-keys). |
| Path-copying regresses to `O(N)` allocation | INV-DISPATCH + INV-IMPLICIT; STM bench asserts `O(log N)` allocations/op inside a transaction. |
| Composite-producer / delete-cleanup STM bug recurs (part-A class) | Clone the *fixed* `TransactionalIntBPlusTree` layer model into both trees; Phase-3 matrix + `verifyLayerWasFullySwept`. |
| Two-tree coordination doubles STM surface | One composite producer owns both trees and their joint commit; the order-key is the only coupling and is asserted by INV-COUPLE tests. |
| Append/sequential load causes half-empty-container churn | Bulk-load fast path (Task 1.5); bench the append pattern specifically. |
| Long-running module silently skips (false green) | Use `-P longRunning`; verify tests actually ran (not the `failIfNoSpecifiedTests` trap). |

---

## Out of scope

- StoragePart granularity / on-disk serialization changes (#760 part B).
- Incremental (patch-only) transactional flatten — future headroom; parity is met without it.
- Changing `SortedRecordsSupplier` / `SortedRecordsProvider` (read consumer) — unchanged by design.
