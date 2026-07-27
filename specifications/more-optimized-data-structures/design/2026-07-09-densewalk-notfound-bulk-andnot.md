> **SUPERSEDED (2026-07-09).** Johnny reviewed this plan and judged it non-perspective: it only ever
> closes 3-33% of the `attributeFiltering` regression (see §6) because it leaves the dominant
> `contains()` cost untouched. The investigation instead pursued the caching-fix fallback flagged in
> §6/the parent report — see `docs/design/2026-07-09-sortindex-committed-snapshot-cache.md`, which
> **is implemented** and closes the regression's mechanism end-to-end (dense dispatch now hits the
> array merge-walk instead of the cold tree walk). This document is kept for historical context only;
> do not implement the bulk-`andNot` change described below.

# `SortedRecordsSupplier.resolvePositionsByDenseWalk` — replace the O(K) `remove()` loop with one bulk `andNot`

Issue: #760 (attribute-filtering query regression). Part of a larger investigation into the
`attributeFiltering`/`attributeAndHierarchyFiltering` JMH regression
(`docs/reports/2026-07-09-write-and-query-throughput-remeasure.md`, §4.1). This plan covers **one**
isolated, low-risk fix inside `resolvePositionsByDenseWalk`: replacing K sequential
`PersistentRoaringBitmap.remove()` calls with K cheap writer appends plus one bulk `andNot`. It does
**not** address the dominant cost in the same method (`selectedRecordIds.contains()`, paid N times) —
that remains open; see §6.

## 1. Problem & scope

`SortedRecordsSupplier.resolvePositionsByDenseWalk` (`evita_engine/.../index/attribute/SortedRecordsSupplier.java:347-372`)
is the O(N) fallback taken by every dense-selection query against a cold (per-query, never-warmed)
tree-backed sort provider — confirmed to be the path essentially all real `attributeFiltering`
traffic takes today (25,919 dense-walk samples vs. 21 sparse-probe samples in a direct CPU profile of
the JMH benchmark; the warm array-merge-walk sample count was 0, since a fresh
`SortedRecordsSupplier` wrapper is built per query and its own warm-check can never observe the
underlying cache — see the report for the full mechanism).

Current code:

```java
private PositionResolution resolvePositionsByDenseWalk(
    @Nonnull PersistentRoaringBitmap selectedRecordIds,
    int selectedRecordCount
) {
    final RoaringBitmapWriter<PersistentRoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
    final PersistentRoaringBitmap notFound = selectedRecordIds.clone();
    final TransactionalUnorderedIntArray theSortedRecords = Objects.requireNonNull(this.sortedRecords);
    final PositionCursor cursor = this.descending
        ? theSortedRecords.reversePositionCursor()
        : theSortedRecords.forwardPositionCursor();
    int matched = 0;
    for (int position = 0; position < this.recordCount && matched < selectedRecordCount; position++) {
        final int recordId = cursor.recordAt(position);
        if (selectedRecordIds.contains(recordId)) {
            mask.add(position);
            notFound.remove(recordId);          // <-- K sequential calls, the target of this fix
            matched++;
        }
    }
    return new PositionResolution(
        mask.get(), notFound, selectedRecordCount - matched, SortResolutionStrategy.TREE_DENSE_WALK
    );
}
```

A JMH micro-benchmark isolating this method's internal primitives directly (`SortIndexResolvePositionsBenchmark`,
`evita_test/evita_performance_tests/src/main/java/io/evitadb/spike/`, not yet committed — see §7) measured, at
N=100,000 with a record-id-vs-sort-value mapping shuffled to avoid a degenerate best-case ordering:

| K/N | `notFound.remove()` loop (today) | proposed: K appends + 1 bulk `andNot` | speedup |
|---|--:|--:|--:|
| 0.005 | 4.7 µs | 2.4 µs | 2.0× |
| 0.01 | 10.9 µs | 2.6 µs | 4.2× |
| 0.02 | 29.1 µs | 5.2 µs | 5.6× |
| 0.1 | 230.3 µs | 12.4 µs | 18.6× |
| 0.5 | 462.7 µs | 52.7 µs | 8.8× |
| 1.0 | 703.7 µs | 81.1 µs | 8.7× |

