---
title: Schema-capability usage is counted per schema element in a collection-carried registry, not per physical index
date: 2026-08-19
updated: 2026-08-23 09:55
status: accepted
kind: feature
issues: [1429]
prs: []
areas: [evita_api/api/statistics, evita_engine/index/usage, evita_engine/index/mutation, evita_engine/core/query, evita_engine/core/collection, evita_engine/core/catalog, evita_external_api/evita_external_api_grpc, evita_test/evita_performance_tests]
supersedes: []
superseded-by: []
relates: [2026-08-16-per-index-usage-statistics, 2026-08-23-usage-statistics-tracking-switch]
---

# Schema-capability usage is counted per schema element in a collection-carried registry, not per physical index

Every schema capability — `filterable()`, `sortable()`, `unique()` on an entity attribute, a reference
attribute or a sortable compound, per scope — now reports how many **logical queries requested** it and
how many **entity mutations touched** it, with coarsened last-seen stamps and a per-entry observation
window. The counters live in a `SchemaCapabilityUsageRegistry` owned by the entity collection (with a
catalog-level twin for globally-unique attributes), fed from exactly two flush points: once per executed
query plan and once per entity mutation. They surface through a new management call
(`listCapabilityUsage`), travel over gRPC, are never persisted, and reset on catalog load.

## Why

The per-index counters of [2026-08-16](2026-08-16-per-index-usage-statistics.md) answer *"is this
physical index earning the heap it occupies?"* — a per-place question. They cannot answer the question
an operator actually acts on: *"you never filter by EAN, so why are you paying to keep its filter index
up to date?"* — a per-**flag** question, because the remedial action is a schema mutation
(`withoutAttribute`, dropping `filterable()`), which removes every physical index maintaining the flag
at once. A `filterable()` attribute fans out into the global index plus every reduced index that carries
it; no per-index row can carry the aggregate without double counting it across the collection or
arbitrarily pinning a collection-wide reading to one index.

The semantic gap is as important as the granularity gap: the per-index `queryCount` deliberately counts
only **winning** plans (a probed-and-discarded candidate would inflate the losers), but for *"would
dropping this flag break somebody's query?"* a losing candidate plan is not a false positive at all —
the query named the element, so removing the flag could invalidate it regardless of which index served
it. The two numbers disagree by design; this surface exists so neither has to be misread as the other.

### Previous state

Only the per-index counters existed. An operator could see a cold physical index but had no defensible
way to conclude the *schema flag* behind it was unused — the same flag's traffic might be landing on a
sibling reduced index, or arriving through queries whose winning plan used a different index entirely.

## Options considered

### Option A — a schema-keyed registry on the collection, fed by two deduplicating flush points (chosen)

`ConcurrentHashMap<SchemaCapabilityKey, SchemaCapabilityUsage>` on `EntityCollection`, key =
(element kind × container × element × capability × scope), holder = two `LongAdder`s + two
second-coarsened volatile stamps + a final `observedSinceMillis`. Holders are resolved once (at
query-context creation / per executor instance) and hot paths keep the reference — no allocation, no
hashing, no `computeIfAbsent` per event. The registry rides the collection's copy constructors by
reference, exactly like its atomic sequences, and is realigned with the schema when the collection is created
and whenever it adopts a new schema version.

- **Pros:** cardinality is bounded by the schema (dozens of entries), not the data; the count matches
  the granularity of the remedial action; the registry survives commits/compaction by the same
  discipline the per-index `IndexActivity` holder proved out; dedup-per-logical-query and
  dedup-per-entity-mutation are enforceable at the two flush points and nowhere else.
- **Cons:** two accumulator mechanisms (query context list, executor set) exist solely to defer
  incrementing until the dedup boundary is known; a future copy site that forgets the registry
  parameter silently resets the counters (the same failure mode the per-index registry of
  [2026-08-16](2026-08-16-per-index-usage-statistics.md) has, caught the same way — the lifecycle tests
  enumerate the copy sites by name).

### Option B — per-sub-index holders, aggregated on read (declined)

Put a usage holder into `FilterIndex`/`SortIndex`/`ChainIndex`/the price sub-indexes and sum on read.

- **Pros:** no new registry object; increments happen where the work happens.
- **Cons:** replays the merge-copy plumbing through every sub-index type.
- **Rejected because:** it multiplies ~48-byte holders across hundreds of thousands of reduced indexes
  for numbers nobody can act on individually — the action is per-flag, so per-sub-index readings would
  be summed away at the surface anyway. All cost, no added meaning.

