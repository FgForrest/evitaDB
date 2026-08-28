---
title: Index caches memoize the bitmap, never the formula, because a formula node carries per-query state
date: 2026-08-28
updated: 2026-08-28 11:40
status: accepted
kind: fix
issues: [1458]
prs: [1459, 1460]
areas: [evita_engine/io/evitadb/index/attribute, evita_engine/io/evitadb/index/hierarchy, evita_engine/io/evitadb/core/query/algebra]
supersedes: []
superseded-by: []
relates: []
---

# Index caches memoize the bitmap, never the formula

Three index structures cached a `Formula` for the lifetime of the index and handed the same instance to
successive query plans. A formula node is per-query state — the first plan to use one wrote its execution
context onto it, and that context transitively pinned the session and the entire catalog generation the
query ran against, permanently. All three now memoize the **bitmap** they were really caching and build a
fresh `ConstantFormula` wrapper per call.

## Why

Production (decodoma ks02, evitaDB 2026.1.15) leaked a post-GC live heap floor from 8.30 GB to 14.43 GB
over 45 hours — about 139 MB/h, 3.3 GB/day, against a 22 GiB maximum, never recovering. A heap dump showed
126 `EvitaSession` instances and at least 41 `Catalog` generations still reachable long after the sessions
had been closed and killed.

The constraint that made this non-obvious is that **the leak is rare, not systemic**. Over the pod's 50.8-hour
life 5,107,029 sessions were opened and 126 survived — one in 40,531. Session close and deregistration are
provably correct: `session_opened_total − session_closed_total` was 0 at the dump, `active_sessions` peaked at
4, and the inactivity killer fired on schedule. Anyone told "126 leaked sessions" naturally opens the session
lifecycle, which is the wrong place. What retains them is a rare *pin* from a structure that outlives them.

Growth is coupled to **read** volume rather than writes: the caches are invalidated only when the index is
written to, so on a read-mostly index they are effectively never cleared. The database leaked faster the more
it did what it is built for.

### Previous state

`AbstractFormula#initialize(QueryExecutionContext)` stores the context on the node and recurses into inner
formulas. `FilterIndex#getAllRecordsFormula()`, `OwnerUniqueIndex#getRecordIdsFormula()` and
`HierarchyIndex#getAllHierarchyNodesFormula()` each memoized the formula they built so that repeated calls
were cheap.

The hazard was already known — `EmptyFormula#initialize` and `SkipFormula#initialize` are both deliberate
no-ops carrying a JavaDoc that describes exactly this leak. `FilterIndex` was a single ternary with **one
branch guarded and one not**:

```java
this.memoizedAllRecordsFormula = allRecords.isEmpty() ?
    EmptyFormula.INSTANCE               // initialize() is a no-op — safe
    : new ConstantFormula(allRecords);  // inherits AbstractFormula.initialize — leaks
```

## Options considered

### Option A — memoize the bitmap, build a fresh formula per call (chosen)

Keep a `Bitmap` field where a `Formula` field used to be, and wrap it in a new `ConstantFormula` on every
call. The expensive thing was always the bitmap — the merged OR of all buckets in a filter index, the
`O(nodes)` walk in a hierarchy index. The formula around it is a few scalars.

- **Pros:** removes the whole category rather than one instance — no index-lifetime object holds query state
  any more, so the next memoization added to an index cannot reintroduce the leak by inheritance. Also fixes
  a latent staleness bug: a `ConstantFormula` computes its hash, transactional ids and estimated cost at
  construction, so a memoized one carried those from whenever it was first built.
- **Cons:** allocates a small wrapper per call on a hot path, and changes an observable contract — three
  tests asserted formula reference identity across calls.

### Option B — override `initialize` as a no-op on `ConstantFormula` (declined)

Give `ConstantFormula` the same treatment `EmptyFormula` and `SkipFormula` already have, so that sharing one
is harmless.

- **Pros:** a single method, no accounting or invalidation changes, and the smallest possible diff for a
  release branch.
- **Cons:** a per-class exemption rather than a structural rule; the next memo of a different formula type
  leaks again.
- **Rejected because:** it is whack-a-mole, and this codebase has already lost that game twice —
  `EmptyFormula` and `SkipFormula` are the two previous rounds. The sweep also refutes the one case that
  would have justified it: `InvertedIndexSubSet#memoizedResult` was the candidate beneficiary, but its
  aggregation lambda returns an `OrFormula` or a `DeferredFormula` in the multi-bucket case, so a
  `ConstantFormula` exemption protects nothing there. Revisit only if a formula type must be shared for a
  reason bitmap memoization cannot serve.