This is **not** the same cost the `2026-07-08-roaringbitmap-cloning.md` plan already fixed — that
plan made `clone()` itself O(1) (confirmed: `selectedRecordIds.clone()` alone now measures
0.006 µs/op, flat regardless of K — negligible). `remove()` on the resulting (now cheaply-cloned)
bitmap is a **separate** cost that plan did not touch: each call still pays real, K-scaling work
(the isolated-A/B allocation figures below show it growing from 1.2 KB/op at K=500 to 33 KB/op at
K=100,000 — consistent with per-call COW/container-mutation overhead, not the array-copy §3.3 of
that plan eliminated).

**Net effect on the whole method's cost** (measured against `resolvePositions_treeAuto`, the full
production dense-walk path, same fixture): swapping in the bulk `andNot` alone reduces total
dense-walk cost by 3-6% at low-mid selectivity (where a separate, still-unaddressed cost —
`selectedRecordIds.contains()`, paid N times regardless of K — dominates) up to **33% at K/N≈0.5**,
where `remove()` was the largest single term. This fix is real and worth shipping on its own merits,
but does **not** by itself close the regression — see §6.

## 2. The fix

Build a bitmap of the matched record ids incrementally during the walk (writer appends — the same
kind of cheap, amortized operation already used for `mask`), then compute the not-found set as a
single bulk set-difference **after** the walk completes, instead of mutating a cloned bitmap K times
inside the loop:

```java
private PositionResolution resolvePositionsByDenseWalk(
    @Nonnull PersistentRoaringBitmap selectedRecordIds,
    int selectedRecordCount
) {
    final RoaringBitmapWriter<PersistentRoaringBitmap> mask = RoaringBitmapBackedBitmap.buildWriter();
    final RoaringBitmapWriter<PersistentRoaringBitmap> matchedIds = RoaringBitmapBackedBitmap.buildWriter();
    final TransactionalUnorderedIntArray theSortedRecords = Objects.requireNonNull(this.sortedRecords);
    final PositionCursor cursor = this.descending
        ? theSortedRecords.reversePositionCursor()
        : theSortedRecords.forwardPositionCursor();
    int matched = 0;
    for (int position = 0; position < this.recordCount && matched < selectedRecordCount; position++) {
        final int recordId = cursor.recordAt(position);
        if (selectedRecordIds.contains(recordId)) {
            mask.add(position);
            matchedIds.add(recordId);
            matched++;
        }
    }
    final PersistentRoaringBitmap notFound = PersistentRoaringBitmap.andNot(selectedRecordIds, matchedIds.get());
    return new PositionResolution(
        mask.get(), notFound, selectedRecordCount - matched, SortResolutionStrategy.TREE_DENSE_WALK
    );
}
```

**Must use the static `PersistentRoaringBitmap.andNot(x1, x2)` overload, not the instance in-place
`andNot(PersistentRoaringBitmap)` method.** The static overload returns a new bitmap and leaves both
operands untouched (it internally calls `markAllShared(x1)`, then merges containers — see
`evita_roaring_bitmap/.../PersistentRoaringBitmap.java:360-395`); the instance overload mutates its
receiver in place. `selectedRecordIds` is a caller-supplied input parameter that today's code never
mutates (only clones), and that contract must be preserved — using the in-place overload on
`selectedRecordIds` directly would corrupt the caller's bitmap.

### 2.1 Correctness — semantic equivalence

Both versions compute `notFound = selectedRecordIds \ {record ids matched during the walk}`:

- **Today:** `notFound` starts as a full clone of `selectedRecordIds`; every matched `recordId` is
  individually removed. End state: set difference, built incrementally by subtraction.
- **Proposed:** `matchedIds` accumulates exactly the same record ids (added in the identical branch
  the `remove()` call sits in today — same condition, same loop). `andNot(selectedRecordIds, matchedIds)`
  computes the same set difference in one bulk pass instead of K subtractions.