### Option C — counters on the schema DTOs (declined)

Hang the counters off `EntitySchema`/`AttributeSchema`, which already have the right identity.

- **Pros:** identity comes for free; no separate key type.
- **Rejected because:** `EntitySchema` is immutable, version-replaced on every evolution, and
  participates in serialization and equality — telemetry there either vanishes on schema replacement or
  contaminates the declarative model. The registry instead lives beside the schema's *owner* and realigns
  itself on schema adoption.

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| Increment at the translator site | Translation runs once per **candidate** plan (`QueryPlanner` re-translates the filter for every candidate), so a translator-site count measures how many alternatives the planner weighed, not what the workload does. The accessor accumulates; only the winning build flushes. | Never — dedup once per logical query *is* the semantic. |
| Increment in `AttributeIndexMutator` directly | It fires once per (mutation × affected index) as the mutation fans out over global and reduced indexes — that counts fan-out width, a legitimately different metric ("physical maintenance operations") already visible as the per-index `updateCount`. | Someone wants the fan-out metric per schema element — then it is a new column, not a redefinition of this one. |
| `AtomicLong` counters | A popular attribute is one shared cache line CASed from every query thread. `LongAdder` is one base long uncontended and self-stripes only when CASes actually fail. | — |
| Hand-rolled `ThreadLocal` striping | The right principle (stripe writes, sum on read) — and `LongAdder` is its battle-tested form. Explicit thread-locals add thread-lifecycle bookkeeping (pooled threads die → registry + weak refs + terminated-sum retention), cost `threads × elements` memory unconditionally, and are hostile to virtual threads. Note the `LongAdder` **rejection** in the 2026-08-16 record does *not* carry over here: that was about 523k byte-exact JOL-asserted per-index holders; this registry holds dozens of entries and asserts no heap arithmetic. | `LongAdder` ever shows in the adversarial benchmark — it did not (see Verification). |
| `everRequested` one-way latch (skip recording after first request) | Prepared as the fallback if the adversarial JMH case regressed. It did not regress, so the extra state and branch were never bought. Counting, not existence, is what the rate-over-window reading needs. | The adversarial benchmark regresses on future hardware or a hotter query path. |
| Uncoarsened last-seen stamps | One volatile store per event on a hot attribute. Skipping the store while the resident value falls in the same second turns thousands of stores per second into one, and second granularity is ample for "last used three weeks ago". | — |
| Columns on `IndexDetail`/`BrowsedIndex` | Pairing a collection-wide aggregate with one physical index's cost on the same row invites exactly the wrong reading — a flag reported dead being dropped while a query still depends on it. The surfaces are read side by side, never merged. | — |
| Lazy holder creation (resolve on first use) | The original shape. `observedSince` then said *"first requested"* while the public contract promised *"declared"*, so a capability first queried a month after load reported a millisecond-wide window and turned one request into an enormous rate — and a capability with no traffic produced no row at all, leaving an operator unable to tell "idle" from "not declared". | Never — eager alignment costs one map lookup per declared capability, on schema adoption only. |
| Seeding the catalog registry from its survival rule verbatim | It admits `SORTABLE` of a global attribute and the flags of a global attribute that is not globally unique. No site can file either against the catalog (its only index is the `GlobalUniqueIndex`), so both would be permanently-zero rows — worse than the late window they would fix. They stay reachable lazily and droppable. | A catalog-level index maintains one of them. |
| Persisting the counters | The operational use is a rate over an observation window; persistence would not improve it. Same contract and reasoning as `ActivityStatistics` (since catalog load, `observedSince` as denominator). | — |

## Decision

**Chosen: Option A.** The granularity follows the remedial action: an operator drops a *flag*, so the
count must be per flag — anything finer gets summed away, anything hosted on the schema DTO dies with
the schema version. The design was adjudicated with Johnny and a Codex design advisory on 2026-08-17;
the binding constraint throughout was hot-path discipline (no allocation per increment, resolve-once
holders, no `computeIfAbsent` in steady state). Option B would win only if per-physical-index capability
readings ever became independently actionable, which the per-index surface already covers from the cost
side.

## Key technical details

- Vocabulary: `SchemaCapabilityUsageStatistics` (evita_api, `api/statistics`) hosts the `ElementKind` and
  `Capability` enums; the engine key `SchemaCapabilityKey` and the wire speak the same enums so no two
  can drift. The snapshot's JavaDoc carries the full semantics (requested ≠ physical earning, dedup,
  debug-mode caveat, lifetime) — it is the reference text.
