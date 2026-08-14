---
title: Statistics are selectable components at two levels, and an exact heap figure is reached one index at a time
date: 2026-08-10
updated: 2026-08-14 14:45
status: accepted
kind: feature
issues: [1339]
prs: [1418]
areas: [evita_api/api/statistics, evita_engine/core/catalog, evita_engine/core/collection, evita_engine/core/transaction, evita_engine/index, evita_external_api/evita_external_api_grpc, evita_driver, evita_store/evita_store_server, evita_store/evita_store_key_value, evita_common/utils]
supersedes: []
superseded-by: []
relates: [2026-07-27-write-path-performance-tuning]
---

# Statistics are selectable components at two levels, and an exact heap figure is reached one index at a time

evitaDB's management API used to answer one statistics question: a flat `CatalogStatistics` computed
in full, for every catalog, on every call. It is now a **component model** — a client names the parts
it wants and the engine computes only those — split across **two calls**: one describing a catalog
(aggregates only, cheap enough to poll) and one describing a single entity collection. On top of that
sit two drill-downs that are not components at all: a paginated browse of a collection's entity
indexes, and a call that describes **one** index named by its primary key, including the exact heap it
occupies. The old flat shape survives only as a gRPC message, assembled from the component model.

## Why

The old call had three problems that compounded each other. It computed everything, so a management
screen polling for a record count paid for a disk walk. It returned a row per entity collection, so
the polled response grew with the size of the catalog. And it had nowhere to put anything expensive:
the numbers an operator actually opens a management screen to find — which index is consuming memory,
how selective an index is, when compaction will next run — could not be added without making the
cheap path slower for everybody.

The constraint that made this non-obvious is that **the cheap and the expensive readings are wanted by
the same person, minutes apart**. An operator polls for health, sees something wrong, and drills in.
Splitting them across two products, or gating the expensive ones behind configuration, would break
that flow. They had to live in one API whose cost is chosen by the caller, request by request.

### Previous state

`io.evitadb.api.CatalogStatistics` was a flat record with `totalRecords`, `indexCount`,
`sizeOnDiskInBytes` and an array of per-collection rows, produced by `getCatalogStatistics()` with no
parameters. Live consumers read all four figures — including the gRPC surface and the Prometheus
series — which is why the old type could not simply be deleted: the components replacing its data had
to land first, or the numbers would have blanked.

## Decisions taken

