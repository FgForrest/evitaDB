---
title: Bulk vs. incremental indexing
perex: |
    evitaDB is designed as a fast, transactional, read-optimized database that offloads work from the primary data store, which is usually some kind of relational database. It is therefore expected to operate in two distinct phases: initial indexing of a large dataset and then maintaining the index throughout its lifetime. These two phases have different requirements and, as such, receive special treatment.
date: '24.8.2028'
author: 'Ing. Jan Novotný'
---

## Bulk indexing (WARM-UP phase)

Bulk indexing is used for rapid indexing of large volumes of source data from an external data store. At this initial stage of the catalog's lifecycle, we don't require transaction support or concurrency. The only goal is to index as much data as possible in the shortest time possible. This phase has the following characteristics:

1. Only a single client (single session) can be open at a time.
2. No rollback is possible - if any error occurs (even part-way through a single entity write), the client must handle recovery on its own (see [Atomicity of individual writes](#atomicity-of-individual-writes)).
3. All changes to indexes are kept in memory and written when the session closes; in case of a database crash, all changes are lost.

<Note type="info">

How much data you write between session closes is a deliberate trade-off. Closing the session is the only moment when index changes reach the disk, so frequent closes act as checkpoints — the work completed so far is durable, and a crash costs you at most the block still in progress. You pay for that in throughput: every close has to collect and persist the modified parts of each index the block touched, and the more data the catalog already holds, the more that costs — so the price is paid repeatedly and rises as the import progresses. Writing the whole dataset within a single session avoids almost all of that cost and gives the fastest possible import, but it keeps the entire result in flight: nothing is durable until the end, the memory held by the pending index changes grows for the whole duration, and any failure — including a single half-applied write, for which this phase offers no rollback — puts you back at the beginning (see [Atomicity of individual writes](#atomicity-of-individual-writes)). Prefer one large block for imports short enough to simply repeat, and periodic closes for imports long enough that losing all the work would hurt.

</Note>

Once initial indexing is finished, the client is expected to finalize the warm-up phase by closing the session and executing the `MakeCatalogAlive` mutation, which transitions the catalog to the ALIVE phase (see next chapter). <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> provides the `goLiveAndClose` method for this purpose. You can also invoke this transition via the `makeCatalogAlive` method in <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS>

## Incremental indexing (ALIVE phase)

Incremental indexing is the phase in which we continuously synchronize changes from the primary data store into evitaDB. Multiple clients (sessions) may be open concurrently, some reading and some writing. Each read-write session defines a transaction boundary, and changes can be committed or rolled back atomically (see [the chapter about transactions](transactions.md) for ACID details). Write performance is considerably lower than in the bulk indexing phase because there is a cost associated with maintaining transactional integrity, concurrency, and durability. Read performance is not affected and remains very high.

## Atomicity of individual writes

The granularity at which a write is atomic differs between the two phases. A single write — an [`upsertEntity`](../use/api/write-data.md#upsert) or [`deleteEntity`](../use/api/write-data.md#removal) call together with all the index changes it implies (attributes, references, facets, prices, hierarchy placement, reflected references) — is treated as one unit of work.

### ALIVE phase — every write is atomic

In the ALIVE phase each entity upsert or removal is **atomic on its own**, in addition to the atomicity of the enclosing transaction. If applying a single entity mutation fails part-way through — for example because it violates a unique constraint or another consistency rule after some of its index entries have already been written — the engine surgically reverts exactly that entity's partial changes and leaves the surrounding transaction untouched. The failing call throws an exception, but every entity written before it in the same transaction remains valid, and the client may catch the exception and continue writing further entities and then commit. One failed entity therefore never corrupts the transaction nor leaks a half-applied index entry (such as an orphaned facet or a phantom price), and any value it tried to reserve (e.g. a unique attribute) becomes available again immediately. This per-entity revert is independent of the enclosing transaction's own outcome: committing publishes only the entities that succeeded, and rolling back discards everything as usual.

### WARM-UP phase — no per-write rollback

In the WARM-UP (bulk indexing) phase there is **no per-write rollback**. Bulk indexing deliberately writes index changes in place to maximize throughput and does not maintain the transactional diff layers that per-entity revert relies on. If an entity upsert or removal fails part-way through, the changes already applied for that entity remain in the in-memory index and the catalog is left in an inconsistent state for that entity.

Because the engine cannot undo a partial write in this phase, **recovery is the client's responsibility**. There are two options:

1. **Compensate on the client side** — detect the failure and re-apply the correct, complete state for the affected entity (or remove it) so the index is returned to a consistent state before continuing. This is only safe if you can reconstruct exactly what was partially written.
2. **Discard and rebuild** (recommended) — abandon the half-built catalog and re-run the bulk import from scratch. Because warm-up is designed for fast initial loading, a full rebuild is usually inexpensive. If you need to rebuild while already serving live traffic, build a fresh catalog in the background and swap it in atomically — see [Full reindex of the live catalog](#full-reindex-of-the-live-catalog) below.

If you require per-entity atomicity during the initial load, transition the catalog to the ALIVE phase first and load the data through transactions instead, accepting the lower write throughput in exchange for the guarantee.

## Full reindex of the live catalog

There are situations when you need to reindex the entire catalog from the primary data store while still serving live traffic from up-to-date data. The recommended approach is to create a new temporary catalog and fill it with an initial set of data using bulk indexing. Once the new catalog is fully indexed, you can switch your application to the new catalog using the replace catalog operation. <LS to="j">There is a method named `replaceCatalog` in the <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> interface for this purpose.</LS> Replacing the catalog is a very fast operation that does not require copying any data - it updates the catalog name in the schema and renames a few files on disk. Even though the operation is quick, sessions using the old catalog will be closed during the process, and attempts to open new sessions will wait until the operation finishes. The switch is not entirely without impact, but the impact is very short-lived. The old catalog is deleted during the process; if you want to keep it, back it up before executing the replace operation.