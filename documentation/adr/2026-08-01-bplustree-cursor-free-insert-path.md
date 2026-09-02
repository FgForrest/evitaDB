---
title: Answer the B+ tree insert-boundary asserts from the descent instead of a captured cursor path
date: 2026-08-01
updated: 2026-08-05 16:00
status: accepted
kind: optimization
issues: [1333]
prs: [1356]
areas: [evita_engine/index/bPlusTree]
supersedes: []
superseded-by: []
relates: [2026-07-10-more-optimized-data-structures, 2026-07-31-bulk-ingest-write-path, 2026-08-05-schema-handling-write-path-optimizations]
---

# Free the B+ tree insert and read paths from the cursor path

The transactional B+ tree family — `Bucket`, `Element`, `Long`, `Object`, `IntToLong` — reached its
leaf by building a full root-to-leaf cursor path on every descent, at a measured 192–232 B, when the
path exists only to cascade a split upward. The lazy capture already applied to
`TransactionalIntToLongBPlusTree` is extended to the rest of the family, and the one thing that made
that extension worthless on two of the trees — a correctness assert that consumed the cursor on
*every* successful insert — is removed rather than worked around: both of its operands are now
resolved during the same allocation-free descent that finds the leaf.

## Why

`createCursor(key)` allocates an `ArrayList`, its backing array, a one-element root sibling array, a
`CursorLevel` per level and the `Cursor` itself. A split happens roughly once per `valueBlockSize`
inserts, and a read never splits at all, so on the overwhelming majority of descents that path is
built and thrown away.

`7869c5fdc` / `46b4fecf3f` had already made `TransactionalIntToLongBPlusTree` descend allocation-free
and capture the path only when the leaf could overflow. Issue #1333 asked whether that ports to the
rest of the family. A census (`2026-07-10-more-optimized-data-structures/reports/2026-07-31-bplustree-optimization-portability-census.md`)
found two blockers, and only the second one mattered:

1. `findLeafNode` existed only on `AbstractIntKeyedBPlusTree`. Mechanical — the cursor descent in the
   other trees is `children[node.searchIndex(key)]` in a loop.
2. **`assertInsertBoundaries` consumes the cursor on every successful insert**, not just on splits.
   Lazy capture therefore saves only as much as the asserts do not fire.

How much that second point costs is decided by key order, and for one tree the source states it:
`AbstractPriceListAndCurrencyPriceIndex:486` documents that price-record inserts "land at the right
edge", so **every** `Element` insert is a tail insert and lazy capture alone is worth precisely zero.
`Bucket` keys on unsorted attribute values, so its asserts already skip ~99 % of inserts.

The issue scheduled "resolve the boundary assertions" as a later phase. For `Element` it is a
prerequisite, not a follow-up.

### Previous state

Every descent — read or write — went through `createCursor`. Insert sites then passed that cursor to
`assertInsertBoundaries`, which walked it bottom-up to find the *fence* (the separator bounding the
leaf on the right) and, for head inserts, the predecessor leaf. Read paths that needed nothing but
`cursor.leafNode()` paid for the whole path anyway. The cursor's `ArrayList` was sized
`(int)(Math.log(size()) + 1)` — a *natural* logarithm of the entry count, a quantity unrelated to the
depth of a tree that branches `internalNodeBlockSize` ways, asking for 14 slots against a real depth
of 3 at 1 M entries.

## Options considered

All three answer the same question: how does the insert path satisfy the boundary asserts without a
cursor?

### Option A — resolve both operands during the allocation-free descent (chosen)

`findLeafNodeWithBoundaryContext(key)` reaches the same leaf and, in the same pass, records the fence
(the separator at the deepest level whose descent was not into the rightmost child) and the deepest
level whose descent was not into the leftmost child, from which the predecessor leaf is resolved
lazily.

- **Pros:** costs nothing beyond two locals on a descent that already happens; the returned record is
  scalar-replaced, so the asserts become free; correct by equivalence rather than by approximation,
  because `path[L].siblings()` *is* `n_{L-1}.getChildren()`, so overwriting one local at every level
  where `childIndex < node.getPeek()` lands on the same separator the cursor's bottom-up walk finds
  first.
- **Cons:** a second resolver for the same operands now exists beside the cursor-based one (still used
  by `validateDirtyScope`), and the two can drift; and the "free" part depends on escape analysis.

