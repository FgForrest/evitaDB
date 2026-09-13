---
title: Define the per-entity I/O statistic as standalone cost and attribute it at the read, not by walking the returned object graph
date: 2026-09-13
updated: 2026-09-13 10:40
status: partially-implemented
kind: refactor
issues: [1547, 1561, 1562, 1563, 1564, 1565, 1566, 1567]
prs: [1548]
areas: [evita_engine/src/main/java/io/evitadb/core/query/response, evita_engine/src/main/java/io/evitadb/core/buffer, evita_engine/src/main/java/io/evitadb/core/query/fetch, evita_store/evita_store_server/src/main/java/io/evitadb/store/catalog, evita_api/src/main/java/io/evitadb/api/requestResponse]
supersedes: []
superseded-by: []
relates: [2026-09-12-committed-snapshot-provenance-for-enrichment, 2026-09-11-reference-name-narrowing]
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
- `ServerEntityDecorator#attachedBodies()` — de-duplicates by the **entity** a body describes, its
  type and primary key, never by the object carrying it. Where two views of one entity account for
  different amounts, the larger is kept.
- `DefaultEntityCollectionPersistenceService.IoFetchStatistics#record` — bills the entity every part
  it had to obtain, including one the scope served. `StorageAccessScope#noteRecordRead` keeps the
  query-wide total physical and no longer reports its decision to the caller.
- Whether a read was **physical** must be reported by the persistence boundary that performed it,
  not inferred from a scope miss: the scope's loader can be satisfied by another in-memory layer
  (`DataStoreChanges.trappedChanges` during a transaction), and binary reads bypass the scope
  entirely. Both remain open.
- **Invariant a future change must preserve:** a decorator that only narrows or re-wraps an entity
  performs no read and must contribute nothing beyond what the entity it wraps already counts.
  Three mechanisms tried to *detect* such a decorator after the fact and all three failed; keying on
  entity identity makes re-wrapping irrelevant rather than detectable.
- Reading the statistic must be `O(1)` and must not force materialization —
  `io.evitadb.core.metric.event.query` resolves it after every fetch when traffic recording is on.

## Verification

Acceptance criteria for the whole line of work, and where each one stands.

| criterion | state | pinned by |
|---|---|---|
| a per-entity number is unchanged by what else shares its page | **met** | `EntityHierarchyFetchFunctionalTest#shouldNotLetAPageMateChangeWhatAnEntityCost` |
| an entity exposing one referenced entity through two views counts it once | **met** | `EntityReferenceFetchFunctionalTest#shouldNotCountAReWrappedBodyTwiceOnEnrichment`, `#shouldNotCountARepeatedCompositionOfTheSameBodyTwice` |
| the query-wide total stays physical | **met** | `StorageAccessScopeTest#shouldNotAddAServedRecordToTheQueryTotal` |
| reaching a richness by enrichment costs what reaching it in one fetch costs | **partly met** | `EntityEnrichmentVersionGuardFunctionalTest#shouldNotTakeTheShortcutForAnEntityReturnedByAQuery` — the unconditional version probe no longer bills; the predicate-driven re-reads still do (#1565) |
| a descendant reachable through two bodies contributes once, not once per path | open | — (#1567) |
| an over-fetching reference fetch reports all N candidates, not the K it exposes | open | — |
| a body-only binary query reports a non-zero count | open | — |
| an in-transaction read-after-write reports no physical reads for trapped parts | open | — |
| resolving the statistic on an entity with no attached bodies does not call `getReferences()` | open | — |

`EntityReferenceFetchFunctionalTest#shouldNotCountARepeatedCompositionOfTheSameBodyTwice` was
written under Option A — it passed because the scope suppressed the second composition's reads. It
now passes for the opposite reason, because the two views describe one entity, and it fails if the
entity-identity keying is reverted. `ServerEntityDecoratorIoStatisticsTest` still builds its fixture
with a `null` parent and an empty `References`, so it exercises only the deferred chain and none of
the attached half; it needs coverage.

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
- Deferred, each with its own issue, to close as consequences of the remaining work rather than as
  point fixes: group-sort over-fetch invisibility (#1561), the binary-fetch bypass (#1562), phantom
  billing of transactional trapped parts (#1563), and the `O(references)` statistic resolution
  (#1564).
- Per-entity numbers remain an approximation until the three open criteria above are met: they can
  still under-report an over-fetching reference fetch and a binary body fetch. The contract javadoc
  says so, and must stop saying so when it stops being true.
- De-duplication keeps the **larger** of two views of one entity, deciding fetch count and fetched
  bytes independently — views fetched under different requirements need not order the same way on
  both, and choosing one view by its record count then reading its bytes off that same object
  reports the smaller figure by an arbitrary margin.
- Where two views genuinely read **disjoint** parts of one entity, keeping the larger under-reports
  the union. An earlier draft of this record claimed no such shape was known; one does exist, since
  ordinary and named reference requirements are held in independent maps and can carry different
  requirements for the same referenced entity (#1566).
- An enrichment re-reads parts the entity already holds, because the narrowing predicates a query
  result carries defeat the "already fetched" comparison, and those re-reads are billed (#1565).
  The unconditional body re-read that establishes whether the decorator is still current is fixed
  here: `IoFetchStatistics#note` puts it in the query total without billing the entity.
- De-duplication covers the **immediate** children only. Two bodies of one owner that share a
  descendant each carry that descendant inside their own aggregate, and summing the two bills it
  twice (#1567). Pushing the de-duplication one level deeper only moves the problem; the root's cost
  has to become a union over the reachable graph rather than a sum of child aggregates.

**The pattern across the open items is the finding.** Five independent shapes now break this design
— over-fetch (#1561), binary reads (#1562), enrichment re-reads (#1565), disjoint views (#1566),
shared descendants (#1567) — and every one of them is a place where the exposed object graph does
not carry the information the statistic needs. Each is cheap to describe and none is cheap to fix
here, because the fix is the same in every case: have the prefetch and the storage boundary report
what they read and for whom, rather than reconstructing it afterwards from what survived into the
result. A sixth point fix inside this design should be treated as evidence the boundary work is
overdue, not as progress.

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