| Date | Decision | Why | Detail |
|------|----------|-----|--------|
| 2026-08-04 | Selectable **components**, not a `BASIC < STORAGE < FULL` detail ladder | A ladder forces a once-and-for-all ruling on which statistic sits at which level, and makes a client wanting one expensive number drag everything cheaper along with it. Adding a component later is purely additive | `CatalogStatisticsComponent` javadoc |
| 2026-08-04 | Break Java backward compatibility outright, with no deprecation shim; keep the **gRPC wire strictly additive** | The compiler finds every Java call site, so a shim buys no safety and leaves two shapes to maintain. The wire has clients no compiler sees, so it gets the opposite treatment. *Additive applies to **shipped** messages*: this feature's own messages were still unreleased throughout, so they were renamed and retyped freely as the surface settled — `GrpcEntityCollectionIndexDetail` → `GrpcIndexDetail`, `entityCount` from `int32` to `Int32Value`, and the two RPCs with them. Nothing that ever left `dev` was touched | — |
| 2026-08-04 | Two levels, and the line between them is drawn by **cost, not tidiness** | The catalog response is the one that gets polled, so it reports aggregates only and never a per-collection breakdown — its size must not grow with the number of collections | `CatalogStatisticsComponent` javadoc, "Two levels, two calls" |
| 2026-08-04 | Components are independently *selectable*, not independently *computed* | `STORAGE_SIZE`, `FRAGMENTATION` and `HISTORY` all need file lengths. One directory listing per request is not merely cheaper — it is the only way the three cannot describe different moments | `Catalog#getStatistics`, `EntityCollection#getStatistics` |
| 2026-08-04 | A requested component that cannot be computed answers with a **status and a reason**, never with zeroes | Zeroes during a bulk load read as "idle and healthy", the exact inverse of the truth; and a corrupted catalog rendering as an empty one is precisely what an operator opened the screen to diagnose | `ComponentAvailability`, `UnusableCatalog` |
| 2026-08-06 | Heap footprints are **exact walks verified against JOL**, not formulas | A formula is unfalsifiable at scale: it stays plausible while being wrong. Measuring the real thing is what made the 90 %-of-live-heap agreement checkable | `heap-accounting-rules.md` |
| 2026-08-10 | The exact heap figure is reached only by **naming one index** | A browse page is selected by map order or entity count, so the caller does not choose what lands on it — a 20-row page can cost 200 ms nobody asked for. Naming an index bounds the cost at the catalog's single worst index and makes it the caller's choice | `memory-footprint-measurement.md` |
| 2026-08-10 | An index is addressed by its **integer primary key**, not by the browse row's discriminator | The discriminator is a *rendering*: injective, so two rows never collide, but it prints representative values through `toString` and cannot be turned back into an index key. The engine already identifies indexes by an int | `BrowsedIndex#indexPrimaryKey` |
| 2026-08-10 | A browse row carries **`entityCount`** and no derived weight | — see *Rejected outright* | `BrowsedIndex#entityCount` |
| 2026-08-10 | Withdraw the `MEMORY_FOOTPRINT` component; **no collection-level component can be refused** | Once the heap figure is per-index there is nothing for a per-collection component to be, and `MEMORY_FOOTPRINT` was the only arm that ever declined a collection-level request | `EntityCollectionStatistics.Builder` |
| 2026-08-10 | An index walk **never asks a map for an accessor**; both transactional map decorators override `forEach` so it does not either | Measuring the catalog is what found it: a `HashMap` keeps the `keySet`/`values`/`entrySet` view it hands out, so a walk on a construction or flush path costs sixteen retained bytes on every index in the catalog rather than nothing. Fourteen such views per entity index, seventeen once flushed — ~117 MB on senesi, now zero | `TransactionalMap#forEach`, `AttributeIndex#collectKeys` |
| 2026-08-10 | The price index's two **query** accessors keep handing out a cached view | They are called repeatedly against the same index by the price translators, which is what the JDK's caching is for. Converting them would trade one retained view for a fresh walk per query — the one place where removing a view is the wrong direction | `PriceIndexReadContract#getPriceListAndCurrencyIndexes` |
| 2026-08-10 | Drop `ComponentAvailability.NOT_SUPPORTED` rather than keep it for a future producer | It never had one, and the retention argument was backwards: **adding** an enum value is the wire-compatible direction, so it can return the day something declines, whereas keeping it makes every client branch on an outcome no server sends | `ComponentAvailability`, `GrpcEnums.proto` (`reserved 4`) |
| 2026-08-10 | **One `EntityIndexType`**, in `evita_api`, rather than an engine enum plus an API mirror | The mirror's only divergence was a value deprecated since 2024.12; with that gone the two were identical, and two identical enums joined by a mapping can only drift. The API module must not depend on `evita_engine`, so the shared half moves down rather than the engine reaching up | `io.evitadb.api.index.EntityIndexType` |
| 2026-08-10 | The merged enum sits in its own `io.evitadb.api.index` package, not under `api.statistics` | The engine's whole index hierarchy keys off it; reporting is one consumer among many. It is also where a `CatalogIndexType` would go if catalog-level indexes ever diversify | `evita_api/module-info.java` |
| 2026-08-10 | Retire `REFERENCED_HIERARCHY_NODE`, folding the on-disk name **at the Kryo registration** rather than in the 2024.11 reader | Constant names are the storage format here, so removal is a format change: `Enum.valueOf` would have failed the load of any pre-2024.12 catalog. All four storage-part serializer versions read the type through one registration, so folding there covers every vintage instead of requiring proof that only 2024.11 can carry the name | `EntityIndexTypeSerializer` |
| 2026-08-12 | **One browse call and one detail call for both owners**, selected by a nullable `entityType`, rather than catalog-only twins | Separate methods would have duplicated the criteria, the row type and the detail record, leaving a UI two code paths for what an operator reads as one table. The engine's own asymmetry — a collection walks hundreds of thousands of indexes, a catalog at most one per scope — is a cost difference, not a shape difference | `EvitaManagementContract#browseIndexes`, `CatalogContract#browseIndexes` |
| 2026-08-12 | A catalog index's handle is **derived from its scope by an explicit switch**, never `Scope#ordinal()` | `CatalogIndexKey` is a bare scope, so there is no assigned integer to hand back. The numbers are a published wire contract: an ordinal silently renumbers when a constant is inserted, whereas a switch stops compiling until somebody chooses the new number | `CatalogIndexProjection#toIndexPrimaryKey` |
| 2026-08-12 | A catalog index reports **no `entityCount` at all**, rather than its summed unique-value count | — see *Rejected outright* | `BrowsedIndex#entityCountIfKnown` |
| 2026-08-12 | A separate `CatalogIndexProjection` rather than generifying `IndexBrowseProjection` over the key type | The collection projection *is* its top-N heap and its overflow-guarded window arithmetic, all of which exist because the map can hold hundreds of thousands of entries. None of it can do anything over two rows, and shared code no test could exercise on one side is worse than a trivial slice. What is shared is the vocabulary — same criteria, same row, same detail record — which is the part that could actually drift | `CatalogIndexProjection` |
| 2026-08-14 | The compaction forecast samples the file's **growth rate separately from its waste rate**, and extrapolates from both | They are different quantities: a removal strands bytes and appends none, a record replaced by a larger one appends more than it strands. Projecting the file's length from the waste rate therefore fired early on delete-heavy load and late on growing records — not a bound in either direction, so it could not be defended as deliberately conservative. Waste is summed in the flush loop that already computes it; growth is read as the data file's own end-position delta in the same place, at no extra IO. Reconstructing growth from the record bodies was tried first and is wrong — every flush also appends an offset index fragment, and a removal writes only its tombstone into that fragment and no body at all, so a delete-only store reported growth `0` for a file that genuinely lengthens once per flush. The position delta agrees with `getFileSize()` by construction, which is the quantity the compaction threshold compares against. The share solve `t = (s·F − L) / (g·(1 − s) − w)` reduces to the old single-rate formula exactly when `g = w`, leaving the ordinary same-size-rewrite case unchanged | `WasteAccumulation`, `DefaultCatalogPersistenceService#projectCompactionTime` |
| 2026-08-14 | Both rate accumulators carry the **counters as of the sampling window's start**, so a rate numerator is the window's whole movement | A flush or commit landing inside an already-sampled millisecond advanced the counter but left nothing the next sample could see, so the next measurable one divided *its own* bytes by an interval spanning the whole burst. A burst of `n` inside one millisecond lost `n − 1` of them, and sub-millisecond spacing is the ordinary case during bulk load and WAL replay rather than an edge case. The same defect existed independently in both classes, which is why the fix is described once and applied twice | `WasteAccumulation#sampled`, `ActivityAccumulation#sampled` |
| 2026-08-14 | The storage footprint discovers the **storage prefix from the folder's own bootstrap file**, and never assumes it equals the catalog name | A rename or `replaceCatalog` relabels a catalog without touching a file name. All three names the decomposition matches on — bootstrap file, superseded-generation pattern, the catalog's own store — are prefix-derived, so the name collapses the *whole* decomposition into `unaccounted`, not one class of it. Discovery sits in the measurement rather than at its call sites because the unusable-catalog reading has no persistence service left to ask, and the engine must not learn how files inside a folder are named. Discovering it is only half the fix: the *consumers* have to use it too, and the first cut left the catalog's own data store, the forecast's file-length lookup and both history-horizon readers still deriving their names from the catalog name | `CatalogStorageFootprintMeasurer#discoverStoragePrefix`, `DefaultCatalogPersistenceService` |
| 2026-08-14 | The footprint **measurement** moves to `evita_store_server`; the **record** stays in the SPI | Listing a directory and reading file lengths is physical contact with storage, which belongs to `evita_store` alone — the SPI package is contracts, and `CatalogStorageFootprint` was the only file in it executing IO. The record could not move with the loop: `CatalogFolderOperations#catalogFolderFootprint` returns it from `evita_engine`, which cannot depend on the storage module. `DataStoreGenerations` and its `isSuperseded` stay too — string work on a name already read is format knowledge, not IO. The cost is real and accepted: the canonical constructor is now reachable cross-module, so "produced only by the measurement" became a convention instead of an enclosure, and the record's javadoc says so rather than claiming an invariant the code no longer enforces. Rule written up in `.claude/rules/module-boundaries.md` | `CatalogStorageFootprintMeasurer` |
| 2026-08-14 | Remove `isCatalogLevel()` and `assertCatalogLevel` outright; a catalog-level call now checks only that something was asked for | With `MEMORY_FOOTPRINT` withdrawn the flag answers `true` for all fourteen components, so the gate rejected nothing and its branch was unreachable from any input. Retaining it against the day a component needs it is speculative generality — every reader has to decode a distinction the code does not make. The asymmetry that remains is the one that is actually true: the catalog is the whole, and only some statistics narrow to a part | `CatalogStatisticsComponent#assertNotEmpty` |
| 2026-08-14 | `BIG_DECIMAL_SIZE` keeps its new payload meaning; a second constant `BIG_DECIMAL_WHOLE_SIZE` carries the owned-object figure | The constant used to mean *whole size including a `BigInteger` most decimals never allocate*, and the layout rework redefined it as payload without touching seven call sites that still added it to a reference slot — `Price.estimateSize()` fell from 132 bytes to 84. Reverting it to a whole size was the smaller diff but reintroduces the wrong number for the compact `intCompact` path and leaves `EvitaDataTypes` doing the header arithmetic twice. Two named constants make the owner/payload distinction impossible to get wrong silently, which is what actually failed here | `MemoryMeasuringConstants#BIG_DECIMAL_WHOLE_SIZE` |
| 2026-08-14 | A `ComponentStatus` **cannot** exist unavailable-without-a-reason; the gRPC decoder substitutes a placeholder rather than passing the gap through | The record promised the binding in prose and the factories guarded it, but the decoder built statuses straight from the wire, so a peer could produce exactly the silent "unavailable, no explanation" the component model exists to prevent. Reporting the peer verbatim was the decoder's stated principle and it loses here: the availability *is* reported verbatim, and inventing an explanation that says the peer sent none is strictly more informative than a `null` no caller checks. The builder carried the same gap one level up - `withUnavailable` recorded a status without clearing the paired value field, so a snapshot could hold a computed value and an `UNAVAILABLE` status for one component and contradict itself. Both recording paths now refuse that, checked against the status map rather than the value fields: an exact stand-in that needs no per-component mapping, because every `withXxx` sets its field and records the component delivered in the same call | `ComponentStatus`, `CatalogStatisticsConverter#toComponentStatuses`, `CatalogStatistics.Builder` |
| 2026-08-14 | Pending sync handles are **drained before** they are forced, not after | `pendingHandles` is a set, so re-registering a handle it still contains is a no-op: a write landing after its own `forceDurable()` returned registered nothing, and the trailing removal then erased a debt nobody had paid — a later bootstrap record pointing at never-forced bytes. Tracking registrations by generation would preserve the exact set but adds a counter per handle to guard a race whose only cost, once inverted, is one redundant ~0.5 ms flush | `CheckpointCoordinator#forcePendingSyncs` |

