---
title: Define the per-entity I/O statistic as standalone cost and attribute it at the read, not by walking the returned object graph
date: 2026-09-13
updated: 2026-09-14 20:35
status: accepted
kind: refactor
issues: [1547, 1561, 1562, 1563, 1564, 1565, 1566, 1567]
prs: [1548]
areas: [evita_engine/src/main/java/io/evitadb/core/query/response, evita_engine/src/main/java/io/evitadb/core/buffer, evita_engine/src/main/java/io/evitadb/core/query/fetch, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_api/src/main/java/io/evitadb/api/requestResponse]
supersedes: []
superseded-by: []
relates: [2026-09-12-committed-snapshot-provenance-for-enrichment, 2026-09-11-reference-name-narrowing, 2026-09-15-bidirectional-reference-counterpart-rewrite]
---

# Define the per-entity I/O statistic as standalone cost, and attribute it where the read happens

`SealedEntity#getIoFetchCount()` / `#getIoFetchedBytes()` report what a single entity cost to
fetch. Three successive implementations of that number have been wrong, each in a different way,
and none of them was caught by a functional suite of 21 000 tests. This record settles the two
questions that were never actually answered — **what the number means when two entities need the
same record**, and **where in the pipeline it is computed** — and makes everything that follows a
consequence of those two answers rather than another repair.

The number is defined as **standalone cost**: what this entity would have cost had it been fetched
on its own. The response-level total stays what it already is, the **physical** cost of the whole
query. The two are different metrics, they answer different questions, and they are no longer
expected to reconcile. Attribution then follows from the definition: an entity counts each **entity**
it needed once, keyed by what that entity is rather than by which object happens to carry it.

## Why

The read path counts I/O so that callers can see which entity in a page is expensive and so the
engine can report what a query cost. Both numbers exist today; only one of them is well defined.

Nothing ever wrote down what the per-entity number should say when **one physical read serves two
returned entities** — an owner reaching the same referenced entity through two reference names, two
products sharing a category body, a child and its own ancestor on the same page. Every
implementation therefore picked an answer implicitly, and each picked a different one. That is the
constraint that made this non-obvious: it looks like an arithmetic bug and it is a missing
specification, so each fix "corrected" the arithmetic towards whichever answer its author had in
mind.

The second force is structural. The statistic was reconstructed after the fact by walking the
object graph the query returned. A read whose body is **not in that graph** cannot be found by any
such walk — and the fetch pipeline routinely reads bodies it then discards: the group-sort fallback
in `ReferencedEntityFetcher` deliberately loads all N candidate bodies before sorting and slicing
to a page of K. Those N−K reads are invisible to a traversal of what was exposed, not because the
traversal is careless but because they are not there. No amount of care inside that design reaches
them.

### Previous state

Three mechanisms, in order, all inferring ownership from the returned graph:

1. **Instance comparison** — a parent body was counted when its instance differed from the one the
   deferred source held. Defeated by an enrichment that re-attaches the same chain as fresh
   wrappers.
2. **A boolean flag** (`owesReferencedEntityStatistics`) on the decorator that attached the bodies.
   Defeated identically; the flag is set on a decorator that re-attaches bodies it did not read.
3. **Own/attached split** (`ae1346b54`) — an *own* half summed along the `deferredIoStatisticsSource`
   chain plus an *attached* half walking the parent chain and the referenced and group bodies of
   the ordinary and named reference sets, de-duplicated by instance identity.

The third is a real improvement — it is the first version to count group bodies and named reference
sets at all, and the first to stop billing a per-query cache hit twice — and it is still wrong in
four independent ways, found by an adversarial review and a four-agent quality pass run over it
after it was written:

| symptom | shape |
|---|---|
| counted twice | enrichment hands one reused body to the ordinary and to each named prefetch; each re-wraps it through `limitEntityInternal`, and two distinct instances chain to the same reads |
| counted zero | group-sort over-fetch: N bodies read, K exposed, N−K invisible |
| counted zero | a binary entity body is read before `IoFetchStatistics` exists and bypasses `StorageAccessScope` |
| counted phantom | in-transaction read-after-write: the scope's loader returns a trapped in-memory part, having touched no storage, and the read is billed as physical |

