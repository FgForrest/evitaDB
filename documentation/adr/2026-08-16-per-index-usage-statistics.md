---
title: An index's usage counters live in a holder passed by reference through every merge copy, not in the index itself
date: 2026-08-16
updated: 2026-08-18 11:20
status: accepted
kind: feature
issues: []
prs: []
areas: [evita_api/api/statistics, evita_engine/index, evita_engine/core/query, evita_engine/core/catalog, evita_engine/core/collection, evita_external_api/evita_external_api_grpc]
supersedes: []
superseded-by: []
relates: [2026-08-10-catalog-and-collection-statistics]
---

# An index's usage counters live in a holder passed by reference through every merge copy, not in the index itself

Every index now reports five readings alongside the cost figures it already carried: how many executed
query plans **chose** it, how many entity mutations **acquired** it for modification, when each of
those last happened, and when observation of it began. They surface on `BrowsedIndex` (the browse row) and `IndexDetail` (the drill-down),
travel over gRPC, and are counted since the catalog was loaded. The state behind them is not a field of
the index — it is a separate `IndexActivity` object the index holds a reference to, and that reference
is passed unchanged through every constructor that rebuilds an index.

## Why

The statistics surface built in [2026-08-10](2026-08-10-catalog-and-collection-statistics/README.md)
answers what an index **costs**: its exact heap footprint, how many entities it covers, how selective
its attributes are. It cannot answer what the index **earns**. An operator looking at a 400 MB
`REFERENCED_ENTITY` index has no way to tell a load-bearing index from one that is maintained on every
write and read by nothing — and those are precisely the two cases that look identical from the cost
side and want opposite actions. The action this unlocks is concrete and cheap: drop a `filterable()`
or `sortable()` flag, or rethink a reference's indexing mode.

The constraint that made this non-obvious is that **an index is a value, not a place**. Every commit
that dirties an index does not mutate it — it builds a replacement through
`createCopyWithMergedTransactionalMemory` and swaps it in; clean indexes are carried across by
reference, dirty ones are rebuilt. A counter held as an ordinary field of the index therefore resets on
every commit that touches it, which is to say: it works perfectly on the indexes nobody writes to, and
loses its history on exactly the indexes worth measuring. Nothing about the symptom would have said so —
the number keeps advancing, it just starts over.

## Options considered

### Option A — a holder object passed by reference through every copy constructor (chosen)

`IndexActivity` is a `final class` with four `volatile long` fields, held by `Index` and exposed by a
new `getActivity()` contract method. Fresh creation and reload-from-disk allocate a new one; every
merge copy hands the existing instance to the reconstruction constructor, the way `primaryKey` travels
and unlike `version`, which is recomputed.

- **Pros:** the holder's lifetime is the logical index's lifetime with no bookkeeping — it is reachable
  exactly while some catalog version still points at the index, and dies with it. The increment sites
  hold an index instance and need nothing else. Reload allocating a fresh holder is what makes "since
  catalog load" true by construction rather than by a reset call somebody has to remember.
- **Cons:** every reconstruction constructor grows a parameter, and a *new* copy site added later that
  forgets it silently resets the counters. That is the failure mode, and it is what `IndexActivityTest`
  exists to catch — it asserts holder identity across all five merge copies plus `CatalogIndex`'s
  shallow copy, and that the reconstruction constructor counts into the holder it is handed.

### Option B — an external registry keyed by index primary key (declined)

A `Map<indexPk, counters>` on the owning `EntityCollection`, outliving the indexes it describes.

- **Pros:** survives every commit for free, with no constructor threading and no copy site to forget.
- **Cons:** the query-side increment happens in `QueryPlanBuilder.build()`, which holds index
  *instances* and has no collection in hand — every increment would first have to find the index's
  owner. Dropped indexes leak their row until something sweeps them, and the sweep has to be written
  and tested. The registry itself then needs its own carry-across-catalog-version story, which is the
  problem this was supposed to avoid.
- **Rejected because:** it moves the threading problem rather than removing it — from a constructor
  parameter the compiler checks to a lifecycle nothing checks — and it puts an owner lookup on the
  query path.

### Option C — persist the counters with the index (declined)

