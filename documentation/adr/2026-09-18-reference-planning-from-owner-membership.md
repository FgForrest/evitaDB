---
title: Answer reference-planning cardinality from the owner→partition map, widened and retuned to 64
date: 2026-09-18
updated: 2026-09-23 23:30
status: partially-implemented
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
relates: [2026-09-09-sibling-resolver-partition-cardinality, 2026-09-17-row-scoped-reference-having-body, 2026-09-23-pick-first-reference-ordering-from-selection]
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

> **Corrected 2026-09-18 by measurement — read "Consequences & open follow-ups" before acting on this
> section.** The sentence below attributes the cost to index *selection*. Building that half and measuring it
> showed the attribution was wrong: the walk belongs to the constraint's *evaluation*, and making selection
> cheap moves the cost to `PLANNING_FILTER` instead of removing it. The latency figures stand; the diagnosis
> does not. The section is kept as written because the reasoning it drove is the subject of this record.

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

- **Three gates, one predicate.** `ReducedIndexMembership#isMaintainedFor` is the whole decision and a
  pure function of the schema, which is what makes maintenance stoppable only at a schema change. It is
  read by the write path (`ReferenceIndexMutator#isReducedIndexMembershipUnmaintained` — that predicate
  negated, guarding both maintenance boundaries), by the load-time rebuild
  (`EntityCollection#rebuildReducedIndexMembership`) and by the discard on schema change
  (`EntityCollection#discardUnmaintainedReducedIndexMemberships`). All three must change together or the
  `covered ∪ residual == advertised` invariant breaks in the direction that is silent — which is why the
  three share one method rather than three copies of a condition.
- **The threshold is a latency dial, never a correctness one.** Any quantity read from the map is *exact
  count over covered partitions + walk over the residual set*, so `T` decides how long that walk is, never
  whether the answer is right. As shipped the only reader is the cross-entity facet trigger: the planner takes
  its candidate count from index discovery and never touches this map. See the follow-ups below.
- **Raising `T` does not make a mutation slower.** A promotion rebuilds `O(T)` entries but cannot recur until
  `T/2` further writes, so the amortised crossing cost is ~2 entries per write regardless of `T`. A larger
  threshold buys a bigger map, not a slower write.
- **The eligibility test is a comparison, not a total.** `IndexSelectionVisitor:285` compares the summed owner
  counts against `mainIndexCardinality / 2`, so a sound lower bound settles it whenever the bound already
  exceeds the limit. The bound actually used is the **candidate count**: in every state the mutator produces,
  an advertised partition holds at least one owner, because `ReferenceIndexMutator#referenceRemovalPerComponent`
  un-advertises it in the same synchronous step in which its last owner leaves — `ReducedIndexMembership:356-369`
  states this and calls the zero-owner arm dead, while still *accounting* for it because the load path could
  present one. Were such a partition ever to reach the planner the count would overstate the sum and raise
  `HIGH_CARDINALITY` where the exact sum would not: the alternative plan is dropped, which costs latency and
  never rows.
- **The obstacle set now reports what was decided, not what is true.** Deciding from the schema first means the
  sum is never computed for a reference that is not partitioned, so `HIGH_CARDINALITY` no longer appears beside
  `NOT_PARTITIONED_INDEX` in `queryTelemetry()` for those references — the plan is identical, the diagnostic
  string is shorter. Nothing outside the tests reads it (no API surface or user doc carries the name), and
  `ReferenceIndexSelectionFunctionalTest#sumExceedsTheLimitButCountDoesNot` pins the absence, so restoring the
  eager sum to "fix" the telemetry fails that test rather than silently reverting the optimization.
- **Correction to an earlier draft of this record.** It claimed the bound could come from the map, as "covered
  rows are exact and every residual partition holds more than `T/2` owners". The **residual half is false**:
  `ReducedIndexMembership:233-245` says membership of that set is not a size predicate — a slice seeded by
  `ReferenceIndexMutator#seedFromAdvertisedIndexes` puts *every* partition there regardless of size. The
  covered half stands, but is not needed: summing covered partitions costs a walk of the covered owners, and
  the candidate count settles the same cases for nothing.
