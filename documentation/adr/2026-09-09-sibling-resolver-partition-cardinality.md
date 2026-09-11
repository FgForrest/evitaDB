---
title: Bound the cross-entity facet walk with a size-thresholded owner→partition index, not a blanket one
date: 2026-09-09
updated: 2026-09-11 15:10
status: accepted
kind: optimization
issues: [1529]
prs: []
areas:
  - evita_engine/src/main/java/io/evitadb/index/mutation
  - evita_engine/src/main/java/io/evitadb/index
  - evita_engine/src/main/java/io/evitadb/index/map
  - evita_test/evita_performance_tests/src/main/java/io/evitadb/spike
supersedes: []
superseded-by: []
relates: [2026-04-23-conditional-facet-indexing, 2026-08-31-cross-entity-histogram-removal-pre-pass]
---

# Bound the sibling-resolver walk with a size-thresholded owner→partition index

`ReevaluateExpressionExecutor#collectOwnersOfReducedIndexes` walks **every** reduced partition of a
collection on **every** cross-entity conditional-facet trigger, intersecting each partition's member bitmap
against the affected owners. The cost is `O(total partitions)`, independent of how many owners the trigger
touches. Measured against a production catalog it is **0.78 ms** as that catalog is configured today — and
**81–113 ms** after a single schema edit any client can make. This record fixes what was measured, what the
numbers mean, and which of the candidate fixes is worth building.

**Vocabulary, because both words are used throughout and neither is obvious.** A **partition** is one
reduced entity index — a `ReducedEntityIndex` or `ReducedGroupEntityIndex` of
`EntityIndexType.REFERENCED_ENTITY` / `REFERENCED_GROUP_ENTITY`, one per distinct referenced entity. The
word is the schema flag's own: `FOR_FILTERING_AND_PARTITIONING` is documented as creating "partitioning
indexes for the main entity type". A partition's **members** are the owner-entity primary keys it holds
(`getAllPrimaryKeys()`) — what `EntityIndexType.REFERENCED_ENTITY`'s javadoc calls the record ids connected
to that referenced entity. For `Product.parameterValues`: one partition per parameter value, whose members
are the products carrying it.

The distinction carries the entire decision below, because **cost is per member while benefit is per
partition** — covering a partition costs one map entry per member and saves exactly one bitmap
intersection, however many members it has.

