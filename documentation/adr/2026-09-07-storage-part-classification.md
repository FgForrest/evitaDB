---
title: A storage part declares which kind of data it holds, at registration, in a closed enum
date: 2026-09-07
updated: 2026-09-07 12:10
status: accepted
kind: feature
issues: [1500]
prs: []
areas: [evita_api/api/statistics, evita_engine/spi/store, evita_engine/core/catalog, evita_store/evita_store_key_value, evita_store/evita_store_entity, evita_store/evita_store_server, evita_external_api/evita_external_api_grpc]
supersedes: []
superseded-by: []
relates: [2026-08-10-catalog-and-collection-statistics]
---

# A storage part declares which kind of data it holds, at registration, in a closed enum

The `STORAGE_COMPOSITION` component reported one row per storage-part type, identified by the part's
simple class name. Every such row now also carries a `StoragePartGroup` — one of fourteen closed
values — and the `StoragePartKind` it folds to. The classification is declared where the type is
registered, so a new storage-part type cannot exist without one, and a client grouping a composition
table never has to know what an `EntityIdsStoragePart` is.

## Why

`storagePartType` is the simple class name of a registered storage part. That set is **open**: it has
36 members today across three registries and grows whenever the engine gains an index structure — a
large index switches to paged storage and starts emitting `FilterIndexLeafPagePart`,
`RangeIndexLeafPagePart` and their siblings, on data that never emitted them before. Nothing else in
the response says what any of those names *are*: no grouping field, no enum, no ordering by kind.

So a client rendering the breakdown had to carry its own name-to-label map, and the constraint that
makes this non-obvious is that **it cannot be written correctly from outside the engine**. The first
client to try produced all three failure modes at once:

- `EntityIndexStoragePart` alone was labelled "Indexes". It is deliberately the *small* sibling —
  attribute indexes are stored separately precisely so the manifest is not rewritten when they change
  — so the row reported 2 129 B of the 45 739 B it appeared to name, understating the index footprint
  by 21× on a fixture where indexes were 44 % of the collection's record payload.
- Nine index types matched nothing and fell through to a suffix-stripping fallback, surfacing internal
  class names (`FilterIndex`, `SortIndex`, `EntityIds`, …) as rows of their own.
- One entry named a type that has never existed — `GlobalIndexStoragePart`, where the registry declares
  `GlobalUniqueIndexStoragePart`.

A name test cannot rescue this either: `EntityIdsStoragePart` and `HistogramCardinalityStoragePart` are
index parts whose class names carry no `Index` at all, and both are registered in
`IndexStoragePartRegistry`.

### Previous state

`StoragePartUsage(storagePartType, count, totalBytes)`, produced from the offset index's own histogram
via `recordTypeRegistry.typeFor(id).getSimpleName()`, and carried to clients as
`GrpcStoragePartUsage`. Correct and reconcilable — the summed bytes are exactly the record payload the
data store holds — but unclassifiable by its consumer.

## Options considered

### Option A — declare the group on the registration record (chosen)

`StoragePartRegistry.StoragePartRecord` gains a third component, so every entry of all three registries
names its group. The offset index's type registry indexes name → group alongside its existing
id ↔ class maps, and the composition measurement fills the new field from it.

- **Pros:** the classification cannot be omitted — the record does not compile without it. It sits in
  the module that owns the types. It is one declaration per type, and the same declaration serves both
  the fine group and the coarse kind.
- **Cons:** touches the registration signature, so the one benchmark that registers a synthetic type
  had to be updated.

### Option B — a marker-interface hierarchy (declined)

`EntityStoragePart` already exists; add `IndexStoragePart`, `MetadataStoragePart` and classify by
`isAssignableFrom`.

- **Pros:** no signature change; the classification travels with the type.
- **Cons:** the twelve leaf-page classes hang off `AbstractLeafPagePart implements StoragePart` and
  would all need retrofitting, and the interface alone cannot separate an attribute-index leaf from a
  price-index leaf without a second tier of markers.
- **Rejected because:** a forgotten marker falls through to a default silently, which is the exact
  failure mode being removed — the classification would be wrong in the same invisible way the client
  allowlist was.

### Option C — infer the group from the declaring registry or the package (declined)

- **Pros:** zero declaration; three registries, three groups.
- **Rejected because:** the registry boundary does not match the grouping, and two live counter-examples
  prove it. `EntitySchemaStoragePart` is declared by `EntityStoragePartRegistry` yet is metadata, and
  `GlobalUniqueIndexStoragePart` is an attribute index living in the catalog's own data store. Package
  inference fails for the same reason plus a second: it would silently reclassify a type on a move.

### Option D — ship pre-folded group rows instead of classifying each entry (declined)

Send `groups[]` of `(group, kind, count, totalBytes)` and let per-type rows stay unclassified.

- **Pros:** zero client arithmetic; shares sum by construction.
- **Rejected because:** it makes the per-type table unusable — a client wanting to expand a group back
  into its types is straight back to guessing — and it puts two representations of one measurement on
  the wire. Summing is the one operation a composition table can safely do; classifying is the one it
  cannot.

## Decision

**Chosen: Option A.** The property worth buying is that a *new* storage-part type lands somewhere
correct without anybody being told, and only a declaration that the compiler demands delivers it. The
other options all degrade silently, which is how the client-side allowlist failed in the first place.

Two shape decisions follow from it:

**Fourteen groups folding to three kinds, not three buckets.** Three coarse kinds are the minimum that
can be honest — every collection store reports `EntitySchemaStoragePart` and the catalog's own store is
entirely metadata and catalog-level indexes, so a data/index split has no home for four registered types
and would render a schema as entity data. The finer groups cost the same single declaration and buy
something three cannot: they run *parallel* to the data groups, so `ATTRIBUTE_DATA` against
`ATTRIBUTE_INDEX` and `PRICE_DATA` against `PRICE_INDEX` state what indexing a schema feature costs
against what storing it costs. A single `INDEX` row cannot be acted on; that pair can.

**`kind` travels on the wire but is derived in Java.** A generated TypeScript enum carries no behaviour,
so a client cannot fold group → kind without the mapping table this change exists to delete. The Java
record derives it instead, and the decoder ignores the wire's `kind` entirely — a peer therefore cannot
produce a record whose group and kind contradict each other.

The enum being **closed** is the contract, not an implementation detail. A new part type is expected to
map into an existing group; adding a group value is a deliberate, documented event, because every client
must grow a label and a colour for it. If that ever stops holding — if a genuinely new kind of stored
thing appears that is neither entity data, an index over it, nor the store's own metadata — this record
is what a superseding one has to argue against.

## Key technical details

- **Entry points.** `StoragePartRegistry.StoragePartRecord` is where a type is classified;
  `OffsetIndexRecordTypeRegistry#groupFor` resolves it; `OffsetIndexStoragePartPersistenceService#measureStoragePartComposition`
  fills it into `StoragePartFootprint`; `StoragePartProjection` carries it to `StoragePartUsage`.
- **Leaf pages are charged to the family whose tree they page.** `PAGED` versus `SINGLE` is a
  storage-format choice, not a different kind of data, and splitting root from leaves would leave two
  numbers neither of which can be read alone.
- **Faceting is charged apart from references.** `io.evitadb.index.IndexType` already spends
  `FACET_INDEX` and `REFERENCE_INDEX` on different things, and `faceted` and `indexed` are separate
  schema decisions an operator turns off separately.
- **The histogram identifies a record type by its simple class name**, which is why registration now
  refuses two types that would share one. Before this change such a collision would have merged two rows
  into one that reported the sum of both and named only half of it — a latent defect the name-keyed
  group lookup made worth closing rather than documenting.
- **Nothing here is persisted.** The classification is computed at read time from a registry built at
  startup; no Kryo format, serializer version or on-disk record changes.

## Verification

`StoragePartGroupRegistrationTest` (5 tests) pins the full 36-type table as an explicit map — written
out rather than derived, so that a reclassification is a reviewed edit rather than a silently restated
one — and asserts that no group is declared without a member, that a simple-name collision is refused,
and that an unregistered type raises rather than falling into a plausible bucket.
`StoragePartCompositionTest` (7) adds the fold assertions: every entry classified, the entity schema
reported as metadata from inside the *entity* registry, the catalog's own store carrying no entity data
at all, and both folds conserving every byte. `CatalogStatisticsConverterTest` (29) adds a wire test the
round trip structurally cannot perform — that `kind` is actually sent, and that a tampered `kind` cannot
reach a decoded record. `EvitaClientReadOnlyTest` asserts the classification survives server → gRPC →
driver on a breakdown the engine really produced.

Counterfactuals, each with the source restored and `cmp`-verified afterwards: misclassifying
`EntitySchemaStoragePart`, removing the encoder's `setKind`, and dropping the collision guard each fail
exactly the test that claims to cover them — and the `setKind` break leaves both full round-trip tests
green, which is why that test exists separately.

`buf lint` passes; the whole reactor builds with `-P full`.

## Consequences & open follow-ups

**The catalog level still reports only the catalog's own data store.** ADR
`2026-08-10-catalog-and-collection-statistics` refused a cross-collection composition on the ground that
"adding record counts of different storage-part types out of different stores does not describe
anything" (`VolatileStateStatistics` javadoc). Grouping weakens that reason — group *bytes* summed across
stores do describe something, and a group roll-up is thirteen rows regardless of collection count, so it
would not violate the "must not grow with the number of collections" constraint that killed the
per-collection breakdown. It was still declined for now, deliberately: the client that motivated this
work fetches per collection anyway. Revisit if a catalog-level "data versus index" chart is wanted, and
note the window — `GrpcStoragePartUsage` and `GrpcCollectionStorageComposition` freeze the day 2026.3
tags, and the statistics surface has shipped in no release before it.

**No pre-folded rows on the wire.** The client sums. If a future client cannot, the additive move is a
`groups[]` field, not a reshape.

**`OffsetIndexRecordTypeCountChangedEvent` does not carry the group.** Adding it as a second
`@ExportMetricLabel` would let Grafana chart data-versus-index bytes over time without a 36-series
dashboard, and the classification is already at hand where the event is emitted. Left out as unrelated
to the reporting surface this issue is about.

**The statistics API is still undocumented for users.** Nothing under `documentation/user/` mentions
`STORAGE_COMPOSITION`, so this taxonomy has no user-facing home yet; when that documentation is written,
the fourteen groups are what it should describe rather than the 36 class names.

## Related work

- [Catalog and collection statistics](2026-08-10-catalog-and-collection-statistics/README.md) — built the
  component model and the composition breakdown this record classifies. Its "no catalog-wide sum"
  decision is the one this change puts back on the table without yet reversing.

## Timeline

- **2026-09-07** — the client-side mapping's three failure modes analysed against a live server;
  classification designed, implemented and verified