## Rejected outright

| Option | Rejected because | Revisit if |
|--------|------------------|------------|
| `BY_MEMORY_DESC` — order the index browse by measured heap | Ordering on the exact figure means measuring *every* index on every call, which is the one cost this surface exists to avoid | A genuinely `O(1)` proxy for heap is found. Entity count is not one — see the row below |
| A light weight, `entityCount × k`, on the browse row | `k` is heap-bytes-per-entity, and that is a property of the customer's schema and data rather than of evitaDB: senesi measured ~8.7 KB/entity for a `GLOBAL` index against ~2.4 KB for a large `REFERENCED_ENTITY` one. A constant shipped in the engine would be wrong in an unknown direction on every other dataset — and being a monotone transform of `entityCount`, it could not reorder anything within a kind anyway | A coefficient can be *derived from the running catalog* instead of shipped — e.g. calibrated from indexes the operator has already measured |
| A call that measures a whole collection | 1.25 s for senesi's Product collection, and **not amortizable**: the warm second pass was slower than the cold one, because the walk is CPU-bound traversal of live structure with no cache to warm | Never as a hidden cost. A client wanting a total issues detail calls in parallel and sums them, which keeps the price visible to whoever chose to pay it |
| Memoizing the heap walk | There is nothing to memoize that would not immediately be stale, and the measurement shows no warm-up benefit to trade against that staleness | The walk stops being traversal of live structure |
| A deprecated no-arg `getStatistics()` kept "for safety" | The compiler catches every Java call site, so it buys nothing and leaves two shapes to maintain | Never for Java. The gRPC *message* was kept for exactly the inverse reason — its clients have no compiler |
| A catalog-level form of the per-collection `INDEX_CARDINALITY` walk | It means paying the per-collection cost for every collection of the catalog at once | Never. The catalog level reports something different and cheaper under the same name — the catalog index's global unique indexes, whose count grows with neither entity nor collection count |
| Reporting a catalog index's summed unique-value count as `entityCount` | It is not an entity count: a globally-unique index maps values to records of *any* collection, so an entity carrying three globally-unique attributes would be counted three times, under a field name and a javadoc that promise a primary-key bitmap cardinality. The same reasoning `referencedEntityCount` already applies — absence is a statement about the index, `0` would read as "covers nothing" | Never under this name. The figure itself is reported, per attribute and correctly labelled, by the detail call's `distinctValueCount` / `recordsCovered` |
| Keeping the single-rate compaction forecast and documenting the assumption honestly | The cheapest option, and it was on the table: the arithmetic stays and the javadoc stops claiming that "every byte of waste is also a byte appended". It lost because the error is not one-sided — early on deletes, late on growing records — so there is no reading under which the reported timestamp is a bound the operator can act on, and an honest caption on a wrong number is still a wrong number | Never, now that both rates are measured. The wording it would have introduced survives as the class comment on `WasteAccumulation` |
| Suppressing the forecast when the two rates diverge beyond a tolerance | Fewer answers, none misleading — but the tolerance is a constant nobody has data to choose, and the case it suppresses hardest (a delete-only store) is one the two-rate solve answers correctly and usefully | A workload is found where the two-rate model is itself the wrong shape, which would make suppression a statement rather than a guess |
| Renumbering the vacated `COMPONENT_MEMORY_FOOTPRINT = 14` | Free in principle, since nothing shipped with 14 — but it is the one option under which a client built against a pre-release proto asks for 14 and is *silently* handed `VOLATILE_STATE`. `reserved` makes it an error | Never |