- Engine core: `io.evitadb.index.usage` — `SchemaCapabilityKey`, `SchemaCapabilityUsage` (holder),
  `SchemaCapabilityUsageRegistry` (`resolve(key)` at setup time, `listUsages()` for the surface,
  `alignWith(schema)` on creation and adoption), `SchemaCapabilityUsageProjection` (rows, ordering, 0-stamp
  → null).
- Query side: `AttributeSchemaAccessor.recordRequestedTraits(...)` is the choke point translators
  already pass through; it appends **resolved holders** to `QueryPlanningContext`'s accumulator.
  `QueryPlanBuilder.build()` drains and increments once per logical query — the drain empties the
  accumulator, which is what makes the debug modes (`VERIFY_ALTERNATIVE_INDEX_RESULTS`,
  `VERIFY_POSSIBLE_CACHING_TREES`) unable to double-flush even though they re-build and re-execute
  plans. The empty-plan short-circuit flushes nothing.
- Update side: `AttributeIndexMutator` reports the touched element to
  `EntityIndexLocalMutationExecutor.reportAttributeTouched(...)`; `markTouched` deduplicates across the
  index fan-out, `applyChanges` flushes once per entity mutation with the same `nowMillis` as the
  per-index loop. Work a later rollback undoes still counts (effort semantics, same as `updateCount`).
- Catalog twin: `Catalog` carries its own registry by reference across version advances. A query that
  names no collection records through `QueryPlanningContext.recordRequestedGlobalCapability`; a mutation
  of a globally-unique attribute files FILTER+UNIQUE into **both** registries (one element, two owners
  of its consequences), deduplicated by the same `markTouched`.
- **Counter-intuitive but correct:** the catalog registry's survival rule never
  tests global uniqueness separately. `GlobalAttributeSchema.verifyAndAlterUniquenessTypes` folds
  global uniqueness into the collection-level flags (`UNIQUE_WITHIN_CATALOG` implies
  `UNIQUE_WITHIN_COLLECTION` — "global attribute setting has always precedence"), so the shared
  `declaresCapability` rule judges catalog entries correctly. A Codex review flagged this as a bug; it
  is pinned as correct by `SchemaCapabilityUsageRegistryTest.CatalogAlignmentTest`.
- **The catalog registry carries only what `CatalogIndex` physically maintains** — the global uniqueness
  of a `uniqueGlobally()` attribute, and nothing else. Both sides are held to it: the update side files
  only `FILTERABLE`+`UNIQUE` there (`reportAttributeTouched`), and the request side now drops `SORTABLE` on the
  `owner == null` route in `AttributeSchemaAccessor#recordRequestedTraits`. Without that filter a
  collection-less `orderBy` on an attribute that is both `uniqueGlobally()` and `sortable()` minted a
  catalog row with requests against an update count that is zero *by construction* — a sortable global
  attribute's sort index lives in every collection declaring it, never in the catalog — which reads as
  *"nothing maintains this flag, drop it"* about a flag that is actively maintained. The request is
  dropped rather than re-attributed, the same trade-off `recordRequestedCapability` makes for a filter
  evaluated against another collection's structures: a number attributed to the wrong owner is worse
  than one missing. A query that **names** its collection is unaffected and records the `SORTABLE` there,
  which is also where its maintenance is counted. Pinned by
  `CatalogUsageRegistryTest.Attribution#shouldNotCountSortOnTheCatalog`.
- **Holders are seeded eagerly, and seeding is deliberately narrower than dropping.** `alignWith` both
  mints and drops, so `observedSince` is literally the instant the capability was declared and an
  untouched flag is reported with honest zeros instead of being absent — an absence an operator cannot
  tell apart from "not declared". The two halves share `declaresCapability` as the single predicate,
  which is what stops the enumeration seeding something the survival rule would immediately drop. But
  **dropping only removes, while seeding creates**, so the asymmetry is intentional: a seeded row nothing
  can ever increment is permanently `0/0` and reads as *"unused, drop it"* — the exact misreading this
  surface exists to prevent — whereas an over-permissive survival rule only costs a stale row until the
  next adoption. Two consequences, each carrying its reason at the site: the collection enumeration
  guards on `reference.isIndexedInScope(scope)` although the survival rule does not, because
  `ReflectedReferenceSchema#shouldValidate` exempts **inherited** attributes from the validation that
  would otherwise make the guard redundant; and the catalog enumeration seeds only `FILTERABLE`+`UNIQUE` of
  `uniqueGloballyInScope` attributes, which after the suppression above is exactly what *both* recording
  sites can file into that registry.