### Option C — pass the context through `compute(...)` instead of storing it on the node (declined)

The structurally correct fix: make execution context a parameter rather than node state, ending the entire
class of bug.

- **Pros:** no formula node ever holds query state, so the invariant becomes unrepresentable rather than
  merely documented.
- **Cons:** changes the `Formula` interface and every implementation, plus the cacheable-formula and
  price-termination hierarchies that read `this.executionContext` from six different subclasses.
- **Rejected because:** the blast radius is not a patch-release change and this had to ship to a leaking
  production system. Worth revisiting for a major version — that is the ADR that would supersede this one.

### Option D — clear the context in a query-teardown hook (declined)

- **Pros:** leaves all caching as-is.
- **Rejected because:** it depends on teardown always running, so the leak returns on any path that throws
  first. Acceptable as belt-and-braces, never as the primary fix.

### Option E — hold the context through a weak or soft reference (declined)

- **Rejected because:** it makes catalog reclamation non-deterministic — the heap would still hold whole
  catalog generations until GC chose to act, which is the symptom rather than the cause.

## Decision

**Chosen: Option A.** The drivers were a leaking production system and a hazard that had already recurred
twice under per-class fixes. Option A is the only one that makes the *category* go away while remaining
small enough to backport: it touches three accessors and their caches, and does not move the `Formula`
interface. Option C wins instead the moment a major version is on the table, because it makes the invariant
unrepresentable rather than merely documented and tested.

## Key technical details

- **The invariant:** *no object with index lifetime may hold a `QueryExecutionContext`, therefore no
  index-lifetime field may hold a `Formula`.* `AbstractFormula#initialize` writes the context onto every node
  of a plan, and it reaches `QueryPlanningContext#evitaSession` and `#catalog` from there.
- Entry points: `FilterIndex#memoizedAllRecords`, `OwnerUniqueIndex#getRecordIdsFormula` (the field is gone
  entirely — `recordIds` was already the bitmap), `HierarchyIndex#memoizedAllNodes`.
- `FilterIndex#getAllRecords()` no longer round-trips through a formula to unwrap a bitmap, and
  `HierarchyIndex#getAllHierarchyNodes()` is new for the same reason: the bitmap is the primitive and the
  formula is the wrapper, not the other way round.
- **`InvertedIndexSubSet#memoizedResult` is deliberately left alone.** It memoizes a formula, but the only
  index-lifetime subset is `FilterIndex#memoizedRangeHistogramSubSet`, whose sole consumer
  (`AttributeHistogramComputer`) reads `getBuckets()` and never `getFormula()`. Bitmap memoization is *not*
  available there — the aggregation lambda may legitimately return a lazy `DeferredFormula`, so materializing
  eagerly would change behaviour. The constraint is recorded as a JavaDoc invariant at the field.
- **Counter-intuitive:** `OwnerUniqueIndex` dropped its cache invalidation on mutation and is nonetheless
  *more* correct. Its formula always wrapped `this.recordIds`, a `TransactionalBitmap` mutated in place, so
  the invalidation never affected the computed result — only the hash and cost captured at construction.
  Building fresh means those are never stale.
- Sites checked and deliberately **not** changed, so they are not re-investigated:
  `GlobalEntityIndex.GlobalIndexProxyState` memoizes a formula but is built per query plan in
  `QueryPlanningContext`, so it is query-lifetime; `EntityIndex#getAllPrimaryKeysFormula`,
  `GlobalUniqueIndex`, `RangeIndex`, `InvertedIndex` and `AbstractPriceListAndCurrencyPriceIndex` all build a
  fresh formula per call already.

## Verification

- `IndexFormulaRetentionTest` is the enforcement: it reflects over every non-static field of `FilterIndex`,
  `OwnerUniqueIndex` and `HierarchyIndex` and fails if any retains a `Formula`, exempting only the
  `EmptyFormula` / `SkipFormula` singletons whose `initialize` is a documented no-op. This is what stops the
  next memoization from reintroducing the leak; per-class discipline demonstrably did not.
- Three existing tests asserted the contract that was removed and were **inverted** rather than deleted —
  they now assert that successive calls return *different* formula instances over the *same* memoized bitmap:
  `FilterIndexTest#shouldMemoizeBitmapButHandOutFreshFormula`,
  `UniqueIndexTest#shouldReturnFreshFormulaTrackingMutations`,
  `HierarchyIndexTest#shouldMemoizeAllNodesBitmapButHandOutFreshFormula`.