### Option B — re-descend after the mutation to recompute the operands (declined)

- **Pros:** simplest; the asserts stay correct with no new descent logic.
- **Cons:** a second full descent per insert.
- **Rejected because:** `7869c5fdc` explicitly documents that a path built after `leaf.insert` can
  descend a re-published root, and reinstating that shape on the *corruption-detection* path of a
  defect-prone family is a poor trade. It also costs that extra descent on 100 % of ascending-key
  inserts — exactly the workload it was meant to help. *Revisit if* the root-republication hazard is
  ever closed structurally and the descent stops showing on the profile.

### Option C — run both asserts unconditionally before the mutation (declined)

Any inserted key must sort strictly between the predecessor's last key and the fence whether or not
it becomes a boundary key, so `insertedAt` is not actually needed.

- **Pros:** removes the `insertedAt` branch entirely; strictly stronger checking.
- **Cons:** the head check resolves and reads the predecessor's boundary key on every insert.
- **Rejected because:** for `Bucket` that key is front-coded, so this reinstates the per-insert decode
  that `8b6c2a2e8` had just removed. *Revisit if* boundary keys ever become cheap to read on every
  tree.

### Adjacent options declined

These answer different questions and were considered alongside:

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Maintain a `height` field instead of deriving the path capacity | Adds mutable state updated on root split and root collapse — the structural path, which is where this family's historical defects live — to save a logarithm. The capacity is an `ArrayList` hint, so an under-estimate costs one array grow and never correctness; that asymmetry does not justify a new invariant | The logarithm ever shows on a profile |
| A reusable caller-owned path buffer | Already rejected in `7869c5fdc`: `createCursor` has ~40 call sites and about half return the cursor inside a long-lived iterator, with `CursorWithLevel` sharing the path list during split cascades | The iterator call sites stop sharing the path |
| Fold away `InsertionPosition` (the census's O2) | **Measured at zero.** Trees that construct it and a tree that does not allocate identically per descent — escape analysis already removes it. Presenting a measured-at-zero change as an optimization miscalibrates the next reader, and touching five hot descents for cosmetics is not free | A future JIT change makes the record escape; the census's descent arms would show it |

## Decision

**Chosen: Option A.** It is the only one that makes the asserts free rather than cheaper, and it is
the only one that removes the dependency on key order instead of answering it — which matters because
`Long`'s production key order is unknown and now does not need to be known. The cursor is captured
only when `leaf.isNearlyFull()`, evaluated **before** the mutation; each tree's `isNearlyFull()`
mirrors *its own* `isFull()`, reading `peek` and capacity from the same resolved transactional state.
Read paths that consumed nothing but `cursor.leafNode()` — `getRecordsEqualTo`, `contains`,
`cardinalityOf`, `search`, `markDirty`, `getLongRecordEqualTo` — take a plain `findLeafNode`.
Structural operations (`delete`, `removeRecord`, `consolidate`, `updateParentKeys`, the iterators,
`validateDirtyScope`) keep the cursor, because they read more of the path than the leaf.

Option B wins instead if the root-republication hazard is ever closed and a second descent measures
free; Option C wins if boundary keys stop costing a decode.

## Key technical details

- **Entry points:** `findLeafNode(key)` (allocation-free, read paths) and
  `findLeafNodeWithBoundaryContext(key)` (insert paths) on `TransactionalBucketBPlusTree`,
  `TransactionalElementBPlusTree`, `TransactionalLongBPlusTree`, `TransactionalObjectBPlusTree`;
  `AbstractIntKeyedBPlusTree` already had the former.
- **Two resolvers compute the same operands and can drift.** Mitigated structurally: the *comparison*
  exists once per tree (`checkTailBoundary` / `checkHeadBoundary`) and both resolvers feed it.
  `fenceOf` and `predecessorLeaf` were widened from `private` to package-private so the equivalence
  tests can call them.
- **A wrong descent is invisible to a green suite.** In a sound tree the asserts never fire, so a
  descent that always answered "no fence" passes every functional test. This is the trap to remember
  when touching the descent.
- **The design depends on escape analysis.** `BoundaryContext` is returned across a method boundary on
  every insert. A change that makes it escape — storing it, widening its lifetime, a megamorphic call
  site defeating inlining — silently reintroduces a per-insert allocation with no test failure.
- **Path capacity is derived, not tracked:** `estimatedPathLength() = 2 + log(size)/log(internalNodeBlockSize)`
  on `AbstractTransactionalBPlusTree`, replacing the natural logarithm at 8 sites.
- `Bucket`'s leaves are column-backed, not array-backed, so its `isNearlyFull()`/`capacity()` read
  `records.capacity()` rather than an array length.
- An insert site that finds `leaf.isFull()` with no captured cursor throws rather than proceeding —
  the "nearly full" predicate is a correctness gate, not a hint.

## Verification

`BPlusTreeCursorAllocationBenchmark` (34 arms, JDK 17, 200k-entry trees, `gc.alloc.rate.norm` at
±1.2 B determinism) with **`IntToLong` as the control** — its insert path is untouched, and it moved
≤ 1.5 % on allocation and ≤ 4 % on time, which is what makes the two runs comparable.

| | before | after |
|---|---|---|
| Read descent, all five trees | 192–232 B | profiler floor |
| Warm-up insert, block 256, random keys — `Bucket` | 304.9 B | 108.1 B (−64.6 %) |
| — `Long` | 272.5 B | 115.7 B (−57.5 %) |
| — `Object` | 237.3 B | 40.3 B (−83.0 %) |
| — `Element` | 220.3 B | 55.3 B (−74.9 %) |
| ALIVE-mode `Bucket` insert | 1309.9 B | 1101.9 B (−15.9 %) |

The ALIVE figure lands against the **14.7 %** the census predicted from the cursor's measured size;
it slightly exceeds it because the capacity fix also shrinks the cursors that splits still do capture.

Latency (3 forks, 15 samples): read descents 10–23 % faster; inserts **19–40 % faster under ascending
keys**, 8–19 % under random; ALIVE `Bucket` 6.7 %. The sharpest result is that the pre-port ~28 %
cursor penalty on a read descent did not shrink but **vanished** — the cursor-capturing and
cursor-free `IntToLong` arms now measure the same (0.793 vs 0.821 ms, overlapping intervals).

`BoundaryContext` is confirmed scalar-replaced: `Bucket`'s per-insert allocation fell by 196.8 B
against a 192 B cursor, i.e. by *more* than the object removed, which a surviving 32-byte record
cannot explain.

Correctness: 20,743 functional tests, 0 failures (only the Docker-dependent `ExportS3ServiceTest`
errors), plus 9 generational/savepoint fuzz classes, 19 tests, with no boundary or missing-split-path
error. Each tree gained a `shouldResolveSameBoundaryOperandsAsCursorPath` equivalence test that pins
the fence and predecessor **values**, on a five-leaf fixture that forces the cross-parent cases — a
three-leaf fixture puts every fence at the leaf's immediate parent and never enters the right-spine
walk.

## Consequences & open follow-ups

- **The census's insert arms are the regression detector** for the escape-analysis dependency. There is
  no cheaper signal; nothing else fails when `BoundaryContext` starts escaping.
- **No end-to-end validation.** All evidence is microbenchmark plus unit/functional/generational
  tests. No application-level corpus run was done, so the share of a real ingest or query these
  percentages translate into is unmeasured.
- **`Element` under random key order** is the one latency arm inside the noise band (−2.7 %).
  `Element`'s production workload is ascending by documented contract, so that is not the arm that
  governs it, but it is also not evidence of a win.
- **Residual per-insert allocation on `Element` (55.3 B) and `Long` (115.7 B)** at block 256 is not
  decomposed. Probably split-time node allocation; these arms cannot separate it.
- A pleasing second-order effect worth knowing: under ascending keys the descent is rightmost at every
  level, so no fence is ever recorded, the tail assert becomes a no-op *and* the cursor is never
  captured. The regime in which lazy capture was worthless is the one in which it is now free — which
  is why ascending inserts gain roughly twice what random ones do.

## Related work

- `2026-07-10-more-optimized-data-structures` — the campaign that built this B+ tree family and its
  paged storage; the cursor path being optimized here is one it introduced.
- `2026-07-31-bulk-ingest-write-path` — same code area (`TransactionalBucketBPlusTree`). Its
  boundary-index work (`8b6c2a2e8`) removed the per-insert front-coded key decode that Option C above
  would have reinstated.

## Timeline

- **2026-07-31** — census measured across all five trees; the assert blocker and the
  `InsertionPosition` null result identified
- **2026-08-01** — implemented across the family, measured, PR #1356 opened
