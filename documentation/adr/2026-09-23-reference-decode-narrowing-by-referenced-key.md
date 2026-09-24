---
title: Bound the reference decode by the referenced keys a requirement names, and let named reference content own the names only it asked for
date: 2026-09-23
updated: 2026-09-23 18:50
status: accepted
kind: optimization
issues: [1637, 1640]
prs: [1638, 1639]
areas: [evita_api/src/main/java/io/evitadb/api/requestResponse, evita_engine/src/main/java/io/evitadb/core/query/response, evita_engine/src/main/java/io/evitadb/core/collection, evita_engine/src/main/java/io/evitadb/spi/store/catalog/persistence, evita_store/evita_store_entity, evita_store/evita_store_server]
supersedes: []
superseded-by: []
relates: [2026-09-11-reference-name-narrowing]
---

# Bound the reference decode by the referenced keys a requirement names

A reflected reference makes a shared entity the owner of one back-reference per entity pointing at
it. On a production retail catalogue one `ParameterValue` owns **72 342** `products` back-references,
and the GraphQL operation that reads it wants about **twenty** of them — named explicitly, in the
reference filter, as `entityPrimaryKeyInSet`. The name axis shipped in
[`2026-09-11-reference-name-narrowing`](2026-09-11-reference-name-narrowing.md) cannot help here,
because the projection *does* ask for `products`. This record adds the second axis: the same
thread-bound coverage now carries, per reference name, the exact referenced primary keys the caller
admitted, and `ReferenceSerializer.read` steps the stream past a reference whose key is outside that
set. No persisted byte changed.

Four further decisions were forced along the way, and they are the half of this record that will
outlive the measurements — they define what a *named* reference content instance means.

## Why

The operation cost **p50 0.266 s** and **28.2 req/s** at concurrency 16 on a restored production
retail corpus (170 377 products, 29 328 parameter values, 302 parameters). Three separate costs sat
on top of each other, and each was only visible once the one above it was removed.

### Previous state

Reference materialization was all-or-nothing per entity: one Kryo record holding every reference,
decoded in full, with the projection's filter applied afterwards. A JDWP logpoint on
`EntityDecorator:744` printing `kept of total` for one request returned **`kept=72342 of 72342`**, and
`N of N` for all 22 entities — the unnamed reference path discarded nothing at all.

The reason is that a GraphQL field alias becomes the `instanceName` of a `ReferenceContent`, producing
a **named** requirement. `ServerEntityDecorator` built the named set (filtered, and the one the
response actually reads) and then called `super.fillFilteredSortedAndFetchedReferences`
*unconditionally*, building a second complete unfiltered decorator set plus a 72 342-entry index that
nothing read. That was deliberate — `computeVisibleReferenceNames` folded named and unnamed names into
one set on purpose, so that "the read is never narrower than the visibility" — which made removing it
an API semantics decision rather than an optimization.

## Decisions taken