Both reviews also flagged that resolving the statistic became `O(references)` and could force lazy
materialization, on a getter traffic recording calls after every fetch.

## Options considered

The primary fork is what the per-entity number means. Option D is the structural alternative and is
independent of which of A–C wins.

### Option A — physical attribution

Every physical read is billed to whoever triggered it; a read served from the per-query
`StorageAccessScope` costs its second consumer nothing. Per-entity numbers then sum to the query
total.

- **Pros:** one coherent model across both metrics; the numbers reconcile; it is what `ae1346b54`
  implemented, so it is the smallest change from today.
- **Cons:** an entity's reported cost depends on what else is on the page. The same entity fetched
  alone and fetched beside its own ancestor reports different numbers, and the second one can be
  zero for data the entity demonstrably exposes. That makes the per-entity number useless for the
  question it exists to answer — *which entity is expensive?* — because it is not comparable
  between rows of the same page.
- **Rejected because:** it makes the per-entity statistic non-comparable across the entities of one
  response, which is the only context in which anyone reads it.

### Option B — standalone cost everywhere

Each entity reports what it would have cost fetched alone; a shared read is billed to every entity
that needed it, and the response total is the sum, exceeding the physical cost of the query.

- **Pros:** per-entity numbers are stable and comparable; one model, no divergence to explain.
- **Cons:** the response total stops describing what the query cost the storage, which is the one
  thing that number is for — capacity planning and slow-query analysis both want physical reads,
  not a sum of hypotheticals.
- **Rejected because:** it fixes the per-entity metric by breaking the response metric; the two
  consumers want genuinely different numbers and this gives both of them the same one.

### Option C — split the two metrics (chosen)

Per-entity numbers are standalone cost (B); the response total stays physical (A). They are
documented as different metrics that deliberately do not reconcile.

- **Pros:** each number answers the question its consumers actually ask, and each is well defined
  on its own terms. Per-entity values are comparable within a page and stable across pages;
  the response total remains a truthful statement about storage traffic.
- **Cons:** two models to hold in mind, and a contract that must say plainly that the sum over
  entities may exceed the total. Existing tests asserting that a repeated composition costs nothing
  assert Option A and become tests of the wrong thing.

### Option D — keep reconstructing from the exposed object graph (declined)

Continue computing the statistic by walking the parent chain and reference sets the decorator
exposes, repairing the known failures in place.

- **Pros:** localized; no change to the fetch pipeline or the storage boundary.
- **Cons:** cannot see a read whose body was discarded before it reached the graph, cannot tell a
  re-wrapped body from a freshly read one, and cannot distinguish a physical read from an in-memory
  one because it is too far from the point where that is known.
- **Rejected because:** the over-fetch case is not a defect in the traversal but a property of it —
  discarded bodies are not in the graph by construction, so no repair inside this design reaches
  them. Three attempts at such repairs have each produced a new failure mode.

## Decision

**Chosen: Option C, implemented by Option D's replacement — attribution at the read.**

Option C wins on the driver that killed A and B: the two numbers have different consumers asking
different questions, and any single definition has to disappoint one of them. Splitting them costs
one paragraph of contract documentation and buys two metrics that are each correct.

Attribution follows from the definition rather than from a new mechanism, and this is where a first
draft of this record was wrong: it proposed an accounting frame pushed per entity composition,
billing every read noted inside it to that entity. **A frame cannot do this job.** Referenced bodies
are prefetched once for a whole page of owners — that is the entire point of prefetching — so no
frame scoped to one owner's composition ever sees them, and a frame scoped to the prefetch cannot
say which owner each body was for. Attribution to an individual owner is irreducibly a mapping from
bodies back to the entities that needed them.

What the definition does settle is the rule that mapping must use. Under standalone cost an entity
counts each **entity** it needed once — not each object carrying it. Two views of one referenced
entity, whether through an ordinary and a named reference set or through a body an enrichment
re-wrapped, describe one set of reads and count once; two entities that both needed the same record
each count it. Object identity cannot express that, and all three previous mechanisms used object
identity, which is why re-wrapping defeated every one of them. The per-entity number consequently
stops depending on whether a read was physical at all, which retires the whole question those
mechanisms existed to answer.

