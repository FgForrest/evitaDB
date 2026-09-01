# Heap footprint measured on the production catalog (2026-08-06)

**Why this file is kept.** The dataset it was taken against — 2.9 GB unpacked from a customer backup —
is not in the repository and never will be. These numbers cannot be regenerated without obtaining that
backup again, and they are what decided the shape of the surface: see the parent record's *Decisions
taken* and *Rejected outright*. Everything below is the measurement as taken; nothing has been
re-interpreted after the fact.

Dataset: `backup_<catalog>_actual_2026-08-04T17-51-25.556112851Z.zip`. 21 collections, catalog version
7692, state ALIVE. Harness: `io.evitadb.spike.footprint.IndexMemoryFootprintSpike` in
`evita_performance_tests` (outside the default reactor, `-P full`). JDK 17, `-Xmx48g -XX:+UseG1GC`,
embedded — no gRPC in the path.

```
java -Xmx48g -XX:+UseG1GC -cp <classpath> \
  io.evitadb.spike.footprint.IndexMemoryFootprintSpike data-catalog production-catalog
```

## The numbers

| | |
|---|---|
| Catalog load | 29 s (storage open 17.5 s) |
| Indexes in the catalog | **523,290** |
| Reported index footprint | **11.55 GB** |
| Full catalog walk, cold | **1,777 – 2,097 ms** |
| Full catalog walk, warm second pass | **2,266 ms** — *no* speed-up |
| Live heap after load | **12.87 GB** |

Per collection, the two that matter:

| Collection | Indexes | Reported | Walk |
|---|---|---|---|
| Product | 251,078 | 8.14 GB | **1,249 ms** |
| Category | 83,002 | 1.48 GB | 190 ms |
| ParameterValue | 157,682 | 1.56 GB | 282 ms |
| Parameter | 28,262 | 283 MB | 41 ms |
| every other one | ≤ 2,439 | ≤ 43 MB | ≤ 6 ms |

Product's index families:

| Kind | Count | Reported | Walk |
|---|---|---|---|
| GLOBAL | 2 | 1.04 GB | 151 ms |
| REFERENCED_ENTITY | 250,804 | 6.83 GB | 1,039 ms |
| REFERENCED_ENTITY_TYPE | 15 | 124 MB | 25 ms |
| REFERENCED_GROUP_ENTITY | 256 | 145 MB | 31 ms |
| REFERENCED_GROUP_ENTITY_TYPE | 1 | 10 MB | 4 ms |

Single slowest index in the catalog: Product's live `GLOBAL`, 118,772 entities, **1.03 GB in 151 ms**.
Second: a `REFERENCED_ENTITY` with 57,423 entities, 136 MB in 19 ms. The median per-referenced-entity
index is **~4 µs**.

## What the numbers decided

**The estimate is credible.** 11.55 GB of indexes against a 12.87 GB live heap — 90 % of the heap
accounted for, with the remainder (offset index, schemas, buffers, class metadata) the right size for
what is left. This was the first end-to-end evidence that the per-layer arithmetic adds up to
something true at scale; no fixture could have shown it.

**The cost is not amortizable.** The warm second pass was *slower* than the cold one (2,266 vs
2,097 ms). There is no cache to warm: the walk is CPU-bound traversal of live structure, and every
request pays full price. A "compute once, serve from a memo" design would be serving a stale figure.

**A per-index reading is worth having, and a per-collection one is not.** Typical index ~4 µs, worst
index in a 523k-index catalog 151 ms — a 300× reduction against the collection walk in the worst case
and ~300,000× in the typical one.

**The surface fork these numbers settled.** Two options were live, both permanent wire contracts:

- **A — a nullable heap reading on `BrowsedIndex`**, populated only when the caller asks. A page is
  selected by `MAP_ORDER` or by entity count, so the caller does not choose what lands on it: a 20-row
  page of Product indexes can contain `GLOBAL` (151 ms) plus several `REFERENCED_ENTITY_TYPE`
  (12–25 ms each) — **200 ms+ for a page nobody asked to be expensive**.
- **B — a call that names one index.** Bounded by the single worst index in the catalog, 151 ms, and
  the caller picked it.

B won. A page's cost under A is the sum of whatever the ordering happened to select; under B it is one
index the caller named.

**Bytes per entity is not a constant.** Product's `GLOBAL` index ran ~8.7 KB per entity against
~2.4 KB for a large `REFERENCED_ENTITY` one. This is the measurement behind the parent record's
rejection of a `entityCount × k` weight: the ratio is a property of this customer's schema and data
distribution, so any coefficient shipped in the engine would be wrong in an unknown direction on every
other dataset.

## Defects this dataset found that no fixture had

**The heap walk threw on this catalog.** `IndexHeapSize.OWNED_KEY_SIZER` delegated every key to
`EvitaDataTypes.estimateSize`, which throws for a type outside evitaDB's own set. But a tree stores the
*normalized* key, and for three attribute types that is a class `EvitaDataTypes` has never heard of:
`OffsetDateTime` → `Instant`, `Currency` → `ComparableCurrency`, `Locale` → `ComparableLocale`. The
first `OffsetDateTime` attribute in the production catalog's product collection took the whole request down with an
`UnsupportedDataTypeException`. All three are now priced explicitly, covered by
`EntityIndexHeapSizeTest.NormalizedKeys`.

**A suspected scaling defect was a fixture artefact.** The `AttributeCardinalityKey` record hashes as
`31 * recordId + value.hashCode()`, and the fixture seeded `insertValue(null, recordId, recordId)`.
That collapses the hash to `32 * recordId` — five always-zero low bits — so 512 entries landed in 32
buckets and **every bin treeified**. A `HashMap.TreeNode` weighs 56 B against a `Node`'s 32, which
`MapHeapSize` cannot see and does not charge: −24 B/entry. Against +16 B/entry from shared
`Integer.valueOf(1)` counters, that nets to the +8 B/entry that looked like a scaling defect. With a
non-degenerate value the divergence is **exactly** `−16 × (n−1)` at both 64 and 512 values — the shared
boxes and nothing else. The blind spot is now pinned by `shouldUnderReportATreeifiedMap`, and
`MapHeapSize`'s javadoc carries the exposure: a **record** key is the shape that can reach it, because
its generated hash has no avalanche of its own. No such clustering appeared in the production catalog.

**Three JVM singletons were missing from the test's shared-root list**, each reading exactly like an
under-charge: `Set.of()` (40 B — and *not* the same object as `Collections.emptySet()`),
`VoidPriceIndex.INSTANCE` (16 B), and `Comparator.naturalOrder()` (72 B, since subtracting an enum
constant also subtracts its name and byte array).