| Decision | Why | Where it lives |
|---|---|---|
| Narrow the decode **inside the serializer**, keyed by referenced primary key | The allocation happens in `ReferenceSerializer.read` before any upper layer can discard it; the referenced key is the field immediately after the name, so a `(name, key)` decision costs four bytes beyond the `name` decision already being made | `ReferenceDecodeCoverage`, `ReferenceSerializer` |
| The key set comes from an `entityPrimaryKeyInSet` in the reference filter's **conjunctive root**, never from a nested `or`/`not` | `A AND pkInSet(X)` and `(A ∩ X) AND pkInSet(X)` select the same references; a key set under a disjunction constrains nothing on its own | `ReferencedEntityFetcher#narrowByExactReferencedPrimaryKeys` |
| Coverage is part of the record cache's **identity**, and completeness guards see the key axis | Otherwise "name present in the set" keeps meaning "complete for that name" and every write-path guard passes on a partial part | `StorageAccessScope.RecordKey`, `ReferencesStoragePart#assertComplete` |
| Enrichment compares **coverage**, not name sets | Two requests agreeing on names but differing on keys would both answer "already fetched" and serve a silently incomplete set — a plausible wrong answer rather than a failure | `DefaultEntityCollectionPersistenceService#shouldFetchReferences`, `ReferenceDecodeCoverage#covers` |
| **A query gets what it asked for**: a name reaching the request only through named reference content yields those named chunks and an **empty** unnamed view | The unnamed view showing what the named requirements happened to fetch was never promised — it was incidental, a by-product of composing every reference twice | `ReferenceContractSerializablePredicate#isReferenceRequestedOnlyAsNamed` |
| **`enrichEntity` adds, `limitEntity` subtracts** | The API already encoded this and nobody had noticed: `EvitaSession.enrichEntity` calls `collection.enrichEntity` alone, while `enrichOrLimitEntity` calls `limitEntity(enrichEntity(...))` | `EntityCollection#enrichEntity` / `#limitEntityInternal` |
| Enrichment carries the named **requirements** and re-fetches, never the chunks | A carried chunk would answer from the body the earlier read saw; enrichment re-reads and may land on a newer one | `ServerEntityDecorator#namedReferenceRequirements` |
| Naming the same instance twice is a **redefinition**, not a union — the enriching request wins | An instance name identifies one field of one response, so two filters for it are contradictory rather than cumulative. Additivity holds **across** instance names, never within one | `EntityCollection#mergeNamedReferenceRequirements` |

## Rejected outright

