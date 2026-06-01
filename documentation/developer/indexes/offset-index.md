# OffsetIndex — append-only key-value persistence

The `OffsetIndex` (`io.evitadb.store.offsetIndex.OffsetIndex`, module `evita_store_key_value`) is the
**persistence substrate** of evitaDB: an append-only, file-backed key→location store on top of which
catalog and entity-collection storage parts are durably written and read back.

> **Naming caveat.** Despite the shared word "index", the `OffsetIndex` is **not** a member of the
> `EntityIndex` family described in the rest of this section (`GlobalEntityIndex`, `ReducedEntityIndex`,
> `AttributeIndex`, …). Those are *in-memory query indexes*. The `OffsetIndex` is the *on-disk storage
> engine* underneath them — closer in spirit to an LSM/mem-table than to a query index. It is documented
> here because the contrast between the two is one of the most important things to understand about how
> evitaDB persists data, and that contrast is the subject of the next section.

## Table of contents

| Section | Content |
|---|---|
| [The key difference vs. in-memory indexes](#the-key-difference-vs-in-memory-indexes) | Eager-write-to-disk vs. STM diff overlay |
| [Append-only file model](#append-only-file-model) | Fragments, `FileLocation`, dead data, WRITE/READ/DELETE |
| [MVCC via versioned roots](#mvcc-via-versioned-roots) | The `Roots` registry, lock-free historical reads |
| [Non-flushed reads & durability](#non-flushed-reads--durability) | The in-flight set, soft flush, sync |
| [History retention & purge](#history-retention--purge) | Releasing versions, telemetry |
| [Source files](#source-files) | Key classes and where they live |

---

## The key difference vs. in-memory indexes

The `EntityIndex` family is **transactional and in-memory**. A mutation made inside a transaction is
captured in a thread-local **diff overlay** against an immutable baseline (see the
[STM layer](../stm/overview.md)). Other readers see only the committed baseline; the uncommitted changes
are invisible to them, **never touch disk**, and are simply **discarded on rollback**. A durable,
version-stamped snapshot is produced only at commit.

The `OffsetIndex` works the **other way around**:

- A `put(catalogVersion, storagePart)` / `remove(catalogVersion, …)` **eagerly appends the record bytes
  to the file** straight away — *before* the version is flushed. The write is physically in the file as
  soon as it happens.
- Those appended-but-not-yet-flushed records are **"non-flushed" (in-flight)** data. They are real,
  on-disk bytes that are simply **not yet a durable, version-resolvable part of the store**.
- `flush(catalogVersion)` is what **commits** them: it promotes the non-flushed records into the
  version-resolution registry, writes the trailing index fragment, and syncs the file. After flush the
  data is durable and resolvable by catalog version.

So the `OffsetIndex` **writes non-committed (non-flushed) data directly to storage, and that data becomes
persistent after `flush`** — there is no discard-on-rollback diff overlay at this layer. Concretely:

| | `EntityIndex` family (in-memory) | `OffsetIndex` (persistence) |
|---|---|---|
| Where uncommitted data lives | thread-local diff overlay (heap) | appended **into the file** immediately |
| Visible to other readers before commit/flush | no | yes — at the writing catalog version |
| On rollback / no flush | diff discarded, nothing persisted | bytes remain in the file as **dead data** to be vacuumed |
| What "commit" means | STM merge → new in-memory snapshot | `flush` → fragment written + file synced (durable) |
| Isolation mechanism | STM (`TransactionalLayerProducer`) | **SNAPSHOT** via MVCC by catalog version (versioned roots) |

### Why the two layers behave differently

The inversion is not arbitrary — it follows from *what* each layer stores and *where* it can afford to
keep it:

- **The `OffsetIndex` writes its payloads straight to disk because they are too large to keep in
  memory.** What it stores is the bulk data — typically the serialized **bodies of entities**, of which
  there are many — so holding all of it on the heap is not an option. The record bytes are appended to
  the file the moment they are written.
- **The `EntityIndex` family keeps its changes in memory because evitaDB keeps every index in memory by
  design.** An index is comparatively small and is consulted on the hot query path, so its transactional
  changes live in the heap-resident diff overlay and are merged into the in-memory snapshot **once the
  transaction commits** — or thrown away (and GC-reclaimed) on rollback. Nothing about an index change
  needs to touch disk before commit.
- **`flush` is what records the `OffsetIndex` layout.** The non-flushed bytes are physically in the file,
  but *where* each block sits — the `RecordKey → FileLocation` map for that version — becomes durably
  recoverable only after the flush writes the trailing index fragment. Until then, the in-flight
  (non-flushed) records are resolvable **only by the reader/writer holding the latest version**; if
  evitaDB were suddenly restarted before the flush, those blocks could not be located and are treated as
  dead data. After the flush, a restart can reconstruct the live key set and locate every previously
  written block.

This inversion is deliberate: appending to a file is fast on every OS and disk, so the storage engine
trades in-place safety for append speed and resolves correctness afterwards by **version** and by
periodic **vacuuming** of dead data — rather than by holding everything in a discardable in-memory diff.

---

## Append-only file model

No bytes in an `OffsetIndex` file are ever overwritten. The file is a chain of **fragments**: each
fragment holds the inserts/deletes accumulated up to one flush and points back to its predecessor; the
oldest fragment holds the initial load. A single `FileLocation` (kept *outside* the index, in the
descriptor) points at the latest fragment, from which the whole chain — and thus the whole live key set
— can be reconstructed on load. Fragments are bounded by the write buffer
(`StorageOptions.outputBufferSize()`), so even the initial state may span several fragments.

The index itself holds only `RecordKey → FileLocation` mappings:

- **WRITE** — append the record to the end of the file; store the returned `FileLocation` against the
  key in the current root.
- **READ** — look up the `FileLocation` by key (fast, in-memory), then seek + read the bytes via a
  pooled `RandomAccessFile` (latency depends on the OS page cache).
- **DELETE** — drop the key from the current root; the removal is also recorded in the fragment so that
  a record inserted in an earlier fragment is ignored on reconstruction.

Because nothing is overwritten, obsolete ("dead") records accumulate and are reclaimed by a periodic
**vacuum/compaction** pass that rewrites the live set into a fresh file, keeping the OS page cache dense.

---

## MVCC via versioned roots

`OffsetIndex` must answer reads **as of a catalog version** so that lock-free readers at an older version
keep seeing the state they were started against while newer flushes proceed. The isolation level this
delivers is **SNAPSHOT**: a reader pinned to catalog version `cv` observes a single, frozen,
internally-consistent point-in-time view of the *entire* key set as of `cv` — never a partial flush and
never a newer one — for its whole lifetime. It is the textbook MVCC realisation of snapshot isolation:
one immutable version per commit, reads routed to the version the reader was started against. (The
write-conflict dimension of snapshot isolation does not arise here, because flush is **single-writer /
serialized** — versions are totally ordered, so two commits can never race to write the same key.)

It achieves this with an immutable registry published through a **single volatile reference**, `Roots`:

```
record Roots(
    long currentVersion,
    long[]                          versions,        // sorted ascending, never empty
    ChampMap<RecordKey,FileLocation>[] locationRoots, // one immutable map per version
    Map<Byte,Integer>[]             histograms,       // per-version record-type counts
    long[]                          timestamps        // per-version promotion epoch millis (telemetry only)
)
```

- The whole tuple is swapped with **one volatile write**, so a reader can never observe a freshly
  appended root paired with a stale `versions` array (no torn snapshot, no lock).
- Each version owns an **immutable, structurally-shared
  [`ChampMap`](../stm/champ-persistent-map.md)** of locations. Retaining many versions is cheap, because
  successive roots share the bulk of their nodes.
- A read at version `cv` resolves through `Roots.floorRoot(cv)` — the greatest retained version not
  exceeding `cv` — then a single `ChampMap.get(key)`. Historical reads are therefore **lock-free
  immutable lookups**, not diff reconstructions.
- `count(cv)` / `count(cv, type)` are per-version isolated via `floorRoot(cv).size()` and the
  per-version histogram (plus any in-flight delta).

Each version is resolved against its own retained root, so the per-version guarantees are exact: batched
multi-version flushes keep a distinct snapshot per committed version, and `contains` is consistent with
`get` at every version.

Promotion (in `flush`) takes the latest root, applies the non-flushed changes through a
`ChampMap.Builder`, and **appends a new root per committed version** to the registry (see
[how OffsetIndex uses CHAMP](../stm/champ-persistent-map.md#how-offsetindex-uses-it)).

---

## Non-flushed reads & durability

Between flushes the writer accumulates the in-flight `(key → location)` changes in a small sidecar
(`VolatileValues`). Reads at the in-flight catalog version see these immediately, layered over the latest
published root — which is why non-flushed data is *visible* even though it is not yet durable.

Durability is tracked separately by `lastSyncedPosition`. A record may be appended to the write buffer
but not yet fsynced to disk; reading its raw on-disk **binary** in that window raises
`RecordNotYetWrittenException` (a soft flush is triggered first where appropriate). A location map says
*what* maps where; it does not say *whether those bytes are already durable* — that is the watermark's
job. `flush(catalogVersion)` writes the index fragment and advances the synced position, at which point
the version's data is fully durable and `copySnapshotTo` / reload-from-descriptor will reproduce it.

---

## History retention & purge

Keeping every version forever would grow the registry without bound, so a catalog releases versions no
client references any more via `purge(catalogVersion)`. The release is **deferred to the next promotion**
(applied under the serialized writer, so no reader-side lock is needed): roots at or below the highest
released version are dropped, and their now-exclusive `ChampMap` nodes are reclaimed by the GC through
structural sharing. The registry always retains the floor entry needed to resolve the smallest version a
client may still reference, plus the current version.

The per-version `timestamps[]` are a **pure telemetry side-channel** (surfacing how far back a
point-in-time restore could currently reach, via `OffsetIndexHistoryKeptEvent`). They never participate
in version resolution — reads and counts are resolved **only** by catalog version.

---

## Source files

| File | Module | Purpose |
|---|---|---|
| `OffsetIndex` | evita_store_key_value | The append-only store, versioned-roots registry, read/write/flush/purge |
| `OffsetIndex.Roots` | evita_store_key_value | Immutable per-version registry published via one volatile |
| `OffsetIndex.VolatileValues` | evita_store_key_value | In-flight (non-flushed) changes + durability/purge watermarks + observers |
| `OffsetIndexSerializationService` | evita_store_key_value | Fragment (de)serialization and snapshot copy |
| [`ChampMap`](../stm/champ-persistent-map.md) | evita_common | Immutable persistent map backing each version's location index |
| `OffsetIndexStoragePartPersistenceService` | evita_store_server | Binds storage parts to an `OffsetIndex` (catalog & entity-collection variants) |
| `OffsetIndexHistoryKeptEvent` | evita_engine | JFR telemetry for retained-history reach |

---

*See also:*
[Overview](overview.md#overview) |
[CHAMP Persistent Map](../stm/champ-persistent-map.md) |
[STM Overview](../stm/overview.md) |
[Transactions (user deep-dive)](../../user/en/deep-dive/transactions.md)
