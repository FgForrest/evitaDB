# `SortIndex` committed-snapshot supplier cache — fixed gate, engaged in production

**Status update (2026-07-10): the 0/17,268 finding below was a BROKEN-GATE artifact, now fixed. The gate
required `Transaction.isTransactionAvailable()`, but read-only sessions (and all plain queries) bind no
transaction, so the fast path never ran. Corrected gate = `getTransactionalMemoryLayerIfExists(this) ==
null` alone, plus `invalidateCommittedSnapshotCacheIfNonTransactional()` for warm-up safety. Re-measured:
the fast path now engages (`resolvePositionsByDenseWalk` 4506 → 0), `attributeAndHierarchyFiltering`
improves ~1.76× (5799 → 10183 ops/s), and `attributeFiltering` moves within noise because it is now bound
by a DIFFERENT subsystem (the filter path: transactional-bitmap `ThreadLocal` tax + RoaringBitmap container
materialization — NOT this sort cache). Full corrected finding + next-bottleneck evidence:
`docs/reports/2026-07-09-sortindex-cache-e2e-findings.md` (UPDATE 2026-07-10) and
`docs/reports/e2e-remeasure2/analysis.txt`. §5.2 below is superseded by that UPDATE.**

Issue: #760 (attribute-filtering query regression). Supersedes
`docs/design/2026-07-09-densewalk-notfound-bulk-andnot.md` (see its superseded-notice header) — that
plan only closed 3-33% of the gap; this one addresses the mechanism identified as the actual dominant
cost in isolation, and is **implemented but currently inert in production** in the working tree
(`SortIndex.java`, `SortIndexChanges.java`, `SortedRecordsSupplier.java`,
`ReferenceSortedRecordsProvider.java`, plus tests).

## 1. Problem & scope

`docs/reports/2026-07-09-write-and-query-throughput-remeasure.md` §4.1 measured `attributeFiltering`/
`attributeAndHierarchyFiltering` at −60 to −66% vs `dev`, attributing 41% of query CPU to
`RoaringArray.<init>` inside `SortedRecordsSupplier.resolvePositionsByDenseWalk`. A direct CPU profile
of the JMH benchmark confirmed the cold dense tree-walk dominates essentially all real traffic
(25,919 samples vs. 21 for the sparse probe; the warm array-merge-walk sample count was **0**).

### 1.1 Root mechanism 1: the per-transaction memoization never survives a query

`SortIndexChanges` already memoizes the expensive materialized arrays (`memoizedAscending` /
`memoizedDescending` / `memoizedAllRecords`), invalidated correctly on every mutation via
`invalidateSupplierArrays()`. But `SortIndex.getAscendingOrderRecordsSupplier()` always resolved this
layer via `Transaction.getOrCreateTransactionalMemoryLayer(this)`, and
`EvitaSession.executeInTransactionIfPossible()` opens and discards a **fresh** `Transaction` (and
therefore a fresh, empty `SortIndexChanges`) on every single query against an `ALIVE` catalog. The
memoization is correct but architecturally unreachable in production: no query ever survives long
enough to see a warm cache.

### 1.2 Root mechanism 2 (found during implementation, via advisor review): the warm-check is on the wrong object anyway

Even granting mechanism 1 a fix, a second, independent bug would still have defeated it:
`SortedRecordsSupplier.resolvePositions()`'s dense-selection dispatch only takes the fast array
merge-walk when `this.recordPositions != null && this.allRecords != null` — fields on the **freshly
built per-query supplier instance itself**, not on whatever cache backs its lazy `Supplier<int[]>`
accessors. A tree-backed supplier's eager fields start `null` at construction
(`SortedRecordsSupplier.java` tree-backed constructor). Neither the sparse probe
(`resolvePositionsBySparseProbe`) nor the cold dense walk (`resolvePositionsByDenseWalk`) ever calls
`getRecordPositions()`/`getAllRecords()` — nothing in the dispatch path does. So even a perfectly warm,
correctly-reused cache is never *consulted*: every dense query still takes the O(N) cold walk, forever,
regardless of how good the underlying memoization is. This was caught by writing a test that asserts
the returned `SortResolutionStrategy`, not just array-identity reuse (identity reuse alone proved the
cache works, not that anything reads from it).

## 2. The fix

### 2.1 Reach the cache without paying the per-transaction cost (`SortIndex.java`)

Added three `volatile` fields on `SortIndex` holding the materialized arrays for **this committed,
immutable snapshot** (`cachedAscendingArrays`, `cachedDescendingArrays`, `cachedAllRecordsBitmap`,
typed `SortIndexChanges.MaterializedSortRecords` / `Bitmap`, promoted from `private` to package-private
so both classes share the shape). `getAscendingOrderRecordsSupplier()` /
`getDescendingOrderRecordsSupplier()` now branch:

