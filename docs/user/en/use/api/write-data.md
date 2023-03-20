---
title: Write data
perex: |
    This article contains the main principles for authoring data in evitaDB, the description of the data API regarding 
    entity upsert and deletion and related recommendations.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
---

## Indexing modes

evitaDB assumes that it will not be the primary data store for your data. Because evitaDB is a relatively new database 
implementation, it's wise to store your primary data in a mature, time-tested and proven technology such as a relational 
database. evitaDB brings you the necessary low-latency and e-commerce-optimized feature as a secondary fast-read index 
where you mirror/transform the data from your primary data store. We would like to become your primary data store one 
day, but let's be honest - we're not there yet.

This reasoning led us to design two different types of [entity data](../data-model.md) ingestion and corresponding
catalog states:

- [bulk indexing](#bulk-indexing), state: `WARMUP`
- [incremental indexing](#incremental-indexing), state `ALIVE`

### Bulk indexing

Bulk indexing is used to quickly index large amounts of source data. It's used for initial catalog creation from 
external (primary) data stores. It doesn't need to support transactions and allows only a single session (single thread) 
to be opened from the client side. The catalog is in a so-called `WARMUP` state 
(<SourceClass>evita_api/src/main/java/io/evitadb/api/CatalogState.java</SourceClass>). The client can both write and 
query the written data, but no other client can open another session because the consistency of the data could not be 
guaranteed for them. The goal here is to index hundreds or thousands of entities per second.

If the database crashes during this initial bulk indexing, the state and consistency of the data must be considered 
corrupted, and the entire catalog should be dumped and rebuilt from scratch. Since there is no client other than the 
one writing the data, we can afford to do this.

Any newly created catalog starts in `WARMUP` state and must be manually switched to *transactional* mode by executing:

<SourceCodeTabs>
[Termination of warm-up mode](docs/user/en/use/api/example/finalization-of-warmup-mode.java)
</SourceCodeTabs>

The `goLiveAndClose` method sets the catalog to `ALIVE` (transactional) state and closes the current session. From this 
moment on, multiple clients can open read-only or read-write sessions in parallel to this particular catalog.

### Incremental indexing

The incremental indexing mode is used to keep the index up to date with the primary data store during its lifetime.
We expect some form of [change data capture](https://en.wikipedia.org/wiki/Change_data_capture) process to be built into 
the primary data store. One of the more interesting recent developments in this area is 
[the Debezium project](https://debezium.io/), which allows changes from primary data stores to be streamed to secondary 
indexes fairly easily.

There might be multiple clients reading & writing data to the same catalog when it is in `ALIVE` state. Each catalog
update is wrapped into a *transaction* that meets 
[the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation). More details about transaction 
handling is in [separate chapter](../../deep-dive/transactions.md).

## Model characteristics

Our model has a few features that you should keep in mind to use to your advantage:

### Immutability

All model classes are **designed to be immutable**. The reason for this is simplicity, implicitly correct behavior in
concurrent access (in other words, entities can be cached without fear of race conditions), and easy identity checking
(where only the primary key and version are needed to claim that two data objects of the same type are identical).

All model classes are described by interfaces, and there should be no reason to use or instantiate direct classes. 
Interfaces follow this structure:

<dl>
    <dt>ModelNameContract</dt>
    <dd>contains all read methods, represents the base contract for the model</dd>
    <dt>ModelNameEditor</dt>
    <dd>contains all modification methods</dd>
    <dt>ModelNameBuilder</dt>
    <dd>combines **Contract** and **Editor** interfaces, and it is actually used to create the instance</dd>
</dl>

When you create new entity using evitaDB API, you obtain a builder, and you can immediately start setting the data 
to the entity and then store the entity to the database:

<SourceCodeTabs>
[Creating new entity returns a builder](docs/user/en/use/api/example/create-new-entity-shortened.java)
</SourceCodeTabs>

When you read existing entity from the catalog, you obtain read-only 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>, which is 
basically a contract interface with a few methods allowing you to convert it to the builder instance that can be used
for updating the data:

<SourceCodeTabs>
[Retrieving existing entity returns a sealed entity](docs/user/en/use/api/example/update-existing-entity-shortened.java)
</SourceCodeTabs>

### Versioning

All model classes are versioned - in other words, when a model instance is modified, the version number of the new 
instance created from that modified state is incremented by one. Version information is available not only at 
the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass> level, 
but also at more granular levels (such as 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass>,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass>, or
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java</SourceClass>).

All model classes that support versioning implement the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Versioned.java</SourceClass> interface.

The version information serves two purposes:

1. **fast hashing & equality check:** only the primaryKey + version information is enough to tell if two instances 
   are the same, and we can say this with enough confidence even in situation, when only 
   [a part of the entity](query-data.md#lazy-loading) was actually loaded from persistent storage
2. **optimistic locking:** if there is a concurrent update of the same entity, we could automatically resolve the 
   conflict, provided that the changes themselves do not overlap.

## Session & transaction

The communication with the evitaDB instance always takes place via the
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface.
Session is a single-threaded communication channel identified by a unique 
[random UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier).

In the web environment it's a good idea to have one session per request, in batch processing it's recommended to keep 
a single session for an entire batch.

<Note type="warning">
To conserve resources, the server automatically closes sessions after a period of inactivity. 
The interval is set by default to `60 seconds` but 
[it can be changed](https://evitadb.io/documentation/operate/configure#server-configuration) to different value.
The inactivity means that there is no activity recorded on the
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> interface. If you need
to artificially keep session alive you need to periodically call some method without side-effects on the session 
interface, such as:

<dl>
    <dt>`isActive`</dt>
    <dd>In case of embedded evitaDB usage.</dd>
    <dt>`getEntityCollectionSize`</dt>
    <dd>
    In case of remote use of evitaDB. In this case we really need to call some method that triggers the network 
    communication. Many methods in 
    <SourceClass>evita_external_api/evita_external_api_grpc/client/src/main/java/io/evitadb/driver/EvitaClientSession.java</SourceClass> 
    return only locally cached results to avoid expensive and unnecessary network calls.
    </dd>
</dl>
</Note>

<SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> is an envelope for a "unit 
of work" with evitaDB. A transaction exists within a session and is guaranteed to have 
[the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation) for reads. The changes in 
a transaction are always isolated from other transactions and become visible only after the transaction has been 
committed. If the transaction is marked as *rollback only*, all changes will be discarded on transaction closing and 
will never reach the shared database state. There can be at most one active transaction in a session, but there can 
be multiple successor transactions during the session's lifetime.

<Note type="warning">
Parallel transaction handling hasn't been finalized yet, and is scheduled to be finalized in 
[issue #16](https://github.com/FgForrest/evitaDB/issues/16). Until this issue is resolved, you must ensure that only 
a single client is writing to the catalog in parallel. Other clients may have been reading in parallel using read-only 
sessions, but the writer must be only one.
</Note>

### Read-only vs. Read-Write sessions

evitaDB recognizes two types of sessions:

<dl>
    <dt>read-only (default)</dt>
    <dd>Read-only sessions are opened by calling the `queryCatalog` method. No write operations are allowed in a 
    read-only session. This also allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling the `updateCatalog` method</dd>
</dl>

In the future, the read-only sessions can be distributed to multiple read nodes, while the read-write sessions must 
talk to the master node.

#### Unsafe session lifecycle

We recommend to open sessions using `queryCatalog` / `updateCatalog` methods that accept a lambda function to execute 
your business logic. This way evitaDB can safely handle the lifecycle management of *sessions* & *transactions*. 
This approach is not always acceptable - for example, if your application needs to be integrated into an existing 
framework that only provides a lifecycle callback methods, there is no way to "wrap" the entire business logic in 
the lambda function.

That's why there is an alternative - not so secure - approach to handling sessions and transactions:

<SourceCodeTabs>
[Manual session and transaction handling](docs/user/en/use/api/example/manual-transaction-management.java)
</SourceCodeTabs>

<Note type="warning">
If you use manual *session / transaction* handling, you must ensure that for every scope opening there is 
a corresponding closing (even if an exception occurs during your business logic call).
</Note>

Both <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> and 
<SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> implement Java 
`Autocloseable` interface, so you can use them this way:

<SourceCodeTabs>
[Get advantage of Autocloseable behaviour](docs/user/en/use/api/example/autocloseable-transaction-management.java)
</SourceCodeTabs>

This approach is safe, but has the same disadvantage as using `queryCatalog` / `updateCatalog` methods - you need to 
have all the business logic executable within the same block.

#### Dry-run session

For testing purposes, there is a special flag that can be used when opening a new session - a **dry run** flag:

<SourceCodeTabs>
[Opening dry-run session](docs/user/en/use/api/example/autocloseable-transaction-management.java)
</SourceCodeTabs>

In this session, all transactions will automatically have a *rollback* flag set when they are opened, without the need
to set the rollback flag manually. This fact greatly simplifies
[transaction rollback on test teardown pattern](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html) when 
implementing your tests, or can be useful if you want to ensure that the changes are not committed in a particular 
session, and you don't have easy access to the places where the transaction is opened.

### Upsert

It's expected that most of the entity instances will be created by the evitaDB service classes - such as
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>
Anyway, there is also the [possibility of creating them directly](#creating-entities-in-detached-mode).

Usually the entity creation will look like this:

<SourceCodeTabs>
[Creating new entity example](docs/user/en/use/api/example/create-new-entity.java)
</SourceCodeTabs>

This way, the created entity can be immediately checked against the schema. This form of code is a condensed version,
and it may be split into several parts, which will reveal the "builder" used in the process.

When you need to alter existing entity, you first fetch it from the server, open for writing (which converts it to
the builder wrapper), modify it, and finally collect the changes and send them to the server. 

<SourceCodeTabs>
[Updating existing entity example](docs/user/en/use/api/example/update-existing-entity.java)
</SourceCodeTabs>

<Note type="info">
The `upsertVia` method is a shortcut for calling `session.upsertEntity(builder.buildChangeSet())`. If you look at the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/BuilderContract.java</SourceClass> you'll 
see, that you can call on it either:

<dl>
    <dt>`buildChangeSet`</dt>
    <dd>Creates a stream of *mutations* that represent the changes you've made to the immutable object.</dd>
    <dt>`toInstance`</dt>
    <dd>Creates a new version of the immutable entity object with all changes applied. This allows you to create a 
    new instance of the object locally without sending the changes to the server. When you fetch the same instance 
    from the server again, you'll see that none of the changes have been applied to the database entity.</dd>
</dl> 
</Note>

<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> or
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/BuilderContract.java</SourceClass> can be
passed to <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> `upsert` method,
which returns 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
containing only entity type and (possibly assigned) primary key information. You can also use the `upsertAndFetchEntity`
method, which inserts or creates the entity and returns its body in the form and size you specify in your `require` 
argument.

#### Creating entities in detached mode

Entities can be created even if no EvitaDB instance is available:

<SourceCodeTabs>
[Detached instantiation example](docs/user/en/use/api/example/detached-instantiation.java)
</SourceCodeTabs>

Although you will probably only take advantage of this approach when writing test cases, it still allows you to create 
a stream of mutations that can be sent to and processed by the evitaDB server once you manage to get its instance.

There is an analogous builder that takes an existing entity and tracks changes made to it.

<SourceCodeTabs>
[Detached existing entity example](docs/user/en/use/api/example/detached-existing-entity-instantiation.java)
</SourceCodeTabs>

### Removal

The easiest way how to remove an entity is by its *primary key*. However, if you need to remove multiple entities at
once you need to define a query that will match all the entities to remove:

<SourceCodeTabs>
[Removing all entities which name starts with `A`](docs/user/en/use/api/example/delete-entities-by-query.java)
</SourceCodeTabs>

The `deleteEntities` method returns the count of removed entities. If you want to return bodies of deleted entities,
you can use alternative method `deleteEntitiesAndReturnBodies`.

<Note type="warning">
evitaDB may not remove all entities matched by the filter part of the query. The removal of entities is subject to the 
logic of the `require` conditions [`page` or `strip`](../../query/requirements/paging.md). Even if you omit the 
`require` part completely, implicit paging (`page(1, 20)`) is used. If the number of removed entities is equal to the 
size of the defined paging, you should repeat the removal command.

Massive entity removal is better to execute in multiple transactional rounds and not in one big transaction. This is a
good practice at least, because large and long-lasting transactions introduce the occurrence of conflicts and lead to 
rollback of other transactions.
</Note>

If you are removing a hierarchical entity, and you need to remove not only the entity itself, but its entire subtree,
you can take advantage of `deleteEntityAndItsHierarchy` method. By default, the method returns the number of entities 
removed, but alternatively it can return the body of the removed root entity with the size and form you specify in 
its `require` argument. If you remove only the root node without removing its children, the children will become 
[orphans](../schema.md#orphan-hierarchy-nodes), and you will need to reattach them to another existing parent. 

<Note type="question">

<NoteTitle toggles="true">

##### How does evitaDB handle the removals internally?
</NoteTitle>

No data is actually removed once it is created and stored. If you remove the reference/attribute/whatever, it remains 
in the entity and is just marked as `dropped'. See the 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Droppable.java</SourceClass> interface 
implementations.

There are a few reasons for this decision:

1. it's good to have the last known version of the data around when things go wrong, so we can still recover to the 
   previous state.
2. it allows us to track the changes in the entity through its lifecycle for debugging purposes
3. it is consistent with our *append-only* storage approach where we need to write 
   [tombstones](https://en.wikipedia.org/wiki/Tombstone_(data_store)) in case of entity or other object removals
</Note>