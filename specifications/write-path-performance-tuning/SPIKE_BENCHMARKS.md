# Spike benchmarks — what each one measures and concluded

Per the project convention: a spike worth keeping in
`evita_test/evita_performance_tests/.../io/evitadb/spike/` is committed to Git alongside a summary
of what it measured and concluded, rather than left as an unexplained standalone class. This file
covers the spikes that came out of the two #760 performance-tuning lines. Method details (build
flags, how to run, the four silent-failure traps) live in `MEASUREMENT_RECIPE.md`; this file is the
per-spike "what it's for and what it found".

Several spikes reproduce a package-private production class or method in isolation specifically so
they can be JMH-benchmarked without pulling in the whole `EvitaSession`/`Catalog`/`Transaction`
machinery — where that is done, the header says so and names the reproduction technique.

## From the WAL-replay / commit-latency line (this spec)

### `SortIndexBenchSupport`, `SortIndexChurnReport`, `SortIndexTimingBenchmark`

Three-file trio measuring the OWNER-mode `OwnerSortIndex` granular-persistence story: how much a
sort index costs to load, read, and incrementally re-persist once its storage parts are split into
independently-addressable pages. `SortIndexBenchSupport` is the shared, **fully deterministic**
building block layer — it parses a real "anchor" ean value distribution (or generates a
shape-replicating synthetic distribution at 10k/100k/1m distinct values), builds the owner, drains
its full and incremental emissions, and serializes parts directly through their Kryo serializers, so
a sibling `dev`-branch mirror can be lined up cell-for-cell with no query-engine noise.

- **`SortIndexChurnReport`** (plain `main`, deterministic, no JMH) reports, per scenario:
  `fullPersistBytes` (the full initial emit, every leaf page + root), `churnBytesPerCommit` (the
  headline — the serialized size of **one** incremental commit after the index reaches a persisted
  steady state), the leaf-page and removal count of that commit, and the JOL deep live-heap size.
- **`SortIndexTimingBenchmark`** is the JMH counterpart, timing `loadDeserialize` (rebuild a live
  index from persisted pages via `fromPersistedPages` + `reconstructSortedRecords`), `readOrderBy`
  (obtain ascending sorted record ids), and `churnSerialize` (serialize one incremental commit's
  parts). Run with `-prof gc` to get the normalized allocation rate alongside timing.

**Dependency to know before running:** the `anchor` scenario reads
`/var/tmp/decodoma-bench/sort-anchor.txt`, produced by `SortAnchorExtractor` — that file does not
exist by default and must be regenerated. The `synth_10k`/`synth_100k`/`synth_1m` scenarios generate
their distributions programmatically and run standalone with no external file.

### `SortAnchorExtractor`

One-time, **read-only** extractor that pulls a single representative product sort-attribute's
value→record-id distribution out of a persisted evitaDB catalog into a neutral, branch-agnostic text
file, feeding the trio above. It deliberately avoids bootstrapping the whole `Catalog`/`EntityIndex`
machinery — it opens the catalog's offset index straight through the supported persistence-service
layer (`CatalogOffsetIndexStoragePartPersistenceService` for the header, then
`DefaultEntityCollectionPersistenceService` to enumerate every `SortIndexStoragePart`).

**Safety property, load-bearing:** it only ever touches a **disposable copy** (by convention,
`/var/tmp/decodoma-bench/decodoma_cz`) — the original source data is never opened. Any future use of
this extractor against a different dataset must preserve that property; do not point it at a live or
canonical data directory.

### `WarmupCopyCatalogBenchmark`

Single-threaded WARM_UP bulk-copy benchmark: connects to a locally running evitaDB server over gRPC,
reads every collection of a source catalog (default `senesi`), faithfully reconstructs the schema on
a fresh `<source>_XXXX` catalog, re-upserts every entity on a single thread in WARM_UP mode, then
switches the target to ALIVE via `goLiveAndClose()`. Reports the copy-loop wall time and the goLive
transition separately.