- **The re-insertion window is accepted, not closed.** `alignWith`'s removal pass is weakly consistent
  and `resolve` is lock-free, so a query still planning against the pre-exchange schema version can
  re-insert a key the alignment just dropped; it then survives until the next adoption, and if the
  capability is re-declared in the meantime that adoption keeps it. The "dropped and re-added starts
  over" guarantee therefore has a race window. Closing it needs a lock or a generation check on the one
  path the design keeps allocation-free and lock-free (the JMH gate below is what protects it), for one
  stale row on a capability that was being queried at the instant it was dropped. Documented on
  `EntityCollection#exchangeSchema`.
- Invariants: no allocation per increment; the registry parameter must ride every new
  collection/catalog copy constructor (the lifecycle tests enumerate the sites by name — a forgotten
  site compiles and silently resets); alignment must **resolve** rather than replace, or every schema
  mutation would reset every counter in the collection; an element dropped and re-added starts with fresh
  counters and a fresh `observedSince`.

### Extending to the non-attribute flags (2026-08-20)

- **Accumulating at a translator site is not the thing the *Rejected outright* table forbids.** That row
  rejects *incrementing* there, because the planner re-translates the filter once per candidate plan and a
  counter raised at translation would measure how many alternatives were weighed. Attributes avoid it by
  having one accessor to funnel through; the non-attribute flags have no such choke point, so their dozen
  sites call `QueryPlanningContext#recordRequestedEntityCapability` /
  `#recordRequestedReferenceCapability`, which **accumulate** into the same per-query list the winning
  build flushes. Once-per-logical-query is preserved, so no supersession is needed — but a future site
  that increments directly would break it silently, which is why both helpers live on the context rather
  than being open-coded at each translator.
- **Every request is recorded *past* the assertion that verifies the flag.** The count therefore means
  *"a query depended on this flag being on"* rather than *"a query mentioned facets"* — only the former
  makes dropping the flag a breaking change, which is the question the surface answers.
- **Passing that assertion licenses the request, not the scope set it is filed under.** Recording sites take
  a `Set<Scope>` but the assertions guarding them are not uniform: most demand *every* named scope declare
  the flag, while `facetHaving`'s demands only that *one* does (`anyMatch`, so a `scope(LIVE, ARCHIVED)`
  query is legal against a reference faceted in `LIVE` alone). Filing the whole requested set behind an
  `anyMatch` guard mints a row in a scope that declares nothing, where no write can ever file a matching
  update — which reads as *"a capability nothing maintains"* about a flag that is simply not there, the one
  misreading this surface exists to prevent. Under an `allMatch` guard the two forms coincide, which is why
  the defect survived review of the seven sites where they do. A recording site must therefore gate per
  scope on the same predicate the seeding and update sides use, rather than inherit the assertion's verdict
  for the whole set; `FacetHavingTranslator` and `ReferenceHistogramStatisticsTranslator` do this
  explicitly, and `RequestedCapabilityAccumulationTest` pins it with the mixed-scope query shape that is
  the only one able to tell the two apart.
- **Reflected-reference readability is per flag, not per reference.** `ReflectedReferenceSchema` overrides
  exactly `isIndexedInScope` and `isFacetedInScope`, and each throws only when *its own* property is
  inherited and the mirrored reference is not attached; `isBucketedInScope` is not overridden and never
  throws. A blanket *"skip any detached reflected reference"* guard therefore drops a reflection that
  states its own `faceted()` — a live capability made invisible. `SchemaCapabilityUsageRegistry#isReadable`
  takes the inheritance test as a parameter for that reason. The seeding enumeration
  (`maintainsElementsIn`) stays deliberately blunter: skipping too much there costs a row that reappears at
  the next adoption, whereas dropping too much on the survival side loses counters already accumulated.
  This one caught three separate review findings — treat it as the trap of this area.
  **`reportReferenceTouched` reads nothing that can throw, and each of its three tests is chosen for that.**
  Its callers do not all arrive the same way: the single-mutation path comes through
  `ReferenceMutationFanOut#apply`, which has already called `isIndexedInScope`, while the bulk
  `indexAllReferences` / `unindexReferences` paths do **not** go through the fan-out and guard with
  `ReferenceIndexMutator#isIndexedReferenceForFiltering` instead. Those two decide the same fact, but only
  the first can throw, so this site uses the second. `FACETED` is tested behind `isReadable`, because the
  bulk paths will happily hand it a reflected reference whose faceting cannot be determined. `BUCKETED` goes
  through `maintainsHistogramIn`, whose `isBucketedInScope` is not overridden and cannot throw. Relying on
  *"some caller already read this, so it must be safe"* is what made the first two attempts here wrong —
  the reachability argument has to hold for every path, and there were more paths than the obvious one.
