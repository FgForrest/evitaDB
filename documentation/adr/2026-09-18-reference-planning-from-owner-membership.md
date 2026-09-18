---
title: Answer reference-planning cardinality from the owner→partition map, widened and retuned to 64
date: 2026-09-18
updated: 2026-09-18 15:08
status: proposed
kind: optimization
issues: [1585, 1603]
prs: []
areas:
  - evita_engine/src/main/java/io/evitadb/index/membership
  - evita_engine/src/main/java/io/evitadb/index/mutation/local
  - evita_engine/src/main/java/io/evitadb/core/query/indexSelection
  - evita_engine/src/main/java/io/evitadb/core/collection
  - evita_test/evita_performance_tests/src/main/java/io/evitadb/spike
supersedes: []
superseded-by: []
relates: [2026-09-09-sibling-resolver-partition-cardinality, 2026-09-17-row-scoped-reference-having-body]
---

# Answer reference-planning cardinality from the owner→partition map, widened and retuned to 64

A `referenceHaving` whose body cannot narrow index discovery walks the reference's entire reduced-index
family, and the walk — not the filtering — is what the query costs. The same walk is what supplies the owner
set a row-scoped body needs when it negates. Rather than build the persisted per-owner counter
`2026-09-17-row-scoped-reference-having-body` priced for that purpose, this decision reuses the derived
owner→partition map that already exists for the cross-entity facet trigger: maintain it for every reference
indexed for filtering instead of only for conditional-facet collections, and raise its coverage threshold
from 16 to 64.

## Why

Index *selection* dominates. Measured on a production retail catalog, a bare `referenceHaving(R)` costs
204 ms on `Product.media` (169,102 partitions) and 222 ms on `ParameterValue.products` (160,216 partitions),
against 0.24–0.68 ms for the same constraint when the body narrows discovery to a single partition. Every
probed query walked the whole family only to reject the reduced-index plan and run the global one.

The constraint that made the answer non-obvious is that two different consumers want almost the same data and
one of them cannot tolerate approximation. Plan choice — the `HIGH_CARDINALITY` test at
`IndexSelectionVisitor:285` — is a threshold comparison whose worst outcome is a slower plan. The `⊤` residue
that `2026-09-17-row-scoped-reference-having-body` identified is a term in a correctness identity: supply an
incomplete owner set and negated queries return wrong answers, which was the defect of #1585 itself.

### Previous state

`ReducedIndexMembership` maps owner → the reduced indexes holding it, per reference and scope. It is derived,
never persisted, and rebuilt when a collection loads. Coverage is thresholded because cost is per membership
while benefit is per partition, and maintenance is gated twice over: the collection must declare a conditional
facet (`ReferenceIndexMutator#isReducedIndexMembershipMaintained`, `EntityCollection:1892`,
`EntityCollection:1999`) and the reference must be `FOR_FILTERING_AND_PARTITIONING`. On the measured catalog
that leaves three of the four reference-heavy collections carrying nothing at all.

## Options considered

### Option A — widen the existing map to every indexed reference, threshold 64 (chosen)

Drop the conditional-facet conjunct at all three gates, loosen the reference condition to "indexed for
filtering", and raise `DEFAULT_COVERAGE_THRESHOLD` to 64. The planner reads exact counts over covered
partitions and walks the residual set, which stays small by construction.

- **Pros:** no persisted format, so no Kryo serializer, no backward-compatibility reader and no schema-change
  recipe; the write-path maintenance seam (`recordOwnerEnteredReducedIndex`) and the load-time rebuild already
  exist; the per-owner row count is the size of a bitmap the structure already maintains; one structure rather
  than two with overlapping content.
- **Cons:** +66 MB resident over the status quo; the threshold now serves two consumers with different
  economics; a structure documented as "an accelerator, never an authority" acquires a consumer for which its
  partition invariant is load-bearing.

### Option B — build the persisted per-owner counter (declined)

The structure `2026-09-17-row-scoped-reference-having-body` priced as option (b) for the `⊤` residue: a
per-(owner, reference) row count plus owner bitmap, persisted alongside the reduced indexes.