The physical-versus-logical distinction survives, but only where it belongs: in the query-wide
total, decided by the scope. That decision must still move to the true persistence boundary, because
a scope miss is not proof of a physical read.

**What would have to change for Option A to win:** a consumer appearing that needs per-entity
numbers to sum to the response total — a billing or quota model, say, rather than a diagnostic one.
That is the trigger for an ADR superseding this one.

## Key technical details

- `SealedEntity#getIoFetchCount()` / `#getIoFetchedBytes()` — **standalone cost**. A read shared
  between two returned entities is reported by both. The sum over a response may exceed
  `EvitaResponse#getIoFetchCount()`, and that is not a defect.
- `EvitaResponse#getIoFetchCount()` / `#getIoFetchedBytes()` — **physical cost** of the whole query,
  including filtering, prefetch and extra-result work that belongs to no single returned entity.
  Unchanged by this decision.
- `EntityCollectionPersistenceService.ReadRecord` — the identity of one storage record that was
  read, as `(containerType, storagePartPk, size)`. It deliberately **names** the record rather than
  holding it: a storage part is the bulk of what an entity is made of, and a decorator outlives the
  response, so holding the part would pin every byte the query read for as long. Reads that carry no
  key of their own — binary fetches, parts not yet assigned one — take a descending negative
  sequence and therefore de-duplicate against nothing, which is the honest answer for them.
- `ServerEntityDecorator#reachableBodies()` — de-duplicates by the **entity** a body describes, its
  type and primary key, never by the object carrying it, and does so over the whole **reachable**
  graph rather than the immediate children. `BodyCost#combine` then takes the **union** of what two
  views of one entity read. Union, not maximum and not sum: a sum bills the body both views started
  by reading twice, and a maximum drops whatever the smaller view alone had to read.
- `ServerEntityDecorator#ownReadRecords()` — the own half is the set of **distinct records** read
  along the `deferredIoStatisticsSource` chain, not the sum of reads along it. That is what makes
  the same entity at the same richness cost the same by every route, without a special case for
  each way a route can re-read something.
- `DefaultEntityCollectionPersistenceService.IoFetchStatistics#record` — bills the entity every part
  it had to obtain, including one the scope served, and records which part it was.
  `StorageAccessScope#noteRecordRead` keeps the query-wide total physical.
- Whether a read was **physical** is reported by the boundary that performed it:
  `DataStoreChanges#getStoragePart` calls `StorageAccessScope#noteRecordServedFromMemory` when it
  answers from a transaction's trapped changes. A scope miss is not proof of a physical read, and
  nothing outside that boundary may claim a read cost nothing.
- `EntityDecorator#areReferenceBodiesAttached()` — set by `fetchReference`, the single place a body
  is ever attached to a reference. It is an **observation**, not a prediction from the request or
  the constructor, and that distinction is the whole lesson of this record: an
  `attributeContent`-only `referenceContent` over a hundred references can then skip the walk
  entirely, and the flag cannot claim there are no bodies while some were attached.
- `EntityDecorator#getChunkedOutReferences()` — references fetched and then dropped by the requested
  chunk, offered once to the decorator that accounts for them and released immediately. Retaining a
  discarded page of bodies for a decorator's whole life is the exact footprint paging exists to
  avoid.
- **Trap:** a **group** body is only ever attached beside a referenced entity, while groups are
  resolved for every reference that passed the filter — `BitmapSlicer#getGroupIds` returns the whole
  filtered set whichever slicing path ran, whereas referenced entity bodies are prefetched only for
  the page that survives an order-free slice. A group reached solely through a reference the page
  sliced away is therefore read and then carried by nothing, and no walk of the exposed graph can
  find it. `EntityDecorator#getUnexposedBodies()` carries exactly those, released by the fetch
  constructor that accounts for them, on the same terms as `getChunkedOutReferences()`.