- **`BUCKETED`'s request site never names the flag.** Nothing in the planner reads `isBucketedInScope`;
  histograms are reached through their declared definition in `ReferenceHistogramStatisticsTranslator`.
  Recording there is what stops the row reporting maintenance nobody asked for and reading as safely
  droppable while those queries depend on it.
- **`isBucketedInScope` is not sufficient to seed `BUCKETED`, and this is the sharpest permanently-zero trap
  in the surface.** The flag means only *"a histogram is declared for this scope"* — it is literally
  `bucketedInScopes.containsKey(scope)`. A histogram declared **without** a `valueExpression` (a *count
  histogram*, allowed by the public builder as `bucketed(name, null)`) yields no
  `HistogramExpressionTrigger` at all: `HistogramExpressionTriggerFactory` skips it explicitly, and every
  maintenance site in `ReferenceIndexMutator` is gated on the trigger collection being non-empty. Such a
  histogram is therefore never added to, removed from or re-evaluated on any mutation, so its update count
  could never leave zero on a perfectly valid schema. `maintainsHistogramIn` requires at least one histogram
  in the scope to carry a value expression before the row is minted. Found by a Codex advisory during
  review, after the first version of the test had itself been written with a count-only histogram and so
  would have pinned the bug as correct.
- **A capability's condition must be shared by *three* sites, not two.** Seeding and pruning agreeing is the
  rule the attribute predicate was built around, but a recording site using a looser test defeats both: it
  lazily mints the row the other two refuse to seed, with an update count for maintenance that never
  happened, which the next alignment then drops again. `maintainsHistogramIn` is therefore public and
  consulted by `reportReferenceTouched` as well. Any future capability whose condition is subtler than a
  single flag inherits this requirement.
- **An `ENTITY` row repeats the entity type in `elementName`**, which the reported row also carries in
  `entityType`. Deliberate: the key must name a schema element without depending on which registry holds
  it, and the entity *is* the element `withHierarchy()` and `withPrice()` belong to.
- **Entity-level rows deduplicate per capability, every other kind per element.** Hierarchy and price share
  one element — the entity — but are maintained by different mutators reacting to different mutations, so a
  shared element-level entry would let whichever ran first swallow the other's count. See
  `EntityIndexLocalMutationExecutor.TouchedSchemaElement`.
- **Seeding loops must enumerate an element's own flags, never `Capability.values()`.** The predicate throws
  on a flag its element cannot carry — correctly, since such a key matches no schema — so a loop over the
  whole enum turns every added value into a runtime failure on the first attribute of every schema. Pinned
  by `ATTRIBUTE_CAPABILITIES`.

## Verification

Functional: `SchemaCapabilityUsageTest` (holder: cross-thread exactness, stamp coarsening),
`SchemaCapabilityKeyTest`, `SchemaCapabilityUsageRegistryTest` (identity; alignment seeds the declared set
exactly, preserves surviving holders and drops the rest, incl. the catalog pin above),
`RequestedCapabilityAccumulationTest` (once per logical query across
candidate plans; debug modes cannot double-flush; empty plan flushes nothing),
`EntityCollectionUsageRegistryTest` (copy sites enumerated), `CatalogUsageRegistryTest` (collection-less
query lands on the catalog, one update per entity mutation, restart resets and re-seeds, adoption realigns),
`SchemaCapabilityUsageSurfaceTest` (end to end incl. drop/re-add starting over, an untouched declared
capability reported with zeros, and an observation window at or before the first query — all three verified
to fail with seeding disabled),
`CatalogStatisticsConverterTest` round-trips (four owner shapes, int64-scale counts, absence decoding),
`EvitaClientReadOnlyTest#shouldReportSchemaCapabilityUsageOverTheWire` (real server, epoch/empty-string
decode traps).