- **Pros:** exact by construction with no residual walk at all; independent of anyone else's tuning; measured
  at 6.27–10.76 MB, roughly a seventh of what the widened map costs.
- **Rejected because:** it pays a persisted-format change — write path, Kryo, backward compatibility with the
  latest release branch — to obtain data that is a pure function of the reduced indexes' membership bitmaps
  and can therefore be rebuilt at load for nothing. It would also duplicate content the widened map already
  holds, leaving two structures to keep in step. It becomes the better option again if the write-path A/B
  shows the widened maintenance is too expensive on the hot path, since a persisted counter moves that cost
  to load time.

### Option C — make the map complete, dropping the threshold (declined)

Cover every partition regardless of size, so the map answers every question with no residual walk.

- **Pros:** the simplest contract — no covered/residual partition to maintain or test; subsumes the per-index
  fan-out question as well, since it names which partitions hold a given owner.
- **Rejected because:** measured at 302 MB against 69.9 MB for threshold 64, and the 232 MB buys the removal
  of 3,527 probes spread across references that already answer in under a millisecond. Coverage cost is per
  membership while benefit is per partition, so completeness spends its memory precisely on the references
  whose walk is cheapest. It becomes worth revisiting if a consumer appears that needs the partition *set*
  rather than the count — the N·M expander fan-out named in
  `2026-09-17-row-scoped-reference-having-body` is the candidate.

## Decision

**Chosen: Option A.** The driver is read latency, and the widened map removes 99.3 % of the walk for 1.4 % of
the heap it accelerates while avoiding a persisted-format change entirely. Threshold 64 rather than 16 is what
makes it answer the question actually asked: 16 covers `Product.media` completely but leaves
`ParameterValue.products` — the second of the two references whose latency motivates the work — walking 40,368
of its 161,977 partitions.

The two changes are justified together and should land together. Raising the threshold alone, with maintenance
still gated to conditional-facet collections, would take `Product`'s memory from 4.4 MB to 12.9 MB to reduce a
residual walk of 2,012 probes to 960 — a poor trade for that consumer on its own. It is the planner, needing
every indexed reference covered, that makes 64 the right value.

Option B wins again if the write-path A/B shows the widened maintenance is materially expensive per mutation;
option C wins if a consumer needs the partition set rather than its size.

## Key technical details

- **Three gates, one predicate.** `declaresConditionalFacetInScope` is read at
  `ReferenceIndexMutator:1343` (write path), `EntityCollection:1892` (load-time rebuild) and
  `EntityCollection:1999` (discard on schema change). All three must change together or the
  `covered ∪ residual == advertised` invariant breaks in the direction that is silent.
- **The threshold is a latency dial, never a correctness one.** Every quantity the planner takes from the map
  is *exact count over covered partitions + walk over the residual set*. `T` decides how long that walk is,
  not whether the answer is right.
- **Raising `T` does not make a mutation slower.** A promotion rebuilds `O(T)` entries but cannot recur until
  `T/2` further writes, so the amortised crossing cost is ~2 entries per write regardless of `T`. A larger
  threshold buys a bigger map, not a slower write.
- **The eligibility test is a comparison, not a total.** `IndexSelectionVisitor:285` compares the summed owner
  counts against `mainIndexCardinality / 2`, so a sound lower bound settles it whenever the bound already
  exceeds the limit — covered rows are exact and every residual partition holds more than `T/2` owners.
- **`ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes` becomes load-bearing for query
  correctness**, not only for the facet trigger. The structure's own javadoc already warns that a slice
  present with both sets empty is a wrong-answer state rather than a slow one; this decision widens the blast
  radius of that state from a wrong facet to a wrong query result.

## Verification

Threshold sweep with `ConditionalFacetReverseIndexFootprint` against a restored production retail catalog
(160,216 products, 18 collections, 41 indexed references, 4.92 GB of reduced-index heap), over the four
reference-heavy collections, maintained for **every** indexed reference:

| threshold | reverse map | partitions still walked (of 490,280) |
|---|---|---|
| 1 | 19.9 MB | 232,443 |
| 4 | 28.0 MB | 127,731 |
| 16 (current) | 45.5 MB | 49,442 |
| **64 (chosen)** | **69.9 MB** | **3,527** |
| 256 | 96.4 MB | 1,304 |
| 4,096 | 170 MB | 134 |
| unbounded | 302 MB | 0 |

Today's resident cost is **4.2 MB**, all of it `Product`. At threshold 64 the widened map is **1.4 %** of the
4.92 GB it accelerates. `ParameterValue.products` falls from 40,368 partitions walked to **38**;
`Product.media` is fully covered from threshold 4 onward for 16.5 MB, its 169,102-partition walk removed
entirely. What still walks at 64 is `Product.parameterValues` (2,254), `groups` (550), `categories` (341),
`relatedProducts` (201), `brand` (69), `tags` (47) and four nine-partition references — the small families
whose bare existence already answers in 0.21–3.2 ms.

The invariant the reuse rests on — that a reduced index is filed in its type index under exactly one
referenced primary key, which `EntityCollection:1927–1931` states in prose — was confirmed by execution rather
than by reading: an assertion comparing `ReferenceTypeCardinalityIndex`'s per-index and per-pair tallies on
every write reported **zero violations** across the `reference | facet` gate (3,384 tests, 0 failures), and an
inverted counterfactual proved the assertion live on the write path
(`ReferenceIndexMutator#referenceInsertPerComponent` → `insertPrimaryKeyIfMissing` → `addRecord`).

## Consequences & open follow-ups

**The write-path CPU of the widening is unmeasured.** Footprint is not throughput. `ownerAdded` costs roughly
two transactional bitmap operations and one boxed map lookup per reference row, against a row insert that
already performs six B+ tree operations on the two cardinality tallies alone — so the marginal cost is
expected to be small, and the amortised crossing cost is threshold-independent. That expectation wants an A/B
over a full catalog reindex with the gate open and closed before the change lands. It is the one measurement
that could still argue for option B.

**The threshold's recorded justification is now wrong by omission.**
`2026-09-09-sibling-resolver-partition-cardinality` justifies 16 as "96 % of the walk for 13 % of the memory",
measured for the cross-entity facet trigger alone. With a second consumer the sentence needs to say whose walk
and whose memory, and that record's follow-ups should point here.

**The representation, not the concept, is where the memory is.** The same sweep prints an `int[] floor` for
each reference: the `TransactionalMap<Integer, TransactionalBitmap>` encoding costs **7–10×** a primitive-array
one, so threshold 64 floors at ~15.5 MB rather than 69.9 MB. Worth doing only if the footprint becomes a
blocker; the transactional wrapper is what is being paid for.

**Measured on LIVE scope and four of eighteen collections.** The four carry the reference-heavy schemas, but
the remaining fourteen will add some, and `ARCHIVED` was not measured at all.

**A redundancy found while verifying the invariant, deliberately not folded in.**
`ReferenceTypeCardinalityIndex` keeps `+pack(indexPk, 0)` and `-pack(indexPk, referencedPk)`, which the
confirmed invariant makes numerically identical; the positive family is read nowhere but the boundary
detection inside `addRecord`/`removeRecord`. Removing it halves that tree — about 485,000 entries and 8.5 MB
on the measured references — and halves the tree churn of every reference-row insert, since each increment is
a lookup, a remove and a re-add. It touches a persisted format with its own serializers and
backward-compatibility readers, and four tests currently pin the capability being removed, so it belongs to
its own issue rather than to this line of work.

## Related work

- `2026-09-09-sibling-resolver-partition-cardinality` — created `ReducedIndexMembership` and chose the
  threshold of 16 for the cross-entity facet trigger. This decision reuses that structure for a second
  consumer and retunes the value; the structural decision it took stands unchanged.
- `2026-09-17-row-scoped-reference-having-body` — identified the `⊤` residue and priced the persisted counter
  this decision declines in favour of the derived map, and named the planning levers this subsumes.