Store both counts and both stamps in `EntityIndexStoragePart` so the readings survive a restart.

- **Pros:** a lifetime total is a stronger statement than a since-load one, and would survive the
  nightly restarts some deployments do.
- **Cons:** it puts a value that changes on every query and every mutation into the index's manifest,
  which is written on flush and carried through the WAL — a rewrite per commit for telemetry — plus a
  Kryo format change with its backward-compatibility burden.
- **Rejected because:** the operational use of these numbers is a **rate over an observation window**
  ("this index served nothing all week while taking 40,000 writes"), and a since-load counter answers
  that as well as a lifetime one. Paying persistent-format cost for a figure whose value is
  differential is the wrong trade. `ActivityStatistics` already sets this precedent — it counts since
  the instance was created and says so.

### Option D — export per-index counters as Prometheus series (declined)

- **Pros:** graphable over time without polling a management API, which is what an operator would
  reach for first.
- **Cons:** index identity is a high-cardinality label — a production catalog was measured at 523,290
  indexes, which is a metric explosion, not a metric.
- **Rejected because:** cardinality. **Revisit as an aggregate**: a catalog-level "indexes never
  queried since load" gauge carries the same signal at cardinality one, and is additive to build
  later.

## Decision

**Chosen: Option A.** The deciding driver is where the increments happen. Both sites — the query
planner and the index mutation executor — hold an index instance and nothing else, so a design in which
the counter is reachable *from the index* costs one field dereference, while any design in which it is
reachable *from the index's owner* costs a lookup on the hottest paths in the engine. Everything else
follows: the holder dies with its index, the reload path resets by allocation, and the one real hazard —
a future copy site that forgets to pass it — is exactly the kind of thing a test can pin, which
Option B's leaked registry rows are not.

The registry would win if index instances ever became unreachable from the increment sites — if, say,
query planning came to work against index *descriptions* rather than instances. Nothing suggests that
is coming.

## Semantics decided, and one deliberate inversion

**The query side counts *chosen*, not *consulted*.** The increment is in `QueryPlanBuilder.build()`,
over the winning `TargetIndexes` set. Planning also builds and costs candidate plans that lose, reaches
a collection's super price index from a reduced-index plan, and pulls referenced-entity indexes to
enrich what is fetched — none of it counts. Counting every consultation was on the table and lost
because it inflates the losers: an index the planner rejects on every query would report the same
traffic as the one it picks, which is the precise distinction the reading exists to draw.

**The update side counts effort, including effort a rollback undoes — the inversion.**
`EntityIndexLocalMutationExecutor.applyChanges()` runs *before* the commit-or-rollback decision, so a
transaction that aborts still counts on every index it touched. This is the opposite of the rule
`IndexPopulation` documents for itself, and deliberately so: a *population* count describes surviving
state and must therefore move rollback-correctly at the commit merge, whereas this counts maintenance
**cost**, and a rolled-back transaction genuinely paid it. The two rules are both right because they
measure different things; the tell for which one a new counter needs is whether it describes what the
index *contains* or what the index *did*.

The same reading is why **a live catalog counts one write more than once**. A transactional mutation is
applied to the index in the writing session's isolated layer and applied again when
`TransactionManager` replays it from the WAL onto the trunk, and both passes are work the index really
performed. Warm-up writes count once, transactional ones twice. This is stated on `BrowsedIndex` and
`IndexActivity` rather than corrected, because correcting it would mean threading "am I a replay" down
into the executor to make a telemetry figure match an intuition it never promised. Compare indexes
against each other, not against an expected mutation count.

**A `GLOBAL` index's update count is close to the collection's total mutation count**, because
practically every entity mutation acquires it. That is accurate rather than misleading — a global index
is never a drop candidate — and it is documented rather than special-cased. The actionable readings are
on reduced indexes, which are acquired only when genuinely touched.