- Four invalidation tests were retargeted from formula identity to bitmap identity, because
  `assertNotSame` on formulas is now trivially true and would have silently stopped testing invalidation at
  all: the non-transactional add/remove tests in `FilterIndexTest` and `HierarchyIndexTest`, and the
  unregister test in `UniqueIndexTest`.

## Consequences & open follow-ups

- **The heap accounting differs by branch.** `IndexHeapSize` and the two heap-size test classes exist only on
  `dev`; the release line has no accounting for these memos at all. On `dev`,
  `IndexHeapSize#memoizedFormulaSizeInBytes` loses all three index callers and keeps exactly one,
  `InvertedIndexSubSet` — its JavaDoc claim to be "the one place a memoized formula is priced" becomes more
  accurate, not less, so the method stays. The three indexes were never symmetric here and still are not:
  `HierarchyIndex` charges its bitmap unconditionally, `FilterIndex` only when `getBucketCount() > 1`,
  because a single-bucket memo aliases the bucket's own bitmap, and `OwnerUniqueIndex` charges nothing
  because it retains nothing.
- **Removing the over-charge exposed a pre-existing under-report in `BaseBitmap#getHeapSizeInBytes`** — about
  two words against a reflective walk, on the order of 16 bytes and constant with data size. It was there
  before this change and merely masked by the formula scaffolding charged at its upper bound on top of it.
  `ContainerIndexHeapSizeTest#shouldStepUpOnceTheAllNodesBitmapIsMemoized` now asserts a bounded **signed**
  divergence rather than a strictly positive one, and the existing non-growth assertion pins that it stays a
  constant. Not chased here: it is a bitmap-accounting question, independent of this leak, and fixing it
  inside a hotfix would put unrelated risk on the release line.
- **A CPU regression was traded for the memory fix on one path, and it is not small.** `ConstantFormula`
  hashes a non-transactional delegate's **contents** in its constructor (`AbstractFormula#initFields` computes
  the hash eagerly). A filter index whose value tree holds more than one bucket memoizes a `BaseBitmap`, which
  is not a `TransactionalLayerProducer`, so every `getAllRecordsFormula()` call now pays `O(records)` where the
  old formula memo paid it once. Measured on a seeded `OwnerFilterIndex`: **+1.4 µs at 1k records, +10 µs at
  10k, +83 µs at 100k, +309 µs at 500k**, against ~1–18 ns for the memoized bitmap alone.

  Only two read paths ask for the formula — `AttributeIsTranslator` (an `attributeIs(NULL|NOT_NULL)` filter)
  and `AttributeHistogramComputer` (once per attribute index in a histogram request). `OwnerUniqueIndex` is
  **not** affected: its delegate is a `TransactionalBitmap`, so the hash is its transactional id and
  construction stays `O(1)`. `HierarchyIndex` is not affected in practice because no main-source caller asks
  it for a formula.

  The fix is to memoize the content hash beside the bitmap and hand it to a `ConstantFormula` overload — zero
  formula retained, so the invariant and its guard test are untouched. Deliberately **not** done here: it adds
  public API to `ConstantFormula` and the leak was the urgent problem. This was found after both PRs opened
  and is recorded so the trade is visible rather than discovered in a profile.
- **`OwnerUniqueIndex` contamination was never observed**, only derived from source. The production dump was
  a truncated 15% prefix, and the 752/752 contaminated memos it did show were all `FilterIndex`. A complete
  dump would confirm it.
- **`HierarchyIndex` had no observed contamination either** — 0 of 101,794 instances, and no main-source
  caller of `getAllHierarchyNodesFormula()` outside the class. It was converted because it is the same shape,
  so that the pattern does not survive as a template someone copies.
- The formula cache was **disabled** on the affected instance (`cache_size_in_bytes = 0`), so this is
  unrelated to the cache redesign tracked in #37.
- `io_evitadb_probe_health_problem{MEMORY_SHORTAGE}` stayed 0 throughout, including at 14.43 GB of a 22 GiB
  heap. evitaDB's own health probe gives no warning for this class of leak — not addressed here.

## Timeline

- **2026-08-27** — heap dump captured from production
- **2026-08-28** — leak confirmed from heap dump and Prometheus, issue #1458 filed, fix implemented