- **Ordering constraint on the above, and the reason `noteUnexposedGroups` sits where it does:** one
  group prefetch index serves **every owner entity in the batch** (`createPrefetchedEntities` takes
  the whole `entityPrimaryKey` map), while which references a given owner keeps is decided per owner
  by the reference `filterBy`. The note must therefore run **after** `sortAndFilterSubList`, over the
  survivors, and never while the raw references are being built: a lookup in a batch-global index
  says only that *somebody* caused the read. Noting during `fetchReference` bills an owner whose
  reference a `filterBy` excluded for a group its page-mate reached — `expected: <2> but was: <4>`,
  pinned by
  `EntityReferenceFetchFunctionalTest#shouldNotBillAnEntityForAGroupOnlyItsPageMateReached`.
- **Trap:** an entity can appear in its **own** reachable set — a reference pointing back at its
  owner, or a nesting that closes the loop a level further down. The aggregate is therefore not
  `own + Σ reachable`: the self-entry is a second *view of the same entity*, so it is united into the
  own half rather than added beside it, or the entity's body is billed once for being the owner and
  again for being its own referenced entity.
- Both memo fields the statistic is cached in — `resolvedOwnReadRecords` and
  `resolvedReachableBodies` — are `volatile`, and the first carries its "cannot be identified"
  answer as a sentinel rather than a companion flag. Unlike the `int` memos, whose only risk is a
  duplicated computation, these resolve to values a torn read cannot be told apart from a real
  answer, and would memoize a wrong number permanently. Do not relax either to a plain field. The
  other fields the same computation reads — `chunkedOutBodies`, `namedReferenceSets`,
  `deferredIoStatisticsSource` — are safe unsynchronized only because each is written before the
  decorator escapes; making any of them lazy needs the same treatment as the two memos.
- **Invariant a future change must preserve:** a decorator that only narrows or re-wraps an entity
  performs no read and must contribute nothing beyond what the entity it wraps already counts.
  Three mechanisms tried to *detect* such a decorator after the fact and all three failed; keying on
  entity identity makes re-wrapping irrelevant rather than detectable.
- **Trap:** a decorator that attaches a **parent chain** is not the one that ran the reference
  fetcher — `ReferencedEntityFetcher` re-attaches a resolved chain through the re-wrapping factory.
  A rule that lets every non-fetching decorator inherit what it reaches therefore drops the parent
  chain silently; `attachesBodies` accounts for both, and
  `EntityHierarchyFetchFunctionalTest#shouldCountTheIoStatisticsOfARequestedParentChainExactlyOnce`
  is what catches it (`expected: <2> but was: <0>`).
- **Trap, the other half of the same flag:** `attachesBodies` is true for a decorator handed a
  parent body as well as for the one that ran the fetcher, and such a decorator therefore neither
  inherits its source's reachable set nor — unless `areReferenceBodiesAttached()` says so — walks its
  own references. The flag must therefore travel with the **reference set**: `EntityDecorator`'s copy
  constructor takes over `filteredReferences` bodies and all, so it takes over
  `referenceBodiesAttached` with them. Without that, every parent-chain link between the leaf and the
  root loses the cost of everything it references — the root is safe only because it is handed
  `CONCEALED_ENTITY`, which is no body, so **a two-level hierarchy cannot show this at all**.

## Verification

Acceptance criteria for the whole line of work, and where each one stands.