**Usage-counter browse orderings shipped as the follow-up this record originally deferred.** The
open question the deferral named — what a page means when the sort key moves under concurrent
traffic — was resolved as: a ranked page is a best-effort top-N view whose candidates each freeze
their ranked reading exactly once during the walk. The freeze is a correctness requirement, not a
presentation choice: a comparator reading the live holder can answer two comparisons of the same
pair differently, a `PriorityQueue` does not reheapify a mutated element, and TimSort may detect
the contract violation and throw — so the comparator sees only the frozen value plus the
`EntityIndexKey` tiebreaker, and the row reports the very value that placed it. The other activity
readings on a row stay fresh reads; only the ranked one has a position to stay consistent with.
Pages are documented as unstable across calls (recording does not advance the catalog version), the
deep-page bound covers every ranked ordering by naming the exempt `MAP_ORDER` so a future value is
bounded by default, and the catalog browse stopped collapsing the counter orderings — a catalog
index is chosen and maintained like any other; only the entity-count ordering keeps its documented
degeneracy there.

**`observedSince` joined the readings as their denominator.** A count without a window start cannot
become a rate, and a zero count cannot be told from a short observation. The stamp is per holder —
the moment observation of *that* index began — deliberately not per catalog load, because an index
created hours after the catalog opened was not observable before it existed and a shared timestamp
would make "never queried in the N days observed" dishonest for it. Plain `final`, published safely
through the index's final `activity` field; heap formulas moved from four to five longs (48 → 56
bytes per holder on the tested layout, ~4 MiB across the measured production catalog's 523k
indexes). On the wire it is additive (`GrpcBrowsedIndex` tag 13, `GrpcIndexDetail` tag 9), and its
absence is meaningful: a **new client decoding an old server** gets `null`, surfaced as
`observedSinceIfKnown()`, never a substituted instant — the epoch fabricates a decades-long window
that turns "never queried in the last week" falsely true, "now" a zero-length one that turns every
rate infinite. Both substitutions were implemented and rejected in review before absence won.
Rate sentences themselves ("a hundred times an hour") are client presentation computed as
`count / (now - observedSince)` and labelled lifetime averages; in-engine windowed rate buckets and
counter persistence stay rejected — memory times hundreds of thousands of indexes for the former,
dirty persistent state for a telemetry figure for the latter.

## Key technical details

**Entry points.** `io.evitadb.index.IndexActivity` is the holder and carries the semantics in its
javadoc; `Index#getActivity()` is the contract, implemented by `EntityIndex` and `CatalogIndex`. The two
increment sites are `QueryPlanBuilder#build` and `EntityIndexLocalMutationExecutor#applyChanges` — one
`System.currentTimeMillis()` per site, shared by every index it stamps, so a single query or a single
entity mutation cannot leave two different moments behind. `BrowsedIndex` carries the primary javadoc
for all five readings; `IndexDetail` refers to it.

**`EntityIndex#getActivity()` is `final`, and that is load-bearing.** The engine builds throwing
ByteBuddy stubs for evicted indexes (`createThrowingStub`), and ByteBuddy cannot override a final
method — so the stub answers from its real superclass instance instead of throwing. A non-final
accessor would make an index browse blow up on any collection with an evicted index.

**Six copy sites, not five.** Five are `createCopyWithMergedTransactionalMemory` overrides — four entity
indexes (`GlobalEntityIndex`, `ReducedEntityIndex`, `ReducedGroupEntityIndex`,
`ReferencedTypeEntityIndex`) plus `CatalogIndex`. The sixth is
`CatalogIndex#createShallowCopyWithResetDirtyFlag`, which rebuilds an index on go-live and on catalog
rename. It was not in the original survey and is the reason the identity test enumerates copy sites
explicitly rather than testing a representative one.

**Heap accounting.** `EntityIndex.getBaseHeapSizeInBytes` counts one more reference slot (`12L` → `13L`)
and charges the holder as `sizeOfObject(5 * Long.BYTES)`; `CatalogIndex` does the same (`3L` → `4L`).
The holder is charged in full by whichever index instance is being measured even though it is shared
across catalog versions, because only one version is ever walked — the ruling is written down in
`heap-accounting-rules.md`. `LongAdder` is deliberately not used: its cell array grows under contention
and would make the byte-exact JOL assertions nondeterministic. The CAS updaters are `static` fields, so
an instance is five longs and nothing else — four volatile, plus the final `observedSinceMillis`.