Edge cases checked:
- **K=0 / no matches:** loop body never executes (`matched < selectedRecordCount` is `0 < 0`,
  false) or executes zero times; `matchedIds.get()` is empty; `andNot(selectedRecordIds, empty)`
  yields a bitmap with the same content as `selectedRecordIds` — matches today's
  `selectedRecordIds.clone()` with zero removals.
- **All selected records found** (loop exits early via `matched < selectedRecordCount` once every
  id is located, without walking all N positions): `matchedIds` has the same content as
  `selectedRecordIds`; `andNot` yields empty — matches today's fully-drained `notFound`.
  Early-exit behavior itself is unchanged by this fix (same loop condition).
- **Some selected ids absent from this index** (the actual "not found" case this method exists to
  handle): those ids are walked past N times without ever matching, so they're never added to
  `matchedIds`, and survive the `andNot` into the result — matches today's behavior (never removed
  from the `notFound` clone).
- **Immutability of `selectedRecordIds`:** unchanged by this fix — never mutated (read via
  `.contains()` and passed by reference into the static `andNot`, which does not mutate its first
  argument).
- **Threading:** `SortedRecordsSupplier`'s own class javadoc already documents that its
  lazily-materialized fields are unsynchronized and a tree-backed instance must be consumed by a
  single thread. This fix introduces no new shared mutable state and doesn't change that constraint.

### 2.2 Allocation trade-off (not a regression, but worth documenting)

| K/N | `remove()` loop, B/op | bulk `andNot`, B/op |
|---|--:|--:|
| 0.005 | 1,216 | 10,744 |
| 0.01 | 2,216 | 12,744 |
| 0.02 | 4,208 | 16,728 |
| 0.1 | 23,514 | 30,776 |
| 0.5 | 33,059 | 25,160 |
| 1.0 | 33,061 | 25,161 |

At very low K the fix allocates *more* (a second writer + bitmap object vs. cheap in-place removes
on an already-small clone), but CPU time is still 2-4× better there. At the K where it matters most
(moderate-to-high selectivity) it allocates *less* as well as running faster. No configuration
measured is a net loss on the metric that matters (wall-clock); the allocation increase at very low
K is small in absolute terms (~9.5 KB) and never the dominant allocator in this method (mask
construction and the untouched `contains()` path already allocate comparably or more at those K).

## 3. Wire format & BWC — none

Purely an in-memory algorithm change inside a private method. No persisted state, no serialization
format, no `serialVersionUID` involved. `SortResolutionStrategy.TREE_DENSE_WALK` (the telemetry
label) is unchanged — this fix doesn't alter which strategy is reported, only how that strategy computes
its result internally.

## 4. Risks & mitigations

- **Off-by-one / wrong record set in `matchedIds`.** Mitigated by construction: `matchedIds.add(recordId)`
  replaces `notFound.remove(recordId)` in the exact same `if` branch, so the set of ids added is
  provably identical to the set of ids that were being removed today (§2.1). Test plan (§5) adds a
  direct equivalence assertion against the current implementation, not just black-box behavior.
- **Accidental use of the in-place `andNot` overload, mutating the caller's `selectedRecordIds`.**
  Called out explicitly in §2 and in code comments at the call site; a dedicated test (§5) asserts
  `selectedRecordIds` is unchanged (same cardinality/content) after the call.
- **This fix alone reads as "the regression is fixed" if skimmed.** It is not — §1 and §6 state
  plainly that it only closes 3-33% of the gap depending on selectivity. The commit/PR description
  implementing this must not overstate the win; it should reference the still-open `contains()` cost
  explicitly so nobody mistakenly closes the regression issue on the strength of this fix alone.

## 5. Test plan (TDD — red first)

Unit (`evita_functional_tests`, `SortedRecordsSupplierTest` or nearest existing suite covering
`resolvePositionsByDenseWalk` / `SortIndexTreeProviderEquivalenceTest`):