```java
public SortedRecordsSupplier getAscendingOrderRecordsSupplier() {
    if (Transaction.isTransactionAvailable() && Transaction.getTransactionalMemoryLayerIfExists(this) == null) {
        return buildCachedSupplier(false, createSortedComparableForwardSeeker());
    }
    return getOrCreateSortIndexChanges().getAscendingOrderRecordsSupplier();
}
```

`Transaction.getTransactionalMemoryLayerIfExists(this)` (non-creating lookup) returns non-`null` only
once something has actually **written** to this `SortIndex` within the current transaction — at that
point the existing per-transaction path takes over unchanged, preserving read-your-own-writes. When no
write has touched this index (the overwhelming majority of queries against an already-committed
snapshot), the request is served from the snapshot-scoped cache instead.

**Why this needs no explicit invalidation.** `SortIndex` is a `TransactionalLayerProducer`: a
transaction never mutates `sortedRecords` on the *existing* instance (`SortIndex`'s class javadoc: "If
no transaction is opened, changes are applied directly... " — the converse holds while a transaction
*is* open). A write always resolves a per-transaction `SortIndexChanges` layer first; the base instance
stays untouched until commit, where `createCopyWithMergedTransactionalMemory` either returns `this`
unchanged (nothing changed) or builds a **brand-new** `SortIndex` (whose cache fields start `null` by
plain field default). Either way the cache is correct without a dirty-flag or version check — this is
the deliberate design choice over reusing the `@NotThreadSafe`-annotated `SortIndexChanges` itself,
which was flagged and reconsidered mid-implementation.

**Only the two read-supplier methods were touched.** `getOrCreateSortIndexChanges()` itself, and every
write call site that uses it (`addRecord`, `removeRecord` ×2, `computeBlockStart`), are **unchanged** —
writes must keep creating a transaction-local layer; routing a write through the shared cache would be
a transaction-isolation violation visible to concurrent readers.

### 2.2 Make the cache eager, not lazy, so dense dispatch actually sees it (§1.2's fix)

`buildCachedSupplier` materializes (or reuses the already-materialized) arrays for the requested
direction **before** constructing the supplier, then passes them into a new eager tree-backed
constructor added to `SortedRecordsSupplier` / `ReferenceSortedRecordsProvider` — the tree
(`sortedRecords`) is still wired too, so the sparse probe and `recordAt`/`positionOf` are unaffected:

```java
private SortedRecordsSupplier buildCachedSupplier(boolean descending, SortedComparableForwardSeeker seeker) {
    final SortIndexChanges.MaterializedSortRecords arrays = descending
        ? getCachedDescendingArrays() : getCachedAscendingArrays();
    return this.referenceKey != null
        ? new ReferenceSortedRecordsProvider(
            transactionalId, this.sortedRecords, descending, recordCount,
            arrays.sortedRecordIds(), arrays.recordPositions(), arrays.allRecords(), seeker, this.referenceKey)
        : new SortedRecordsSupplier(
            transactionalId, this.sortedRecords, descending, recordCount,
            arrays.sortedRecordIds(), arrays.recordPositions(), arrays.allRecords(), seeker);
}
```

First call for a direction on a given snapshot pays the one-time O(N log N) materialization; every
subsequent query against that snapshot — sparse or dense, this transaction or a different throwaway
one — reuses it. A dedicated test asserts the returned `PositionResolution.strategy()` is
`ARRAY_MERGE_WALK`, not `TREE_DENSE_WALK`, confirming the dispatch actually flips.

### 2.3 Correctness — verified

- **Cross-transaction reuse**: separate throwaway `Transaction`s against the same untouched `SortIndex`
  return the identical array instance (`assertSame`).
- **Read-your-own-write**: a write mid-transaction is visible to a later read in the *same* transaction
  (falls through to the live layer, not the stale snapshot cache); verified both directions.
- **Isolation across indexes**: writing to a *different* `SortIndex` in the same transaction does not
  disturb this one's cache eligibility (the transactional-layer lookup is keyed per-instance).
- **Commit semantics**: a no-op (read-only) transaction's committed copy is the *same* instance
  (`isDirty == false` path) and keeps its warm cache; a real write produces a *new* instance that starts
  cold and reflects the write when queried.
- **Concurrency**: 8 threads racing to populate the cache on first touch never throw and always observe
  fully-published, correct data — the benign first-touch race is safe because `MaterializedSortRecords`
  is a `record` (`final` fields ⇒ safe publication under the JMM), matching the precedent in
  `PersistentTransactionalMap#state`.