- **The sums are `long`.** They count reference rows, which are not bounded by the owner collection's
  cardinality; an overflowed `int` goes negative, satisfies the limit and marks the alternative *eligible*.
  Fixed in the same line of work.
- **`ReducedIndexMembershipCompletenessTest#assertMembershipMatchesIndexes` guards the facet trigger, and
  as shipped nothing else.** A slice present with both sets empty is the wrong-answer state the structure's
  javadoc warns about, but no query path reads this map — `rg ReducedIndexMembership evita_engine/.../core/query`
  finds one comment and no call — so the blast radius of that state is a wrong facet count. The widening to a
  wrong query *result* arrives with the translator consumer and not before. A failure of this assertion today
  is therefore not a `referenceHaving` correctness incident, and must not be triaged as one.

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
referenced primary key, which `EntityCollection#registerReducedIndex` states in prose — was confirmed by
execution rather than by reading: an assertion comparing `ReferenceTypeCardinalityIndex`'s per-index and
per-pair tallies on every write reported **zero violations** across the `reference | facet` gate (3,384
tests, 0 failures), and an inverted counterfactual proved the assertion live on the write path
(`ReferenceIndexMutator#referenceInsertPerComponent` → `insertPrimaryKeyIfMissing` → `addRecord`).

### The write-path A/B

**This measures bulk ingest, and bulk ingest only.** `Catalog:1793` states that in `WARM_UP` writes bypass the
transactional pipeline entirely, so the commit-time merge the map participates in never executed during these
four runs. What the table below establishes is that maintaining the map costs nothing detectable while rows are
being written; it says nothing whatever about `ALIVE`, which is measured separately two sections down.

Full `WARM_UP` catalog reindex plus `goLive` of the same production retail corpus (386,369 entities;
119,447 `Product`, 88–90 % of load time), through the gRPC driver into a separate server process, writer heap
23g and reader 14g/8g pinned across every run. Two runs per arm, alternated `B, A, B, A` so that any drift
over the session is shared rather than loaded onto one arm. All four verified their per-collection counts
against the source (harness exit `0`); every `goLive` completed rather than timing out.

| run | arm | load wall | `Product` mean upsert | TOTAL |
|---|---|---|---|---|
| 1 | widened, 64 | 831.5 s | 5,946.1 µs | 862.1 s |
| 2 | baseline, 16 | 828.9 s | 5,968.6 µs | 859.3 s |
| 3 | widened, 64 | 817.2 s | 5,855.6 µs | 847.9 s |
| 4 | baseline, 16 | 868.9 s | 6,280.1 µs | 899.7 s |

Arm means put the widened build **2.9 % faster** on load wall-clock and **3.7 %** on `Product` mean upsert —
but the baseline arm's own two runs differ by **4.8 %** and **5.2 %** respectively, so the gap between the
arms is smaller than the spread within one of them. The result is therefore **no measurable write-path cost**,
not an improvement: the harness resolves about ±9 % and nothing here clears it.

**The null result is not vacuous, and that was checked rather than assumed.** A census booting the same corpus
under each build and counting the membership slices the gate leaves behind reports **3 slices for the
baseline** — `Product`/`LIVE` only, `[brand, categories, groups]` — against **45 for the widened build**,
spread over 13 of 18 collections and both scopes, with `Product`/`LIVE` alone going from 3 tracked references
to 15. The widened arm maintains roughly 45× the partitions, matching the 211,148-against-4,708 split the
footprint sweep predicted, and it cost nothing detectable. The census measures the **gate predicate** rather
than which code path populated the map — a catalog load rebuilds it from the indexes either way — which is
sufficient here because the write gate and the load gate read the same predicate.

## Consequences & open follow-ups

**The `WARM_UP` write-path CPU of the widening was measured and is below the noise floor** — see the A/B
above. The expectation that `ownerAdded` costs roughly two transactional bitmap operations and one boxed map
lookup against a row insert already performing six B+ tree operations on the cardinality tallies alone is
borne out: 45× the partitions tracked, no detectable change in reindex time. This was the one measurement
that could still have argued for option B on ingest cost, and it does not.