**The wire is additive.** `GrpcBrowsedIndex` gains tags 9-13 and `GrpcIndexDetail` tags 5-9, and
`GrpcIndexBrowseOrdering` values 3-6. The two
counts are `int64`; the two stamps are message-typed `GrpcOffsetDateTime` so that "never" is expressible
as absence — a `0` sentinel would render as a date in 1970 on every never-queried index, which is most
of them.

## Verification

`IndexUsageStatisticsTest` (9 tests) drives an embedded `Evita` end to end through the very management
calls an operator uses. Its fixture gives four categories an identically-sized index each, so every
assertion compares an index that saw traffic against a sibling that did not — a counter advancing on
*every* index, the plausible way this goes wrong, fails rather than passes. Two of its cases are the
load-bearing ones: an index the planner built a candidate plan around and then discarded must **not**
count a query, and counters must survive a commit that dirties the index. The query-side tests
deliberately assert nothing about *which* index the planner picks — that is the cost model's business
and free to change.

`IndexActivityTest` (11 tests) pins holder identity across all six copy sites and a fresh holder on
reload. `EntityIndexHeapSizeTest` and `CatalogIndexHeapSizeTest` (23 tests) verify the heap arithmetic
against JOL byte-exactly. `CatalogStatisticsConverterTest` round-trips both surfaces including a
never-queried row, where the absent stamps must decode to `null` rather than to the epoch.
`EvitaClientReadOnlyTest#shouldReportIndexUsageOverTheWire` proves the readings survive the driver.
It asserts four *independent* presence flags rather than a pairing, and that is the point: a projected
row is a non-atomic snapshot in both directions, because a projection reads the count before the stamp
while `IndexActivity` advances the count first. Neither reading implies the other on a row, so a test
that says otherwise pins nothing and flakes. What a dropped field cannot survive is the readings
arriving uniformly empty.

## Consequences & open follow-ups

**A seventh copy site would silently reset the counters.** The compiler catches it — the reconstruction
constructors take the holder as a required parameter — but only if the new site goes through one of
them; a site that calls a *fresh-creation* constructor compiles and quietly starts over.
`IndexActivityTest` enumerates the six known sites by name, so extending it is the check.

**The numbers are not documented for users, because the surface they sit on is not.**
`browseIndexes` / `getIndexDetail` have no page under `documentation/user/en/` — the 2026-08-10 feature
shipped without one — so there was nothing to extend and inventing a management-API chapter here would
have been a larger and separate piece of work. Whoever writes that page should document all four
readings together with the cost figures beside them; the prose to lift is on `BrowsedIndex`.

**Index maintenance done by a cross-entity trigger is not counted.** `EntityCollection#applyIndexMutations`
dispatches through `IndexMutationExecutorRegistry` to executors that acquire indexes via
`EntityIndexMaintainer#getOrCreateIndex` — a path that never reaches
`EntityIndexLocalMutationExecutor.accessedIndexes` and therefore never reaches `recordUpdate`. An index in
collection B maintained mostly by writes to collection A consequently under-reports, which is the wrong
direction for a metric an operator uses to decide what to drop. Read `updateCount` as a floor. Counting it
means recording at the dispatch site rather than widening the executor, and it was left out of this change
because it puts telemetry bookkeeping on a hot path outside the change's own scope; the exclusion is stated
on `BrowsedIndex#updateCount()`.

**No aggregate is exported anywhere.** Per-index Prometheus series were rejected on cardinality, and
nothing replaced them, so an operator learns an index is cold only by opening the browse. A
catalog-level gauge ("indexes with zero queries since load") is the cheap version of that and is
additive whenever somebody wants it.

**The transactional double count is a documented property, not a bug to be fixed later.** If a future
reader decides it should count once, note that the fix is not in the counter — it is a "this is a
replay" signal threaded into `EntityIndexLocalMutationExecutor`, which is a real cost paid on the write
path for a telemetry figure.

## Related work

- [Catalog and collection statistics](2026-08-10-catalog-and-collection-statistics/README.md) — the
  surface these readings were added to, and the source of the constraint they had to respect: a browse
  row may carry `O(1)` readings and may never be *ordered* by one that has to be measured. It is also
  where the heap-accounting rules this change extends are kept.

## Timeline

- **2026-08-16** — implemented: holder threaded through all six copy sites, both increment sites, the
  API records, the gRPC wire and the driver