## Key technical details

**Entry points.** `CatalogStatisticsComponent` is the selection vocabulary and the place the two-level
cost argument is written down. `Catalog#getStatistics` and `EntityCollection#getStatistics` are the two
dispatch switches — both exhaustive over the enum, with catalog-only components throwing rather than
falling through at the collection level and vice versa. `EntityCollection#browseIndexes` and
`EntityCollection#describeIndex` are the two drill-downs. `CatalogStatisticsConverter` and
`EvitaEnumConverter` are the whole of the wire translation. `Catalog#browseIndexes` and
`Catalog#describeIndex` are their catalog-level twins, projected by `CatalogIndexProjection`;
`EvitaManagement` is the only place that chooses between the two, on `entityType == null`.

**The invariant that makes an integer handle safe to hand to a client.** Index primary keys are
**never reused**: `indexPkSequence` is only ever `incrementAndGet()`-ed, and the collection header's
high-water mark is written *from the sequence* rather than recomputed as the maximum of the keys that
survived a removal. A row held across its index's deletion can therefore fail to resolve — it raises
`IndexNotFoundException` — but can never resolve to a *different* index. Recomputing that high-water
mark from live keys would silently break this, which is why it is stated here and not only in the
javadoc.

**One renderer, two surfaces.** A browse row and its drill-down render an index's discriminator through
the same `IndexBrowseProjection.renderDiscriminator`, so they cannot drift apart.
`IndexCardinalityProjection.describeIndex` takes the rendered discriminator as a parameter rather than
computing it, because its own assertion — that the discriminator is a `String` — holds for the
schema-bounded kinds it describes and would throw for a per-referenced-entity index keyed by a
`RepresentativeReferenceKey`. The per-index detail call is the only surface on which such an index is
ever described.