| Option | Rejected because | Revisit if |
|---|---|---|
| Per-name offset table in the serialized record, so a read seeks rather than walks | The 72 342 entries are **one** `products` run, so a per-name table removes nothing on this workload. A true in-record seek is also more than offsets: `ObservableInput.skip` advances sequentially, the record hands its whole payload to Kryo as one object, and records may be continued or compressed | A workload appears whose cost is spread across many names rather than concentrated in one |
| Split `ReferencesStoragePart` per reference name (#1554) | All hot references share one name, so the hot part stays 72 342 entries. It also contradicts the one-part-per-entity accessor model and the single global `lastUsedPrimaryKey` | It lands for its own reasons; it subsumes the *name* axis but not this one |
| Narrow above the serializer | The allocation being removed happens inside `ReferenceSerializer.read`; an upper layer can only discard what has already been built | Never — this is structural |
| **Blanket skip** of the unnamed view for a named-only name | Breaks the Java driver: `EntityConverter:198` builds a plain `EntityDecorator` client-side which has no named sets and reads the unnamed view exclusively. It also collapses a *specified* three-way distinction — a name a named requirement asked for is carried; a name nobody asked for is **not available** and throws `ContextMissingException` rather than answering empty — pinned by `EntityLazyLoadFunctionalTest.shouldExposeReferencesConsistentlyForNamedReferenceContent` | — |
| **Derive** the unnamed view from the union of the named chunks (built, measured, then removed) | It synthesises a requirement nobody wrote into `EvitaRequest#getReferenceEntityFetch()` — a map with **ten** readers, four of which changed behaviour and three unintentionally. The worst was a second, *unfiltered* prefetch pass per named-only name, on exactly the shape this work exists to speed up. Removing it dissolved eight findings from two review rounds at once, net −434/+66 | A future reader wants the unnamed view to reflect named fetches: it must be built somewhere that is not the request's own requirement map |
| Build the unnamed view **lazily** on first access | Preserves every semantic, but `EntityDecorator`'s constructor fills `filteredReferences`, `filteredReferencesByName`, `filteredDuplicateReferences`, `referenceBodiesAttached` and `chunkedOutReferences` in one pass, and `referenceBodiesAttached` is read by `ServerEntityDecorator#reachableBodies` for I/O statistics — the lazy slot has to carry that too | The unnamed view becomes a cost again after this record's narrowing; it is the only option needing no test changed |
| Preserve the named **chunks** verbatim across enrichment (Codex's recommendation) | Enrichment re-reads and may land on a newer body, so a carried-over chunk answers from the older one. Carrying the *requirements* and re-fetching costs one decode and cannot go stale | — |
| Union two requirements that name the same instance | See the redefinition decision above; a union has no wider set to add to, only a more recent statement of what the name means | — |

## Key technical details

- **Two axes, one type.** `ReferenceDecodeCoverage` spans both the name axis and the referenced-key
  axis; `#covers` is what enrichment consults. Its key sets are sorted, cloned `int[]` — deliberately
  not `PersistentRoaringBitmap`, which is mutable and whose hash code is not encoding-independent even
  where equality is.
- **Keys are not deduplicated.** `entityPrimaryKeyInSet(10, 10, 20)` binds `{10, 10, 20}`; the arrays
  are cloned and sorted but never deduped.
- **One requirement may name several references.** `unionSortedKeys` allocates, so the first `merge`
  for each name would otherwise hand every name the same array instance — `EvitaRequest`
  `clone()`s when `referenceNames.length > 1`.
- **A mis-mirrored skip desynchronises the stream** rather than failing at the reference
  (`ReferenceSerializer:194-197`). Any future skip work needs the same field-by-field equivalence proof
  this one got.
- **The total-count invariant holds only under the union rule.** The total is the size of the list
  surviving the requirement's filter, before page slicing; `count(refs ∩ F)` with `F ⊆ X` equals
  `count((refs ∩ X) ∩ F)`. No test pinned this before this work.
- **`FacetIndexContract.getSize()` is now `getAssociationCount()`.** It counts facet-to-entity
  associations *including duplicates* — on a production global index it summed to **2 276 771** against
  **130 033** entities — so the old name was a lie. `EntityIndex#size()` now answers the figure the old
  name promised, uniformly, including `ReferencedTypeEntityIndex` whose records are the reduced-index
  primary keys it navigates to. Both former call sites consumed it as `worstCardinality`, which made
  one index estimate its own cardinality as **0**.

## Verification

- **Full suite on the merged dev head: 24 585 tests, 24 541 passed, 44 skipped, 0 failed** (CI on
  `face41278`). The release arm gated at 21 428, one known load-dependent flake
  (`CdcCallbackDispatcherTest`, green 4/4 in isolation) and one Docker-absent error
  (`ExportS3ServiceTest`).
- **End-to-end, restored production retail corpus**, one server at a time, cache off (the shipped
  default), 30 distinct primary keys:

  | | stock 2026.2 | this work |
  |---|---|---|
  | p50, one request at a time | 0.266 s | **0.026 s** (10.2×) |
  | throughput, c=16 | 28.2 req/s | **~160 req/s** (5.7×) |

- **The decode itself**, `ReferenceNarrowingDecodeBenchmark`, 72 217 back-references, `decodeAll` →
  `decodeKeyNarrowed`: **1.4–1.5× faster, 3.03× less garbage** (29.76 → 9.83 MB/op). Latency is quoted
  as a range on purpose — run-to-run variance reached 11 % on a single fork, and moved two arms in
  *opposite* directions. `gc.alloc.rate.norm` reproduced byte-identically across all eighteen
  configurations in both runs, so **allocation is the robust number and latency the approximate one**.
- **Correctness by golden capture**: full response bodies for 12 live and 12 archived primary keys,
  `diff -r` against a stock-2026.2 capture **empty** at every step.
- **Counterfactuals**, because a green gate proves nothing on its own:
  - replacing the narrowed bitmap with `EmptyBitmap.INSTANCE` turned 45 green tests into 3 failures +
    1 error, naming exactly the shapes touched — including
    `shouldFilterStoreReferencesByEntityHavingCombinedWithEntityPrimaryKeyInSet`, literally the
    `entityHaving` + `entityPrimaryKeyInSet` combination of the slow query;
  - flipping `mergeNamedReferenceRequirements` to the union reading a reviewer argued for produced
    2 failures: `shouldRedefineTheNamedSetWhenEnrichingThroughTheSameAlias` saw all three categories
    survive where one should stand, and `shouldKeepBothNamedSetsWhenEnrichingOverAnotherReferenceName`
    lost its second set.
- **Documentation examples unaffected**: same-lineage A/B against a pristine demo dataset, 1329
  examples, failing-name sets **identical** between arms. The 18 pre-existing failures are histogram
  counts inflated ~1.6× by dataset drift — narrowing that dropped references would push counts *down*.

## Consequences & open follow-ups

- **The decode floor is now the attribute decode on the skip path.** Skipping ~all 72 217 references
  still allocates 9.83 MB/op; the fixture gives each back-reference one attribute and 72 217 × ~136 B
  ≈ 9.8 MB, matching to rounding. A skipped reference still runs `readClassAndObject` over its
  attribute values to advance the stream. Advancing by *length* instead is the next lever and needs the
  desynchronisation proof above. This retires an earlier assumption that production back-references are
  attribute-free — that was inference from a GC frame reaching 0.00 %, never verified.
- **gRPC serializes the unnamed view.** `EntityConverter:436` iterates `entity.getReferences()`, so a
  Java-driver client that writes named reference content explicitly now receives nothing for that name.
  Before this work it received the full *unfiltered* set — also not what it asked for, merely non-empty.
  There is no way to carry a named chunk over the wire today. GraphQL and REST are unaffected: GraphQL
  reads the named chunk by `ReferenceContentKey` and emits an unnamed catch-all on its no-filter fast
  path; REST only ever builds unnamed `ReferenceContent`.
- **`CacheEden` retains the composed entity and its predicates but no named reference state**
  (`EntityPayload`), so a cached entity cannot answer a named chunk. Reachability was not established
  by either the review that raised it or the verification that followed; caching is off by default
  (`CacheOptions.DEFAULT_ENABLED == false`) and the component is slated for rewrite, so it was
  knowingly left alone. **Do not read "cache out of scope" as a general statement** — it is valid for
  this deployment, never for a generally shipped feature.
- **The write-side entity seam is complete only by accident.** `LocalMutationExecutorCollector`'s
  `getFullEntityContents` runs the ordinary predicate-driven read path and is complete only because its
  request is a literal `entityFetchAll()`. Nothing asserts it. The counterfactual that proves it:
  narrow that request to a single-name `referenceContent` and
  `shouldAutomaticallyRemoveReflectedReferenceViaReferenceOnEntityRemoval` must fail today with
  dangling counterparts. A write-side entry point that binds coverage to null explicitly, and asserts
  the predicate narrows nothing, is the fix.
- **A metadata-laundering copy exists on the removal path.** `ContainerizedLocalMutationExecutor`
  copies `getReferences()` into the four-argument `ReferencesStoragePart` constructor, which marks the
  result **complete**; the copy then generates `REMOVE_ALL_EXISTING` reflected-reference mutations.
  Inert today because the source is the executor's own unfiltered fetch — armed by any future caller
  that hands it a narrowed part.
- **#1640** — an `@Internal` annotation for members public only for internal reach, with named
  reference content as the first citizen. Named reference content is an undocumented internal feature
  used only by evitaDB's own GraphQL and REST layers, which is why the tests pinning its unnamed-view
  side effects were the only ones to fail.
- **Report-only, found on the way, deliberately not fixed**: `UserDocumentationTest:670` says
  "disabled" in its javadoc but carries no `@Disabled` and reaches the demo host; its
  `hasLocalExamples` sets the flag before the LOCALHOST skip, so 10 of 551 files take a global
  exclusive lock for examples that never run.

## Related work

- [`2026-09-11-reference-name-narrowing`](2026-09-11-reference-name-narrowing.md) — the *name* axis.
  This record is its second axis and inherits its safety argument: a reference this read skips is one
  the predicate would have filtered out of the composed entity anyway. That record's proposed
  "compare the encoded string length" follow-up does not exist as described — Kryo writes short ASCII
  as raw bytes with the last byte's high bit set, with no length prefix — and it has been corrected in
  place.

## Timeline

- **2026-09-21** — `parameterValueProductList` profiled on a restored production catalogue; the
  reference-index translation identified as the first cost
- **2026-09-22** — index translation narrowed (6.5×); two independent design reviews of the key axis,
  both **GO WITH CHANGES**; the unnamed-view duplicate identified as the next cost and the blanket skip
  rejected against a specified contract
- **2026-09-23** — key axis implemented and measured; the derived-unnamed-view union built, reviewed,
  and removed; the enrichment/limit contract set; PR #1639 merged to dev and #1638 to master