- **Deliberately unchanged**: the non-transactional (bulk-load/warm-up) path still uses
  `this.sortIndexChanges`'s own mutation-invalidated memoization — it was never part of the regression
  (that path can mutate `sortedRecords` in place, so the snapshot cache's no-invalidation-needed
  argument does not hold there).

## 3. Wire format & BWC — none

Pure in-memory dispatch/caching change. No persisted state, no serialization format touched.

## 4. Measured results

### 4.1 Read latency — controlled A/B (`SortIndexCommittedSnapshotCacheBenchmark`, N=100,000, shuffled fixture)

The first pass compared the fix against a *zero-transaction-overhead* baseline
(`SortIndexResolvePositionsBenchmark.resolvePositions_treeAuto`) and looked like a sparse-selectivity
regression. That comparison wasn't controlled — the old code, exercised under a real throwaway
transaction (matching production), also pays a `SortIndexChanges` layer allocation per query that the
zero-overhead baseline never did. Re-measured old-vs-new **inside the identical harness** (real
`Transaction`, same per-call buffers):

| K/N | old (real txn) | new (real txn) | speedup |
|---|--:|--:|--:|
| 0.005 | 104.7 µs | 94.2 µs | 1.11× |
| 0.01 | 215.7 µs | 207.8 µs | 1.04× |
| 0.02 | 5527.2 µs | 291.6 µs | 19.0× |
| 0.1 | 4032.0 µs | 681.4 µs | 5.9× |
| 0.5 | 3716.8 µs | 1269.4 µs | 2.9× |
| 1.0 | 3494.7 µs | 1214.5 µs | 2.9× |

The dense numbers land almost exactly on the independently-measured caching ceiling
(`resolvePositions_warmArray`: 314.6 / 939.1 / 1265.4 / 1322.0 µs at the same K/N) — strong
triangulation that the fix achieves the theoretical best case. A dedicated bare-transaction-overhead
benchmark (`throwawayTransactionOverheadOnly`) measured ~0.2 µs/op — confirming `Transaction`
construction itself was never the bottleneck. 4-thread concurrent reads track single-thread numbers
closely (no contention pathology).

### 4.2 Retained memory (JOL, N=100,000)

| State | Retained bytes | Δ vs cold |
|---|--:|--:|
| Cold (no supplier requested yet) | 7,615,672 | — |
| One direction queried | 8,432,360 | +816,688 |
| Both directions queried | 9,232,424 | +1,616,752 |

≈8 bytes/record/direction (two `int[N]` arrays), plus a small shared direction-independent bitmap.

## 5. Explicitly open — decisions for Johnny, not yet resolved

### 5.1 Eager materialization reintroduces exactly the memory cost #760's lazy design removed — for the sparse path too

§2.2's eager materialization runs on **every** first cached-path call, sparse or dense — because
sparse-vs-dense isn't known until `resolvePositions()` is called, *after* the supplier already exists.
This means a sparse-only query against a freshly-committed snapshot now pays full O(N) materialization
and retains ~800KB/direction it previously never touched at all. The benchmark in §4 is structurally
blind to this cost: one long-lived snapshot reused across thousands of iterations amortizes it to
~zero. It will **not** be amortized on the write/mixed path (the same area as the known 45×
transactional-upsert problem), where snapshots turn over every commit and a sparse query can hit a cold
snapshot every time.

Order-of-magnitude for a real deployment: ~800KB × (actively-queried sortable attributes × locales ×
reference-index instances). This needs a concrete number against real dataset shapes before it can be
judged acceptable — **not done as part of this change**.

**Lever if this matters**: keep `buildCachedSupplier` lazy (as originally attempted) and instead have
`SortedRecordsSupplier.resolvePositions()`'s dense-*cold* branch trigger materialization itself before
falling back to `resolvePositionsByDenseWalk`, so a sparse query — which never reaches that branch —
never pays it. This is a change to shared dispatch code used by every consumer (not just the cached
snapshot path), more invasive, and was deliberately not built speculatively. Only worth doing if the
sparse/write-path cost above turns out to matter.

### 5.2 End-to-end regression re-measurement — DONE, negative result

Everything in §4 is from the isolated `SortIndexCommittedSnapshotCacheBenchmark` (a standalone
`OwnerSortIndex`, no `EvitaSession`/`Catalog` overhead). It proves the *mechanism*: dispatch now
reaches `ARRAY_MERGE_WALK` and hits the caching ceiling. It does **not** prove the *symptom* — the
−60/−66% `ArtificialEntitiesThroughputBenchmark.attributeFiltering`/`attributeAndHierarchyFiltering`
regression — is closed end-to-end, and a full re-measurement now confirms it is **not**:

- `attributeFiltering`: 2129.6 ± 127.2 ops/s post-fix vs. 1998.6 ± 209.4 ops/s pre-fix — confidence
  intervals overlap substantially; not a real, decisive win.
- `attributeAndHierarchyFiltering`: 5710.7 ± 278.4 ops/s post-fix vs. 5799.4 ± 215.2 ops/s pre-fix —
  flat, no improvement.
- Both remain roughly 60% below `dev` (5858.4 / 14879.5 ops/s respectively).

A JFR CPU profile (`-prof jfr`) of the end-to-end `attributeFiltering` run explains why: across 17,268
execution samples, **zero** reach `SortIndex.buildCachedSupplier`/`getCachedAscendingArrays`. Every
sampled sort-supplier fetch instead shows `SortIndexChanges.getDescendingOrderRecordsSupplier()` —
the pre-fix path — and `resolvePositionsByDenseWalk` remains hot (4,506 samples). The fast-path gate in
§2.1 has two conditions; re-extracting the same recording with `jfr print --stack-depth 128` (the
default is 5 frames, too shallow to see the outer `Transaction` wrapper) confirmed **all** sort-related
samples carry an `executeInTransactionIfProvided` frame — `Transaction.isTransactionAvailable()` is
true, ruling out "no transaction on-thread." The gate fails on its second condition:
`Transaction.getTransactionalMemoryLayerIfExists(this) == null` is false on essentially every real
query, because a transactional memory layer for the `SortIndex` already exists by the time the sort
step runs.

**Root cause**: `getOrCreateSortIndexChanges()` — the *creating* accessor
(`Transaction.getOrCreateTransactionalMemoryLayer(this)`) — is called from four sites in `SortIndex.java`
(lines 561, 578, 1146, 1217), not just genuine writes. Line 1217 is inside
`getRecordsEqualToInternal()`, a read-only attribute-equality lookup used during filtering, called
*before* the sort step in any query that filters and sorts in the same transaction. This is a confirmed
layer-creating read path and the likely (not yet proven-exclusive) culprit — all four call sites need
auditing to separate genuine writers from reads that shouldn't create a layer.

**This means the fix's premise — "no layer exists ⇒ safe to use the committed cache" — is false in
practice**, not because the cache logic is wrong, but because "no layer exists" is almost never true by
the time the read runs. The fix is salvageable, not dead: either (a) route genuinely read-only callers
through a non-creating accessor so reads stop poisoning the gate, or (b) key the gate off "was this
index *written* in this transaction" rather than "does a layer exist at all." Both have correctness
implications (a read in a write-touched transaction must still see the transaction's own modifications)
and are a design decision, not a mechanical patch — Johnny's call.

Full investigation notes: `docs/reports/2026-07-09-sortindex-cache-e2e-findings.md`.

## 6. Test plan — done

- `SortIndexTest.CommittedSnapshotCacheTest` (7 cases): dispatch-strategy flip, cross-transaction reuse
  (both directions), read-your-own-write, cross-index isolation, no-op-commit identity, real-commit
  cold-start, concurrent first-touch race.
- Full `SortIndexTest` / `SortIndexTreeProviderEquivalenceTest` / `SortIndexViewModeTest` /
  `SortIndexOwnerPagingRoundTripTest` suites: 114/114 green.
- Broader regression sweep `-Dgroups="(attribute | indexing) & !slow"`: 5624/5624 green.

## 7. Implementation status

Landed directly in the working tree (not a spike): `SortIndex.java`, `SortIndexChanges.java`
(visibility-only change), `SortedRecordsSupplier.java`, `ReferenceSortedRecordsProvider.java`, and the
new `CommittedSnapshotCacheTest` nested suite. `SortIndexCommittedSnapshotCacheBenchmark` (the JMH
spike backing §4) remains uncommitted under `evita_test/evita_performance_tests/.../spike/` — same
disposition question as the superseded plan's spike (land as a permanent perf-regression guard, or
fold into an existing suite — Johnny's call).

Still pending before this can be called closed: §5.1's concrete memory-impact number (now more urgent,
since a gate redesign per §5.2 will make the cache path actually execute in production), a fix for
§5.2's gate-defeated-by-read-paths root cause (audit the four `getOrCreateSortIndexChanges()` call
sites and choose between the two levers in §5.2), and — only after that fix — a repeat of the
end-to-end re-measurement to confirm the regression actually closes once the fast path can fire.