**Heap accounting is a set of rules, not a set of formulas.** Every `getHeapSizeInBytes()` in the index
layer applies four accounting rules and a body of ownership rulings whose failure mode is a plausible
wrong number rather than an error. They are in `heap-accounting-rules.md`; the one most likely to be
got wrong is `EntityIndex.components`, where treating the wrapper objects as "charged as fields" charges
them *nowhere* and the symptom is a silent under-report.

**Remote callers see a message, not an exception type.** The gRPC driver rebuilds every business
failure as a plain `EvitaInvalidUsageException` carrying the server's error code, so no concrete
subclass survives the wire — `IndexNotFoundException`, `CollectionNotFoundException` and
`CatalogNotFoundException` all arrive as the base type. This is pre-existing driver behaviour, stated
here because a test asserting the concrete type passes embedded and fails over the driver.

## Verification

**5,819 tests, 0 failures, 25 skipped** on `-Dgroups="(indexing | management) & !slow"`, which covers
the engine, the converters and the driver suite end to end. The unified browse and detail add
`CatalogIndexProjectionTest` (handles, filter semantics, paging, the detail readings),
`CatalogIndexHeapSizeTest` and a `Catalog-level indexes` group in `IndexBrowseTest` that runs the whole
path through an embedded `Evita` — including the assertion that a *collection's* handle does not resolve
against the catalog, which is what makes the identity a pair rather than an integer.