- **Equivalence against current behavior** — for a range of N/K combinations (including the
  sparse/dense boundary, K=0, K=N), assert the new implementation's `PositionResolution` (`mask`,
  `notFoundRecords`, `notFoundRecordsCount`, `strategy`) is *identical* to the current
  implementation's, run side by side (keep the old loop as a `@Deprecated`-free private reference
  method in the test only, or golden-master the outputs before changing production code — either is
  fine as long as the comparison is direct, not just "looks reasonable").
- **`selectedRecordIds` immutability** — assert cardinality and content of `selectedRecordIds` are
  unchanged after a `resolvePositions` call that takes the dense-walk path with a non-empty
  not-found set.
- **All-found / none-found / partially-found** cases explicitly, matching §2.1's edge-case list.
- **Descending direction** — the fix touches only the `matchedIds`/`notFound` construction, not the
  cursor direction logic, but re-run the existing descending-order dense-walk tests to confirm no
  interaction.

Full gate: `SortIndexTreeProviderEquivalenceTest` and `ChainIndexTreeProviderEquivalenceTest` (the
existing cold/warm/forced-resolution equivalence suites) must stay green — they already assert
strategy-level behavior for exactly this method's family.

## 6. Explicitly out of scope — the dominant, still-open cost

The same isolated-benchmark investigation that validated this fix also measured
`selectedRecordIds.contains(recordId)` — called once per position, i.e. N times regardless of K —
as the larger term at low-to-moderate selectivity (K/N≈0.02-0.1, plausibly representative of a large
share of real `attributeFiltering` traffic): 2.2-3.9 ms at those points on the same fixture, vs. this
fix's target term topping out at 0.7 ms. Two container-representation hypotheses for speeding up
`contains()` (`RoaringBitmapWriter.optimiseForArrays()` / `optimiseForRuns()` at construction time)
were tested as an isolated A/B and **refuted** — both were statistically indistinguishable from the
production default across the full selectivity sweep. No working streaming-compatible (i.e.,
no-array-materialization) fix for the `contains()` cost has been found. Applying only this plan's fix,
overall `resolvePositionsByDenseWalk` cost drops by 3-33% depending on selectivity — a real
improvement, but nowhere near sufficient on its own to close the 60-66% `attributeFiltering`
regression back to `dev` parity. Closing that gap further requires either a new idea for the
`contains()` cost, or reconsidering the array-materialization-cache approach this investigation set
out to avoid (see the parent report for the caching-ceiling numbers).

## 7. Implementation steps

1. Land the `SortIndexResolvePositionsBenchmark` spike (`evita_test/evita_performance_tests/.../spike/`,
   currently uncommitted, session-local) as a permanent perf-regression-guard benchmark, or fold its
   `denseWalk_bulkAndNot` / `denseWalk_notFoundRemoveOnly` methods into an existing sort-index spike
   suite — Johnny's call on which.
2. Write the §5 equivalence tests against the *current* implementation first (red — they should pass
   trivially against today's code, since they're characterizing existing behavior).
3. Apply the §2 code change to `resolvePositionsByDenseWalk`.
4. Re-run §5 tests (green) plus the full `SortIndexTreeProviderEquivalenceTest` /
   `ChainIndexTreeProviderEquivalenceTest` suites.
5. `mvn -pl evita_engine,evita_test/evita_functional_tests test -Dgroups="attribute & indexing"` (or
   the nearest matching tag expression) for the broader regression sweep.
6. Re-run the isolated JMH spike post-change to confirm the measured 2-19× term-level speedup and the
   3-33% whole-method reduction hold on the actual (not prototyped) code path.
7. Do **not** close or claim resolution of the `attributeFiltering` regression on this fix alone —
   re-run `ArtificialEntitiesThroughputBenchmark.attributeFiltering`/`attributeAndHierarchyFiltering`
   end-to-end against `dev` to measure the real, whole-query effect, and update the parent report with
   the result and the still-open `contains()` cost (§6).
