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
2. There are no transactions - a group of writes cannot be committed or discarded as a unit. A *single* entity write is still atomic on its own, so an error part-way through one leaves nothing behind (see [Atomicity of individual writes](#atomicity-of-individual-writes)).
3. All changes to indexes are kept in memory and written when the session closes; in case of a database crash, all changes are lost.

<Note type="info">

How much data you write between session closes is a deliberate trade-off. Closing the session is the only moment when index changes reach the disk, so frequent closes act as checkpoints — the work completed so far is durable, and a crash costs you at most the block still in progress. You pay for that in throughput: every close has to collect and persist the modified parts of each index the block touched, and the more data the catalog already holds, the more that costs — so the price is paid repeatedly and rises as the import progresses. Writing the whole dataset within a single session avoids almost all of that cost and gives the fastest possible import, but it keeps the entire result in flight: nothing is durable until the end, the memory held by the pending index changes grows for the whole duration, and anything that loses the session — a crash, an out-of-memory condition — puts you back at the beginning. A single rejected entity write is not such a failure: it is reverted on its own and the import simply continues (see [Atomicity of individual writes](#atomicity-of-individual-writes)). Prefer one large block for imports short enough to simply repeat, and periodic closes for imports long enough that losing all the work would hurt.

</Note>

Once initial indexing is finished, the client is expected to finalize the warm-up phase by closing the session and executing the `MakeCatalogAlive` mutation, which transitions the catalog to the ALIVE phase (see next chapter). <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> provides the `goLiveAndClose` method for this purpose. You can also invoke this transition via the `makeCatalogAlive` method in <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS>

## Incremental indexing (ALIVE phase)

Incremental indexing is the phase in which we continuously synchronize changes from the primary data store into evitaDB. Multiple clients (sessions) may be open concurrently, some reading and some writing. Each read-write session defines a transaction boundary, and changes can be committed or rolled back atomically (see [the chapter about transactions](transactions.md) for ACID details). Write performance is considerably lower than in the bulk indexing phase because there is a cost associated with maintaining transactional integrity, concurrency, and durability. Read performance is not affected and remains very high.

## Atomicity of individual writes

A single write — an [`upsertEntity`](../use/api/write-data.md#upsert) or [`deleteEntity`](../use/api/write-data.md#removal) call together with all the index changes it implies (attributes, references, facets, prices, hierarchy placement, reflected references) — is treated as one unit of work, and that unit is atomic in **both** phases. What differs between them is only what surrounds it: in the ALIVE phase the write also sits inside a transaction, while in the WARM-UP phase it does not.

### ALIVE phase — every write is atomic

In the ALIVE phase each entity upsert or removal is **atomic on its own**, in addition to the atomicity of the enclosing transaction. If applying a single entity mutation fails part-way through — for example because it violates a unique constraint or another consistency rule after some of its index entries have already been written — the engine surgically reverts exactly that entity's partial changes and leaves the surrounding transaction untouched. The failing call throws an exception, but every entity written before it in the same transaction remains valid, and the client may catch the exception and continue writing further entities and then commit. One failed entity therefore never corrupts the transaction nor leaks a half-applied index entry (such as an orphaned facet or a phantom price), and any value it tried to reserve (e.g. a unique attribute) becomes available again immediately. This per-entity revert is independent of the enclosing transaction's own outcome: committing publishes only the entities that succeeded, and rolling back discards everything as usual.

### WARM-UP phase — every write is atomic as well

Bulk indexing writes index changes in place to maximize throughput and maintains none of the transactional diff layers the ALIVE phase reverts from. It nevertheless offers the same per-write guarantee: as an entity is written, the engine records how to undo each change it makes, so an entity upsert or removal that fails part-way through is **reverted completely**. The index entries already written for that entity are removed, any unique value it reserved becomes available again, and its stored body goes back to what it was before the call.

The failing call throws an exception and the session stays usable. You may catch it, skip or retry the offending entity, and continue the import with the rest of the data — the catalog remains consistent, and the later transition to the ALIVE phase proceeds normally. Neither compensating on the client side nor rebuilding the catalog is needed because of a single rejected entity.

Recording the undo information costs on the order of 2 % of bulk indexing throughput, which is why the guarantee is unconditional rather than something you opt into.

One thing is deliberately not rewound: the primary key drawn for a failed entity is not returned to the pool. Primary key sequences guarantee uniqueness, not contiguity, so a reverted write leaves a harmless gap in the numbering — the same as in the ALIVE phase.

## Full reindex of the live catalog

There are situations when you need to reindex the entire catalog from the primary data store while still serving live traffic from up-to-date data. The recommended approach is to create a new temporary catalog and fill it with an initial set of data using bulk indexing. Once the new catalog is fully indexed, you can switch your application to the new catalog using the replace catalog operation. <LS to="j">There is a method named `replaceCatalog` in the <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> interface for this purpose.</LS> Replacing the catalog is a very fast operation that does not require copying any data - it updates the catalog name in the schema and renames a few files on disk. Even though the operation is quick, sessions using the old catalog will be closed during the process, and attempts to open new sessions will wait until the operation finishes. The switch is not entirely without impact, but the impact is very short-lived. The old catalog is deleted during the process; if you want to keep it, back it up before executing the replace operation.