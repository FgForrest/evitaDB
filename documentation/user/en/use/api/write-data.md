---
title: Write data
perex: |
    This article contains the main principles for authoring data in evitaDB, the description of the data API regarding 
    entity upsert and deletion and related recommendations.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

<LanguageSpecific to="java,graphql,rest">

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

</LanguageSpecific>

<LanguageSpecific to="java">

Any newly created catalog starts in `WARMUP` state and must be manually switched to *transactional* mode by executing:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Termination of warm-up mode](/documentation/user/en/use/api/example/finalization-of-warmup-mode.java)
</SourceCodeTabs>

The `goLiveAndClose` method sets the catalog to `ALIVE` (transactional) state and closes the current session. From this 
moment on, multiple clients can open read-only or read-write sessions in parallel to this particular catalog.

</LanguageSpecific>
<LanguageSpecific to="graphql">

Any newly created catalog starts in `WARMUP` state and must be manually switched to *transactional* mode using the 
[system API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) by executing:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Termination of warm-up mode](/documentation/user/en/use/api/example/finalization-of-warmup-mode.graphql)
</SourceCodeTabs>

The `switchCatalogToAliveState` mutation sets the catalog to `ALIVE` (transactional) state. From this
moment on, multiple clients can send queries or mutations requests in parallel to this particular catalog.

</LanguageSpecific>
<LanguageSpecific to="rest">

Any newly created catalog starts in `WARMUP` state and must be manually switched to *transactional* mode using the
[system API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) by executing:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Termination of warm-up mode](/documentation/user/en/use/api/example/finalization-of-warmup-mode.graphql)
</SourceCodeTabs>

The `/catalogs/{catalog-name}` endpoint with `PATCH` method sets the catalog to `ALIVE` (transactional) state. From this
moment on, multiple clients can send fetching or mutating requests in parallel to this particular catalog.

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

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

</LanguageSpecific>

<LanguageSpecific to="java">

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

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/use/api/example/open-session-manually.java">

[Creating new entity returns a builder](/documentation/user/en/use/api/example/create-new-entity-shortened.java)
</SourceCodeTabs>

When you read existing entity from the catalog, you obtain read-only 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>, which is 
basically a contract interface with a few methods allowing you to convert it to the builder instance that can be used
for updating the data:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java,/documentation/user/en/use/api/example/open-session-manually.java">

[Retrieving existing entity returns a sealed entity](/documentation/user/en/use/api/example/update-existing-entity-shortened.java)
</SourceCodeTabs>

</LanguageSpecific>
<LanguageSpecific to="graphql">