Option A is **implemented and measured**; the figures below distinguish throughout between what the
harness *modelled* and what the shipped code *does*. The prior constant-factor change (PRs #1524 / #1525)
shipped unmeasured and is now quantified — at far less than its cost model claimed.

**Three local optimizations of this loop were measured and all three failed**, two of them after being
argued for on a cost model that looked sound. That is the record's second finding, and it is worth as much
as the first: on this walk, do not ship a local fix on an allocation or instruction-count argument.

## Why

`2026-04-23-conditional-facet-indexing` measured this walk at 3,000 partitions, found it to be 1.4 % of a
trigger, and said plainly that the figure "does not generalise, and must not be quoted without its
cardinality". Issue #1529 exists to establish what happens at real cardinality. It projected the walk
reaching 32 % of a trigger at 100k partitions **by linear extrapolation** from ~157 ns/partition, and named
migrated catalogs as the population at risk, because `ReferenceSchemaSerializer_2025_5:118-125` promotes
every indexed reference of a pre-2025.7 schema to `FOR_FILTERING_AND_PARTITIONING`.

Both the mechanism and the magnitude turned out to be wrong, in opposite directions.

### Previous state

The walk iterates each partitioned sibling reference's `REFERENCED_*_TYPE` index, excluding the reference
the trigger fired for (`ReevaluateExpressionExecutor.java:449`), probes every advertised partition, and
intersects. Partitions that actually hold an affected owner are then re-fetched through
`getOrCreateIndexByPrimaryKey` (`:517`) to enrol them in the dirty set — required for correctness and
proportional to *intersecting* partitions only.

## The measurement

Production e-commerce catalog: 18 collections, 3.44 GB, `ALIVE`. `Product` holds 160,216 entities and
**281,784 entity indexes**, of which 281,497 are `REFERENCED_ENTITY`.

Regenerate with the committed spikes in `evita_test/evita_performance_tests/.../spike/`:
`ConditionalFacetPartitionCensus` (counts), `ConditionalFacetReverseIndexFootprint` (memory, threshold
sweep), `ConditionalFacetPartitionExistenceProbe` (which partitions exist at which index type),
`ConditionalFacetSiblingResolverReport` (timings). Only the last needs a quiet machine; the other three emit
counts and sizes.

### The exposure is a schema flag, not migration drift

Only **3 of 15** `Product` references are `FOR_FILTERING_AND_PARTITIONING` (`brand` 148 partitions,
`categories` 516, `groups` 3,969), so the walk probes **4,633**. The remaining eleven sit at
`FOR_FILTERING` — including `parameterValues`, the reference this issue named as the danger and the one
that actually carries the `facetedPartially` expression.

But their reduced indexes **already exist and are already populated** — verified partition by partition,
not inferred: `ConditionalFacetPartitionExistenceProbe` resolved every primary key advertised by every
`REFERENCED_*_TYPE` index of `Product`, and **all 205,315 belonging to `FOR_FILTERING` references resolved
to a live `ReducedEntityIndex`**, against 4,633 for the three partitioned ones. `media` alone holds 168,039.

This matters more than it looks, because two committed comments asserted the opposite —
`ReevaluateExpressionExecutor:346` ("Reduced indexes only exist when the reference uses
FOR_FILTERING_AND_PARTITIONING indexing") and `ReferenceIndexMutator:1247` ("the only ones for which reduced
indexes exist at all"). Both were wrong and both were load-bearing: they make the walk's cost look bounded
by the current schema, and they are why this record's own author briefly doubted a correct measurement.
Reduced indexes are created behind `isIndexedReferenceForFiltering` — anything but `NONE`
(`EntityIndexLocalMutationExecutor:2652` and peers). What `FOR_FILTERING_AND_PARTITIONING` governs is
**facet maintenance inside** them. Both comments are corrected in this change.

So what keeps 205,315 partitions out of the walk is one enum per reference, and nothing else — no absent
structure, no data to build. Counted from the live indexes without touching the schema:

| reference | advertises | state |
|---|---|---|
| `media` | 168,039 | `FOR_FILTERING` |
| `parameterValues` | 21,561 | `FOR_FILTERING`, carries the trigger |
| `relatedProducts` | 13,131 | `FOR_FILTERING` |
| `groups` / `categories` / `brand` | 3,969 / 516 / 148 | partitioned |
| **walk today** | **4,633** | |
| **every reference switched** | **209,948** | **45×** |

`ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING`'s own javadoc recommends itself "when reference
filtering is frequently used in queries and query performance is critical" — advice a client would
reasonably follow on `media` or `parameterValues`. **The walk's cost is unbounded in a dimension the client
controls, through a knob documented as an optimization.** That, not migration drift, is the exposure.

And because the partitions already exist, the flip needs **no reindexing, no republish and no entity
write**: `SetReferenceSchemaIndexedMutation` changes the schema, no engine handler rebuilds anything, and
`resolveSiblingReducedIndexes`'s filter simply admits 40× more partitions on the next trigger. The walk
goes from 4,633 to 188,387 partitions the moment the schema mutation commits.

A second, compounding case: the `:449` exclusion protects the trigger's own reference from its *own* walk,
never from anybody else's. A `facetedPartially` expression on a second reference makes today's protected
`parameterValues` that trigger's sibling.

### Timings

Quiet box, AMD Ryzen AI 9 HX 370, 16 MiB L3, `-Xmx48g`, JDK 21. All arms probe-only and checksum-gated on
the `(owner, partition)` pairs emitted; every run agreed. `WARM` = arms back-to-back, order rotated;
`COLD` = one arm per round with 128 MiB streamed outside the timed region. The two series are never mixed.

| P | shape | series | pre-#1524 walk | `HEAD` walk | hybrid `T`=16 |
|---|---|---|---|---|---|
| 4,633 | sparse (20 owners) | WARM | 814.7 µs | 776.1 µs | **289.1 µs** |
| 4,633 | sparse | COLD | 3.69 ms | 3.56 ms | **1.59 ms** |
| 4,633 | dense (57,962) | WARM | 2.88 ms | 2.89 ms | 2.45 ms |
| 188,387 | sparse | WARM | 83.9 ms | **81.4 ms** | **1.72 ms** |
| 188,387 | dense | WARM | 112.7 ms | **112.8 ms** | **10.6 ms** |

**Scaling is super-linear, as suspected but never previously shown.** Partition count grows 40.7 ×
(4,633 → 188,387) while walk time grows **104.8 ×**; the per-probe constant degrades from 167.5 ns to
431.8 ns. The issue's linear model predicted 39 ms at 250k partitions; the truth is 81 ms at 188k.

**Cache state matters more than any arm, and only at low `P`.** At 4,633 partitions COLD is **4.6 ×** WARM
(768 vs 167 ns/probe); at 188,387 they agree within 3 %, because nothing fits cache either way. A
warm-only protocol — which is what was originally planned — would have understated the low-`P` case
fourfold and hidden the knee completely.

### What the per-partition cost is actually made of

Two further arms bound what any local fix could recover. **D** replaces the engine's
`getIndexByPrimaryKeyIfExists` with a bare int-keyed hash get; **E** additionally pre-resolves the partition's
membership bitmap, leaving only the traversal and the `and()`. Neither is implementable — a real walk cannot
pre-resolve a transactional bitmap and still see the transaction's own writes — and that is the point: no
engine change can beat a bare hash lookup, so no engine change can recover more than these deltas.

| `P` | shape | `HEAD` (B) | index preresolved (D) | bitmap preresolved (E) | no transaction |
|---|---|---|---|---|---|
| 4,633 | sparse | 836.5 µs | 622.7 µs (−25.6 %) | 493.2 µs (−41.0 %) | 486.2 µs (−41.9 %) |
| 4,633 | dense | 2.77 ms | 2.59 ms (−6.6 %) | 2.38 ms (−14.0 %) | 2.55 ms (−8.2 %) |
| 188,387 | sparse | 80.69 ms | 73.30 ms (−9.2 %) | 46.36 ms (−42.6 %) | 77.05 ms (−4.5 %) |
| 188,387 | dense | 100.16 ms | 93.33 ms (−6.8 %) | 66.74 ms (−33.4 %) | 93.47 ms (−6.7 %) |

**The overhead is a constant per partition, not a share, and reporting it as a share is how this record's
first draft reached a wrong conclusion.** `B − E` is 74.1 ns/partition sparse and 84.0 ns/partition dense at
`P`=4,633 — the same number across a 2,900× change in affected-owner count. It reads as 41 % against one
denominator and 14 % against another. Quote nanoseconds per partition.

**At low `P` the whole gap is the transaction; at high `P` almost none of it is.** At `P`=4,633, arm E
(493.2 µs) lands on top of the no-transaction walk (486.2 µs): once the transactional layer resolutions are
gone, nothing else in the lookup costs anything measurable. At `P`=188,387 the no-transaction walk saves only
4.5 %, while E saves 42.6 % — so what B − E is buying there is **cache, not transaction machinery**. Reaching
a partition's bitmap through its `EntityIndex` costs 28.0 ns/partition at `P`=4,633 and **143.0 ns/partition
at `P`=188,387**, 5.1× worse on identical code: two pointer hops into objects that no longer fit in cache.

A probe inside a transaction resolves a transactional layer three separate times — the collection's
`DataStoreChanges`, the `indexesByPrimaryKey` ChampMap's `MapChanges`, and the partition's own bitmap — and
`getIndexByPrimaryKeyIfExists` additionally allocates an `IntFunction`, an `Optional`, a capturing lambda and
an `Integer` box per call. All of that together is the 39 ns/partition of `B − D`. It is real, and at high
`P` it is not the problem.

### The implementation, measured against the model that justified it

`ConditionalFacetMembershipReport` runs the shipped resolver's own shape against the walk it replaces, in one
process, with a bound transaction and rotated arm order, checksum-compared. Figures below are **two
independent runs**, 2026-09-11, same box, reported as `run A / run B` — a single run of this harness does not
resolve the dense high-cardinality walk better than ~11 %.

| `P` | shape | walk | lookup | |
|---|---|---|---|---|
| 4,633 | sparse | 653 / 681 µs | **232 / 232 µs** | 2.8–2.9× |
| 4,633 | dense | 12.02 / 12.06 ms | **11.22 / 11.02 ms** | 1.07–1.09× |
| 188,387 | sparse | 82.4 / 85.4 ms | **1.50 / 1.60 ms** | **54–55×** |
| 188,387 | dense | 259.6 / 230.2 ms | **173.0 / 172.2 ms** | **1.34–1.50×** |

Memory: **4.2 MiB** on the shipping schema, **24.9 MiB** with every reference partitioned. Rebuilding the
lookup over 188,387 reduced indexes costs **156.0 ms**, against a 26 s catalog load. Coverage reproduced
exactly across both runs and both configurations — 2,697 covered / 1,936 residual, and 185,475 / **2,912**.

**These figures supersede the ones this record carried until 2026-09-11, which were too high.** The harness
had diverged from `ReevaluateExpressionExecutor` in two ways its checksum gate could not see, because that
gate compares the emitted `(owner, index)` pairs and the pairs were identical either way:

- the lookup arm's covered half emitted the map's pairs directly rather than using the map as an index
  *selector* and probing what it named — so only one arm was short-changed, and the ratio was inflated
  directly;
- **neither** arm ran the executor's accumulator, the owner-keyed `Map<Integer, List<SiblingReducedIndex>>`
  built by `addSibling`. That one is symmetric — both arms emit the same pair set — but omitting a cost
  common to both inflates a ratio just the same.

The superseded row that mattered most was `188,387 dense`, published as **8.3×** and actually **1.34–1.50×**.
The `4,633 dense` row went from 1.41× to 1.07–1.09×. The two sparse rows barely moved.

**What the correction did not change: the decision.** The lookup is faster in all four shapes, nothing is a
regression, and the sparse cases — the ones a real conditional-facet trigger produces most often, since a
trigger names the owners of one changed attribute value — remain 2.8× and 54×.

**Why the dense rows are bounded, and why no threshold setting rescues them.** The accumulator's cost is
proportional to the number of `(owner, partition)` pairs in the *answer*, which both arms must produce in
full. `T` decides which partitions are probed; it cannot decide how large the answer is. In the dense shape
the affected set is 57,962 owners out of 160,216 entities — roughly 36 % of the collection — so most sibling
partitions genuinely hold an affected owner and there is nothing to skip. Subtracting the accumulator (about
136 ms, derived as the walk's before/after difference rather than measured directly) leaves the probe work
alone at roughly 3.3×, which is the optimization's actual contribution; the accumulator dilutes it to the
measured 1.34–1.50×.

**Absolute figures are not comparable across the harness correction.** Any number quoted from this record
before 2026-09-11 is a lower bound on the lookup and an upper bound on the speedup.

**Absolute figures do not transfer between the two spikes.** The same walk measures 1.76 ms here and 836 µs
in `ConditionalFacetSiblingResolverReport`, 103 ms here and 80.7 ms there. Only ratios within one run mean
anything — the cross-run comparison is what produced a spurious +11.6 % on this loop once before.

## Options considered

### Option A — hybrid owner→partition index with a per-partition size threshold (chosen for the structural fix)

Cover partitions of at most `T` owners in an `ownerPK → partitionPK` map; leave larger partitions on the
walk. Derived at catalog open, maintained at the existing owner-membership boundaries.

- **Pros:** `T`=16 removes 96 % of the walk for **13 %** of the blanket structure's memory. At P=188,387 it
  takes the sparse walk from 81.4 ms to 1.72 ms and the dense one from 112.8 ms to 10.6 ms. References whose
  partitions are all large disqualify themselves automatically — `stocks`, `stockVisibilities` and
  `bonusVisibilities` each produce a **112-byte map covering zero owners** — so no per-reference heuristic is
  needed.
- **Cost: 36.3 MiB, not the 24.6 MiB an earlier draft of this record quoted.** The two figures scope the map
  differently and only one of them is a structure that can be maintained. 24.6 MiB covers one trigger's
  sibling set, with the mutated reference excluded at *build* time — but which reference is mutated changes
  per trigger, so no such map exists. The maintainable structure covers every reference whose partitions the
  walk could ever visit (`P`=209,948) and filters the mutated one **at use**: 36.3 MiB, 8,308 residual
  partitions. Against 274.7 MiB for the blanket structure the decision is unchanged; the number is not.
- **Cons:** a permanent resident structure; threshold crossings are bulk operations; the map must join
  transactional memory *and* the warm-up savepoint; and it needs a schema hook (see *Key technical details*).

### Option B — blanket owner→partition index on every `ReferencedTypeEntityIndex` (declined)

The structure #1529 proposes: cover every partition.

- **Pros:** removes the walk entirely; simplest to reason about.
- **Rejected because:** **cost is per membership while benefit is per partition**, and the two are
  decoupled by 40,000 × across references of one collection. A partition with 21,467 owners costs 21,467
  map entries and saves exactly **one** probe. Measured: 78.2 MiB today (17.7 KB per probe removed) and
  274.7 MiB if every reference were switched — against 36.3 MiB for Option A at 96 % of the benefit.
  `stocks` alone would cost 33.4 MiB to remove 9 probes. **Revisit if** a catalog is ever found whose
  partitions are uniformly small, where the threshold would cover everything anyway.

### Option C — hoist the loop-invariant transaction resolution out of the walk (declined)

`TransactionalDataStoreMemoryBuffer#getIndexIfExists:112` resolves a transactional layer for the *same*
data source on every iteration, and `TransactionalBitmap#getRoaringBitmap:198` reads the `CURRENT_TRANSACTION`
ThreadLocal again per partition. `Transaction.java:331-338` already tells callers touching several
transactional members to resolve once and pass down, pricing the machinery at 5.25 % of busy-thread wall
time elsewhere. An earlier draft of this record chose it, and chose it *first*.

- **Pros:** zero memory, local, no new resident structure.
- **Rejected because it is worth 4.5–6.7 % where it matters, and nothing at all once Option A ships.**
  Measured as a ceiling by running the identical walk with no transaction bound:

  | configuration | with tx | without | share | absolute |
  |---|---|---|---|---|
  | P=4,633 sparse | 836.5 µs | 486.2 µs | 41.9 % | 0.35 ms |
  | P=188,387 sparse | 80.69 ms | 77.05 ms | **4.5 %** | **3.64 ms** |
  | P=188,387 dense | 100.16 ms | 93.47 ms | **6.7 %** | **6.69 ms** |

  That is the *ceiling*; a real hoist recovers only part of it, because one of the three layer resolutions
  per partition is the partition's own bitmap and cannot be hoisted at all. **And the two options are not
  additive**: after A the walk covers 2,912 partitions instead of 188,387, leaving roughly 0.1 ms of
  transaction overhead on it. A hoist landing before A is thrown away by it; landing after A it is worth
  nothing. **Revisit if** Option A is abandoned, or independently for a workload where this machinery is
  the dominant cost — `Transaction.java:331-338` already documents one, and it is not this walk.

### Option F — gate the intersection behind an allocation-free `intersects` test (declined)

The walk materialises an intersection bitmap *and* an `int[]` for every probed partition, including the
overwhelming majority that share no owner with the affected set. `PersistentRoaringBitmap#intersects`
allocates nothing and short-circuits on the first common bit.

- **Pros:** implementable as a two-line reordering of operations that already exist; zero memory.
- **Rejected because it is a regression in the shape that matters most.** Measured at P=188,387: **−5.8 %**
  sparse (80.63 → 75.96 ms) but **+8.2 %** dense (101.24 → 109.55 ms). At DENSE nearly every partition *does*
  intersect, so `intersects` finds a hit and `and()` then re-walks the same containers — a second pass for
  nothing. The sparse gain is also smaller than the allocation argument predicts, because `and()` over two
  bitmaps with no common container keys is already cheap and the discarded result is empty. **Revisit if**
  a workload is found whose triggers are reliably sparse; the sign of this change depends on the shape.

### Option D — do nothing (declined)

- **Pros:** the walk is 0.78 ms on the measured catalog as configured; the 2026-04-23 assessment stands.
- **Rejected because:** it stands only for that configuration. One enum edit takes it to 81–113 ms, with no
  data growth, no migration, and no warning — and the enum is documented as a performance feature.

### Option E — serve partition facets as `globalIndex facets AND partition members` at query time (not evaluated)

Recorded in #1529 as the way to delete the walk, the registration and the per-owner writes together.
**Rejected because** it was out of scope for a measurement issue and its price — a query-time intersection
per facet lookup plus recomputing per-partition facet counts — was never measured. **Revisit if** the
write-path fixes prove insufficient.

## Decision

**Option A, alone.** It is the only candidate that changes the asymptotics, and the threshold is what makes
it affordable: 36.3 MiB rather than 274.7 MiB, for 96 % of the benefit.

**Every local alternative was measured and every one failed.** C is worth 4.5–6.7 % and nothing at all once
A ships; F is a regression in the dense shape; the D and E bounds show that even a *perfect* index lookup
recovers 9.2 % and a perfect bitmap resolution 42.6 % — and the latter is unreachable except by holding a
flat partition→bitmap map, which is itself a reverse index. Nothing local saves the high-`P` walk. Only not
walking does.

**Do not re-propose a local fix here on a cost model.** Three have now been argued convincingly and
measured as regressions or near-nothing: the two named in `2026-04-23-conditional-facet-indexing`, and
Option F above. The two shapes disagree in sign, so a single-shape measurement is not evidence either.

**Option A must not ship as the blanket structure #1529 describes.** If a future reader finds this record
while proposing that structure, the number to look at is the 40,000 × spread in benefit-per-byte across
references of a single collection.

## Key technical details

- The walk: `ReevaluateExpressionExecutor.java:480-527`; sibling selection and the mutated-reference
  exclusion at `:441-466`, notably `:449`. Registration at `:517`, proportional to intersecting partitions
  and **unchanged by any option here** — a reverse index changes how intersecting partitions are *found*,
  never which ones they are.
- Maintenance boundaries for Option A already exist and already return the owner 0→1 / 1→0 signal:
  `ReferenceIndexMutator.java:808`, `:820` (insert) and `:972`, `:984` (remove). `:770` / `:936` maintain
  the *referenced-entity → partition* direction and are a separate concern.
- **Threshold crossing is O(1) amortised**: a partition can only cross upward once per `T` insertions into
  it. Use hysteresis (promote at `T`, demote at `T/2`). In `WARM_UP` partitions grow monotonically, so each
  crosses at most once.
- **The map must be `TransactionalMap<Integer, TransactionalBitmap>`, or an equally rollback-capable
  structure.** A plain `Map<Integer,int[]>` is *not* a `TransactionalLayerCreator`, so it never reaches
  `WarmUpSavepoint#verifyRollbackSupported` and would diverge **silently** on a rolled-back transaction or
  a failed warm-up mutation. This is a correctness disqualification, not a footprint trade-off — and it
  costs 6-8 ×, not the "roughly half" #1529 assumes.
- A new field on `ReferencedTypeEntityIndex` must be threaded through
  `createCopyWithMergedTransactionalMemory` (`:804-823`) and is picked up by `removeLayer` (`:826-831`) via
  component registration.
- **The map deliberately has NO schema hook, and must be an accelerator rather than an authority.** The
  map's domain — which references contribute entries — is decided by `getReferenceIndexType`, and that flag
  can flip with no entity write to hang maintenance off, over partitions that already exist. The tempting
  conclusion is that a raise must rebuild the map. It must not: **the engine does not rebuild indexes on a
  schema change at all** (issue #409 owns that work and has not landed), and the supported way to change a
  reference's index type is a **full reindex**, which builds the map from the final schema with nothing to
  react to. A hook here would be schema-change index maintenance implemented in one corner of the engine,
  and would rot the moment #409 lands.
  What makes that safe is a property the design needs anyway: **anything the map does not cover stays on
  the walk.** Partitions above the threshold already work that way; extending "not covered" to include
  "reference not in the map" costs nothing. A flag raised on a live catalog without a reindex therefore
  yields the ordinary unaccelerated walk over the newly-visited partitions — correct, merely slow, which is
  exactly today's behaviour and the right failure mode for a state the engine does not support.
  **When #409 lands, this map is one of the structures it must rebuild.**
- **Resident cost tracks what the client turned on:** 4.0 MiB on today's schema (three partitioned
  references), 36.3 MiB only if every reference is raised and the catalog reindexed.
- **Derived at load, not persisted.** Deriving costs one pass over the partitions — the same traversal the
  walk does, paid once per collection load — which avoids a storage format change for a structure that is
  pure acceleration.
- **Option A tolerates a null partition PK, and the design review that forbade it was incomplete.** The
  original reasoning was: today's walk cannot meet a stale PK, because it only visits currently-advertised
  partitions; a reverse map reads its own state and would go straight to `getOrCreateIndexByPrimaryKey`,
  whose accessor returns `null` for a removed index. Index PKs come from a monotonic sequence and are never
  reused, so a stale entry naming a *dropped* index is a hard failure rather than silent corruption. On that
  shape alone, throwing is the right call.

  It misses the other shape. A stale entry can equally name an index that still **exists** while the owner
  has already left it. There is no null to throw on: the probe finds a live index and hands back owners that
  do not belong in the trigger's set — silent wrong data, which is the very failure the throw was meant to
  prevent and the one it cannot see. The shipped resolver therefore intersects the probed index's members
  with the affected owners (`ReevaluateExpressionExecutor#collectOwnersOfProbedIndexes`), rejecting both
  shapes with one test; the `null` branch is then the same rejection reached a step earlier, not a papering
  over.

  The invariant is not abandoned — it is enforced where it can be enforced completely.
  `ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes` compares the map against the live
  indexes in both directions, in every scope, and refuses to pass vacuously. That is strictly stronger than
  a runtime throw, because it also catches the stale-positive the throw is blind to, and it costs operators
  nothing at runtime.

## Verification

Measurement only; no production code changed. Every arm is checksum-gated on the `(owner, partition)` pairs
it emits and all runs agreed, so no arm was compared while computing a different answer.

Six arms, all checksum-gated against each other: the pre-#1524 walk, current `HEAD`, the hybrid, the two
unimplementable bounds (D, E) and the `intersects` gate (F). Run-to-run reproducibility across independent
JVMs: `HEAD` at P=188,387 sparse measured 80,687,553 ns and 80,626,005 ns — **0.08 % apart**; the dense pair
1.1 % apart. The deltas quoted here are resolved comfortably by the paired warm-rotation series.

**The implementation's own tests are proved by counterfactual, not by being green.**
`ReducedIndexMembershipCompletenessTest` asserts the lookup against the reduced indexes themselves — never
against its own bookkeeping — and each mechanism was disabled in turn to confirm the assertions are
load-bearing. Disabling the remove boundary fails exactly four tests with
`coveredOwners must be exactly the owners of covered indexes ==> expected: <[2, 3, 4, 5]> but was: <[1, 2, 3, 4, 5]>`.
This matters more than usual here: the failure mode is silent, because a lookup that omits an entry makes the
trigger skip a facet registration, and a missing facet is invisible to anyone not looking for it.

The completeness quantifier is also checked statically, since a green test says nothing about an unhooked
path: `rg -n "insertPrimaryKeyIfMissing|removePrimaryKey" evita_engine/src/main/java` returns exactly four
sites that mutate a **reduced** index's membership, and both hooks cover all four; every other hit is the type
index or the global index.

`ConditionalFacetPartitionExistenceProbe` (counts only, no quiet machine needed) is what establishes that
205,315 partitions of `FOR_FILTERING` references already exist and resolve to live indexes; it prints its
own verdict line so the claim can be re-checked in one run rather than re-derived.

**The constant-factor change that shipped unmeasured (PRs #1524 / #1525, `5db4385e1`) is now quantified:**
its cost model claimed 20-35 % of the walk; measured **4.7 %** (P=4,633 sparse warm), **3.6 %** cold,
**0.0 %** in the dense shape, **3.1 %** at P=188,387. A real, consistent, small improvement. Shipping it
without a performance claim was the right call — the claim it was never given would have been wrong.

## Consequences & open follow-ups

- **#1529's decision rule cannot be applied as written.** Its second clause requires "the sparse shape
  shows a registration saving too", and no reverse index can deliver one: registration fires for exactly
  the partitions holding an affected owner, and the union of the affected owners' partition sets *is* that
  set. Its first clause ("walk exceeds ~10 % of the trigger") is also corpus-dependent, since
  `trigger total ≈ affectedOwners × refsPerOwner × groups` — all three chosen by whoever builds the
  fixture. Report the walk in **absolute milliseconds**; that is what this record does.
- **The hybrid's own cost scales with affected owners.** Arm C is 47 × faster than `HEAD` in the sparse
  shape but 10.6 × in the dense one, because it performs one boxed `HashMap` lookup per affected owner and
  the dense shape has 57,962. **A primitive int-keyed map is a separate, unmeasured lever.**
- **The dense shape is bound by the accumulator, not by the walk, and `T` cannot move it.** Once the harness
  was corrected (2026-09-11) the dense rows fell to 1.07–1.09× and 1.34–1.50×. The reason is
  `ReevaluateExpressionExecutor#addSibling`: it runs once per `(owner, partition)` pair of the *answer*, and
  both the lookup and the walk must produce that answer in full, so it is a constant common to both and
  dilutes any ratio built on top of it. Raising the coverage threshold changes which partitions are probed;
  it cannot change how many pairs exist. **Roughly 136 ms of the 188,387-dense figure is this, derived as the
  walk's before/after difference across the harness correction and never profiled directly.** The candidate
  on inspection is the boxed `Map<Integer, List<SiblingReducedIndex>>` and its per-pair `computeIfAbsent` —
  not the `contains` de-duplication beside it, which scans a four-element list of identity-compared records.
  **Measure before changing either.**
- **`DEFAULT_COVERAGE_THRESHOLD` = 16 has never been swept against the shipped implementation.** Every
  threshold figure in this record comes from `ConditionalFacetReverseIndexFootprint`, which models memory
  only. `ReducedIndexMembership(int)` exists, but both real construction sites — `GlobalEntityIndex:476` and
  the spike's `buildSlicesFor` — call the no-argument constructor, so the value is not reachable without
  plumbing it through. A sweep would trade memory against the residual probe and is the one lever that
  genuinely improves the **sparse** cases, which are the shape a real trigger produces most often.
- **Schema evolution changing `ReferenceIndexType` is now traced.** It does move the map's domain, and the
  resolution is deliberately *not* to react to it — see *Key technical details*. **The completeness test
  must pin the degradation property instead**: with a reference raised on a populated collection and no
  reindex, a trigger must still register every owner it owes, via the walk, unaccelerated. That is the
  assertion that keeps the map an accelerator; without it, a later change could quietly make the map
  authoritative and turn an unsupported-but-correct state into a wrong one.
- **Still unmeasured: the `WARM_UP` build cost of the map.** Every figure here is steady-state. Deriving the
  map at catalog open walks every partition once — the same traversal the walk does, paid once — but the
  bulk-load path builds partitions incrementally and its cost was never measured.
- **The trigger total was never measured**, so "walk as a share of the trigger" is not reported. At 81 ms
  of walk it can no longer change a conclusion.
- **This map exposed a pre-existing hole in `MapChanges`, and any future producer-valued
  `TransactionalMap` can reach it.** When a key holding a `TransactionalStateProducer` is overwritten (or
  removed and re-inserted) and then removed again inside one transaction, the commit-time sweep visits the
  key but finds the *delegate's original* under it — the replacement instance is visited by nothing, its
  nested diff layer orphans, and the commit fails with `StaleTransactionMemoryException` **after** the
  version reached disk. `ProducerMapChanges#createMergedChampMap` shared the blind spot for the same
  reason: it also releases `getMapDelegate().get(key)`. The membership map is simply the first structure
  that churns one key's value repeatedly within a single entity mutation, which is why the hole went
  unseen. Fixed by stashing the discarded replacement under the existing survivor-guarded, commit-time
  release; both commit paths are covered because neither overrides `remove`.
- **One catalog is not a population.** Every count here is exact for one snapshot of one schema.

## Related work

- `2026-04-23-conditional-facet-indexing` — introduced this walk and measured it at 3,000 partitions;
  this record supplies the cardinality that assessment explicitly deferred, and confirms its caution
  (two "obvious" optimizations of the same loop had measured as regressions).
- `2026-08-31-cross-entity-histogram-removal-pre-pass` — rejected its Option D for want of an
  owner→references mapping. Option A here would supply one, but only for partitions below the threshold,
  which may not satisfy that use case.

## Timeline

- **2026-09-09** — arm (b) shipped unmeasured in PRs #1524 / #1525; #1529 opened to fix the protocol
- **2026-09-09** — census, footprint, threshold sweep and timings taken against a production catalog
- **2026-09-09** — cost decomposed into its parts (arms D/E); Options C and F measured and both declined;
  partition existence at `FOR_FILTERING` verified, two committed comments corrected; decision narrowed to
  Option A alone