Heap arithmetic is verified against JOL per class rather than in aggregate, with
`-Djol.magicFieldOffset=true` on the surefire `argLine`. The end-to-end check that the per-class
arithmetic composes into something true is the senesi measurement: **11.55 GB of reported index
footprint against a 12.87 GB live heap**, with the ~1.3 GB remainder the right size for the offset
index, schemas, buffers and class metadata. No fixture could have shown that.

Cost, measured on a 523,290-index production catalog: the median per-referenced-entity index walks in
**~4 µs** and the single worst index in the catalog — Product's live `GLOBAL`, 118,772 entities — in
**151 ms**. Full numbers and the defects the real dataset exposed are in
`memory-footprint-measurement.md`.

## Consequences & open follow-ups

**Every component has a catalog-level form, and the flag that said so is gone.**
`MEMORY_FOOTPRINT` was the only collection-only component; once it was withdrawn
`CatalogStatisticsComponent#isCatalogLevel()` answered `true` for all fourteen, and the rejection branch
of `assertCatalogLevel` could not be reached, or tested, from any input. It survived one revision on the
argument that rebuilding the gate later would cost more than keeping it, then went: a gate that never
fires is read by everyone and exercised by nothing. What remains is the asymmetry that is real —
`isCollectionLevel()` refuses the five catalog-wide components, and a catalog-level call asserts only a
non-empty selection. Should a component without a catalog-level form arrive, the javadoc of
`assertNotEmpty` says where its gate belongs.

**`ComponentAvailability.NOT_SUPPORTED` is gone.** It never had a producer, and the argument for keeping
it — that a removed value could not return without a wire change — had the direction wrong: adding an
enum value is the backward-compatible move in proto3, so it can come back the day a component genuinely
cannot be computed. The distinction it drew is real (a client shown `FEATURE_DISABLED` goes looking for a
setting to switch on, and there would be none), which is why the wire number is `reserved` rather than
reused. `CheapScalarStatisticsTest` accordingly asserts that every catalog-level component is delivered,
without a partition that iterated nothing.