For the non-attribute flags (2026-08-20):
`SchemaCapabilityUsageRegistryTest.ReferenceAndEntityCapabilityTest` — each of the five seeded from a schema
that declares it, including `BUCKETED` on its own rather than riding along with `faceted()`, since it is the
one flag no query path consults by name and therefore the easiest to wire in one direction only; a reference
that is not `indexed()` seeding nothing at all; a hierarchy seeded only in the scope that indexes it; and
dropping `faceted()` leaving the `indexed()` holder — and its counters — untouched. A count-only histogram
seeding **no** `BUCKETED` row while still seeding the reference's own `INDEXED` row is pinned separately by
`shouldNotSeedACountOnlyHistogram` — that pair is what keeps the suppression from over-reaching.
`CatalogStatisticsConverterTest#shouldRoundTripEverySchemaCapabilityAndElementKind` walks both enums value by
value and asserts the wire constants stay **distinct**: the converter switches are exhaustive, so a missing
value cannot compile, but two capabilities mapped onto one constant compiles fine and would silently pool two
flags' traffic into a single reported row.

JMH gate (`SchemaCapabilityUsageBenchmark`, evita_performance_tests): before = `b6d181bc3` (pre-
instrumentation), after = branch tip; identical public-API-only benchmark sources built into both jars.
24 hardware threads, `@Threads(MAX)`, 2 forks × (5×2 s warmup + 5×3 s measurement), query cache
disabled so every invocation pays the planning pass the recording lives in. Two passes with the jar
order reversed to control run-order warm-up bias:

| Benchmark | Pass | before (ops/s) | after (ops/s) | delta |
|---|---|---|---|---|
| contendedSingleAttributeLookup (every thread, one shared holder) | 1 | 1 639 535 ± 45 413 | 1 622 580 ± 107 487 | −1.0 % |
| contendedSingleAttributeLookup | 2 (reversed) | 1 670 930 ± 25 076 | 1 673 114 ± 18 250 | +0.1 % |
| representativeAttributeFiltering (between + sort, page 20) | 1 | 291 972 ± 6 330 | 317 240 ± 5 995 | +8.7 % |
| representativeAttributeFiltering | 2 (reversed) | 330 988 ± 9 441 | 323 728 ± 14 618 | −2.2 % |

The representative case swinging +8.7 % → −2.2 % purely with run order shows the deltas are machine
warm-up noise, not instrumentation cost. The adversarial shared-holder case — the design's only
conceivable contention point — is flat within error bars in both passes. **No measurable regression;
the `everRequested` fallback stays unimplemented.**

## Consequences & open follow-ups

- **Non-attribute flag breadth landed on 2026-08-20**, extending the same architecture to the flags an
  attribute does not own: `FACETED`, `INDEXED` and `BUCKETED` on a reference, `HIERARCHICAL` and
  `PRICED` on the entity, with `ElementKind` gaining `REFERENCE` and `ENTITY` to carry them.
  See *Extending to the non-attribute flags* below for the three things that were not obvious.
- **Per price list × currency granularity is still not started.** `PRICED` reports the *flag*, which
  is what a schema mutation drops; splitting it per price list would report something no single mutation
  can remove, and the counting site does not distinguish them anyway.
- **Cross-collection trigger maintenance is not counted**, inherited from the per-index gap: index work
  dispatched through `IndexMutationExecutorRegistry` never reaches
  `EntityIndexLocalMutationExecutor`, so `updatedCount` is a floor. Same direction of error, same
  documented-on-the-surface treatment. Referenced-entity cross-collection *query* attribution is
  likewise a documented gap, not a wrong number.
- **A new copy site can silently reset the counters** — the same standing hazard the 2026-08-16 record
  carries for `IndexActivity`; extending the enumerated lifecycle tests is the check.
- **No user-facing documentation page exists**, because the management surface it extends
  (`browseIndexes` and friends) still has none; whoever writes that page should present this surface
  and the per-index one side by side, with the prose lifted from `SchemaCapabilityUsageStatistics`.

## Related work

- [2026-08-16-per-index-usage-statistics](2026-08-16-per-index-usage-statistics.md) — the per-index
  counters this surface complements; the holder-by-reference lifecycle discipline and the
  since-catalog-load contract were adopted from it, and the two surfaces are designed to be read side
  by side (physical earning vs. logical demand).

## Timeline

- **2026-08-17** — design adjudicated with Johnny and a Codex design advisory; plan fixed
- **2026-08-18** — registry, both accumulation sides, catalog twin implemented
- **2026-08-19** — diagnostic surface incl. gRPC, evolution pruning proven, JMH gate passed
- **2026-08-20** — vocabulary renamed to match the flags it reports (`FILTERABLE`/`SORTABLE`, and
  `…Statistics` rather than `…Snapshot`, which is the transaction layer's word); breadth extended to the
  reference and entity flags
