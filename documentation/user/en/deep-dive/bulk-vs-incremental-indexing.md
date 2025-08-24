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
2. No rollback is possible - if any error occurs, the client must handle recovery on its own.
3. All changes to indexes are kept in memory and written when the session closes; in case of a database crash, all changes are lost.

Once initial indexing is finished, the client is expected to finalize the warm-up phase by closing the session and executing the `MakeCatalogAlive` mutation, which transitions the catalog to the ALIVE phase (see next chapter). <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> provides the `goLiveAndClose` method for this purpose. You can also invoke this transition via the `makeCatalogAlive` method in <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass>.</LS>

## Incremental indexing (ALIVE phase)

Incremental indexing is the phase in which we continuously synchronize changes from the primary data store into evitaDB. Multiple clients (sessions) may be open concurrently, some reading and some writing. Each read-write session defines a transaction boundary, and changes can be committed or rolled back atomically (see [the chapter about transactions](transactions.md) for ACID details). Write performance is considerably lower than in the bulk indexing phase because there is a cost associated with maintaining transactional integrity, concurrency, and durability. Read performance is not affected and remains very high.

## Full reindex of the live catalog

There are situations when you need to reindex the entire catalog from the primary data store while still serving live traffic from up-to-date data. The recommended approach is to create a new temporary catalog and fill it with an initial set of data using bulk indexing. Once the new catalog is fully indexed, you can switch your application to the new catalog using the replace catalog operation. <LS to="j">There is a method named `replaceCatalog` in the <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaContract.java</SourceClass> interface for this purpose.</LS> Replacing the catalog is a very fast operation that does not require copying any data - it updates the catalog name in the schema and renames a few files on disk. Even though the operation is quick, sessions using the old catalog will be closed during the process, and attempts to open new sessions will wait until the operation finishes. The switch is not entirely without impact, but the impact is very short-lived. The old catalog is deleted during the process; if you want to keep it, back it up before executing the replace operation.