**The end-to-end proof that a refusal survives server → gRPC → driver is gone.** It had exactly one case
and it was `MEMORY_FOOTPRINT` at the collection level. Every refusal the engine can still produce needs a
catalog that is warming up, corrupted, or checkpointing inline — states the driver suite's dataset is not
in. What remains: `CheapScalarStatisticsTest` proves the engine *produces* a refusal,
`CatalogStatisticsConverterTest` proves the converter *carries* one, and the single function joining them
is shared by every component's status, so a break there fails broadly rather than hides. Restoring the leg
needs a second differently-configured server or a deliberately corrupted catalog in the driver dataset —
neither of which belongs to this feature.

**All ~117 MB of cached map views is gone, and the accounting convention that hid it with it.** A
`HashMap` keeps the `keySet`/`values`/`entrySet` view it hands out, so an accessor asked for on a
construction or flush path is sixteen retained bytes on every index in the catalog — fourteen per entity
index, seventeen once flushed, across senesi's 523,290. Every such walk now goes through `forEach`, and
both transactional map decorators override it to delegate to the backing map instead of iterating the
`entrySet()` the `Map` default would request. Every empty entity index of every kind now measures
*exactly* against JOL, where the suite previously had to subtract a documented constant.

The figure is a cold-load bound rather than a constant: `AttributeIndexLoader` builds every map with
`CollectionUtils.createHashMap` and `PersistentTransactionalMap` keeps a non-`ChampMap` source thawed, so
a freshly loaded catalog — exactly what was measured — was fully exposed. A map that has been through a
commit-merge holds a sealed `ChampMap`, which caches no view either way.

`CatalogIndex` was swept last and had two more: `getModifiedStorageParts` walked its `entrySet`, and
`createStoragePart` handed the storage part the map's live `keySet` — which is also the safer thing to
fix, since a storage part is serialized after the call returns. `CatalogIndexHeapSizeTest` measures the
index either side of a flush and fails on a byte of growth, which is the only way to catch a view the
arithmetic cannot see.

Two price accessors keep their view deliberately; see *Decisions taken*.

**`EntityIndexType` constant names are now part of two contracts, not one.** They were already the
on-disk format — an index storage part stores its type through `Enum#name()` — and they are now also the
API vocabulary the statistics surface reports. Renaming one therefore breaks catalogs *and* clients, and
removing one needs the same treatment `REFERENCED_HIERARCHY_NODE` got: a fold at the Kryo registration,
plus a test that spells the retired name as a literal, because once the constant is gone nothing else in
the suite can reach that path. `EntityIndexTypeSerializerTest` is that test and is deliberately the only
remaining mention of the name. Ordinals stay unpersisted and may move freely.

**A catalog index has no `EntityIndexType` value, and this is where a future `CatalogIndexType` goes.**
The engine addresses catalog indexes by `CatalogIndexKey`, which carries a scope and nothing else because
there is exactly one kind of them — so a `CATALOG` constant would be a value every entity-index switch
had to reject, for no information gained. The absence is now load-bearing on three published records:
`BrowsedIndex#indexType`, `IndexCardinality#indexType` and the wire's `optional GrpcEntityIndexType`.
Adding a `CatalogIndexType` later is additive on all three — it is a *new* field, not a value smuggled
into this one.

**What a catalog index states by absence, it states in four places at once.** `entityType`, `indexType`,
`entityCount` and all three discriminator fields are unset together, and `BrowsedIndex`'s compact
constructor asserts the first two travel as a pair — a converter that dropped one would otherwise produce
a row describing an index that cannot exist. On the wire every one of them is an unset wrapper (or, for
the enum, proto3 explicit presence) rather than a sentinel, because each has a non-null default that a
converter reading it without a presence check would silently accept: `""`, `INDEX_TYPE_UNSPECIFIED`, `0`.
`CatalogStatisticsConverterTest` round-trips exactly such a row for that reason.