| criterion | state | pinned by |
|---|---|---|
| a per-entity number is unchanged by what else shares its page | **met** | `EntityHierarchyFetchFunctionalTest#shouldNotLetAPageMateChangeWhatAnEntityCost` |
| an entity exposing one referenced entity through two views counts it once | **met** | `EntityReferenceFetchFunctionalTest#shouldNotCountAReWrappedBodyTwiceOnEnrichment`, `#shouldNotCountARepeatedCompositionOfTheSameBodyTwice` |
| the query-wide total stays physical | **met** | `StorageAccessScopeTest#shouldNotAddAServedRecordToTheQueryTotal` |
| reaching a richness by enrichment costs what reaching it in one fetch costs | **met** | `EntityReferenceFetchFunctionalTest#shouldCostTheSameWhetherReachedByFetchOrByEnrichment` — 21 by either route |
| an entity a query returned can take the enrichment shortcut | **met** | `EntityEnrichmentVersionGuardFunctionalTest#shouldTakeTheShortcutForAnEntityReturnedByAQuery` (#1565) |
| an entity that reaches itself counts its own body once | **met** | `EntityIoStatisticsFunctionalTest#shouldCountAnEntityReachingItselfExactlyOnce` — 3 without the union, 2 with it |
| a page does not change what the groups cost | **met** | `EntityReferenceFetchFunctionalTest#shouldCountGroupBodiesReadForReferencesThePageSlicedAway` — 4 of 36 group reads counted before the fix |
| a hierarchical entity's referenced bodies survive an enrichment | **met** | `EntityIoStatisticsFunctionalTest#shouldKeepReferencedBodiesWhenAnEnrichmentReAttachesTheParent` — the shipped datasets have no hierarchical collection carrying references, so this shape had no coverage at all |
| a page-mate does not change what an entity cost, through a shared group index | **met** | `EntityReferenceFetchFunctionalTest#shouldNotBillAnEntityForAGroupOnlyItsPageMateReached` — 4 against 2 before the fix |
| a parent-chain link keeps what its own referenced bodies cost | **met** | `EntityIoStatisticsFunctionalTest#shouldKeepTheReferencedBodiesOfAParentChainLink` — 0 of 1 counted before the fix; needs a **three**-level chain, a two-level one passes regardless |
| a descendant reachable through two bodies contributes once, not once per path | **met** | `EntityReferenceFetchFunctionalTest#shouldCountABodyTwoReferencedBodiesShareExactlyOnce` (#1567) |
| an over-fetching reference fetch reports all N candidates, not the K it exposes | **met** | `EntityReferenceFetchFunctionalTest#shouldCountBodiesTheRequestedPageDropped` (#1561) |
| two disjoint views of one entity report their union | **met** | `EntityReferenceFetchFunctionalTest#shouldCountTheUnionOfTwoDisjointViewsOfOneReferencedEntity` (#1566) |
| a body-only binary query reports a non-zero count | **met** | `EntityBasicFetchFunctionalTest#shouldCountTheBinaryEntityBodyRead` (#1562) |
| an in-transaction read-after-write reports no physical reads for trapped parts | **met** | `EntityIoStatisticsFunctionalTest#shouldNotBillAReadAfterWriteAsPhysicalIo` (#1563) |
| resolving the statistic on an entity with no attached bodies does not call `getReferences()` | **met** | `EntityDecorator#areReferenceBodiesAttached()` short-circuits the walk (#1564) |

Every one of these was watched fail before it was believed. Reverting all four production fixes at
once and running the tests together:

| test | without its fix |
|---|---|
| `shouldNotBillAReadAfterWriteAsPhysicalIo` | `expected: <0> but was: <2>` |
| `shouldCountTheBinaryEntityBodyRead` | `expected: <true> but was: <false>` |
| `shouldCountBodiesTheRequestedPageDropped` | `expected: <98> but was: <14>` |
| `shouldCountABodyTwoReferencedBodiesShareExactlyOnce` | `expected: <2> but was: <4>` |

`shouldTakeTheShortcutForAnEntityReturnedByAQuery` carries its own proof in this file's history: it
asserted `assertNotSame` and passed, and now asserts `assertSame` and passes.

**The numbers quoted in #1565 were not evidence of what that issue claimed.** It reported a product
costing 2 alone, 3 fetched with its store bodies and 5 reached by enrichment, and read the gap as
parts being re-read and re-billed. It is not: `session.enrichEntity` derives a request covering every
data locale, so the enriched entity reads the localized attribute records the one-shot query never
asked for. Pin the locale scope equal on both arms and the two routes agree exactly, at 21. The
predicate-identity defect the issue *names* was real and is fixed; its measurement was measuring
something else, which is worth recording because the next reader would otherwise hunt a bug that is
not there.

`EntityReferenceFetchFunctionalTest#shouldNotCountARepeatedCompositionOfTheSameBodyTwice` was
written under Option A — it passed because the scope suppressed the second composition's reads. It
now passes for the opposite reason, because the two views describe one entity, and it fails if the
entity-identity keying is reverted. `ServerEntityDecoratorIoStatisticsTest` builds its fixture with
a `null` parent and an empty `References` and hands its decorators raw counts with no record
identities, so it is now the coverage of the **fall-back** path — the one taken by a decorator whose
reads were never identified.

## Consequences & open follow-ups

- The cache-hit suppression added in `ae1346b54` was correct for the response total and **wrong for
  the per-entity half** under this decision. That half is reverted:
  `IoFetchStatistics#record` bills unconditionally again and `StorageAccessScope#noteRecordRead` is
  void once more. Anyone reading that diff as a regression of `ae1346b54` should read this record
  first — it is the decision, not an oversight.
- Two fixes carried in the same commits are independent of all of this and stand on their own: the
  enrichment parent-chain truncation in `ExistingEntityDecoratorProvider#getExistingParentEntity`
  (a data defect — an enrichment silently returned a one-link chain where several links were
  requested) and the named-reference-set `NullPointerException` (#1560).
- `IoFetchStatistics#note` — which puts the enrichment's version probe into the query total without
  billing the entity — is now **redundant but retained**. Record-level de-duplication makes a
  re-read of a part the entity already holds idempotent by construction, so the special case no
  longer carries the property on its own. It is kept because it still says the right thing about
  the query-wide total, which counts reads rather than records.
- **The raw-count fallback** — adding counts up rather than unioning records, and keeping the
  **larger** of two views — is reached only by a link that *read something and kept no record of
  what*. A link reporting no reads at all is not such a link: it identifies nothing because it has
  nothing to identify, contributes an empty set, and leaves the chain identified. A `CacheEden`
  restore is exactly that shape — `enrichCachedEntityIfNecessary` decorates with zero counts and no
  records — so a cache hit takes the **union** path with an empty contribution, not the fallback.
  Every production call site of `ServerEntityDecorator.decorate` either carries its `ReadRecord`s or
  passes `0, 0`, which leaves the fallback with no production producer today; it survives because
  nothing in the type system prevents a future one, and `ServerEntityDecoratorIoStatisticsTest` is
  what keeps it honest.
- **`ReadRecord` identity is `(containerType, key)` within a pinned catalog version.** Two reads of
  one key at two different versions can legitimately differ in size and will count twice. That is
  correct — a version change means the record changed — but it is worth knowing before reading a
  cross-version number as a discrepancy.
- The binary read path still bypasses `StorageAccessScope`'s de-duplication entirely (it is counted,
  but not cached or keyed). Adding it to the scope needs a discriminator in `RecordKey`: a binary
  and a deserialized read of the same part share every other component, and serving one where the
  other is expected is a `ClassCastException` rather than a wrong number.

**What made this line of work hard is worth keeping.** Five independent shapes broke the previous
design — over-fetch (#1561), binary reads (#1562), disjoint views (#1566), shared descendants
(#1567), and the unreachable enrichment shortcut (#1565) — and every one of them was a place where
the exposed object graph did not carry the information the statistic needed. They are closed by the
same move in every case: the layer that performs a read says what it read, and the layer that
attaches a body says that it attached one, instead of anything downstream reconstructing either from
what survived into the result. A future point fix that infers one of these again should be read as
evidence the inference is back, not as progress.

## Related work

- [2026-09-12-committed-snapshot-provenance-for-enrichment](2026-09-12-committed-snapshot-provenance-for-enrichment.md)
  — same PR and same class; it established that provenance must be *carried* rather than inferred
  from a caller's assertion, which is the same lesson this record applies to read attribution.
- [2026-09-11-reference-name-narrowing](2026-09-11-reference-name-narrowing.md) — the read-path work
  whose repairs exposed how weakly the I/O statistic was specified.

## Timeline

- **2026-09-12** — parent-chain accounting found missing and repaired by instance comparison; an
  adversarial review found that repair and its predecessor wrong in the same way
- **2026-09-13** — own/attached split implemented; an adversarial review and a four-agent quality
  pass over it returned seven findings, three high, agreeing on one
- **2026-09-13** — decision accepted: define the metric first, attribute at the read
- **2026-09-13** — the seven shapes the decision left open (#1561 — #1567) closed together, by
  carrying record identities out of the storage layer and observing body attachment at the single
  place it happens, rather than by seven point fixes