This was the reproducer behind the WARM_UP bulk-upsert bottleneck line
(`reports/2026-07-22-warmup-upsert-and-collation.md`): it measured the 2 020 s baseline copy (90.3 %
in `Product`), the round-2 allocation fix's ~10 % speedup, and the P2 collation-cache result.

**Fully generic — the deleted senesi dataset does not strand this spike.** `[sourceCatalog] [host]
[port] [maxPerCollection]` all default but are overridable; it works against any catalog on any
running server. A non-zero `maxPerCollection` caps entities per collection for a fast smoke test;
`0` (default) runs the real, full-dataset measurement. Schema reconstruction happens before the
clock starts and is therefore not measured; the copy-loop time necessarily includes client-side
read + gRPC round-trip overhead, which cannot be separated from the WARM_UP write cost when driving
the server through the remote driver.

## From the granular-storage-parts line (`../more-optimized-data-structures/`)

Included here because they live in the same `spike/` folder and the convention is one summary
covering the whole directory, not one per originating investigation.

### `FrontCodedFindKeyBenchmark`, `FrontCodedSerializationBenchmark`, `FrontCodedTreeQueryBenchmark`

A three-stage investigation of `FrontCodedStringColumn`'s allocation cost, each reusing the
reproduction technique the first one established (self-checked decode fidelity against the real
package-private class, since `FrontCodedStringColumn` cannot be benchmarked directly from outside
`evita_engine`).

- **`FrontCodedFindKeyBenchmark`** isolates `findKeyPosition`'s cost into two questions the alloc
  profile alone couldn't separate: how much is the per-hop `new String(...)` versus the restart-chain
  decode walk itself, and whether an unsigned byte-lexicographic compare actually beats
  `String.compareTo` on realistic keys.
- **`FrontCodedSerializationBenchmark`** is the encode-side decision gate for the same column: whether
  a bulk-encode-from-raw-bytes counterpart is worth building for the read side.
- **`FrontCodedTreeQueryBenchmark`** is the query-path counterpart, driving the **real production**
  `TransactionalBucketBPlusTree` (the exact class `GlobalUniqueIndex` uses) through repeated
  `getLongRecordEqualTo` lookups matching a unique-attribute filter at query time, with block sizes
  mirrored exactly from `UniqueIndexBPlusTreeSupport` so tree shape and descent depth match production.

### `PrimitiveColumnSerializationBenchmark`

Decision gate for the generalized "Option B" proposal (skip the box-then-Kryo-polymorphic-write round
trip on the granular leaf-page flush/load path) applied to every primitive `ValueColumn`, not just
`FrontCodedStringColumn`. Measures `InstantValueColumn` and the shared `LongValueColumn`/
`IntValueColumn` shape, with `BoxedObjectColumn` included as the zero-alloc control.

### `SortIndexCommittedSnapshotCacheBenchmark`

Validates the committed-snapshot supplier cache on `SortIndex#getAscendingOrderRecordsSupplier()` /
`#getDescendingOrderRecordsSupplier()`. The bug it targets: every query in a transactional (ALIVE)
catalog opens and discards its own throwaway `Transaction`, which used to defeat the
`SortIndexChanges` array memoization completely — a fresh, empty diff layer was minted per query and
never survived to see a second call. The fix moved memoization onto the `SortIndex` instance itself
(a committed snapshot is immutable until the next commit produces a new instance), and this
benchmark is the regression guard for that cache actually working across repeated queries.

### `SortIndexResolvePositionsBenchmark`

Pinpointed isolation of `SortedRecordsProvider#resolvePositions` on a plain scalar `OwnerSortIndex`
attribute, built directly with no session/catalog/transaction overhead — the same isolation pattern
`SortIndexTimingBenchmark` uses. Investigates an `attributeFiltering`/`attributeAndHierarchyFiltering`
JMH regression where a CPU profile showed 37–41 % of query time inside
`SortedRecordsSupplier.resolvePositionsByDenseWalk`.