**Identity is the pair `(entityType, indexPrimaryKey)`, and the two owners derive the integer
differently.** A collection assigns it from a forward-only sequence and never reuses it; the catalog
derives it from the scope, so it denotes one logical index for the catalog's life whether or not that
index exists right now — the `ARCHIVED` index is created lazily, so its handle can fail to resolve and
later start resolving. That is a genuine weakening of the "can only fail to resolve" guarantee, stated on
`BrowsedIndex#indexPrimaryKey` and on `IndexNotFoundException`, and it is why the handle alone is not the
identity.

**`CatalogIndex` now prices itself, and the senesi 11.55 GB figure still covers entity indexes only.**
The roll-up was the piece missing from the footprint campaign — `GlobalUniqueIndex` already priced
itself, and every structure beneath it. It is charged exactly, with an empty index asserted to the byte;
what a seeded one carries above the measurement is its children's, and `CatalogIndexHeapSizeTest` pins
that as one boxed locale id at a single leaf and as a term tracking leaves rather than values beyond one.
The senesi total predates the roll-up and was not re-measured, so it remains an entity-index figure; a
catalog index is bounded by (globally-unique attributes × locales in use) and cannot move a
twelve-gigabyte total materially.

**A per-index heap reading on the browse row remains architecturally possible and remains refused.** If
one is ever added it belongs to the page that was *already* selected, and still could not be used to
select it. Reversing that would reverse the cost argument the whole browse surface rests on.

## Related work

- [Write-path performance tuning](../2026-07-27-write-path-performance-tuning/README.md) — same code
  area (`evita_engine/index`, the B+ tree layer). Its collation-key cache is one of the structures this
  work had to rule on: charged as the 48-byte private part only, never the ~30 KB shared collation
  tables.

## Supporting material

- `memory-footprint-measurement.md` — the production measurements that decided the surface. Kept because
  the 2.9 GB customer dataset they were taken against is not in the repository, so they cannot be
  regenerated without obtaining that backup again.
- `heap-accounting-rules.md` — the rules every `getHeapSizeInBytes()` applies and the ownership rulings
  that are not visible in the code applying them. Kept because a wrong ruling produces a plausible
  number rather than a failure.

## Timeline

- **2026-08-04** — component model designed and the first components delivered; legacy flat shape retired
  to a gRPC-only message
- **2026-08-05** — per-collection last-modified stamps, checkpoint retention, `INDEX_CARDINALITY` at both
  levels, paginated index browse
- **2026-08-06** — heap-footprint arithmetic across the bitmap, B+ tree, container and index layers,
  verified against JOL; measured against the senesi catalog
- **2026-08-10** — surface decided: per-index detail call addressed by primary key, `entityCount` with no
  coefficient, `MEMORY_FOOTPRINT` withdrawn along with collection-level refusal
- **2026-08-12** — browse and detail unified across both owners behind a nullable `entityType`; catalog index
  handles derived by an explicit switch rather than an ordinal
- **2026-08-14** — forecast split into a waste rate and a growth rate; both accumulators fixed to carry the
  sampling window's start; storage prefix discovered from the bootstrap file; the measurement moved out of the
  SPI into `evita_store_server` and the module boundary written down as a rule
- **2026-08-14** — review round on PR #1418: growth re-derived from the file's own end position after the
  record-body sum was shown blind to the offset index fragment; the remaining catalog-name-derived file names
  routed through the prefix; `BIG_DECIMAL_SIZE`'s payload/whole-size split propagated to its seven call sites;
  the emptied-node clamp removed from four heap walkers; pending sync handles drained before forcing
- **2026-08-14** — second review round on PR #1418, approved: the six new deprecations dated to the release
  that ships them, a throwing `default` added to both component switches, and the builder taught to refuse a
  self-contradicting snapshot. The session-registration race the same round surfaced is recorded in
  `2026-08-06-catalog-folder-decoupling` instead, whose reader guarantee it belongs to
