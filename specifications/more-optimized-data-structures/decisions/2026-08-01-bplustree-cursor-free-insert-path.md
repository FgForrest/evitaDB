# ADR: the B+ tree insert path stops capturing a cursor path

**Status:** accepted, implemented on `dev` for 2026.2
**Issue:** [#1333](https://github.com/FgForrest/evitaDB/issues/1333)
**Supersedes nothing.** Extends `7869c5fdc` / `46b4fecf3f` (which did this for one tree) to the family.
**Evidence:** `../reports/2026-07-31-bplustree-optimization-portability-census.md`

> Placement note: this repository has no ADR convention. `specifications/more-optimized-data-structures/`
> already carries `plans/`, `design/`, `reports/` and `performance/`, and this decision is confined to
> that code area, so it lands here in a new `decisions/`. Move it if a repo-wide home is preferred.

## Context

The transactional B+ tree family — `Bucket`, `Element`, `Long`, `Object`, `IntToLong` — reached its leaf
by calling `createCursor(key)`, which allocates an `ArrayList`, its backing array, a one-element root
sibling array, a `CursorLevel` per level and the `Cursor` itself. Measured: **192–232 B per descent**.

That path exists for exactly one purpose — cascading a split upward — and a split happens roughly once
per `valueBlockSize` inserts. `7869c5fdc` had already made `TransactionalIntToLongBPlusTree` descend
allocation-free and capture the path only when the leaf could overflow. #1333 asked whether that ports.

Two things blocked the port, and only the second mattered:

1. `findLeafNode` existed only on `AbstractIntKeyedBPlusTree`. Mechanical — the cursor descent in the
   other trees is `children[node.searchIndex(key)]` in a loop.
2. **`assertInsertBoundaries` consumes the cursor on every successful insert**, not just on splits. So
   lazy capture saves only as much as the asserts do not fire.

How much that second point costs is decided by key order, and for two trees the source states it:
`AbstractPriceListAndCurrencyPriceIndex:486` documents that price-record inserts "land at the right
edge", so **every** `Element` insert is a tail insert and lazy capture alone is worth precisely zero;
`Bucket` keys on unsorted attribute values, so its asserts already skip ~99 % of inserts.

The issue scheduled "resolve the boundary assertions" as a later phase. For `Element` it is a
prerequisite, not a follow-up.

## Decision

**1. Resolve the boundary asserts' operands during the allocation-free descent, not from a captured
path.** `findLeafNodeWithBoundaryContext(key)` reaches the same leaf and, in the same pass, records the
*fence* (the separator at the deepest level whose descent was not into the rightmost child) and the
deepest level whose descent was not into the leftmost child, from which the predecessor leaf is
resolved. Both asserts read the cursor as pure index arithmetic over the descent, so this is an
equivalence, not an approximation: `path[L].siblings()` is `n_{L-1}.getChildren()`, therefore
overwriting one local at every level where `childIndex < node.getPeek()` lands on the same separator the
cursor's bottom-up walk finds first.

**2. Capture the cursor only when `leaf.isNearlyFull()`**, evaluated **before** the mutation. Each tree's
`isNearlyFull()` mirrors *its own* `isFull()`, reading `peek` and the capacity from the same resolved
transactional state.

**3. Read paths use a plain allocation-free `findLeafNode`.** Every lookup that consumed nothing but
`cursor.leafNode()` — `getRecordsEqualTo`, `contains`, `cardinalityOf`, `search`, `markDirty`,
`getLongRecordEqualTo` — was switched.

**4. The cursor path's `ArrayList` capacity is derived, not tracked.**
`estimatedPathLength() = 2 + log(size)/log(internalNodeBlockSize)` replaces `(int)(Math.log(size()) + 1)`,
which took a *natural* logarithm of the entry count — a quantity unrelated to the depth of a tree that
branches `internalNodeBlockSize` ways (14 slots requested against a real depth of 3 at 1 M entries).

**5. `InsertionPosition` is NOT folded away.** It was expected to be the wider win because it is paid on
reads too. Measurement says otherwise: trees that construct it and a tree that does not allocate
identically per descent. Escape analysis already removes it, so there is nothing to port.

**6. Structural operations keep the cursor.** `delete`, `removeRecord`, `consolidate`, `updateParentKeys`,
the iterators and `validateDirtyScope` read more of the path than the leaf.

## Consequences

**Measured — allocation.** Read descents fell from 192–232 B to the profiler floor on all five trees.
Warm-up insert allocation fell 57–83 % on the four ported trees, against a control (`IntToLong`, insert
path untouched) that moved ≤ 1.5 % — which is what makes the before/after comparable at all. The
ALIVE-mode `Bucket` insert fell **15.9 %** against the **14.7 %** predicted from the cursor's measured
size.

**Measured — latency** (3 forks, 15 samples). Read descents 10–23 % faster; inserts **19–40 % faster
under ascending keys**, 8–19 % under random; ALIVE `Bucket` 6.7 %. Control arms drifted +1 % to +4 %.
The sharpest result is that the pre-port ~28 % cursor penalty on a read descent did not shrink but
**vanished**: the cursor-capturing and cursor-free `IntToLong` arms now measure the same
(0.793 vs 0.821 ms, overlapping intervals).

Ascending inserts gain roughly twice what random ones do, which is the decision showing through rather
than a coincidence — ascending is precisely where the tail assert degenerates to a no-op *and* the
cursor is never captured. Random inserts are dominated by cache misses that no bookkeeping removal
touches. One arm, `Element` under random keys, is indistinguishable from noise; `Element`'s production
workload is ascending by documented contract, so that is not the arm that governs it.

**The design depends on escape analysis.** `BoundaryContext` is returned across a method boundary on
every insert. Measurement says it is scalar-replaced — `Bucket`'s per-insert allocation fell by 196.8 B
against a 192 B cursor, i.e. by *more* than the object removed, which a surviving 32-byte record cannot
explain. This is a JIT property, not a language guarantee: a future change that makes the record escape
(storing it, widening its lifetime, a megamorphic call site defeating inlining) would silently reintroduce
a per-insert allocation. The census's insert arms are the regression detector.

**Two resolvers now compute the same operands** — the cursor-based one (still used by
`validateDirtyScope`) and the descent-based one. They can drift. Mitigated structurally: the *comparison*
exists once per tree (`checkTailBoundary` / `checkHeadBoundary`) and both resolvers feed it, plus an
equivalence test per tree pins that they agree. `fenceOf` and `predecessorLeaf` were widened from
`private` to package-private so those tests can call them.

**A wrong descent is invisible to a green suite.** In a sound tree the asserts never fire, so a descent
that always answered "no fence" would pass every functional test. The equivalence tests therefore pin the
fence and predecessor *values*, on a five-leaf fixture that forces the cross-parent cases; a three-leaf
fixture puts every fence at the leaf's immediate parent and never enters the right-spine walk.

**A pleasing second-order effect.** Under ascending keys the descent is rightmost at every level, so no
fence is ever recorded, the tail assert becomes a no-op *and* the cursor is never captured. The regime in
which lazy capture was worthless is the one in which it is now free.

## Alternatives considered

**Re-descend after the mutation to recompute the operands.** Simplest, and the asserts would still be
correct. Rejected on two grounds: `7869c5fdc` explicitly documents that a path built after `leaf.insert`
can descend a re-published root, and re-introducing that shape on the corruption-detection path of a
defect-prone family is a poor trade; and it costs a full extra descent on 100 % of ascending-key inserts,
i.e. exactly the workload it was meant to help.

**Run both asserts unconditionally before the mutation.** Any inserted key must sort strictly between the
predecessor's last key and the fence, whether or not it becomes a boundary key — so `insertedAt` is not
actually needed. Rejected because the head check would then resolve and read the predecessor's boundary
key on *every* insert; for `Bucket` that key is front-coded, so this would reinstate the per-insert decode
that `8b6c2a2e8` had just removed.

**Maintain a `height` field** instead of deriving the path capacity. Rejected: it adds mutable state
updated on root split and root collapse — the structural path, which is where this family's historical
defects live — to save a logarithm. The capacity is an `ArrayList` hint, so an under-estimate costs one
array grow and never correctness; that asymmetry does not justify new invariants.

**A reusable caller-owned path buffer.** Already considered and rejected in `7869c5fdc`: `createCursor`
has ~40 call sites and about half return the cursor inside a long-lived iterator, with `CursorWithLevel`
sharing the path list during split cascades.

**Port `InsertionPosition` removal anyway, as a readability change.** Rejected. It is a real reduction in
operations, but presenting a measured-at-zero change as an optimization miscalibrates the next reader,
and touching five hot descents for cosmetics is not free.

## What this decision does not establish

- **End-to-end effect.** All evidence is microbenchmark plus unit / functional / generational tests. No
  application-level corpus run has been done, so the share of a real ingest or query these percentages
  translate into is unmeasured.
- **`Element` under random key order** — the one latency arm inside the noise band.
- **`Long`'s production key order.** Unknown, and deliberately made moot — the fenced descent removes the
  dependency on it rather than answering it.
- **The residual per-insert allocation on `Element` (55.3 B) and `Long` (115.7 B)** at block 256. Probably
  split-time node allocation; these arms cannot decompose it.