**The `ALIVE` commit cost was a separate question, and the first answer was 23 ms per commit.** The A/B above
could not see it: `WARM_UP` bypasses the pipeline, so the merge never ran. Under `ALIVE` every commit that
dirties a collection's global index merges that index, and the global index merges the whole
`reducedIndexMembership` map with it (`GlobalEntityIndex:537`). A `TransactionalMap` holding
`TransactionalLayerProducer` values cannot early-out on an absent diff layer — a producer mutates through its
own layer, invisible to the map — so it walked **every** owner entry of **every** maintained reference on
every commit, touched or not. Reconstructed at the production corpus's `Product` LIVE shape (eight references,
182,583 owner entries) and committed through a real transaction, that cost **23.2 ms per commit**, and an arm
that wrote one owner cost the same as an arm that wrote nothing — the tell that the cost is the walk.

`indexPrimaryKeysByOwner` is therefore a `PersistentTransactionalProducerMap`, the CHAMP-backed map
`EntityCollection` already uses for its own partition maps, which path-copies only the keys the transaction
touched. Same shape, same probe: **0.086 ms untouched, 0.112 ms with one owner written** — 270×, and now
proportional to what was written. The two in-place bitmap mutations declare themselves through
`markValueMutated`; a forgotten declaration leaves an orphaned layer that `verifyLayerWasFullySwept` turns
into a `StaleTransactionMemoryException` at commit, so the failure mode is loud rather than silent. Nothing
about persistence changes — `ReducedIndexMembershipMapComponent` writes no storage part, because the map is
derived state rebuilt at load.

The lesson generalises past this record: **a `TransactionalMap` whose values are producers and whose size
scales with data volume pays `O(N)` on every commit of its owner, whether or not that transaction touched
it.** Anything else answering that description is a candidate for the same swap.

**The planner-side deferral was built and measured, and it does not make the query faster — the premise of this
half of the decision was wrong.** `IndexSelectionVisitor` now settles eligibility from the schema and the
candidate count and never resolves the partitions when they cannot change the outcome. Measured on the same
production catalog, `Product.media`:

| phase | before | after |
|---|---|---|
| `PLANNING_INDEX_USAGE` | 43.10 ms | **0.14 ms** |
| `PLANNING_FILTER` | 154.82 ms | 196.77 ms |
| `OVERALL` | 230.65 ms | 227.88 ms |

Index selection falls 300×; `PLANNING_FILTER` rises by 41.95 ms against the 42.96 ms saved. **The cost moves
rather than disappears.** `ReferenceHavingTranslator:145-151` looks up the same `TargetIndexes` by constraint
identity and forces it, because a query filtering on a reference must resolve that reference's partitions to
*evaluate* the constraint whichever plan wins. The rejected candidate's list was never waste; it is what the
winning `GLOBAL` plan consumes.

The earlier reading — "index selection dominates; every probed query walked the whole family only to reject
the reduced-index plan" — was an artifact of **where the cost was attributed**, not evidence that it was
avoidable. Anyone proposing to make index selection cheaper for this shape should stop here: it already is.

**The consumer that would pay for the widening is at the translator, not the planner.** A bare
`referenceHaving(R)` resolves the whole family to OR their owner bitmaps into "every owner with any row of
R" — the `⊤` set of `2026-09-17-row-scoped-reference-having-body`, and exactly what `getCoveredOwners()`
maintains, leaving a walk over the residual set only (38 partitions against 161,977 on
`ParameterValue.products`). That is where the 197 ms is. It is not designed yet, and the open question is how
fast a seeded slice converges, since `ReducedIndexMembership:233-245` puts every partition in the residual set
until each is written again.

This record stays `partially-implemented`: the widening is maintained and measured free on the write path, it
serves the cross-entity facet trigger it was built for, and its second consumer has moved rather than
arrived.

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
- `2026-09-23-pick-first-reference-ordering-from-selection` — a third consumer: a pick-first reference ordering
  finds the partitions of its selected owners through this map, which the widening to every indexed reference is
  what makes available on the references such orderings use.