In the GraphQL API, the immutability is implicit by design. You may be able to modify the returned entity objects in your client
application, but these changes cannot be propagated to the evitaDB server, so it is recommended to make your client model
immutable as well (see the Java API for inspiration). The only way to modify the data is to use the
[catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances) and manually send evitaDB mutations
with individual changes using one of the `updateCollectionName` GraphQL mutations specific to your selected 
[entity collection](/documentation/user/en/use/data-model.md#collection).

</LanguageSpecific>
<LanguageSpecific to="rest">

In the REST API, the immutability is implicit by design. You may be able to modify the returned entity objects in your client
application, but these changes cannot be propagated to the evitaDB server, so it is encouraged to make your client model
immutable as well (see the Java API for inspiration). The only way to modify the data is to use the
[catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances) and manually send evitaDB mutations
with individual changes using one of the REST endpoints for modifying data of your selected
[entity collection](/documentation/user/en/use/data-model.md#collection).

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

### Versioning

All model classes are versioned - in other words, when a model instance is modified, the version number of the new 
instance created from that modified state is incremented by one.

</LanguageSpecific>

<LanguageSpecific to="java">

Version information is available not only at 
the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass> level, 
but also at more granular levels (such as 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass>,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass>, or
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java</SourceClass>).

All model classes that support versioning implement the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Versioned.java</SourceClass> interface.

</LanguageSpecific>
<LanguageSpecific to="graphql,rest">

Version information is available at the entity level. 

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

The version information serves two purposes:

1. **fast hashing & equality check:** only the primaryKey + version information is enough to tell if two instances
   are the same, and we can say this with enough confidence even in situation, when only
   [a part of the entity](query-data.md#lazy-fetching-enrichment) was actually loaded from persistent storage
2. **optimistic locking:** if there is a concurrent update of the same entity, we could automatically resolve the
   conflict, provided that the changes themselves do not overlap.

</LanguageSpecific>

<LanguageSpecific to="java">

<Note type="info">
Since the entity is *immutable* and *versioned* the default implementation of the `hashCode` and `equals` takes these 
three components into account:

1. entity type
2. primary key
3. version

If you need a thorough comparison that compares all model data, you must use the `differsFrom` method defined in the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ContentComparator.java</SourceClass>
interface and implemented by the 
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass>.

</Note>

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

## Session & transaction

</LanguageSpecific>

<LanguageSpecific to="java">

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

</LanguageSpecific>
<LanguageSpecific to="graphql">

The communication with the evitaDB instance using the GraphQL API always uses some kind of session. In the case of the GraphQL API,
a session is a per-request communication channel that is used in the background.

A transaction is an envelope for a "unit of work" with evitaDB. 
In the GraphQL API, a transaction exists for the duration of a session, or more precisely a GraphQL API request, and it is 
guaranteed to have [the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation) for reads. The changes in
a transaction are always isolated from other transactions and become visible only after the transaction has been
committed, i.e. the request to the GraphQL API has been processed and was successful. If a GraphQL API request results in 
any kind of error, the transaction is automatically rolled back.

</LanguageSpecific>
<LanguageSpecific to="rest">

The communication with the evitaDB instance using the REST API always uses some kind of session. In the case of the REST API,
a session is a per-request communication channel that is used in the background.

A transaction is an envelope for a "unit of work" with evitaDB.
In the REST API, a transaction exists for the duration of a session, or more precisely a REST API request, and it is
guaranteed to have [the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation) for reads. The changes in
a transaction are always isolated from other transactions and become visible only after the transaction has been
committed, i.e. the request to the REST API has been processed and was successful. If a REST API request results in
any kind of error, the transaction is automatically rolled back.

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

<Note type="warning">
Parallel transaction handling hasn't been finalized yet, and is scheduled to be finalized in 
[issue #16](https://github.com/FgForrest/evitaDB/issues/16). Until this issue is resolved, you must ensure that only 
a single client is writing to the catalog in parallel. Other clients may have been reading in parallel using read-only 
sessions, but the writer must be only one.
</Note>

### Read-only vs. Read-Write sessions

evitaDB recognizes two types of sessions:

</LanguageSpecific>

<LanguageSpecific to="java">

<dl>
    <dt>read-only (default)</dt>
    <dd>Read-only sessions are opened by calling the `queryCatalog` method. No write operations are allowed in a 
    read-only session. This also allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling the `updateCatalog` method</dd>
</dl>

</LanguageSpecific>
<LanguageSpecific to="graphql">

<dl>
    <dt>read-only</dt>
    <dd>Read-only sessions are opened by calling GraphQL queries, i.e. `getCollectionName`, `listCollectionName`,
    `queryCollectionName` and so on. No write operations are allowed in a read-only session 
    which allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling GraphQL mutations, i.e. `upsertCollectionName`, `deleteCollectionName`
    and so on.</dd>
</dl>

</LanguageSpecific>
<LanguageSpecific to="rest">

<dl>
    <dt>read-only</dt>
    <dd>Read-only sessions are opened by calling endpoints then only return data, typically endpoints ending with `/get`,
    `/list`, `/query` and so on. No write operations are allowed in a read-only session
    which allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling endpoints modify any data.</dd>
</dl>

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

In the future, the read-only sessions can be distributed to multiple read nodes, while the read-write sessions must 
talk to the master node.

</LanguageSpecific>

<LanguageSpecific to="java">

#### Unsafe session lifecycle

We recommend to open sessions using `queryCatalog` / `updateCatalog` methods that accept a lambda function to execute 
your business logic. This way evitaDB can safely handle the lifecycle management of *sessions* & *transactions*. 
This approach is not always acceptable - for example, if your application needs to be integrated into an existing 
framework that only provides a lifecycle callback methods, there is no way to "wrap" the entire business logic in 
the lambda function.

That's why there is an alternative - not so secure - approach to handling sessions and transactions:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java">

[Manual session and transaction handling](/documentation/user/en/use/api/example/manual-transaction-management.java)
</SourceCodeTabs>

<Note type="warning">
If you use manual *session / transaction* handling, you must ensure that for every scope opening there is 
a corresponding closing (even if an exception occurs during your business logic call).
</Note>

Both <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> and 
<SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> implement Java 
`Autocloseable` interface, so you can use them this way:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java">

[Get advantage of Autocloseable behaviour](/documentation/user/en/use/api/example/autocloseable-transaction-management.java)
</SourceCodeTabs>

This approach is safe, but has the same disadvantage as using `queryCatalog` / `updateCatalog` methods - you need to 
have all the business logic executable within the same block.

#### Dry-run session

For testing purposes, there is a special flag that can be used when opening a new session - a **dry run** flag:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java">

[Opening dry-run session](/documentation/user/en/use/api/example/dry-run-session.java)
</SourceCodeTabs>

In this session, all transactions will automatically have a *rollback* flag set when they are opened, without the need
to set the rollback flag manually. This fact greatly simplifies
[transaction rollback on test teardown pattern](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html) when 
implementing your tests, or can be useful if you want to ensure that the changes are not committed in a particular 
session, and you don't have easy access to the places where the transaction is opened.

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

### Upsert

</LanguageSpecific>

<LanguageSpecific to="java">

It's expected that most of the entity instances will be created by the evitaDB service classes - such as
<SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass>
Anyway, there is also the [possibility of creating them directly](#creating-entities-in-detached-mode).

Usually the entity creation will look like this:

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Creating new entity example](/documentation/user/en/use/api/example/create-new-entity.java)
</SourceCodeTabs>

This way, the created entity can be immediately checked against the schema. This form of code is a condensed version,
and it may be split into several parts, which will reveal the "builder" used in the process.

When you need to alter existing entity, you first fetch it from the server, open for writing (which converts it to
the builder wrapper), modify it, and finally collect the changes and send them to the server. 

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Updating existing entity example](/documentation/user/en/use/api/example/update-existing-entity.java)
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

Entity instances can be created even if no EvitaDB instance is available:

<SourceCodeTabs>
[Detached instantiation example](/documentation/user/en/use/api/example/detached-instantiation.java)
</SourceCodeTabs>

Although you will probably only take advantage of this approach when writing test cases, it still allows you to create 
a stream of mutations that can be sent to and processed by the evitaDB server once you manage to get its instance.

There is an analogous builder that takes an existing entity and tracks changes made to it.

<SourceCodeTabs requires="/documentation/user/en/use/api/example/detached-existing-entity-preparation.java">

[Detached existing entity example](/documentation/user/en/use/api/example/detached-existing-entity-instantiation.java)
</SourceCodeTabs>

</LanguageSpecific>
<LanguageSpecific to="graphql">

In the GraphQL API, there is no way to send full entity object to the server to be stored. Instead, you send a collection 
of mutations that add, change, or remove individual data from an entity (new or existing one). Similarly to how the schema 
is defined in the GraphQL API.

<Note type="question">

<NoteTitle toggles="true">

##### Why do we use the mutation approach for entity definition?
</NoteTitle>

We know that this approach is not very user-friendly. However, the idea behind this approach is to provide a simple and versatile
way to programmatically build an entity with transactions in mind (in fact, this is how evitaDB works internally,
so the collection of mutations is passed directly to the engine on the server). It is expected that the developer
using the GraphQL API will create a library with e.g. entity builders that will generate the collection of mutations for
the entity definition (see Java API for inspiration).

</Note>

You can create a new entity or update an existing one using the [catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)
at the `https://your-server:5555/gql/test-catalog` URL. This API contains `upsertCollectionName` GraphQL mutations for each
[entity collection](/documentation/user/en/use/data-model.md#collection) that are customized to collections'
[schemas](/documentation/user/en/use/schema.md#entity). These mutations take a collection of evitaDB mutations which define
the changes to be applied to an entity. In one go, you can then retrieve the entity with the changes applied by defining
return data.

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Creating new entity example](/documentation/user/en/use/api/example/create-new-entity.graphql)
</SourceCodeTabs>

Because these GraphQL mutations are also for updating existing entities, evitaDB will automatically
either create a new entity with specified mutations (and possibly a primary key) or update an existing one if a primary key
of an existing entity is specified. You can further customize the behavior of the mutation by specifying the `entityExistence`
argument.

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Updating existing entity example](/documentation/user/en/use/api/example/update-existing-entity.graphql)
</SourceCodeTabs>

</LanguageSpecific>
<LanguageSpecific to="rest">

In the REST API, there is no way to send full entity object to the server to be stored. Instead, you send a collection
of mutations that add, change, or remove individual data from an entity (new or existing one). Similarly to how the schema
is defined in the REST API.

<Note type="question">

<NoteTitle toggles="true">

##### Why do we use the mutation approach for entity definition?
</NoteTitle>

We know that this approach is not very user-friendly. However, the idea behind this approach is to provide a simple and versatile
way to programmatically build an entity with transactions in mind (in fact, this is how evitaDB works internally,
so the collection of mutations is passed directly to the engine on the server). It is expected that the developer
using the REST API will create a library with e.g. entity builders that will generate the collection of mutations for
the entity definition (see Java API for inspiration).

</Note>

You can create a new entity or update an existing one using the [catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)
at a collection endpoint, for example `https://your-server:5555/test/test-catalog/product` with `PUT` HTTP method. 
There endpoints are customized to collections' [schemas](/documentation/user/en/use/schema.md#entity). These endpoints take a 
collection of evitaDB mutations which define the changes to be applied to an entity. In one go, you can then retrieve the 
entity with the changes applied by defining requirements.

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly>

[Creating new entity example](/documentation/user/en/use/api/example/create-new-entity.rest)
</SourceCodeTabs>

Because these endpoints are also for updating existing entities, evitaDB will automatically
either create a new entity with specified mutations (and possibly a primary key) or update an existing one if a primary key
of an existing entity is specified. You can further customize the behavior of the mutation by specifying the `entityExistence`
argument.

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Updating existing entity example](/documentation/user/en/use/api/example/update-existing-entity.rest)
</SourceCodeTabs>

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

### Removal

</LanguageSpecific>

<LanguageSpecific to="java,rest">

The easiest way how to remove an entity is by its *primary key*. However, if you need to remove multiple entities at
once you need to define a query that will match all the entities to remove:

</LanguageSpecific>
<LanguageSpecific to="graphql">

To remove one or multiple entities, you need to define a query that will match all the entities to remove:

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

<SourceCodeTabs requires="ignoreTest,/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly>

[Removing all entities which name starts with `A`](/documentation/user/en/use/api/example/delete-entities-by-query.java)
</SourceCodeTabs>

</LanguageSpecific>

<LanguageSpecific to="java">

The `deleteEntities` method returns the count of removed entities. If you want to return bodies of deleted entities,
you can use alternative method `deleteEntitiesAndReturnBodies`.

</LanguageSpecific>
<LanguageSpecific to="graphql">

The delete mutation can return entity bodies, so you can define the return structure of data as you need as if you were fetching
entities in usual way.

<Note type="warning">

evitaDB may not remove all entities matched by the filter part of the query. The removal of entities is subject to the 
logic of the pagination arguments `offset` and `limit`. Even if you omit the 
these arguments completely, implicit pagination (`offset: 1, limit: 20`) will be used. If the number of entities removed is equal to the 
size of the defined paging, you should repeat the removal command.

Massive entity removal is better to execute in multiple transactional rounds rather than in one big transaction, i.e. multiple 
GraphQL requests. This is at least a
good practice, because large and long-running transactions increase probability of conflicts that lead to
rollbacks of other transactions.

</Note>

</LanguageSpecific>
<LanguageSpecific to="rest">

Both deletion endpoints can return entity bodies, so you can define the return requirements as you need as if you were fetching
entities in usual way.

</LanguageSpecific>

<LanguageSpecific to="java,rest">

<Note type="warning">
evitaDB may not remove all entities matched by the filter part of the query. The removal of entities is subject to the 
logic of the `require` conditions [`page` or `strip`](../../query/requirements/paging.md). Even if you omit the 
`require` part completely, implicit pagination (`page(1, 20)`) will be used. If the number of entities removed is equal to the 
size of the defined paging, you should repeat the removal command.

Massive entity removal is better to execute in multiple transactional rounds rather than in one big transaction. This is
at least a good practice, because large and long-running transactions increase the probability of conflicts that lead to
rollbacks of other transactions.

</Note>

</LanguageSpecific>

<LanguageSpecific to="java">

If you are removing a hierarchical entity, and you need to remove not only the entity itself, but its entire subtree,
you can take advantage of `deleteEntityAndItsHierarchy` method. By default, the method returns the number of entities
removed, but alternatively it can return the body of the removed root entity with the size and form you specify in
its `require` argument. If you remove only the root node without removing its children, the children will become
[orphans](../schema.md#orphan-hierarchy-nodes), and you will need to reattach them to another existing parent.

</LanguageSpecific>

<LanguageSpecific to="java,graphql,rest">

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

</LanguageSpecific>

<LanguageSpecific to="evitaql">

Unfortunately, it is currently not possible to write data using EvitaQL. This extension is also not planned to be
implemented in the near future, because we believe that sufficient options (Java, GraphQL, REST API) are available.

</LanguageSpecific>