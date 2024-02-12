---
title: Write data
perex: |
    This article contains the main principles for authoring data in evitaDB, the description of the data API regarding
    entity upsert and deletion and related recommendations.
date: '17.1.2023'
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'java'
---

<LS to="e">

Unfortunately, it is currently not possible to write data using EvitaQL. This extension is also not planned to be
implemented in the near future, because we believe that sufficient options (Java, GraphQL, REST API, gRPC and C#) are available.

</LS>

<LS to="j,g,r,c">

## Indexing modes

evitaDB assumes that it will not be the primary data store for your data. Because evitaDB is a relatively new database
implementation, it's wise to store your primary data in a mature, time-tested and proven technology such as a relational
database. evitaDB brings you the necessary low-latency and e-commerce-optimized feature as a secondary fast-read index
where you mirror/transform the data from your primary data store. We would like to become your primary data store one
day, but let's be honest - we're not there yet.

This reasoning led us to design two different types of [entity data](../data-model.md) ingestion and corresponding
catalog states:

- [bulk indexing](#bulk-indexing), state: <LS to="j,g,r">`WARMUP`</LS><LS to="c">`Warmup`</LS>
- [incremental indexing](#incremental-indexing), state: <LS to="j,g,r">`ALIVE`</LS><LS to="c">`Alive`</LS>

### Bulk indexing

Bulk indexing is used to quickly index large amounts of source data. It's used for initial catalog creation from
external (primary) data stores. It doesn't need to support transactions and allows only a single session (single thread)
to be opened from the client side. The catalog is in a so-called <LS to="j,g,r">`WARMUP`</LS><LS to="c">`Warmup`</LS> state
(<LS to="j,g,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/CatalogState.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Session/CatalogState.cs</SourceClass></LS>).
The client can both write and
query the written data, but no other client can open another session because the consistency of the data could not be
guaranteed for them. The goal here is to index hundreds or thousands of entities per second.

If the database crashes during this initial bulk indexing, the state and consistency of the data must be considered
corrupted, and the entire catalog should be dumped and rebuilt from scratch. Since there is no client other than the
one writing the data, we can afford to do this.

</LS>

<LS to="j,c">

Any newly created catalog starts in <LS to="j">`WARMUP`</LS><LS to="c">`Warmup`</LS>
state and must be manually switched to *transactional* mode by executing:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Termination of warm-up mode](/documentation/user/en/use/api/example/finalization-of-warmup-mode.java)
</SourceCodeTabs>

The <LS to="j">`goLiveAndClose`</LS><LS to="c">`GoLiveAndClose`</LS>
method sets the catalog to <LS to="j">`ALIVE`</LS><LS to="c">`Alive`</LS>
(transactional) state and closes the current session. From this moment on, multiple clients can open read-only or read-write
sessions in parallel to this particular catalog.

</LS>
<LS to="g,r">

Any newly created catalog starts in `WARMUP` state and must be manually switched to *transactional* mode using the
<LS to="g">[system API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)</LS>
<LS to="r">[system API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)</LS>
by executing:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Termination of warm-up mode](/documentation/user/en/use/api/example/finalization-of-warmup-mode.graphql)
</SourceCodeTabs>

The <LS to="g">`switchCatalogToAliveState` mutation</LS><LS to="r">`/catalogs/{catalog-name}` endpoint with `PATCH` method</LS>
sets the catalog to `ALIVE` (transactional) state. From this moment on, multiple clients can send queries or mutations
requests in parallel to this particular catalog.

</LS>

<LS to="j,g,r,c">

### Incremental indexing

The incremental indexing mode is used to keep the index up to date with the primary data store during its lifetime.
We expect some form of [change data capture](https://en.wikipedia.org/wiki/Change_data_capture) process to be built into
the primary data store. One of the more interesting recent developments in this area is
[the Debezium project](https://debezium.io/), which allows changes from primary data stores to be streamed to secondary
indexes fairly easily.

There might be multiple clients reading & writing data to the same catalog when it is in <LS to="j">`ALIVE`</LS>
<LS to="c">`Alive`</LS> state. Each catalog
update is wrapped into a *transaction* that meets
[the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation). More details about transaction
handling is in [separate chapter](../../deep-dive/transactions.md).

## Model characteristics

Our model has a few features that you should keep in mind to use to your advantage:

### Immutability

All model classes are **designed to be immutable**. The reason for this is simplicity, implicitly correct behavior in
concurrent access (in other words, entities can be cached without fear of race conditions), and easy identity checking
(where only the primary key and version are needed to claim that two data objects of the same type are identical).

</LS>

<LS to="j,c">

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

<SourceCodeTabs requires="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/use/api/example/open-session-manually.java" langSpecificTabOnly local>

[Creating new entity returns a builder](/documentation/user/en/use/api/example/create-new-entity-shortened.java)
</SourceCodeTabs>

When you read existing entity from the catalog, you obtain read-only
<LS to="java>"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass></LS><LS to="csharp>"><SourceClass>EvitaDB.Client/Models/Data/ISealedEntity.cs</SourceClass></LS>, which is
basically a contract interface with a few methods allowing you to convert it to the builder instance that can be used
for updating the data:

<LS to="j,c">
<SourceCodeTabs requires="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java,/documentation/user/en/use/api/example/open-session-manually.java" langSpecificTabOnly local>

[Retrieving existing entity returns a sealed entity](/documentation/user/en/use/api/example/update-existing-entity-shortened.java)
</SourceCodeTabs>
</LS>

</LS>

<LS to="g,r">

In the <LS to="g">GraphQL</LS><LS to="r">REST</LS> API,
the immutability is implicit by design. You may be able to modify the returned entity objects in your client
application, but these changes cannot be propagated to the evitaDB server, so it is recommended to make your client model
immutable as well (see the Java API for inspiration). The only way to modify the data is to use the
<LS to="g">[catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)</LS>
<LS to="r">[catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)</LS>
and manually send evitaDB mutations with individual changes using one of the
<LS to="g">`updateCollectionName` GraphQL mutations specific to</LS>
<LS to="r">REST endpoints for modifying data of</LS> your selected [entity collection](/documentation/user/en/use/data-model.md#collection).

</LS>

<LS to="j,g,r,c">

### Versioning

All model classes are versioned - in other words, when a model instance is modified, the version number of the new
instance created from that modified state is incremented by one.

</LS>

<LS to="j,c">

Version information is available not only at the <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IEntity.cs</SourceClass></LS> level,
but also at more granular levels (such as
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AttributesContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IAttributes.cs</SourceClass></LS>,
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IReference.cs</SourceClass></LS>, or
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/AssociatedDataContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IAssociatedData.cs</SourceClass></LS>).
All model classes that support versioning implement the <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Versioned.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IVersioned.cs</SourceClass></LS> interface.

</LS>

<LS to="g,r">

Version information is available at the entity level.

</LS>

<LS to="j,g,r,c">

The version information serves two purposes:

1. **fast hashing & equality check:** only the primaryKey + version information is enough to tell if two instances
   are the same, and we can say this with enough confidence even in situation, when only
   [a part of the entity](query-data.md#lazy-fetching-enrichment) was actually loaded from persistent storage
2. **optimistic locking:** if there is a concurrent update of the same entity, we could automatically resolve the
   conflict, provided that the changes themselves do not overlap.

</LS>

<LS to="j,c">

<Note type="info">
Since the entity is *immutable* and *versioned* the default implementation of the `hashCode` and `equals` takes these
three components into account:

1. entity type
2. primary key
3. version

If you need a thorough comparison that compares all model data, you must use the <LS to="j">`differsFrom`</LS><LS to="c">`DiffersFrom`</LS> method defined in the
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ContentComparator.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IContentComparator.cs</SourceClass></LS>
interface and implemented by the
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityContract.java</SourceClass></LS><LS to="c"><SourceClass><SourceClass>EvitaDB.Client/Models/Data/IEntity.cs</SourceClass></SourceClass></LS>.

</Note>

</LS>

<LS to="j,g,r,c">

## Session & transaction

</LS>

<LS to="j,c">

The communication with the evitaDB instance always takes place via the
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS> interface.
Session is a single-threaded communication channel identified by a unique
[random UUID](https://en.wikipedia.org/wiki/Universally_unique_identifier).

In the web environment it's a good idea to have one session per request, in batch processing it's recommended to keep
a single session for an entire batch.

<Note type="warning">
To conserve resources, the server automatically closes sessions after a period of inactivity.
The interval is set by default to `60 seconds` but
[it can be changed](https://evitadb.io/documentation/operate/configure#server-configuration) to different value.
The inactivity means that there is no activity recorded on the
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS> interface. If you need
to artificially keep session alive you need to periodically call some method without side-effects on the session
interface, such as:

<dl>
    <dt>`isActive`</dt>
    <dd>In case of embedded evitaDB usage.</dd>
    <dt>`getEntityCollectionSize`</dt>
    <dd>
    In case of remote use of evitaDB. In this case we really need to call some method that triggers the network
    communication. Many methods in
	<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS>
    return only locally cached results to avoid expensive and unnecessary network calls.
    </dd>
</dl>
</Note>

<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass></LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientTransaction.cs</SourceClass></LS> is an envelope for a "unit
of work" with evitaDB. A transaction exists within a session and is guaranteed to have
[the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation) for reads. The changes in
a transaction are always isolated from other transactions and become visible only after the transaction has been
committed. If the transaction is marked as *rollback only*, all changes will be discarded on transaction closing and
will never reach the shared database state. There can be at most one active transaction in a session, but there can
be multiple successor transactions during the session's lifetime.

</LS>

<LS to="g,r">

The communication with the evitaDB instance using the <LS to="g">GraphQL</LS><LS to="r">REST</LS>
API always uses some kind of session. In the case of the <LS to="g">GraphQL</LS><LS to="r">REST</LS> API,
a session is a per-request communication channel that is used in the background.

A transaction is an envelope for a "unit of work" with evitaDB.
In the <LS to="g">GraphQL</LS><LS to="r">REST</LS> API,
a transaction exists for the duration of a session, or more precisely a <LS to="g">GraphQL</LS><LS to="r">REST</LS>
API request, and it is
guaranteed to have [the snapshot isolation level](https://en.wikipedia.org/wiki/Snapshot_isolation) for reads. The changes in
a transaction are always isolated from other transactions and become visible only after the transaction has been
committed, i.e. the request to the <LS to="g">GraphQL</LS><LS to="r">REST</LS>
API has been processed and was successful. If a <LS to="g">GraphQL</LS><LS to="r">REST</LS>
API request results in any kind of error, the transaction is automatically rolled back.

</LS>

<LS to="j,g,r,c">

<Note type="warning">
Parallel transaction handling hasn't been finalized yet, and is scheduled to be finalized in
[issue #16](https://github.com/FgForrest/evitaDB/issues/16). Until this issue is resolved, you must ensure that only
a single client is writing to the catalog in parallel. Other clients may have been reading in parallel using read-only
sessions, but the writer must be only one.
</Note>

### Read-only vs. Read-Write sessions

evitaDB recognizes two types of sessions:

</LS>

<LS to="j,c">

<dl>
    <dt>read-only (default)</dt>
	<dd>Read-only sessions are opened by calling the <LS to="j">`queryCatalog`</LS><LS to="c">`QueryCatalog`</LS> method. No write operations are allowed in a
    read-only session. This also allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling the <LS to="j">`updateCatalog`</LS><LS to="c">`UpdateCatalog`</LS> method</dd>
</dl>

</LS>
<LS to="g">

<dl>
    <dt>read-only</dt>
    <dd>Read-only sessions are opened by calling GraphQL queries, i.e. `getCollectionName`, `listCollectionName`,
    `queryCollectionName` and so on. No write operations are allowed in a read-only session
    which allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling GraphQL mutations, i.e. `upsertCollectionName`, `deleteCollectionName`
    and so on.</dd>
</dl>

</LS>
<LS to="r">

<dl>
    <dt>read-only</dt>
    <dd>Read-only sessions are opened by calling endpoints then only return data, typically endpoints ending with `/get`,
    `/list`, `/query` and so on. No write operations are allowed in a read-only session
    which allows evitaDB to optimize its behavior when working with the database.</dd>
    <dt>read-write</dt>
    <dd>Read-write sessions are opened by calling endpoints modify any data.</dd>
</dl>

</LS>

<LS to="j,g,r,c">

In the future, the read-only sessions can be distributed to multiple read nodes, while the read-write sessions must
talk to the master node.

</LS>

<LS to="j,c">

#### Unsafe session lifecycle

We recommend to open sessions using <LS to="j">`queryCatalog` / `updateCatalog`</LS>
<LS to="c">`QueryCatalog` / `UpdateCatalog`</LS> methods that accept a lambda function to execute
your business logic. This way evitaDB can safely handle the lifecycle management of *sessions* & *transactions*.
This approach is not always acceptable - for example, if your application needs to be integrated into an existing
framework that only provides a lifecycle callback methods, there is no way to "wrap" the entire business logic in
the lambda function.

That's why there is an alternative - not so secure - approach to handling sessions and transactions:

<SourceCodeTabs requires="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java" langSpecificTabOnly local>

[Manual session and transaction handling](/documentation/user/en/use/api/example/manual-transaction-management.java)
</SourceCodeTabs>

<Note type="warning">
If you use manual *session / transaction* handling, you must ensure that for every scope opening there is
a corresponding closing (even if an exception occurs during your business logic call).
</Note>

Both <LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS> and
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/TransactionContract.java</SourceClass> implement Java
`Autocloseable`</LS><LS to="c"><SourceClass>EvitaDB.Client/EvitaClientTransaction.cs</SourceClass> implement C# `IDisposable`</LS>
interface, so you can use them this way:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Get advantage of Autocloseable behaviour](/documentation/user/en/use/api/example/autocloseable-transaction-management.java)
</SourceCodeTabs>

This approach is safe, but has the same disadvantage as using <LS to="j">`queryCatalog` / `updateCatalog`</LS>
<LS to="c">`QueryCatalog` / `UpdateCatalog`</LS> methods - you need to
have all the business logic executable within the same block.

#### Dry-run session

For testing purposes, there is a special flag that can be used when opening a new session - a **dry run** flag:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-test-catalog.java" langSpecificTabOnly local>

[Opening dry-run session](/documentation/user/en/use/api/example/dry-run-session.java)
</SourceCodeTabs>

In this session, all transactions will automatically have a *rollback* flag set when they are opened, without the need
to set the rollback flag manually. This fact greatly simplifies
[transaction rollback on test teardown pattern](http://xunitpatterns.com/Transaction%20Rollback%20Teardown.html) when
implementing your tests, or can be useful if you want to ensure that the changes are not committed in a particular
session, and you don't have easy access to the places where the transaction is opened.

</LS>

<LS to="j,g,r,c">

### Upsert

</LS>

<LS to="j,c">

It's expected that most of the entity instances will be created by the evitaDB service classes - such as
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass></LS>
Anyway, there is also the [possibility of creating them directly](#creating-entities-in-detached-mode).

Usually the entity creation will look like this:

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Creating new entity example](/documentation/user/en/use/api/example/create-new-entity.java)
</SourceCodeTabs>

This way, the created entity can be immediately checked against the schema. This form of code is a condensed version,
and it may be split into several parts, which will reveal the "builder" used in the process.

When you need to alter existing entity, you first fetch it from the server, open for writing (which converts it to
the builder wrapper), modify it, and finally collect the changes and send them to the server.

<SourceCodeTabs requires="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Updating existing entity example](/documentation/user/en/use/api/example/update-existing-entity.java)
</SourceCodeTabs>

<Note type="info">

The <LS to="j">`upsertVia`</LS><LS to="c">`UpsertVia`</LS>
method is a shortcut for calling <LS to="j">`session.upsertEntity(builder.buildChangeSet())`</LS>
<LS to="c">`session.UpsertEntity(builder.BuildChangeSet())`</LS>. If you look at the
<LS to="j"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/BuilderContract.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IBuilder.cs</SourceClass></LS> you'll
see, that you can call on it either:

<LS to="j">

<dl>
    <dt>`buildChangeSet`</dt>
    <dd>Creates a stream of *mutations* that represent the changes you've made to the immutable object.</dd>
    <dt>`toInstance`</dt>
    <dd>Creates a new version of the immutable entity object with all changes applied. This allows you to create a
    new instance of the object locally without sending the changes to the server. When you fetch the same instance
    from the server again, you'll see that none of the changes have been applied to the database entity.</dd>
</dl>

</LS>
<LS to="c">

<dl>
	<dt>`BuildChangeSet`</dt>
	<dd>Creates a stream of *mutations* that represent the changes you've made to the immutable object.</dd>
	<dt>`ToInstance`</dt>
	<dd>Creates a new version of the immutable entity object with all changes applied. This allows you to create a
		new instance of the object locally without sending the changes to the server. When you fetch the same instance
		from the server again, you'll see that none of the changes have been applied to the database entity.</dd>
</dl>

</LS>

</Note>

<LS to="j">

<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/mutation/EntityMutation.java</SourceClass> or
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/BuilderContract.java</SourceClass> can be
passed to <SourceClass>evita_api/src/main/java/io/evitadb/api/EvitaSessionContract.java</SourceClass> `upsert` method,
which returns
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
containing only entity type and (possibly assigned) primary key information. You can also use the `upsertAndFetchEntity`
method, which inserts or creates the entity and returns its body in the form and size you specify in your `require`
argument.

</LS>
<LS to="c">
<SourceClass>EvitaDB.Client/Models/Data/Mutations/IEntityMutation.cs</SourceClass> or
<SourceClass>EvitaDB.Client/Models/Data/IBuilder.cs</SourceClass> can be
passed to <SourceClass>EvitaDB.Client/EvitaClientSession.cs</SourceClass> `Upsert` method,
which returns <SourceClass>EvitaDB.Client/Models/Data/Structure/EntityReference.cs</SourceClass>
containing only entity type and (possibly assigned) primary key information. You can also use the `UpsertAndFetchEntity`
method, which inserts or creates the entity and returns its body in the form and size you specify in your `Require`
argument.
</LS>

</LS>

<LS to="j,c">

#### Creating entities in detached mode

Entity instances can be created even if no evitaDB instance is available:

<SourceCodeTabs langSpecificTabOnly>
[Detached instantiation example](/documentation/user/en/use/api/example/detached-instantiation.java)
</SourceCodeTabs>

Although you will probably only take advantage of this approach when writing test cases, it still allows you to create
a stream of mutations that can be sent to and processed by the evitaDB server once you manage to get its instance.

There is an analogous builder that takes an existing entity and tracks changes made to it.

<SourceCodeTabs requires="/documentation/user/en/use/api/example/detached-existing-entity-preparation.java" langSpecificTabOnly>

[Detached existing entity example](/documentation/user/en/use/api/example/detached-existing-entity-instantiation.java)
</SourceCodeTabs>

</LS>

<LS to="g,r">

In the <LS to="g">GraphQL</LS><LS to="r">REST</LS> API,
there is no way to send full entity object to the server to be stored. Instead, you send a collection
of mutations that add, change, or remove individual data from an entity (new or existing one). Similarly to how the schema
is defined in the <LS to="g">GraphQL</LS><LS to="r">REST</LS> API.

<Note type="question">

<NoteTitle toggles="true">

##### Why do we use the mutation approach for entity definition?
</NoteTitle>

We know that this approach is not very user-friendly. However, the idea behind this approach is to provide a simple and versatile
way to programmatically build an entity with transactions in mind (in fact, this is how evitaDB works internally,
so the collection of mutations is passed directly to the engine on the server). It is expected that the developer
using the <LS to="g">GraphQL</LS><LS to="r">REST</LS> API
will create a library with e.g. entity builders that will generate the collection of mutations for the entity definition
(see Java API for inspiration).

</Note>

<LS to="g">

You can create a new entity or update an existing one using the [catalog data API](/documentation/user/en/use/connectors/graphql.md#graphql-api-instances)
at the `https://your-server:5555/gql/evita` URL. This API contains `upsertCollectionName` GraphQL mutations for each
[entity collection](/documentation/user/en/use/data-model.md#collection) that are customized to collections'
[schemas](/documentation/user/en/use/schema.md#entity). These mutations take a collection of evitaDB mutations which define
the changes to be applied to an entity. In one go, you can then retrieve the entity with the changes applied by defining
return data.

</LS>
<LS to="r">

You can create a new entity or update an existing one using the [catalog API](/documentation/user/en/use/connectors/rest.md#rest-api-instances)
at a collection endpoint, for example `https://your-server:5555/test/evita/product` with `PUT` HTTP method.
There endpoints are customized to collections' [schemas](/documentation/user/en/use/schema.md#entity). These endpoints take a
collection of evitaDB mutations which define the changes to be applied to an entity. In one go, you can then retrieve the
entity with the changes applied by defining requirements.

</LS>

<SourceCodeTabs requires="/documentation/user/en/get-started/example/complete-startup.java,/documentation/user/en/get-started/example/define-catalog-with-schema.java" langSpecificTabOnly local>

[Creating new entity example](/documentation/user/en/use/api/example/create-new-entity.graphql)
</SourceCodeTabs>

Because these <LS to="g">GraphQL mutations</LS><LS to="r">endpoints</LS>
are also for updating existing entities, evitaDB will automatically
either create a new entity with specified mutations (and possibly a primary key) or update an existing one if a primary key
of an existing entity is specified. You can further customize the behavior of the mutation by specifying the `entityExistence`
argument.

<SourceCodeTabs requires="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Updating existing entity example](/documentation/user/en/use/api/example/update-existing-entity.graphql)
</SourceCodeTabs>

</LS>

<LS to="j,g,r,c">

### Removal

</LS>

<LS to="j,r,c">

The easiest way how to remove an entity is by its *primary key*. However, if you need to remove multiple entities at
once you need to define a query that will match all the entities to remove:

</LS>
<LS to="g">

To remove one or multiple entities, you need to define a query that will match all the entities to remove:

</LS>

<LS to="j,g,r,c">

<SourceCodeTabs requires="/documentation/user/en/use/api/example/finalization-of-warmup-mode.java,/documentation/user/en/get-started/example/create-small-dataset.java" langSpecificTabOnly local>

[Removing all entities which name starts with `A`](/documentation/user/en/use/api/example/delete-entities-by-query.java)
</SourceCodeTabs>

</LS>

<LS to="j,c">

The <LS to="j">`deleteEntities`</LS><LS to="c">`DeleteEntities`</LS> method returns the count of removed entities.
If you want to return bodies of deleted entities, you can use alternative method <LS to="j">`deleteEntitiesAndReturnBodies`</LS><LS to="c">`DeleteEntitiesAndReturnBodies`</LS>.

</LS>

<LS to="g,r">

<LS to="g">The delete mutation</LS><LS to="r">Both deletion endpoints</LS>
can return entity bodies, so you can define the return structure of data as you need as if you were fetching entities in
usual way.

</LS>

<LS to="j,r,g,c">

<Note type="warning">

evitaDB may not remove all entities matched by the filter part of the query. The removal of entities is subject to the
logic of the <LS to="j,r">`require` conditions [`page` or `strip`](../../query/requirements/paging.md)</LS>
<LS to="c">`Require` conditions [`Page` or `Strip`](../../query/requirements/paging.md)</LS>
<LS to="g">pagination arguments [`offset` and `limit`](../../query/requirements/paging.md)</LS>.
Even if you omit the these completely, implicit pagination <LS to="j,r">(`page(1, 20)`)</LS>
<LS to="c">(`Page(1, 20)`)</LS><LS to="g">(`offset: 1, limit: 20`)</LS>
will be used. If the number of entities removed is equal to the size of the defined paging, you should repeat the removal command.

Massive entity removal is better to execute in multiple transactional rounds rather than in one big transaction<LS to="g,r">, i.e. multiple requests</LS>.
This is at least a good practice, because large and long-running transactions increase probability of conflicts that lead to
rollbacks of other transactions.

</Note>

</LS>

<LS to="j,c">

If you are removing a hierarchical entity, and you need to remove not only the entity itself, but its entire subtree,
you can take advantage of <LS to="j">`deleteEntityAndItsHierarchy`</LS>
<LS to="c">`DeleteEntityAndItsHierarchy`</LS> method.
By default, the method returns the number of entities removed, but alternatively it can return the body of the removed root
entity with the size and form you specify in
its <LS to="j">`require`</LS><LS to="c">`Require`</LS> argument.
If you remove only the root node without removing its children, the children will become
[orphans](../schema.md#orphan-hierarchy-nodes), and you will need to reattach them to another existing parent.

</LS>

<LS to="j,g,r,c">

<Note type="question">

<NoteTitle toggles="true">

##### How does evitaDB handle the removals internally?
</NoteTitle>

No data is actually removed once it is created and stored. If you remove the reference/attribute/whatever, it remains
in the entity and is just marked as `dropped`. See the
<LS to="j,g,r"><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/Droppable.java</SourceClass></LS>
<LS to="c"><SourceClass>EvitaDB.Client/Models/Data/IDroppable.cs</SourceClass></LS>
interface implementations.

There are a few reasons for this decision:

1. it's good to have the last known version of the data around when things go wrong, so we can still recover to the
   previous state.
2. it allows us to track the changes in the entity through its lifecycle for debugging purposes
3. it is consistent with our *append-only* storage approach where we need to write [tombstones](https://en.wikipedia.org/wiki/Tombstone_(data_store)) in case of entity or other object removals

</Note>

</LS>

<LS to="j">

## Custom contracts

Similar to [query data using custom contracts](query-data.md#custom-contracts), you can also create new entities and
modify existing ones using custom contracts. This allows you to completely bypass working with the evitaDB internal
model and stick to your own - domain specific - model. When modeling your read/write contracts, we recommend to stick
to the [sealed/open principle](../connectors/java.md#data-modeling-recommendations).

Your write contract will likely extend read contracts using annotations described in
the [schema API](schema-api.md#schema-controlling-annotations) and/or [query data API](query-data.md#custom-contracts).
If you follow the Java Beans naming convention, you don't need to use annotations on write methods, but if you want to
use different names or clarify your write contract, just use the [query data annotations](query-data.md#custom-contracts)
on write methods. In some cases, you may want to use the following additional annotations:

<dl>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/CreateWhenMissing.java</SourceClass></dt>
    <dd>
        Annotation can be used on methods accepting [Consumer](/https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/function/Consumer.html)
         type or methods that return/accept your custom contract. When the method is called, the automatic
         implementation logic will create a new instance of this contract for you to work with. The new instance is
         persisted along with the entity that was responsible for creating it (see details in the following paragraphs).
    </dd>
    <dt><SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/RemoveWhenExists.java</SourceClass></dt>
    <dd>
        Annotation can be used on methods and will trigger the removal of the specific entity data - attribute,
         associated data, parent reference, entity reference, or price. The removal only affects the entity itself,
         never the target entity. Physical removal is only performed when the entity itself is upserted to the database.
    </dd>
</dl>

Write methods can return several types (in some cases the supported list is even longer - but these cases are described
in the appropriate sections):

- `void` - the method performs the modification and returns no value
- `selfType` - the method executes the modification and returns the reference to the contract itself, which allows
  to chain multiple write calls together ([builder pattern](https://blogs.oracle.com/javamagazine/post/exploring-joshua-blochs-builder-design-pattern-in-java))

In the following sections, we'll describe the behavior of the automatic implementation logic in detail and with
examples:

<Note type="info">

The examples contain only interface/class definitions since the Java record is read-only. Examples describe
a read/write contract in the same class, which is the simpler approach, but not entirely safe in terms of parallel
access to the data. If you want to follow the recommended [sealed/open principle](../connectors/java.md#data-modeling-recommendations)
you should declare `extends SealedEntity<MyEntity, MyEntityEditor>` in the read interface contract
and `extends InstanceEditor<MyEntity>` in the write interface contract.

</Note>

<Note type="warning">

When you create new (non-existing) entities using methods annotated with `@CreateWhenMissing`, these entities are held
in local memory and their persistence is delayed until the entity that created them is persisted using
the `upsertDeeply` method. If you don't call this method, or call the simple `upsert` method, the created entities and
references to them will be lost. You may also want to persist them separately or before the main entity that created
them. In this case you can call the `upsert` method on them directly.

The API allows you to create an infinite depth chain of dependent entities and the `upsertDeeply` / `upsert` logic will
work correctly at all levels. If you create entity `A` in which you created a reference to entity `B` in which you
created another reference to entity `C`, the `upsertDeeply` method called on entity `A` will persist all three entities
in the correct order (`C`, `B`, `A`). If you call the `upsertDeeply` method on entity `B`, it will only persist
the sub-entities in the correct order (`C`, `B`). You can also manually call the `upsert` method on entity `C`, then `B`
and finally `A`. However, if you persist entity `A` without first persisting entities `B` and `C`, the reference between
`A` and `B` will be dropped. You can still call `upsertDeeply` on entity `B`, which will keep the reference between `B`
and `C`.

</Note>

### Primary key

The primary key might be assigned by evitaDB, but can be set also from the outside. To allow setting the primary key you
need to declare method accepting number data type (usually
[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html)) and annotate it with the `@PrimaryKey`
or `@PrimaryKeyRef` annotation:

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/primary-key-read-interface.java" variants="interface|class">

[Example interface with primary key modifier](/documentation/user/en/use/api/example/primary-key-write-interface.java)

</SourceAlternativeTabs>

### Attributes

To set the entity or reference attribute, you must use the appropriate data type and annotate it with the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Attribute.java</SourceClass>
or <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AttributeRef.java</SourceClass>
annotation or have a corresponding getter (or field) with this annotation in the same class.

If the attribute represents a multi-value type (array), you can also wrap it in [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(or its specializations [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
or [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)) or pass it as simple array of values. The rules apply to both for entity and reference attributes:

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/attribute-read-interface.java" variants="interface|class">

[Example interface with attribute modifier](/documentation/user/en/use/api/example/attribute-write-interface.java)

</SourceAlternativeTabs>

<Note type="info">

Java enum data types are automatically converted to evitaDB string data type using the `name()` method and vice versa
using the `valueOf()` method.

</Note>

### Associated Data

To set the entity associated data, you must use the appropriate data type and annotate it with the
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedData.java</SourceClass>
or <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/AssociatedDataRef.java</SourceClass>
annotation or have a corresponding getter (or field) with this annotation in the same class.

If the associated date represents a multi-value type (array), you can also wrap it in [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(or its specializations [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
or [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)) or pass it as simple array of values.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/associated-data-read-interface.java" variants="interface|class">

[Example interface with associated data modifier](/documentation/user/en/use/api/example/associated-data-write-interface.java)

</SourceAlternativeTabs>

If the method accepts ["non-supported data type"](../data-types.md#simple-data-types) evitaDB automatically converts the data
to ["complex data type"](../data-types.md#complex-data-types) using [documented deserialization rules](../data-types.md#deserialization).

### Prices

To set the entity prices, you could work with
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/PriceContract.java</SourceClass> data type or
pass all necessary dat in method parameters and annotate the methods with
the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Price.java</SourceClass>
annotation. You can set (create or update) a single price by its business key, which consists of:

- **`priceId`** - number datatype with external price identifier
- **`currency`** - [Currency](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Currency.html) or [string](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) datatype accepting 3-letter currency ISO code
- **`priceList`** - [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html) data type with price list name

or you can set all prices using the method that takes the array, [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html), [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html) or
[Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html) parameter with all entity prices.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/price-read-interface.java" variants="interface|class">

[Example interface with price modifier](/documentation/user/en/use/api/example/price-write-interface.java)

</SourceAlternativeTabs>

### Hierarchy

To set the hierarchy placement information of the entity (i.e., its parent), you must use either the numeric data
type, your own custom interface type, <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/SealedEntity.java</SourceClass>
or <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/structure/EntityReference.java</SourceClass>
data type and annotate it with the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ParentEntity.java</SourceClass>
annotation or have a corresponding getter (or field) with this annotation in the same class.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/parent-read-interface.java" variants="interface|class">

[Example interface with parent modifier](/documentation/user/en/use/api/example/parent-write-interface.java)

</SourceAlternativeTabs>

If you set the value to `NULL`, the entity becomes a root entity.

### References

To set the entity references, you must use either the numeric data type, your own custom interface type,
<SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/EntityReferenceContract.java</SourceClass>
or <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/ReferenceContract.java</SourceClass>
data type and annotate it with the <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/Reference.java</SourceClass>
or <SourceClass>evita_api/src/main/java/io/evitadb/api/requestResponse/data/annotation/ReferenceRef.java</SourceClass>
annotation or have a corresponding getter (or field) with this annotation in the same class.

If the reference has `ZERO_OR_MORE` or `ONE_OR_MORE` cardinality, you can also wrap it in [Collection](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Collection.html)
(or its specializations [List](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/List.html)
or [Set](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/Set.html)), or pass it as a simple array
to rewrite all the values at once. If the reference has `ZERO_OR_ONE` cardinality and you pass a `NULL` value,
the reference is automatically removed.

<SourceAlternativeTabs requires="documentation/user/en/use/api/example/reference-read-interface.java" variants="interface|class">

[Example interface with reference modifier](/documentation/user/en/use/api/example/reference-write-interface.java)

</SourceAlternativeTabs>

</LS>